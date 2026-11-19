package es.avisosaviones;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Todos los permisos y ajustes que necesita la app, con su estado y un botón para activarlos. */
public class PermisosActivity extends Activity {

    private static final int REQ = 11;

    /** Un permiso o ajuste: cómo se comprueba y cómo se activa. */
    private abstract class Permiso {
        final String icono, nombre, para;
        final boolean opcional;
        Permiso(String icono, String nombre, String para, boolean opcional) {
            this.icono = icono; this.nombre = nombre; this.para = para; this.opcional = opcional;
        }
        abstract boolean aplica();
        abstract boolean activo();
        abstract void activar();
    }

    private final List<Permiso> permisos = new ArrayList<>();
    private LinearLayout lista;
    private TextView resumen;
    private boolean activandoTodos = false;
    private boolean esperandoAjustes = false;          // se abrió una pantalla de ajustes del sistema
    private final java.util.Set<Permiso> intentados = new java.util.HashSet<>(); // en esta ronda de "activar todos"
    private String pedidoAhora = null; // permiso de Android pedido con el diálogo del sistema

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.AZUL_OSCURO);
        definirPermisos();

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Ui.FONDO);

        LinearLayout barra = new LinearLayout(this);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        barra.setBackgroundColor(Ui.AZUL_OSCURO);
        barra.setPadding(dp(8), dp(12), dp(16), dp(12));
        TextView atras = Ui.texto(this, "←", 24, Color.WHITE, true);
        atras.setPadding(dp(12), 0, dp(12), 0);
        atras.setOnClickListener(v -> finish());
        barra.addView(atras);
        barra.addView(Ui.texto(this, "🔐 Permisos", 20, Color.WHITE, true));
        raiz.addView(barra);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(24));

        resumen = Ui.texto(this, "", 15, Ui.TEXTO, false);
        col.addView(resumen);

        TextView todos = Ui.botonPrincipal(this, "Activar todos", Ui.AZUL);
        todos.setOnClickListener(v -> { activandoTodos = true; intentados.clear(); siguientePendiente(); });
        col.addView(todos, Ui.ancho(14, this));

        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        col.addView(lista, Ui.ancho(6, this));

        LinearLayout samsung = Ui.tarjeta(this, "📱 Si tu móvil es Samsung, Xiaomi, Huawei…",
                "Algunos fabricantes tienen su propio ahorro de batería que Android no deja comprobar. "
                        + "Samsung: Ajustes → Batería → Límites de uso en segundo plano → quita Avisos Aviones de "
                        + "«Aplicaciones en suspensión» y «en suspensión profunda». Xiaomi: activa «Inicio automático».");
        TextView abrir = Ui.botonSecundario(this, "Abrir los ajustes de la app");
        abrir.setOnClickListener(v -> abrirAjustesApp());
        samsung.addView(abrir, Ui.ancho(10, this));
        col.addView(samsung, Ui.ancho(16, this));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        raiz.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);
        Ui.ajustarABordes(raiz, barra);

        if (getIntent().getBooleanExtra("todos", false)) {
            activandoTodos = true;
            lista.postDelayed(this::siguientePendiente, 500);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        pintar();
        // Al volver de una pantalla de ajustes, seguir con el siguiente si se pidió "activar todos"
        if (activandoTodos && esperandoAjustes) {
            esperandoAjustes = false;
            lista.postDelayed(this::siguientePendiente, 400);
        }
    }

    // ------------------------------------------------------------ definición

    private void definirPermisos() {
        permisos.add(new Permiso("📍", "Ubicación", "Para saber qué aeronaves tienes cerca.", false) {
            boolean aplica() { return true; }
            boolean activo() { return concedido(Manifest.permission.ACCESS_FINE_LOCATION); }
            void activar() { pedir(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION); }
        });
        permisos.add(new Permiso("🛰️", "Ubicación del móvil encendida", "Sin ella, la app no sabe dónde estás.", false) {
            boolean aplica() { return true; }
            boolean activo() {
                LocationManager lm = getSystemService(LocationManager.class);
                return Build.VERSION.SDK_INT < 28 || lm.isLocationEnabled();
            }
            void activar() { abrir(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
        });
        permisos.add(new Permiso("🔄", "Ubicación «Permitir todo el tiempo»",
                "Para que Android deje reanudar la vigilancia sola si la cierra.", false) {
            boolean aplica() { return Build.VERSION.SDK_INT >= 29; }
            boolean activo() { return concedido(Manifest.permission.ACCESS_BACKGROUND_LOCATION); }
            void activar() {
                if (!concedido(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    Toast.makeText(PermisosActivity.this, "Primero la ubicación normal", Toast.LENGTH_SHORT).show();
                    pedir(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
                    return;
                }
                Toast.makeText(PermisosActivity.this, "Elige «Permitir todo el tiempo»", Toast.LENGTH_LONG).show();
                pedir(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
        });
        permisos.add(new Permiso("🔔", "Notificaciones", "Para poder avisarte.", false) {
            boolean aplica() { return true; }
            boolean activo() {
                return getSystemService(NotificationManager.class).areNotificationsEnabled();
            }
            void activar() {
                if (Build.VERSION.SDK_INT >= 33 && !concedido(Manifest.permission.POST_NOTIFICATIONS)) {
                    pedir(Manifest.permission.POST_NOTIFICATIONS);
                } else {
                    abrir(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                }
            }
        });
        permisos.add(new Permiso("🔋", "Batería «Sin restricciones»",
                "El ahorro de batería es lo que suele cerrar la app tras unas horas.", false) {
            boolean aplica() { return true; }
            boolean activo() {
                return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
            }
            void activar() {
                try {
                    abrir(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    abrir(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            }
        });
        permisos.add(new Permiso("🚫", "Sin restricción en segundo plano",
                "Si está restringida, Android no la deja funcionar con la pantalla apagada.", false) {
            boolean aplica() { return Build.VERSION.SDK_INT >= 28; }
            boolean activo() { return !getSystemService(ActivityManager.class).isBackgroundRestricted(); }
            void activar() { abrirAjustesApp(); }
        });
        permisos.add(new Permiso("⏰", "Alarmas y recordatorios",
                "Para despertar el móvil justo antes de que llegue un avión.", true) {
            boolean aplica() { return Build.VERSION.SDK_INT >= 31; }
            boolean activo() { return getSystemService(AlarmManager.class).canScheduleExactAlarms(); }
            void activar() {
                abrir(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
            }
        });
        permisos.add(new Permiso("🧊", "No pausar si no se usa",
                "Android quita los permisos a las apps que no abres en meses; esto lo evita.", true) {
            boolean aplica() { return Build.VERSION.SDK_INT >= 30; }
            boolean activo() { return getPackageManager().isAutoRevokeWhitelisted(); }
            void activar() {
                abrir(new Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.parse("package:" + getPackageName())));
            }
        });
    }

    // ------------------------------------------------------------ pintar

    private void pintar() {
        lista.removeAllViews();
        int faltan = 0;
        for (Permiso p : permisos) {
            if (!p.aplica()) continue;
            boolean ok = p.activo();
            if (!ok) faltan++;

            LinearLayout fila = new LinearLayout(this);
            fila.setGravity(Gravity.CENTER_VERTICAL);
            fila.setPadding(dp(14), dp(14), dp(12), dp(14));
            fila.setBackground(Ui.redondeado(Color.WHITE, 16, this));
            fila.setElevation(dp(1));

            TextView ic = Ui.texto(this, p.icono, 22, Ui.TEXTO, false);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(Ui.redondeado(ok ? Color.parseColor("#E7F6EC") : Color.parseColor("#FDECEC"), 12, this));
            fila.addView(ic, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout textos = new LinearLayout(this);
            textos.setOrientation(LinearLayout.VERTICAL);
            textos.setPadding(dp(12), 0, dp(8), 0);
            textos.addView(Ui.texto(this, p.nombre + (p.opcional ? "  (recomendado)" : ""), 15, Ui.TEXTO, true));
            textos.addView(Ui.texto(this, p.para, 13, Ui.TEXTO_SUAVE, false));
            fila.addView(textos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (ok) {
                fila.addView(Ui.texto(this, "✅", 22, Ui.VERDE, false));
            } else {
                TextView b = Ui.texto(this, "Activar", 14, Color.WHITE, true);
                b.setPadding(dp(14), dp(8), dp(14), dp(8));
                b.setBackground(Ui.pulsable(Ui.AZUL, 16, this));
                b.setOnClickListener(v -> { activandoTodos = false; p.activar(); });
                fila.addView(b);
            }
            lista.addView(fila, Ui.ancho(10, this));
        }
        resumen.setText(faltan == 0 ? "✅ Todo activado. La vigilancia puede funcionar sin interrupciones."
                : "Faltan " + faltan + (faltan == 1 ? " permiso" : " permisos")
                  + ". Activa todos para que la app avise siempre y no se pare.");
    }

    private void siguientePendiente() {
        for (Permiso p : permisos) {
            if (p.aplica() && !p.activo() && !intentados.contains(p)) {
                intentados.add(p); // si vuelve sin activarlo, se pasa al siguiente (sin bucles)
                p.activar();
                return;
            }
        }
        activandoTodos = false;
        pintar();
        boolean todo = true;
        for (Permiso p : permisos) if (p.aplica() && !p.activo()) todo = false;
        Toast.makeText(this, todo ? "✅ Todo activado" : "Quedan algunos sin activar", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ utilidades

    private boolean concedido(String permiso) {
        return checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean yaPedido(String permiso) {
        return Ajustes.prefs(this).getBoolean("pedido_" + permiso, false);
    }

    /** Pide un permiso; si Android ya no deja preguntar (se denegó dos veces), abre los ajustes de la app. */
    private void pedir(String... ps) {
        String principal = ps[0];
        if (yaPedido(principal) && !shouldShowRequestPermissionRationale(principal)
                && !principal.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            Toast.makeText(this, "Actívalo en Permisos de la app", Toast.LENGTH_LONG).show();
            abrirAjustesApp();
            return;
        }
        Ajustes.prefs(this).edit().putBoolean("pedido_" + principal, true).apply();
        pedidoAhora = principal;
        requestPermissions(ps, REQ);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        String pedido = pedidoAhora;
        pedidoAhora = null;
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok && pedido != null && !shouldShowRequestPermissionRationale(pedido)
                && !Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(pedido)) {
            // Denegado del todo (o Android exige hacerlo desde ajustes, como "todo el tiempo")
            Toast.makeText(this, "Actívalo en los ajustes de la app → Permisos", Toast.LENGTH_LONG).show();
            abrirAjustesApp();
            return;
        }
        pintar();
        if (activandoTodos) lista.postDelayed(this::siguientePendiente, 400);
    }

    private void abrir(Intent i) {
        esperandoAjustes = true;
        try {
            startActivity(i);
        } catch (Exception e) {
            abrirAjustesApp();
        }
    }

    private void abrirAjustesApp() {
        esperandoAjustes = true;
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    /** ¿Falta algo imprescindible? (para avisar desde la pantalla principal) */
    static int faltanImprescindibles(Context c) {
        int n = 0;
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) n++;
        if (Build.VERSION.SDK_INT >= 29 && c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) n++;
        if (!c.getSystemService(NotificationManager.class).areNotificationsEnabled()) n++;
        if (!c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName())) n++;
        if (Build.VERSION.SDK_INT >= 28 && c.getSystemService(ActivityManager.class).isBackgroundRestricted()) n++;
        if (Build.VERSION.SDK_INT >= 28 && !c.getSystemService(LocationManager.class).isLocationEnabled()) n++;
        return n;
    }
}
