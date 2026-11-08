package es.avisosaviones;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Historial de aeronaves avisadas, guardado como una línea JSON por paso. */
final class Registro {
    private static final String ARCHIVO = "historial.jsonl";
    private static final int MAXIMO = 1000;

    private Registro() {}

    private static File archivo(Context c) {
        return new File(c.getFilesDir(), ARCHIVO);
    }

    static synchronized void guardar(Context c, JSONObject entrada) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(archivo(c), true), StandardCharsets.UTF_8)) {
            w.write(entrada.toString());
            w.write('\n');
        } catch (Exception ignored) {}
        recortarSiHaceFalta(c);
    }

    /** Más recientes primero. */
    static synchronized List<JSONObject> leer(Context c) {
        List<JSONObject> lista = new ArrayList<>();
        File f = archivo(c);
        if (!f.exists()) return lista;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = r.readLine()) != null) {
                if (linea.isEmpty()) continue;
                try { lista.add(new JSONObject(linea)); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        Collections.reverse(lista);
        return lista;
    }

    static synchronized void borrar(Context c) {
        //noinspection ResultOfMethodCallIgnored
        archivo(c).delete();
    }

    private static void recortarSiHaceFalta(Context c) {
        List<JSONObject> todas = leer(c); // recientes primero
        if (todas.size() <= MAXIMO * 1.2) return;
        List<JSONObject> quedan = new ArrayList<>(todas.subList(0, MAXIMO));
        Collections.reverse(quedan);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(archivo(c), false), StandardCharsets.UTF_8)) {
            for (JSONObject o : quedan) { w.write(o.toString()); w.write('\n'); }
        } catch (Exception ignored) {}
    }
}
