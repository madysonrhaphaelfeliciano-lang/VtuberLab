package com.vtuber.overlay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Captura o microfone e/ou o som interno do celular (Android 10+), mistura os dois em um só
 * áudio estéreo e grava em AAC num arquivo .m4a temporário. O RecordService junta esse áudio
 * com o vídeo quando a gravação termina.
 *
 * Uso: new AudioCapture(...) -> prepare() (pode lançar exceção) -> start() -> pause(b) -> stop().
 */
final class AudioCapture {
    private static final int FRAMES = 1024;               // quadros de áudio por rodada (~21 ms)
    private static final float MIC_GAIN = 1.0f;
    private static final float SYS_GAIN = 0.8f;           // som interno um pouco mais baixo, para a voz aparecer

    private final MediaProjection projection;
    private final boolean useMic, useSys;
    private final int micSource, rate, bitrate;
    private final File out;

    private AudioRecord mic, sys;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private int track = -1;
    private boolean muxStarted = false;
    private Thread thread;
    private volatile boolean running = false, paused = false;
    private long frames = 0;                               // quadros gravados (sem contar a pausa)

    AudioCapture(MediaProjection projection, boolean useMic, boolean useSys, int micSource,
                 int rate, int bitrate, File out) {
        this.projection = projection;
        this.useMic = useMic;
        this.useSys = useSys;
        this.micSource = micSource;
        this.rate = rate;
        this.bitrate = bitrate;
        this.out = out;
    }

    /** Cria as fontes de áudio, o codificador e o arquivo. Lança exceção se algo não for aceito. */
    void prepare() throws Exception {
        try {
            if (useSys) {
                if (android.os.Build.VERSION.SDK_INT < 29) throw new Exception("som interno exige Android 10 ou mais novo");
                AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build();
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                int buf = Math.max(min, FRAMES * 4) * 4;
                sys = new AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(cfg)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(buf)
                        .build();
                if (sys.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("o aparelho não liberou o som interno");
            }
            if (useMic) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int buf = Math.max(min, FRAMES * 2) * 4;
                mic = new AudioRecord(micSource, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
                if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                    mic.release();
                    // "Sem filtro"/"Voz" nem sempre existem: tenta o microfone padrão
                    mic = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, buf);
                    if (mic.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("o microfone não abriu");
                }
            }
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (Exception e) {
            release();
            throw e;
        }
    }

    /** Começa a capturar. Lança exceção se alguma fonte não conseguir iniciar. */
    void start() throws Exception {
        try {
            if (sys != null) sys.startRecording();
            if (mic != null) mic.startRecording();
            codec.start();
        } catch (Exception e) {
            release();
            throw e;
        }
        running = true;
        thread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "VTuberAudio");
        thread.start();
    }

    void pause(boolean p) { paused = p; }

    /** Para a captura e fecha o arquivo de áudio (espera a thread terminar). */
    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(3000); } catch (InterruptedException ignored) { }
            thread = null;
        }
        release();
    }

    private void loop() {
        short[] micBuf = new short[FRAMES];
        short[] sysBuf = new short[FRAMES * 2];
        byte[] pcm = new byte[FRAMES * 4];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int nMic = 0, nSys = 0;
                if (mic != null) nMic = readFull(mic, micBuf, FRAMES);
                if (sys != null) nSys = readFull(sys, sysBuf, FRAMES * 2) / 2;
                if (paused) continue;                       // na pausa lê e joga fora, para o tempo não andar
                // Se uma das fontes falhar nesta rodada, ela entra como silêncio
                for (int i = 0; i < FRAMES; i++) {
                    float m = (mic != null && i < nMic) ? micBuf[i] * MIC_GAIN : 0f;
                    float l = (sys != null && i < nSys) ? sysBuf[i * 2] * SYS_GAIN : 0f;
                    float r = (sys != null && i < nSys) ? sysBuf[i * 2 + 1] * SYS_GAIN : 0f;
                    short ol = clip(l + m), or = clip(r + m);
                    pcm[i * 4] = (byte) (ol & 0xFF);
                    pcm[i * 4 + 1] = (byte) ((ol >> 8) & 0xFF);
                    pcm[i * 4 + 2] = (byte) (or & 0xFF);
                    pcm[i * 4 + 3] = (byte) ((or >> 8) & 0xFF);
                }
                feed(pcm, FRAMES * 4, false);
                drain(info, false);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                // manda o fim do áudio (tenta mais de uma vez se o codificador estiver ocupado)
                for (int i = 0; i < 25 && !feed(new byte[0], 0, true); i++) drain(info, false);
                drain(info, true);
            } catch (Exception ignored) { }
        }
    }

    /** Lê "n" amostras esperando (bloqueia). Devolve quantas leu; 0 se deu erro. */
    private static int readFull(AudioRecord r, short[] buf, int n) {
        int got = 0;
        while (got < n) {
            int k = r.read(buf, got, n - got);
            if (k <= 0) break;
            got += k;
        }
        return got;
    }

    private static short clip(float v) {
        if (v > 32767f) return 32767;
        if (v < -32768f) return -32768;
        return (short) v;
    }

    private boolean feed(byte[] data, int len, boolean eos) {
        int idx = codec.dequeueInputBuffer(20000);
        if (idx < 0) return false;
        ByteBuffer in = codec.getInputBuffer(idx);
        in.clear();
        if (len > 0) in.put(data, 0, len);
        long pts = frames * 1000000L / rate;
        if (!eos) frames += len / 4;
        codec.queueInputBuffer(idx, 0, len, pts, eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        return true;
    }

    private void drain(MediaCodec.BufferInfo info, boolean untilEos) {
        int guard = 0;
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, untilEos ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || ++guard > 300) return;     // espera o fim por até ~3 s
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxStarted) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                }
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer o = codec.getOutputBuffer(idx);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (o != null && info.size > 0 && !config && muxStarted) {
                o.position(info.offset);
                o.limit(info.offset + info.size);
                muxer.writeSampleData(track, o, info);
            }
            codec.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    private void release() {
        try { if (sys != null) { sys.stop(); } } catch (Exception ignored) { }
        try { if (sys != null) { sys.release(); } } catch (Exception ignored) { }
        try { if (mic != null) { mic.stop(); } } catch (Exception ignored) { }
        try { if (mic != null) { mic.release(); } } catch (Exception ignored) { }
        sys = null; mic = null;
        try { if (codec != null) { codec.stop(); } } catch (Exception ignored) { }
        try { if (codec != null) { codec.release(); } } catch (Exception ignored) { }
        codec = null;
        try { if (muxer != null && muxStarted) muxer.stop(); } catch (Exception ignored) { }
        try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
        muxer = null;
        muxStarted = false;
    }
}
