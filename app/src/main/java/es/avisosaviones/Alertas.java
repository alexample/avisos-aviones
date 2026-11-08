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

    /** Primera vez con la versión de alertas: convierte los ajustes de antes en alertas. */
    private static List<Alerta> migrarDesdeAjustesAntiguos(Context c) {
        SharedPreferences p = Ajustes.prefs(c);
        List<Alerta> lista = new ArrayList<>();

        Alerta encima = new Alerta();
        encima.nombre = "Encima de mí";
        encima.radioKm = p.getFloat(Ajustes.RADIO_KM, 2f);
        encima.tipo = p.getBoolean(Ajustes.SOLO_HELICOPTEROS, false) ? Alerta.HELICOPTEROS : Alerta.TODO;
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
        emerg.tipo = Alerta.EMERGENCIAS;
        emerg.radioKm = 100;
        emerg.activa = false;
        lista.add(emerg);

        Alerta heli = new Alerta();
        heli.nombre = "Helicópteros cerca";
        heli.tipo = Alerta.HELICOPTEROS;
        heli.radioKm = 10;
        heli.activa = false;
        lista.add(heli);
        return lista;
    }
}
