# Tanteo

Aplicacion Android multideporte para registrar partidos desde un telefono y un reloj Wear OS.

## Funciones

- Seleccion de padel, tenis, futbol 5, futbol 7 o futbol 11.
- Historial y resumen global independientes para cada deporte.
- Tanteador de goles y cronometro libre para futbol.
- Cronologia de goles con marcador acumulado y precision de minutos y segundos.
- Planteles configurables de 5, 7 u 11 jugadores desde el telefono, con catalogos de nombres independientes para padel, tenis y futbol.
- Integrantes configurables tambien para padel y tenis: usuario, companeros y rivales, sin nombres personalizados para los equipos.
- Los integrantes pueden completarse o corregirse desde el telefono despues de finalizar el partido.
- Administracion global del catalogo para eliminar companeros o rivales cargados incorrectamente.
- Estadisticas por compañeros y rivales en el resumen global de futbol.
- Graficos historicos de ritmo cardiaco y distancia recorrida.
- Selector de deporte persistente en el telefono y pantalla inicial de deportes en el reloj.
- Deportes configurables como visibles u ocultos y cambio rapido mediante deslizamiento horizontal en el telefono.
- Colores fijos: rojo para Mi Equipo y azul para Rival.
- Partidos de 1, 3 o 5 sets y modo libre sin cantidad predeterminada.
- Cierre manual con empates, sets, games o puntos inconclusos.
- Game con ventaja o punto de oro y tie-break automatico.
- Modalidad individual o dobles e integrantes configurables.
- Indicacion del equipo y jugador que saca.
- Deshacer puntos, vibraciones y bloqueo de controles en el reloj.
- Recuperacion del partido si una app se cierra.
- Historial con fecha, resultados y tiempos por set y game.
- Carga manual de partidos anteriores para cualquier deporte, con fecha, resultado, duracion, equipos y planteles; las metricas de salud quedan sin medicion.
- Imagen de estadisticas para guardar o compartir.
- Imagen del resumen estadistico global para guardar o compartir.
- Registro para todos los deportes desde el reloj de ritmo cardiaco, distancia estimada, pasos y calorias mediante Wear OS Health Services.
- Sincronizacion bidireccional entre reloj y telefono.

## Modulos

- `Tanteo/app`: aplicacion Wear OS.
- `Tanteo/mobile`: aplicacion para telefonos Android.
- `Tanteo/shared`: modelos y reglas usados por ambas apps.

## Requisitos

- Android Studio con JDK 17 o 21.
- Android SDK 36.
- Telefono Android con Google Play Services.
- Reloj Wear OS emparejado con el telefono.

Al abrir la app del reloj se solicitan permisos de actividad y ritmo cardiaco. Si se rechazan, el tanteador sigue funcionando, pero el historial no incluye esas metricas.

## Ejecutar

Abrir la carpeta `Tanteo` en Android Studio y esperar la sincronizacion de Gradle.

Para el reloj, seleccionar la configuracion `app`, elegir el reloj y pulsar **Run**. Para el telefono, seleccionar `mobile`, elegir el telefono y pulsar **Run**.

Para conectar por Wi-Fi, activar `Depuracion inalambrica` en las opciones de desarrollador del dispositivo y usar **Pair Devices Using Wi-Fi** en Android Studio.

## Sincronizacion

La sincronizacion utiliza Wear OS Data Layer y no requiere servidor. Ambas aplicaciones deben tener el mismo `applicationId`, firma y estar instaladas en un telefono y reloj emparejados. Se sincronizan el partido activo y hasta 50 registros del historial.

## Compilar

Desde `Tanteo`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :mobile:assembleDebug :app:assembleDebug
```

Los archivos locales, caches, APK y claves de firma se excluyen mediante `.gitignore`.

## Compartir e instalar sin Google Play

Se pueden distribuir las dos aplicaciones como archivos APK. La persona que las reciba debe instalar una APK en el telefono y otra en el reloj. Para que la sincronizacion funcione, ambas deben generarse con el mismo `applicationId`, la misma version y la misma clave de firma.

### Generar APK de prueba

Desde la carpeta `Tanteo`:

```powershell
.\gradlew.bat :mobile:assembleDebug :app:assembleDebug
```

Se generan estos archivos:

- Telefono: `mobile/build/outputs/apk/debug/mobile-debug.apk`
- Reloj: `app/build/outputs/apk/debug/app-debug.apk`

Estas APK sirven para pruebas directas. Para compartir versiones estables conviene generar APK firmadas desde Android Studio mediante **Build > Generate Signed App Bundle or APK > APK**. Hay que seleccionar primero `mobile` y despues `app`, utilizando exactamente el mismo archivo de claves y el mismo alias en ambas. La clave debe guardarse en un lugar seguro y no subirse a GitHub; sin ella no se podran publicar actualizaciones compatibles.

### Instalar en el telefono

1. Enviar `mobile-debug.apk` o la APK firmada del modulo `mobile` al telefono.
2. Abrir el archivo desde el telefono.
3. Cuando Android lo solicite, permitir **Instalar aplicaciones desconocidas** para la aplicacion desde la que se abrio el APK.
4. Confirmar la instalacion.

### Instalar en el reloj por Wi-Fi

El APK del reloj no se abre desde el telefono. Se instala mediante ADB inalambrico:

1. En el reloj, activar **Opciones de desarrollador**, **Depuracion ADB** y **Depuracion inalambrica**.
2. Abrir **Depuracion inalambrica > Vincular dispositivo nuevo** para consultar la IP, los puertos y el codigo.
3. En una PC con Android SDK Platform Tools, ejecutar:

```powershell
adb pair IP:PUERTO_DE_VINCULACION
adb connect IP:PUERTO_DE_DEPURACION
adb install -r app-debug.apk
```

Al ejecutar `adb pair`, ingresar el codigo mostrado por el reloj. Para una APK firmada, reemplazar `app-debug.apk` por el nombre correspondiente.

El telefono debe estar emparejado normalmente con ese reloj. Además, la APK del telefono y la del reloj deben provenir de la misma compilacion y estar firmadas con la misma clave para utilizar Wear OS Data Layer.
