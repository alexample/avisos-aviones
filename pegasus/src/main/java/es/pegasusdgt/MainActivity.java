package es.pegasusdgt;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ = 1;

    private TextView estado, detalle, boton, movimiento;
    private LinearLayout permisos, matriculas, avistamientos;
    private EditText radio, umbrales, intervalo;
    private TextToSpeech tts;

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            mostrarEstado(i.getStringExtra(PegasusService.TEXTO));
            pintarAvistamientos();
        }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.AZUL_OSCURO);
        construir();
        tts = new TextToSpeech(this, s -> { if (s == TextToSpeech.SUCCESS) tts.setLanguage(new Locale("es", "ES")); });
        if (Config.auto(this)) Movimiento.registrar(this);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(PegasusService.ESTADO);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receptor, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receptor, f);
        mostrarEstado(Config.activo(this) ? Config.p(this).getString("ultimo_estado", null) : null);
        pintarPermisos();
        pintarMovimiento();
        pintarAvistamientos();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receptor);
        guardar();
    }

    // ================================================================ interfaz

    private void construir() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Ui.FONDO);

        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setPadding(dp(20), dp(20), dp(20), dp(22));
        GradientDrawable deg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.AZUL_OSCURO, Color.parseColor("#7C2D12")});
        float r = dp(28);
        deg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        cab.setBackground(deg);
        cab.addView(Ui.texto(this, "🚁  Pegasus DGT", 24, Color.WHITE, true));
        estado = Ui.texto(this, "", 18, Color.WHITE, true);
        cab.addView(estado, Ui.ancho(16, this));
        detalle = Ui.texto(this, "", 13, Color.parseColor("#E5D3C7"), false);
        cab.addView(detalle, Ui.ancho(4, this));
        boton = Ui.texto(this, "", 17, Ui.AZUL_OSCURO, true);
        boton.setGravity(Gravity.CENTER);
        boton.setPadding(0, dp(15), 0, dp(15));
        boton.setOnClickListener(v -> alternar());
        cab.addView(boton, Ui.ancho(18, this));
        raiz.addView(cab);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), dp(24));

        // ---- Permisos (solo se ve si falta algo)
        permisos = new LinearLayout(this);
        permisos.setOrientation(LinearLayout.VERTICAL);
        col.addView(permisos);

        // ---- Arranque automático
        LinearLayout tAuto = Ui.tarjeta(this, "🚗 Arranque automático",
                "Empieza a vigilar sola al detectar que vas en coche y se para al bajarte o tras 10 min parado.");
        Switch swAuto = new Switch(this);
        swAuto.setText("Activar al conducir");
        swAuto.setTextSize(16);
        swAuto.setTextColor(Ui.TEXTO);
        swAuto.setChecked(Config.auto(this));
        swAuto.setOnCheckedChangeListener((bb, on) -> {
            Config.p(this).edit().putBoolean("auto", on).apply();
            if (on) Movimiento.registrar(this); else Movimiento.quitar(this);
            swAuto.postDelayed(this::pintarMovimiento, 800);
        });
        tAuto.addView(swAuto, Ui.ancho(8, this));
        movimiento = Ui.texto(this, "", 13, Ui.TEXTO_SUAVE, false);
        tAuto.addView(movimiento, Ui.ancho(6, this));
        col.addView(tAuto, Ui.ancho(14, this));

        // ---- Avisos
        LinearLayout tAv = Ui.tarjeta(this, "🔊 Avisos", "Por voz y con sonido, para no tener que mirar el móvil.");
        Switch swVoz = new Switch(this);
        swVoz.setText("Avisar por voz");
        swVoz.setTextSize(16);
        swVoz.setTextColor(Ui.TEXTO);
        swVoz.setChecked(Config.voz(this));
        swVoz.setOnCheckedChangeListener((bb, on) -> Config.p(this).edit().putBoolean("voz", on).apply());
        tAv.addView(swVoz, Ui.ancho(8, this));
        radio = campo(tAv, "Vigilar hasta (km)", String.valueOf(Config.radioKm(this)), InputType.TYPE_CLASS_NUMBER);
        umbrales = campo(tAv, "Volver a avisar al bajar de (km)", Config.p(this).getString("umbrales", "20, 10, 5, 2"),
                InputType.TYPE_CLASS_TEXT);
        intervalo = campo(tAv, "Comprobar cada (segundos)", String.valueOf(Config.intervaloS(this)), InputType.TYPE_CLASS_NUMBER);
        TextView probar = Ui.botonSecundario(this, "▶ Probar aviso");
        probar.setOnClickListener(v -> {
            PegasusService.probarVoz(this, tts);
        });
        tAv.addView(probar, Ui.ancho(12, this));
        col.addView(tAv, Ui.ancho(12, this));

        // ---- Matrículas
        LinearLayout tMat = Ui.tarjeta(this, "🚁 Helicópteros vigilados", "Toca uno para quitarlo.");
        matriculas = new LinearLayout(this);
        matriculas.setOrientation(LinearLayout.VERTICAL);
        tMat.addView(matriculas, Ui.ancho(6, this));
        LinearLayout filaAdd = new LinearLayout(this);
        filaAdd.setGravity(Gravity.CENTER_VERTICAL);
        EditText nueva = new EditText(this);
        nueva.setHint("Añadir matrícula (EC-ABC)");
        nueva.setSingleLine(true);
        nueva.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filaAdd.addView(nueva, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = Ui.texto(this, "＋", 26, Ui.AZUL, true);
        add.setPadding(dp(14), 0, dp(6), 0);
        add.setOnClickListener(v -> {
            String m = nueva.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (m.length() < 4) return;
            List<String> l = Config.matriculas(this);
            if (!l.contains(m)) l.add(m);
            Config.guardarMatriculas(this, l);
            nueva.setText("");
            pintarMatriculas();
        });
        filaAdd.addView(add);
        tMat.addView(filaAdd, Ui.ancho(6, this));
        col.addView(tMat, Ui.ancho(12, this));

        // ---- Avistamientos
        LinearLayout tAvi = Ui.tarjeta(this, "📋 Últimos avistamientos", null);
        avistamientos = new LinearLayout(this);
        avistamientos.setOrientation(LinearLayout.VERTICAL);
        tAvi.addView(avistamientos, Ui.ancho(4, this));
        col.addView(tAvi, Ui.ancho(12, this));

        TextView nota = Ui.texto(this, "Datos: adsb.lol, adsb.fi (opendata.adsb.fi) y airplanes.live. Solo aparecen cuando el helicóptero emite su "
                + "posición (ADS-B); si en un vuelo no lo hace, no se puede ver. No uses el móvil mientras conduces: "
                + "la app avisa por voz.", 12, Ui.TEXTO_SUAVE, false);
        nota.setPadding(dp(4), 0, dp(4), 0);
        col.addView(nota, Ui.ancho(16, this));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        raiz.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);
        Ui.ajustarABordes(raiz, cab);
        pintarMatriculas();
    }

    private EditText campo(LinearLayout padre, String etiqueta, String valor, int tipo) {
        padre.addView(Ui.texto(this, etiqueta, 14, Ui.TEXTO, false), Ui.ancho(10, this));
        EditText e = new EditText(this);
        e.setText(valor);
        e.setSingleLine(true);
        e.setInputType(tipo);
        padre.addView(e);
        return e;
    }

    private void guardar() {
        int r = 60, i = 10;
        try { r = Integer.parseInt(radio.getText().toString().trim()); } catch (Exception ignored) {}
        try { i = Integer.parseInt(intervalo.getText().toString().trim()); } catch (Exception ignored) {}
        Config.p(this).edit()
                .putInt("radio_km", Math.max(5, Math.min(400, r)))
                .putInt("intervalo_s", Math.max(5, Math.min(120, i)))
                .putString("umbrales", umbrales.getText().toString().trim())
                .apply();
    }

    private void mostrarEstado(String txt) {
        boolean on = Config.activo(this);
        boton.setText(on ? "⏹   Parar" : "▶   Vigilar ahora");
        boton.setTextColor(on ? Color.WHITE : Ui.AZUL_OSCURO);
        boton.setBackground(Ui.pulsable(on ? Color.parseColor("#33FFFFFF") : Color.WHITE, 16, this));
        if (!on) {
            estado.setText(Config.auto(this) ? "⏸ Esperando a que conduzcas" : "⏸ Parado");
            String err = Config.p(this).getString("ultimo_error", "");
            detalle.setText(Config.p(this).getString("ultimo_movimiento", "") + (err.isEmpty() ? "" : "\n⚠️ " + err));
            return;
        }
        if (txt == null) { estado.setText("Vigilando…"); detalle.setText(""); return; }
        int s = txt.indexOf('\n');
        estado.setText(s > 0 ? txt.substring(0, s) : txt);
        detalle.setText(s > 0 ? txt.substring(s + 1) : "");
    }

    private void alternar() {
        guardar();
        if (Config.activo(this)) {
            PegasusService.parar(this);
            boton.postDelayed(() -> mostrarEstado(null), 400);
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Primero activa la ubicación (arriba)", Toast.LENGTH_LONG).show();
            pintarPermisos();
            return;
        }
        Config.p(this).edit().remove("ultimo_error").apply();
        PegasusService.arrancar(this, true);
        boton.postDelayed(() -> mostrarEstado(null), 400);
    }

    private void pintarMovimiento() {
        movimiento.setText("Detección de coche: " + Movimiento.estado(this));
    }

    private void pintarMatriculas() {
        matriculas.removeAllViews();
        ViewGroup fila = Ui.filaChips(this);
        for (String m : Config.matriculas(this)) {
            TextView ch = Ui.chip(this, m + "  ✕");
            ch.setOnClickListener(v -> {
                List<String> l = Config.matriculas(this);
                l.remove(m);
                Config.guardarMatriculas(this, l);
                pintarMatriculas();
            });
            fila.addView(ch);
        }
        matriculas.addView(fila);
    }

    private void pintarAvistamientos() {
        avistamientos.removeAllViews();
        JSONArray a = Config.avistamientos(this);
        if (a.length() == 0) {
            avistamientos.addView(Ui.texto(this, "Todavía ninguno.", 14, Ui.TEXTO_SUAVE, false));
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        for (int i = 0; i < a.length() && i < 15; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String t = f.format(new Date(o.optLong("ts"))) + "  ·  " + o.optString("reg") + " a "
                    + o.optDouble("dist") + " km, " + o.optString("donde");
            TextView tv = Ui.texto(this, t, 14, Ui.TEXTO, false);
            tv.setPadding(0, dp(6), 0, dp(6));
            avistamientos.addView(tv);
        }
    }

    // ================================================================ permisos

    private void pintarPermisos() {
        permisos.removeAllViews();
        List<String[]> faltan = new ArrayList<>(); // {texto, clave}
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            faltan.add(new String[]{"📍 Ubicación precisa", "ubic"});
        else if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            faltan.add(new String[]{"🔄 Ubicación «Permitir todo el tiempo» (para arrancar sola en el coche)", "fondo"});
        if (Build.VERSION.SDK_INT >= 29 && !Movimiento.tienePermiso(this))
            faltan.add(new String[]{"🚗 Actividad física (para saber que vas en coche)", "act"});
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            faltan.add(new String[]{"🔔 Notificaciones", "notif"});
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))
            faltan.add(new String[]{"🔋 Batería «Sin restricciones» (para arrancar sola)", "bat"});
        if (faltan.isEmpty()) return;

        LinearLayout t = Ui.tarjeta(this, "⚠️ Faltan permisos", "Toca cada uno para activarlo.");
        for (String[] f : faltan) {
            TextView b = Ui.botonSecundario(this, f[0]);
            b.setOnClickListener(v -> activar(f[1]));
            t.addView(b, Ui.ancho(8, this));
        }
        permisos.addView(t, Ui.ancho(14, this));
    }

    private void activar(String clave) {
        try {
            switch (clave) {
                case "ubic":
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ);
                    break;
                case "fondo":
                    Toast.makeText(this, "Elige «Permitir todo el tiempo»", Toast.LENGTH_LONG).show();
                    requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ);
                    break;
                case "act":
                    requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ);
                    break;
                case "notif":
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ);
                    break;
                case "bat":
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                    break;
                default: break;
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] ps, int[] res) {
        super.onRequestPermissionsResult(rc, ps, res);
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok && ps.length > 0 && !shouldShowRequestPermissionRationale(ps[0])) {
            Toast.makeText(this, "Actívalo en los ajustes de la app → Permisos", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
        if (ok && ps.length > 0 && Manifest.permission.ACTIVITY_RECOGNITION.equals(ps[0]) && Config.auto(this)) {
            Movimiento.registrar(this);
        }
        pintarPermisos();
        pintarMovimiento();
    }
}
