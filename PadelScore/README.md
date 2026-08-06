# Punto Padel

Aplicacion Android para llevar el tanteo de padel o tenis desde un telefono y un reloj Wear OS.

## Funciones

- Partidos al mejor de 1, 3 o 5 sets.
- Game con ventaja o punto de oro.
- Modalidad individual o dobles.
- Nombres y colores configurables.
- Indicacion del equipo y jugador que saca.
- Tie-break automatico en 6-6.
- Deshacer el ultimo punto y bloquear controles en el reloj.
- Cronometro total, duracion por set y promedio por game.
- Recuperacion del partido si la app se cierra.
- Historial local con resultados, fecha, estadisticas y nombres editables.
- Sincronizacion bidireccional entre reloj y telefono.

## Modulos

- `app`: aplicacion para Wear OS.
- `mobile`: aplicacion para telefonos Android.
- `shared`: modelos y reglas de tanteo utilizados por ambas apps.

## Requisitos

- Android Studio con JDK 17 o 21.
- Android SDK 36.
- Telefono Android con Google Play Services.
- Reloj Wear OS emparejado con ese telefono.

## Ejecutar en el reloj

1. Abrir este proyecto en Android Studio.
2. Esperar que termine la sincronizacion de Gradle.
3. Seleccionar la configuracion `app`.
4. Elegir un emulador Wear OS o el reloj fisico.
5. Pulsar **Run**.

Para conectar un Galaxy Watch por Wi-Fi:

1. Activar las opciones de desarrollador tocando 5 veces `Version de software`.
2. Activar `Depuracion ADB` y `Depuracion inalambrica`.
3. En `Vincular dispositivo nuevo`, copiar IP, puerto y codigo.
4. Ejecutar `adb pair IP:PUERTO` y escribir el codigo.
5. Volver a Depuracion inalambrica y ejecutar `adb connect IP:PUERTO_DE_CONEXION`.

## Ejecutar en el telefono

1. Conectar el telefono por USB o depuracion inalambrica.
2. Seleccionar la configuracion `mobile` en Android Studio.
3. Elegir el telefono como dispositivo.
4. Pulsar **Run**.

El APK de desarrollo se genera en:

`mobile/build/outputs/apk/debug/mobile-debug.apk`

## Sincronizacion

La sincronizacion utiliza Wear OS Data Layer y no requiere un servidor propio.

- Ambas apps deben tener el mismo `applicationId` y la misma firma.
- El telefono debe ser el dispositivo emparejado con el reloj.
- Los cambios se guardan localmente antes de enviarse.
- Si los dispositivos estan desconectados, los datos se entregan al reconectarse.
- Se sincronizan el partido actual y hasta 50 partidos del historial.
- Si se modifica desde ambos dispositivos al mismo tiempo, prevalece el ultimo cambio.

## Compilar y probar

En Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :mobile:assembleDebug
```

El Gradle Wrapper se incluye en el repositorio. `local.properties`, `.idea`, caches, APK y archivos de firma estan excluidos mediante `.gitignore`.
