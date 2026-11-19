package es.pegasusdgt;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ajustes de la app Pegasus. */
final class Config {
    private Config() {}

    /** Helicópteros de la DGT (Pegasus): EC135 y AS355. */
    static final String[] PEGASUS = {
            "EC-LBD", "EC-LDF",
            "EC-JMK", "EC-KXU", "EC-MDO", "EC-MHU", "EC-IXI",
            "EC-IBN", "EC-ISB", "EC-HCE", "EC-HZY", "EC-DZX",
    };

    static SharedPreferences p(Context c) { return c.getSharedPreferences("pegasus", Context.MODE_PRIVATE); }

    static boolean auto(Context c) { return p(c).getBoolean("auto", true); }
    static boolean voz(Context c) { return p(c).getBoolean("voz", true); }
    static int radioKm(Context c) { return p(c).getInt("radio_km", 60); }
    static int intervaloS(Context c) { return p(c).getInt("intervalo_s", 10); }
    static boolean activo(Context c) { return p(c).getBoolean("activo", false); }
    static boolean manual(Context c) { return p(c).getBoolean("manual", false); }

    /** Umbrales de aviso en km, de mayor a menor. */
    static List<Double> umbrales(Context c) {
        List<Double> l = new ArrayList<>();
        for (String t : p(c).getString("umbrales", "20, 10, 5, 2").split("[,;\\s]+")) {
            try { double v = Double.parseDouble(t.replace(',', '.')); if (v > 0) l.add(v); } catch (Exception ignored) {}
        }
        l.sort((a, b) -> Double.compare(b, a));
        return l;
    }

    static String normalizar(String r) {
        return r == null ? "" : r.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    static List<String> matriculas(Context c) {
        List<String> l = new ArrayList<>();
        String json = p(c).getString("matriculas", null);
        if (json == null) {
            for (String r : PEGASUS) l.add(r);
            return l;
        }
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) {}
        return l;
    }

    static void guardarMatriculas(Context c, List<String> l) {
        p(c).edit().putString("matriculas", new JSONArray(l).toString()).apply();
    }

    // ------------------------------------------------------------ avistamientos

    static void anotarAvistamiento(Context c, String matricula, double distKm, String donde, int altM) {
        try {
            JSONArray a = new JSONArray(p(c).getString("avistamientos", "[]"));
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("reg", matricula);
            o.put("dist", Math.round(distKm * 10) / 10.0);
            o.put("donde", donde);
            o.put("alt", altM);
            JSONArray n = new JSONArray();
            n.put(o);
            for (int i = 0; i < a.length() && i < 49; i++) n.put(a.get(i));
            p(c).edit().putString("avistamientos", n.toString()).apply();
        } catch (Exception ignored) {}
    }

    static JSONArray avistamientos(Context c) {
        try { return new JSONArray(p(c).getString("avistamientos", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
}
