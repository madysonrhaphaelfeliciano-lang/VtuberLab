package com.vtuber.overlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Configurações do app: botões laterais, sala de espera na live, transições e mais. */
public class SettingsActivity extends Activity {
    private static final String[] TRANS = {"Nenhuma", "Suave", "Vídeo 1", "Vídeo 2", "Vídeo 3"};
    private boolean closing = false;
    private LinearLayout panelRef;
    private int panelW;

    @Override protected void onCreate(Bundle b) { super.onCreate(b); build(true); }

    private int dp(int v) { return Ui.dp(this, v); }

    /** Linha com botão de ligar/desligar salvo em SharedPreferences. */
    private View toggleRow(String title, String sub, final String file, final String key, boolean def) {
        final android.content.SharedPreferences sp = getSharedPreferences(file, MODE_PRIVATE);
        return Ui.row(this, title, sub, Ui.toggle(this, sp.getBoolean(key, def), on -> sp.edit().putBoolean(key, on).apply()));
    }

    private View divider() {
        View d = new View(this); d.setBackgroundColor(Ui.BORDER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1)); p.topMargin = dp(4); p.bottomMargin = dp(4);
        d.setLayoutParams(p);
        return d;
    }

    private void build(boolean animate) {
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> close());
        panelW = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), dp(390));
        final LinearLayout panel = new LinearLayout(this);
        panelRef = panel;
        panel.setOrientation(LinearLayout.VERTICAL); panel.setClickable(true);
        GradientDrawable pg = new GradientDrawable(); pg.setColor(Ui.BG);
        pg.setCornerRadii(new float[]{dp(28), dp(28), 0, 0, 0, 0, dp(28), dp(28)}); pg.setStroke(dp(2), Ui.BORDER);
        panel.setBackground(pg);
        panel.setPadding(dp(14), dp(16), dp(14), dp(10));
        root.addView(panel, new FrameLayout.LayoutParams(panelW, -1, Gravity.END));

        // Cabeçalho
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Configurações do app", 21, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView x = Ui.text(this, "✕", 16, Ui.BG, true); x.setGravity(Gravity.CENTER); x.setBackground(Ui.rounded(this, Ui.YELLOW, 999));
        x.setOnClickListener(v -> close());
        head.addView(x, new LinearLayout.LayoutParams(dp(42), dp(42)));
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(0, dp(8), 0, dp(8));

        box.addView(Ui.section(this, "Tela inicial"));
        LinearLayout c1 = Ui.card(this);
        c1.addView(toggleRow("Mostrar os botões laterais", "Dois toques numa área vazia também mostram ou escondem.", "settings", "side_buttons", true));
        box.addView(c1);

        box.addView(Ui.section(this, "Live"));
        LinearLayout c2 = Ui.card(this);
        c2.addView(toggleRow("Sala de espera ao iniciar", "A live começa numa tela de espera, sem mostrar seu celular.", "settings", "waiting_on_live", true));
        c2.addView(divider());
        c2.addView(toggleRow("Esconder o Vtuber", "Na sala de espera e na tela final.", "settings", "hide_wait_end", true));
        box.addView(c2);

        box.addView(Ui.section(this, "Transição entre salas"));
        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.choice(this, "Efeito", "Vídeos ainda não importados não podem ser escolhidos.", new String[]{"Nenhuma", "Suave", "Vídeo 1", "Vídeo 2", "Vídeo 3"}, AppSettings.transition(this), i -> pickTransition(i)));
        LinearLayout vids = new LinearLayout(this); vids.setOrientation(LinearLayout.HORIZONTAL); vids.setPadding(0, dp(8), 0, 0);
        for (int i = 1; i <= 3; i++) {
            final int slot = i;
            boolean has = videoFile(slot).exists();
            TextView b = Ui.chip(this, (has ? "Trocar " : "Importar ") + i, false, v -> pickVideo(slot));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = i < 3 ? dp(6) : 0;
            vids.addView(b, lp);
        }
        c3.addView(vids);
        TextView hint = Ui.text(this, "Vídeos de transição: MP4 de até 5 segundos.", 11, Ui.MUTED, false); hint.setPadding(0, dp(6), 0, 0);
        c3.addView(hint);
        box.addView(c3);

        box.addView(Ui.section(this, "Collab"));
        LinearLayout c4 = Ui.card(this);
        c4.addView(toggleRow("Conexão avançada (STUN)", "Ajuda os celulares a se encontrarem na internet.", "collab", "stun", true));
        box.addView(c4);

        box.addView(Ui.section(this, "Ajuda"));
        LinearLayout c5 = Ui.card(this);
        c5.addView(Ui.row(this, "Guia da primeira vez", "Rever o passo a passo inicial.", Ui.button(this, "Abrir", true, v -> startActivity(new Intent(this, OnboardingActivity.class)))));
        c5.addView(divider());
        c5.addView(Ui.row(this, "Créditos e licenças", "Projetos usados no app.", Ui.button(this, "Ver", false, v -> showCredits())));
        box.addView(c5);

        box.addView(Ui.section(this, "Restaurar"));
        LinearLayout c6 = Ui.card(this);
        TextView rb = Ui.button(this, "Restaurar", false, v -> askReset()); rb.setTextColor(Ui.DANGER);
        c6.addView(Ui.row(this, "Voltar ao padrão", "Não apaga personagens, cenários nem a chave da Live.", rb));
        box.addView(c6);

        TextView ver = Ui.text(this, versionText(), 11, Ui.MUTED, false); ver.setPadding(dp(4), dp(4), 0, dp(8));
        box.addView(ver);

        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false); sv.addView(box);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (animate) {
            panel.setTranslationX(panelW);
            panel.post(() -> panel.animate().translationX(0).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).start());
        }
    }

    private void close() {
        if (closing) return;
        closing = true;
        if (panelRef == null) { finish(); return; }
        panelRef.animate().translationX(panelW).setDuration(200).withEndAction(() -> { finish(); overridePendingTransition(0, 0); }).start();
    }

    @Override public void onBackPressed() { close(); }

    private void pickTransition(int n) {
        if (n >= 2 && !videoFile(n - 1).exists()) {
            Toast.makeText(this, TRANS[n] + " ainda não foi importado. Importe o vídeo primeiro.", Toast.LENGTH_SHORT).show();
            build(false);   // volta a marcar a opção que estava valendo
            return;
        }
        AppSettings.p(this).edit().putInt("transition", n).apply();
    }

    private android.app.AlertDialog.Builder dialog() {
        return new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
    }

    private void styleDialog(android.app.AlertDialog d) {
        try {
            d.getWindow().setBackgroundDrawable(Ui.outlined(this, Ui.PANEL, 24, Ui.BORDER, 2));
            for (int w : new int[]{android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE}) {
                android.widget.Button bt = d.getButton(w);
                if (bt != null) bt.setTextColor(Ui.YELLOW);
            }
        } catch (Exception ignored) { }
    }

    // ---------- Versão, créditos e restaurar padrão ----------
    private String versionText() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return "Versão instalada: VTuber Lab " + pi.versionName + " (build " + code + ")";
        } catch (Exception e) { return "VTuber Lab"; }
    }

    private void showCredits() {
        String t = versionText() + "\n\n"
            + "O VTuber Lab usa os projetos abaixo. Obrigado a todas as pessoas que os criam e mantêm.\n\n"
            + "LIVE (transmissão)\n"
            + "\u2022 RootEncoder, de pedroSG94, licença Apache 2.0.\n  github.com/pedroSG94/RootEncoder\n\n"
            + "COLLAB (conexão entre celulares)\n"
            + "\u2022 STUN: servidor público do Google (stun.l.google.com). Ele só ajuda os celulares a descobrirem o endereço de internet um do outro; nenhum vídeo, modelo ou movimento passa por ele.\n"
            + "\u2022 WebRTC, que vem no Android System WebView (Chromium), licença BSD de 3 cláusulas.\n\n"
            + "RASTREAMENTO DO ROSTO\n"
            + "\u2022 MediaPipe (Face Landmarker), do Google, licença Apache 2.0. É carregado pela internet quando a câmera liga.\n\n"
            + "BASE DO APP\n"
            + "\u2022 AndroidX WebKit e outras bibliotecas AndroidX, do Google, licença Apache 2.0.\n"
            + "\u2022 Kotlin, da JetBrains, licença Apache 2.0 (usado pelo RootEncoder).\n\n"
            + "CHAT DA LIVE\n"
            + "\u2022 O chat usa a página de chat do YouTube. YouTube é marca da Google LLC. O VTuber Lab é independente e não é afiliado, patrocinado nem aprovado pelo YouTube ou pelo Google.\n\n"
            + "Os textos completos das licenças estão nos sites de cada projeto.";
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(13); tv.setTextColor(Ui.TEXT); tv.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        styleDialog(dialog().setTitle("Créditos e licenças").setView(sv).setPositiveButton("Fechar", null).show());
    }

    private void askReset() {
        styleDialog(dialog()
            .setTitle("Restaurar configurações?")
            .setMessage("Isto volta as opções desta tela (botões laterais, sala de espera na live, esconder o Vtuber, transição, STUN, aparência do chat e camada dos acessórios) ao padrão.\n\n"
                + "NÃO apaga seus personagens, cenários, acessórios, vídeos de transição nem a chave da Live.")
            .setPositiveButton("Restaurar", (d, w) -> resetAll())
            .setNegativeButton("Cancelar", null)
            .show());
    }

    private void resetAll() {
        boolean onb = AppSettings.onboardingDone(this);
        AppSettings.p(this).edit().clear().putBoolean("onboarding_done", onb).apply();
        getSharedPreferences("collab", MODE_PRIVATE).edit().remove("stun").apply();
        android.content.SharedPreferences cp = getSharedPreferences("chat", MODE_PRIVATE);
        String vid = cp.getString("video", "");
        cp.edit().clear().putString("video", vid).apply();   // guarda só o link da live; a aparência volta ao padrão
        AppSettings.main(this).edit().remove("acc_back").remove("rec_landscape").remove("rec_orient").apply();
        if (ChatService.isRunning()) startService(new Intent(this, ChatService.class).setAction(ChatService.ACTION_STYLE));
        Toast.makeText(this, "Configurações restauradas para o padrão.", Toast.LENGTH_SHORT).show();
        build(false);
    }

    private File videoFile(int slot) { return new File(new File(getFilesDir(), "transitions"), "t" + slot + ".mp4"); }

    private void pickVideo(int slot) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("video/mp4");
        startActivityForResult(i, 100 + slot);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req < 101 || req > 103 || res != RESULT_OK || data == null || data.getData() == null) return;
        final int slot = req - 100;
        final Uri uri = data.getData();
        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                MediaMetadataRetriever mr = new MediaMetadataRetriever();
                long dur = 0;
                try { mr.setDataSource(SettingsActivity.this, uri); String ds = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); dur = ds == null ? 0 : Long.parseLong(ds); }
                finally { try { mr.release(); } catch (Exception ignore) { } }
                if (dur <= 0) throw new Exception("não consegui ler o vídeo");
                if (dur > 5300) throw new Exception("o vídeo tem " + (dur / 1000) + " s e o limite é 5 s");
                File dir = new File(getFilesDir(), "transitions"); dir.mkdirs();
                InputStream in = getContentResolver().openInputStream(uri);
                FileOutputStream out = new FileOutputStream(videoFile(slot));
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close(); out.close();
                msg = "Vídeo " + slot + " importado.";
            } catch (Exception e) { msg = "Falha: " + e.getMessage(); }
            final String m = msg;
            runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(SettingsActivity.this, m, Toast.LENGTH_LONG).show(); build(false); } });
        } }).start();
    }
}
