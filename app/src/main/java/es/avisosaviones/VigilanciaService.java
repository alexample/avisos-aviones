package es.avisosaviones;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Servicio en primer plano: obtiene la ubicación, consulta cada X segundos las
 * aeronaves cercanas y lanza una notificación cuando una entra en el radio.
 */
public class VigilanciaService extends Service {

    static final String ACCION_PARAR = "es.avisosaviones.PARAR";
    static final String ACCION_ESTADO = "es.avisosaviones.ESTADO";
    static final String EXTRA_TEXTO = "texto";

    private static final String CANAL_SERVICIO = "servicio";
    private static final String CANAL_AVISOS = "avisos";
    private static final int ID_SERVICIO = 1;
    /** No repetir aviso del mismo aparato durante este tiempo. */
    private static final long ENFRIAMIENTO_MS = 10 * 60 * 1000L;

    private static final String[] FUENTES = {
            "https://api.airplanes.live/v2/point/%f/%f/%d",
            "https://api.adsb.lol/v2/point/%f/%f/%d",
    };

    private HandlerThread hilo;
    private Handler handler;
    private LocationManager lm;
    private PowerManager.WakeLock wakeLock;
    private volatile Location ultimaUbicacion;
    private final Map<String, Long> avisados = new HashMap<>();
    private int idAviso = 100;

    private final LocationListener oyente = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            if (ultimaUbicacion == null || l.getAccuracy() <= ultimaUbicacion.getAccuracy() + 50
                    || l.getTime() - ultimaUbicacion.getTime() > 120_000) {
                ultimaUbicacion = l;
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private final Runnable comprobar = new Runnable() {
        @Override public void run() {
            try {
                comprobarCielo();
            } catch (Throwable t) {
                publicarEstado("Error: " + t.getMessage());
            }
            handler.postDelayed(this, Ajustes.intervaloS(VigilanciaService.this) * 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanales();
        hilo = new HandlerThread("vigilancia");
        hilo.start();
        handler = new Handler(hilo.getLooper());
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avisosaviones:vigilancia");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_PARAR.equals(intent.getAction())) {
            Ajustes.prefs(this).edit().putBoolean(Ajustes.ACTIVO, false).apply();
            publicarEstado("Parado.");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n = notificacionServicio("Vigilando el cielo…");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_SERVICIO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(ID_SERVICIO, n);
            }
        } catch (Exception e) {
            // Android puede negarse a reanudarlo en segundo plano: el usuario lo reabrirá desde la app.
            Ajustes.prefs(this).edit().putBoolean(Ajustes.ACTIVO, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!wakeLock.isHeld()) wakeLock.acquire();
        empezarUbicacion();
        handler.removeCallbacks(comprobar);
        handler.post(comprobar);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { lm.removeUpdates(oyente); } catch (Exception ignored) {}
        if (wakeLock.isHeld()) wakeLock.release();
        hilo.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- ubicación

    private void empezarUbicacion() {
        boolean fina = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean gruesa = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fina && !gruesa) {
            publicarEstado("Falta permiso de ubicación.");
            return;
        }
        try {
            lm.removeUpdates(oyente);
            Looper looper = hilo.getLooper();
            for (String prov : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (prov.equals(LocationManager.GPS_PROVIDER) && !fina) continue;
                if (!lm.isProviderEnabled(prov)) continue;
                Location ult = lm.getLastKnownLocation(prov);
                if (ult != null) oyente.onLocationChanged(ult);
                lm.requestLocationUpdates(prov, 60_000L, 50f, oyente, looper);
            }
        } catch (SecurityException e) {
            publicarEstado("Falta permiso de ubicación.");
        }
    }

    // ---------------------------------------------------------------- consulta

    private void comprobarCielo() throws Exception {
        Location yo = ultimaUbicacion;
        if (yo == null) {
            publicarEstado("Esperando ubicación… (¿tienes la ubicación activada?)");
            return;
        }

        double radioKm = Ajustes.radioKm(this);
        int altMaxM = Ajustes.altitudMaxM(this);
        boolean soloHeli = Ajustes.soloHelicopteros(this);
        int radioNm = (int) Math.max(1, Math.ceil(radioKm / 1.852));

        JSONArray aparatos = descargar(yo.getLatitude(), yo.getLongitude(), radioNm);

        int enRadio = 0;
        long ahora = System.currentTimeMillis();
        for (int i = 0; i < aparatos.length(); i++) {
            JSONObject a = aparatos.getJSONObject(i);
            if (!a.has("lat") || !a.has("lon")) continue;

            float[] d = new float[1];
            Location.distanceBetween(yo.getLatitude(), yo.getLongitude(),
                    a.getDouble("lat"), a.getDouble("lon"), d);
            double distKm = d[0] / 1000.0;
            if (distKm > radioKm) continue;

            boolean enTierra = "ground".equals(a.optString("alt_baro"));
            if (enTierra) continue;
            int altM = altitudMetros(a);
            if (altMaxM > 0 && altM > altMaxM) continue;

            boolean heli = esHelicoptero(a);
            if (soloHeli && !heli) continue;

            enRadio++;
            String hex = a.optString("hex", "?");
            Long ultimo = avisados.get(hex);
            if (ultimo != null && ahora - ultimo < ENFRIAMIENTO_MS) {
                avisados.put(hex, ahora); // sigue cerca: alarga el silencio
                continue;
            }
            avisados.put(hex, ahora);
            avisar(a, heli, distKm, altM);
        }

        // Limpieza de la memoria de avisos
        Iterator<Map.Entry<String, Long>> it = avisados.entrySet().iterator();
        while (it.hasNext()) if (ahora - it.next().getValue() > ENFRIAMIENTO_MS) it.remove();

        String hora = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String txt = String.format(Locale.getDefault(),
                "Vigilando · %d en tu radio de %.1f km · %d en la zona\nÚltima comprobación %s (precisión GPS ±%.0f m)",
                enRadio, radioKm, aparatos.length(), hora, yo.getAccuracy());
        publicarEstado(txt);
        actualizarNotificacionServicio(String.format(Locale.getDefault(),
                "Vigilando · %d en tu radio · %s", enRadio, hora));
    }

    private JSONArray descargar(double lat, double lon, int radioNm) throws Exception {
        Exception ultimoError = null;
        for (String plantilla : FUENTES) {
            try {
                String url = String.format(Locale.US, plantilla, lat, lon, radioNm);
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("User-Agent", "AvisosAviones/1.0 (Android)");
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String linea;
                    while ((linea = r.readLine()) != null) sb.append(linea);
                }
                JSONObject json = new JSONObject(sb.toString());
                JSONArray ac = json.optJSONArray("ac");
                if (ac == null) ac = json.optJSONArray("aircraft");
                return ac != null ? ac : new JSONArray();
            } catch (Exception e) {
                ultimoError = e;
            }
        }
        throw new Exception("sin conexión con los servidores de datos (" + ultimoError.getMessage() + ")");
    }

    private static int altitudMetros(JSONObject a) {
        double pies = a.optDouble("alt_baro", Double.NaN);
        if (Double.isNaN(pies)) pies = a.optDouble("alt_geom", Double.NaN);
        if (Double.isNaN(pies)) return -1;
        return (int) Math.round(pies * 0.3048);
    }

    /** Categoría ADS-B A7 = helicóptero / rotor. */
    private static boolean esHelicoptero(JSONObject a) {
        if ("A7".equalsIgnoreCase(a.optString("category"))) return true;
        String desc = a.optString("desc").toUpperCase(Locale.ROOT);
        return desc.contains("HELICOPTER") || desc.contains("ROTOR");
    }

    // ---------------------------------------------------------------- notificaciones

    private void avisar(JSONObject a, boolean heli, double distKm, int altM) {
        String indicativo = a.optString("flight", "").trim();
        String matricula = a.optString("r", "").trim();
        String tipo = a.optString("t", "").trim();
        String desc = a.optString("desc", "").trim();
        String operador = a.optString("ownOp", "").trim();
        String hex = a.optString("hex", "");

        String nombre = !indicativo.isEmpty() ? indicativo : (!matricula.isEmpty() ? matricula : hex.toUpperCase(Locale.ROOT));

        boolean militar = (a.optInt("dbFlags", 0) & 1) != 0;
        String squawk = a.optString("squawk", "").trim();
        String emergencia = a.optString("emergency", "none");
        String textoEmergencia = textoEmergencia(squawk, emergencia);

        String titulo;
        if (textoEmergencia != null) titulo = "🚨 " + textoEmergencia + ": " + nombre;
        else if (militar) titulo = (heli ? "🚁🎖️ Helicóptero militar" : "✈️🎖️ Avión militar") + " encima: " + nombre;
        else titulo = (heli ? "🚁 Helicóptero" : "✈️ Avión") + " encima: " + nombre;

        String ruta = indicativo.isEmpty() ? "" :
                obtenerRuta(indicativo, a.optDouble("lat"), a.optDouble("lon"));

        double nudos = a.optDouble("gs", Double.NaN);
        double rumbo = a.optDouble("track", a.optDouble("true_heading", Double.NaN));
        double piesMin = a.optDouble("baro_rate", a.optDouble("geom_rate", Double.NaN));

        StringBuilder cuerpo = new StringBuilder();
        // Línea 1: qué es
        StringBuilder l1 = new StringBuilder();
        unir(l1, !desc.isEmpty() ? desc : tipo);
        if (!matricula.equals(nombre)) unir(l1, matricula);
        unir(l1, operador);
        if (l1.length() > 0) cuerpo.append(l1).append('\n');
        // Línea 2: ruta
        if (!ruta.isEmpty()) cuerpo.append("🛫 ").append(ruta).append('\n');
        // Línea 3: dónde y cómo vuela
        StringBuilder l3 = new StringBuilder();
        unir(l3, String.format(Locale.getDefault(), "A %.1f km", distKm));
        if (altM >= 0) unir(l3, String.format(Locale.getDefault(), "%,d m", altM));
        if (!Double.isNaN(nudos)) unir(l3, String.format(Locale.getDefault(), "%.0f km/h", nudos * 1.852));
        cuerpo.append(l3);
        // Línea 4: rumbo, subida/bajada, squawk
        StringBuilder l4 = new StringBuilder();
        if (!Double.isNaN(rumbo)) unir(l4, "Rumbo " + puntoCardinal(rumbo) + String.format(Locale.US, " (%.0f°)", rumbo));
        if (!Double.isNaN(piesMin)) {
            int mMin = (int) Math.round(piesMin * 0.3048);
            if (mMin > 150) unir(l4, "↗ subiendo " + mMin + " m/min");
            else if (mMin < -150) unir(l4, "↘ bajando " + (-mMin) + " m/min");
            else unir(l4, "→ nivelado");
        }
        if (!squawk.isEmpty()) unir(l4, "Squawk " + squawk);
        if (l4.length() > 0) cuerpo.append('\n').append(l4);

        // Flightradar24: por indicativo si lo hay, si no por matrícula. Si tienes la app
        // de FR24 instalada, Android abre el enlace directamente en ella.
        String urlFr24;
        String indicativoLimpio = indicativo.replaceAll("[^A-Za-z0-9]", "");
        String matriculaLimpia = matricula.replaceAll("[^A-Za-z0-9-]", "");
        if (!indicativoLimpio.isEmpty()) {
            urlFr24 = "https://www.flightradar24.com/" + indicativoLimpio.toUpperCase(Locale.ROOT);
        } else if (!matriculaLimpia.isEmpty()) {
            urlFr24 = "https://www.flightradar24.com/data/aircraft/" + matriculaLimpia.toLowerCase(Locale.ROOT);
        } else {
            urlFr24 = String.format(Locale.US, "https://www.flightradar24.com/%.4f,%.4f/13",
                    a.optDouble("lat"), a.optDouble("lon"));
        }

        try {
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("hex", hex);
            e.put("nombre", nombre);
            e.put("heli", heli);
            e.put("modelo", !desc.isEmpty() ? desc : tipo);
            e.put("matricula", matricula.equals(nombre) ? "" : matricula);
            e.put("operador", operador);
            e.put("dist_km", Math.round(distKm * 10) / 10.0);
            e.put("alt_m", altM);
            if (!Double.isNaN(nudos)) e.put("vel_kmh", Math.round(nudos * 1.852));
            e.put("ruta", ruta);
            e.put("militar", militar);
            if (!Double.isNaN(rumbo)) e.put("rumbo", puntoCardinal(rumbo));
            if (textoEmergencia != null) e.put("emergencia", textoEmergencia);
            e.put("url_fr24", urlFr24);
            Registro.guardar(this, e);
        } catch (Exception ignored) {}

        Intent verFr24 = AbrirFr24Activity.intentPara(this, urlFr24);
        PendingIntent piFr24 = PendingIntent.getActivity(this, idAviso * 2, verFr24,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent verAl = new Intent(Intent.ACTION_VIEW, Uri.parse("https://globe.airplanes.live/?icao=" + hex))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piAl = PendingIntent.getActivity(this, idAviso * 2 + 1, verAl,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CANAL_AVISOS)
                .setSmallIcon(R.drawable.ic_avion)
                .setContentTitle(titulo)
                .setContentText(cuerpo.toString().replace("\n", " · "))
                .setStyle(new Notification.BigTextStyle().bigText(cuerpo.toString()))
                .setContentIntent(piFr24)
                .addAction(new Notification.Action.Builder(null, "Flightradar24", piFr24).build())
                .addAction(new Notification.Action.Builder(null, "airplanes.live", piAl).build())
                .setAutoCancel(true)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(idAviso++, n);
        if (idAviso > 10_000) idAviso = 100;
    }

    // ---------------------------------------------------------------- datos extra

    /** Indicativo → "Madrid (MAD) → Barcelona (BCN)". Cadena vacía = desconocida. */
    private final Map<String, String> cacheRutas = new HashMap<>();

    private String obtenerRuta(String indicativo, double lat, double lon) {
        String clave = indicativo.trim().toUpperCase(Locale.ROOT);
        if (cacheRutas.containsKey(clave)) return cacheRutas.get(clave);
        String ruta = "";
        try {
            JSONObject avion = new JSONObject();
            avion.put("callsign", clave);
            avion.put("lat", lat);
            avion.put("lng", lon);
            JSONObject peticion = new JSONObject();
            peticion.put("planes", new JSONArray().put(avion));

            HttpURLConnection c = (HttpURLConnection) new URL("https://api.adsb.lol/api/0/routeset").openConnection();
            c.setConnectTimeout(6_000);
            c.setReadTimeout(8_000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "AvisosAviones/1.0 (Android)");
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(peticion.toString().getBytes("UTF-8"));
            }
            if (c.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String linea;
                    while ((linea = r.readLine()) != null) sb.append(linea);
                }
                JSONArray res = new JSONArray(sb.toString());
                if (res.length() > 0) ruta = formatearRuta(res.getJSONObject(0));
            }
        } catch (Exception ignored) {}
        if (cacheRutas.size() > 500) cacheRutas.clear();
        cacheRutas.put(clave, ruta);
        return ruta;
    }

    private static String formatearRuta(JSONObject r) {
        Object plausible = r.opt("plausible");
        if (Boolean.FALSE.equals(plausible) || Integer.valueOf(0).equals(plausible)) return "";
        JSONArray aeropuertos = r.optJSONArray("_airports");
        if (aeropuertos != null && aeropuertos.length() >= 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < aeropuertos.length(); i++) {
                JSONObject ap = aeropuertos.optJSONObject(i);
                if (ap == null) continue;
                String lugar = ap.optString("location", "").trim();
                if (lugar.isEmpty()) lugar = ap.optString("name", "").trim();
                String codigo = ap.optString("iata", "").trim();
                if (codigo.isEmpty()) codigo = ap.optString("icao", "").trim();
                if (sb.length() > 0) sb.append(" → ");
                sb.append(lugar.isEmpty() ? codigo : lugar + (codigo.isEmpty() ? "" : " (" + codigo + ")"));
            }
            return sb.toString();
        }
        String codigos = r.optString("_airport_codes_iata", "");
        if (codigos.isEmpty() || "unknown".equalsIgnoreCase(codigos)) codigos = r.optString("airport_codes", "");
        if (codigos.isEmpty() || "unknown".equalsIgnoreCase(codigos)) return "";
        return codigos.replace("-", " → ");
    }

    private static String puntoCardinal(double grados) {
        String[] p = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        int i = (int) Math.round(((grados % 360) + 360) % 360 / 45.0) % 8;
        return p[i];
    }

    private static String textoEmergencia(String squawk, String emergencia) {
        if ("7700".equals(squawk)) return "EMERGENCIA (7700)";
        if ("7600".equals(squawk)) return "Fallo de radio (7600)";
        if ("7500".equals(squawk)) return "Interferencia ilícita (7500)";
        if (emergencia != null && !emergencia.isEmpty() && !"none".equalsIgnoreCase(emergencia)) {
            return "EMERGENCIA (" + emergencia + ")";
        }
        return null;
    }

    private static void unir(StringBuilder sb, String s) {
        if (s == null || s.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(s.trim());
    }

    private void crearCanales() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel serv = new NotificationChannel(CANAL_SERVICIO, "Vigilancia activa",
                NotificationManager.IMPORTANCE_LOW);
        serv.setDescription("Notificación fija mientras la app vigila");
        nm.createNotificationChannel(serv);

        NotificationChannel av = new NotificationChannel(CANAL_AVISOS, "Aeronaves encima",
                NotificationManager.IMPORTANCE_HIGH);
        av.setDescription("Aviso cuando un avión o helicóptero entra en tu radio");
        av.enableVibration(true);
        nm.createNotificationChannel(av);
    }

    private Notification notificacionServicio(String texto) {
        Intent abrir = new Intent(this, MainActivity.class);
        PendingIntent piAbrir = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE);
        Intent parar = new Intent(this, VigilanciaService.class).setAction(ACCION_PARAR);
        PendingIntent piParar = PendingIntent.getService(this, 1, parar, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CANAL_SERVICIO)
                .setSmallIcon(R.drawable.ic_avion)
                .setContentTitle("Avisos Aviones")
                .setContentText(texto)
                .setOngoing(true)
                .setContentIntent(piAbrir)
                .addAction(new Notification.Action.Builder(null, "Parar", piParar).build())
                .build();
    }

    private void actualizarNotificacionServicio(String texto) {
        getSystemService(NotificationManager.class).notify(ID_SERVICIO, notificacionServicio(texto));
    }

    private void publicarEstado(String texto) {
        Intent i = new Intent(ACCION_ESTADO).setPackage(getPackageName()).putExtra(EXTRA_TEXTO, texto);
        sendBroadcast(i);
    }
}
