package es.avisosaviones;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lista de aeronaves que han pasado (las que generaron aviso). */
public class HistorialActivity extends Activity {

    private LinearLayout lista;
    private TextView resumen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad + dp(8), pad, pad);
        col.setBackgroundColor(Ui.FONDO);
        getWindow().setStatusBarColor(Ui.AZUL_OSCURO);

        TextView titulo = new TextView(this);
        titulo.setText("📋 Historial");
        titulo.setTextSize(24);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(titulo);

        resumen = new TextView(this);
        resumen.setPadding(0, dp(4), 0, dp(8));
        col.addView(resumen);

        LinearLayout botones = new LinearLayout(this);
        botones.setOrientation(LinearLayout.HORIZONTAL);
        Button refrescar = new Button(this);
        refrescar.setText("Actualizar");
        refrescar.setOnClickListener(v -> cargar());
        botones.addView(refrescar);
        Button borrar = new Button(this);
        borrar.setText("Borrar todo");
        borrar.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("¿Borrar todo el historial?")
                .setPositiveButton("Borrar", (d, w) -> { Registro.borrar(this); cargar(); })
                .setNegativeButton("Cancelar", null)
                .show());
        botones.addView(borrar);
        col.addView(botones);

        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        col.addView(lista);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.FONDO);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargar();
    }

    private void cargar() {
        lista.removeAllViews();
        List<JSONObject> entradas = Registro.leer(this);

        long inicioHoy = inicioDelDia();
        int hoy = 0, helis = 0;
        for (JSONObject e : entradas) {
            if (e.optLong("ts") >= inicioHoy) hoy++;
            if (e.optBoolean("heli")) helis++;
        }
        resumen.setText(entradas.isEmpty()
                ? "Todavía no ha pasado nada. Aquí aparecerá cada aparato que te avise."
                : String.format(Locale.getDefault(), "%d en total · %d hoy · %d helicópteros\nToca uno para abrirlo en Flightradar24.",
                    entradas.size(), hoy, helis));

        SimpleDateFormat fHoy = new SimpleDateFormat("HH:mm", Locale.getDefault());
        SimpleDateFormat fOtro = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

        for (JSONObject e : entradas) {
            long ts = e.optLong("ts");
            String cuando = (ts >= inicioHoy ? fHoy : fOtro).format(new Date(ts));
            boolean heli = e.optBoolean("heli");

            StringBuilder linea2 = new StringBuilder();
            agregar(linea2, e.optString("modelo"));
            agregar(linea2, e.optString("matricula"));
            agregar(linea2, e.optString("operador"));

            StringBuilder linea3 = new StringBuilder();
            linea3.append(String.format(Locale.getDefault(), "%.1f km", e.optDouble("dist_km")));
            int alt = e.optInt("alt_m", -1);
            if (alt >= 0) linea3.append(String.format(Locale.getDefault(), " · %,d m", alt));
            double vel = e.optDouble("vel_kmh", -1);
            if (vel >= 0) linea3.append(String.format(Locale.getDefault(), " · %.0f km/h", vel));
            String rumbo = e.optString("rumbo");
            if (!rumbo.isEmpty()) linea3.append(" · rumbo ").append(rumbo);
            String ruta = e.optString("ruta");
            String emerg = e.optString("emergencia");
            String alertaNombre = e.optString("alerta");

            String icono = (heli ? "🚁" : "✈️") + (e.optBoolean("militar") ? "🎖️" : "")
                    + (emerg.isEmpty() ? "" : "🚨");
            TextView t = new TextView(this);
            t.setText(icono + " " + cuando + "  " + e.optString("nombre")
                    + (emerg.isEmpty() ? "" : "  " + emerg)
                    + (linea2.length() > 0 ? "\n" + linea2 : "")
                    + (ruta.isEmpty() ? "" : "\n🛫 " + ruta)
                    + "\n" + linea3
                    + (alertaNombre.isEmpty() ? "" : "\n🔔 " + alertaNombre));
            t.setTextSize(15);
            t.setPadding(dp(12), dp(10), dp(12), dp(10));
            t.setTextColor(Ui.TEXTO);
            t.setBackground(Ui.pulsable(heli ? Color.parseColor("#FFF4E5") : Color.WHITE, 14, this));
            t.setElevation(dp(1));

            String url = e.optString("url_fr24");
            if (!url.isEmpty()) {
                String reg = e.optString("reg");
                String cs = e.optString("callsign");
                t.setOnClickListener(v -> startActivity(AbrirFr24Activity.intentPara(this, url, reg, cs)));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            lista.addView(t, lp);
        }
    }

    private static void agregar(StringBuilder sb, String s) {
        if (s == null || s.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(s.trim());
    }

    private static long inicioDelDia() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
