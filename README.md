# ✈️ Avisos Aviones

App Android que te manda una notificación cuando un avión o helicóptero pasa cerca de donde estás.

## Descargar

Ve a **[Releases → última versión](../../releases/latest)** y descarga `AvisosAviones.apk`.
Al instalar, Android te pedirá permitir "instalar apps de origen desconocido".

## Uso

1. Ajusta el **radio** (km), la **altitud máxima** (0 = sin límite) y si quieres **solo helicópteros**.
2. Pulsa **Empezar a vigilar** y concede ubicación y notificaciones.
3. Pulsa **Quitar ahorro de batería** para que Android no la duerma.

Mientras vigila verás una notificación fija con botón **Parar**. Cada aviso muestra
indicativo, tipo, matrícula, distancia y altitud; al tocarlo se abre en el mapa de airplanes.live.

## Cómo funciona

- Ubicación del móvil (GPS / red).
- Cada X segundos consulta la API gratuita de [airplanes.live](https://airplanes.live) (y [adsb.lol](https://adsb.lol) como respaldo).
- Helicóptero = categoría ADS-B `A7`.
- No repite aviso del mismo aparato durante 10 minutos.

**Límite:** solo aparecen aeronaves que emiten posición ADS-B. Muchos helicópteros militares,
policiales o algunos privados no lo hacen.

## Compilar

Cada push a `main` compila la APK con GitHub Actions y la publica en Releases.
