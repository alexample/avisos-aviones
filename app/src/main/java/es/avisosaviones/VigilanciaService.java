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
import android.os.SystemClock;

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
import java.util.Iterator;
import java.util.List;
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


    private HandlerThread hilo;
    private Handler handler;
    private LocationManager lm;
    private PowerManager.WakeLock wakeLock;
    private volatile Location ultimaUbicacion;
    private final Map<String, Long> avisados = new HashMap<>();
    private long ultimoAviso = 0;
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
            wakeLock.acquire(90_000L); // despierto solo mientras se comprueba
            ultimaComprobacion = SystemClock.elapsedRealtime();
            Ajustes.prefs(VigilanciaService.this).edit().putLong(Vigilante.LATIDO, System.currentTimeMillis()).apply();
            long espera = Ajustes.intervaloS(VigilanciaService.this) * 1000L;
            try {
                espera = comprobarCielo();
            } catch (ServidoresOcupados o) {
                espera = Math.max(espera, o.esperaMs + 1000);
                publicarEstado("Servidores ocupados · reintento en " + Math.max(1, o.esperaMs / 1000) + " s"
                        + "\nLos servidores gratuitos limitan las consultas; la app espera y sigue sola.");
            } catch (Throwable t) {
                publicarEstado("Error: " + t.getMessage());
            }
            programarSiguiente(espera);
        }
    };

    static final String ACCION_TIC = "es.avisosaviones.TIC";
    private long ultimaComprobacion = 0;
    private long esperaActual = 0;
    private boolean enPrimerPlano = false;

    /**
     * Esperas cortas (algo a punto de llegar): el móvil se mantiene despierto ese rato.
     * Esperas largas: el móvil puede dormir y una alarma lo despierta para la siguiente comprobación.
     */
    private void programarSiguiente(long ms) {
        esperaActual = ms;
        handler.removeCallbacks(comprobar);
        if (ms <= 60_000L) {
            cancelarTic();
            wakeLock.acquire(ms + 15_000L);
        } else {
            if (wakeLock.isHeld()) wakeLock.release();
            programarTic(ms);
        }
        handler.postDelayed(comprobar, ms); // con la pantalla encendida llega a su hora; dormido, lo despierta la alarma
    }

    private PendingIntent piTic() {
        return PendingIntent.getService(this, 5, new Intent(this, VigilanciaService.class).setAction(ACCION_TIC),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void programarTic(long ms) {
        android.app.AlarmManager am = getSystemService(android.app.AlarmManager.class);
        long cuando = SystemClock.elapsedRealtime() + ms;
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, piTic());
        } else {
            am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, piTic());
        }
    }

    private void cancelarTic() {
        getSystemService(android.app.AlarmManager.class).cancel(piTic());
    }

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
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_PARAR.equals(intent.getAction())) {
            Ajustes.prefs(this).edit().putBoolean(Ajustes.ACTIVO, false).apply();
            Vigilante.cancelar(this);
            publicarEstado("Parado.");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACCION_TIC.equals(intent.getAction()) && enPrimerPlano) {
            // Alarma: toca comprobar (si no se ha hecho ya hace nada con la pantalla encendida)
            if (SystemClock.elapsedRealtime() - ultimaComprobacion > esperaActual * 0.7) {
                handler.removeCallbacks(comprobar);
                handler.post(comprobar);
            }
            return START_STICKY;
        }

        Notification n = notificacionServicio("Vigilando el cielo…");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_SERVICIO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(ID_SERVICIO, n);
            }
        } catch (Exception e) {
            // Android no deja reanudarla en segundo plano: avisar para que el usuario toque y la reanude.
            // (Se queda "activa" para que el vigilante lo siga intentando.)
            Vigilante.apuntarMotivo(this, "Android no dejó reanudarla (" + e.getClass().getSimpleName() + ")");
            Vigilante.avisarParada(this);
            Vigilante.programar(this, 15 * 60 * 1000L);
            stopSelf();
            return START_NOT_STICKY;
        }

        enPrimerPlano = true;
        // Si el hilo de trabajo se ha quedado colgado (sin latidos), se cambia por uno nuevo
        if (!Vigilante.vivo(this)) reiniciarHilo();

        empezarUbicacion();
        handler.removeCallbacks(comprobar);
        handler.post(comprobar);
        Vigilante.programar(this, 15 * 60 * 1000L);
        return START_STICKY;
    }

    private void reiniciarHilo() {
        handler.removeCallbacksAndMessages(null);
        try { lm.removeUpdates(oyente); } catch (Exception ignored) {}
        hilo.quit();
        hilo = new HandlerThread("vigilancia");
        hilo.start();
        handler = new Handler(hilo.getLooper());
    }

    /** El usuario quita la app de recientes: algunos fabricantes matan el servicio, así que se programa volver. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (Ajustes.activo(this)) Vigilante.programar(this, 5_000L);
    }

    @Override
    public void onDestroy() {
        if (Ajustes.activo(this)) {
            // No lo ha parado el usuario: lo ha cerrado Android
            Vigilante.apuntarMotivo(this, "Android cerró el servicio");
            Vigilante.programar(this, 10_000L);
        }
        handler.removeCallbacksAndMessages(null);
        cancelarTic();
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
            // Ahorro: ubicación por wifi/red cada 3 min (de sobra para radios de km); GPS solo si no hay red.
            // Además se aprovechan gratis las ubicaciones que pidan otras apps (proveedor pasivo).
            boolean red = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            for (String prov : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                if (!fina && !prov.equals(LocationManager.NETWORK_PROVIDER)) continue;
                if (prov.equals(LocationManager.GPS_PROVIDER) && red) {
                    Location ult = lm.getLastKnownLocation(prov);
                    if (ult != null) oyente.onLocationChanged(ult);
                    continue;
                }
                if (!lm.isProviderEnabled(prov)) continue;
                Location ult = lm.getLastKnownLocation(prov);
                if (ult != null) oyente.onLocationChanged(ult);
                boolean gps = prov.equals(LocationManager.GPS_PROVIDER);
                lm.requestLocationUpdates(prov, gps ? 300_000L : 180_000L, gps ? 200f : 100f, oyente, looper);
            }
        } catch (SecurityException e) {
            publicarEstado("Falta permiso de ubicación.");
        }
    }

    // ---------------------------------------------------------------- consulta

    /** Comprueba el cielo y devuelve cuántos ms esperar hasta la siguiente comprobación. */
    private long comprobarCielo() throws Exception {
        long base = Ajustes.intervaloS(this) * 1000L;
        Location yo = ultimaUbicacion;
        if (yo == null) {
            publicarEstado("Esperando ubicación… (¿tienes la ubicación activada?)");
            return Math.max(base, 30_000L);
        }

        List<Alerta> alertas = Alertas.cargar(this);
        List<Alerta> activas = new ArrayList<>();
        for (Alerta al : alertas) if (al.activa) activas.add(al);
        if (activas.isEmpty()) {
            publicarEstado("Sin alertas encendidas\nEnciende alguna en la app");
            return 120_000L;
        }
        double radioAlertasKm = Math.min(400, Alertas.radioMaximo(activas)); // 0 si todas son "sin límite"
        // Se mira un radio más amplio para prever quién va a entrar y cuándo
        double radioConsultaKm = radioAlertasKm > 0
                ? Math.min(460, Math.max(radioAlertasKm, Ajustes.radioPrevisionKm(this))) : 0;
        double lat0 = yo.getLatitude(), lon0 = yo.getLongitude();

        // Aparatos alrededor (alertas con distancia) + búsquedas globales (alertas sin límite)
        Map<String, JSONObject> porHex = new java.util.LinkedHashMap<>();
        int consultados = 0;
        if (radioConsultaKm > 0) {
            int radioNm = (int) Math.max(1, Math.ceil(radioConsultaKm / 1.852));
            JSONArray cerca0 = descargar(yo.getLatitude(), yo.getLongitude(), radioNm);
            consultados = cerca0.length();
            for (int i = 0; i < cerca0.length(); i++) {
                JSONObject o = cerca0.optJSONObject(i);
                if (o != null) porHex.put(o.optString("hex"), o);
            }
        }
        java.util.Set<String> globales = new java.util.LinkedHashSet<>();
        for (Alerta al : activas) {
            globales.addAll(al.consultasGlobales());
            // Sin límite + servicio: buscar por Mode S las aeronaves etiquetadas
            if (al.sinLimite() && !al.servicios.isEmpty()) {
                for (String r : Servicios.matriculasDe(this, al.servicios)) globales.add("reg/" + Servicios.conGuion(r));
                List<String> hexes = Servicios.hexDe(this, al.servicios);
                for (int i = 0; i < hexes.size(); i += 20) {
                    globales.add("hex/" + String.join(",", hexes.subList(i, Math.min(hexes.size(), i + 20))));
                }
            }
        }
        JSONArray lejos = consultasGlobales(globales);
        for (int i = 0; i < lejos.length(); i++) {
            JSONObject o = lejos.optJSONObject(i);
            if (o != null && !porHex.containsKey(o.optString("hex"))) porHex.put(o.optString("hex"), o);
        }
        List<JSONObject> aparatos = new ArrayList<>(porHex.values());

        Map<String, String[]> fr24 = null; // se descarga solo si hace falta, una vez por vuelta
        int cerca = 0, coinciden = 0, enviados = 0;
        // Previsión: cuándo entra en alguna alerta el primero que viene hacia aquí
        double proximaS = Double.MAX_VALUE;
        String quienLlega = null;
        String errorAparato = null;
        double masCerca = Double.MAX_VALUE;
        long ahora = System.currentTimeMillis();
        for (JSONObject a : aparatos) {
          try {
            if (!a.has("lat") || !a.has("lon")) continue;
            if ("ground".equals(a.optString("alt_baro"))) continue;

            float[] d = new float[1];
            Location.distanceBetween(yo.getLatitude(), yo.getLongitude(),
                    a.getDouble("lat"), a.getDouble("lon"), d);
            Alerta.Aeronave av = new Alerta.Aeronave();
            av.distKm = d[0] / 1000.0;
            if (av.distKm <= radioAlertasKm) {
                cerca++;
                masCerca = Math.min(masCerca, av.distKm);
            }
            av.altM = altitudMetros(a);
            av.tipoCodigo = a.optString("t", "").trim().toUpperCase(Locale.ROOT);
            av.clase = Tipos.clase(this, a.optString("category", ""), av.tipoCodigo);
            av.heli = Tipos.HELI.equals(av.clase) || esHelicoptero(a);
            av.militar = (a.optInt("dbFlags", 0) & 1) != 0;
            av.emergencia = textoEmergencia(a.optString("squawk", "").trim(), a.optString("emergency", "none")) != null;
            av.matricula = a.optString("r", "");
            av.indicativo = a.optString("flight", "").trim();
            av.squawk = a.optString("squawk", "").trim();
            av.servicio = Servicios.de(this, a.optString("hex", ""), av.matricula);

            // ¿Cuándo podría entrar en alguna alerta? (si ya se avisó hace poco, no hace falta)
            String hexP = a.optString("hex", "?").toUpperCase(Locale.ROOT);
            Long avisadoP = avisados.get(hexP);
            if (avisadoP == null || ahora - avisadoP >= ENFRIAMIENTO_MS) {
                for (Alerta al : activas) {
                    if (al.sinLimite() || av.distKm <= al.radioKm) continue;
                    if (!al.coincideSalvoDistancia(av)) continue;
                    double t = segundosHastaEntrar(lat0, lon0, a, av.distKm, al.radioKm, av.heli);
                    if (t < proximaS) {
                        proximaS = t;
                        quienLlega = !av.indicativo.isEmpty() ? av.indicativo
                                : (!av.matricula.isEmpty() ? av.matricula : hexP);
                    }
                }
            }

            // Alertas que cumple sin mirar la ruta
            List<Alerta> candidatas = new ArrayList<>();
            for (Alerta al : activas) if (al.coincideSinRuta(av)) candidatas.add(al);
            if (candidatas.isEmpty()) continue;

            String hex = a.optString("hex", "?").toUpperCase(Locale.ROOT);
            Long ultimo = avisados.get(hex);
            if (ultimo != null && ahora - ultimo < ENFRIAMIENTO_MS) {
                avisados.put(hex, ahora); // sigue en la zona: alarga el silencio
                coinciden++;
                continue;
            }

            if (fr24 == null) fr24 = cargarFr24(yo.getLatitude(), yo.getLongitude(), Math.max(radioConsultaKm, 5));
            String[] datosFr24 = fr24.get(hex); // {id, origen, destino, vuelo, indicativo}

            Alerta cumplida = null;
            String origen = null, destino = null, vuelo = null;
            for (Alerta al : candidatas) {
                if (!al.necesitaRuta()) { cumplida = al; break; }
                if (origen == null) {
                    origen = datosFr24 != null ? datosFr24[1] : "";
                    destino = datosFr24 != null ? datosFr24[2] : "";
                    vuelo = datosFr24 != null ? datosFr24[3] : "";
                    if ((origen.isEmpty() && destino.isEmpty()) || vuelo.isEmpty()) {
                        String[] r = obtenerRuta(av.indicativo); // adsbdb como respaldo
                        if (origen.isEmpty() && destino.isEmpty()) { origen = r[2]; destino = r[3]; }
                        if (vuelo.isEmpty()) vuelo = r[4];
                    }
                }
                if (al.coincideRuta(origen, destino, vuelo)) { cumplida = al; break; }
            }
            if (cumplida == null) continue;

            coinciden++;
            avisados.put(hex, ahora);
            avisar(a, av.heli, av.distKm, av.altM, av.distKm < 3, datosFr24,
                    (av.servicio.isEmpty() ? "" : Servicios.legible(av.servicio) + " · ") + "🔔 " + cumplida.nombre,
                    Sonidos.canal(this, cumplida));
            ultimoAviso = System.currentTimeMillis();
            // Tope de avisos NUEVOS por vuelta (los ya avisados no cuentan); el resto sale en la siguiente
            if (++enviados >= 10) { proximaS = 0; break; }
          } catch (Exception ex) {
            // Un aparato con datos raros no debe impedir revisar los demás
            errorAparato = ex.getClass().getSimpleName() + ": " + ex.getMessage();
          }
        }

        // Limpieza de la memoria de avisos
        Iterator<Map.Entry<String, Long>> it = avisados.entrySet().iterator();
        while (it.hasNext()) if (ahora - it.next().getValue() > ENFRIAMIENTO_MS) it.remove();

        String hora = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String titulo = cerca == 0 ? "Vigilando · cielo despejado"
                : String.format(Locale.getDefault(), "Vigilando · %d %s (la más cercana a %s)",
                    cerca, cerca == 1 ? "aeronave" : "aeronaves", Alerta.km((float) masCerca));
        String extra = (ultimoAviso > 0
                ? " · último aviso " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ultimoAviso)) : "")
                + (errorAparato != null ? "\n⚠️ " + errorAparato : "");
        // Siguiente comprobación: justo antes de que llegue el primero (con margen), entre el mínimo y el máximo.
        // El máximo también cubre lo que aún no vemos: lo más rápido (≈1000 km/h) que entre por el borde del radio amplio.
        long maxMs = Ajustes.esperaMaxS(this) * 1000L;
        if (radioConsultaKm > radioAlertasKm && radioAlertasKm > 0) {
            maxMs = Math.min(maxMs, (long) ((radioConsultaKm - radioAlertasKm) / 1000.0 * 3600_000L) - 30_000L);
        }
        if (!globales.isEmpty()) maxMs = Math.min(maxMs, 120_000L);
        maxMs = Math.max(maxMs, base);
        long espera = proximaS == Double.MAX_VALUE ? maxMs : (long) (proximaS * 1000) - 20_000L;
        espera = Math.max(base, Math.min(maxMs, espera));
        String prevision = " · próxima en " + duracion(espera)
                + (quienLlega != null && proximaS < 1800 ? " (" + quienLlega + " llega en ~" + duracion((long) (proximaS * 1000)) + ")" : "");
        extra = prevision + extra;

        String txt = String.format(Locale.getDefault(),
                "%s\n%d cumplen tus alertas%s · comprobado a las %s · GPS ±%.0f m%s",
                titulo, coinciden, globales.isEmpty() ? "" : " · " + lejos.length() + " en búsquedas sin límite",
                hora, yo.getAccuracy(),
                estadoFr24.isEmpty() ? "" : " · " + estadoFr24) + extra;
        publicarEstado(txt);
        String textoFijo = String.format(Locale.getDefault(), "Vigilando · %d cerca", cerca);
        if (!textoFijo.equals(ultimoTextoFijo)) { // actualizar la notificación solo si cambia (gasta menos)
            actualizarNotificacionServicio(textoFijo);
            ultimoTextoFijo = textoFijo;
        }
        return espera;
    }

    private String ultimoTextoFijo = "";

    private static String duracion(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 90) return s + " s";
        return Math.round(s / 60.0) + " min";
    }

    /**
     * Segundos hasta que el aparato entre en un círculo de radio rKm alrededor de (lat0, lon0),
     * suponiendo que sigue recto a la misma velocidad. Si está cerca, se usa también el peor caso
     * (que gire hacia aquí) porque los giros cambian las cuentas. MAX_VALUE = no viene.
     */
    private static double segundosHastaEntrar(double lat0, double lon0, JSONObject a, double distKm, double rKm, boolean heli) {
        double falta = distKm - rKm;
        if (falta <= 0) return 0;
        double gs = a.optDouble("gs", Double.NaN);          // nudos
        double rumbo = a.optDouble("track", Double.NaN);    // grados
        double kmh = Double.isNaN(gs) ? Double.NaN : gs * 1.852;
        // Helicópteros: giran, patrullan y dan vueltas, así que nunca se fía de su rumbo: siempre el peor caso
        // (que venga directo a ~280 km/h, más de lo que corren un EC135 o un AS355)
        double peorKmh = heli ? 280 : (Double.isNaN(kmh) ? 900 : Math.max(kmh * 1.1, 250));
        double peor = falta / peorKmh * 3600;
        if (Double.isNaN(kmh) || Double.isNaN(rumbo) || kmh < 60) return peor; // parado, sin datos o muy lento

        double este = (a.optDouble("lon") - lon0) * 111.32 * Math.cos(Math.toRadians(lat0));
        double norte = (a.optDouble("lat") - lat0) * 110.57;
        double v = kmh / 3600.0;                            // km/s
        double vx = v * Math.sin(Math.toRadians(rumbo)), vy = v * Math.cos(Math.toRadians(rumbo));
        double qa = vx * vx + vy * vy;
        double qb = 2 * (este * vx + norte * vy);
        double qc = este * este + norte * norte - rKm * rKm;
        double disc = qb * qb - 4 * qa * qc;
        double t = Double.MAX_VALUE;
        if (disc >= 0) {
            double t1 = (-qb - Math.sqrt(disc)) / (2 * qa);
            if (t1 >= 0) t = t1;
        }
        if (heli || falta < 15) t = Math.min(t, peor); // helicóptero o muy cerca: puede girar hacia aquí
        return t;
    }

    private static final String[] BASES = {"https://api.airplanes.live/v2/", "https://api.adsb.lol/v2/"};

    /** Los dos servidores nos están limitando (HTTP 429): hay que esperar. */
    static final class ServidoresOcupados extends Exception {
        final long esperaMs;
        ServidoresOcupados(long esperaMs) {
            super("servidores ocupados");
            this.esperaMs = esperaMs;
        }
    }

    // Pausa por servidor tras un 429 (respetando Retry-After, y si no, 30 s → 1 min → 2 min… hasta 10 min)
    private final Map<String, Long> pausaHasta = new HashMap<>();
    private final Map<String, Long> pausaMs = new HashMap<>();

    /** Pide una ruta de la API (p. ej. "point/40.4/-3.7/5") al primer servidor que no esté en pausa. */
    private JSONArray pedir(String ruta) throws Exception {
        Exception ultimoError = null;
        long ahora = System.currentTimeMillis();
        long menorEspera = Long.MAX_VALUE;
        boolean todosEnPausa = true;
        for (String base : BASES) {
            Long hasta = pausaHasta.get(base);
            if (hasta != null && ahora < hasta) {
                menorEspera = Math.min(menorEspera, hasta - ahora);
                continue;
            }
            todosEnPausa = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + ruta).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("User-Agent", "AvisosAviones/1.0 (Android)");
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 429 || code == 503) {
                    long espera;
                    long retry = -1;
                    try { retry = Long.parseLong(String.valueOf(c.getHeaderField("Retry-After")).trim()); } catch (Exception ignored) {}
                    if (retry > 0) espera = Math.min(600_000L, retry * 1000L);
                    else {
                        Long antes = pausaMs.get(base);
                        espera = antes == null ? 30_000L : Math.min(600_000L, antes * 2);
                    }
                    pausaMs.put(base, espera);
                    pausaHasta.put(base, ahora + espera);
                    menorEspera = Math.min(menorEspera, espera);
                    todosEnPausa = true;
                    ultimoError = new Exception("HTTP " + code);
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String linea;
                    while ((linea = r.readLine()) != null) sb.append(linea);
                }
                pausaMs.remove(base);
                JSONObject json = new JSONObject(sb.toString());
                JSONArray ac = json.optJSONArray("ac");
                if (ac == null) ac = json.optJSONArray("aircraft");
                return ac != null ? ac : new JSONArray();
            } catch (Exception e) {
                ultimoError = e;
                todosEnPausa = false;
            }
        }
        if (menorEspera != Long.MAX_VALUE && (todosEnPausa || ultimoError == null)) throw new ServidoresOcupados(menorEspera);
        throw new Exception("sin conexión con los servidores de datos ("
                + (ultimoError != null ? ultimoError.getMessage() : "?") + ")");
    }

    private JSONArray descargar(double lat, double lon, int radioNm) throws Exception {
        return pedir(String.format(Locale.US, "point/%.4f/%.4f/%d", lat, lon, radioNm));
    }

    // ---------------------------------------------------------------- búsquedas sin límite

    private final Map<String, JSONArray> cacheRuta = new HashMap<>();
    private final Map<String, Long> horaRuta = new HashMap<>();

    /**
     * Aparatos de las alertas "sin límite". Para no saturar la API se hace como mucho UNA consulta
     * por vuelta (la más antigua) y cada una se repite como mucho cada 2 minutos; entre medias se usa lo guardado.
     */
    private JSONArray consultasGlobales(java.util.Set<String> rutas) {
        cacheRuta.keySet().retainAll(rutas);
        horaRuta.keySet().retainAll(rutas);
        long ahora = System.currentTimeMillis();
        String masAntigua = null;
        long horaMasAntigua = Long.MAX_VALUE;
        for (String r : rutas) {
            long h = horaRuta.containsKey(r) ? horaRuta.get(r) : 0;
            if (h < horaMasAntigua) { horaMasAntigua = h; masAntigua = r; }
        }
        if (masAntigua != null && ahora - horaMasAntigua >= 120_000L) {
            try {
                cacheRuta.put(masAntigua, pedir(masAntigua));
                horaRuta.put(masAntigua, ahora);
            } catch (Exception ignored) {
                // En pausa o sin conexión: se reintenta en otra vuelta con lo que haya guardado
            }
        }
        JSONArray todo = new JSONArray();
        for (JSONArray ac : cacheRuta.values()) {
            for (int i = 0; i < ac.length(); i++) {
                Object o = ac.opt(i);
                if (o != null) todo.put(o);
            }
        }
        return todo;
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

    private void avisar(JSONObject a, boolean heli, double distKm, int altM, boolean dentro, String[] fr24,
                        String nombreAlerta, String canal) {
        String indicativo = a.optString("flight", "").trim();
        String matricula = a.optString("r", "").trim();
        String tipo = a.optString("t", "").trim();
        String desc = a.optString("desc", "").trim();
        if (desc.isEmpty()) {
            Tipos.Tipo info = Tipos.buscarCodigo(this, tipo);
            if (info != null) desc = info.nombre;
        }
        String operador = a.optString("ownOp", "").trim();
        String hex = a.optString("hex", "");

        String nombre = !indicativo.isEmpty() ? indicativo : (!matricula.isEmpty() ? matricula : hex.toUpperCase(Locale.ROOT));

        boolean militar = (a.optInt("dbFlags", 0) & 1) != 0;
        String squawk = a.optString("squawk", "").trim();
        String emergencia = a.optString("emergency", "none");
        String textoEmergencia = textoEmergencia(squawk, emergencia);

        String[] infoRuta = obtenerRuta(indicativo);
        String ruta = infoRuta[0];
        if (operador.isEmpty()) operador = infoRuta[1];
        if (fr24 != null && !fr24[1].isEmpty() && !fr24[2].isEmpty()) {
            // FR24 tiene la ruta real del día; adsbdb solo la habitual del indicativo
            boolean mismaRuta = fr24[1].equalsIgnoreCase(infoRuta[2]) && fr24[2].equalsIgnoreCase(infoRuta[3]);
            if (!mismaRuta) ruta = fr24[1] + " → " + fr24[2];
        }
        String vueloFr24 = fr24 != null ? fr24[3] : "";
        String nombreTitulo = (!vueloFr24.isEmpty() && !vueloFr24.equalsIgnoreCase(indicativo))
                ? vueloFr24 + " (" + nombre + ")" : nombre;

        String donde = dentro ? " encima: " : String.format(Locale.getDefault(), " a %.0f km: ", distKm);
        String titulo;
        if (textoEmergencia != null) titulo = "🚨 " + textoEmergencia + ": " + nombreTitulo;
        else if (militar) titulo = (heli ? "🚁🎖️ Helicóptero militar" : "✈️🎖️ Avión militar") + donde + nombreTitulo;
        else titulo = (heli ? "🚁 Helicóptero" : "✈️ Avión") + donde + nombreTitulo;

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
        // FR24 no encuentra los vuelos por indicativo ATC (p. ej. IBE07PH), así que se usa
        // la matrícula: su ficha muestra el vuelo en curso.
        if (fr24 != null && !fr24[0].isEmpty()) {
            String cs = !fr24[4].isEmpty() ? fr24[4] : (!indicativoLimpio.isEmpty() ? indicativoLimpio : matriculaLimpia);
            urlFr24 = "https://www.flightradar24.com/" + cs.replaceAll("[^A-Za-z0-9]", "") + "/" + fr24[0];
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
            e.put("nombre", nombreTitulo);
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
            e.put("reg", matricula);
            e.put("callsign", indicativo);
            if (!vueloFr24.isEmpty()) e.put("vuelo", vueloFr24);
            e.put("dentro", dentro);
            e.put("alerta", nombreAlerta);
            Registro.guardar(this, e);
        } catch (Exception ignored) {}

        Intent verFr24 = AbrirFr24Activity.intentPara(this, urlFr24, matricula, indicativo);
        PendingIntent piFr24 = PendingIntent.getActivity(this, idAviso * 2, verFr24,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent verAl = new Intent(Intent.ACTION_VIEW, Uri.parse("https://globe.airplanes.live/?icao=" + hex))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piAl = PendingIntent.getActivity(this, idAviso * 2 + 1, verAl,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, canal)
                .setSmallIcon(R.drawable.ic_avion)
                .setContentTitle(titulo)
                .setSubText(nombreAlerta)
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

    /** Indicativo → {ruta, aerolínea}. Cadenas vacías = desconocido. */
    private final Map<String, String[]> cacheRutas = new HashMap<>();

    /** Ruta y aerolínea según adsbdb.com (gratis, sin clave). */
    private String[] obtenerRuta(String indicativo) {
        String clave = indicativo.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (clave.isEmpty()) return new String[]{"", "", "", "", ""};
        if (cacheRutas.containsKey(clave)) return cacheRutas.get(clave);
        String[] res = {"", "", "", "", ""};
        boolean definitivo = false;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.adsbdb.com/v0/callsign/" + clave).openConnection();
            c.setConnectTimeout(6_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("User-Agent", "AvisosAviones/1.0 (Android)");
            int code = c.getResponseCode();
            if (code == 404) definitivo = true; // ruta desconocida
            if (code == 200) {
                definitivo = true;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String linea;
                    while ((linea = r.readLine()) != null) sb.append(linea);
                }
                JSONObject fr = new JSONObject(sb.toString()).optJSONObject("response");
                if (fr != null) fr = fr.optJSONObject("flightroute");
                if (fr != null) {
                    String o = aeropuerto(fr.optJSONObject("origin"));
                    String d = aeropuerto(fr.optJSONObject("destination"));
                    if (!o.isEmpty() && !d.isEmpty()) res[0] = o + " → " + d;
                    res[2] = codigoAeropuerto(fr.optJSONObject("origin"));
                    res[3] = codigoAeropuerto(fr.optJSONObject("destination"));
                    res[4] = fr.optString("callsign_iata", "").trim();
                    JSONObject al = fr.optJSONObject("airline");
                    if (al != null) res[1] = al.optString("name", "").trim();
                }
            }
        } catch (Exception ignored) {}
        if (definitivo) {
            if (cacheRutas.size() > 500) cacheRutas.clear();
            cacheRutas.put(clave, res);
        }
        return res;
    }

    private static String codigoAeropuerto(JSONObject ap) {
        if (ap == null) return "";
        String iata = ap.optString("iata_code", "").trim();
        String icao = ap.optString("icao_code", "").trim();
        return iata.isEmpty() ? icao : iata;
    }

    // ---------------------------------------------------------------- Flightradar24

    /** Último estado de la consulta a FR24, para mostrarlo en la app. */
    private volatile String estadoFr24 = "";

    /**
     * Vuelos de FR24 en un cuadrado alrededor de la posición: hex → {id, origen, destino, vuelo, indicativo}.
     * Da la ruta real del día y el identificador para abrir el vuelo en el mapa.
     */
    private Map<String, String[]> cargarFr24(double lat, double lon, double radioKm) {
        Map<String, String[]> mapa = new HashMap<>();
        try {
            double dLat = radioKm / 111.0 + 0.05;
            double dLon = radioKm / (111.0 * Math.max(0.2, Math.cos(Math.toRadians(lat)))) + 0.05;
            String url = String.format(Locale.US,
                    "https://data-cloud.flightradar24.com/zones/fcgi/feed.js?bounds=%.4f,%.4f,%.4f,%.4f"
                            + "&faa=1&satellite=1&mlat=1&flarm=1&adsb=1&gnd=0&air=1&vehicles=0&estimated=1"
                            + "&maxage=14400&gliders=1&stats=0",
                    lat + dLat, lat - dLat, lon - dLon, lon + dLon);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("User-Agent", AbrirFr24Activity.UA_NAVEGADOR);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Referer", "https://www.flightradar24.com/");
            c.setRequestProperty("Origin", "https://www.flightradar24.com");
            int code = c.getResponseCode();
            if (code != 200) {
                estadoFr24 = "FR24: no disponible (" + code + "), uso rutas aproximadas";
                return mapa;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String linea;
                while ((linea = r.readLine()) != null) sb.append(linea);
            }
            JSONObject json = new JSONObject(sb.toString());
            Iterator<String> claves = json.keys();
            while (claves.hasNext()) {
                String id = claves.next();
                JSONArray v = json.optJSONArray(id);
                if (v == null || v.length() < 17) continue;
                String hex = v.optString(0, "").toUpperCase(Locale.ROOT);
                if (hex.isEmpty()) continue;
                mapa.put(hex, new String[]{
                        id,
                        v.optString(11, "").trim(),
                        v.optString(12, "").trim(),
                        v.optString(13, "").trim(),
                        v.optString(16, "").trim()});
            }
            estadoFr24 = "FR24: OK (" + mapa.size() + " vuelos)";
        } catch (Exception e) {
            estadoFr24 = "FR24: error " + e.getClass().getSimpleName() + ", uso rutas aproximadas";
        }
        return mapa;
    }

    private static String aeropuerto(JSONObject ap) {
        if (ap == null) return "";
        String lugar = ap.optString("municipality", "").trim();
        if (lugar.isEmpty()) lugar = ap.optString("name", "").trim();
        String codigo = ap.optString("iata_code", "").trim();
        if (codigo.isEmpty()) codigo = ap.optString("icao_code", "").trim();
        if (lugar.isEmpty()) return codigo;
        return codigo.isEmpty() ? lugar : lugar + " (" + codigo + ")";
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

        // Ahora cada alerta tiene su canal (con su sonido); el canal único de antes sobra
        nm.deleteNotificationChannel(CANAL_AVISOS);
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
