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
        String titulo = (heli ? "🚁 Helicóptero" : "✈️ Avión") + " encima: " + nombre;

        StringBuilder cuerpo = new StringBuilder();
        if (!desc.isEmpty()) cuerpo.append(desc);
        else if (!tipo.isEmpty()) cuerpo.append(tipo);
        if (!matricula.isEmpty() && !matricula.equals(nombre)) cuerpo.append(cuerpo.length() > 0 ? " · " : "").append(matricula);
        if (!operador.isEmpty()) cuerpo.append(cuerpo.length() > 0 ? " · " : "").append(operador);
        if (cuerpo.length() > 0) cuerpo.append("\n");
        cuerpo.append(String.format(Locale.getDefault(), "A %.1f km", distKm));
        if (altM >= 0) cuerpo.append(String.format(Locale.getDefault(), " · %,d m de altitud", altM));

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

        Intent verFr24 = new Intent(Intent.ACTION_VIEW, Uri.parse(urlFr24))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
