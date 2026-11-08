package es.avisosaviones;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Abre una aeronave en la app de Flightradar24 si está instalada; si no, en la web.
 * No tiene interfaz: se abre desde la notificación o el historial, lanza FR24 y se cierra.
 */
public class AbrirFr24Activity extends Activity {

    static final String EXTRA_URL = "url";

    /** Paquetes conocidos de la app de Flightradar24. */
    private static final String[] PAQUETES_FR24 = {
            "com.flightradar24free",
            "com.flightradar24pro",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url != null) abrir(this, url);
        finish();
    }

    static Intent intentPara(Context c, String url) {
        return new Intent(c, AbrirFr24Activity.class)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
    }

    static void abrir(Context c, String url) {
        Uri uri = Uri.parse(url);

        // 1) La app de FR24 con el enlace del vuelo
        for (String paquete : PAQUETES_FR24) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri)
                        .setPackage(paquete)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Exception ignored) {}
        }

        // 2) La app de FR24 instalada pero sin aceptar enlaces: al menos abrirla
        for (String paquete : PAQUETES_FR24) {
            Intent lanzar = c.getPackageManager().getLaunchIntentForPackage(paquete);
            if (lanzar != null) {
                try {
                    lanzar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(lanzar);
                    Toast.makeText(c, "Abriendo Flightradar24. Busca el vuelo en el mapa.", Toast.LENGTH_LONG).show();
                    return;
                } catch (Exception ignored) {}
            }
        }

        // 3) Sin app: la web
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(c, "No hay navegador para abrir Flightradar24", Toast.LENGTH_LONG).show();
        }
    }
}
