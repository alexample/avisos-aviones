package es.avisosaviones;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lista de aeropuertos con código IATA (generada en la compilación desde OurAirports).
 * Formato de cada línea: IATA|ICAO|ciudad|nombre|país
 */
final class Aeropuertos {

    static final class Aeropuerto {
        final String iata, icao, ciudad, nombre, pais;
        final String clave; // texto normalizado para buscar
        final boolean grande;

        Aeropuerto(String iata, String icao, String ciudad, String nombre, String pais, boolean grande) {
            this.iata = iata; this.icao = icao; this.ciudad = ciudad; this.nombre = nombre; this.pais = pais;
            this.grande = grande;
            String alias = ALIAS.getOrDefault(normalizar(ciudad), "");
            this.clave = normalizar(iata + " " + icao + " " + ciudad + " " + nombre + " " + alias);
        }

        String titulo() {
            return (ciudad.isEmpty() ? nombre : ciudad) + " (" + iata + ")";
        }

        String detalle() {
            return nombre + (pais.isEmpty() ? "" : " · " + pais);
        }
    }

    /** Nombres en español de ciudades que en la lista vienen en inglés. */
    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        String[][] a = {
                {"london", "londres"}, {"new york", "nueva york"}, {"munich", "munich"}, {"cologne", "colonia"},
                {"geneva", "ginebra"}, {"brussels", "bruselas"}, {"lisbon", "lisboa"}, {"rome", "roma"},
                {"milan", "milan"}, {"venice", "venecia"}, {"athens", "atenas"}, {"istanbul", "estambul"},
                {"moscow", "moscu"}, {"warsaw", "varsovia"}, {"prague", "praga"}, {"vienna", "viena"},
                {"copenhagen", "copenhague"}, {"stockholm", "estocolmo"}, {"frankfurt am main", "francfort"},
                {"frankfurt", "francfort"}, {"edinburgh", "edimburgo"}, {"marrakesh", "marrakech"},
                {"cairo", "el cairo"}, {"tokyo", "tokio"}, {"beijing", "pekin"}, {"naples", "napoles"},
                {"florence", "florencia"}, {"turin", "turin"}, {"seville", "sevilla"}, {"zurich", "zurich"},
                {"basel", "basilea"}, {"hamburg", "hamburgo"}, {"nuremberg", "nuremberg"}, {"oslo", "oslo"},
                {"helsinki", "helsinki"}, {"dublin", "dublin"}, {"bucharest", "bucarest"}, {"budapest", "budapest"},
                {"tangier", "tanger"}, {"casablanca", "casablanca"}, {"algiers", "argel"}, {"tunis", "tunez"},
                {"palma de mallorca", "mallorca"}, {"ibiza", "eivissa"}, {"santa cruz de tenerife", "tenerife"},
                {"las palmas de gran canaria", "gran canaria"}, {"saint petersburg", "san petersburgo"},
                {"mexico city", "ciudad de mexico"}, {"havana", "la habana"}, {"sao paulo", "san pablo"},
        };
        for (String[] p : a) ALIAS.put(p[0], p[1]);
    }

    private static List<Aeropuerto> lista;
    private static Map<String, Aeropuerto> porIata;

    static synchronized List<Aeropuerto> todos(Context c) {
        if (lista != null) return lista;
        lista = new ArrayList<>();
        porIata = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("aeropuertos.txt"), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = r.readLine()) != null) {
                String[] p = linea.split("\\|", -1);
                if (p.length < 5 || p[0].length() != 3) continue;
                boolean grande = p.length > 5 && "L".equals(p[5]);
                Aeropuerto a = new Aeropuerto(p[0], p[1], p[2], p[3], p[4], grande);
                lista.add(a);
                porIata.put(a.iata, a);
            }
        } catch (Exception ignored) {}
        return lista;
    }

    static Aeropuerto porCodigo(Context c, String iata) {
        todos(c);
        return porIata.get(iata == null ? "" : iata.toUpperCase(Locale.ROOT));
    }

    /** "Madrid (MAD)" si se conoce; si no, el código tal cual. */
    static String etiqueta(Context c, String iata) {
        Aeropuerto a = porCodigo(c, iata);
        return a != null ? a.titulo() : iata;
    }

    /** Búsqueda por código, ciudad o nombre, sin importar tildes ni mayúsculas. */
    static List<Aeropuerto> buscar(Context c, String texto, int max) {
        String q = normalizar(texto);
        List<Aeropuerto> res = new ArrayList<>();
        if (q.isEmpty()) return res;
        List<Aeropuerto> exactos = new ArrayList<>(), empiezan = new ArrayList<>(), contienen = new ArrayList<>();
        for (Aeropuerto a : todos(c)) {
            if (a.iata.equalsIgnoreCase(q) || a.icao.equalsIgnoreCase(q)) exactos.add(a);
            else if (empiezaPalabra(a.clave, q)) empiezan.add(a);
            else if (q.length() >= 3 && a.clave.contains(q)) contienen.add(a);
        }
        // Los aeropuertos grandes primero
        java.util.Comparator<Aeropuerto> orden = (x, y) -> Boolean.compare(y.grande, x.grande);
        Collections.sort(empiezan, orden);
        Collections.sort(contienen, orden);
        res.addAll(exactos);
        res.addAll(empiezan);
        res.addAll(contienen);
        return res.size() > max ? new ArrayList<>(res.subList(0, max)) : res;
    }

    private static boolean empiezaPalabra(String clave, String q) {
        if (clave.startsWith(q)) return true;
        return clave.contains(" " + q);
    }

    static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
