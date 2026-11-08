package es.avisosaviones;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMISOS = 1;

    private TextView boton, estadoTitulo, estadoDetalle, punto;
    private LinearLayout listaAlertas;

    private final BroadcastReceiver receptorEstado = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String txt = intent.getStringExtra(VigilanciaService.EXTRA_TEXTO);
            if (txt != null) mostrarEstado(txt);
            refrescarBoton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.AZUL_OSCURO);
        construir();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(VigilanciaService.ACCION_ESTADO);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receptorEstado, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receptorEstado, f);
        refrescarBoton();
        pintarAlertas();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receptorEstado);
    }

    private int dp(float v) { return Ui.dp(this, v); }

    // ================================================================ interfaz

    private void construir() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Ui.FONDO);

        // ---------- Cabecera
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setPadding(dp(20), dp(20), dp(20), dp(22));
        GradientDrawable deg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.AZUL_OSCURO, Color.parseColor("#1D4E89")});
        float r = dp(28);
        deg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        cab.setBackground(deg);

        LinearLayout filaTitulo = new LinearLayout(this);
        filaTitulo.setGravity(Gravity.CENTER_VERTICAL);
        TextView titulo = Ui.texto(this, "✈️  Avisos Aviones", 24, Color.WHITE, true);
        filaTitulo.addView(titulo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView ajustes = Ui.texto(this, "⚙️", 22, Color.WHITE, false);
        ajustes.setPadding(dp(10), dp(4), dp(4), dp(4));
        ajustes.setOnClickListener(v -> abrirAjustes());
        filaTitulo.addView(ajustes);
        cab.addView(filaTitulo);

        LinearLayout filaEstado = new LinearLayout(this);
        filaEstado.setGravity(Gravity.CENTER_VERTICAL);
        punto = new TextView(this);
        filaEstado.addView(punto, new LinearLayout.LayoutParams(dp(10), dp(10)));
        estadoTitulo = Ui.texto(this, "", 16, Color.WHITE, true);
        estadoTitulo.setPadding(dp(8), 0, 0, 0);
        filaEstado.addView(estadoTitulo);
        cab.addView(filaEstado, Ui.ancho(16, this));

        estadoDetalle = Ui.texto(this, "", 13, Color.parseColor("#B9CCE6"), false);
        cab.addView(estadoDetalle, Ui.ancho(4, this));

        boton = Ui.texto(this, "", 17, Ui.AZUL_OSCURO, true);
        boton.setGravity(Gravity.CENTER);
        boton.setPadding(0, dp(15), 0, dp(15));
        boton.setClickable(true);
        boton.setOnClickListener(v -> alternar());
        cab.addView(boton, Ui.ancho(18, this));
        raiz.addView(cab);

        // ---------- Cuerpo
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(24));

        LinearLayout filaAlertas = new LinearLayout(this);
        filaAlertas.setGravity(Gravity.CENTER_VERTICAL);
        TextView tAl = Ui.texto(this, "Mis alertas", 20, Ui.TEXTO, true);
        filaAlertas.addView(tAl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nueva = Ui.texto(this, "＋ Nueva", 15, Color.WHITE, true);
        nueva.setPadding(dp(16), dp(9), dp(16), dp(9));
        nueva.setBackground(Ui.pulsable(Ui.AZUL, 20, this));
        nueva.setOnClickListener(v -> startActivity(new Intent(this, AlertaActivity.class)));
        filaAlertas.addView(nueva);
        col.addView(filaAlertas, Ui.ancho(14, this));

        TextView expl = Ui.texto(this, "Te aviso cuando una aeronave cumple cualquiera de las alertas encendidas.",
                13, Ui.TEXTO_SUAVE, false);
        col.addView(expl, Ui.ancho(4, this));

        listaAlertas = new LinearLayout(this);
        listaAlertas.setOrientation(LinearLayout.VERTICAL);
        col.addView(listaAlertas, Ui.ancho(4, this));

        TextView historial = Ui.botonSecundario(this, "📋  Historial de pasos");
        historial.setOnClickListener(v -> startActivity(new Intent(this, HistorialActivity.class)));
        col.addView(historial, Ui.ancho(18, this));

        TextView nota = Ui.texto(this, "Datos: airplanes.live y adsb.lol · rutas de Flightradar24 y adsbdb. "
                + "Los aparatos que no emiten ADS-B (muchos helicópteros militares o policiales) no aparecen.",
                12, Ui.TEXTO_SUAVE, false);
        nota.setPadding(dp(4), 0, dp(4), 0);
        col.addView(nota, Ui.ancho(16, this));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        raiz.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);
        mostrarEstado(null);
    }

    private void pintarAlertas() {
        listaAlertas.removeAllViews();
        List<Alerta> lista = Alertas.cargar(this);
        if (lista.isEmpty()) {
            LinearLayout vacia = Ui.tarjeta(this, "Sin alertas", "Pulsa «＋ Nueva» para crear la primera.");
            listaAlertas.addView(vacia, Ui.ancho(10, this));
            return;
        }
        for (Alerta a : lista) {
            LinearLayout t = new LinearLayout(this);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(14), dp(14), dp(10), dp(14));
            t.setBackground(Ui.pulsable(Color.WHITE, 18, this));
            t.setElevation(dp(1.5f));

            TextView icono = Ui.texto(this, a.icono(), 22, Ui.TEXTO, false);
            icono.setGravity(Gravity.CENTER);
            icono.setBackground(Ui.redondeado(a.activa ? Ui.AZUL_CLARO : Color.parseColor("#EEF1F5"), 14, this));
            t.addView(icono, new LinearLayout.LayoutParams(dp(46), dp(46)));

            LinearLayout textos = new LinearLayout(this);
            textos.setOrientation(LinearLayout.VERTICAL);
            textos.setPadding(dp(12), 0, dp(8), 0);
            TextView n = Ui.texto(this, a.nombre, 16, a.activa ? Ui.TEXTO : Ui.TEXTO_SUAVE, true);
            TextView s = Ui.texto(this, a.resumen(), 13, Ui.TEXTO_SUAVE, false);
            textos.addView(n);
            textos.addView(s);
            t.addView(textos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Switch sw = new Switch(this);
            sw.setChecked(a.activa);
            sw.setOnCheckedChangeListener((b, on) -> {
                a.activa = on;
                Alertas.guardarUna(this, a);
                pintarAlertas();
            });
            t.addView(sw);

            t.setOnClickListener(v -> startActivity(new Intent(this, AlertaActivity.class)
                    .putExtra(AlertaActivity.EXTRA_ID, a.id)));
            listaAlertas.addView(t, Ui.ancho(10, this));
        }
    }

    private void mostrarEstado(String txt) {
        boolean on = Ajustes.activo(this);
        punto.setBackground(Ui.redondeado(on ? Color.parseColor("#4ADE80") : Color.parseColor("#94A3B8"), 5, this));
        if (!on) {
            estadoTitulo.setText("Parado");
            int activas = 0;
            for (Alerta a : Alertas.cargar(this)) if (a.activa) activas++;
            estadoDetalle.setText(activas == 0 ? "No hay alertas encendidas"
                    : activas + (activas == 1 ? " alerta encendida" : " alertas encendidas") + ". Pulsa empezar.");
            return;
        }
        if (txt == null) {
            estadoTitulo.setText("Vigilando…");
            estadoDetalle.setText("Buscando tu ubicación");
            return;
        }
        int salto = txt.indexOf('\n');
        estadoTitulo.setText(salto > 0 ? txt.substring(0, salto) : txt);
        estadoDetalle.setText(salto > 0 ? txt.substring(salto + 1) : "");
    }

    private void refrescarBoton() {
        boolean on = Ajustes.activo(this);
        boton.setText(on ? "⏹   Parar vigilancia" : "▶   Empezar a vigilar");
        boton.setTextColor(on ? Color.WHITE : Ui.AZUL_OSCURO);
        boton.setBackground(Ui.pulsable(on ? Color.parseColor("#33FFFFFF") : Color.WHITE, 16, this));
        if (!on) mostrarEstado(null);
    }

    private void abrirAjustes() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(8), dp(24), 0);
        l.addView(Ui.texto(this, "Comprobar cada (segundos)", 15, Ui.TEXTO, false));
        EditText inter = new EditText(this);
        inter.setInputType(InputType.TYPE_CLASS_NUMBER);
        inter.setText(String.valueOf(Ajustes.intervaloS(this)));
        l.addView(inter);
        TextView bat = Ui.botonSecundario(this, "🔋 Quitar ahorro de batería");
        bat.setOnClickListener(v -> pedirSinOptimizacionBateria());
        l.addView(bat, Ui.ancho(12, this));
        l.addView(Ui.texto(this, "Recomendado: si no, Android puede dormir la app y dejarías de recibir avisos.",
                12, Ui.TEXTO_SUAVE, false), Ui.ancho(6, this));

        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setView(l)
                .setPositiveButton("Guardar", (d, w) -> {
                    int i = 15;
                    try { i = Integer.parseInt(inter.getText().toString().trim()); } catch (Exception ignored) {}
                    Ajustes.prefs(this).edit().putInt(Ajustes.INTERVALO_S, Math.max(5, i)).apply();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ================================================================ vigilancia

    private void alternar() {
        if (Ajustes.activo(this)) {
            startService(new Intent(this, VigilanciaService.class).setAction(VigilanciaService.ACCION_PARAR));
            Ajustes.prefs(this).edit().putBoolean(Ajustes.ACTIVO, false).apply();
            refrescarBoton();
            return;
        }
        boolean alguna = false;
        for (Alerta a : Alertas.cargar(this)) if (a.activa) alguna = true;
        if (!alguna) {
            Toast.makeText(this, "Enciende al menos una alerta", Toast.LENGTH_LONG).show();
            return;
        }
        if (pedirPermisosSiFaltan()) return; // seguirá en onRequestPermissionsResult
        arrancar();
    }

    private void arrancar() {
        startForegroundService(new Intent(this, VigilanciaService.class));
        Ajustes.prefs(this).edit().putBoolean(Ajustes.ACTIVO, true).apply();
        refrescarBoton();
        mostrarEstado(null);
    }

    /** @return true si ha tenido que pedir permisos. */
    private boolean pedirPermisosSiFaltan() {
        List<String> faltan = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.ACCESS_FINE_LOCATION);
            faltan.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (faltan.isEmpty()) return false;
        requestPermissions(faltan.toArray(new String[0]), REQ_PERMISOS);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISOS) return;
        boolean ubicacion = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!ubicacion) {
            Toast.makeText(this, "Sin permiso de ubicación no puedo saber qué tienes cerca.", Toast.LENGTH_LONG).show();
            return;
        }
        arrancar();
    }

    private void pedirSinOptimizacionBateria() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Ya está desactivado el ahorro de batería para la app 👍", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
