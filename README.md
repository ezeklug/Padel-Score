# Punto Padel para Wear OS

Aplicacion independiente para llevar el tanteo de padel o tenis desde un reloj Wear OS.

## Funciones

- Partido al mejor de 1, 3 o 5 sets.
- Game tradicional con ventaja o punto de oro en 40-40.
- Nombres editables para ambos equipos.
- Tie-break automatico en 6-6.
- Deshacer el ultimo punto.
- Recuperacion del partido si la app se cierra.
- Bloqueo de controles durante el partido y vibracion al anotar.
- Duracion total, por set y promedio por game.
- Historial local de hasta 50 partidos con nombres editables.

## Abrir y probar

1. Instalar la version actual de Android Studio.
2. Elegir **Open** y seleccionar esta carpeta `PadelScore`.
3. Esperar a que Android Studio descargue Gradle y las dependencias.
4. En **Tools > Device Manager**, crear un dispositivo **Wear OS Small Round**.
5. Seleccionar el reloj virtual y pulsar **Run**.

## Instalar en un Galaxy Watch4

El Watch4 usa Wear OS y puede recibir la app directamente desde Android Studio.

1. Reloj: **Ajustes > Acerca del reloj > Informacion de software**. Tocar 5 veces **Version de software** para activar opciones de desarrollador.
2. Reloj: **Opciones de desarrollador**. Activar **Depuracion ADB** y **Depuracion inalambrica**.
3. PC y reloj deben estar en la misma red Wi-Fi.
4. Android Studio: **Device Manager > Pair Devices Using Wi-Fi > Pair using pairing code**.
5. En el reloj, abrir **Vincular dispositivo nuevo**, e ingresar en Android Studio la IP, puerto y codigo mostrados.
6. Seleccionar el reloj en la barra superior de Android Studio y pulsar **Run**. La app quedara instalada en el menu del reloj.![alt text](image.png)

Para generar un APK manual: **Build > Build App Bundles or APKs > Build APKs**. Luego se puede instalar con `adb install app-debug.apk` mientras el reloj esta conectado.

