package com.vtuber.overlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Kit visual "Kawaii Pro" (preto e amarelo) usado nas Configurações, na aba de gravação e na tela da Live. */
final class Ui {
    private Ui() { }

    static final int BG = 0xFF121215, PANEL = 0xFF1B1B20, CARD = 0xFF26262E, BORDER = 0xFF34343F,
            TEXT = 0xFFF8F9FA, MUTED = 0xFFA6A8B3, YELLOW = 0xFFFFD43B, DANGER = 0xFFFF5C6C, REC = 0xFFFF4D5E;

    interface OnPick { void pick(int index); }
    interface OnToggle { void toggle(boolean on); }

    static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density + .5f); }

    static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable outlined(Context c, int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = rounded(c, color, radiusDp);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lpm(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** Cartão: fundo cinza-escuro de cantos redondos com borda fina. Já vem com margem embaixo. */
    static LinearLayout card(Context c) {
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setBackground(outlined(c, CARD, 20, BORDER, 1));
        k.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        k.setLayoutParams(lpm(c, -1, -2, 0, 0, 0, 10));
        return k;
    }

    /** Título de seção (pequeno, amarelo, em maiúsculas). */
    static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.ROOT), 11, YELLOW, true);
        t.setLetterSpacing(0.08f);
        t.setLayoutParams(lpm(c, -1, -2, 4, 8, 0, 6));
        return t;
    }

    /** Linha com título (e subtítulo opcional) à esquerda e um controle à direita. */
    static LinearLayout row(Context c, String title, String sub, View right) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(c, 6), 0, dp(c, 6));
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(c, title, 14, TEXT, true));
        if (sub != null) {
            TextView s = text(c, sub, 11, MUTED, false);
            s.setPadding(0, dp(c, 1), 0, 0);
            l.addView(s);
        }
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (right != null) {
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
            rp.leftMargin = dp(c, 10);
            r.addView(right, rp);
        }
        return r;
    }

    /** Título + opções em pílulas logo abaixo (a opção escolhida fica amarela). */
    static LinearLayout choice(Context c, String title, String sub, String[] names, int sel, OnPick cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 6), 0, dp(c, 6));
        box.addView(text(c, title, 14, TEXT, true));
        if (sub != null) {
            TextView s = text(c, sub, 11, MUTED, false);
            s.setPadding(0, dp(c, 1), 0, 0);
            box.addView(s);
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(c, 38));
        sp.topMargin = dp(c, 8);
        box.addView(segmented(c, names, sel, cb), sp);
        return box;
    }

    static LinearLayout segmented(final Context c, final String[] names, int sel, final OnPick cb) {
        final LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(c, BG, 999));
        seg.setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        final TextView[] items = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(c, names[i], 12, MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine();
            t.setPadding(dp(c, 2), 0, dp(c, 2), 0);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    paintSeg(c, items, idx);
                    if (cb != null) cb.pick(idx);
                }
            });
            items[i] = t;
            seg.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
        }
        paintSeg(c, items, sel);
        return seg;
    }

    private static void paintSeg(Context c, TextView[] items, int sel) {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == sel;
            items[i].setTextColor(on ? BG : MUTED);
            items[i].setBackground(on ? rounded(c, YELLOW, 999) : null);
        }
    }

    /** Botão arredondado: primário (amarelo) ou secundário (escuro com borda). */
    static TextView button(Context c, String label, boolean primary, View.OnClickListener l) {
        TextView b = text(c, label, 14, primary ? BG : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine();
        b.setMinHeight(dp(c, 40));
        b.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        b.setBackground(primary ? rounded(c, YELLOW, 999) : outlined(c, CARD, 999, BORDER, 2));
        b.setOnClickListener(l);
        return b;
    }

    /** Chip pequeno (ex.: atalhos prontos). Escolhido = amarelo. */
    static TextView chip(Context c, String label, boolean on, View.OnClickListener l) {
        TextView t = text(c, label, 12, on ? BG : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        t.setBackground(on ? rounded(c, YELLOW, 999) : outlined(c, BG, 999, BORDER, 1));
        t.setOnClickListener(l);
        return t;
    }

    /** Botão de ligar/desligar (trilho com bolinha que desliza). */
    static View toggle(Context c, boolean on, OnToggle cb) {
        return new Switch(c, on, cb);
    }

    private static final class Switch extends View {
        private boolean on;
        private float pos;   // 0 = desligado, 1 = ligado
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final OnToggle cb;

        Switch(Context c, boolean on, OnToggle cb) {
            super(c);
            this.on = on;
            this.pos = on ? 1f : 0f;
            this.cb = cb;
            setMinimumWidth(dp(c, 50));
            setMinimumHeight(dp(c, 30));
            setClickable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { set(!Switch.this.on, true); }
            });
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 50), dp(getContext(), 30));
        }

        private void set(boolean v, boolean notify) {
            if (v == on) return;
            on = v;
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
            a.setDuration(140);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator va) { pos = (Float) va.getAnimatedValue(); invalidate(); }
            });
            a.start();
            if (notify && cb != null) cb.toggle(on);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(blend(0xFF3A3A44, YELLOW, pos));
            cv.drawRoundRect(r, h / 2f, h / 2f, p);
            float pad = h * 0.14f, d = h - 2 * pad;
            float x = pad + (w - 2 * pad - d) * pos;
            p.setColor(blend(0xFFB9BBC6, BG, pos));
            cv.drawCircle(x + d / 2f, h / 2f, d / 2f, p);
        }

        private static int blend(int a, int b, float t) {
            int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
        }
    }

    /** Barra de progresso fina (ex.: espaço livre). */
    static View bar(Context c, float fraction, int color) {
        LinearLayout track = new LinearLayout(c);
        track.setBackground(rounded(c, BG, 999));
        View fill = new View(c);
        fill.setBackground(rounded(c, color, 999));
        float f = Math.max(0.03f, Math.min(1f, fraction));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, f));
        track.addView(new View(c), new LinearLayout.LayoutParams(0, -1, 1f - f));
        return track;
    }
}
