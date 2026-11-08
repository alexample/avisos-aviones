package es.avisosaviones;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modelos de aeronave por código OACI (generado al compilar desde tar1090-db).
 * Línea: CODIGO|nombre|descripción OACI (L2J, H1T…)|estela (L, M, H, J)
 */
final class Tipos {

    static final class Tipo {
        final String codigo, nombre, desc, estela, clave;
        Tipo(String codigo, String nombre, String desc, String estela) {
            this.codigo = codigo; this.nombre = bonito(nombre); this.desc = desc; this.estela = estela;
            this.clave = Aeropuertos.normalizar(codigo + " " + nombre + " " + nombre.replace("-", ""));
        }
        String titulo() { return codigo + (nombre.isEmpty() ? "" : " · " + nombre); }
        String detalle() { return claseLegible(clasePorTipo(this)); }
    }

    // Clases de aeronave
    static final String GRANDE = "grande", LIGERO = "ligero", HELI = "heli",
            PLANEADOR = "planeador", GLOBO = "globo", DRON = "dron", DESCONOCIDA = "";

    static final String[] CLASES = {GRANDE, LIGERO, HELI, PLANEADOR, GLOBO, DRON};
    static final String[] CLASES_TXT = {"✈️ Aviones grandes", "🛩️ Avionetas", "🚁 Helicópteros",
            "🪂 Planeadores", "🎈 Globos", "🛸 Drones"};

    static String claseLegible(String c) {
        for (int i = 0; i < CLASES.length; i++) if (CLASES[i].equals(c)) return CLASES_TXT[i];
        return "";
    }

    static String iconoClase(String c) {
        String t = claseLegible(c);
        return t.isEmpty() ? "✈️" : t.substring(0, t.indexOf(' '));
    }

    private static Map<String, Tipo> porCodigo;
    private static List<Tipo> lista;

    static synchronized List<Tipo> todos(Context c) {
        if (lista != null) return lista;
        lista = new ArrayList<>();
        porCodigo = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("tipos.txt"), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = r.readLine()) != null) {
                String[] p = linea.split("\\|", -1);
                if (p.length < 4 || p[0].isEmpty()) continue;
                Tipo t = new Tipo(p[0], p[1], p[2], p[3]);
                lista.add(t);
                porCodigo.put(t.codigo, t);
            }
        } catch (Exception ignored) {}
        return lista;
    }

    static Tipo buscarCodigo(Context c, String codigo) {
        todos(c);
        return codigo == null ? null : porCodigo.get(codigo.trim().toUpperCase(Locale.ROOT));
    }

    static String etiqueta(Context c, String codigo) {
        Tipo t = buscarCodigo(c, codigo);
        return t != null ? t.titulo() : codigo;
    }

    static List<Tipo> buscar(Context c, String texto, int max) {
        String q = Aeropuertos.normalizar(texto);
        List<Tipo> exactos = new ArrayList<>(), empiezan = new ArrayList<>(), contienen = new ArrayList<>();
        if (q.isEmpty()) return exactos;
        for (Tipo t : todos(c)) {
            if (t.codigo.equalsIgnoreCase(q)) exactos.add(t);
            else if (t.clave.startsWith(q) || t.clave.contains(" " + q)) empiezan.add(t);
            else if (q.length() >= 2 && t.clave.contains(q)) contienen.add(t);
        }
        exactos.addAll(empiezan);
        exactos.addAll(contienen);
        return exactos.size() > max ? new ArrayList<>(exactos.subList(0, max)) : exactos;
    }

    /** Clase a partir del modelo (descripción OACI y estela). */
    static String clasePorTipo(Tipo t) {
        if (t == null || t.desc.isEmpty()) return DESCONOCIDA;
        if ("GLID".equals(t.codigo)) return PLANEADOR;
        char k = t.desc.charAt(0);
        if (k == 'H' || k == 'G') return HELI;
        if (k == 'B') return GLOBO; // B = globo / dirigible
        if (t.desc.startsWith("L0") || t.desc.startsWith("S0")) return PLANEADOR;
        if ("L".equals(t.estela)) return LIGERO;
        if ("M".equals(t.estela) || "H".equals(t.estela) || "J".equals(t.estela)) return GRANDE;
        return DESCONOCIDA;
    }

    /** Clase de una aeronave: primero su categoría ADS-B, si no, su modelo. */
    static String clase(Context c, String categoria, String codigoTipo) {
        String cat = categoria == null ? "" : categoria.toUpperCase(Locale.ROOT);
        switch (cat) {
            case "A7": return HELI;
            case "B1": return PLANEADOR;
            case "B2": return GLOBO;
            case "B6": return DRON;
            case "B4": return LIGERO;
            default: break;
        }
        String porTipo = clasePorTipo(buscarCodigo(c, codigoTipo));
        if (!porTipo.isEmpty()) return porTipo;
        switch (cat) {
            case "A1": case "A2": return LIGERO;
            case "A3": case "A4": case "A5": case "A6": return GRANDE;
            default: return DESCONOCIDA;
        }
    }

    /** "AIRBUS A-320neo" → "Airbus A-320neo". */
    private static String bonito(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : s.trim().split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            boolean soloLetras = w.matches("[A-Z]{3,}");
            sb.append(soloLetras ? w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT) : w);
        }
        return sb.toString();
    }
}
