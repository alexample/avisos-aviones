package es.pegasusdgt;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vigilancia de Pegasus mientras conduces: GPS preciso, consulta cada pocos segundos
 * y avisos por voz cuando un Pegasus está cerca, con la dirección respecto a tu marcha.
 */
public class PegasusService extends Service {

    static final String PARAR = "es.pegasusdgt.PARAR";
    static final String FIN = "es.pegasusdgt.FIN";
    static final String ESTADO = "es.pegasusdgt.ESTADO";
    static final String TEXTO = "texto";

    private static final String CANAL_FIJO = "fijo";
    private static final String CANAL_AVISO = "pegasus_v1";
    private static final int ID_FIJO = 1;
    private static final long QUIETO_MAX_MS = 10 * 60_000L;   // parado tanto tiempo: fin de trayecto
    private static final long GRACIA_FIN_MS = 2 * 60_000L;    // tras "bajó del coche"

    private static final String[] BASES = {"https://api.airplanes.live/v2/", "https://api.adsb.lol/v2/"};

    private HandlerThread hilo;
    private Handler handler;
    private LocationManager lm;
    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private boolean ttsListo = false;
    private volatile Location yo;
    private long ultimoMovimiento = System.currentTimeMillis();
    private int base = 0;
    private long pausaHasta = 0;

    /** Por cada Pegasus a la vista: el umbral más cercano ya avisado y cuándo se le vio. */
    private static final class Seguimiento { int nivel = -1; long visto; }
    private final Map<String, Seguimiento> seguidos = new HashMap<>();

    // ------------------------------------------------------------ arranque y parada

    static void arrancar(Context c, boolean manual) {
        Config.p(c).edit().putBoolean("manual", manual).apply();
        try {
            c.startForegroundService(new Intent(c, PegasusService.class));
        } catch (Exception e) {
            Config.p(c).edit().putString("ultimo_error", "Android no dejó arrancar: " + e.getClass().getSimpleName()).apply();
        }
    }

    static void parar(Context c) {
        c.startService(new Intent(c, PegasusService.class).setAction(PARAR));
    }

    static void finConduccion(Context c) {
        if (!Config.activo(c)) return;
        try { c.startService(new Intent(c, PegasusService.class).setAction(FIN)); } catch (Exception ignored) {}
    }

    private final LocationListener oyente = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            yo = l;
            if (l.hasSpeed() && l.getSpeed() > 2.5f) ultimoMovimiento = System.currentTimeMillis(); // > 9 km/h
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private final Runnable vuelta = new Runnable() {
        @Override public void run() {
            try { comprobar(); } catch (Throwable t) { publicar("Error: " + t.getMessage(), null); }
            if (!Config.manual(PegasusService.this)
                    && System.currentTimeMillis() - ultimoMovimiento > QUIETO_MAX_MS) {
                publicar("Parado hace 10 min: fin del trayecto", null);
                terminar();
                return;
            }
            handler.postDelayed(this, Math.max(5, Config.intervaloS(PegasusService.this)) * 1000L);
        }
    };

    private final Runnable pararPorFin = this::terminar;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL_FIJO, "Vigilando", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel av = new NotificationChannel(CANAL_AVISO, "Pegasus cerca", NotificationManager.IMPORTANCE_HIGH);
        av.setSound(Uri.parse("android.resource://" + getPackageName() + "/raw/aviso"),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        av.enableVibration(true);
        nm.createNotificationChannel(av);

        hilo = new HandlerThread("pegasus");
        hilo.start();
        handler = new Handler(hilo.getLooper());
        lm = getSystemService(LocationManager.class);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pegasus:vigilancia");
        wakeLock.setReferenceCounted(false);
        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "ES"));
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                ttsListo = true;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null ? intent.getAction() : null;
        if (PARAR.equals(accion)) { terminar(); return START_NOT_STICKY; }
        if (FIN.equals(accion)) {
            if (!Config.manual(this)) {
                handler.removeCallbacks(pararPorFin);
                handler.postDelayed(pararPorFin, GRACIA_FIN_MS);
                publicar("Has bajado del coche: paro en 2 min", null);
            }
            return START_STICKY;
        }

        try {
            Notification n = fija("Vigilando Pegasus…");
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_FIJO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(ID_FIJO, n);
        } catch (Exception e) {
            Config.p(this).edit().putString("ultimo_error", "Android no dejó arrancar: " + e.getClass().getSimpleName()).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        Config.p(this).edit().putBoolean("activo", true).apply();
        handler.removeCallbacks(pararPorFin); // si vuelve a subir al coche, seguir
        ultimoMovimiento = System.currentTimeMillis();
        wakeLock.acquire(6 * 3600_000L); // en el coche la batería no es problema (y se renueva en cada arranque)
        empezarGps();
        handler.removeCallbacks(vuelta);
        handler.post(vuelta);
        return START_STICKY;
    }

    private void terminar() {
        Config.p(this).edit().putBoolean("activo", false).putBoolean("manual", false).apply();
        publicar("Parado", null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { lm.removeUpdates(oyente); } catch (Exception ignored) {}
        if (wakeLock.isHeld()) wakeLock.release();
        if (tts != null) tts.shutdown();
        hilo.quitSafely();
        Config.p(this).edit().putBoolean("activo", false).apply();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void empezarGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            publicar("Falta el permiso de ubicación", null);
            return;
        }
        try {
            lm.removeUpdates(oyente);
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.isProviderEnabled(prov)) continue;
                Location u = lm.getLastKnownLocation(prov);
                if (u != null && (yo == null || u.getTime() > yo.getTime())) yo = u;
                lm.requestLocationUpdates(prov, prov.equals(LocationManager.GPS_PROVIDER) ? 2000L : 10_000L, 0f,
                        oyente, hilo.getLooper());
            }
        } catch (SecurityException e) {
            publicar("Falta el permiso de ubicación", null);
        }
    }

    // ------------------------------------------------------------ comprobación

    private void comprobar() throws Exception {
        Location l = yo;
        if (l == null) { publicar("Esperando GPS…", null); return; }
        if (System.currentTimeMillis() < pausaHasta) {
            publicar("Servidores ocupados, reintento en " + (pausaHasta - System.currentTimeMillis()) / 1000 + " s", null);
            return;
        }
        int radioKm = Config.radioKm(this);
        JSONArray ac = descargar(l.getLatitude(), l.getLongitude(), (int) Math.ceil(radioKm / 1.852));

        Set<String> vigilados = new HashSet<>();
        for (String r : Config.matriculas(this)) vigilados.add(Config.normalizar(r));
        List<Double> umbrales = Config.umbrales(this);

        long ahora = System.currentTimeMillis();
        String masCercano = null;
        double distMasCercano = Double.MAX_VALUE;
        for (int i = 0; i < ac.length(); i++) {
            JSONObject a = ac.optJSONObject(i);
            if (a == null || !a.has("lat") || !a.has("lon")) continue;
            String reg = a.optString("r", "");
            if (!vigilados.contains(Config.normalizar(reg))) continue;
            boolean enTierra = "ground".equals(a.optString("alt_baro"));

            float[] res = new float[2];
            Location.distanceBetween(l.getLatitude(), l.getLongitude(), a.getDouble("lat"), a.getDouble("lon"), res);
            double distKm = res[0] / 1000.0;
            String donde = direccion(l, res[1]);
            int altM = (int) Math.round(a.optDouble("alt_baro", -1) * 0.3048);
            if (distKm > radioKm) continue;

            if (distKm < distMasCercano) {
                distMasCercano = distKm;
                masCercano = String.format(Locale.getDefault(), "🚁 %s a %s · %s%s", reg, km(distKm), donde,
                        enTierra ? " (en tierra)" : altM > 0 ? String.format(Locale.getDefault(), " · %,d m", altM) : "");
            }
            if (enTierra) continue; // posado: se muestra, pero no se avisa

            // Nivel = cuántos umbrales ha cruzado ya (0 = solo a la vista dentro del radio)
            int nivel = 0;
            for (double u : umbrales) if (distKm <= u) nivel++;
            Seguimiento s = seguidos.get(reg);
            boolean nuevo = s == null;
            if (nuevo) { s = new Seguimiento(); seguidos.put(reg, s); }
            s.visto = ahora;
            if (nivel > s.nivel) {
                s.nivel = nivel;
                avisar(reg, distKm, donde, altM, nuevo);
            }
        }
        // Olvidar los que llevan 5 min sin verse (si vuelven, se avisa de nuevo)
        Iterator<Map.Entry<String, Seguimiento>> it = seguidos.entrySet().iterator();
        while (it.hasNext()) if (ahora - it.next().getValue().visto > 5 * 60_000L) it.remove();

        String hora = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String vel = l.hasSpeed() ? String.format(Locale.getDefault(), " · %.0f km/h", l.getSpeed() * 3.6) : "";
        if (masCercano == null) {
            publicar("Sin Pegasus en " + radioKm + " km", "Comprobado a las " + hora + vel + " · GPS ±" + Math.round(l.getAccuracy()) + " m");
        } else {
            publicar(masCercano, "Comprobado a las " + hora + vel);
        }
    }

    /** "delante", "a la derecha", "detrás a la izquierda"… según hacia dónde vas; si vas parado, punto cardinal. */
    private static String direccion(Location yo, float rumboAlHeli) {
        if (yo.hasBearing() && yo.hasSpeed() && yo.getSpeed() > 3f) {
            double rel = ((rumboAlHeli - yo.getBearing()) % 360 + 540) % 360 - 180; // -180..180
            double a = Math.abs(rel);
            String lado = rel > 0 ? "a la derecha" : "a la izquierda";
            if (a <= 25) return "delante";
            if (a <= 65) return "delante " + lado;
            if (a <= 115) return lado;
            if (a <= 155) return "detrás " + lado;
            return "detrás";
        }
        String[] p = {"al norte", "al noreste", "al este", "al sureste", "al sur", "al suroeste", "al oeste", "al noroeste"};
        return p[(int) Math.round(((rumboAlHeli % 360) + 360) % 360 / 45.0) % 8];
    }

    private static String km(double d) {
        if (d < 1) return Math.round(d * 1000 / 50) * 50 + " m";
        if (d < 10) return String.format(Locale.getDefault(), "%.1f km", d);
        return Math.round(d) + " km";
    }

    private void avisar(String reg, double distKm, String donde, int altM, boolean nuevo) {
        String texto = String.format(Locale.getDefault(), "%s a %s, %s%s", reg, km(distKm), donde,
                altM > 0 ? String.format(Locale.getDefault(), " · %,d m", altM) : "");
        Notification n = new Notification.Builder(this, CANAL_AVISO)
                .setSmallIcon(R.drawable.ic_heli)
                .setContentTitle("🚁 Pegasus " + (nuevo ? "a la vista" : "más cerca"))
                .setContentText(texto)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(100 + Math.abs(reg.hashCode() % 1000), n);

        if (Config.voz(this) && ttsListo) {
            String km = distKm < 1 ? "menos de un kilómetro"
                    : (distKm < 10 ? String.format(Locale.getDefault(), "%.0f", distKm) : String.valueOf(Math.round(distKm)))
                      + (Math.round(distKm) == 1 ? " kilómetro" : " kilómetros");
            tts.speak("Pegasus a " + km + ", " + donde, TextToSpeech.QUEUE_ADD, null, reg + System.currentTimeMillis());
        }
        if (nuevo) Config.anotarAvistamiento(this, reg, distKm, donde, altM);
    }

    private JSONArray descargar(double lat, double lon, int radioNm) throws Exception {
        Exception ultimo = null;
        for (int intento = 0; intento < BASES.length; intento++) {
            String b = BASES[(base + intento) % BASES.length];
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(String.format(Locale.US, "%spoint/%.5f/%.5f/%d",
                        b, lat, lon, Math.max(1, Math.min(250, radioNm)))).openConnection();
                c.setConnectTimeout(8_000);
                c.setReadTimeout(10_000);
                c.setRequestProperty("User-Agent", "PegasusDGT/1.0 (Android)");
                int code = c.getResponseCode();
                if (code == 429) { ultimo = new Exception("HTTP 429"); continue; }
                if (code != 200) throw new Exception("HTTP " + code);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String linea;
                    while ((linea = r.readLine()) != null) sb.append(linea);
                }
                base = (base + intento + 1) % BASES.length; // repartir entre los dos servidores
                JSONArray ac = new JSONObject(sb.toString()).optJSONArray("ac");
                return ac != null ? ac : new JSONArray();
            } catch (Exception e) {
                ultimo = e;
            }
        }
        if (ultimo != null && "HTTP 429".equals(ultimo.getMessage())) pausaHasta = System.currentTimeMillis() + 30_000L;
        throw new Exception("sin conexión con los servidores (" + (ultimo != null ? ultimo.getMessage() : "?") + ")");
    }

    // ------------------------------------------------------------ notificación fija y estado

    private Notification fija(String texto) {
        PendingIntent abrir = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent parar = PendingIntent.getService(this, 2,
                new Intent(this, PegasusService.class).setAction(PARAR), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL_FIJO)
                .setSmallIcon(R.drawable.ic_heli)
                .setContentTitle("Pegasus DGT")
                .setContentText(texto)
                .setOngoing(true)
                .setContentIntent(abrir)
                .addAction(new Notification.Action.Builder(null, "Parar", parar).build())
                .build();
    }

    private String ultimoFijo = "";

    private void publicar(String titulo, String detalle) {
        sendBroadcast(new Intent(ESTADO).setPackage(getPackageName())
                .putExtra(TEXTO, titulo + (detalle != null ? "\n" + detalle : "")));
        Config.p(this).edit().putString("ultimo_estado", titulo + (detalle != null ? "\n" + detalle : "")).apply();
        if (!titulo.equals(ultimoFijo)) {
            ultimoFijo = titulo;
            getSystemService(NotificationManager.class).notify(ID_FIJO, fija(titulo));
        }
    }

    /** Para "Probar aviso" desde la app. */
    static void probarVoz(Context c, TextToSpeech t) {
        t.speak("Pegasus a 5 kilómetros, delante a la derecha", TextToSpeech.QUEUE_FLUSH, null, "prueba");
    }
}
