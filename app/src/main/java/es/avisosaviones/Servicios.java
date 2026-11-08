package es.avisosaviones;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Etiquetas de servicio que pone el usuario (DGT, 112, policía…), por matrícula o código Mode S.
 * Las APIs gratuitas no dan el operador de las aeronaves españolas, así que se construye a mano.
 */
final class Servicios {
    private static final String CLAVE = "servicios_json";

    static final String[] IDS = {"dgt", "sanitario", "policia", "incendios", "salvamento", "otro"};
    static final String[] TXT = {"🚦 DGT / Pegasus", "🚑 Sanitario / 112", "👮 Policía / G. Civil",
            "🔥 Incendios", "⚓ Salvamento", "🏷️ Otro"};

    /** Helicópteros de la DGT (Pegasus) aportados por el usuario: EC135 y AS355. */
    private static final String[] DGT_PEGASUS = {
            "EC-LBD", "EC-LDF",                                    // EC135
            "EC-JMK", "EC-KXU", "EC-MDO", "EC-MHU", "EC-IXI",      // AS355
            "EC-IBN", "EC-ISB", "EC-HCE", "EC-HZY", "EC-DZX",
    };
    private static final String SEMILLA = "semilla_dgt_v1";

    private Servicios() {}

    /** Añade una sola vez las matrículas conocidas (sin pisar lo que haya cambiado el usuario). */
    private static void sembrar(Context c, JSONObject o) {
        if (Ajustes.prefs(c).getBoolean(SEMILLA, false)) return;
        try {
            for (String r : DGT_PEGASUS) if (!o.has("R:" + reg(r))) o.put("R:" + reg(r), "dgt");
        } catch (Exception ignored) {}
        Ajustes.prefs(c).edit().putString(CLAVE, o.toString()).putBoolean(SEMILLA, true).apply();
    }

    /** "ECLBD" → "EC-LBD" (formato español); otras se dejan igual. */
    static String conGuion(String r) {
        if (r.matches("EC[A-Z]{3}")) return "EC-" + r.substring(2);
        return r;
    }

    static String legible(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return TXT[i];
        return "";
    }

    static String icono(String id) {
        String t = legible(id);
        return t.isEmpty() ? "🏷️" : t.substring(0, t.indexOf(' '));
    }

    private static String reg(String r) {
        return r == null ? "" : r.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static JSONObject leer(Context c) {
        JSONObject o;
        try { o = new JSONObject(Ajustes.prefs(c).getString(CLAVE, "{}")); }
        catch (Exception e) { o = new JSONObject(); }
        sembrar(c, o);
        return o;
    }

    private static void escribir(Context c, JSONObject o) {
        Ajustes.prefs(c).edit().putString(CLAVE, o.toString()).apply();
    }

    /** Servicio de una aeronave ("" si no está etiquetada). */
    static String de(Context c, String hex, String matricula) {
        JSONObject o = leer(c);
        String r = reg(matricula);
        if (!r.isEmpty() && o.has("R:" + r)) return o.optString("R:" + r);
        String h = hex == null ? "" : hex.trim().toUpperCase(Locale.ROOT);
        if (!h.isEmpty() && o.has("H:" + h)) return o.optString("H:" + h);
        return "";
    }

    static void etiquetar(Context c, String hex, String matricula, String servicio) {
        JSONObject o = leer(c);
        try {
            String r = reg(matricula);
            String h = hex == null ? "" : hex.trim().toUpperCase(Locale.ROOT);
            if (!r.isEmpty()) o.put("R:" + r, servicio);
            if (!h.isEmpty()) o.put("H:" + h, servicio);
        } catch (Exception ignored) {}
        escribir(c, o);
    }

    static void quitar(Context c, String hex, String matricula) {
        JSONObject o = leer(c);
        o.remove("R:" + reg(matricula));
        if (hex != null) o.remove("H:" + hex.trim().toUpperCase(Locale.ROOT));
        escribir(c, o);
    }

    static void quitarClave(Context c, String clave) {
        JSONObject o = leer(c);
        o.remove(clave);
        escribir(c, o);
    }

    /** Lista para mostrar: {clave, texto}. */
    static List<String[]> todas(Context c) {
        List<String[]> l = new ArrayList<>();
        JSONObject o = leer(c);
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            String quien = k.startsWith("R:") ? "Matrícula " + k.substring(2) : "Mode S " + k.substring(2);
            l.add(new String[]{k, legible(o.optString(k)) + "  ·  " + quien});
        }
        return l;
    }

    /** Códigos Mode S etiquetados con alguno de estos servicios (para buscarlos sin límite). */
    static List<String> hexDe(Context c, List<String> servicios) {
        List<String> l = new ArrayList<>();
        JSONObject o = leer(c);
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith("H:") && servicios.contains(o.optString(k))) l.add(k.substring(2).toLowerCase(Locale.ROOT));
        }
        return l;
    }

    static List<String> matriculasDe(Context c, List<String> servicios) {
        List<String> l = new ArrayList<>();
        JSONObject o = leer(c);
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith("R:") && servicios.contains(o.optString(k))) l.add(k.substring(2));
        }
        return l;
    }
}
