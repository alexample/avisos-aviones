package es.avisosaviones;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Crear o editar una alerta. */
public class AlertaActivity extends Activity {

    static final String EXTRA_ID = "id";

    private static final float[] DISTANCIAS = {0.5f, 1, 2, 3, 5, 7, 10, 15, 20, 30, 40, 50, 75, 100, 150, 200, 250};
    private static final float[] MINIMOS = {0.5f, 1, 2, 3, 5, 10, 20, 50};
    private static final int[] ALTITUDES = {0, 300, 500, 1000, 1500, 2000, 3000, 5000, 8000};

    private Alerta alerta;
    private boolean nueva;

    private EditText nombre, matriculas, indicativos, vuelos, squawks;
    private TextView txtAvisoLimite;
    private ViewGroup chipsExclOrigen, chipsExclDestino;
    private TextView txtDistancia, txtMinimo, txtAltitud, txtTipoAyuda;
    private SeekBar barraDistancia, barraMinimo, barraAltitud;
    private Switch swMinimo;
    private final List<TextView> chipsTipo = new ArrayList<>();
    private TextView chipMilitar, chipEmergencia;
    private final List<TextView> chipsServicio = new ArrayList<>();
    private TextView txtSonido;
    private static final int REQ_SONIDO = 7;
    private ViewGroup chipsModelo;
    private ViewGroup chipsOrigen, chipsDestino;
    private ScrollView scroll;
    private LinearLayout contenido;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.AZUL_OSCURO);
        String id = getIntent().getStringExtra(EXTRA_ID);
        alerta = id != null ? Alertas.buscar(this, id) : null;
        nueva = alerta == null;
        if (nueva) alerta = new Alerta();
        construir();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void construir() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Ui.FONDO);

        // ---------- Barra superior
        LinearLayout barra = new LinearLayout(this);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        barra.setBackgroundColor(Ui.AZUL_OSCURO);
        barra.setPadding(dp(8), dp(12), dp(16), dp(12));
        TextView atras = Ui.texto(this, "←", 24, Color.WHITE, true);
        atras.setPadding(dp(12), 0, dp(12), 0);
        atras.setOnClickListener(v -> finish());
        barra.addView(atras);
        barra.addView(Ui.texto(this, nueva ? "Nueva alerta" : "Editar alerta", 20, Color.WHITE, true));
        raiz.addView(barra);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(6), dp(14), dp(24));
        contenido = col;

        // ---------- Nombre
        LinearLayout tNombre = Ui.tarjeta(this, "Nombre", null);
        nombre = new EditText(this);
        nombre.setText(alerta.nombre);
        nombre.setSingleLine(true);
        nombre.setSelectAllOnFocus(nueva);
        nombre.setTextSize(17);
        tNombre.addView(nombre);
        col.addView(tNombre, Ui.ancho(12, this));

        // ---------- Qué
        LinearLayout tTipo = Ui.tarjeta(this, "¿De qué quieres que te avise?",
                "Marca uno o varios. Si no marcas ninguno, de todo.");
        ViewGroup filaTipo = Ui.filaChips(this);
        for (int i = 0; i < Tipos.CLASES.length; i++) {
            final String c = Tipos.CLASES[i];
            TextView ch = Ui.chip(this, Tipos.CLASES_TXT[i]);
            ch.setOnClickListener(v -> {
                if (alerta.clases.contains(c)) alerta.clases.remove(c); else alerta.clases.add(c);
                refrescarTipo();
            });
            chipsTipo.add(ch);
            filaTipo.addView(ch);
        }
        tTipo.addView(filaTipo, Ui.ancho(8, this));

        tTipo.addView(Ui.texto(this, "Y además, solo si es…", 14, Ui.TEXTO, true), Ui.ancho(14, this));
        ViewGroup filaSolo = Ui.filaChips(this);
        chipMilitar = Ui.chip(this, "🎖️ Militar");
        chipMilitar.setOnClickListener(v -> { alerta.soloMilitar = !alerta.soloMilitar; refrescarTipo(); refrescarDistancias(); });
        chipEmergencia = Ui.chip(this, "🚨 Emergencia");
        chipEmergencia.setOnClickListener(v -> { alerta.soloEmergencia = !alerta.soloEmergencia; refrescarTipo(); refrescarDistancias(); });
        filaSolo.addView(chipMilitar);
        filaSolo.addView(chipEmergencia);
        tTipo.addView(filaSolo, Ui.ancho(8, this));
        txtTipoAyuda = Ui.texto(this, "", 13, Ui.TEXTO_SUAVE, false);
        tTipo.addView(txtTipoAyuda, Ui.ancho(10, this));
        col.addView(tTipo, Ui.ancho(12, this));

        // ---------- Servicio
        LinearLayout tServ = Ui.tarjeta(this, "🏷️ Servicio",
                "Solo funciona con aeronaves que hayas etiquetado: mantén pulsado un paso en el Historial, o añade una matrícula aquí.");
        ViewGroup filaServ = Ui.filaChips(this);
        for (int i = 0; i < Servicios.IDS.length; i++) {
            final String id = Servicios.IDS[i];
            TextView ch = Ui.chip(this, Servicios.TXT[i]);
            ch.setOnClickListener(v -> {
                if (alerta.servicios.contains(id)) alerta.servicios.remove(id); else alerta.servicios.add(id);
                refrescarServicios();
                refrescarTipo();
                refrescarDistancias();
            });
            chipsServicio.add(ch);
            filaServ.addView(ch);
        }
        tServ.addView(filaServ, Ui.ancho(8, this));
        TextView gestionar = Ui.botonSecundario(this, "Aeronaves etiquetadas…");
        gestionar.setOnClickListener(v -> gestionarEtiquetas());
        tServ.addView(gestionar, Ui.ancho(12, this));
        col.addView(tServ, Ui.ancho(12, this));

        // ---------- Modelo
        LinearLayout tModelo = Ui.tarjeta(this, "🔧 Modelo",
                "Opcional. Escribe un modelo o fabricante (A320, 737, Cessna, Robinson…) y elígelo.");
        chipsModelo = Ui.filaChips(this);
        tModelo.addView(chipsModelo, Ui.ancho(6, this));
        tModelo.addView(buscadorModelo());
        if (Tipos.todos(this).isEmpty()) {
            tModelo.addView(Ui.texto(this, "⚠️ No se ha cargado la lista de modelos; escribe el código OACI (A20N, B738…) y pulsa Intro.",
                    13, Ui.ROJO, false), Ui.ancho(8, this));
        }
        col.addView(tModelo, Ui.ancho(12, this));

        // ---------- Distancia
        LinearLayout tDist = Ui.tarjeta(this, "📍 ¿A qué distancia de ti?", null);
        txtDistancia = Ui.texto(this, "", 26, Ui.AZUL, true);
        tDist.addView(txtDistancia, Ui.ancho(4, this));
        barraDistancia = barra(DISTANCIAS.length,
                alerta.sinLimite() ? DISTANCIAS.length : indiceCercano(DISTANCIAS, alerta.radioKm), () -> {
            int p = barraDistancia.getProgress();
            alerta.radioKm = p >= DISTANCIAS.length ? 0 : DISTANCIAS[p];
            refrescarDistancias();
        });
        tDist.addView(barraDistancia, Ui.ancho(4, this));
        txtAvisoLimite = Ui.texto(this, "", 13, Ui.TEXTO_SUAVE, false);
        tDist.addView(txtAvisoLimite, Ui.ancho(2, this));

        swMinimo = new Switch(this);
        swMinimo.setText("Ignorar lo que pase muy cerca");
        swMinimo.setTextSize(15);
        swMinimo.setTextColor(Ui.TEXTO);
        swMinimo.setChecked(alerta.distMinKm > 0);
        tDist.addView(swMinimo, Ui.ancho(14, this));
        txtMinimo = Ui.texto(this, "", 14, Ui.TEXTO_SUAVE, false);
        tDist.addView(txtMinimo, Ui.ancho(4, this));
        barraMinimo = barra(MINIMOS.length - 1, indiceCercano(MINIMOS, alerta.distMinKm > 0 ? alerta.distMinKm : 1), () -> {
            if (swMinimo.isChecked()) alerta.distMinKm = MINIMOS[barraMinimo.getProgress()];
            refrescarDistancias();
        });
        tDist.addView(barraMinimo);
        swMinimo.setOnCheckedChangeListener((b, on) -> {
            alerta.distMinKm = on ? MINIMOS[barraMinimo.getProgress()] : 0;
            refrescarDistancias();
        });
        col.addView(tDist, Ui.ancho(12, this));

        // ---------- Ruta
        LinearLayout tRuta = Ui.tarjeta(this, "🛫 Ruta",
                "Opcional. Escribe una ciudad, un aeropuerto o su código y elígelo de la lista.");
        tRuta.addView(Ui.texto(this, "Sale de", 14, Ui.TEXTO, true), Ui.ancho(10, this));
        chipsOrigen = Ui.filaChips(this);
        tRuta.addView(chipsOrigen, Ui.ancho(6, this));
        tRuta.addView(buscadorAeropuerto("Ej: Madrid, Barajas, MAD…", alerta.origenes, chipsOrigen));
        tRuta.addView(Ui.texto(this, "Va a", 14, Ui.TEXTO, true), Ui.ancho(14, this));
        chipsDestino = Ui.filaChips(this);
        tRuta.addView(chipsDestino, Ui.ancho(6, this));
        tRuta.addView(buscadorAeropuerto("Ej: Londres, Heathrow, LHR…", alerta.destinos, chipsDestino));
        if (Aeropuertos.todos(this).isEmpty()) {
            tRuta.addView(Ui.texto(this, "⚠️ No se ha cargado la lista de aeropuertos; escribe el código IATA y pulsa Intro.",
                    13, Ui.ROJO, false), Ui.ancho(8, this));
        }
        col.addView(tRuta, Ui.ancho(12, this));

        // ---------- Ignorar aeropuertos
        LinearLayout tIgn = Ui.tarjeta(this, "🚫 Ignorar vuelos",
                "Por ejemplo, los que despegan del aeropuerto que tienes al lado.");
        tIgn.addView(Ui.texto(this, "Que salen de", 14, Ui.TEXTO, true), Ui.ancho(10, this));
        chipsExclOrigen = Ui.filaChips(this);
        tIgn.addView(chipsExclOrigen, Ui.ancho(6, this));
        tIgn.addView(buscadorAeropuerto("Buscar aeropuerto…", alerta.excluirOrigenes, chipsExclOrigen));
        tIgn.addView(Ui.texto(this, "Que llegan a", 14, Ui.TEXTO, true), Ui.ancho(14, this));
        chipsExclDestino = Ui.filaChips(this);
        tIgn.addView(chipsExclDestino, Ui.ancho(6, this));
        tIgn.addView(buscadorAeropuerto("Buscar aeropuerto…", alerta.excluirDestinos, chipsExclDestino));
        TextView cercano = Ui.botonSecundario(this, "📍 Elegir un aeropuerto cerca de mí");
        cercano.setOnClickListener(v -> elegirAeropuertoCercano());
        tIgn.addView(cercano, Ui.ancho(14, this));
        col.addView(tIgn, Ui.ancho(12, this));

        // ---------- Más filtros (plegable)
        LinearLayout tMas = Ui.tarjeta(this, null, null);
        TextView cabMas = Ui.texto(this, "🔎 Más filtros  ▾", 17, Ui.TEXTO, true);
        tMas.addView(cabMas);
        LinearLayout cuerpoMas = new LinearLayout(this);
        cuerpoMas.setOrientation(LinearLayout.VERTICAL);
        tMas.addView(cuerpoMas);

        txtAltitud = Ui.texto(this, "", 15, Ui.TEXTO, false);
        cuerpoMas.addView(txtAltitud, Ui.ancho(12, this));
        barraAltitud = barra(ALTITUDES.length - 1, indiceCercanoInt(ALTITUDES, alerta.altMaxM), () -> {
            alerta.altMaxM = ALTITUDES[barraAltitud.getProgress()];
            refrescarAltitud();
        });
        cuerpoMas.addView(barraAltitud);

        cuerpoMas.addView(Ui.texto(this, "Matrícula", 15, Ui.TEXTO, false), Ui.ancho(14, this));
        matriculas = campo("Ej: EC-MXV   ·   EC-* (todas las españolas)", alerta.matriculas);
        cuerpoMas.addView(matriculas);
        cuerpoMas.addView(Ui.texto(this, "Aerolínea o indicativo", 15, Ui.TEXTO, false), Ui.ancho(14, this));
        indicativos = campo("Ej: IBE* (Iberia)  ·  !RYR* (todo menos Ryanair)", alerta.indicativos);
        cuerpoMas.addView(indicativos);
        cuerpoMas.addView(Ui.texto(this, "Número de vuelo", 15, Ui.TEXTO, false), Ui.ancho(14, this));
        vuelos = campo("Ej: IB3456  ·  FR82*", alerta.vuelos);
        cuerpoMas.addView(vuelos);
        cuerpoMas.addView(Ui.texto(this, "Squawk", 15, Ui.TEXTO, false), Ui.ancho(14, this));
        squawks = campo("Ej: 7700, 7600, 7500", alerta.squawks);
        squawks.setInputType(InputType.TYPE_CLASS_NUMBER);
        cuerpoMas.addView(squawks);
        android.text.TextWatcher alCambiar = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            @Override public void onTextChanged(CharSequence a, int b, int c, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { refrescarDistancias(); }
        };
        matriculas.addTextChangedListener(alCambiar);
        indicativos.addTextChangedListener(alCambiar);
        squawks.addTextChangedListener(alCambiar);
        cuerpoMas.addView(Ui.texto(this,
                "Puedes poner varios separados por comas. El * vale por cualquier cosa y el ! delante excluye.",
                12, Ui.TEXTO_SUAVE, false), Ui.ancho(6, this));

        boolean hayMas = alerta.altMaxM > 0 || !alerta.matriculas.isEmpty() || !alerta.indicativos.isEmpty()
                || !alerta.vuelos.isEmpty() || !alerta.squawks.isEmpty() || alerta.sinLimite();
        cuerpoMas.setVisibility(hayMas ? View.VISIBLE : View.GONE);
        cabMas.setText(hayMas ? "🔎 Más filtros  ▴" : "🔎 Más filtros  ▾");
        cabMas.setOnClickListener(v -> {
            boolean abrir = cuerpoMas.getVisibility() != View.VISIBLE;
            cuerpoMas.setVisibility(abrir ? View.VISIBLE : View.GONE);
            cabMas.setText(abrir ? "🔎 Más filtros  ▴" : "🔎 Más filtros  ▾");
        });
        col.addView(tMas, Ui.ancho(12, this));

        // ---------- Sonido
        LinearLayout tSon = Ui.tarjeta(this, "🔊 Sonido del aviso", null);
        LinearLayout filaSon = new LinearLayout(this);
        filaSon.setGravity(Gravity.CENTER_VERTICAL);
        txtSonido = Ui.texto(this, "", 16, Ui.TEXTO, false);
        filaSon.addView(txtSonido, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView probar = Ui.texto(this, "▶ Probar", 15, Ui.AZUL, true);
        probar.setPadding(dp(12), dp(8), dp(4), dp(8));
        probar.setOnClickListener(v -> Sonidos.probar(this, alerta.sonido));
        filaSon.addView(probar);
        tSon.addView(filaSon, Ui.ancho(8, this));
        TextView cambiar = Ui.botonSecundario(this, "Cambiar sonido");
        cambiar.setOnClickListener(v -> elegirSonido());
        tSon.addView(cambiar, Ui.ancho(10, this));
        Switch swVibrar = new Switch(this);
        swVibrar.setText("Vibrar");
        swVibrar.setTextSize(15);
        swVibrar.setTextColor(Ui.TEXTO);
        swVibrar.setChecked(alerta.vibrar);
        swVibrar.setOnCheckedChangeListener((b, on) -> alerta.vibrar = on);
        tSon.addView(swVibrar, Ui.ancho(12, this));
        col.addView(tSon, Ui.ancho(12, this));

        // ---------- Botones
        TextView guardar = Ui.botonPrincipal(this, "Guardar alerta", Ui.AZUL);
        guardar.setOnClickListener(v -> guardar());
        col.addView(guardar, Ui.ancho(20, this));
        if (!nueva) {
            TextView borrar = Ui.texto(this, "Eliminar alerta", 15, Ui.ROJO, true);
            borrar.setGravity(Gravity.CENTER);
            borrar.setPadding(0, dp(16), 0, dp(8));
            borrar.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("¿Eliminar «" + alerta.nombre + "»?")
                    .setPositiveButton("Eliminar", (d, w) -> {
                        Alertas.borrar(this, alerta.id);
                        Sonidos.borrarCanales(this, alerta.id);
                        finish();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show());
            col.addView(borrar);
        }

        ScrollView sv = new ScrollView(this);
        scroll = sv;
        sv.addView(col);
        raiz.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);
        Ui.ajustarABordes(raiz, barra);

        refrescarTipo();
        refrescarDistancias();
        refrescarAltitud();
        refrescarServicios();
        refrescarSonido();
        pintarChipsModelos();
        pintarChipsAeropuertos(alerta.origenes, chipsOrigen);
        pintarChipsAeropuertos(alerta.destinos, chipsDestino);
        pintarChipsAeropuertos(alerta.excluirOrigenes, chipsExclOrigen);
        pintarChipsAeropuertos(alerta.excluirDestinos, chipsExclDestino);
    }

    // ------------------------------------------------------------ piezas

    private SeekBar barra(int max, int valor, Runnable alCambiar) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(valor);
        s.setPadding(dp(8), dp(10), dp(8), dp(10));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { alCambiar.run(); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        return s;
    }

    private EditText campo(String pista, String valor) {
        EditText e = new EditText(this);
        e.setHint(pista);
        e.setText(valor);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        subirAlEscribir(e);
        return e;
    }

    /** Sube el campo para que quede visible encima del teclado (y deje sitio a la lista). */
    private void subirAlEscribir(View campo) {
        campo.setOnFocusChangeListener((v, foco) -> { if (foco) v.postDelayed(() -> llevarArriba(v), 350); });
        campo.setOnClickListener(v -> v.postDelayed(() -> llevarArriba(v), 350));
    }

    private void llevarArriba(View v) {
        android.graphics.Rect r = new android.graphics.Rect();
        v.getDrawingRect(r);
        contenido.offsetDescendantRectToMyCoords(v, r);
        scroll.smoothScrollTo(0, Math.max(0, r.top - dp(90)));
    }

    private static int indiceCercano(float[] valores, float v) {
        int mejor = 0;
        for (int i = 0; i < valores.length; i++) if (Math.abs(valores[i] - v) < Math.abs(valores[mejor] - v)) mejor = i;
        return mejor;
    }

    private static int indiceCercanoInt(int[] valores, int v) {
        int mejor = 0;
        for (int i = 0; i < valores.length; i++) if (Math.abs(valores[i] - v) < Math.abs(valores[mejor] - v)) mejor = i;
        return mejor;
    }

    private void refrescarTipo() {
        for (int i = 0; i < Tipos.CLASES.length; i++)
            Ui.marcarChip(this, chipsTipo.get(i), alerta.clases.contains(Tipos.CLASES[i]));
        Ui.marcarChip(this, chipMilitar, alerta.soloMilitar);
        Ui.marcarChip(this, chipEmergencia, alerta.soloEmergencia);
        StringBuilder sb = new StringBuilder("Te avisará de: ").append(alerta.textoQue().toLowerCase(Locale.getDefault())).append('.');
        if (alerta.clases.contains(Tipos.HELI)) sb.append("\nOjo: muchos helicópteros militares o policiales no emiten su posición.");
        if (alerta.soloEmergencia) sb.append("\nEmergencia = squawk 7700 (emergencia), 7600 (fallo de radio) o 7500 (secuestro).");
        if (alerta.soloMilitar) sb.append("\nMilitar = marcado así en la base de datos de airplanes.live.");
        txtTipoAyuda.setText(sb.toString());
    }

    // ------------------------------------------------------------ sonido

    private void refrescarSonido() {
        txtSonido.setText(Sonidos.nombre(this, alerta.sonido));
    }

    private void elegirSonido() {
        int n = Sonidos.PROPIOS.length;
        String[] opciones = new String[n + 3];
        String[] valores = new String[n + 3];
        for (int i = 0; i < n; i++) { opciones[i] = Sonidos.PROPIOS[i][1]; valores[i] = "raw:" + Sonidos.PROPIOS[i][0]; }
        opciones[n] = "📱 El del móvil";           valores[n] = Sonidos.POR_DEFECTO;
        opciones[n + 1] = "🎶 Elegir otro del móvil…"; valores[n + 1] = null;
        opciones[n + 2] = "🔕 Sin sonido";          valores[n + 2] = Sonidos.SILENCIO;
        int marcado = -1;
        for (int i = 0; i < valores.length; i++) if (valores[i] != null && valores[i].equals(alerta.sonido)) marcado = i;
        final String anterior = alerta.sonido;
        new AlertDialog.Builder(this)
                .setTitle("Sonido para «" + nombre.getText().toString().trim() + "»")
                .setSingleChoiceItems(opciones, marcado, (d, i) -> {
                    if (valores[i] == null) {
                        d.dismiss();
                        Sonidos.parar();
                        Intent pick = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                                        android.media.RingtoneManager.TYPE_NOTIFICATION | android.media.RingtoneManager.TYPE_RINGTONE
                                                | android.media.RingtoneManager.TYPE_ALARM)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Sonido del aviso")
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
                        startActivityForResult(pick, REQ_SONIDO);
                        return;
                    }
                    alerta.sonido = valores[i];
                    Sonidos.probar(this, alerta.sonido);
                    refrescarSonido();
                })
                .setPositiveButton("Aceptar", (d, w) -> Sonidos.parar())
                .setNegativeButton("Cancelar", (d, w) -> { Sonidos.parar(); alerta.sonido = anterior; refrescarSonido(); })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SONIDO && resultCode == RESULT_OK && data != null) {
            android.net.Uri u = data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (u != null) {
                alerta.sonido = u.toString();
                refrescarSonido();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Sonidos.parar();
    }

    // ------------------------------------------------------------ servicios

    private void refrescarServicios() {
        for (int i = 0; i < Servicios.IDS.length; i++)
            Ui.marcarChip(this, chipsServicio.get(i), alerta.servicios.contains(Servicios.IDS[i]));
    }

    private void gestionarEtiquetas() {
        List<String[]> l = Servicios.todas(this);
        String[] items = new String[l.size() + 1];
        items[0] = "➕ Añadir una matrícula";
        for (int i = 0; i < l.size(); i++) items[i + 1] = l.get(i)[1] + "   ✕";
        new AlertDialog.Builder(this)
                .setTitle(l.isEmpty() ? "Aún no has etiquetado ninguna" : "Toca una para quitarla")
                .setItems(items, (d, i) -> {
                    if (i == 0) { anadirMatricula(); return; }
                    String[] e = l.get(i - 1);
                    new AlertDialog.Builder(this)
                            .setMessage("¿Quitar «" + e[1] + "»?")
                            .setPositiveButton("Quitar", (d2, w) -> { Servicios.quitarClave(this, e[0]); gestionarEtiquetas(); })
                            .setNegativeButton("Cancelar", null)
                            .show();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void anadirMatricula() {
        EditText reg = new EditText(this);
        reg.setHint("Ej: EC-ABC");
        reg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout l = new LinearLayout(this);
        l.setPadding(dp(24), dp(8), dp(24), 0);
        l.addView(reg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Matrícula de la aeronave")
                .setView(l)
                .setPositiveButton("Siguiente", (d, w) -> {
                    String r = reg.getText().toString().trim().toUpperCase(Locale.ROOT);
                    if (r.length() < 3) return;
                    new AlertDialog.Builder(this)
                            .setTitle("¿Qué es " + r + "?")
                            .setItems(Servicios.TXT, (d2, i) -> {
                                Servicios.etiquetar(this, null, r, Servicios.IDS[i]);
                                Toast.makeText(this, r + " → " + Servicios.TXT[i], Toast.LENGTH_SHORT).show();
                            })
                            .show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------ modelos

    private AutoCompleteTextView buscadorModelo() {
        AutoCompleteTextView ac = new AutoCompleteTextView(this);
        ac.setHint("🔍  Ej: A320, 737, Cessna 172…");
        ac.setSingleLine(true);
        ac.setThreshold(1);
        ac.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ac.setImeOptions(EditorInfo.IME_ACTION_DONE);
        subirAlEscribir(ac);
        AdaptadorModelos ad = new AdaptadorModelos(this);
        ac.setAdapter(ad);
        ac.setOnItemClickListener((p, v, pos, id) -> {
            String codigo = ad.getItem(pos).codigo;
            if (!alerta.modelos.contains(codigo)) alerta.modelos.add(codigo);
            ac.setText("");
            pintarChipsModelos();
        });
        ac.setOnEditorActionListener((v, accion, ev) -> {
            String t = ac.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (t.matches("[A-Z0-9*?]{2,4}")) {
                if (!alerta.modelos.contains(t)) alerta.modelos.add(t);
                ac.setText("");
                pintarChipsModelos();
                return true;
            }
            return false;
        });
        return ac;
    }

    private void pintarChipsModelos() {
        chipsModelo.removeAllViews();
        if (alerta.modelos.isEmpty()) {
            chipsModelo.addView(Ui.texto(this, "Cualquier modelo", 14, Ui.TEXTO_SUAVE, false));
        } else {
            for (String codigo : new ArrayList<>(alerta.modelos)) {
                TextView ch = Ui.chip(this, Tipos.etiqueta(this, codigo) + "   ✕");
                Ui.marcarChip(this, ch, true);
                ch.setOnClickListener(v -> { alerta.modelos.remove(codigo); pintarChipsModelos(); });
                chipsModelo.addView(ch);
            }
        }
        refrescarDistancias();
    }

    /** Sugerencias de modelos mientras se escribe. */
    private static final class AdaptadorModelos extends BaseAdapter implements Filterable {
        private final Context c;
        private List<Tipos.Tipo> datos = new ArrayList<>();
        AdaptadorModelos(Context c) { this.c = c; }
        @Override public int getCount() { return datos.size(); }
        @Override public Tipos.Tipo getItem(int i) { return datos.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View reciclada, ViewGroup padre) {
            LinearLayout fila;
            TextView t1, t2;
            if (reciclada instanceof LinearLayout) {
                fila = (LinearLayout) reciclada;
                t1 = (TextView) fila.getChildAt(0);
                t2 = (TextView) fila.getChildAt(1);
            } else {
                fila = new LinearLayout(c);
                fila.setOrientation(LinearLayout.VERTICAL);
                fila.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
                t1 = Ui.texto(c, "", 16, Ui.TEXTO, true);
                t2 = Ui.texto(c, "", 13, Ui.TEXTO_SUAVE, false);
                fila.addView(t1);
                fila.addView(t2);
            }
            Tipos.Tipo t = datos.get(i);
            t1.setText(t.titulo());
            t2.setText(t.detalle());
            return fila;
        }
        @Override public Filter getFilter() {
            return new Filter() {
                @Override protected FilterResults performFiltering(CharSequence q) {
                    FilterResults r = new FilterResults();
                    List<Tipos.Tipo> l = q == null ? new ArrayList<>() : Tipos.buscar(c, q.toString(), 30);
                    r.values = l;
                    r.count = l.size();
                    return r;
                }
                @SuppressWarnings("unchecked")
                @Override protected void publishResults(CharSequence q, FilterResults r) {
                    datos = r.values != null ? (List<Tipos.Tipo>) r.values : new ArrayList<>();
                    notifyDataSetChanged();
                }
                @Override public CharSequence convertResultToString(Object o) {
                    return o instanceof Tipos.Tipo ? ((Tipos.Tipo) o).codigo : "";
                }
            };
        }
    }

    private void leerCamposTexto() {
        if (matriculas != null) alerta.matriculas = matriculas.getText().toString().trim();
        if (indicativos != null) alerta.indicativos = indicativos.getText().toString().trim();
        if (vuelos != null) alerta.vuelos = vuelos.getText().toString().trim();
        if (squawks != null) alerta.squawks = squawks.getText().toString().trim();
    }

    private void refrescarDistancias() {
        leerCamposTexto();
        if (alerta.sinLimite()) {
            txtDistancia.setText("🌍 Sin límite");
            boolean vale = !alerta.consultasGlobales().isEmpty() || !alerta.servicios.isEmpty();
            txtAvisoLimite.setVisibility(View.VISIBLE);
            txtAvisoLimite.setTextColor(vale ? Ui.TEXTO_SUAVE : Ui.ROJO);
            txtAvisoLimite.setText(vale
                    ? "Te avisará esté donde esté la aeronave, en todo el mundo."
                    : "⚠️ Sin límite solo funciona si marcas Militar, Emergencia o un servicio, eliges un modelo concreto, "
                      + "o pones un squawk, matrícula o indicativo exactos (en «Más filtros», sin * ni !).");
        } else {
            txtDistancia.setText("Hasta " + Alerta.km(alerta.radioKm));
            txtAvisoLimite.setVisibility(View.GONE);
        }
        barraMinimo.setVisibility(swMinimo.isChecked() ? View.VISIBLE : View.GONE);
        txtMinimo.setVisibility(swMinimo.isChecked() ? View.VISIBLE : View.GONE);
        if (swMinimo.isChecked()) {
            String t = "No avisar si está a menos de " + Alerta.km(alerta.distMinKm);
            if (!alerta.sinLimite() && alerta.distMinKm >= alerta.radioKm) t += "  ⚠️ es mayor que la distancia máxima";
            txtMinimo.setText(t);
        }
    }

    private void refrescarAltitud() {
        txtAltitud.setText(alerta.altMaxM == 0 ? "Altitud: cualquiera"
                : String.format(Locale.getDefault(), "Altitud: por debajo de %,d m", alerta.altMaxM));
    }

    // ------------------------------------------------------------ aeropuertos

    private AutoCompleteTextView buscadorAeropuerto(String pista, List<String> destino, ViewGroup chips) {
        AutoCompleteTextView ac = new AutoCompleteTextView(this);
        ac.setHint("🔍  " + pista);
        ac.setSingleLine(true);
        ac.setThreshold(1);
        ac.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ac.setImeOptions(EditorInfo.IME_ACTION_DONE);
        AdaptadorAeropuertos ad = new AdaptadorAeropuertos(this);
        subirAlEscribir(ac);
        ac.setAdapter(ad);
        ac.setOnItemClickListener((p, v, pos, id) -> {
            Aeropuertos.Aeropuerto a = ad.getItem(pos);
            if (!destino.contains(a.iata)) destino.add(a.iata);
            ac.setText("");
            pintarChipsAeropuertos(destino, chips);
        });
        // Intro con un código de 3 letras lo añade directamente
        ac.setOnEditorActionListener((v, accion, ev) -> {
            String t = ac.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (t.matches("[A-Z0-9]{3}")) {
                if (!destino.contains(t)) destino.add(t);
                ac.setText("");
                pintarChipsAeropuertos(destino, chips);
                return true;
            }
            return false;
        });
        return ac;
    }

    private void pintarChipsAeropuertos(List<String> codigos, ViewGroup chips) {
        chips.removeAllViews();
        if (codigos.isEmpty()) {
            boolean exclusion = chips == chipsExclOrigen || chips == chipsExclDestino;
            chips.addView(Ui.texto(this, exclusion ? "Ninguno" : "Cualquier aeropuerto", 14, Ui.TEXTO_SUAVE, false));
            return;
        }
        for (String codigo : new ArrayList<>(codigos)) {
            TextView ch = Ui.chip(this, Aeropuertos.etiqueta(this, codigo) + "   ✕");
            Ui.marcarChip(this, ch, true);
            ch.setOnClickListener(v -> { codigos.remove(codigo); pintarChipsAeropuertos(codigos, chips); });
            chips.addView(ch);
        }
    }

    /** Sugerencias de aeropuertos mientras se escribe. */
    private static final class AdaptadorAeropuertos extends BaseAdapter implements Filterable {
        private final Context c;
        private List<Aeropuertos.Aeropuerto> datos = new ArrayList<>();

        AdaptadorAeropuertos(Context c) { this.c = c; }

        @Override public int getCount() { return datos.size(); }
        @Override public Aeropuertos.Aeropuerto getItem(int i) { return datos.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View reciclada, ViewGroup padre) {
            LinearLayout fila;
            TextView t1, t2;
            if (reciclada instanceof LinearLayout) {
                fila = (LinearLayout) reciclada;
                t1 = (TextView) fila.getChildAt(0);
                t2 = (TextView) fila.getChildAt(1);
            } else {
                fila = new LinearLayout(c);
                fila.setOrientation(LinearLayout.VERTICAL);
                fila.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
                t1 = Ui.texto(c, "", 16, Ui.TEXTO, true);
                t2 = Ui.texto(c, "", 13, Ui.TEXTO_SUAVE, false);
                fila.addView(t1);
                fila.addView(t2);
            }
            Aeropuertos.Aeropuerto a = datos.get(i);
            t1.setText(a.titulo());
            t2.setText(a.detalle());
            return fila;
        }

        @Override public Filter getFilter() {
            return new Filter() {
                @Override protected FilterResults performFiltering(CharSequence q) {
                    FilterResults r = new FilterResults();
                    List<Aeropuertos.Aeropuerto> l = q == null ? new ArrayList<>() : Aeropuertos.buscar(c, q.toString(), 25);
                    r.values = l;
                    r.count = l.size();
                    return r;
                }
                @SuppressWarnings("unchecked")
                @Override protected void publishResults(CharSequence q, FilterResults r) {
                    datos = r.values != null ? (List<Aeropuertos.Aeropuerto>) r.values : new ArrayList<>();
                    notifyDataSetChanged();
                }
                @Override public CharSequence convertResultToString(Object o) {
                    return o instanceof Aeropuertos.Aeropuerto ? ((Aeropuertos.Aeropuerto) o).titulo() : "";
                }
            };
        }
    }

    private void elegirAeropuertoCercano() {
        android.location.Location yo = null;
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            for (String prov : lm.getProviders(true)) {
                android.location.Location l = lm.getLastKnownLocation(prov);
                if (l != null && (yo == null || l.getTime() > yo.getTime())) yo = l;
            }
        } catch (SecurityException ignored) {}
        if (yo == null) {
            Toast.makeText(this, "No sé dónde estás todavía. Empieza a vigilar una vez (para dar permiso de ubicación) y vuelve a probar.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final double lat = yo.getLatitude(), lon = yo.getLongitude();
        List<Aeropuertos.Aeropuerto> cerca = Aeropuertos.cercanos(this, lat, lon, 6, 100);
        if (cerca.isEmpty()) {
            Toast.makeText(this, "No hay aeropuertos con vuelos a menos de 100 km", Toast.LENGTH_LONG).show();
            return;
        }
        String[] opciones = new String[cerca.size()];
        for (int i = 0; i < cerca.size(); i++) {
            Aeropuertos.Aeropuerto a = cerca.get(i);
            opciones[i] = a.titulo() + "  ·  " + Alerta.km((float) Aeropuertos.distanciaKm(lat, lon, a.lat, a.lon));
        }
        new AlertDialog.Builder(this)
                .setTitle("¿Qué aeropuerto ignorar?")
                .setItems(opciones, (d, i) -> {
                    String codigo = cerca.get(i).iata;
                    new AlertDialog.Builder(this)
                            .setTitle(cerca.get(i).titulo())
                            .setItems(new String[]{"Ignorar los que salen", "Ignorar los que llegan", "Ignorar ambos"}, (d2, j) -> {
                                if (j != 1 && !alerta.excluirOrigenes.contains(codigo)) alerta.excluirOrigenes.add(codigo);
                                if (j != 0 && !alerta.excluirDestinos.contains(codigo)) alerta.excluirDestinos.add(codigo);
                                pintarChipsAeropuertos(alerta.excluirOrigenes, chipsExclOrigen);
                                pintarChipsAeropuertos(alerta.excluirDestinos, chipsExclDestino);
                            })
                            .show();
                })
                .show();
    }

    // ------------------------------------------------------------ guardar

    private void guardar() {
        String n = nombre.getText().toString().trim();
        if (n.isEmpty()) {
            Toast.makeText(this, "Ponle un nombre a la alerta", Toast.LENGTH_SHORT).show();
            return;
        }
        leerCamposTexto();
        if (alerta.sinLimite() && alerta.consultasGlobales().isEmpty() && alerta.servicios.isEmpty()) {
            Toast.makeText(this, "Para «sin límite» marca Militar o Emergencia, elige un modelo, o pon un squawk, matrícula o indicativo exactos",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!alerta.sinLimite() && alerta.distMinKm >= alerta.radioKm) {
            Toast.makeText(this, "La distancia mínima tiene que ser menor que la máxima", Toast.LENGTH_LONG).show();
            return;
        }
        alerta.nombre = n;
        Alertas.guardarUna(this, alerta);
        Toast.makeText(this, "Alerta guardada", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK, new Intent());
        finish();
    }
}
