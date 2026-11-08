package es.avisosaviones;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * Sonidos de aviso. En Android 8+ el sonido va en el canal de notificación y no se puede cambiar
 * después, así que cada alerta tiene su canal y, si cambia el sonido, se crea uno nuevo.
 */
final class Sonidos {

    static final String POR_DEFECTO = "";        // sonido de notificación del móvil
    static final String SILENCIO = "silencio";

    /** Sonidos incluidos en la app: {id del recurso, nombre}. */
    static final String[][] PROPIOS = {
            {"sirena", "🚨 Sirena"},
            {"rotor", "🚁 Rotor de helicóptero"},
            {"reactor", "✈️ Reactor"},
            {"radar", "📡 Radar"},
            {"pitido", "🔔 Doble pitido"},
            {"campana", "🎵 Campanita"},
    };

    private static Ringtone sonando;

    private Sonidos() {}

    static Uri uriPropio(Context c, String id) {
        return Uri.parse("android.resource://" + c.getPackageName() + "/raw/" + id);
    }

    /** Uri a sonar, o null si es silencio. */
    static Uri uri(Context c, String sonido) {
        if (SILENCIO.equals(sonido)) return null;
        if (sonido == null || sonido.isEmpty()) return Settings.System.DEFAULT_NOTIFICATION_URI;
        if (sonido.startsWith("raw:")) return uriPropio(c, sonido.substring(4));
        return Uri.parse(sonido);
    }

    static String nombre(Context c, String sonido) {
        if (SILENCIO.equals(sonido)) return "🔕 Sin sonido";
        if (sonido == null || sonido.isEmpty()) return "📱 El del móvil";
        if (sonido.startsWith("raw:")) {
            String id = sonido.substring(4);
            for (String[] p : PROPIOS) if (p[0].equals(id)) return p[1];
        }
        try {
            Ringtone r = RingtoneManager.getRingtone(c, Uri.parse(sonido));
            if (r != null) return "🎶 " + r.getTitle(c);
        } catch (Exception ignored) {}
        return "🎶 Sonido elegido";
    }

    static void probar(Context c, String sonido) {
        parar();
        Uri u = uri(c, sonido);
        if (u == null) return;
        try {
            sonando = RingtoneManager.getRingtone(c, u);
            if (sonando != null) sonando.play();
        } catch (Exception ignored) {}
    }

    static void parar() {
        try { if (sonando != null) sonando.stop(); } catch (Exception ignored) {}
        sonando = null;
    }

    /** Canal de notificación de una alerta con su sonido y vibración (lo crea si hace falta). */
    static String canal(Context c, Alerta a) {
        String prefijo = "av_" + a.id.replace("-", "").substring(0, 10) + "_";
        String id = prefijo + Integer.toHexString((a.sonido + "|" + a.vibrar).hashCode());
        NotificationManager nm = c.getSystemService(NotificationManager.class);

        // Borrar canales viejos de esta alerta (de cuando tenía otro sonido)
        for (NotificationChannel ch : nm.getNotificationChannels()) {
            if (ch.getId().startsWith(prefijo) && !ch.getId().equals(id)) nm.deleteNotificationChannel(ch.getId());
        }
        NotificationChannel existente = nm.getNotificationChannel(id);
        if (existente != null) {
            if (!existente.getName().toString().equals("🔔 " + a.nombre)) {
                existente.setName("🔔 " + a.nombre);
                nm.createNotificationChannel(existente);
            }
            return id;
        }
        NotificationChannel ch = new NotificationChannel(id, "🔔 " + a.nombre, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Avisos de la alerta «" + a.nombre + "»");
        Uri u = uri(c, a.sonido);
        if (u == null) ch.setSound(null, null);
        else ch.setSound(u, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ch.enableVibration(a.vibrar);
        if (a.vibrar) ch.setVibrationPattern(new long[]{0, 250, 120, 250});
        nm.createNotificationChannel(ch);
        return id;
    }

    /** Al borrar una alerta, quitar también su canal. */
    static void borrarCanales(Context c, String alertaId) {
        String prefijo = "av_" + alertaId.replace("-", "").substring(0, 10) + "_";
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (NotificationChannel ch : nm.getNotificationChannels()) {
            if (ch.getId().startsWith(prefijo)) nm.deleteNotificationChannel(ch.getId());
        }
    }
}
