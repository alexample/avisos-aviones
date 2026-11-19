package es.pegasusdgt;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Detecta cuándo subes y bajas del coche (reconocimiento de actividad de Google)
 * y arranca o para la vigilancia. También se vuelve a apuntar al reiniciar el móvil.
 */
public class Movimiento extends BroadcastReceiver {

    static final String ACCION = "es.pegasusdgt.TRANSICION";

    static boolean tienePermiso(Context c) {
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent pi(Context c) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, 0, new Intent(c, Movimiento.class).setAction(ACCION), flags);
    }

    /** Apuntarse a "sube al coche" / "baja del coche". */
    static void registrar(Context c) {
        if (!tienePermiso(c)) { estado(c, "falta el permiso de actividad física"); return; }
        List<ActivityTransition> t = new ArrayList<>();
        t.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
        t.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build());
        try {
            ActivityRecognition.getClient(c)
                    .requestActivityTransitionUpdates(new ActivityTransitionRequest(t), pi(c))
                    .addOnSuccessListener(v -> estado(c, "activa"))
                    .addOnFailureListener(e -> estado(c, "no disponible (" + e.getMessage() + ")"));
        } catch (SecurityException e) {
            estado(c, "falta el permiso de actividad física");
        } catch (Exception e) {
            estado(c, "no disponible en este móvil");
        }
    }

    static void quitar(Context c) {
        try { ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pi(c)); } catch (Exception ignored) {}
        estado(c, "desactivada");
    }

    private static void estado(Context c, String s) {
        Config.p(c).edit().putString("estado_movimiento", s).apply();
    }

    static String estado(Context c) {
        return Config.p(c).getString("estado_movimiento", "sin configurar");
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String accion = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(accion) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(accion)) {
            if (Config.auto(c)) registrar(c);
            return;
        }
        if (!ActivityTransitionResult.hasResult(intent)) return;
        ActivityTransitionResult r = ActivityTransitionResult.extractResult(intent);
        if (r == null) return;
        for (ActivityTransitionEvent e : r.getTransitionEvents()) {
            if (e.getActivityType() != DetectedActivity.IN_VEHICLE) continue;
            String hora = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            if (e.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                Config.p(c).edit().putString("ultimo_movimiento", "🚗 En coche desde las " + hora).apply();
                if (Config.auto(c)) PegasusService.arrancar(c, false);
            } else {
                Config.p(c).edit().putString("ultimo_movimiento", "🚶 Fuera del coche desde las " + hora).apply();
                PegasusService.finConduccion(c);
            }
        }
    }
}
