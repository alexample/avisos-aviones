package es.avisosaviones;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMISOS = 1;

    private EditText radio, altitud, intervalo;
    private EditText distMin, radioExt, fMat, fInd, fOri, fDes, fSqk;
    private android.widget.RadioGroup zona;
    private static final int ID_DENTRO = 1001, ID_FUERA = 1002, ID_AMBAS = 1003;
    private CheckBox soloHeli;
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

    private void construirInterfaz() {
        int pad = dp(20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad + dp(24), pad, pad);

        TextView titulo = new TextView(this);
        titulo.setText("✈️ Avisos Aviones");
        titulo.setTextSize(26);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(titulo);

        TextView sub = new TextView(this);
        sub.setText("Te avisa cuando un avión o helicóptero pasa cerca de donde estás.");
        sub.setPadding(0, dp(6), 0, dp(18));
        col.addView(sub);

        radio = campo(col, "Radio de aviso (km)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        altitud = campo(col, "Altitud máxima en metros (0 = sin límite)", InputType.TYPE_CLASS_NUMBER);
        intervalo = campo(col, "Comprobar cada (segundos, mínimo 5)", InputType.TYPE_CLASS_NUMBER);

        soloHeli = new CheckBox(this);
        soloHeli.setText("Solo helicópteros 🚁");
        soloHeli.setPadding(0, dp(8), 0, dp(8));
        col.addView(soloHeli);

        // ---------- Filtros
        TextView tf = new TextView(this);
        tf.setText("🔎 Filtros (opcionales)");
        tf.setTextSize(18);
        tf.setTypeface(Typeface.DEFAULT_BOLD);
        tf.setPadding(0, dp(16), 0, 0);
        col.addView(tf);

        TextView ayuda = new TextView(this);
        ayuda.setTextSize(12);
        ayuda.setText("Vacío = sin filtro. Varios valores separados por comas. "
                + "Comodín *  (EC-* = matrículas españolas). Con ! delante se excluye (!RYR*).");
        col.addView(ayuda);

        TextView tz = new TextView(this);
        tz.setText("Avisar de los que están:");
        tz.setPadding(0, dp(10), 0, 0);
        col.addView(tz);
        zona = new android.widget.RadioGroup(this);
        zona.addView(opcion(ID_DENTRO, "Dentro del radio de aviso"));
        zona.addView(opcion(ID_FUERA, "Fuera del radio (hasta el radio ampliado)"));
        zona.addView(opcion(ID_AMBAS, "Dentro y fuera (hasta el radio ampliado)"));
        col.addView(zona);

        radioExt = campo(col, "Radio ampliado (km)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        distMin = campo(col, "Distancia mínima (km, 0 = sin mínimo)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int texto = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        fMat = campo(col, "Matrículas  (ej: EC-MXV, EC-*)", texto);
        fInd = campo(col, "Indicativos  (ej: IBE*, RYR9K)", texto);
        fOri = campo(col, "Origen, código IATA  (ej: MAD, BCN)", texto);
        fDes = campo(col, "Destino, código IATA  (ej: LHR, PMI)", texto);
        fSqk = campo(col, "Squawk  (ej: 7700, 7600, 7500)", texto);

        Button guardar = new Button(this);
        guardar.setText("💾 Guardar ajustes");
        guardar.setOnClickListener(v -> {
            if (guardarAjustes()) Toast.makeText(this,
                    Ajustes.activo(this) ? "Guardado. Se aplica en la próxima comprobación." : "Guardado.",
                    Toast.LENGTH_SHORT).show();
        });
        col.addView(guardar);

        boton = new Button(this);
        boton.setTextSize(18);
        boton.setOnClickListener(v -> alternar());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lp.topMargin = dp(12);
        col.addView(boton, lp);

        Button historial = new Button(this);
        historial.setText("📋 Historial de pasos");
        historial.setOnClickListener(v -> startActivity(new Intent(this, HistorialActivity.class)));
        col.addView(historial);

        Button bateria = new Button(this);
        bateria.setText("Quitar ahorro de batería (recomendado)");
        bateria.setOnClickListener(v -> pedirSinOptimizacionBateria());
        col.addView(bateria);

        estado = new TextView(this);
        estado.setPadding(0, dp(18), 0, 0);
        estado.setGravity(Gravity.START);
        estado.setText("Parado.");
        col.addView(estado);

        TextView nota = new TextView(this);
        nota.setTextSize(12);
        nota.setPadding(0, dp(24), 0, 0);
        nota.setText("Datos: airplanes.live / adsb.lol (redes ADS-B comunitarias). "
                + "Los aparatos que no emiten ADS-B (muchos helicópteros militares o policiales) no aparecen.");
        col.addView(nota);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    private EditText campo(LinearLayout col, String etiqueta, int tipo) {
        TextView t = new TextView(this);
        t.setText(etiqueta);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
        EditText e = new EditText(this);
        e.setInputType(tipo);
        e.setSingleLine(true);
        col.addView(e);
        return e;
    }

    private android.widget.RadioButton opcion(int id, String txt) {
        android.widget.RadioButton rb = new android.widget.RadioButton(this);
        rb.setId(id);
        rb.setText(txt);
        return rb;
    }

    private static String numero(float f) {
        return f == (int) f ? String.valueOf((int) f) : String.valueOf(f);
    }

    private static float leerFloat(EditText e, float porDefecto) {
        String t = e.getText().toString().trim().replace(',', '.');
        return t.isEmpty() ? porDefecto : Float.parseFloat(t);
    }

    private void cargarAjustes() {
        float r = Ajustes.radioKm(this);
        radio.setText(r == (int) r ? String.valueOf((int) r) : String.valueOf(r));
        altitud.setText(String.valueOf(Ajustes.altitudMaxM(this)));
        intervalo.setText(String.valueOf(Ajustes.intervaloS(this)));
        soloHeli.setChecked(Ajustes.soloHelicopteros(this));
        int mz = Ajustes.modoZona(this);
        zona.check(mz == Ajustes.ZONA_FUERA ? ID_FUERA : mz == Ajustes.ZONA_AMBAS ? ID_AMBAS : ID_DENTRO);
        radioExt.setText(numero(Ajustes.radioExtKm(this)));
        distMin.setText(numero(Ajustes.distMinKm(this)));
        fMat.setText(Ajustes.fMatriculas(this));
        fInd.setText(Ajustes.fIndicativos(this));
        fOri.setText(Ajustes.fOrigen(this));
        fDes.setText(Ajustes.fDestino(this));
        fSqk.setText(Ajustes.fSquawk(this));
    }

    private boolean guardarAjustes() {
        try {
            float r = Float.parseFloat(radio.getText().toString().replace(',', '.'));
            int a = altitud.getText().length() == 0 ? 0 : Integer.parseInt(altitud.getText().toString());
            int i = Integer.parseInt(intervalo.getText().toString());
            if (r <= 0 || r > 400) throw new NumberFormatException();
            if (i < 5) i = 5;
            float ext = leerFloat(radioExt, 50f);
            float min = leerFloat(distMin, 0f);
            if (ext <= 0 || ext > 400 || min < 0) throw new NumberFormatException();
            int idZona = zona.getCheckedRadioButtonId();
            int modo = idZona == ID_FUERA ? Ajustes.ZONA_FUERA : idZona == ID_AMBAS ? Ajustes.ZONA_AMBAS : Ajustes.ZONA_DENTRO;
            if (modo != Ajustes.ZONA_DENTRO && ext <= r) {
                Toast.makeText(this, "El radio ampliado tiene que ser mayor que el radio de aviso", Toast.LENGTH_LONG).show();
                return false;
            }
            Ajustes.prefs(this).edit()
                    .putFloat(Ajustes.RADIO_EXT_KM, ext)
                    .putFloat(Ajustes.DIST_MIN_KM, min)
                    .putInt(Ajustes.MODO_ZONA, modo)
                    .putString(Ajustes.F_MATRICULAS, fMat.getText().toString().trim())
                    .putString(Ajustes.F_INDICATIVOS, fInd.getText().toString().trim())
                    .putString(Ajustes.F_ORIGEN, fOri.getText().toString().trim())
                    .putString(Ajustes.F_DESTINO, fDes.getText().toString().trim())
                    .putString(Ajustes.F_SQUAWK, fSqk.getText().toString().trim())
                    .putFloat(Ajustes.RADIO_KM, r)
                    .putInt(Ajustes.ALTITUD_MAX_M, Math.max(0, a))
                    .putInt(Ajustes.INTERVALO_S, i)
                    .putBoolean(Ajustes.SOLO_HELICOPTEROS, soloHeli.isChecked())
                    .apply();
            return true;
        } catch (NumberFormatException ex) {
            Toast.makeText(this, "Revisa los valores (radios entre 0 y 400 km, distancia mínima ≥ 0)", Toast.LENGTH_LONG).show();
            return false;
        }
    }

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
