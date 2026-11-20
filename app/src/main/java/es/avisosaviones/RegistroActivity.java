package es.avisosaviones;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Muestra el registro de actividad, con botones para actualizar, compartir y borrar. */
public class RegistroActivity extends Activity {

    private TextView texto;
    private ScrollView sv;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.AZUL_OSCURO);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Color.parseColor("#0F172A"));

        LinearLayout barra = new LinearLayout(this);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        barra.setBackgroundColor(Ui.AZUL_OSCURO);
        barra.setPadding(dp(8), dp(12), dp(8), dp(12));
        TextView atras = Ui.texto(this, "←", 24, Color.WHITE, true);
        atras.setPadding(dp(12), 0, dp(12), 0);
        atras.setOnClickListener(v -> finish());
        barra.addView(atras);
        barra.addView(Ui.texto(this, "📝 Registro", 20, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        barra.addView(boton("↻", v -> cargar()));
        barra.addView(boton("📤", v -> compartir()));
        barra.addView(boton("🗑", v -> new AlertDialog.Builder(this)
                .setMessage("¿Borrar el registro?")
                .setPositiveButton("Borrar", (d, w) -> { Depuracion.borrar(this); cargar(); })
                .setNegativeButton("Cancelar", null).show()));
        raiz.addView(barra);

        texto = new TextView(this);
        texto.setTypeface(Typeface.MONOSPACE);
        texto.setTextSize(11);
        texto.setTextColor(Color.parseColor("#D1E2F5"));
        texto.setTextIsSelectable(true);
        texto.setPadding(dp(10), dp(10), dp(10), dp(24));
        sv = new ScrollView(this);
        sv.addView(texto);
        raiz.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);
        Ui.ajustarABordes(raiz, barra);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargar();
    }

    private TextView boton(String s, android.view.View.OnClickListener l) {
        TextView t = Ui.texto(this, s, 20, Color.WHITE, false);
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        t.setOnClickListener(l);
        return t;
    }

    private void cargar() {
        String s = Depuracion.leer(this, 120_000);
        texto.setText(s.isEmpty() ? "El registro está vacío." : s);
        sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void compartir() {
        String s = Depuracion.leer(this, 90_000);
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Registro Avisos Aviones")
                .putExtra(Intent.EXTRA_TEXT, s);
        startActivity(Intent.createChooser(i, "Compartir registro"));
    }
}
