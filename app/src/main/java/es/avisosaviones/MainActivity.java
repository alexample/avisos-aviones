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
    private static final int REQ_SEGUNDO_PLANO = 2;

    private TextView boton, estadoTitulo, estadoDetalle, punto;
    private LinearLayout listaAlertas;
    private TextView diagnostico;
    private boolean ubicacionPreguntada = false; // para no insistir en bucle si dice que no

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
        // Si debería estar vigilando pero Android la ha cerrado, se reanuda (desde aquí sí está permitido)
        long latido = Ajustes.prefs(this).getLong(Vigilante.LATIDO, 0);
        Depuracion.log(this, "app", "abierta · activo=" + Ajustes.activo(this) + " · última comprobación hace "
                + (latido == 0 ? "?" : (System.currentTimeMillis() - latido) / 1000 + " s"));
        if (Ajustes.activo(this) && !Vigilante.vivo(this)) Vigilante.revisar(this, "app");
        pintarDiagnostico();
    }

    private void pintarDiagnostico() {
        StringBuilder sb = new StringBuilder();
        long latido = Ajustes.prefs(this).getLong(Vigilante.LATIDO, 0);
        if (Ajustes.activo(this) && latido > 0 && !Vigilante.vivo(this)) {
            sb.append("⚠️ La vigilancia no comprobaba nada desde las ")
                    .append(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(latido)))
                    .append(". Se ha reanudado; mira ⚙️ → Registro para ver qué pasó.");
        }
        String motivo = Ajustes.prefs(this).getString(Vigilante.MOTIVO_PARADA, "");
        if (!motivo.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("Última parada: ").append(motivo);
        String cierre = Vigilante.ultimoCierre(this);
        if (!cierre.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("Último cierre de Android: ").append(cierre);
        List<String> falta = faltaParaSegundoPlano();
        if (!falta.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("⚠️ Para que no se pare: ").append(String.join(" y ", falta))
                .append(". Toca aquí.");
        diagnostico.setText(sb.toString());
        diagnostico.setVisibility(sb.length() > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** Lo que falta para que Android deje la vigilancia en paz. */
    private List<String> faltaParaSegundoPlano() {
        List<String> l = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) l.add("ubicación «Permitir todo el tiempo»");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) l.add("batería «Sin restricciones»");
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms())
            l.add("«Alarmas y recordatorios» (para comprobar a su hora con el móvil en reposo)");
        return l;
    }

    /** Explica y pide, uno a uno, los permisos que evitan que Android cierre la vigilancia. */
    private void pedirSegundoPlano() {
        if (!ubicacionPreguntada && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setTitle("Ubicación: «Permitir todo el tiempo»")
                    .setMessage("Si Android cierra la vigilancia (por ejemplo, tras unas horas), solo puede volver a arrancarla "
                            + "sola si la app tiene la ubicación permitida todo el tiempo.\n\nEn la pantalla siguiente elige "
                            + "«Permitir todo el tiempo». La ubicación solo se usa en el móvil para saber qué tienes cerca.")
                    .setPositiveButton("Continuar", (d, w) -> {
                        ubicacionPreguntada = true;
                        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_SEGUNDO_PLANO);
                    })
                    .setNegativeButton("Ahora no", (d, w) -> { ubicacionPreguntada = true; pedirSegundoPlano(); })
                    .show();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            new AlertDialog.Builder(this)
                    .setTitle("Batería: «Sin restricciones»")
                    .setMessage("El ahorro de batería es lo que suele cerrar la app tras unas horas. "
                            + "Acepta en la pantalla siguiente para que Android no la duerma.\n\n"
                            + "En Samsung, además: Ajustes → Batería → Límites de uso en segundo plano → "
                            + "quita Avisos Aviones de «Aplicaciones en suspensión» y «en suspensión profunda».")
                    .setPositiveButton("Continuar", (d, w) -> pedirSinOptimizacionBateria())
                    .setNegativeButton("Ahora no", null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms()) {
            new AlertDialog.Builder(this)
                    .setTitle("«Alarmas y recordatorios»")
                    .setMessage("La app calcula cuándo va a llegar el próximo avión y deja dormir al móvil hasta entonces. "
                            + "Con este permiso se despierta justo a tiempo; sin él, Android puede retrasar la comprobación unos minutos.")
                    .setPositiveButton("Continuar", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("Ahora no", null)
                    .show();
        }
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

        diagnostico = Ui.texto(this, "", 12, Color.parseColor("#9A3412"), false);
        diagnostico.setPadding(dp(14), dp(12), dp(14), dp(12));
        diagnostico.setBackground(Ui.redondeado(Color.parseColor("#FFF7ED"), 14, this));
        diagnostico.setOnClickListener(v -> startActivity(new Intent(this, PermisosActivity.class)));
        col.addView(diagnostico, Ui.ancho(14, this));

        TextView nota = Ui.texto(this, "Datos: adsb.lol, adsb.fi (opendata.adsb.fi) y airplanes.live · rutas de Flightradar24 y adsbdb. "
                + "Los aparatos que no emiten ADS-B (muchos helicópteros militares o policiales) no aparecen.",
                12, Ui.TEXTO_SUAVE, false);
        nota.setPadding(dp(4), 0, dp(4), 0);
        col.addView(nota, Ui.ancho(16, this));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        raiz.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);
        Ui.ajustarABordes(raiz, cab);
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
            // Al volver a la app: lo último que dijo la vigilancia (si es reciente)
            long hora = Ajustes.prefs(this).getLong("ultimo_estado_hora", 0);
            String ultimo = Ajustes.prefs(this).getString("ultimo_estado", null);
            if (ultimo != null && System.currentTimeMillis() - hora < 20 * 60_000L) {
                long min = (System.currentTimeMillis() - hora) / 60_000L;
                txt = ultimo + (min >= 2 ? "  ·  (estado de hace " + min + " min)" : "");
            } else {
                estadoTitulo.setText("Vigilando…");
                estadoDetalle.setText("Primera comprobación en marcha");
                return;
            }
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
        TextView permisos = Ui.botonSecundario(this, "🔐 Permisos"
                + (PermisosActivity.faltanImprescindibles(this) > 0 ? "  ⚠️ faltan " + PermisosActivity.faltanImprescindibles(this) : "  ✅"));
        permisos.setOnClickListener(v -> startActivity(new Intent(this, PermisosActivity.class)));
        l.addView(permisos, Ui.ancho(4, this));
        TextView registro = Ui.botonSecundario(this, "📝 Registro de actividad");
        registro.setOnClickListener(v -> startActivity(new Intent(this, RegistroActivity.class)));
        l.addView(registro, Ui.ancho(8, this));
        l.addView(Ui.texto(this, "La app mira un radio amplio y calcula cuándo llegará el próximo avión a tus alertas; "
                + "hasta entonces deja dormir al móvil.", 13, Ui.TEXTO_SUAVE, false));
        l.addView(Ui.texto(this, "Radio de previsión (km)", 15, Ui.TEXTO, false), Ui.ancho(12, this));
        EditText prev = new EditText(this);
        prev.setInputType(InputType.TYPE_CLASS_NUMBER);
        prev.setText(String.valueOf(Ajustes.radioPrevisionKm(this)));
        l.addView(prev);
        l.addView(Ui.texto(this, "Tiempo mínimo entre comprobaciones (segundos)", 15, Ui.TEXTO, false), Ui.ancho(8, this));
        EditText inter = new EditText(this);
        inter.setInputType(InputType.TYPE_CLASS_NUMBER);
        inter.setText(String.valueOf(Ajustes.intervaloS(this)));
        l.addView(inter);
        l.addView(Ui.texto(this, "Tiempo máximo entre comprobaciones (minutos)", 15, Ui.TEXTO, false), Ui.ancho(8, this));
        EditText maxi = new EditText(this);
        maxi.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxi.setText(String.valueOf(Ajustes.esperaMaxS(this) / 60));
        l.addView(maxi);

        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setView(l)
                .setPositiveButton("Guardar", (d, w) -> {
                    int i = 15, p = 100, m = 5;
                    try { i = Integer.parseInt(inter.getText().toString().trim()); } catch (Exception ignored) {}
                    try { p = Integer.parseInt(prev.getText().toString().trim()); } catch (Exception ignored) {}
                    try { m = Integer.parseInt(maxi.getText().toString().trim()); } catch (Exception ignored) {}
                    Ajustes.prefs(this).edit()
                            .putInt(Ajustes.INTERVALO_S, Math.max(10, i))
                            .putInt(Ajustes.RADIO_PREVISION_KM, Math.max(10, Math.min(450, p)))
                            .putInt(Ajustes.ESPERA_MAX_S, Math.max(1, Math.min(30, m)) * 60)
                            .apply();
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
        Ajustes.prefs(this).edit().putBoolean(Ajustes.ACTIVO, true).remove(Vigilante.MOTIVO_PARADA).apply();
        startForegroundService(new Intent(this, VigilanciaService.class));
        refrescarBoton();
        mostrarEstado(null);
        pintarDiagnostico();
        if (PermisosActivity.faltanImprescindibles(this) > 0) {
            diagnostico.postDelayed(() -> startActivity(new Intent(this, PermisosActivity.class).putExtra("todos", true)), 800);
        }
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
        if (requestCode == REQ_SEGUNDO_PLANO) {
            pintarDiagnostico();
            diagnostico.postDelayed(this::pedirSegundoPlano, 500); // siguiente: batería
            return;
        }
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
