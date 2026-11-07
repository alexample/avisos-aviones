package es.avisosaviones;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes guardados de la app. */
final class Ajustes {
    private static final String NOMBRE = "ajustes";

    static final String RADIO_KM = "radio_km";
    static final String ALTITUD_MAX_M = "altitud_max_m";
    static final String SOLO_HELICOPTEROS = "solo_helicopteros";
    static final String INTERVALO_S = "intervalo_s";
    static final String ACTIVO = "activo";

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
}
