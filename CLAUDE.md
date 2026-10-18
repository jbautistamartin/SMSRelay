# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Toda la documentación de este proyecto está en español, igual que los comentarios y el KDoc
> del código. La línea de arriba se conserva en inglés porque es la cabecera estándar que
> identifica el fichero.

## Comandos de compilación y ejecución

> **Toolchain:** el proyecto apunta a JVM 17 y se compila con AGP 8.5.2. Si el JDK del `PATH` es
> más nuevo que el 21, apunta `JAVA_HOME` a un JDK 17 o 21 — AGP 8.5.2 no soporta JDK 25. En esta
> máquina sirve el JBR de Android Studio:
> `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …`.
> `local.properties` no se versiona; si falta, define `ANDROID_HOME` en su lugar.

```bash
# Compilación debug
./gradlew assembleDebug

# Instalar en el dispositivo conectado
./gradlew installDebug

# Compilación release (requiere las siguientes claves en local.properties):
#   KEYSTORE_PATH=../../Claves/capicua.jks
#   KEYSTORE_PASSWORD=<contraseña del almacén>
#   KEY_ALIAS=capicua          ← el alias es «capicua», no el nombre de la aplicación
#   KEY_PASSWORD=<contraseña de la clave>
# El almacén está fuera del repositorio y se comparte con las demás aplicaciones de
# la casa, así que KEYSTORE_PATH va relativa: el file() de app/build.gradle.kts la
# resuelve desde el módulo app/, no desde la raíz del proyecto.
# Sin las cuatro claves el build NO falla: el APK sale sin firmar.
# Con las cuatro claves pero una ruta o un alias equivocados, el build SÍ falla,
# en validateSigningRelease.
# El APK resultante se genera como smstowhats-<versionName>.apk
./gradlew assembleRelease

# Comprobar que el APK lleva la clave compartida (SHA-256 1C:6C:F6:C6:…:EC:CA)
apksigner verify --print-certs app/build/outputs/apk/release/smstowhats-<versionName>.apk

# Tests unitarios
./gradlew test

# Una sola clase de test
./gradlew test --tests "com.capicua.smstowhats.domain.rules.EvaluadorDeReglasTest"

# Conceder permisos en tiempo de ejecución en un dispositivo dedicado (tras instalar)
# No hay SEND_SMS ni READ_PHONE_STATE: esta app recibe por la radio y
# entrega por la red. Ver «Notas importantes», más abajo.
adb shell pm grant com.capicua.smstowhats android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstowhats android.permission.READ_SMS
adb shell pm grant com.capicua.smstowhats android.permission.POST_NOTIFICATIONS

# Eximir la app del modo Doze / optimización de batería (OBLIGATORIO para un despacho fiable)
# Sin esto, WorkManager puede posponerse indefinidamente con la pantalla apagada.
# La alternativa por interfaz está en la sección «Preparación del dispositivo», más abajo.
adb shell dumpsys deviceidle whitelist +com.capicua.smstowhats

# Verificar que la app está en la lista de exentas
adb shell dumpsys deviceidle whitelist
# Se espera: una línea que contenga "com.capicua.smstowhats"

# Quitarla de la lista (si hiciera falta)
adb shell dumpsys deviceidle whitelist -com.capicua.smstowhats

# Seguimiento del log en tiempo real
adb logcat -s "SmsReceiver" "SmsIngestionService" "WhatsAppSender" "DispatchWorker" "OrphanRescueWorker" "HealthMonitorWorker"

# Simular un SMS entrante en un emulador (no hace falta SIM)
adb emu sms send +34600112233 "Tu codigo es 4821"
```

## Publicar una versión

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts` (van juntos: Android se niega a
   instalar un APK cuyo `versionCode` no sea mayor que el instalado).
2. **Escribe la entrada en `CHANGELOG.md`**: lo que está bajo `[No publicado]` pasa a ser la
   versión nueva con su fecha. Ese fichero es el resumen de *qué* ha cambiado; las notas del
   release de GitHub son la versión larga —cómo instalarlo, cómo actualizar desde la anterior— y
   pueden repetirlo.
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/smstowhats-<versionName>.apk`,
   firmado con el almacén compartido (ver los comandos de compilación, más arriba).
4. Etiqueta sobre `main` y publica: `gh release create vX.Y.Z --notes-file <notas> <apk>`.

El APK mantiene la misma clave de firma versión tras versión. Es lo que permite al usuario
instalar la nueva encima sin perder reglas, ajustes ni historial — y también la razón de que un
APK con *otra* clave (una compilación debug, por ejemplo) no se pueda actualizar en el sitio.

> **La fase 9 se cerró el 18 oct 2026 y la 1.0.0 está publicada.** Lo que esa versión afirma —y
> lo que no— está en «El estado de la fase 9», más abajo. Resumen: la cadena se vio funcionar
> contra el número de prueba de Meta; el paso a un número propio sigue pendiente de la
> verificación de empresa, que no es trabajo de este repositorio.

## Arquitectura

Clean Architecture con separación estricta en tres capas y MVVM en la capa de presentación.
Inyección de dependencias con Hilt en todo el proyecto.

```
Presentación  (Fragments + ViewModels)
    ↓ usa
Dominio       (UseCases + motor de reglas + modelos de Kotlin puro)
    ↓ usa interfaces, implementadas por
Datos         (Room · SmsManager · DataStore · WorkManager)
```

### Ciclo de vida de un SMS

```
SIM → SmsReceiver.onReceive()           ← BroadcastReceiver (hilo principal, < 10 s)
        ↓ startForegroundService()
      SmsIngestionService               ← ForegroundService (debe llamar a startForeground() < 5 s)
        ↓ ProcesarSmsEntranteUseCase
      INSERT en Room (SmsEntity)        ← outbox: persiste ANTES de hacer nada más
        ↓ límites
        ↓ EvaluadorDeReglas
      INSERT en Room (ReenvioEntity × N) ← una fila por número de destino
        ↓ ColaDeEnvios.encolar()
      DispatchWorker.doWork()           ← un worker por reenvío, con backoff lineal
        ↓ EnviadorDeMensajes.enviar()   ← el interfaz; WhatsAppSender lo implementa
      POST graph.facebook.com/v26.0/<phone_number_id>/messages → actualizar Room
```

**Todo lo que está por encima del interfaz es agnóstico del medio, y esa es la gracia.** El
outbox, la idempotencia de cliente, el rescate de huérfanos, la política de reintentos y el
historial se escribieron para una pasarela HTTP, sobrevivieron a la mudanza a `SmsManager`,
sobrevivieron a la mudanza a Telegram y sobrevivieron a la mudanza a la Cloud API sin que nadie
los tocara. `TransportModule` es la única clase del proyecto que nombra un transporte concreto,
así que cambiar de medio es cambiar un `@Binds`.

El paso de límites de ese diagrama son ahora **dos** pasos: un tope por minuto que corta una
ráfaga y un tope por mes que corta una factura. Ver «Los dos topes», más abajo.

`OrphanRescueWorker` se reencadena cada 30 s y rescata **dos** cosas: SMS que se quedaron en
`PENDIENTE` cuya evaluación de reglas nunca terminó, y reenvíos en `PENDIENTE` sin tarea viva de
WorkManager.

`HealthMonitorWorker` se ejecuta cada 15 minutos y purga los reenvíos confirmados, los SMS ya
atendidos (de más de `RETENTION_DELIVERED_DAYS`, hoy **30 días**) y las entradas de log antiguas
(de más de `RETENTION_LOGS_DAYS`, también 30 días).

`BootReceiver` reactiva las tareas de WorkManager tras reiniciar el dispositivo.

### Mecanismo de reenvío

Quien opera la app configura una lista ordenada de **reglas** en la pantalla de Reglas; cada regla
decide, para un SMS entrante dado, **qué número de WhatsApp** lo recibe y con qué texto.

Una regla casa cuando se cumplen **los dos** criterios. Un criterio vacío se cumple siempre, así
que una regla con ambos vacíos casa con todos los SMS:

- `regexTelefono` — se aplica al remitente **tal como lo entrega la PDU**, sin normalizar. Puede
  ser alfanumérico (`BANCO`, `AMAZON`).
- `regexMensaje` — se aplica al cuerpo completo del SMS.

La coincidencia es **parcial** (`Regex.containsMatchIn`): `codigo` casa con "Tu codigo es 4821".
Ancla con `^…$` para exigir el texto entero. Los patrones distinguen mayúsculas salvo que la regla
active `ignorarMayusculas`, que aplica `RegexOption.IGNORE_CASE` a **las dos** expresiones a la
vez; un `(?i)` en línea sigue funcionando y los dos son acumulativos, no incompatibles.

Las reglas se evalúan por `orden` ascendente. La primera que casa gana y la evaluación se detiene,
**salvo** que la regla tenga `continuar = true`, en cuyo caso la evaluación sigue y el mismo SMS
puede llegar a varios destinos.

El `destino` de la regla es un **número de teléfono en forma E.164**, usado literalmente, y **en él
no se resuelve ningún marcador**. `DestinoDeWhatsApp` acepta una sola forma —`+`, prefijo de país,
de 8 a 15 dígitos— y normaliza quitando los espacios, guiones y paréntesis con que la gente
escribe los números.

**El `+` inicial es obligatorio, y eso es una decisión de diseño, no una exigencia de la API.** La
Cloud API acepta el número de las dos maneras. Lo que se gana exigiéndolo es un mensaje de error
sin ambigüedad: `600112233` son nueve dígitos que podrían ser un móvil español escrito a pelo
(inútil, falta el prefijo de país) o un número internacional completo de un país de prefijo corto,
y no hay forma de saberlo a simple vista. Con el `+` obligatorio la ambigüedad desaparece y la
pantalla puede decir lo que de verdad lo arregla —*«falta el prefijo internacional, escribe
+34…»*— que es el error que comete todo el mundo. `faltaPrefijo()` existe exactamente para ese
mensaje, y se niega a dispararse con cualquier cosa que empiece por `-`, porque un `chat_id` de
Telegram merece el otro mensaje.

**Los destinos son siempre personas individuales, nunca grupos.** Es una decisión, no una
limitación encontrada: la Cloud API admite grupos desde 2026, pero solo los que ella misma haya
creado y sean por invitación, de hasta ocho participantes, y solo para una Official Business
Account. Ceñirse a personas deja una sola forma que validar en lugar de dos y fija
`recipient_type` en `individual` sin ramificaciones.

Que en el destino no se resuelva ningún marcador es una **propiedad de seguridad**, y es el único
punto en el que el argumento hubo que reescribirlo en vez de heredarlo. SMStoSMS resolvía ahí
`{telefono}` para contestar a quien escribía; con Telegram el marcador se fue porque el remitente
de un SMS y un chat son identidades sin relación y la sustitución era imposible. Ahora el destino
vuelve a ser un número de teléfono, así que **sí** sería posible — y sigue sin hacerse, por una
razón mejor: el cuerpo del SMS de un tercero no debe decidir nunca adónde se envía nada, y menos
en una cuenta de empresa que factura por mensaje.

**Ya no hay descubrimiento de chats, y no lo sustituye nada.** `DescubridorDeChats` existía porque
nadie conoce su propio `chat_id`. Su propio número de teléfono lo conoce todo el mundo. En la
Cloud API no hay `getUpdates`, y enumerar quién ha escrito exigiría levantar un webhook público;
no hace falta.

El texto que se entrega se compone con la `plantilla` de la regla, con tres marcadores opcionales:

- `{mensaje}` — el cuerpo del SMS recibido
- `{telefono}` — el número del remitente
- `{fecha}` — fecha de recepción, formateada para mostrarse en hora **local** (no ISO UTC: la lee
  una persona en su teléfono)

`{mensaje}` se sustituye **el último** a propósito, para que el cuerpo del SMS —contenido de
terceros— no se vuelva a escanear y no pueda inyectar marcadores.

**La plantilla por defecto es `{telefono} · {fecha}\n{mensaje}`, no `{mensaje}`.** Con SMS bastaba
con el cuerpo: el reenvío llegaba como otro SMS y el teléfono ya mostraba quién lo mandaba. Por
WhatsApp todo llega del mismo número, así que cuatro avisos seguidos sin el remitente dentro son
cuatro mensajes indistinguibles. El contexto que el SMS daba gratis hay que ponerlo a mano — y con
una plantilla importa todavía más, porque el cuerpo que Meta aprobó es idéntico en todos y cada uno
de los reenvíos y la variable es lo único que los distingue.

### Modo de entrega: texto libre, plantillas, y por qué las plantillas son el camino principal

**Esta es la sección que hay que leer antes que ninguna otra sobre la entrega.** La Cloud API tiene
dos clases de mensaje y no son intercambiables:

| | Mensaje de servicio (texto libre) | Mensaje de plantilla |
|---|---|---|
| Cuándo se puede enviar | Solo dentro de una **ventana de 24 h** que abre el destinatario escribiendo al número emisor | **Siempre**, sin contacto previo |
| Contenido | Texto libre, hasta **4.096** caracteres | Un cuerpo aprobado por Meta, hasta **1.024** renderizados |
| Saltos de línea en la parte variable | Sí | **No** — ni tabuladores, ni más de 4 espacios seguidos |
| Coste | Los primeros **1.000 al mes y por número** gratis; el resto se factura desde el 1 oct 2026 | **Todos** se facturan |

Un reenviador de SMS es por definición un emisor **no solicitado**: el SMS llega cuando llega y
tiene que salir entonces, no cuando al destinatario le apetezca abrir una ventana. Por tanto:

> **Las plantillas no son el plan B de esta aplicación. Son el camino principal.** El texto libre
> es la optimización que aplica cuando la ventana resulta estar abierta.

Eso invierte lo que uno supondría, y es la razón de que `PlantillaDeWhatsApp` no sea un extra
opcional.

**El contrato de la plantilla es una sola variable**, y el cuerpo que quien opera tiene que dar de
alta en el Business Manager es exactamente `CUERPO_RECOMENDADO`:

```
Reenvío de SMS

{{1}}

— SMStoWhats
```

Tres propiedades, las tres deliberadas:

1. **Los saltos de línea viven en la plantilla, no en la variable.** Meta aprueba el cuerpo una
   vez, con su maquetación dentro. Lo que no puede contener un salto de línea, un tabulador ni más
   de cuatro espacios seguidos es el *valor* que se pasa en `{{1}}`: hacerlo devuelve un `132007` y
   mata el reenvío. De ahí `aplanar()`, que convierte los saltos de línea en ` · ` y colapsa el
   resto.
2. **Ni empieza ni termina con la variable**, cosa que Meta no permite.
3. **Una variable y no tres.** Remitente, fecha y cuerpo en tres huecos maquetarían mejor y
   romperían algo que importa más: habría una **segunda** vía de composición del mensaje, la de la
   plantilla, que ignoraría la `plantilla` de cada regla. Con una variable, `EvaluadorDeReglas`
   compone el texto en los dos modos y la plantilla de la regla sigue significando lo mismo.

`PRESUPUESTO` se deriva de ese contrato (1.024 menos el texto fijo menos un margen) porque la
aplicación no conoce el cuerpo aprobado: vive en la cuenta de Meta. Cambia `CUERPO_RECOMENDADO` y
el presupuesto se recalcula. Hoy vale **978**.

`ModoDeEntrega` tiene tres valores. `AUTOMATICO` prueba el texto libre y, ante un `131047`,
reenvía como plantilla **dentro del mismo intento del worker** — no como reintento, porque la
ventana seguiría cerrada diez intentos después: lo que la abre es que el destinatario escriba. Eso
hace de este el primer transporte del proyecto que emite dos peticiones distintas para un solo
reenvío, y el worker no se entera, que es lo correcto: el modo de entrega es del transporte.

### Los dos topes, y las dos guardas que ya no existen

SMStoSMS tenía tres protecciones. **Dos han desaparecido, y borrarlas fue lo correcto** — lee esto
antes de "restaurar" nada que parezca que falta:

1. *Un SMS cuyo remitente sea uno de los destinos configurados se descarta.*
2. *Una regla que casa cuyo destino sea igual al remitente se salta.*

Las dos comparaban el `destino` de una regla con el remitente del SMS. En el momento en que
`destino` pasó a ser un `chat_id`, esa comparación dejó de significar nada: un chat de Telegram y
un número de teléfono son identidades sin relación. `AppConfig.protegerBucles`,
`NormalizadorTelefono` y `ReglaDao.obtenerDestinosActivos()` se fueron con ellas.

**`destino` vuelve a ser un número de teléfono, así que esa comparación sería ahora posible — y
sigue sin merecer la pena.** La razón ha cambiado: un mensaje de WhatsApp no vuelve nunca como un
SMS. El SMS llega por la radio y el reenvío sale por la red; no hay círculo que cerrar. Lo único
con esa forma lo vigila la propia Meta, que rechaza un envío del número a sí mismo con el error
`131021`. **No "restaures" las guardas.** `NormalizadorTelefono` tampoco vuelve: lo que hace falta
es validar una forma E.164, no comparar.

`AppConfig.maxReenviosPorMinuto` sobrevive, y su significado cambia por **tercera** vez:

| Transporte | Para qué servía |
|-----------|-----------------|
| SMS | Cortafuegos de coste: un bucle con reintentos detrás era una factura de teléfono |
| Telegram | Los límites de la Bot API — un mensaje por segundo y por chat. Entregar era gratis |
| Cloud API | Las dos cosas a la vez, y el lado del coste muerde más que nunca |

Lo que sigue haciendo es cortar una **ráfaga**: un aluvión de SMS dentro de un mismo minuto. El
rendimiento de la propia Cloud API es generoso (unos 80 mensajes por segundo), así que ese no es el
techo; el techo es `131056` —demasiados mensajes al mismo destinatario demasiado deprisa—.

**`AppConfig.maxMensajesPorMes` es nuevo, y es el tope que de verdad importa.** Es la primera
protección del proyecto que mide **dinero**: Meta regala los primeros 1.000 mensajes de servicio al
mes y por número y factura todos los de plantilla, y pasarse no produce un error, produce una
factura. Mil al mes son **treinta y tres al día**; 10 por minuto son 14.400 al día, tres órdenes de
magnitud por encima del primer techo con el que te vas a encontrar. Son dos protecciones distintas
porque miden cosas distintas: una corta una ráfaga, la otra corta un mes. El valor por defecto es
900 —por debajo del tramo gratuito, así que una instalación sin configurar no factura nunca nada— y
`0` lo desactiva.

`ContadorDeMensajes` lleva la cuenta, en el DataStore de ajustes y bajo sus propias claves (el
mismo razonamiento que `EstadoDeLaCuenta`: no es configuración, es un hecho sobre *este* teléfono y
*este* mes). Cuenta **mensajes entregados y no reenvíos**, porque un SMS largo se trocea y Meta
factura por mensaje: contar reenvíos daría un número que no se parece a la factura, que es lo único
para lo que existe. El mes cambia dentro de la misma transacción que el incremento, sin tarea
programada: una pieza menos que pueda no ejecutarse, en una aplicación cuyo trabajo periódico ya
pelea con Doze.

El Panel lo muestra, porque es la única cifra de esta aplicación que cuesta dinero y que nadie iría
a buscar. Enterrada en Ajustes, el primer aviso de haberse pasado sería la factura.

El exceso se descarta y se registra como `LogTipo.LIMITE_ALCANZADO` — renombrado desde
`BUCLE_EVITADO`, que llevaba el nombre de una protección que ya no existe. El nombre ha envejecido
mejor que su razón original: ahora hay dos topes que pueden dispararlo, así que «un tope dijo que
no» es exactamente lo que registra.

### Navegación por pestañas

Las cinco pantallas principales —Panel, Reglas, Logs, Ajustes y Acerca de— son **páginas de un
`ViewPager2`**, no destinos del grafo de navegación, así que a cada una se llega tanto tocando la
Bottom Navigation como deslizando a izquierda o derecha.

`nav_graph.xml` contiene por tanto solo tres destinos: `homeFragment`, que hospeda el pager, más
las dos subpantallas de reglas `editarReglaFragment` y `probarReglasFragment`. Las dos acciones
cuelgan de `homeFragment`, porque cuando `ReglasFragment` navega, el destino actual del
NavController es el que hospeda el pager y no la lista de reglas. `findNavController()` sigue
resolviendo desde dentro de una página: sube por la cadena de fragments padre.

`Pestana` (`presentation/home/Pestana.kt`) es la única fuente de verdad que ata tres cosas que
antes tenían que coincidir a mano: la posición en el pager, el id del menú de Bottom Navigation y
el Fragment. El orden de declaración **es** el orden de las páginas, y `bottom_nav_menu.xml` tiene
que listar sus elementos en ese mismo orden o la barra se lee distinta de como se desliza.

`setupWithNavController` ya no está. La barra es de `MainActivity` y el pager de `HomeFragment`, y
no comparten tiempo de vida —el pager se destruye en cuanto navegas al editor de reglas—, así que
se coordinan a través de `PestanasViewModel`, un `StateFlow` con alcance de actividad que lleva la
pestaña actual. Volver a seleccionar la pestaña que ya está activa no emite nada, y eso es lo que
evita que la sincronización en dos sentidos (barra → pager, deslizamiento → barra) se realimente.

Atrás en cualquier pestaña que no sea Panel vuelve a Panel en lugar de cerrar la app; en Panel el
gesto conserva su significado normal. La pila de retroceso del NavController daba eso gratis
deshaciendo las pestañas visitadas — un pager no tiene pila, así que `HomeFragment` define el
comportamiento de forma explícita.

**El interruptor general de la recepción está en Ajustes**, arriba del todo porque todo lo que hay
debajo es irrelevante mientras esté apagado. `recepcionActiva` se persiste como cualquier otro
ajuste, pero apagarlo hace algo más que guardar un flag: `ControlRecepcion` desactiva el
**componente** `SmsReceiver` a través del `PackageManager`. Un `if` al principio de `onReceive`
despertaría a la app con cada mensaje que llegase al teléfono solo para descartarlo, y obligaría a
una lectura asíncrona de DataStore en el hilo principal dentro del presupuesto de diez segundos del
broadcast. Desactivar el componente significa que el sistema deja de entregar el broadcast: nada
que competir, nada que decidir. Detiene solo el lado **entrante**: lo que ya estuviera en cola se
sigue reenviando, porque descartarlo perdería mensajes que la app ya había aceptado. El sistema
persiste el estado del componente y sobrevive a los reinicios, así que
`SmsToSmsApplication.sincronizarRecepcion()` solo importa después de una **reinstalación**, donde
el componente vuelve al valor del manifiesto y el ajuste guardado tiene que volver a imponerse.

**En «Acerca de» vive el enlace de donación**, y es la única pantalla que lo menciona: una tarjeta
con un botón *Invitar a un café* que abre `https://paypal.me/jbautistamartin` en el navegador
(`URL_DONATIVO`, una const privada de `AboutFragment`). El corazón del botón
(`drawable/ic_corazon.xml`) es el mismo trazado de cuatro béziers y el mismo dorado que el que
SilentRunner dibuja en su menú de bandeja, de modo que el icono se lee igual en toda la casa; lleva
`app:iconTint="@null"`, porque teñirlo con el color del tema tiraría por la borda lo único que lo
identifica. El intent se lanza capturando `ActivityNotFoundException` en lugar de preguntar antes al
`PackageManager` —el mismo razonamiento que en `OptimizacionBateria`: desde Android 11 la
visibilidad de paquetes puede afirmar que no hay navegador cuando sí lo hay—. Si no resuelve nada,
un Snackbar lo dice en vez de dejar un botón muerto. Es opcional de principio a fin: la app no
insiste nunca y ninguna funcionalidad depende de ello.

**La misma pantalla lleva una tarjeta de *Código abierto*** con un botón *Ver el proyecto en
GitHub* (`URL_PROYECTO`, al lado de `URL_DONATIVO` en `AboutFragment`), y va justo encima de la de
donación porque es lo que da contexto a la donación: el código está publicado, y la tarjeta dice
dónde. Su icono (`drawable/ic_codigo.xml`) es el caso contrario al del corazón: no hay color que lo
identifique, así que se deja teñir por el tema. Los dos botones pasan por el mismo `abrirEnlace(url)`
y comparten el Snackbar para un dispositivo sin navegador: esa cadena pasó de llamarse
`about_donativo_sin_navegador` a `about_sin_navegador` cuando llegó el segundo enlace, porque nunca
dijo nada sobre donar.

**«Acerca de» comprueba además si hay versión nueva**, por el mismo permiso `INTERNET` que usa la
entrega. Ese permiso llevaba aquí un descargo —*la app reenvía SMS por la radio del teléfono y el
contenido de los mensajes no viaja nunca por la red*— y **esa frase ahora es falsa**. Era cierta en
SMStoSMS y se mantuvo escrita exactamente mientras lo fue. En esta app el cuerpo del SMS sale por la
red por diseño, hacia los servidores de Meta, y el aviso de privacidad de «Acerca de» lo dice con
todas las letras porque quien la instala necesita saberlo.

Lo que la comprobación de versión añade encima de eso es
`data/update/ComprobadorDeActualizaciones` preguntando a `api.github.com` por el último release, y
`DescargaDeApk` descargando el APK cuando alguien pulsa el botón. Retrofit y OkHttp siguen **sin**
estar: tanto esa petición como las llamadas a la Cloud API son una docena de líneas de
`HttpURLConnection` cada una, con `kotlinx.serialization` para las respuestas, que ya estaba aquí
para la exportación de reglas.

Cómo se comporta, y por qué:

- **`MainActivity` pregunta al arrancar** y, si hay una versión más nueva, pone una **marca en la
  pestaña «Acerca de»** — ni diálogo ni notificación. Esto corre en un teléfono que reenvía SMS sin
  que nadie lo mire; cualquier cosa que se ponga delante al arrancar tapa justo lo que venías a ver.
  La tarjeta de «Acerca de» es donde se explica, con el interruptor que apaga la pregunta automática
  y un *Buscar ahora* que ignora el intervalo diario.
- **Una pregunta al día, con `If-None-Match`.** La API sin autenticar permite 60 peticiones por hora
  y por IP, y un 304 no cuenta. La marca de tiempo y el ETag viven en `EstadoActualizacion`, en el
  **mismo DataStore** que los ajustes pero con sus propias claves — deliberadamente **fuera** de
  `AppConfig`: eso es configuración de reenvío, viaja en el fichero de copia de seguridad, y que
  este teléfono quiera avisos de actualización no tiene nada que ver con cómo deba reenviar SMS otro
  teléfono.
- **Lo que se encontró se recuerda, no solo cuándo se preguntó.** `EstadoActualizacion` guarda
  también la etiqueta, la página y el APK del release encontrado, y `comprobar()` lo devuelve desde
  ahí mientras siga ganando a la versión instalada. Sin eso, el aviso vivía solo en el proceso que
  lo descubrió: la marca salía en el primer arranque del día y ya no estaba en el segundo, porque la
  barrera diaria respondía `SinPreguntar` y nada recordaba el hallazgo. Es además la respuesta
  correcta a un **304** —«el último release no ha cambiado» no significa que sea el instalado— y se
  limpia sola, porque una vez instalada la actualización la comparación deja de cumplirse y se
  ejecuta `olvidarNovedad()`.
- **Las versiones se comparan como números** (`VersionPublicada`), nunca como texto: `"1.10.0" <
  "1.9.0"` alfabéticamente, lo que dejaría el aviso mudo a partir del décimo release. Es la única
  pieza de todo esto que puede probarse con tests unitarios sin red, y `VersionPublicadaTest` la
  cubre.
- **Un 404 significa que todavía no hay releases**, no un error. Sin red, no decir nada.
- **Instalar es cosa del sistema.** `DownloadManager` descarga el APK al directorio externo privado
  de la app (sin permiso de almacenamiento) y `REQUEST_INSTALL_PACKAGES` permite abrir el diálogo
  del instalador de Android — las instalaciones silenciosas necesitan ser device owner, y esto no lo
  es. Desde Android 8 el permiso del manifiesto tampoco basta: el usuario tiene que autorizar a
  *esta* app en Ajustes, y eso se comprueba **antes** de descargar tres megas para nada.
- **La firma es el modelo de seguridad.** Android solo actualiza un APK firmado con la misma clave,
  así que nadie puede colar un APK falsificado encima de este y aquí no hay hashes que verificar. La
  otra cara es que no puede instalarse sobre una compilación debug
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), que es por lo que la tarjeta dice que la actualización
  conserva reglas, ajustes e historial.

**Un hijo con desplazamiento horizontal dentro del pager necesita `NestedScrollableHost`**
(`presentation/common/NestedScrollableHost.kt`, la clase de ejemplo de AndroidX adaptada a este
código). Si no, los dos gestos son el mismo gesto y gana el pager: el carrusel de filtros de la
pantalla de Logs dejaría de desplazarse, y arrastrar sobre él cambiaría de pantalla. El host
arbitra: el hijo se queda el gesto mientras le quede recorrido en esa dirección y lo cede en su
borde. `fragment_logs.xml` es hoy el único layout que lo necesita; cualquier nuevo desplazador
horizontal, carrusel o deslizador que se ponga en una pestaña necesita el mismo envoltorio.

### Ficheros clave

| Fichero | Cometido |
|------|------|
| `domain/transport/EnviadorDeMensajes.kt` | **La juntura.** Lo único que la tubería sabe del medio |
| `domain/transport/ResultadoEnvio.kt` | `Enviado` / `ErrorTransitorio` / `ErrorPermanente` — toda la política de reintentos |
| `di/TransportModule.kt` | La única clase que nombra un transporte concreto |
| `data/whatsapp/WhatsAppSender.kt` | El `POST` a `/messages`, en los dos modos de entrega; implementa el interfaz |
| `data/whatsapp/MapeoDeRespuestas.kt` | **Puro:** (código HTTP, cuerpo) → `ResultadoEnvio`, guiado por `error.code`, más el troceado. Con tests |
| `data/whatsapp/PlantillaDeWhatsApp.kt` | **Puro.** El contrato de la plantilla, `aplanar()` y el presupuesto. Con tests |
| `data/whatsapp/DestinoDeWhatsApp.kt` | Qué puede escribirse como destino: un número E.164. Con tests |
| `data/whatsapp/TokenDeAcceso.kt` | Lo que puede decirse del token sin preguntar a Meta. Con tests |
| `data/whatsapp/IdNumeroTelefono.kt` | La segunda credencial, y el mensaje de error de haber copiado el número en su lugar. Con tests |
| `data/whatsapp/ComprobadorDeCuenta.kt` | El botón de Ajustes que dice *qué* número es, más el `quality_rating` |
| `data/whatsapp/EstadoDeLaCuenta.kt` | Recuerda credenciales rechazadas, para que el Panel pueda decirlo |
| `data/whatsapp/ContadorDeMensajes.kt` | Mensajes entregados este mes, servicio frente a plantilla, para el Panel y el tope |
| `data/config/ModoDeEntrega.kt` | `AUTOMATICO` / `SOLO_TEXTO_LIBRE` / `SOLO_PLANTILLA` |
| `domain/rules/EvaluadorDeReglas.kt` | Motor de coincidencia, resolución de plantillas, validación de patrones |
| `domain/usecase/ProcesarSmsEntranteUseCase.kt` | Los dos topes → evaluar → crear reenvíos → encolar |
| `worker/ColaDeEnvios.kt` | El único sitio que encola una tarea de despacho |
| `worker/DispatchWorker.kt` | Entrega un reenvío; gestiona la lógica de reintento y de fallo |
| `worker/OrphanRescueWorker.kt` | Rescata SMS y reenvíos atascados cada 30 s |
| `data/config/AppConfig.kt` | Las dos credenciales, modo de entrega, plantilla, reintentos, timeout, los dos topes |
| `data/rules/ReglasJson.kt` | Formato versionado de exportación e importación de reglas |
| `data/local/db/Migraciones.kt` | Migraciones de Room escritas a mano, registradas por `DatabaseModule` |
| `util/OptimizacionBateria.kt` | Exención de Doze: consulta del estado + el botón de Ajustes |
| `util/InicioAutomatico.kt` | Pantalla de inicio automático del fabricante (MIUI/EMUI/ColorOS/Funtouch) + el botón de Ajustes |
| `util/ControlRecepcion.kt` | Activa y desactiva el componente `SmsReceiver` — el interruptor de recepción |
| `data/config/ConfigBackup.kt` | Exportar e importar la configuración entera: ajustes **y** reglas |
| `data/update/ComprobadorDeActualizaciones.kt` | Pregunta a GitHub por el último release y decide si gana al instalado |
| `data/update/VersionPublicada.kt` | Comparación SemVer — la única parte de la comprobación con tests unitarios |
| `data/update/DescargaDeApk.kt` | Descarga con `DownloadManager` más el intent del instalador del sistema |
| `data/update/EstadoActualizacion.kt` | Interruptor del aviso, última consulta y ETag, en sus propias claves de DataStore |

### La entrega: qué le debe el transporte al worker

`EnviadorDeMensajes.enviar()` **espera el desenlace**, y ese requisito ha sobrevivido ya a tres
transportes. Con `SmsManager` significaba suspender hasta que llegara el broadcast del `sentIntent`
de cada parte, porque la llamada de envío retornaba antes de que el mensaje saliera del
dispositivo; con Telegram y con la Cloud API significa esperar la respuesta HTTP. La razón es la
misma en todos los casos: si la llamada retornase antes de conocerse el desenlace, toda entrega
parecería correcta y los fallos reales se desvanecerían.

**Sin `parse_mode` — pero el riesgo cambia de forma y se suaviza.** Con Telegram, poner `parse_mode`
hacía que cualquier SMS con un `_`, un `*`, un `[` o un `<` volviera como
`400 can't parse entities`, matando el reenvío porque alguien quería negrita. WhatsApp no tiene ese
interruptor: **interpreta `*negrita*` y `_cursiva_` siempre**, así que un SMS con asteriscos llegará
con media frase en negrita. Se acepta. Escapar el cuerpo significaría *interpretar* contenido de
terceros para modificarlo, que es justo lo que este proyecto no hace en ningún sitio, y el mensaje
llega entero de todas formas.

**`preview_url` va en `false`, y eso no es cosmética.** Dejado en true, WhatsApp **descarga** el
primer enlace que encuentre en el cuerpo para componer su tarjeta de vista previa. El cuerpo es el
SMS de un tercero, así que entregar un mensaje haría que los servidores de Meta fuesen a buscar una
URL elegida por quien mandó ese SMS — desde confirmar que un enlace de phishing tiene público hasta
quemar un enlace de un solo uso. No es configurable.

**La correspondencia de errores** vive en `MapeoDeRespuestas.interpretarEnvio()`, deliberadamente
separada del código que hace la petición para que pueda probarse con tests unitarios sin red. Y aquí
está la diferencia estructural con la versión de Telegram:

> **El código HTTP ya no basta.** La Cloud API mete **cuatro fallos distintos dentro del mismo
> `400`** y los distingue en `error.code`. Clasificarlos igual es lo que convierte un problema de
> configuración en cuatrocientas líneas de log idénticas de las que solo la primera informaba. Así
> que la clasificación vive en `error.code`, y el código HTTP es la red de seguridad para respuestas
> sin cuerpo legible.

| `error.code` | HTTP | Resultado |
|--------------|------|--------|
| — (2xx sin bloque `error`) | 2xx | `Enviado` |
| `131047` | 400 | **Caso especial**: la ventana de 24 h está cerrada. En `AUTOMATICO`, reenviar como plantilla |
| `131026` | 400 | `ErrorPermanente` — no es usuario de WhatsApp |
| `131021` | 400 | `ErrorPermanente` — emisor y destinatario son el mismo número |
| `132000` `132001` `132005` `132007` `132012` `132015` `132016` | 400 | `ErrorPermanente` — la plantilla y sus parámetros no cuadran |
| `0` `3` `190` | 401/403 | `ErrorPermanente` **y fallo de credencial** — el token |
| `100` | 400 | `ErrorPermanente` **y fallo de credencial** — el `phone_number_id` |
| `130429` `80007` `131048` `131056` | 429 | `ErrorTransitorio`, **sin espera sugerida** |
| `131000` `133004`, timeout, `IOException` | 5xx | `ErrorTransitorio` |
| un `4xx` no reconocido | 4xx | `ErrorPermanente` |
| cualquier otra cosa | — | `ErrorTransitorio`, por precaución |

Cuatro de ellas merecen nota aparte:

- **Un `100` es un `phone_number_id` incorrecto, no un recurso que falta.** Llega como un `400` con
  un mensaje genérico ("Unsupported post request"). Es el equivalente exacto del `404` de Telegram:
  lo que no existe es la ruta, y la ruta se construye con la credencial. Por eso
  `esFalloDeCredencial()` cubre **las dos** credenciales —`190`/`0`/`3` para el token, `100` para el
  id— y solo esas encienden el aviso del Panel a través de `EstadoDeLaCuenta`. Un `131026` es un
  problema con *ese* destino, y señalar a las credenciales mandaría a alguien a mirar donde no es.
- **Meta no manda `retry_after`.** Telegram sí, y `ResultadoEnvio.ErrorTransitorio` sigue llevando
  un `esperaSegundos` para ello; con la Cloud API es siempre null, que ya significaba «aplica el
  backoff de siempre». En consecuencia `DispatchWorker.esperarSiLoPiden()` hoy no duerme nunca
  —retorna en su primera línea— y se queda porque el contrato admite la espera y el próximo medio
  puede pedirla.
- **Un `4xx` no reconocido se cierra como permanente**, apartándose de lo que el MASTERPLAN esbozó
  primero. Un reintento manda **los mismos bytes** a la misma URL, así que «tu petición está mal» no
  puede cambiar por esperar; el motivo lleva el `error.code`, que es con lo que se busca en la
  documentación de Meta. Lo desconocido que *no* sea un 4xx se sigue reintentando por precaución.
- **El `131047` no se clasifica, se intercepta.** `esVentanaCerrada()` es un predicado aparte en
  lugar de un caso nuevo de `ResultadoEnvio`, porque `ResultadoEnvio` lo lee `DispatchWorker` y el
  worker no debe enterarse de que hay dos formas de entregar.

**Por encima de 4.096 caracteres** (texto libre) o de **978** (dentro de una plantilla) el texto se
trocea en lugar de truncarse (`MapeoDeRespuestas.trocear`), cortando por el último salto de línea y
recurriendo al último espacio. Truncar perdería contenido en silencio, y dejar que Meta responda
`132005` convertiría un mensaje largo en una entrega fallida — **pero trocear un mensaje de
plantilla cuesta dinero**, un mensaje facturado por trozo, que es por lo que `WhatsAppSender` lo
registra a nivel de aviso. Si un trozo falla y uno anterior tuvo éxito, se reintenta el reenvío
entero, asumiendo un posible duplicado: el mismo razonamiento que usaba el multiparte de SMS.

### Idempotencia

De cliente, en dos niveles:

- `ProcesarSmsEntranteUseCase.procesar()` comprueba si el SMS ya tiene reenvíos antes de crear
  ninguno. Eso es lo que hace seguro ejecutar el rescate de huérfanos sobre un SMS procesado a
  medias.
- `DispatchWorker` comprueba `reenvio.estado` al principio de cada intento y devuelve éxito sin
  tocar el transporte si ya está en `ENVIADO`.

`ColaDeEnvios` usa `ExistingWorkPolicy.KEEP`, así que un reenvío nunca tiene dos tareas
simultáneas.

### Base de datos Room

Cuatro tablas, versión de esquema **1** (ver el KDoc de `SmsDatabase`: volvió a empezar en 1 por
**tercera** vez, y por la misma razón). Esta vez el esquema no cambió en absoluto: las cuatro tablas
son idénticas y `reenvios.destino` sigue siendo un `TEXT`. Lo que cambió es lo que ese texto
*significa*, y eso es un cambio de contrato y no de esquema: lo vigila la versión del formato de
copia de seguridad, no una migración.

- `sms` — una fila por SMS recibido. Solo lo que llegó, más `estado`
  (`PENDIENTE`/`PROCESADO`/`SIN_REGLA`/`DESCARTADO`) y `motivo_descarte`.
- `reglas` — reglas ordenadas, con `ignorar_mayusculas` desde el principio. `destino` guarda un
  número de teléfono E.164, y `plantilla` **no** tiene valor por defecto en SQL a propósito: estaba
  escrito en dos sitios y los dos habrían divergido (ver el KDoc de `ReglaEntity`).
- `reenvios` — la unidad de despacho: una fila por número de destino, con su propio `estado`,
  `intentos` y `ultimo_error`. La FK a `sms` es CASCADE; la FK a `reglas` es SET NULL, y
  `nombre_regla` está desnormalizado para que el historial sobreviva al borrado de la regla. No hay
  columna `partes`: esa contaba los fragmentos de un SMS concatenado.
- `log_entries` — registro de auditoría. Tipos: `SMS_RECIBIDO`, `REGLA_APLICADA`, `SMS_REENVIADO`,
  `SIN_REGLA`, `LIMITE_ALCANZADO`, `ERROR`, `SISTEMA`.

Modo WAL activado. Los esquemas se exportan a `app/schemas/` y se versionan. Sin
`fallbackToDestructiveMigration`: las migraciones tienen que ser explícitas.

> **`Migraciones.TODAS` está vacío, y es la última vez que puede estarlo.** El esquema se quedó en
> una v1 limpia porque ningún dispositivo tiene esta base de datos: un `applicationId` nuevo es una
> aplicación distinta con su propio directorio de datos. En el momento en que la app se instale en
> un teléfono con reglas dentro, el esquema deja de poder editarse en el sitio —cambiar la v1 sin
> migrar hace que Room aborte al abrir la base de datos—, así que a partir de entonces todo cambio
> sube la versión y escribe su `Migration` en `data/local/db/Migraciones.kt`, que `DatabaseModule`
> registra.

## El estado de la fase 9

La fase 9 era **la única que no se cerraba desde el editor**: las ocho anteriores se cierran con
tests, y esta se cerraba cuando un SMS real acabase en un WhatsApp real. **Se cerró el 18 oct
2026** y con ella salió la 1.0.0.

| Comprobación | Estado |
|---|---|
| La cadena SIM → regla → Cloud API → WhatsApp | **Vista funcionar** (número de prueba) |
| Los 138 tests unitarios | **Pasan**, 0 fallos |
| `assembleRelease` con la clave compartida | **Compila y firma** — SHA-256 `1C:6C:F6:C6:…:EC:CA` |
| Número propio en producción | **Pendiente** — verificación de empresa, fuera de este repositorio |

**Qué afirma el 1.0.0 y qué no.** Afirma que la aplicación hace lo que dice. No afirma que la
cuenta de Meta de quien la instale esté lista: para un número propio hacen falta la verificación
de empresa —sin la cual Meta no deja ni registrar el número— y la plantilla dada de alta en la
WABA de producción, que no viaja desde la de pruebas. El punto 9.3 del masterplan enumera lo que
el número de prueba no puede decir, y sigue vigente: el nombre a mostrar, los límites reales y la
calidad del número solo se ven en producción.

## Convenciones de nombres

El código mezcla idiomas deliberadamente, y el código nuevo debe seguir el mismo reparto:

- **Los miembros** —funciones, propiedades, parámetros, nombres de columna de Room, constantes de
  enum— van en **español**: `guardar`, `observarTodos`, `obtenerPendientes`, `telefono`,
  `fecha_recepcion`, `SMS_REENVIADO`. Los únicos miembros en inglés son sobrescrituras del framework
  donde no hay elección (`onCreate`, `doWork`, `onBindViewHolder`, `provideDatabase`).
- **Los nombres de tipo** llevan un **sufijo arquitectónico en inglés** sobre un sustantivo español
  o inglés: `SmsRepository`, `ReglaDao`, `ReenvioEntity`, `SettingsFragment`, `ReglasViewModel`. Los
  tipos de dominio puro pueden ir enteros en español: `LogTipo`, `EstadoSms`, `EvaluadorDeReglas`.
- **Los comentarios y el KDoc** van en **español**.
- **Los nombres de clase de caso de uso siguen la misma regla:** una frase verbal en español más el
  sufijo `UseCase` — `ProcesarSmsEntranteUseCase`, `ObtenerListaSmsUseCase`. El `GetSmsListUseCase`
  en inglés heredado de SMSGateway se renombró por coherencia.

Este reparto se verificó contra el código previo a la migración, no se dio por supuesto: de las 89
funciones que el proyecto original declaraba con elección real de idioma, **89 estaban en español y
ninguna en inglés**, y las 13 columnas de Room estaban también en español. Los únicos miembros en
inglés eran sobrescrituras del framework. No "restaures" nombres en inglés: no había ninguno que
restaurar.

## Preparación del dispositivo (teléfono dedicado de producción)

Estos pasos se hacen una sola vez en el dispositivo Android dedicado que recibe los SMS y los
entrega en WhatsApp. Sáltate los que ya estén hechos.

### Por qué hay que desactivar la optimización de batería

El **modo Doze** de Android suspende los procesos en segundo plano cuando la pantalla lleva unos
minutos apagada. WorkManager respeta Doze y puede retrasar `DispatchWorker` durante horas. Eximir a
la app garantiza que cada SMS se entregue a los pocos segundos de llegar, sin importar el estado de
la pantalla ni de la batería.

### Opción A — desde la app (sin USB y sin rutas propias de cada fabricante)

**Ajustes** → **Fiabilidad en segundo plano**. La tarjeta indica si la exención ya está concedida y
el botón abre la pantalla del sistema que la concede. Prueba tres destinos en orden —el diálogo de
exención de un toque, la lista completa de optimización de batería y la ficha de Ajustes de la
app— porque ningún fabricante garantiza el primero. Es la vía preferible en un teléfono que tienes
en la mano; el estado se refresca al volver a la pantalla.

### Opción B — comando ADB (lo más rápido si el teléfono ya está conectado)

```bash
adb shell dumpsys deviceidle whitelist +com.capicua.smstowhats
```

Se ejecuta una vez tras instalar. Para verificar que la app quedó en la lista:

```bash
adb shell dumpsys deviceidle whitelist
# La salida debe incluir una línea con: com.capicua.smstowhats
```

### Opción C — interfaz del dispositivo, a mano

Solo hace falta si el botón de dentro de la app no consigue abrir nada. La ruta exacta varía según
el fabricante; usa la que más se parezca:

**Android puro / Pixel**
1. **Ajustes** → **Aplicaciones** → **SMStoWhats**
2. **Batería** → seleccionar **Sin restricciones**

**Samsung (One UI)**
1. **Ajustes** → **Mantenimiento del dispositivo** → **Batería**
2. **Límites de uso en segundo plano** → **Aplicaciones sin suspender** → **Añadir** → SMStoWhats

**Xiaomi / MIUI / HyperOS**
1. **Ajustes** → **Aplicaciones** → **Administrar aplicaciones** → **SMStoWhats**
2. **Ahorro de batería** → **Sin restricciones**
3. Volver a la ficha de la app → activar **Inicio automático**

**Huawei / EMUI**
1. **Ajustes** → **Aplicaciones** → **SMStoWhats** → **Consumo de batería**
2. Desactivar **Gestión inteligente de energía** y seleccionar **Sin restricciones**

**OnePlus / OxygenOS / ColorOS**
1. **Ajustes** → **Aplicaciones** → **SMStoWhats** → **Batería**
2. **Optimización de batería** → **No optimizar**

> **Nota:** en cualquier fabricante también puedes buscar "Optimización de batería" directamente
> en el buscador de Ajustes, seleccionar **Todas las aplicaciones** y cambiar SMStoWhats
> a **No optimizar**.

### Otros ajustes recomendados (todos los fabricantes)

| Ajuste | Dónde | Valor |
|---------|-------|-------|
| Inicio automático | Ajustes → Aplicaciones → SMStoWhats | **Activado** |
| Ejecutar en segundo plano | Ajustes → Aplicaciones → SMStoWhats → Batería | **Permitido** |
| Optimización de batería | Ajustes → Batería → Optimización → SMStoWhats | **No optimizar** |

---

## Notas importantes

- `libs.versions.toml` es la única fuente de verdad para las versiones de todas las dependencias.
  Retrofit y OkHttp se eliminaron cuando desapareció la capa HTTP; no los vuelvas a añadir.
  `navigation-ui` corrió la misma suerte cuando las cinco pestañas dejaron de ser destinos de
  navegación: ya nadie llama a `setupWithNavController`. `kotlinx.serialization` **sí** se usa, por
  el formato de exportación e importación de reglas.
- **`SEND_SMS` ya no está, y `READ_PHONE_STATE` tampoco.** El primero se fue con el transporte por
  SMS —esta app no envía ningún SMS— y el segundo con el selector de SIM que elegía desde qué
  tarjeta enviar. No vuelvas a añadir ninguno: nada los necesita, y `SEND_SMS` es un permiso
  restringido. Dicho esto, **esto no abre la puerta de Google Play**: `RECEIVE_SMS` y `READ_SMS`
  pertenecen al mismo grupo restringido y siguen exigiendo una declaración aprobada. La distribución
  por APK no se ve afectada.
- **Que no arranque nada tras un reinicio no suele ser un fallo de `BootReceiver`.** El receptor de
  SMS está declarado en el manifiesto, así que el sistema lo arranca por su cuenta — el receptor
  solo reprograma los workers de segundo plano. Dos cosas fuera del alcance de la app lo rompen: la
  lista de **inicio automático** del fabricante (MIUI, HyperOS, EMUI, ColorOS y Funtouch la traen
  vacía, y el permiso no se puede conceder ni consultar desde código — `InicioAutomatico` abre esa
  pantalla y Ajustes lo explica) y el **estado detenido** de Android: una app recién instalada que
  nadie ha abierto todavía no recibe ningún broadcast, SMS incluidos, hasta que alguien la lanza una
  vez. Después de instalar el APK, abre la app una vez.
- **Las reglas son configuración, así que su copia de seguridad está en Ajustes, no en la pestaña
  Reglas.** Un único fichero lleva los dos bloques (`ConfigBackup` + versión de formato **5**): los
  ajustes se **reemplazan** al importar, las reglas se **añaden** — importar no borra nunca, porque
  una importación equivocada que vaciase la lista dejaría el teléfono sin entregar nada. Una regla
  importada cuyo destino no sea un número E.164 válido se rechaza y se cuenta aparte, lo cual es
  nuevo en el formato 5: un `chat_id` no se podía comprobar sin preguntar a Telegram, un número de
  teléfono sí.
- **Ninguna de las dos credenciales viaja en el fichero de copia de seguridad**, y
  `ConfiguracionJson.aDominio()` recibe **los dos** valores actuales como argumentos para que
  importar no los borre. Esos argumentos no son adorno: los ajustes se *reemplazan* al importar, así
  que sin ellos un fichero dejaría las dos vacías y la app muda sin que nadie la tocara. La línea
  entre lo que viaja y lo que no es «¿describe *cómo* reenviar, o identifica a *este* teléfono?» —
  el modo de entrega, el nombre de la plantilla y los dos topes viajan; el token y el
  `phone_number_id` se quedan. El token por la razón obvia (el fichero es JSON en claro y el token
  es permiso para escribir como ese número); el id porque hereda exactamente el papel de
  `simActual`: restaurado en otro teléfono con otra cuenta, enviaría desde donde no es.
- **El formato 5 se niega a importar ficheros del formato 4 o anteriores**, y es la segunda vez que
  una importación de este proyecto rompe a propósito, en espejo de la primera. Un fichero de formato
  4 escrito por SMStoTelegram tiene *exactamente la misma forma*: ningún campo cambió de nombre ni de
  tipo. Lo que cambió es lo que significa `destino` — un `chat_id` allí, un número de teléfono aquí.
  Se importaría sin un solo error de parseo y dejaría un conjunto de reglas cuyos destinos no
  existen, fallando uno a uno cuando llegase un SMS, sin nada que señalase al fichero importado.

  Un fichero de formato **3** se rechaza también, y ese merece decirlo en voz alta porque sus
  destinos *sí* son números de teléfono: son números nacionales, escritos para marcarse desde un
  país, así que entrarían todos sin prefijo de país y fallarían por igual.
- **«Acerca de» lleva un descargo de responsabilidad** junto al aviso de privacidad: la aplicación
  se entrega tal cual y quien la instala y la configura es el único responsable de su uso y de la
  ley que le aplique. En una app que manda los SMS de otras personas a un servicio de terceros, eso
  no es relleno — y el aviso de privacidad es el texto más importante del proyecto. **Con esta
  migración ha empeorado, y lo dice.** Afirma que el cuerpo del SMS llega a los servidores de
  **Meta**; que el mensaje se queda en la conversación de destino y en el teléfono del
  destinatario; que los reenvíos salen de una cuenta de empresa de WhatsApp **registrada a nombre
  de quien opera la app**, así que para Meta y para el destinatario el emisor es esa persona; que
  fuera de la ventana de 24 h los mensajes llegan mediante plantillas aprobadas por Meta **a alguien
  que no escribió primero y no pidió nada**, lo que en los propios términos de Meta es mensajería no
  solicitada y puede acabar con el número limitado o bloqueado; y que el remitente original del SMS
  ni lo sabe ni ha dado su consentimiento. El texto de responsabilidad añade que ni Capicua ni el
  autor responden de lo que Meta facture ni de las restricciones que Meta aplique. **No suavices
  nada de eso.**
- La variante debug usa `applicationId = com.capicua.smstowhats.debug`, así que puede convivir con
  la de release en el mismo dispositivo. **No** comparten base de datos.
- **Todo lo que tenga alcance por instalación debe derivarse de `applicationId`, nunca escribirse a
  mano.** La autoridad del FileProvider estaba escrita como `com.capicua.smstowhats.provider`, de
  modo que ambas variantes declaraban la *misma* autoridad — y una autoridad es única en todo el
  dispositivo, así que Android se negaba a tener las dos instaladas
  (`INSTALL_FAILED_CONFLICTING_PROVIDER`), echando por tierra el sentido mismo del
  `applicationIdSuffix`. Ahora se lee `${applicationId}.provider` en el manifiesto y
  `BuildConfig.APPLICATION_ID + ".provider"` en `Constants`; los dos tienen que ir a la par. El
  síntoma despista: aparece al instalar, nunca al compilar.
- **Aquí el edge-to-edge es obligatorio.** Con `targetSdk 35`, Android 15 dibuja la ventana por
  debajo de las barras de estado y de navegación e ignora `fitsSystemWindows`; no hay forma de
  renunciar. `MainActivity` reparte los insets (`systemBars or displayCutout`) y es el único sitio
  que lo hace, así que cualquier contenedor principal nuevo lo hereda. Sin eso, el recorte de la
  cámara tapaba la cabecera de cada pantalla y la barra de navegación del sistema quedaba **encima**
  de la Bottom Navigation, lo que hacía imposible salir de las pantallas que no están en esa barra.
  `ProbarReglasFragment` y `EditarReglaFragment` llevan además su propio botón de atrás, ya que
  ninguno tiene entrada en la barra inferior.
- **Asunto conocido del toolchain:** el wrapper es Gradle 9.3.0 mientras que AGP es 8.5.2. Compila,
  pero avisa de APIs que desaparecen en Gradle 10 — entre ellas `android.applicationVariants.all`,
  el bloque que renombra el APK de release. Antes de pasar a Gradle 10, o subes AGP o fijas el
  wrapper a Gradle 8.x. Hoy no hay nada roto, así que se ha dejado estar a propósito.
- **`INTERNET` es ahora estructural.** Es por donde se entregan los mensajes, no un extra para la
  comprobación de versión. La frase que vivía aquí —*que esta app no necesite red es parte de lo que
  es*— era de SMStoSMS y ya no aplica.
- **Siete conjuntos de tests unitarios, 134 tests**, todos en Kotlin puro y sin dependencias de
  Android: `EvaluadorDeReglasTest` (44), `MapeoDeRespuestasTest` (24), `PlantillaDeWhatsAppTest`
  (19), `ReglasJsonTest` (14), `CredencialesTest` (12), `DestinoDeWhatsAppTest` (11) y
  `VersionPublicadaTest` (10). `DestinoDeTelegramTest` y `TokenDeBotTest` se fueron con las clases
  que probaban.
  `MapeoDeRespuestas` y `PlantillaDeWhatsApp` se mantienen separados del código que hace la petición
  HTTP **para que puedan probarse sin red**: son las piezas que más importa acertar, y las únicas
  que se pueden probar sin una cuenta. Mantén esa separación. Las reglas de la plantilla en
  particular no se pueden verificar de ninguna otra forma: un salto de línea en un parámetro es un
  `132007`, y los tests cazaron dos fallos reales en `aplanar()` que ninguna lectura habría
  encontrado.
- **Tres ficheros MASTERPLAN recogen las tres migraciones**, fase a fase, con las decisiones
  tomadas, las que se reordenaron sobre la marcha y por qué. `MASTERPLAN-WHATSAPP.md` es el de esta:
  Telegram → Cloud API. `MASTERPLAN-TELEGRAM.md` es el de SMStoSMS → Telegram, y `MASTERPLAN-SMS.md`
  el de la migración anterior desde SMSGateway. Léelos antes de cambiar nada estructural: entre
  ellos explican por qué varias cosas que parecen descuidos son deliberadas.
  `MASTERPLAN-WHATSAPP.md` lleva además dos **correcciones** escritas a posteriori, donde el plan
  resultó estar equivocado una vez escrito el código: el destino de un `4xx` no reconocido, y el
  hecho de que `limiteCaracteres` no podía seguir siendo una propiedad.
- **Montar la cuenta de Meta está documentado en `docs/CUENTA-WHATSAPP-BUSINESS.md`**, y ese es el
  fichero que hay que actualizar cuando Meta mueva un botón — no el README, que solo enumera los
  cinco requisitos previos y explica *por qué* existen. La guía está escrita para quien instala el
  APK, así que nombra pantallas y clics; es el único documento del proyecto que se queda obsoleto
  solo, porque la consola cambia sin que nadie toque este repositorio.
  Cita dos valores que se **derivan en código**, así que cambiar cualquiera de ellos implica cambiar
  la guía: `PlantillaDeWhatsApp.PRESUPUESTO` (978 caracteres, el presupuesto dentro de una
  plantilla) y `AppConfig.maxMensajesPorMes` (900). El presupuesto ya se había desviado una vez
  —`CLAUDE.md` y `docs/ARCHITECTURE.md` decían 992 mientras el código calculaba 978, y no lo cazó
  nadie porque `PlantillaDeWhatsAppTest` solo comprueba `> 900` y `< LIMITE_RENDERIZADO`, nunca el
  literal—. El 18 oct 2026 se corrigieron además dos menciones a 992 que quedaban en el KDoc del
  propio `PlantillaDeWhatsApp`. **Mientras el test no afirme el literal, esto se volverá a desviar.**
