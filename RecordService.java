package com.vtuber.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Grava a tela (com o overlay da Vtuber) + áudio do microfone e/ou do som interno (Android 10+). Salva em Filmes/VTuberLab.
 * Só microfone: o MediaRecorder grava tudo direto. Com som interno: o vídeo e o áudio (AudioCapture, que mistura
 * microfone + som interno) vão para arquivos temporários e são juntados no fim.
 * Usa os ajustes da aba de gravação (RecPrefs): resolução, FPS, qualidade, HEVC, áudio, contagem regressiva.
 * Tem pausa, cronômetro na notificação e para sozinha se o espaço do celular estiver acabando.
 */
public class RecordService extends Service {

    static final String ACTION_STOP = "com.vtuber.overlay.REC_STOP";
    static final String ACTION_PAUSE = "com.vtuber.overlay.REC_PAUSE";
    private static final String CHANNEL_ID = "vtuber_rec";
    private static final int NOTIF_ID = 2;
    private static final long MIN_FREE = 150L * 1024 * 1024;   // menos que isso: não começa / para

    private static volatile boolean recording = false, counting = false, paused = false;
    private static volatile long t0 = 0, pausedAcc = 0, pausedAt = 0;

    static boolean isRecording() { return recording; }
    /** Contagem regressiva em andamento (ainda não está gravando). */
    static boolean isCounting() { return counting; }
    static boolean isPaused() { return paused; }
    /** Juntando vídeo e áudio depois de parar (só com som interno): ainda não dá para gravar de novo. */
    static boolean isFinalizing() { return finalizing; }
    /** Tempo gravado até agora (sem contar as pausas). */
    static long elapsedMs() {
        if (!recording) return 0;
        long end = paused ? pausedAt : SystemClock.elapsedRealtime();
        return Math.max(0, end - t0 - pausedAcc);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri outUri;
    private File outFile;
    private boolean stopping = false;
    private boolean audio = false, landscape = true, lowSpace = false;
    private boolean useMic = false, useInternal = false, mixMode = false;
    private static volatile boolean finalizing = false;
    private AudioCapture audioCap;
    private File vidTmp, audTmp;
    private int cdShown = 0;
    private TextView cdView;

    private final Runnable cdTick = new Runnable() {
        @Override public void run() {
            if (!counting || stopping) return;
            if (cdShown > 1) {
                cdShown--;
                showCount(cdShown);
                notifyState();
                handler.postDelayed(this, 1000);
            } else {
                // some o número e espera um instante, para ele não aparecer no começo do vídeo
                removeCountView();
                handler.postDelayed(beginRun, 300);
            }
        }
    };
    private final Runnable beginRun = new Runnable() { @Override public void run() { beginRecording(); } };

    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!recording || stopping) return;
            if (RecPrefs.lowSpaceStop(RecordService.this) && freeBytes() < MIN_FREE) {
                lowSpace = true;
                stopRecording();
                return;
            }
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        if (finalizing) return START_NOT_STICKY;   // juntando vídeo e áudio: espera terminar
        if (ACTION_STOP.equals(act)) {
            if (recording) stopRecording();
            else if (counting) cancelCountdown();
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(act)) {
            if (recording) togglePause();
            else if (!counting) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || recording || counting) return START_NOT_STICKY;

        int code = intent.getIntExtra("code", 0);
        Intent data = (Intent) intent.getParcelableExtra("data");
        boolean perm = intent.getBooleanExtra("audio", false);
        useMic = perm && RecPrefs.mic(this);
        useInternal = perm && RecPrefs.internal(this) && Build.VERSION.SDK_INT >= 29;
        audio = useMic;
        mixMode = useInternal;
        landscape = intent.getBooleanExtra("landscape", true);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        stopping = false;
        lowSpace = false;
        counting = true;
        cdShown = 0;

        goForeground();   // precisa vir ANTES de getMediaProjection (Android 14+)
        try {
            openProjection(code, data);
            int cd = RecPrefs.countdownSecs(this);
            if (cd > 0) {
                cdShown = cd;
                showCount(cd);
                notifyState();
                handler.postDelayed(cdTick, 1000);
            } else {
                beginRecording();
            }
        } catch (Exception e) {
            fail(e);
        }
        return START_NOT_STICKY;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    // ---------- Notificação ----------
    private Notification.Action action(int icon, String label, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build();
    }

    private Notification buildNotification() {
        PendingIntent stopPi = PendingIntent.getService(this, 11, new Intent(this, RecordService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pausePi = PendingIntent.getService(this, 13, new Intent(this, RecordService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(this, 12, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openPi)
                .setOngoing(true);
        if (!recording) {
            b.setContentTitle(cdShown > 0 ? "A gravação começa em " + cdShown + "..." : "Preparando a gravação...")
                    .setContentText("Toque em Cancelar para desistir.")
                    .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", stopPi));
        } else {
            String mic = RecPrefs.audioLabel(mixMode, audio);
            if (paused) {
                b.setContentTitle("Gravação pausada").setContentText(mic + " Toque em Continuar para voltar.")
                        .addAction(action(android.R.drawable.ic_media_play, "Continuar", pausePi));
            } else {
                b.setContentTitle("Gravando a tela").setContentText(mic)
                        .setShowWhen(true).setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsedMs())
                        .addAction(action(android.R.drawable.ic_media_pause, "Pausar", pausePi));
            }
            b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPi));
        }
        return b.build();
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Gravação de tela", NotificationManager.IMPORTANCE_LOW));
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if ((useMic || useInternal) && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void notifyState() {
        try { getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification()); } catch (Exception ignored) { }
    }

    // ---------- Contagem regressiva (número grande no meio da tela, fora do vídeo) ----------
    private void showCount(int n) {
        try {
            if (wm == null || !Settings.canDrawOverlays(this)) return;
            if (cdView == null) {
                TextView t = new TextView(this);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(0xFFFFD43B);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 84);
                t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(0xD9121215);
                g.setStroke(dp(4), 0xFFFFD43B);
                t.setBackground(g);
                int d = dp(170);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(d, d,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.CENTER;
                wm.addView(t, lp);
                cdView = t;
            }
            cdView.setText(String.valueOf(n));
        } catch (Exception ignored) { }
    }

    private void removeCountView() {
        if (cdView == null) return;
        try { wm.removeView(cdView); } catch (Exception ignored) { }
        cdView = null;
    }

    private void cancelCountdown() {
        if (stopping) return;
        stopping = true;
        Toast.makeText(this, "Gravação cancelada.", Toast.LENGTH_SHORT).show();
        cleanup(false);
        stopForeground(true);
        stopSelf();
    }

    // ---------- Captura ----------
    private void openProjection(int code, Intent data) throws Exception {
        if (data == null) throw new Exception("permissão de captura ausente");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) throw new Exception("captura não autorizada");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (recording) stopRecording();
                        else if (counting) cancelCountdown();
                    }
                });
            }
        }, handler);
    }

    private static long freeBytes() {
        try { return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes(); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }

    private void fail(Exception e) {
        Toast.makeText(this, "Não consegui gravar a tela: " + Friendly.why(e), Toast.LENGTH_LONG).show();
        cleanup(false);
        stopForeground(true);
        stopSelf();
    }

    private void beginRecording() {
        if (stopping || projection == null) return;
        try {
            if (RecPrefs.lowSpaceStop(this) && freeBytes() < MIN_FREE)
                throw new Exception("o celular está com pouco espaço livre (menos de 150 MB).");
            try {
                prepareAndStart(false);
            } catch (Exception first) {
                // Se a captura já foi criada, não dá para tentar de novo (o Android 14 só deixa uma vez).
                if (display != null) throw first;
                discardAttempt();
                prepareAndStart(true);   // ajustes seguros: até 1080p, 30 FPS, H.264, microfone padrão
            }
        } catch (Exception e) {
            fail(e);
            return;
        }
        paused = false;
        pausedAcc = 0;
        t0 = SystemClock.elapsedRealtime();
        recording = true;
        counting = false;
        notifyState();
        handler.postDelayed(watch, 5000);
    }

    private void prepareAndStart(boolean safe) throws Exception {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        int[] wh = RecPrefs.size(this, m.widthPixels, m.heightPixels, landscape, safe);
        int w = wh[0], h = wh[1];
        int fps = safe ? 30 : RecPrefs.fps(this);
        boolean hevc = !safe && RecPrefs.hevc(this);

        // Som interno: prepara a captura antes. Se o aparelho não aceitar, grava só o microfone.
        audioCap = null;
        if (mixMode) {
            try {
                audTmp = new File(getCacheDir(), "rec_audio.m4a");
                audTmp.delete();
                AudioCapture ac = new AudioCapture(projection, useMic, true,
                        safe ? MediaRecorder.AudioSource.MIC : RecPrefs.micSource(this),
                        safe ? 44100 : RecPrefs.audioRate(this), safe ? 128000 : RecPrefs.audioBitrate(this), audTmp);
                ac.prepare();
                audioCap = ac;
            } catch (Exception e) {
                mixMode = false;
                audTmp = null;
                Toast.makeText(this, useMic ? "Não consegui gravar o som interno: gravando só o microfone."
                        : "Não consegui gravar o som interno: o vídeo vai ficar sem áudio.", Toast.LENGTH_LONG).show();
            }
        }
        boolean recAudio = audio && !mixMode;   // MediaRecorder só grava o áudio quando não há som interno

        MediaRecorder r = new MediaRecorder();
        recorder = r;
        if (recAudio) r.setAudioSource(safe ? MediaRecorder.AudioSource.MIC : RecPrefs.micSource(this));
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        r.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        if (recAudio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(safe ? 128000 : RecPrefs.audioBitrate(this));
            r.setAudioSamplingRate(safe ? 44100 : RecPrefs.audioRate(this));
        }
        r.setVideoSize(w, h);
        r.setVideoFrameRate(fps);
        r.setVideoEncodingBitRate(RecPrefs.videoBitrate(this, w, h, fps, safe));
        openOutput(r);
        r.prepare();
        display = projection.createVirtualDisplay("VTuberRec", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.getSurface(), null, handler);
        try {
            r.start();
        } catch (Exception e) {
            throw new Exception("o aparelho não aceitou essas opções de vídeo. Em Gravação, tente 1080p, 30 FPS e H.264.");
        }
        if (audioCap != null) {
            try { audioCap.start(); }
            catch (Exception e) {
                audioCap = null;
                Toast.makeText(this, "Não consegui iniciar o áudio: o vídeo vai ficar sem som.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openOutput(MediaRecorder r) throws Exception {
        if (mixMode) {   // vídeo sem áudio num arquivo temporário; o arquivo final é criado ao juntar
            vidTmp = new File(getCacheDir(), "rec_video.mp4");
            vidTmp.delete();
            r.setOutputFile(vidTmp.getAbsolutePath());
            return;
        }
        createFinalOutput("w");
        if (pfd != null) r.setOutputFile(pfd.getFileDescriptor());
        else r.setOutputFile(outFile.getAbsolutePath());
    }

    /** Cria o arquivo final (Filmes/VTuberLab). mode: "w" para o MediaRecorder, "rw" para juntar vídeo + áudio. */
    private void createFinalOutput(String mode) throws Exception {
        String name = "VTuberLab_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VTuberLab");
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (outUri == null) throw new Exception("não foi possível criar o arquivo");
            pfd = getContentResolver().openFileDescriptor(outUri, mode);
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VTuberLab");
            dir.mkdirs();
            outFile = new File(dir, name);
        }
    }

    /** Joga fora uma tentativa que falhou antes de criar a captura (o arquivo vazio é apagado). */
    private void discardAttempt() {
        try { if (recorder != null) { recorder.reset(); recorder.release(); } } catch (Exception ignored) { }
        recorder = null;
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        pfd = null;
        if (outUri != null) {
            try { getContentResolver().delete(outUri, null, null); } catch (Exception ignored) { }
            outUri = null;
        }
        if (outFile != null) { outFile.delete(); outFile = null; }
        if (audioCap != null) { audioCap.stop(); audioCap = null; }
        deleteTemps();
    }

    private void deleteTemps() {
        if (vidTmp != null) { vidTmp.delete(); vidTmp = null; }
        if (audTmp != null) { audTmp.delete(); audTmp = null; }
    }

    private void togglePause() {
        if (!recording || stopping || recorder == null) return;
        try {
            if (!paused) {
                recorder.pause();
                if (audioCap != null) audioCap.pause(true);
                pausedAt = SystemClock.elapsedRealtime();
                paused = true;
            } else {
                recorder.resume();
                if (audioCap != null) audioCap.pause(false);
                pausedAcc += SystemClock.elapsedRealtime() - pausedAt;
                paused = false;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Este aparelho não conseguiu pausar a gravação.", Toast.LENGTH_SHORT).show();
            return;
        }
        notifyState();
    }

    private void stopRecording() {
        if (stopping) return;
        stopping = true;
        long dur = elapsedMs();
        boolean ok = true;
        recording = false;
        paused = false;
        handler.removeCallbacks(watch);
        try { recorder.stop(); } catch (Exception e) { ok = false; }
        if (audioCap != null) { audioCap.stop(); audioCap = null; }   // fecha o arquivo de áudio
        if (ok && mixMode) {
            cleanup(true);              // solta captura e gravador; os temporários ficam para juntar
            finishMix(dur);
            return;
        }
        cleanup(ok);
        if (ok) {
            String where = Build.VERSION.SDK_INT >= 29 ? "Filmes/VTuberLab" : "Android/data/com.vtuber.overlay/files/Movies";
            Toast.makeText(this, (lowSpace ? "Pouco espaço no celular: a gravação foi salva e encerrada (" : "Gravação salva (")
                    + RecPrefs.fmtTime(dur) + ") em " + where, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Gravação muito curta, descartada.", Toast.LENGTH_SHORT).show();
        }
        stopForeground(true);
        stopSelf();
    }

    private void cleanup(boolean keep) {
        recording = false;
        counting = false;
        paused = false;
        handler.removeCallbacksAndMessages(null);
        removeCountView();
        try { if (audioCap != null) audioCap.stop(); } catch (Exception ignored) { }
        audioCap = null;
        try { if (display != null) display.release(); } catch (Exception ignored) { }
        try { if (recorder != null) { recorder.reset(); recorder.release(); } } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        display = null; recorder = null; projection = null; pfd = null;
        if (outUri != null) {
            try {
                if (keep) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, cv, null, null);
                } else getContentResolver().delete(outUri, null, null);
            } catch (Exception ignored) { }
            outUri = null;
        }
        if (outFile != null) { if (!keep) outFile.delete(); outFile = null; }
        if (!keep) deleteTemps();
    }

    // ---------- Juntar vídeo + áudio (só quando grava o som interno) ----------
    /** Junta os temporários em segundo plano, salva em Filmes/VTuberLab e encerra o serviço. */
    private void finishMix(final long dur) {
        finalizing = true;
        try {
            Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Salvando a gravação...")
                    .setContentText("Juntando vídeo e áudio. Não feche o app.")
                    .setOngoing(true);
            getSystemService(NotificationManager.class).notify(NOTIF_ID, b.build());
        } catch (Exception ignored) { }
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = muxFinal();
                handler.post(new Runnable() {
                    @Override public void run() {
                        finishOutput(ok);
                        deleteTemps();
                        String where = Build.VERSION.SDK_INT >= 29 ? "Filmes/VTuberLab" : "Android/data/com.vtuber.overlay/files/Movies";
                        Toast.makeText(RecordService.this, ok
                                ? (lowSpace ? "Pouco espaço no celular: a gravação foi salva e encerrada (" : "Gravação salva (")
                                    + RecPrefs.fmtTime(dur) + ") em " + where
                                : "Não consegui salvar a gravação com o áudio.", Toast.LENGTH_LONG).show();
                        finalizing = false;
                        stopForeground(true);
                        stopSelf();
                    }
                });
            }
        }, "VTuberMux").start();
    }

    /** Marca o arquivo final como pronto (ok) ou apaga o que ficou pela metade. */
    private void finishOutput(boolean keep) {
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        pfd = null;
        if (outUri != null) {
            try {
                if (keep) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, cv, null, null);
                } else getContentResolver().delete(outUri, null, null);
            } catch (Exception ignored) { }
            outUri = null;
        }
        if (outFile != null) { if (!keep) outFile.delete(); outFile = null; }
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Junta o vídeo temporário e o áudio temporário no arquivo final. Se o áudio faltar, salva só o vídeo. */
    private boolean muxFinal() {
        MediaExtractor ev = null, ea = null;
        MediaMuxer mux = null;
        boolean started = false;
        try {
            createFinalOutput("rw");
            mux = pfd != null ? new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    : new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            ev = new MediaExtractor();
            ev.setDataSource(vidTmp.getAbsolutePath());
            int vt = findTrack(ev, "video/");
            if (vt < 0) throw new Exception("sem vídeo");
            ev.selectTrack(vt);
            int mv = mux.addTrack(ev.getTrackFormat(vt));
            int ma = -1;
            if (audTmp != null && audTmp.length() > 0) {
                try {
                    ea = new MediaExtractor();
                    ea.setDataSource(audTmp.getAbsolutePath());
                    int at = findTrack(ea, "audio/");
                    if (at >= 0) { ea.selectTrack(at); ma = mux.addTrack(ea.getTrackFormat(at)); }
                    else { ea.release(); ea = null; }
                } catch (Exception e) { if (ea != null) { try { ea.release(); } catch (Exception ignored) { } } ea = null; ma = -1; }
            }
            mux.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // intercala vídeo e áudio pelo tempo de cada quadro
            while (true) {
                long tv = ev.getSampleTime();
                long ta = (ea != null && ma >= 0) ? ea.getSampleTime() : -1;
                if (tv < 0 && ta < 0) break;
                boolean pickV = ta < 0 || (tv >= 0 && tv <= ta);
                MediaExtractor ex = pickV ? ev : ea;
                int track = pickV ? mv : ma;
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) {
                    if (pickV) break;
                    ea.release(); ea = null;   // acabou o áudio; segue só com o vídeo
                    continue;
                }
                info.set(0, size, ex.getSampleTime(),
                        (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                mux.writeSampleData(track, buf, info);
                ex.advance();
            }
            mux.stop();
            started = false;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (mux != null) { if (started) mux.stop(); mux.release(); } } catch (Exception ignored) { }
            try { if (ev != null) ev.release(); } catch (Exception ignored) { }
            try { if (ea != null) ea.release(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeCountView();
        if (recording) {
            boolean mix = mixMode;
            boolean ok = true;
            try { recorder.stop(); } catch (Exception ignored) { ok = false; }
            if (audioCap != null) { audioCap.stop(); audioCap = null; }
            cleanup(ok);
            if (ok && mix) { boolean saved = muxFinal(); finishOutput(saved); deleteTemps(); }
        }
        else if (counting) cleanup(false);
        super.onDestroy();
    }
}
