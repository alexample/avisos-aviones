package es.avisosaviones;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.regex.Pattern;

/** Ajustes guardados de la app. */
final class Ajustes {
    private static final String NOMBRE = "ajustes";

    static final String RADIO_KM = "radio_km";
    static final String ALTITUD_MAX_M = "altitud_max_m";
    static final String SOLO_HELICOPTEROS = "solo_helicopteros";
    static final String INTERVALO_S = "intervalo_s";
    static final String ACTIVO = "activo";

    // Filtros
    static final String DIST_MIN_KM = "dist_min_km";
    static final String MODO_ZONA = "modo_zona";        // 0 dentro, 1 fuera, 2 ambos
    static final String RADIO_EXT_KM = "radio_ext_km";
    static final String F_MATRICULAS = "f_matriculas";
    static final String F_INDICATIVOS = "f_indicativos";
    static final String F_ORIGEN = "f_origen";
    static final String F_DESTINO = "f_destino";
    static final String F_SQUAWK = "f_squawk";

    static final int ZONA_DENTRO = 0, ZONA_FUERA = 1, ZONA_AMBAS = 2;

    private Ajustes() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NOMBRE, Context.MODE_PRIVATE);
    }

    static float radioKm(Context c) { return prefs(c).getFloat(RADIO_KM, 2f); }
    /** 0 = sin límite. */
    static int altitudMaxM(Context c) { return prefs(c).getInt(ALTITUD_MAX_M, 0); }
    static boolean soloHelicopteros(Context c) { return prefs(c).getBoolean(SOLO_HELICOPTEROS, false); }
    static int intervaloS(Context c) { return prefs(c).getInt(INTERVALO_S, 15); }
    static boolean activo(Context c) { return prefs(c).getBoolean(ACTIVO, false); }

    static float distMinKm(Context c) { return prefs(c).getFloat(DIST_MIN_KM, 0f); }
    static int modoZona(Context c) { return prefs(c).getInt(MODO_ZONA, ZONA_DENTRO); }
    static float radioExtKm(Context c) { return prefs(c).getFloat(RADIO_EXT_KM, 50f); }
    static String fMatriculas(Context c) { return prefs(c).getString(F_MATRICULAS, ""); }
    static String fIndicativos(Context c) { return prefs(c).getString(F_INDICATIVOS, ""); }
    static String fOrigen(Context c) { return prefs(c).getString(F_ORIGEN, ""); }
    static String fDestino(Context c) { return prefs(c).getString(F_DESTINO, ""); }
    static String fSquawk(Context c) { return prefs(c).getString(F_SQUAWK, ""); }

    /** Filtro vacío = acepta todo. */
    static boolean vacio(String filtro) {
        return filtro == null || filtro.trim().isEmpty();
    }

    /**
     * ¿Alguno de los valores coincide con alguno de los términos del filtro?
     * Términos separados por comas o espacios; admite comodines * y ?.
     * Un término que empieza por ! excluye (p. ej. "!EC-*").
     */
    static boolean coincide(String filtro, String... valores) {
        if (vacio(filtro)) return true;
        boolean hayInclusiones = false, incluido = false;
        for (String t : filtro.toUpperCase(Locale.ROOT).split("[,;\\s]+")) {
            if (t.isEmpty()) continue;
            boolean excluir = t.startsWith("!");
            if (excluir) t = t.substring(1);
            if (t.isEmpty()) continue;
            String limpio = t.replaceAll("[^A-Z0-9*?]", "");
            if (limpio.isEmpty()) continue;
            Pattern p = Pattern.compile(limpio.replace("*", ".*").replace("?", "."));
            boolean algun = false;
            for (String v : valores) {
                if (v == null || v.trim().isEmpty()) continue;
                if (p.matcher(normalizar(v.toUpperCase(Locale.ROOT))).matches()) { algun = true; break; }
            }
            if (excluir) { if (algun) return false; }
            else { hayInclusiones = true; if (algun) incluido = true; }
        }
        return !hayInclusiones || incluido;
    }

    /** Solo letras y números, para que EC-MXV y ECMXV sean lo mismo. */
    private static String normalizar(String s) {
        return s.replaceAll("[^A-Z0-9]", "");
    }
}
