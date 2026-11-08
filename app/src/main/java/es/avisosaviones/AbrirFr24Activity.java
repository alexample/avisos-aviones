package es.avisosaviones;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Abre una aeronave en Flightradar24 (app si está instalada, si no la web).
 * Primero intenta localizar el vuelo EN DIRECTO para abrir el mapa con él marcado;
 * si no lo encuentra, abre la ficha de la matrícula (o el mapa en su posición).
 */
public class AbrirFr24Activity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_MATRICULA = "matricula";
    static final String EXTRA_INDICATIVO = "indicativo";

    private static final String[] PAQUETES_FR24 = {
            "com.flightradar24free",
            "com.flightradar24pro",
    };

    private static final String UA_NAVEGADOR =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String respaldo = getIntent().getStringExtra(EXTRA_URL);
        final String matricula = limpiar(getIntent().getStringExtra(EXTRA_MATRICULA));
        final String indicativo = limpiar(getIntent().getStringExtra(EXTRA_INDICATIVO));

        if (matricula.isEmpty() && indicativo.isEmpty()) {
            if (respaldo != null) abrir(this, respaldo);
            finish();
            return;
        }

        Toast.makeText(this, "Buscando el vuelo en Flightradar24…", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String enDirecto = buscarVueloEnDirecto(indicativo, matricula);
            ui.post(() -> {
                String url = enDirecto != null ? enDirecto : respaldo;
                if (url != null) abrir(app, url);
                finish();
            });
        }).start();
    }

    private static String limpiar(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
    }

    /** Devuelve https://www.flightradar24.com/INDICATIVO/idvuelo o null. */
    static String buscarVueloEnDirecto(String indicativo, String matricula) {
        for (String consulta : new String[]{indicativo, matricula}) {
            if (consulta == null || consulta.isEmpty()) continue;
            try {
                String url = "https://www.flightradar24.com/v1/search/web/find?limit=10&query="
                        + URLEncoder.encode(consulta, "UTF-8");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5_000);
                c.setReadTimeout(5_000);
                c.setRequestProperty("User-Agent", UA_NAVEGADOR);
                c.setRequestProperty("Accept", "application/json");
                if (c.getResponseCode() != 200) continue;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String linea;
                    while ((linea = r.readLine()) != null) sb.append(linea);
                }
                JSONArray res = new JSONObject(sb.toString()).optJSONArray("results");
                if (res == null) continue;

                JSONObject mejor = null;
                for (int i = 0; i < res.length(); i++) {
                    JSONObject o = res.optJSONObject(i);
                    if (o == null || !"live".equals(o.optString("type"))) continue;
                    JSONObject d = o.optJSONObject("detail");
                    String reg = d == null ? "" : limpiar(d.optString("reg"));
                    String cs = d == null ? "" : limpiar(d.optString("callsign"));
                    boolean coincide = (!matricula.isEmpty() && matricula.equals(reg))
                            || (!indicativo.isEmpty() && indicativo.equals(cs));
                    if (coincide) { mejor = o; break; }
                    if (mejor == null) mejor = o;
                }
                if (mejor == null) continue;
                String id = mejor.optString("id");
                if (id.isEmpty()) continue;
                JSONObject d = mejor.optJSONObject("detail");
                String cs = d == null ? "" : d.optString("callsign", "").trim();
                if (cs.isEmpty()) cs = !indicativo.isEmpty() ? indicativo : matricula;
                return "https://www.flightradar24.com/" + cs + "/" + id;
            } catch (Exception ignored) {}
        }
        return null;
    }

    static Intent intentPara(Context c, String url, String matricula, String indicativo) {
        return new Intent(c, AbrirFr24Activity.class)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_MATRICULA, matricula)
                .putExtra(EXTRA_INDICATIVO, indicativo)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
    }

    static void abrir(Context c, String url) {
        Uri uri = Uri.parse(url);

        // 1) La app de FR24 con el enlace
        for (String paquete : PAQUETES_FR24) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                        .setPackage(paquete)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }

        // 2) La app instalada pero sin aceptar enlaces: al menos abrirla
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
