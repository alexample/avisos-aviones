package es.avisosaviones;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Una regla de aviso: "avísame de X cuando esté a menos de Y km". */
final class Alerta {

    static final String TODO = "todo", AVIONES = "aviones", HELICOPTEROS = "helicopteros",
            MILITARES = "militares", EMERGENCIAS = "emergencias";

    String id = UUID.randomUUID().toString();
    String nombre = "Nueva alerta";
    boolean activa = true;
    float radioKm = 5f;
    float distMinKm = 0f;          // 0 = sin mínimo
    String tipo = TODO;
    final List<String> origenes = new ArrayList<>();   // códigos IATA
    final List<String> destinos = new ArrayList<>();
    int altMaxM = 0;               // 0 = cualquier altitud
    String matriculas = "";        // texto libre con comodines
    String indicativos = "";

    // ------------------------------------------------------------ coincidencia

    /** Datos de una aeronave para comprobar las alertas. */
    static final class Aeronave {
        double distKm;
        boolean heli, militar, emergencia;
        int altM;
        String matricula = "", indicativo = "";
    }

    boolean necesitaRuta() {
        return !origenes.isEmpty() || !destinos.isEmpty();
    }

    /** Todo menos la ruta (que se consulta solo si hace falta). */
    boolean coincideSinRuta(Aeronave a) {
        if (!activa) return false;
        if (a.distKm > radioKm) return false;
        if (distMinKm > 0 && a.distKm < distMinKm) return false;
        if (altMaxM > 0 && a.altM > altMaxM) return false;
        switch (tipo) {
            case AVIONES: if (a.heli) return false; break;
            case HELICOPTEROS: if (!a.heli) return false; break;
            case MILITARES: if (!a.militar) return false; break;
            case EMERGENCIAS: if (!a.emergencia) return false; break;
            default: break;
        }
        if (!Ajustes.coincide(matriculas, a.matricula)) return false;
        return Ajustes.coincide(indicativos, a.indicativo);
    }

    boolean coincideRuta(String origen, String destino) {
        if (!origenes.isEmpty() && !contiene(origenes, origen)) return false;
        return destinos.isEmpty() || contiene(destinos, destino);
    }

    private static boolean contiene(List<String> lista, String codigo) {
        if (codigo == null || codigo.isEmpty()) return false;
        for (String c : lista) if (c.equalsIgnoreCase(codigo)) return true;
        return false;
    }

    // ------------------------------------------------------------ textos

    String icono() {
        switch (tipo) {
            case HELICOPTEROS: return "🚁";
            case MILITARES: return "🎖️";
            case EMERGENCIAS: return "🚨";
            default: return necesitaRuta() ? "🛫" : "✈️";
        }
    }

    static String nombreTipo(String t) {
        switch (t) {
            case AVIONES: return "Aviones";
            case HELICOPTEROS: return "Helicópteros";
            case MILITARES: return "Militares";
            case EMERGENCIAS: return "Emergencias";
            default: return "Todo";
        }
    }

    /** Frase corta para la tarjeta: "Helicópteros · hasta 10 km · MAD → LHR". */
    String resumen() {
        StringBuilder sb = new StringBuilder();
        sb.append(TODO.equals(tipo) ? "Cualquier aeronave" : nombreTipo(tipo));
        sb.append(" · ");
        if (distMinKm > 0) sb.append("de ").append(km(distMinKm)).append(" a ").append(km(radioKm));
        else sb.append("hasta ").append(km(radioKm));
        if (necesitaRuta()) {
            sb.append(" · ");
            sb.append(origenes.isEmpty() ? "cualquier sitio" : String.join(", ", origenes));
            sb.append(" → ");
            sb.append(destinos.isEmpty() ? "cualquier sitio" : String.join(", ", destinos));
        }
        if (altMaxM > 0) sb.append(" · bajo ").append(String.format(Locale.getDefault(), "%,d m", altMaxM));
        if (!matriculas.trim().isEmpty()) sb.append(" · ").append(matriculas.trim());
        if (!indicativos.trim().isEmpty()) sb.append(" · ").append(indicativos.trim());
        return sb.toString();
    }

    static String km(float v) {
        if (v < 1) return String.format(Locale.getDefault(), "%.0f m", v * 1000);
        if (v == (int) v) return (int) v + " km";
        return String.format(Locale.getDefault(), "%.1f km", v);
    }

    // ------------------------------------------------------------ JSON

    JSONObject aJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("nombre", nombre);
            o.put("activa", activa);
            o.put("radio_km", radioKm);
            o.put("dist_min_km", distMinKm);
            o.put("tipo", tipo);
            o.put("origenes", new JSONArray(origenes));
            o.put("destinos", new JSONArray(destinos));
            o.put("alt_max_m", altMaxM);
            o.put("matriculas", matriculas);
            o.put("indicativos", indicativos);
        } catch (Exception ignored) {}
        return o;
    }

    static Alerta deJson(JSONObject o) {
        Alerta a = new Alerta();
        a.id = o.optString("id", a.id);
        a.nombre = o.optString("nombre", a.nombre);
        a.activa = o.optBoolean("activa", true);
        a.radioKm = (float) o.optDouble("radio_km", 5);
        a.distMinKm = (float) o.optDouble("dist_min_km", 0);
        a.tipo = o.optString("tipo", TODO);
        JSONArray or = o.optJSONArray("origenes");
        if (or != null) for (int i = 0; i < or.length(); i++) a.origenes.add(or.optString(i));
        JSONArray de = o.optJSONArray("destinos");
        if (de != null) for (int i = 0; i < de.length(); i++) a.destinos.add(de.optString(i));
        a.altMaxM = o.optInt("alt_max_m", 0);
        a.matriculas = o.optString("matriculas", "");
        a.indicativos = o.optString("indicativos", "");
        return a;
    }
}
