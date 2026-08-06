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

- `PadelScore/app`: aplicacion Wear OS.
- `PadelScore/mobile`: aplicacion para telefonos Android.
- `PadelScore/shared`: modelos y reglas usados por ambas apps.

## Requisitos

- Android Studio con JDK 17 o 21.
- Android SDK 36.
- Telefono Android con Google Play Services.
- Reloj Wear OS emparejado con el telefono.

Al abrir la app del reloj se solicitan permisos de actividad y ritmo cardiaco. Si se rechazan, el tanteador sigue funcionando, pero el historial no incluye esas metricas.

## Ejecutar

Abrir la carpeta `PadelScore` en Android Studio y esperar la sincronizacion de Gradle.

Para el reloj, seleccionar la configuracion `app`, elegir el reloj y pulsar **Run**. Para el telefono, seleccionar `mobile`, elegir el telefono y pulsar **Run**.

Para conectar por Wi-Fi, activar `Depuracion inalambrica` en las opciones de desarrollador del dispositivo y usar **Pair Devices Using Wi-Fi** en Android Studio.

## Sincronizacion

La sincronizacion utiliza Wear OS Data Layer y no requiere servidor. Ambas aplicaciones deben tener el mismo `applicationId`, firma y estar instaladas en un telefono y reloj emparejados. Se sincronizan el partido activo y hasta 50 registros del historial.

## Compilar

Desde `PadelScore`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :mobile:assembleDebug :app:assembleDebug
```

Los archivos locales, caches, APK y claves de firma se excluyen mediante `.gitignore`.
