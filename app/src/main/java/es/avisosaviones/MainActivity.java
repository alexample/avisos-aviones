package es.avisosaviones;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMISOS = 1;
    private static final int ID_DENTRO = 1001, ID_FUERA = 1002, ID_AMBAS = 1003;

    /** Campo con interruptor: el interruptor activa/desactiva el filtro sin borrar el valor. */
    private static final class Filtro {
        final String clave;
        final Switch sw;
        final EditText campo;
        Filtro(String clave, Switch sw, EditText campo) { this.clave = clave; this.sw = sw; this.campo = campo; }
    }

    private EditText radio, radioExt, intervalo;
    private Switch soloHeli;
    private RadioGroup zona;
    private LinearLayout bloqueRadioExt;
    private Filtro fDistMin, fAltitud, fMat, fInd, fSqk, fOri, fDes;
    private final List<Filtro> filtros = new ArrayList<>();
    private Button boton;
    private TextView estado;

    private final BroadcastReceiver receptorEstado = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String txt = intent.getStringExtra(VigilanciaService.EXTRA_TEXTO);
            if (txt != null) estado.setText(txt);
            refrescarBoton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        construirInterfaz();
        cargarAjustes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(VigilanciaService.ACCION_ESTADO);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receptorEstado, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receptorEstado, f);
        }
        refrescarBoton();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receptorEstado);
    }

    // ================================================================ interfaz

    private void construirInterfaz() {
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad + dp(24), pad, pad);

        TextView titulo = new TextView(this);
        titulo.setText("✈️ Avisos Aviones");
        titulo.setTextSize(26);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(titulo);

        // ---------- Arriba: lo que más se usa
        boton = new Button(this);
        boton.setTextSize(18);
        boton.setOnClickListener(v -> alternar());
        LinearLayout.LayoutParams lpBoton = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lpBoton.topMargin = dp(12);
        col.addView(boton, lpBoton);

        estado = new TextView(this);
        estado.setPadding(0, dp(8), 0, dp(4));
        estado.setText("Parado.");
        col.addView(estado);

        Button historial = new Button(this);
        historial.setText("📋 Historial de pasos");
        historial.setOnClickListener(v -> startActivity(new Intent(this, HistorialActivity.class)));
        col.addView(historial);

        // ---------- 1. Zona
        LinearLayout zonaTarjeta = tarjeta(col, "📍 Zona");
        radio = campoSimple(zonaTarjeta, "Radio de aviso (km)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        etiqueta(zonaTarjeta, "Avisar de los que están:");
        zona = new RadioGroup(this);
        zona.addView(opcion(ID_DENTRO, "Dentro del radio de aviso"));
        zona.addView(opcion(ID_FUERA, "Fuera del radio (hasta el radio ampliado)"));
        zona.addView(opcion(ID_AMBAS, "Dentro y fuera (hasta el radio ampliado)"));
        zona.setOnCheckedChangeListener((g, id) -> bloqueRadioExt.setVisibility(id == ID_DENTRO ? View.GONE : View.VISIBLE));
        zonaTarjeta.addView(zona);

        bloqueRadioExt = new LinearLayout(this);
        bloqueRadioExt.setOrientation(LinearLayout.VERTICAL);
        radioExt = campoSimple(bloqueRadioExt, "Radio ampliado (km)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        zonaTarjeta.addView(bloqueRadioExt);

        fDistMin = filtro(zonaTarjeta, Ajustes.DIST_MIN_KM, "Distancia mínima (km)",
                "No avisar de lo que esté más cerca de esto", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        // ---------- 2. Aeronave
        LinearLayout aeroTarjeta = tarjeta(col, "✈️ Aeronave");
        soloHeli = new Switch(this);
        soloHeli.setText("Solo helicópteros 🚁");
        soloHeli.setPadding(0, dp(6), 0, dp(6));
        aeroTarjeta.addView(soloHeli);
        int texto = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        fAltitud = filtro(aeroTarjeta, Ajustes.ALTITUD_MAX_M, "Altitud máxima (m)", "ej: 3000", InputType.TYPE_CLASS_NUMBER);
        fMat = filtro(aeroTarjeta, Ajustes.F_MATRICULAS, "Matrículas", "ej: EC-MXV, EC-*", texto);
        fInd = filtro(aeroTarjeta, Ajustes.F_INDICATIVOS, "Indicativos", "ej: IBE*, !RYR*", texto);
        fSqk = filtro(aeroTarjeta, Ajustes.F_SQUAWK, "Squawk", "ej: 7700, 7600, 7500", texto);

        // ---------- 3. Ruta
        LinearLayout rutaTarjeta = tarjeta(col, "🛫 Ruta (código IATA)");
        fOri = filtro(rutaTarjeta, Ajustes.F_ORIGEN, "Origen", "ej: MAD, BCN", texto);
        fDes = filtro(rutaTarjeta, Ajustes.F_DESTINO, "Destino", "ej: LHR, PMI", texto);

        TextView ayuda = new TextView(this);
        ayuda.setTextSize(12);
        ayuda.setPadding(dp(4), dp(8), dp(4), 0);
        ayuda.setText("Cada filtro tiene su interruptor: apagado se ignora pero guarda lo escrito. "
                + "Vacío = sin filtro. Varios valores con comas; * es comodín; ! delante excluye.");
        col.addView(ayuda);

        Button guardar = new Button(this);
        guardar.setText("💾 Guardar ajustes");
        guardar.setOnClickListener(v -> {
            if (guardarAjustes()) Toast.makeText(this,
                    Ajustes.activo(this) ? "Guardado. Se aplica en la próxima comprobación." : "Guardado.",
                    Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lpG = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpG.topMargin = dp(8);
        col.addView(guardar, lpG);

        // ---------- 4. Avanzado
        LinearLayout avTarjeta = tarjeta(col, "⚙️ Avanzado");
        intervalo = campoSimple(avTarjeta, "Comprobar cada (segundos, mínimo 5)", InputType.TYPE_CLASS_NUMBER);
        Button bateria = new Button(this);
        bateria.setText("Quitar ahorro de batería (recomendado)");
        bateria.setOnClickListener(v -> pedirSinOptimizacionBateria());
        avTarjeta.addView(bateria);

        TextView nota = new TextView(this);
        nota.setTextSize(12);
        nota.setPadding(dp(4), dp(16), dp(4), 0);
        nota.setText("Datos: airplanes.live / adsb.lol, rutas de Flightradar24 y adsbdb. "
                + "Los aparatos que no emiten ADS-B (muchos helicópteros militares o policiales) no aparecen.");
        col.addView(nota);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    private LinearLayout tarjeta(LinearLayout padre, String titulo) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(Color.parseColor("#F1F5FA"));
        fondo.setCornerRadius(dp(14));
        t.setBackground(fondo);

        TextView tt = new TextView(this);
        tt.setText(titulo);
        tt.setTextSize(18);
        tt.setTypeface(Typeface.DEFAULT_BOLD);
        t.addView(tt);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        padre.addView(t, lp);
        return t;
    }

    private void etiqueta(LinearLayout padre, String txt) {
        TextView t = new TextView(this);
        t.setText(txt);
        t.setPadding(0, dp(8), 0, 0);
        padre.addView(t);
    }

    private EditText campoSimple(LinearLayout padre, String etiqueta, int tipo) {
        etiqueta(padre, etiqueta);
        EditText e = new EditText(this);
        e.setInputType(tipo);
        e.setSingleLine(true);
        padre.addView(e);
        return e;
    }

    private Filtro filtro(LinearLayout padre, String clave, String etiqueta, String pista, int tipo) {
        Switch sw = new Switch(this);
        sw.setText(etiqueta);
        sw.setPadding(0, dp(10), 0, 0);
        padre.addView(sw);
        EditText e = new EditText(this);
        e.setInputType(tipo);
        e.setSingleLine(true);
        e.setHint(pista);
        padre.addView(e);
        sw.setOnCheckedChangeListener((b, on) -> { e.setEnabled(on); e.setAlpha(on ? 1f : 0.4f); });
        Filtro f = new Filtro(clave, sw, e);
        filtros.add(f);
        return f;
    }

    private RadioButton opcion(int id, String txt) {
        RadioButton rb = new RadioButton(this);
        rb.setId(id);
        rb.setText(txt);
        return rb;
    }

    // ================================================================ ajustes

    private static String numero(float f) {
        return f == (int) f ? String.valueOf((int) f) : String.valueOf(f);
    }

    private static float leerFloat(EditText e, float porDefecto) {
        String t = e.getText().toString().trim().replace(',', '.');
        return t.isEmpty() ? porDefecto : Float.parseFloat(t);
    }

    private void cargarAjustes() {
        SharedPreferences p = Ajustes.prefs(this);
        radio.setText(numero(Ajustes.radioKm(this)));
        radioExt.setText(numero(Ajustes.radioExtKm(this)));
        intervalo.setText(String.valueOf(Ajustes.intervaloS(this)));
        soloHeli.setChecked(Ajustes.soloHelicopteros(this));
        int mz = Ajustes.modoZona(this);
        zona.check(mz == Ajustes.ZONA_FUERA ? ID_FUERA : mz == Ajustes.ZONA_AMBAS ? ID_AMBAS : ID_DENTRO);
        bloqueRadioExt.setVisibility(mz == Ajustes.ZONA_DENTRO ? View.GONE : View.VISIBLE);

        fDistMin.campo.setText(numero(p.getFloat(Ajustes.DIST_MIN_KM, 0f)));
        int alt = p.getInt(Ajustes.ALTITUD_MAX_M, 0);
        fAltitud.campo.setText(alt > 0 ? String.valueOf(alt) : "");
        for (Filtro f : new Filtro[]{fMat, fInd, fSqk, fOri, fDes}) {
            f.campo.setText(p.getString(f.clave, ""));
        }
        for (Filtro f : filtros) {
            boolean on = Ajustes.usa(this, f.clave);
            f.sw.setChecked(on);
            f.campo.setEnabled(on);
            f.campo.setAlpha(on ? 1f : 0.4f);
        }
    }

    private boolean guardarAjustes() {
        try {
            float r = leerFloat(radio, 2f);
            String ti = intervalo.getText().toString().trim();
            int i = ti.isEmpty() ? 15 : Integer.parseInt(ti);
            if (r <= 0 || r > 400) throw new NumberFormatException();
            if (i < 5) i = 5;
            float ext = leerFloat(radioExt, 50f);
            float min = leerFloat(fDistMin.campo, 0f);
            String ta = fAltitud.campo.getText().toString().trim();
            int alt = ta.isEmpty() ? 0 : Integer.parseInt(ta);
            if (ext <= 0 || ext > 400 || min < 0 || alt < 0) throw new NumberFormatException();
            int idZona = zona.getCheckedRadioButtonId();
            int modo = idZona == ID_FUERA ? Ajustes.ZONA_FUERA : idZona == ID_AMBAS ? Ajustes.ZONA_AMBAS : Ajustes.ZONA_DENTRO;
            if (modo != Ajustes.ZONA_DENTRO && ext <= r) {
                Toast.makeText(this, "El radio ampliado tiene que ser mayor que el radio de aviso", Toast.LENGTH_LONG).show();
                return false;
            }
            SharedPreferences.Editor ed = Ajustes.prefs(this).edit()
                    .putFloat(Ajustes.RADIO_KM, r)
                    .putFloat(Ajustes.RADIO_EXT_KM, ext)
                    .putInt(Ajustes.MODO_ZONA, modo)
                    .putInt(Ajustes.INTERVALO_S, i)
                    .putBoolean(Ajustes.SOLO_HELICOPTEROS, soloHeli.isChecked())
                    .putFloat(Ajustes.DIST_MIN_KM, min)
                    .putInt(Ajustes.ALTITUD_MAX_M, alt);
            for (Filtro f : new Filtro[]{fMat, fInd, fSqk, fOri, fDes}) {
                ed.putString(f.clave, f.campo.getText().toString().trim());
            }
            for (Filtro f : filtros) {
                ed.putBoolean(Ajustes.claveUsar(f.clave), f.sw.isChecked());
            }
            ed.apply();
            return true;
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Revisa los números (radios entre 0 y 400 km, nada negativo)", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    // ================================================================ vigilancia

    private void alternar() {
        if (Ajustes.activo(this)) {
            Intent i = new Intent(this, VigilanciaService.class).setAction(VigilanciaService.ACCION_PARAR);
            startService(i);
            Ajustes.prefs(this).edit().putBoolean(Ajustes.ACTIVO, false).apply();
            estado.setText("Parado.");
            refrescarBoton();
            return;
        }
        if (!guardarAjustes()) return;
        if (pedirPermisosSiFaltan()) return; // seguirá en onRequestPermissionsResult
        arrancar();
    }

    private void arrancar() {
        Intent i = new Intent(this, VigilanciaService.class);
        startForegroundService(i);
        Ajustes.prefs(this).edit().putBoolean(Ajustes.ACTIVO, true).apply();
        estado.setText("Iniciando… buscando tu ubicación.");
        refrescarBoton();
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
            Toast.makeText(this, "Sin permiso de ubicación no puedo saber qué tienes encima.", Toast.LENGTH_LONG).show();
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

    private void refrescarBoton() {
        boolean on = Ajustes.activo(this);
        boton.setText(on ? "⏹  Parar vigilancia" : "▶  Empezar a vigilar");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
