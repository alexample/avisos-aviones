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
    float radioKm = 5f;            // 0 o menos = sin límite
    float distMinKm = 0f;          // 0 = sin mínimo
    String tipo = TODO;            // obsoleto: solo para convertir alertas antiguas
    final List<String> clases = new ArrayList<>();     // Tipos.GRANDE, HELI…; vacía = todas
    boolean soloMilitar = false;
    boolean soloEmergencia = false;
    final List<String> modelos = new ArrayList<>();    // códigos OACI (A20N, B738, EC35…), admite *
    final List<String> servicios = new ArrayList<>();  // etiquetas del usuario (Servicios.IDS)
    final List<String> origenes = new ArrayList<>();   // códigos IATA
    final List<String> destinos = new ArrayList<>();
    int altMaxM = 0;               // 0 = cualquier altitud
    String matriculas = "";        // texto libre con comodines
    String indicativos = "";
    String vuelos = "";            // número de vuelo comercial (IB3456)
    String squawks = "";
    final List<String> excluirOrigenes = new ArrayList<>();
    final List<String> excluirDestinos = new ArrayList<>();

    boolean sinLimite() { return radioKm <= 0; }

    // ------------------------------------------------------------ coincidencia

    /** Datos de una aeronave para comprobar las alertas. */
    static final class Aeronave {
        double distKm;
        boolean heli, militar, emergencia;
        int altM;
        String matricula = "", indicativo = "", squawk = "", clase = "", tipoCodigo = "", servicio = "";
    }

    boolean necesitaRuta() {
        return !origenes.isEmpty() || !destinos.isEmpty()
                || !excluirOrigenes.isEmpty() || !excluirDestinos.isEmpty() || !vuelos.trim().isEmpty();
    }

    /**
     * Para "sin límite" no se puede consultar el mundo entero: se piden a la API solo
     * los aparatos que encajan (squawk, militares, matrícula o indicativo concretos).
     * Devuelve rutas de la API tipo "squawk/7700"; vacía si la alerta no se puede buscar así.
     */
    List<String> consultasGlobales() {
        List<String> q = new ArrayList<>();
        if (!sinLimite()) return q;
        if (soloEmergencia) { q.add("squawk/7700"); q.add("squawk/7600"); q.add("squawk/7500"); }
        if (soloMilitar) q.add("mil");
        for (String m : modelos) if (m.matches("[A-Z0-9]{2,4}")) q.add("type/" + m);
        for (String t : terminosExactos(squawks)) if (t.matches("[0-7]{4}")) q.add("squawk/" + t);
        for (String t : terminosExactos(matriculas)) q.add("reg/" + t);
        for (String t : terminosExactos(indicativos)) q.add("callsign/" + t);
        return q;
    }

    /** Términos sin comodines ni exclusiones. */
    private static List<String> terminosExactos(String filtro) {
        List<String> l = new ArrayList<>();
        if (filtro == null) return l;
        for (String t : filtro.toUpperCase(Locale.ROOT).split("[,;\\s]+")) {
            if (t.isEmpty() || t.startsWith("!") || t.contains("*") || t.contains("?")) continue;
            l.add(t);
        }
        return l;
    }

    /** Todo menos la ruta (que se consulta solo si hace falta). */
    boolean coincideSinRuta(Aeronave a) {
        if (!activa) return false;
        if (!sinLimite() && a.distKm > radioKm) return false;
        if (distMinKm > 0 && a.distKm < distMinKm) return false;
        if (altMaxM > 0 && a.altM > altMaxM) return false;
        if (!clases.isEmpty() && !clases.contains(a.clase)) return false;
        if (soloMilitar && !a.militar) return false;
        if (soloEmergencia && !a.emergencia) return false;
        if (!modelos.isEmpty() && !Ajustes.coincide(String.join(",", modelos), a.tipoCodigo)) return false;
        if (!servicios.isEmpty() && !servicios.contains(a.servicio)) return false;
        if (!Ajustes.coincide(matriculas, a.matricula)) return false;
        if (!Ajustes.coincide(squawks, a.squawk)) return false;
        return Ajustes.coincide(indicativos, a.indicativo);
    }

    /** Ruta, exclusiones y número de vuelo (datos de FR24 o adsbdb; vacío = desconocido). */
    boolean coincideRuta(String origen, String destino, String vuelo) {
        if (!origenes.isEmpty() && !contiene(origenes, origen)) return false;
        if (!destinos.isEmpty() && !contiene(destinos, destino)) return false;
        if (contiene(excluirOrigenes, origen)) return false;
        if (contiene(excluirDestinos, destino)) return false;
        if (!vuelos.trim().isEmpty() && (vuelo == null || vuelo.isEmpty() || !Ajustes.coincide(vuelos, vuelo))) return false;
        return true;
    }

    private static boolean contiene(List<String> lista, String codigo) {
        if (codigo == null || codigo.isEmpty()) return false;
        for (String c : lista) if (c.equalsIgnoreCase(codigo)) return true;
        return false;
    }

    // ------------------------------------------------------------ textos

    String icono() {
        if (soloEmergencia) return "🚨";
        if (soloMilitar) return "🎖️";
        if (servicios.size() == 1) return Servicios.icono(servicios.get(0));
        if (clases.size() == 1) return Tipos.iconoClase(clases.get(0));
        if (!modelos.isEmpty()) return "🔧";
        return !origenes.isEmpty() || !destinos.isEmpty() ? "🛫" : "✈️";
    }

    /** "Helicópteros y avionetas", "Cualquier aeronave"… */
    String textoQue() {
        StringBuilder sb = new StringBuilder();
        if (clases.isEmpty()) sb.append("Cualquier aeronave");
        else {
            for (int i = 0; i < clases.size(); i++) {
                String t = Tipos.claseLegible(clases.get(i));
                t = t.substring(t.indexOf(' ') + 1);
                if (i > 0) sb.append(i == clases.size() - 1 ? " y " : ", ");
                sb.append(i == 0 ? t : t.toLowerCase(Locale.getDefault()));
            }
        }
        if (soloMilitar) sb.append(" militar");
        if (!servicios.isEmpty()) {
            sb.append(" de ");
            for (int i = 0; i < servicios.size(); i++) {
                String t = Servicios.legible(servicios.get(i));
                if (i > 0) sb.append(i == servicios.size() - 1 ? " o " : ", ");
                sb.append(t.substring(t.indexOf(' ') + 1));
            }
        }
        if (soloEmergencia) sb.append(" en emergencia");
        return sb.toString();
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
        sb.append(textoQue());
        if (!modelos.isEmpty()) sb.append(" · ").append(String.join(", ", modelos));
        sb.append(" · ");
        if (sinLimite()) sb.append(distMinKm > 0 ? "a más de " + km(distMinKm) : "sin límite de distancia");
        else if (distMinKm > 0) sb.append("de ").append(km(distMinKm)).append(" a ").append(km(radioKm));
        else sb.append("hasta ").append(km(radioKm));
        if (!origenes.isEmpty() || !destinos.isEmpty()) {
            sb.append(" · ");
            sb.append(origenes.isEmpty() ? "cualquier sitio" : String.join(", ", origenes));
            sb.append(" → ");
            sb.append(destinos.isEmpty() ? "cualquier sitio" : String.join(", ", destinos));
        }
        if (altMaxM > 0) sb.append(" · bajo ").append(String.format(Locale.getDefault(), "%,d m", altMaxM));
        if (!matriculas.trim().isEmpty()) sb.append(" · ").append(matriculas.trim());
        if (!indicativos.trim().isEmpty()) sb.append(" · ").append(indicativos.trim());
        if (!vuelos.trim().isEmpty()) sb.append(" · vuelo ").append(vuelos.trim());
        if (!squawks.trim().isEmpty()) sb.append(" · squawk ").append(squawks.trim());
        if (!excluirOrigenes.isEmpty()) sb.append(" · sin salidas de ").append(String.join(", ", excluirOrigenes));
        if (!excluirDestinos.isEmpty()) sb.append(" · sin llegadas a ").append(String.join(", ", excluirDestinos));
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
            o.put("clases", new JSONArray(clases));
            o.put("solo_militar", soloMilitar);
            o.put("solo_emergencia", soloEmergencia);
            o.put("modelos", new JSONArray(modelos));
            o.put("servicios", new JSONArray(servicios));
            o.put("origenes", new JSONArray(origenes));
            o.put("destinos", new JSONArray(destinos));
            o.put("alt_max_m", altMaxM);
            o.put("matriculas", matriculas);
            o.put("indicativos", indicativos);
            o.put("vuelos", vuelos);
            o.put("squawks", squawks);
            o.put("excluir_origenes", new JSONArray(excluirOrigenes));
            o.put("excluir_destinos", new JSONArray(excluirDestinos));
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
        JSONArray cl = o.optJSONArray("clases");
        if (cl != null) {
            for (int i = 0; i < cl.length(); i++) a.clases.add(cl.optString(i));
            a.soloMilitar = o.optBoolean("solo_militar", false);
            a.soloEmergencia = o.optBoolean("solo_emergencia", false);
        } else {
            // Alerta de la versión anterior: convertir el "tipo" único
            switch (a.tipo) {
                case AVIONES: a.clases.add(Tipos.GRANDE); a.clases.add(Tipos.LIGERO); break;
                case HELICOPTEROS: a.clases.add(Tipos.HELI); break;
                case MILITARES: a.soloMilitar = true; break;
                case EMERGENCIAS: a.soloEmergencia = true; break;
                default: break;
            }
        }
        JSONArray se = o.optJSONArray("servicios");
        if (se != null) for (int i = 0; i < se.length(); i++) a.servicios.add(se.optString(i));
        JSONArray mo = o.optJSONArray("modelos");
        if (mo != null) for (int i = 0; i < mo.length(); i++) a.modelos.add(mo.optString(i));
        JSONArray or = o.optJSONArray("origenes");
        if (or != null) for (int i = 0; i < or.length(); i++) a.origenes.add(or.optString(i));
        JSONArray de = o.optJSONArray("destinos");
        if (de != null) for (int i = 0; i < de.length(); i++) a.destinos.add(de.optString(i));
        a.altMaxM = o.optInt("alt_max_m", 0);
        a.matriculas = o.optString("matriculas", "");
        a.indicativos = o.optString("indicativos", "");
        a.vuelos = o.optString("vuelos", "");
        a.squawks = o.optString("squawks", "");
        JSONArray xo = o.optJSONArray("excluir_origenes");
        if (xo != null) for (int i = 0; i < xo.length(); i++) a.excluirOrigenes.add(xo.optString(i));
        JSONArray xd = o.optJSONArray("excluir_destinos");
        if (xd != null) for (int i = 0; i < xd.length(); i++) a.excluirDestinos.add(xd.optString(i));
        return a;
    }
}
