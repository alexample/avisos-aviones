package es.avisosaviones;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colores y piezas de interfaz comunes. */
final class Ui {
    static final int FONDO = Color.parseColor("#F3F5F9");
    static final int TARJETA = Color.WHITE;
    static final int AZUL_OSCURO = Color.parseColor("#0B1F3A");
    static final int AZUL = Color.parseColor("#1565C0");
    static final int AZUL_CLARO = Color.parseColor("#E3EEFB");
    static final int TEXTO = Color.parseColor("#14213D");
    static final int TEXTO_SUAVE = Color.parseColor("#64748B");
    static final int BORDE = Color.parseColor("#D9E1EC");
    static final int VERDE = Color.parseColor("#16A34A");
    static final int ROJO = Color.parseColor("#DC2626");

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable redondeado(int color, float radioDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radioDp));
        return g;
    }

    static GradientDrawable contorno(int relleno, int borde, float radioDp, Context c) {
        GradientDrawable g = redondeado(relleno, radioDp, c);
        g.setStroke(dp(c, 1), borde);
        return g;
    }

    /** Fondo con efecto al pulsar. */
    static RippleDrawable pulsable(int color, float radioDp, Context c) {
        return new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22000000")),
                redondeado(color, radioDp, c), null);
    }

    static LinearLayout.LayoutParams ancho(int margenArribaDp, Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, margenArribaDp);
        return lp;
    }

    static TextView texto(Context c, String s, float sp, int color, boolean negrita) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (negrita) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    /** Tarjeta blanca con título opcional. */
    static LinearLayout tarjeta(Context c, String titulo, String subtitulo) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        t.setPadding(p, p, p, p);
        t.setBackground(redondeado(TARJETA, 18, c));
        t.setElevation(dp(c, 1.5f));
        if (titulo != null) t.addView(texto(c, titulo, 17, TEXTO, true));
        if (subtitulo != null) {
            TextView s = texto(c, subtitulo, 13, TEXTO_SUAVE, false);
            s.setPadding(0, dp(c, 2), 0, dp(c, 4));
            t.addView(s);
        }
        return t;
    }

    /** Botón grande relleno. */
    static TextView botonPrincipal(Context c, String s, int color) {
        TextView b = texto(c, s, 17, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), dp(c, 15), dp(c, 16), dp(c, 15));
        b.setBackground(pulsable(color, 16, c));
        b.setClickable(true);
        return b;
    }

    /** Botón secundario con borde. */
    static TextView botonSecundario(Context c, String s) {
        TextView b = texto(c, s, 15, AZUL, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#221565C0")),
                contorno(Color.WHITE, BORDE, 14, c), null));
        b.setClickable(true);
        return b;
    }

    /** Chip seleccionable. */
    static TextView chip(Context c, String s) {
        TextView t = texto(c, s, 14, TEXTO, false);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setClickable(true);
        marcarChip(c, t, false);
        return t;
    }

    static void marcarChip(Context c, TextView t, boolean on) {
        t.setBackground(on ? redondeado(AZUL, 20, c) : contorno(Color.WHITE, BORDE, 20, c));
        t.setTextColor(on ? Color.WHITE : TEXTO);
    }

    /** Fila que envuelve sus hijos en varias líneas (para chips). */
    static ViewGroup filaChips(Context c) {
        return new FilaFlexible(c);
    }

    /** ViewGroup sencillo que coloca hijos en fila y salta de línea si no caben. */
    static final class FilaFlexible extends ViewGroup {
        private final int hueco;
        FilaFlexible(Context c) { super(c); hueco = dp(c, 8); }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int ancho = MeasureSpec.getSize(wSpec);
            int x = 0, y = 0, altoFila = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                measureChild(v, MeasureSpec.makeMeasureSpec(ancho, MeasureSpec.AT_MOST), hSpec);
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > ancho) { x = 0; y += altoFila + hueco; altoFila = 0; }
                x += w + hueco;
                altoFila = Math.max(altoFila, h);
            }
            setMeasuredDimension(ancho, y + altoFila);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int ancho = r - l;
            int x = 0, y = 0, altoFila = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > ancho) { x = 0; y += altoFila + hueco; altoFila = 0; }
                v.layout(x, y, x + w, y + h);
                x += w + hueco;
                altoFila = Math.max(altoFila, h);
            }
        }
    }
}
