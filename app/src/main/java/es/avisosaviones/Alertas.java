package es.avisosaviones;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Guarda y carga la lista de alertas. */
final class Alertas {
    private static final String CLAVE = "alertas_json";

    private Alertas() {}

    static synchronized List<Alerta> cargar(Context c) {
        SharedPreferences p = Ajustes.prefs(c);
        String json = p.getString(CLAVE, null);
        List<Alerta> lista = new ArrayList<>();
        if (json == null) {
            lista = migrarDesdeAjustesAntiguos(c);
            guardar(c, lista);
            return lista;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) lista.add(Alerta.deJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        if (!p.getBoolean("semilla_sonidos_v1", false)) {
            for (Alerta a : lista) if (a.sonido.isEmpty()) a.sonido = sonidoSugerido(a);
            p.edit().putBoolean("semilla_sonidos_v1", true).apply();
            guardar(c, lista);
        }
        if (!p.getBoolean("semilla_alerta_pegasus_v1", false)) {
            lista.add(alertaPegasus());
            p.edit().putBoolean("semilla_alerta_pegasus_v1", true).apply();
            guardar(c, lista);
        }
        return lista;
    }

    static synchronized void guardar(Context c, List<Alerta> lista) {
        JSONArray arr = new JSONArray();
        for (Alerta a : lista) arr.put(a.aJson());
        Ajustes.prefs(c).edit().putString(CLAVE, arr.toString()).apply();
    }

    static Alerta buscar(Context c, String id) {
        for (Alerta a : cargar(c)) if (a.id.equals(id)) return a;
        return null;
    }

    static void guardarUna(Context c, Alerta alerta) {
        List<Alerta> lista = cargar(c);
        boolean reemplazada = false;
        for (int i = 0; i < lista.size(); i++) {
            if (lista.get(i).id.equals(alerta.id)) { lista.set(i, alerta); reemplazada = true; break; }
        }
        if (!reemplazada) lista.add(alerta);
        guardar(c, lista);
    }

    static void borrar(Context c, String id) {
        List<Alerta> lista = cargar(c);
        for (int i = lista.size() - 1; i >= 0; i--) if (lista.get(i).id.equals(id)) lista.remove(i);
        guardar(c, lista);
    }

    /** Radio máximo entre las alertas activas (para saber cuánto cielo consultar). */
    static float radioMaximo(List<Alerta> lista) {
        float max = 0;
        for (Alerta a : lista) if (a.activa) max = Math.max(max, a.radioKm);
        return max;
    }

    /** Sonido que encaja con lo que vigila la alerta. */
    static String sonidoSugerido(Alerta a) {
        if (a.servicios.contains("dgt") || a.soloEmergencia || a.servicios.contains("policia")) return "raw:sirena";
        if (a.clases.size() == 1 && Tipos.HELI.equals(a.clases.get(0))) return "raw:rotor";
        if (a.soloMilitar) return "raw:radar";
        if (a.clases.size() == 1 && Tipos.GRANDE.equals(a.clases.get(0))) return "raw:reactor";
        return Sonidos.POR_DEFECTO;
    }

    /** Alerta lista para usar con los helicópteros Pegasus de la DGT. */
    private static Alerta alertaPegasus() {
        Alerta a = new Alerta();
        a.nombre = "Pegasus DGT cerca";
        a.servicios.add("dgt");
        a.radioKm = 30;
        a.activa = true;
        a.sonido = "raw:sirena";
        return a;
    }

    /** Primera vez con la versión de alertas: convierte los ajustes de antes en alertas. */
    private static List<Alerta> migrarDesdeAjustesAntiguos(Context c) {
        SharedPreferences p = Ajustes.prefs(c);
        List<Alerta> lista = new ArrayList<>();

        Alerta encima = new Alerta();
        encima.nombre = "Encima de mí";
        encima.radioKm = p.getFloat(Ajustes.RADIO_KM, 2f);
        if (p.getBoolean(Ajustes.SOLO_HELICOPTEROS, false)) encima.clases.add(Tipos.HELI);
        if (Ajustes.usa(c, Ajustes.ALTITUD_MAX_M)) encima.altMaxM = p.getInt(Ajustes.ALTITUD_MAX_M, 0);
        lista.add(encima);

        // Si tenía filtros de ruta/matrícula, se conservan en una alerta aparte
        String ori = Ajustes.fOrigen(c), des = Ajustes.fDestino(c);
        String mat = Ajustes.fMatriculas(c), ind = Ajustes.fIndicativos(c);
        if (!ori.isEmpty() || !des.isEmpty() || !mat.isEmpty() || !ind.isEmpty() || !Ajustes.fSquawk(c).isEmpty()) {
            Alerta f = new Alerta();
            f.nombre = "Mis filtros anteriores";
            f.radioKm = Math.max(encima.radioKm, p.getFloat(Ajustes.RADIO_EXT_KM, 50f));
            f.distMinKm = Ajustes.distMinKm(c);
            for (String s : ori.toUpperCase().split("[,;\\s]+")) if (s.matches("[A-Z0-9]{3,4}")) f.origenes.add(s);
            for (String s : des.toUpperCase().split("[,;\\s]+")) if (s.matches("[A-Z0-9]{3,4}")) f.destinos.add(s);
            f.matriculas = mat;
            f.indicativos = ind;
            f.squawks = Ajustes.fSquawk(c);
            lista.add(f);
        }

        Alerta emerg = new Alerta();
        emerg.nombre = "Emergencias en la zona";
        emerg.soloEmergencia = true;
        emerg.radioKm = 100;
        emerg.activa = false;
        lista.add(emerg);

        lista.add(alertaPegasus());
        p.edit().putBoolean("semilla_alerta_pegasus_v1", true).apply();

        Alerta heli = new Alerta();
        heli.nombre = "Helicópteros cerca";
        heli.clases.add(Tipos.HELI);
        heli.radioKm = 10;
        heli.activa = false;
        lista.add(heli);
        return lista;
    }
}
