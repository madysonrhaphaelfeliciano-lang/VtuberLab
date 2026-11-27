package com.vtuber.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;

/** Ajustes da gravação de tela (aba de gravação). Preferências no arquivo "rec". */
final class RecPrefs {
    private RecPrefs() { }

    static SharedPreferences p(Context c) { return c.getSharedPreferences("rec", Context.MODE_PRIVATE); }

    // ---- Opções (o índice salvo aponta para estas listas) ----
    static final String[] RES_NAMES = {"720p", "1080p", "Nativa"};
    static final int[] RES_SHORT = {720, 1080, 0};            // lado menor; 0 = tamanho real da tela
    static final String[] FPS_NAMES = {"24", "30", "60"};
    static final int[] FPS_VALUES = {24, 30, 60};
    static final String[] QUAL_NAMES = {"Econômica", "Padrão", "Alta", "Máxima"};
    static final float[] QUAL_BPP = {0.08f, 0.14f, 0.20f, 0.30f};   // bits por pixel por quadro
    static final String[] CODEC_NAMES = {"H.264", "HEVC"};
    static final String[] MIC_NAMES = {"Padrão", "Voz", "Sem filtro"};
    static final String[] AQ_NAMES = {"Normal", "Alta", "Máxima"};
    static final int[] AQ_BITRATE = {128000, 192000, 256000};
    static final int[] AQ_RATE = {44100, 48000, 48000};
    static final String[] CD_NAMES = {"Não", "3 s", "5 s", "10 s"};
    static final int[] CD_SECS = {0, 3, 5, 10};

    private static int idx(Context c, String k, int def, int n) {
        int v = p(c).getInt(k, def);
        return v < 0 || v >= n ? def : v;
    }

    static int res(Context c) { return idx(c, "res", 1, RES_NAMES.length); }
    static int fpsIdx(Context c) { return idx(c, "fps", 1, FPS_VALUES.length); }
    static int fps(Context c) { return FPS_VALUES[fpsIdx(c)]; }
    static int qual(Context c) { return idx(c, "qual", 2, QUAL_NAMES.length); }
    static int codec(Context c) { return idx(c, "codec", 0, CODEC_NAMES.length); }
    static boolean hevc(Context c) { return codec(c) == 1; }
    static boolean mic(Context c) { return p(c).getBoolean("mic", true); }
    /** Gravar o som interno do celular (Android 10+). */
    static boolean internal(Context c) { return android.os.Build.VERSION.SDK_INT >= 29 && p(c).getBoolean("internal", true); }
    /** Tem algum áudio ligado (microfone ou som interno)? */
    static boolean anyAudio(Context c) { return mic(c) || internal(c); }

    /** Texto curto do que entra no áudio do vídeo. */
    static String audioLabel(boolean internal, boolean mic) {
        if (internal && mic) return "Com microfone e som interno.";
        if (internal) return "Com som interno.";
        if (mic) return "Com áudio do microfone.";
        return "Sem áudio.";
    }
    static int micSrcIdx(Context c) { return idx(c, "micsrc", 0, MIC_NAMES.length); }
    static int aq(Context c) { return idx(c, "aq", 1, AQ_NAMES.length); }
    static int countdownIdx(Context c) { return idx(c, "cd", 1, CD_SECS.length); }
    static int countdownSecs(Context c) { return CD_SECS[countdownIdx(c)]; }
    static boolean lowSpaceStop(Context c) { return p(c).getBoolean("lowspace", true); }

    static void setInt(Context c, String k, int v) { p(c).edit().putInt(k, v).apply(); }
    static void setBool(Context c, String k, boolean v) { p(c).edit().putBoolean(k, v).apply(); }
    static void reset(Context c) { p(c).edit().clear().apply(); }

    /** Fonte do microfone escolhida (Sem filtro = UNPROCESSED, no Android 7+). */
    static int micSource(Context c) {
        switch (micSrcIdx(c)) {
            case 1: return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case 2: return MediaRecorder.AudioSource.UNPROCESSED;
            default: return MediaRecorder.AudioSource.MIC;
        }
    }

    /**
     * Tamanho do vídeo (largura, altura) para a tela real sw x sh. O formato da tela é mantido.
     * safe = ajustes seguros (no máximo 1080p) usados quando o aparelho recusa as opções escolhidas.
     */
    static int[] size(Context c, int sw, int sh, boolean landscape, boolean safe) {
        int a = Math.max(sw, sh), b = Math.min(sw, sh);
        int target = safe ? 1080 : RES_SHORT[res(c)];
        float k = 1f;
        if (target > 0 && b > target) k = target / (float) b;
        int cap = (!safe && target == 0) ? 3840 : 2560;      // limite do lado maior (codificadores comuns)
        if (a * k > cap) k = cap / (float) a;
        int w = Math.max(16, ((int) (a * k)) & ~1), h = Math.max(16, ((int) (b * k)) & ~1);
        return landscape ? new int[]{w, h} : new int[]{h, w};
    }

    static int videoBitrate(Context c, int w, int h, int fps, boolean safe) {
        float bpp = safe ? QUAL_BPP[2] : QUAL_BPP[qual(c)];
        // HEVC rende parecido com ~35% menos bits
        if (!safe && hevc(c)) bpp *= 0.65f;
        long v = (long) (w * (long) h * fps * bpp);
        return (int) Math.max(1_500_000L, Math.min(50_000_000L, v));
    }

    static int audioBitrate(Context c) { return AQ_BITRATE[aq(c)]; }
    static int audioRate(Context c) { return AQ_RATE[aq(c)]; }

    /** Tamanho estimado por minuto, em MB, para a tela real sw x sh (usa as opções atuais). */
    static float mbPerMinute(Context c, int sw, int sh, boolean landscape) {
        int[] wh = size(c, sw, sh, landscape, false);
        long bits = videoBitrate(c, wh[0], wh[1], fps(c), false) + (anyAudio(c) ? audioBitrate(c) : 0);
        return bits / 8f * 60f / 1_000_000f;
    }

    /** "1920×864 · 30 FPS" */
    static String summary(Context c, int sw, int sh, boolean landscape) {
        int[] wh = size(c, sw, sh, landscape, false);
        return wh[0] + "×" + wh[1] + " · " + fps(c) + " FPS";
    }

    /** Atalhos prontos: 0 Rápida, 1 Equilibrada, 2 Qualidade. */
    static void applyPreset(Context c, int which) {
        SharedPreferences.Editor e = p(c).edit();
        if (which == 0) e.putInt("res", 0).putInt("fps", 1).putInt("qual", 1).putInt("codec", 0);
        else if (which == 1) e.putInt("res", 1).putInt("fps", 1).putInt("qual", 2).putInt("codec", 0);
        else e.putInt("res", 1).putInt("fps", 2).putInt("qual", 3).putInt("codec", 1);
        e.apply();
    }

    /** Qual atalho pronto bate com as opções atuais (-1 = nenhum). */
    static int currentPreset(Context c) {
        int r = res(c), f = fpsIdx(c), q = qual(c), k = codec(c);
        if (r == 0 && f == 1 && q == 1 && k == 0) return 0;
        if (r == 1 && f == 1 && q == 2 && k == 0) return 1;
        if (r == 1 && f == 2 && q == 3 && k == 1) return 2;
        return -1;
    }

    static String fmtTime(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, x)
                : String.format(java.util.Locale.US, "%02d:%02d", m, x);
    }

    static String fmtSize(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9);
        if (bytes >= 1_000_000L) return String.format(java.util.Locale.US, "%.0f MB", bytes / 1e6);
        return Math.max(1, bytes / 1000) + " KB";
    }
}
