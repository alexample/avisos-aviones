package es.avisosaviones;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Modo casa: con el móvil conectado a uno de "tus" wifis, los avisos salen en silencio
 * (salvo las alertas marcadas como "Avisar también en casa").
 * Android solo deja leer el nombre del wifi con permiso de ubicación y la ubicación encendida.
 */
final class Casa {
    private static final String ACTIVO = "casa_activo";
    private static final String WIFIS = "casa_wifis";
    private static volatile String ssidVisto = null;   // lo que nos cuenta Android al cambiar de red
    private static ConnectivityManager.NetworkCallback escucha;

    private Casa() {}

    static boolean activo(Context c) { return Ajustes.prefs(c).getBoolean(ACTIVO, false); }
    static void activar(Context c, boolean on) { Ajustes.prefs(c).edit().putBoolean(ACTIVO, on).apply(); }

    static List<String> wifis(Context c) {
        List<String> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Ajustes.prefs(c).getString(WIFIS, "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) {}
        return l;
    }

    static void guardarWifis(Context c, List<String> l) {
        Ajustes.prefs(c).edit().putString(WIFIS, new JSONArray(l).toString()).apply();
    }

    /** ¿Modo casa activo y conectado a uno de tus wifis? */
    static boolean enCasa(Context c) {
        if (!activo(c)) return false;
        String ssid = ssidActual(c);
        return ssid != null && wifis(c).contains(ssid);
    }

    /** Nombre del wifi conectado, o null si no hay wifi (o Android no deja verlo). */
    static String ssidActual(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network red = cm.getActiveNetwork();
        NetworkCapabilities cap = red != null ? cm.getNetworkCapabilities(red) : null;
        if (cap == null || !cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
        String s = limpiar(ssidVisto);
        if (s != null) return s;
        try {
            @SuppressWarnings("deprecation")
            WifiInfo wi = c.getApplicationContext().getSystemService(WifiManager.class).getConnectionInfo();
            if (wi != null) s = limpiar(wi.getSSID());
        } catch (Exception ignored) {}
        return s;
    }

    private static String limpiar(String ssid) {
        if (ssid == null) return null;
        String s = ssid.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        if (s.isEmpty() || s.equalsIgnoreCase("<unknown ssid>")) return null;
        return s;
    }

    /** Escuchar los cambios de wifi (Android 12+ solo da el nombre así). Se llama al arrancar la vigilancia. */
    static synchronized void escuchar(Context c) {
        if (escucha != null || Build.VERSION.SDK_INT < 31) return;
        try {
            ConnectivityManager cm = c.getApplicationContext().getSystemService(ConnectivityManager.class);
            escucha = new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities cap) {
                    if (cap.getTransportInfo() instanceof WifiInfo) ssidVisto = ((WifiInfo) cap.getTransportInfo()).getSSID();
                }
                @Override public void onLost(Network n) { ssidVisto = null; }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), escucha);
        } catch (Exception e) {
            escucha = null;
        }
    }
}
