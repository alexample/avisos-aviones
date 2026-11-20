package es.avisosaviones;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Registro de actividad para saber qué hace la vigilancia (se ve en ⚙️ → Registro de actividad). */
final class Depuracion {
    private static final String ARCHIVO = "registro.log";
    private static final long MAX = 400_000L;      // al pasar de esto se recorta
    private static final long DEJAR = 200_000L;    // y se dejan las últimas líneas
    private static final SimpleDateFormat F = new SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.getDefault());

    private Depuracion() {}

    static File archivo(Context c) { return new File(c.getFilesDir(), ARCHIVO); }

    static synchronized void log(Context c, String etiqueta, String msg) {
        Log.d("AvisosAviones", etiqueta + ": " + msg);
        try {
            File f = archivo(c);
            String linea = F.format(new Date()) + " [" + etiqueta + "] " + msg + "\n";
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write(linea.getBytes(StandardCharsets.UTF_8));
            }
            if (f.length() > MAX) recortar(f);
        } catch (Exception ignored) {}
    }

    static void error(Context c, String etiqueta, String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String pila = sw.toString();
        if (pila.length() > 1500) pila = pila.substring(0, 1500) + "…";
        log(c, etiqueta, "❌ " + msg + ": " + t + "\n" + pila);
    }

    static synchronized String leer(Context c, int maxCaracteres) {
        File f = archivo(c);
        if (!f.exists()) return "";
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long desde = Math.max(0, r.length() - maxCaracteres);
            r.seek(desde);
            byte[] b = new byte[(int) (r.length() - desde)];
            r.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            int nl = desde > 0 ? s.indexOf('\n') : -1;
            return nl >= 0 ? s.substring(nl + 1) : s;
        } catch (Exception e) {
            return "No se pudo leer el registro: " + e;
        }
    }

    static synchronized void borrar(Context c) {
        //noinspection ResultOfMethodCallIgnored
        archivo(c).delete();
    }

    private static void recortar(File f) throws Exception {
        byte[] b;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long desde = r.length() - DEJAR;
            r.seek(desde);
            b = new byte[(int) DEJAR];
            r.readFully(b);
        }
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(b);
        }
    }
}
