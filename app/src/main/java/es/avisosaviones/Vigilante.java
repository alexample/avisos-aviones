package es.avisosaviones;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ApplicationExitInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Vigilante del servicio: cada 15 min (y al reiniciar el móvil o actualizar la app) comprueba
 * que la vigilancia sigue viva y, si Android la ha cerrado, la vuelve a arrancar.
 */
public class Vigilante extends BroadcastReceiver {

    static final String ACCION_REVISAR = "es.avisosaviones.REVISAR";
    private static final String CANAL_PARADA = "parada";
    private static final int ID_PARADA = 2;
    static final String LATIDO = "latido";
    static final String MOTIVO_PARADA = "motivo_parada";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Ajustes.activo(c)) return;
        revisar(c, "vigilante");
        programar(c, 15 * 60 * 1000L);
    }

    /** Programa la próxima revisión (funciona aunque el móvil esté en reposo). */
    static void programar(Context c, long dentroDeMs) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0,
                new Intent(c, Vigilante.class).setAction(ACCION_REVISAR),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + dentroDeMs, pi);
    }

    static void cancelar(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0,
                new Intent(c, Vigilante.class).setAction(ACCION_REVISAR),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    /** ¿Ha dado señales de vida la vigilancia hace poco? */
    static boolean vivo(Context c) {
        long latido = Ajustes.prefs(c).getLong(LATIDO, 0);
        long margen = Math.max(180_000L, Math.max(Ajustes.intervaloS(c), Ajustes.esperaMaxS(c)) * 2500L);
        return System.currentTimeMillis() - latido < margen;
    }

    /** Arranca (o despierta) el servicio; si Android no deja, avisa al usuario para que toque y lo reanude. */
    static void revisar(Context c, String quien) {
        try {
            c.startForegroundService(new Intent(c, VigilanciaService.class).putExtra("desde", quien));
            c.getSystemService(NotificationManager.class).cancel(ID_PARADA);
        } catch (Exception e) {
            apuntarMotivo(c, "Android no dejó reanudarla en segundo plano (" + e.getClass().getSimpleName() + ")");
            avisarParada(c);
        }
    }

    static void apuntarMotivo(Context c, String motivo) {
        String hora = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date());
        Ajustes.prefs(c).edit().putString(MOTIVO_PARADA, hora + " · " + motivo).apply();
    }

    /** Notificación "se ha parado, toca para reanudar". */
    static void avisarParada(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CANAL_PARADA, "Vigilancia detenida", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(c, 3,
                new Intent(c, MainActivity.class).putExtra("reanudar", true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CANAL_PARADA)
                .setSmallIcon(R.drawable.ic_avion)
                .setContentTitle("La vigilancia se ha detenido")
                .setContentText("Android la ha cerrado. Toca para reanudarla.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(ID_PARADA, n);
    }

    /** Motivo con el que Android cerró la app la última vez (Android 11+). */
    static String ultimoCierre(Context c) {
        if (Build.VERSION.SDK_INT < 30) return "";
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 1);
            if (l.isEmpty()) return "";
            ApplicationExitInfo e = l.get(0);
            String hora = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(e.getTimestamp()));
            String motivo;
            switch (e.getReason()) {
                case ApplicationExitInfo.REASON_LOW_MEMORY: motivo = "el móvil se quedó sin memoria"; break;
                case ApplicationExitInfo.REASON_CRASH:
                case ApplicationExitInfo.REASON_CRASH_NATIVE: motivo = "fallo de la app"; break;
                case ApplicationExitInfo.REASON_ANR: motivo = "la app se quedó bloqueada"; break;
                case ApplicationExitInfo.REASON_USER_REQUESTED: motivo = "cerrada a mano (forzar detención o deslizar)"; break;
                case ApplicationExitInfo.REASON_USER_STOPPED: motivo = "detenida por el usuario"; break;
                case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: motivo = "Android vio demasiado consumo"; break;
                case ApplicationExitInfo.REASON_SIGNALED: motivo = "cerrada por el sistema (ahorro de batería del fabricante)"; break;
                case ApplicationExitInfo.REASON_FREEZER: motivo = "congelada por el sistema"; break;
                case ApplicationExitInfo.REASON_PERMISSION_CHANGE: motivo = "cambio de permisos"; break;
                case ApplicationExitInfo.REASON_DEPENDENCY_DIED: motivo = "dependencia del sistema"; break;
                case ApplicationExitInfo.REASON_EXIT_SELF: motivo = "se cerró ella misma"; break;
                default: motivo = "otro motivo (" + e.getReason() + ")";
            }
            String desc = e.getDescription();
            return hora + " · " + motivo + (desc != null && !desc.isEmpty() && e.getReason() == ApplicationExitInfo.REASON_CRASH
                    ? " (" + (desc.length() > 80 ? desc.substring(0, 80) + "…" : desc) + ")" : "");
        } catch (Exception ex) {
            return "";
        }
    }
}
