# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Toda la documentación de este proyecto está en español, igual que los comentarios y el KDoc
> del código. La línea de arriba se conserva en inglés porque es la cabecera estándar que
> identifica el fichero.

## Qué es SMSRelay

Recibe los SMS que llegan a la SIM de un teléfono dedicado y los reenvía según reglas. **Cada regla
elige su canal**, y hay cuatro:

| Canal | Entrega en | Transporte | Heredado de |
|---|---|---|---|
| WhatsApp | un número de WhatsApp, por la Cloud API de Meta | `data/whatsapp/WhatsAppSender` | SMStoWhats |
| Telegram | una persona, un grupo o un canal, por un bot | `data/telegram/TelegramSender` | SMStoTelegram |
| SMS | otro teléfono, desde la SIM de este | `data/sms/SmsSender` | SMStoSMS |
| API | una API REST, con un GET a una URL con plantilla | `data/api/ApiSender` | SMSGateway |

Es la unión de las cuatro aplicaciones anteriores de la casa, que eran copias unas de otras con un
medio de salida distinto cada una. SMSRelay nació como copia literal de **SMStoWhats** —la más
reciente— el 25 oct 2026, y las otras tres aportaron **su transporte**, no su esqueleto. El plan de
esa mudanza es `MASTERPLAN-RELAY.md`; los cuatro anteriores viajan con el código como historia
(ver «Los planes», al final).

*Relay* es retransmitir: recibir y pasar a otro sitio sin decir a cuál. Es el primer nombre de la
casa que no lleva el destino dentro, y es a propósito: el próximo canal no obliga a renombrar nada.

## Comandos de compilación y ejecución

> **Toolchain:** el código se compila para JVM 17 con AGP 9.4.1 sobre Gradle 9.8.2, y Gradle se
> ejecuta con **Temurin 25**: `gradle/gradle-daemon-jvm.properties` lo exige, así que vale el JDK
> de `JAVA_HOME` o el que elija Android Studio, siempre que sea el 25. Hace falta el SDK con la
> **plataforma 37** (`compileSdk 37`: `core-ktx` 1.19 no compila contra una inferior).
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
# El APK resultante se genera como smsrelay-<versionName>.apk
./gradlew assembleRelease

# Comprobar que el APK lleva la clave compartida (SHA-256 1C:6C:F6:C6:…:EC:CA)
apksigner verify --print-certs app/build/outputs/apk/release/smsrelay-<versionName>.apk

# Tests unitarios
./gradlew test

# Una sola clase de test
./gradlew test --tests "com.capicua.smsrelay.data.rules.ImportacionDeCopiaTest"

# Conceder permisos en tiempo de ejecución en un dispositivo dedicado (tras instalar)
adb shell pm grant com.capicua.smsrelay android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smsrelay android.permission.READ_SMS
adb shell pm grant com.capicua.smsrelay android.permission.POST_NOTIFICATIONS
# Solo si alguna regla sale por SMS (la app lo pide al elegir ese canal):
adb shell pm grant com.capicua.smsrelay android.permission.SEND_SMS
# Solo para elegir la SIM de envío en un teléfono con dos (lo pide el botón de Ajustes):
adb shell pm grant com.capicua.smsrelay android.permission.READ_PHONE_STATE

# Eximir la app del modo Doze / optimización de batería (OBLIGATORIO para un despacho fiable)
# Sin esto, WorkManager puede posponerse indefinidamente con la pantalla apagada.
# La alternativa por interfaz está en la sección «Preparación del dispositivo», más abajo.
adb shell dumpsys deviceidle whitelist +com.capicua.smsrelay

# Verificar que la app está en la lista de exentas
adb shell dumpsys deviceidle whitelist
# Se espera: una línea que contenga "com.capicua.smsrelay"

# Seguimiento del log en tiempo real
adb logcat -s "SmsReceiver" "SmsIngestionService" "DispatchWorker" "WhatsAppSender" "TelegramSender" "SmsSender" "ApiSender" "OrphanRescueWorker" "HealthMonitorWorker"

# Simular un SMS entrante en un emulador (no hace falta SIM)
adb emu sms send +34600112233 "Tu codigo es 4821"
```

## Publicar una versión

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts` (van juntos: Android se niega a
   instalar un APK cuyo `versionCode` no sea mayor que el instalado).
2. **Escribe la entrada en `CHANGELOG.md`**: lo que está bajo `[No publicado]` pasa a ser la
   versión nueva con su fecha. Ese fichero es el resumen de *qué* ha cambiado; las notas del
   release de GitHub son la versión larga —cómo instalarlo, cómo migrar desde las anteriores— y
   pueden repetirlo.
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/smsrelay-<versionName>.apk`,
   firmado con el almacén compartido.
4. Etiqueta sobre `main` y publica: `gh release create vX.Y.Z --notes-file <notas> <apk>`.

El APK mantiene la misma clave de firma versión tras versión, y es **la misma clave que las cuatro
aplicaciones anteriores**. No sirve para instalar SMSRelay *encima* de ninguna de ellas —el
`applicationId` es otro, así que es otra aplicación—, pero sí para que cada versión de SMSRelay se
instale sobre la anterior sin perder nada.

## El estado

**Las fases 1 a 9 de `MASTERPLAN-RELAY.md` están cerradas (25 oct 2026)**, con 234 tests en verde.
Queda la **fase 10**: ver salir un SMS real por cada canal real, la batería de pruebas de esa fase
y publicar la 1.0.0. Hasta entonces la aplicación está en `1.0.0`/`versionCode 1` sin publicar.

**El repositorio `github.com/jbautistamartin/SMSRelay` no existe todavía** (decisión F del
masterplan). El comprobador de actualizaciones y la tarjeta *Código abierto* apuntan ya a él; un
404 de GitHub se interpreta como «todavía no hay releases», así que mientras no exista la
aplicación simplemente no avisa de nada.

**Qué pasa con las cuatro anteriores** es la decisión D: una última versión de cada una cuya tarjeta
de actualizaciones diga que la aplicación sigue en SMSRelay, y después archivar sus repositorios.
Es trabajo de esos repositorios, no de este, y no está hecho.

## Arquitectura

Clean Architecture con separación estricta en tres capas y MVVM en la capa de presentación.
Inyección de dependencias con Hilt en todo el proyecto.

```
Presentación  (Fragments + ViewModels)
    ↓ usa
Dominio       (UseCases + motor de reglas + modelos de Kotlin puro)
    ↓ usa interfaces, implementadas por
Datos         (Room · DataStore · WorkManager · los cuatro transportes)
```

### Ciclo de vida de un SMS

```
SIM → SmsReceiver.onReceive()            ← BroadcastReceiver (hilo principal, < 10 s)
        ↓ startForegroundService()
      SmsIngestionService                ← ForegroundService (debe llamar a startForeground() < 5 s)
        ↓ ProcesarSmsEntranteUseCase
      INSERT en Room (SmsEntity)         ← outbox: persiste ANTES de hacer nada más
        ↓ guarda antibucle y tope por minuto (globales)
        ↓ EvaluadorDeReglas              ← cada coincidencia lleva su canal y su destino resuelto
        ↓ guardas y topes de canal       ← omiten reenvíos sueltos
      INSERT en Room (ReenvioEntity × N) ← una fila por destino, con su canal copiado
        ↓ ColaDeEnvios.encolar()         ← restricción de red según el canal
      DispatchWorker.doWork()            ← un worker por reenvío, con backoff lineal
        ↓ Transportes.de(reenvio.canal).enviar()
      ┌─ WhatsAppSender   POST graph.facebook.com/v26.0/<phone_number_id>/messages
      ├─ TelegramSender   POST api.telegram.org/bot<token>/sendMessage
      ├─ SmsSender        SmsManager.sendMultipartTextMessage, esperando cada sentIntent
      └─ ApiSender        GET <la URL de la regla, con los marcadores resueltos>
        ↓ actualizar Room
```

**Todo lo que está por encima de la juntura es agnóstico del medio, y esa es la gracia.** El
outbox, la idempotencia de cliente, el rescate de huérfanos, la política de reintentos y el
historial se escribieron para una pasarela HTTP y sobrevivieron a tres mudanzas de medio sin que
nadie los tocara. La cuarta mudanza no cambió el medio: **los juntó**, y tampoco los tocó.

`OrphanRescueWorker` se reencadena cada 30 s y rescata **dos** cosas: SMS que se quedaron en
`PENDIENTE` cuya evaluación de reglas nunca terminó, y reenvíos en `PENDIENTE` sin tarea viva de
WorkManager. Un reenvío lleva su canal, así que el rescate sabe por dónde reintentar sin mirar la
regla.

`HealthMonitorWorker` se ejecuta cada 15 minutos y purga los reenvíos confirmados, los SMS ya
atendidos (de más de `RETENTION_DELIVERED_DAYS`, hoy **30 días**) y las entradas de log antiguas
(de más de `RETENTION_LOGS_DAYS`, también 30 días).

`BootReceiver` reactiva las tareas de WorkManager tras reiniciar el dispositivo.

### La juntura, con cuatro transportes debajo

`domain/transport/EnviadorDeMensajes.kt` es el interfaz que separa la tubería del medio:

```kotlin
suspend fun limiteCaracteres(): Int
suspend fun enviar(destino: String, texto: String, timeoutSegundos: Int): ResultadoEnvio
suspend fun motivoNoDisponible(): String?
```

Su contrato no cambió en cuatro mudanzas: el transporte **espera la confirmación real**, y no
reintenta, no registra en el log y no decide el destino. Lo que cambió en esta es que hay **cuatro
implementaciones** y alguien tiene que elegir entre ellas, y ese alguien es la regla.

- **`Canal`** (`domain/transport/Canal.kt`) es `API`, `SMS`, `TELEGRAM`, `WHATSAPP`, cada uno con su
  `etiqueta`. `Canal.POR_DEFECTO` es `WHATSAPP`, y es el único sitio donde está escrito: lo leen el
  modelo de regla, la entidad de Room y el editor.
- **`di/TransportModule`** sigue siendo la única clase del proyecto que nombra un transporte
  concreto, ahora como un *multibinding* de Hilt: cuatro `@Binds @IntoMap` con la clave
  `@ClaveCanal`, que producen un `Map<Canal, EnviadorDeMensajes>`.
- **`Transportes`** envuelve ese mapa. `de(canal)` devuelve null en lugar de lanzar si un canal no
  tiene transporte —eso no es un error de programación: pasa mientras se escribe uno, y pasaría si
  algún día se retira—, y `motivoSinTransporte(canal)` lo convierte en un texto legible.
- **`DispatchWorker`** pide `transportes.de(reenvio.canal)` y no sabe nada más.
- **`ColaDeEnvios`** elige la restricción de red por canal: WhatsApp y Telegram necesitan red; el
  SMS y la API **no** (`NOT_REQUIRED`) — el SMS sale por la radio, y la falta de cobertura ya la
  reporta el envío; una API puede estar en la red local, y Android no marca como «con internet»
  una red sin salida, así que la restricción bloquearía justo ese caso.
- **`motivoNoDisponible()`** es lo que alimenta los avisos: el del Panel combina los motivos de los
  canales que usan las reglas activas, prefijados con la etiqueta del canal; la tarjeta *Canales*
  de Ajustes enseña los cuatro; y la importación de una copia avisa de los que no pueden entregar.

**`ResultadoEnvio` no se tocó.** `Enviado`, `ErrorTransitorio(motivo, esperaSegundos)` y
`ErrorPermanente` ya servían a los cuatro: `esperaSegundos` se escribió para el `retry_after` de
Telegram, que volvió con él; con WhatsApp, el SMS y la API llega null.

### Mecanismo de reenvío

Quien opera la app configura una lista ordenada de **reglas**; cada regla decide, para un SMS
entrante, **por qué canal**, **a qué destino** y **con qué texto** sale.

Una regla casa cuando se cumplen **los dos** criterios. Un criterio vacío se cumple siempre, así
que una regla con ambos vacíos casa con todos los SMS —que es exactamente lo que hacía SMSGateway—:

- `regexTelefono` — se aplica al remitente **tal como lo entrega la PDU**, sin normalizar. Puede
  ser alfanumérico (`BANCO`, `AMAZON`).
- `regexMensaje` — se aplica al cuerpo completo del SMS.

La coincidencia es **parcial** (`Regex.containsMatchIn`): `codigo` casa con "Tu codigo es 4821".
Ancla con `^…$` para exigir el texto entero. Los patrones distinguen mayúsculas salvo que la regla
active `ignorarMayusculas`, que aplica `RegexOption.IGNORE_CASE` a **las dos** expresiones a la
vez; un `(?i)` en línea sigue funcionando y los dos son acumulativos.

Las reglas se evalúan por `orden` ascendente. La primera que casa gana y la evaluación se detiene,
**salvo** que la regla tenga `continuar = true`. Con cuatro canales eso es más útil que nunca: un
mismo SMS puede salir por Telegram y por SMS a la vez, con dos reglas.

#### El destino, según el canal

El mismo campo `destino` significa una cosa distinta en cada canal, y el editor valida la forma
del canal elegido:

| Canal | Qué es el destino | Validación | Marcadores |
|---|---|---|---|
| WhatsApp | Un número **E.164**: `+`, prefijo de país, de 8 a 15 dígitos | `DestinoDeWhatsApp` | Ninguno |
| Telegram | Un `chat_id` (negativo para grupos y canales) o el `@usuario` de un canal público | `DestinoDeTelegram` | Ninguno |
| SMS | Un número tal como se marcaría, nacional o internacional | dígitos y `+ -()`, al menos 4 | `{telefono}` **solo, como destino entero**: contesta a quien escribió |
| API | Una URL `http(s)` con `{mensaje}` | `PlantillaDeUrl.validar()` | `{mensaje}`, `{telefono}`, `{fecha}`, **nunca en el servidor** |

**El destino se resuelve en `EvaluadorDeReglas`, no en el transporte.** El transporte recibe un
destino y un texto ya hechos, que es lo que dice su contrato.

**Que el cuerpo de un SMS no pueda decidir adónde se manda nada es una propiedad de seguridad**, y
es la razón de los límites de la tabla:

- WhatsApp y Telegram no resuelven ningún marcador en el destino.
- En SMS solo vale `{telefono}` como destino **entero** —contestar a quien escribió, que era la
  función original de SMStoSMS—, y la coincidencia queda marcada `respondeAlRemitente`. El editor
  rechaza `{telefono}` mezclado con más texto.
- En la API los tres marcadores van en la ruta o en la consulta, URL-codificados (`URLEncoder`, con
  `+` convertido en `%20`), y `PlantillaDeUrl.validar()` **rechaza cualquier marcador en la
  autoridad** —entre `://` y el primer `/`, `?` o `#`—: un SMS no puede elegir servidor. SMSGateway
  sí lo permitía; aquí una URL así no se puede guardar, y al importarla se cuenta como rechazada.
  `{fecha}` en la URL va en **ISO 8601 UTC**, como en SMSGateway, porque la lee una máquina.

**El `+` inicial es obligatorio en WhatsApp, y eso es una decisión de diseño, no una exigencia de la
API.** `600112233` son nueve dígitos que podrían ser un móvil español sin prefijo o un número
internacional completo de un país de prefijo corto. Con el `+` obligatorio la pantalla puede decir
lo que de verdad lo arregla —*«falta el prefijo internacional, escribe +34…»*—. En SMS, en cambio,
un número nacional es justo lo correcto: es lo que la SIM sabe marcar.

**El editor detecta el destino de otro canal.** El error más probable de una aplicación con cuatro
canales en el mismo campo es pegar un `-1001234567890` con WhatsApp o SMS marcado.
`DestinoDeTelegram.esInequivocamenteChat()` lo reconoce —un id negativo o un `@usuario`, nunca un
id positivo, que podría ser un teléfono— y el mensaje dice *cambia el canal* en lugar de *no tiene
forma de teléfono*.

**Los destinos de WhatsApp son siempre personas individuales, nunca grupos.** La Cloud API admite
grupos desde 2026, pero solo los que ella misma haya creado, por invitación, de hasta ocho
participantes y para una Official Business Account. Para grupos está Telegram.

#### La plantilla del texto

El texto que se entrega se compone con la `plantilla` de la regla, con tres marcadores opcionales:

- `{mensaje}` — el cuerpo del SMS recibido
- `{telefono}` — el número del remitente
- `{fecha}` — fecha de recepción, formateada en hora **local** (la lee una persona)

`{mensaje}` se sustituye **el último** a propósito, para que el cuerpo del SMS —contenido de
terceros— no se vuelva a escanear y no pueda inyectar marcadores.

**La plantilla por defecto depende del canal** (`Regla.plantillaPorDefecto(canal)`):

- `{telefono} · {fecha}\n{mensaje}` para WhatsApp, Telegram y SMS. Por WhatsApp y por Telegram todo
  llega del mismo emisor, y por SMS del número de la pasarela, así que sin el remitente dentro
  cuatro avisos seguidos son cuatro mensajes indistinguibles.
- `{mensaje}` para la API (`PLANTILLA_POR_DEFECTO_API`): el texto viaja dentro de la URL, la URL ya
  lleva sus propios `{telefono}` y `{fecha}` donde la API los espere, y repetirlos dentro del
  mensaje solo ensucia lo que recibe una máquina.

Al cambiar de canal en una regla **nueva**, el editor cambia la plantilla por la del canal si
seguía siendo la de otro; en una regla existente no toca nada.

### Los cuatro canales

#### WhatsApp — texto libre, plantillas, y por qué las plantillas son el camino principal

**Es el canal con más trampas, y esta es la sección que hay que leer antes de tocar su entrega.**
La Cloud API tiene dos clases de mensaje y no son intercambiables:

| | Mensaje de servicio (texto libre) | Mensaje de plantilla |
|---|---|---|
| Cuándo se puede enviar | Solo dentro de una **ventana de 24 h** que abre el destinatario escribiendo al número emisor | **Siempre**, sin contacto previo |
| Contenido | Texto libre, hasta **4.096** caracteres | Un cuerpo aprobado por Meta, hasta **1.024** renderizados |
| Saltos de línea en la parte variable | Sí | **No** — ni tabuladores, ni más de 4 espacios seguidos |
| Coste | Los primeros **1.000 al mes y por número** gratis; el resto se factura desde el 1 oct 2026 | **Todos** se facturan |

Un reenviador de SMS es por definición un emisor **no solicitado**: el SMS llega cuando llega y
tiene que salir entonces. Por tanto:

> **Las plantillas no son el plan B de este canal. Son el camino principal.** El texto libre es la
> optimización que aplica cuando la ventana resulta estar abierta.

**El contrato de la plantilla es una sola variable**, y el cuerpo que quien opera tiene que dar de
alta en el Business Manager es exactamente `PlantillaDeWhatsApp.CUERPO_RECOMENDADO`:

```
Reenvío de SMS

{{1}}

— SMSRelay
```

1. **Los saltos de línea viven en la plantilla, no en la variable.** Lo que no puede contener un
   salto de línea, un tabulador ni más de cuatro espacios seguidos es el *valor* de `{{1}}`:
   hacerlo devuelve un `132007` y mata el reenvío. De ahí `aplanar()`, que convierte los saltos de
   línea en ` · ` y colapsa el resto.
2. **Ni empieza ni termina con la variable**, cosa que Meta no permite.
3. **Una variable y no tres.** Con tres habría una segunda vía de composición del mensaje que
   ignoraría la `plantilla` de cada regla. Con una, `EvaluadorDeReglas` compone el texto en los dos
   modos y la plantilla de la regla sigue significando lo mismo.

`PRESUPUESTO` se deriva de ese contrato (1.024 menos el texto fijo menos un margen) porque la
aplicación no conoce el cuerpo aprobado: vive en la cuenta de Meta. **Hoy vale 980**, y
`PlantillaDeWhatsAppTest` afirma el literal. Subió de 978 a 980 al cambiar la firma de
`— SMStoWhats` a `— SMSRelay`; una plantilla aprobada con la firma vieja sigue sirviendo, porque le
sobran dos caracteres de margen.

`ModoDeEntrega` tiene tres valores. `AUTOMATICO` prueba el texto libre y, ante un `131047`, reenvía
como plantilla **dentro del mismo intento del worker** — no como reintento, porque la ventana
seguiría cerrada diez intentos después. El worker no se entera: el modo de entrega es del
transporte.

**`preview_url` va en `false`, y eso no es cosmética.** Dejado en true, WhatsApp **descarga** el
primer enlace del cuerpo para su vista previa, y el cuerpo es el SMS de un tercero: los servidores
de Meta irían a buscar una URL elegida por quien mandó ese SMS. No es configurable.

WhatsApp **interpreta `*negrita*` y `_cursiva_` siempre**; un SMS con asteriscos llegará con media
frase en negrita. Se acepta: escapar significaría interpretar contenido de terceros para
modificarlo.

**La correspondencia de errores** vive en `data/whatsapp/MapeoDeRespuestas.interpretarEnvio()`, y
aquí el código HTTP no basta: la Cloud API mete **cuatro fallos distintos dentro del mismo `400`** y
los distingue en `error.code`.

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

- **Un `100` es un `phone_number_id` incorrecto**, no un recurso que falta: lo que no existe es la
  ruta, y la ruta se construye con la credencial. Solo los fallos de credencial encienden el aviso
  del Panel, a través de `EstadoDeLaCuenta`.
- **Un `4xx` no reconocido se cierra como permanente**: un reintento manda los mismos bytes a la
  misma URL. El motivo lleva el `error.code`, que es con lo que se busca en la documentación.
- **El `131047` no se clasifica, se intercepta** con `esVentanaCerrada()`, para que el worker no se
  entere de que hay dos formas de entregar.

Por encima de 4.096 caracteres (texto libre) o de 980 (plantilla) el texto se **trocea** cortando
por el último salto de línea o espacio — **y trocear un mensaje de plantilla cuesta dinero**, uno
por trozo, que es por lo que se registra a nivel de aviso.

Montar la cuenta de Meta está en `docs/CUENTA-WHATSAPP-BUSINESS.md`.

#### Telegram — un bot

- **Credencial: `AppConfig.tokenBot`.** `TokenDeBot` valida su forma sin red; `ComprobadorDelBot`
  pregunta a `getMe` desde Ajustes; `EstadoDelBot` recuerda un token rechazado para que el Panel lo
  diga en rojo.
- **`DescubridorDeChats`** existe porque nadie conoce su propio `chat_id`: pregunta a `getUpdates`
  quién ha escrito al bot y el editor ofrece la lista (*Elegir de los chats del bot*). Un bot no
  puede escribir primero, y Telegram solo guarda esos mensajes 24 h.
- **Sin `parse_mode`.** Con él, cualquier SMS con `_`, `*`, `[` o `<` volvería como `400 can't parse
  entities` y mataría el reenvío porque alguien quería negrita.
- **`retry_after` duerme al worker.** `data/telegram/MapeoDeRespuestas` lo copia a
  `ErrorTransitorio.esperaSegundos`, y `DispatchWorker.esperarSiLoPiden()` duerme lo pedido, hasta
  `MAX_ESPERA_SEGUNDOS` (120): WorkManager cuenta su backoff desde que el worker termina, así que
  dormir es la única forma de retrasar el siguiente intento.
- Un `404` de la Bot API es **el token**, no el chat: la ruta lleva el token dentro.
- 4.096 caracteres por mensaje; lo que pase se trocea.

Preparar el bot está en `docs/BOT-TELEGRAM.md`.

#### SMS — desde la SIM de este teléfono

- **`SmsSender` espera el `sentIntent` de cada parte.** `sendMultipartTextMessage` retorna antes de
  que el mensaje salga del dispositivo; si `enviar()` retornase entonces, todo parecería entregado.
  Suspende hasta que llegan todas las confirmaciones o vence `timeoutEnvioSegundos`.
- **Más de 160 caracteres GSM-7 se fragmenta** con `divideMessage`, y el destinatario lo ve como un
  solo mensaje. `limiteCaracteres()` devuelve 160 para que *Probar reglas* pueda decir cuántas
  partes —y cuántos SMS facturados— va a costar.
- **La SIM de envío** es `AppConfig.subscriptionId` (`SIM_POR_DEFECTO = -1` deja elegir al sistema).
  `ProveedorDeSims` las enumera, y para eso hace falta `READ_PHONE_STATE`.
- **Los permisos se piden cuando hacen falta, no al instalar.** `SEND_SMS` al elegir el canal SMS en
  el editor, o desde la sección *SMS* de Ajustes; `READ_PHONE_STATE` solo al pulsar *Elegir la SIM
  de envío*. Quien no use el canal SMS no ve nunca ninguno de los dos diálogos. Los dos están en el
  manifiesto, y eso tiene un precio que el masterplan reconoce: se perdió el argumento de permisos
  mínimos que tenían SMStoTelegram y SMStoWhats.
- Sin `SEND_SMS` concedido, `motivoNoDisponible()` lo dice, y el aviso llega al Panel y a Ajustes.

#### API — un GET a una URL con plantilla

- **El destino de la regla es la URL** (decisión A del masterplan): cada regla puede apuntar a una
  API distinta, y eso es lo que hace de la API un canal y no un ajuste global como en SMSGateway.
- `ApiSender` hace un `GET` con `HttpURLConnection`, **sin seguir redirecciones**
  (`instanceFollowRedirects = false`): seguir un 3xx mandaría el SMS a un sitio que nadie
  configuró. Un 3xx es `ErrorPermanente`.
- **`AppConfig.aceptarCertificadosInvalidos`** instala un `SSLSocketFactory` que acepta cualquier
  certificado, para APIs en la red local con certificados propios. Es global, no por regla, como
  en SMSGateway, y el aviso de privacidad dice lo que cuesta.
- **El log nunca lleva la URL entera**: `PlantillaDeUrl.sinQuery()` le quita la consulta, que es
  donde van el mensaje y, a menudo, una clave de la API.
- La correspondencia de `data/api/MapeoDeRespuestas`: 2xx `Enviado`; 429 transitorio; 3xx y 4xx
  permanentes; 5xx transitorio; cualquier otra cosa, transitorio.
- **El texto admite 1.500 caracteres, y pasarse no trocea**: una petición por trozo no es lo que
  espera ningún servidor. URL-codificado, un acento ocupa seis, así que el editor avisa pronto.

### Los topes y las guardas

Hay dos topes y dos guardas, y desde SMSRelay **no todos son de todos los canales**:

| Protección | Alcance | Dónde | Qué hace al saltar |
|---|---|---|---|
| Tope por minuto (`maxReenviosPorMinuto`, 10) | **Global** | Antes de evaluar | Descarta el SMS |
| Tope mensual (`maxMensajesPorMes`, 900) | **Solo WhatsApp** | Tras evaluar, por reenvío | Omite los reenvíos de WhatsApp; los demás siguen |
| Guarda antibucle: el remitente es un destino SMS | Solo SMS | Antes de evaluar | Descarta el SMS |
| Guarda antibucle: una regla SMS que reenviaría al propio remitente | Solo SMS | Tras evaluar, por reenvío | Omite ese reenvío |

Las dos guardas dependen de `AppConfig.protegerBucles` (activado por defecto) y usan
`NormalizadorTelefono` para comparar números. Una tercera comprobación no es una guarda sino una
evidencia: una regla que contesta a un remitente **alfanumérico** (`BANCO`) se omite, porque a una
cabecera no se le puede mandar un SMS.

- **Las guardas solo miran el canal SMS.** Un mensaje de WhatsApp o de Telegram no vuelve nunca como
  SMS, y una URL tampoco: no hay círculo que cerrar. SMStoTelegram y SMStoWhats las borraron por eso,
  y el razonamiento sigue en pie para esos tres canales. Lo que volvió con el canal SMS fue el
  círculo, y con él las guardas — **solo para él**.
- **El tope por minuto es global** porque corta una ráfaga de SMS entrantes, y eso se ve sin saber
  por dónde va a salir cada uno. Es su cuarto significado: cortafuegos de factura en SMSGateway y
  SMStoSMS, límite de la Bot API en Telegram, límite y coste en WhatsApp, y ahora todos a la vez.
- **El tope mensual es solo de WhatsApp** (decisión B) porque mide **dinero de Meta**: Meta regala
  los primeros 1.000 mensajes de servicio al mes y factura todos los de plantilla, y pasarse no da
  ningún error, da una factura. Telegram no factura, el SMS lo cobra el operador con su tarifa y la
  API es de quien la configura. Agotar el mes de WhatsApp **no** deja de reenviar por los demás.
  `0` lo desactiva.

`ContadorDeMensajes` cuenta **mensajes de WhatsApp entregados** —no reenvíos: un SMS largo se
trocea y Meta factura por mensaje—, en el DataStore de ajustes y bajo sus propias claves. El mes
cambia en la misma transacción que el incremento, sin tarea programada. El Panel lo muestra **solo
si hay alguna regla activa por WhatsApp**; sin ninguna, la cifra no significa nada.

Todo lo que dispara un tope o una guarda queda como `LogTipo.LIMITE_ALCANZADO`, con su canal cuando
es por reenvío, y el detalle **empieza por el nombre** de lo que saltó: *Tope por minuto*, *Tope
mensual de WhatsApp* o *Guarda antibucle de SMS*.

### Ajustes, Panel, Logs y Probar reglas

- **Ajustes** tiene una sección por canal —*Cuenta de WhatsApp* y *Entrega*, *Bot de Telegram*,
  *SMS*, *API*— y, por encima, la tarjeta **Canales**, que enseña los cuatro con su estado
  (`motivoNoDisponible()`) y cuántas reglas activas usa cada uno (`SettingsViewModel.estadoCanales`).
  Se refresca al volver a la pantalla, porque los permisos y las credenciales cambian fuera.
- **Guardar parte de `configCargada.copy(...)`, no de `AppConfig()`.** Con cuatro secciones, un
  ajuste que la pantalla no pinta se perdería en cada «Guardar».
- **El Panel** avisa en rojo de los canales usados por reglas activas que no pueden entregar,
  diciendo **cuál**: *Telegram: falta el token del bot*. Un fallo de Telegram no tapa WhatsApp.
- **Logs** tiene dos filtros que se combinan: el tipo y el canal
  (`LogDao.observarFiltrados(tipo, canal, limite)`). `LogEntry.canal` es nulo en lo que no es de
  ningún canal, como un SMS recibido.
- **La lista de reglas** enseña «Canal · destino», y la URL de la API sin consulta.
- **Probar reglas** dice por qué canal saldría cada coincidencia, cuántos caracteres admite ese
  canal, y avisa de lo que no saldría: una URL inválida, una regla SMS que contestaría a un
  remitente alfanumérico.

### Navegación por pestañas

Las cinco pantallas principales —Panel, Reglas, Logs, Ajustes y Acerca de— son **páginas de un
`ViewPager2`**, no destinos del grafo de navegación, así que a cada una se llega tanto tocando la
Bottom Navigation como deslizando.

`nav_graph.xml` contiene por tanto solo tres destinos: `homeFragment`, que hospeda el pager, más
las dos subpantallas de reglas `editarReglaFragment` y `probarReglasFragment`. Las dos acciones
cuelgan de `homeFragment`, porque cuando `ReglasFragment` navega, el destino actual del
NavController es el que hospeda el pager.

`Pestana` (`presentation/home/Pestana.kt`) es la única fuente de verdad que ata la posición en el
pager, el id del menú y el Fragment. El orden de declaración **es** el orden de las páginas, y
`bottom_nav_menu.xml` tiene que listar sus elementos en ese mismo orden.

La barra es de `MainActivity` y el pager de `HomeFragment`, y no comparten tiempo de vida, así que
se coordinan a través de `PestanasViewModel`, un `StateFlow` con alcance de actividad. Volver a
seleccionar la pestaña activa no emite nada, y eso evita que la sincronización en dos sentidos se
realimente. Atrás en cualquier pestaña que no sea Panel vuelve a Panel.

**Un hijo con desplazamiento horizontal dentro del pager necesita `NestedScrollableHost`**
(`presentation/common/NestedScrollableHost.kt`). Si no, los dos gestos son el mismo y gana el
pager. `fragment_logs.xml` lo usa para sus **dos** carruseles de filtros; cualquier desplazador
horizontal nuevo en una pestaña necesita el mismo envoltorio.

**El interruptor general de la recepción está en Ajustes**, arriba del todo. Apagarlo desactiva el
**componente** `SmsReceiver` a través del `PackageManager` (`util/ControlRecepcion`): el sistema
deja de entregar el broadcast, en lugar de despertar a la app con cada SMS para descartarlo. Detiene
solo el lado entrante; lo ya encolado se sigue reenviando. El estado del componente sobrevive a los
reinicios, así que `SmsRelayApplication.sincronizarRecepcion()` solo importa tras una
reinstalación.

### «Acerca de»

Lleva las mismas seis tarjetas, en el mismo orden, que las cuatro aplicaciones anteriores:
**marcadores, privacidad, responsabilidad, actualizaciones, código abierto y donación**.

- **El aviso de privacidad es el texto más importante del proyecto, y habla de los cuatro
  canales.** Lo común primero —el SMS sale del dispositivo, el remitente original no lo sabe ni ha
  dado su consentimiento, el registro local— y un párrafo por canal. El de WhatsApp se conserva
  **entero** desde SMStoWhats: los servidores de Meta, la cuenta de empresa **registrada a nombre de
  quien opera** (ante Meta y ante el destinatario el emisor es esa persona), las plantillas que
  llegan **a alguien que no escribió primero ni pidió nada** —mensajería no solicitada en los
  términos de Meta, que puede limitar o bloquear el número—. El de Telegram dice que los mensajes
  pasan por sus servidores y que en un grupo o canal los lee todo el que esté dentro, también quien
  entre después; el de SMS, que pasan por el operador, que los cobra, y que el destinatario ve el
  número de la pasarela; el de la API, que el contenido va en la URL a un servidor que elige quien
  opera y queda en sus registros, y lo que supone aceptar certificados no válidos. **No suavices
  nada de eso.**
- **El descargo de responsabilidad** dice que la aplicación se entrega tal cual y que ni Capicua ni
  el autor responden de lo que Meta facture o restrinja, de lo que cobre el operador, de lo que
  Telegram haga con el bot ni de lo que haga el servidor de la API.
- **El enlace de donación** abre `https://paypal.me/jbautistamartin` (`URL_DONATIVO` en
  `AboutFragment`). El corazón del botón (`drawable/ic_corazon.xml`) es el mismo trazado dorado que
  el de SilentRunner y lleva `app:iconTint="@null"`; el de *Código abierto*
  (`drawable/ic_codigo.xml`) se deja teñir. Los dos pasan por `abrirEnlace(url)`, que captura
  `ActivityNotFoundException` en lugar de preguntar antes al `PackageManager` (la visibilidad de
  paquetes de Android 11 puede mentir) y comparten el Snackbar `about_sin_navegador`.
- **La comprobación de versión nueva** pregunta a `api.github.com` por el último release de
  `jbautistamartin/SMSRelay`:
  - `MainActivity` pregunta al arrancar y, si hay versión nueva, pone una **marca en la pestaña** —
    ni diálogo ni notificación: en un teléfono que nadie mira, lo que se ponga delante tapa lo que
    venías a ver.
  - **Una pregunta al día, con `If-None-Match`**: la API sin autenticar permite 60 peticiones por
    hora, y un 304 no cuenta. El estado vive en `EstadoActualizacion`, en el mismo DataStore pero
    **fuera de `AppConfig`**, porque `AppConfig` viaja en la copia de seguridad.
  - **Lo encontrado se recuerda**, no solo cuándo se preguntó; se olvida solo cuando la versión
    instalada deja de perder.
  - **Las versiones se comparan como números** (`VersionPublicada`, con tests): `"1.10.0" < "1.9.0"`
    alfabéticamente.
  - **Un 404 significa que no hay releases.** Hoy, que el repositorio no existe, es lo que pasa.
  - `DownloadManager` descarga el APK y el instalador del sistema lo instala;
    `REQUEST_INSTALL_PACKAGES` exige además autorizar a *esta* app, y se comprueba **antes** de
    descargar. **La firma es el modelo de seguridad**: Android solo actualiza un APK firmado con la
    misma clave.

### La copia de seguridad — formato 6

Las reglas son configuración, así que su copia está en **Ajustes**, no en la pestaña Reglas. Un
único fichero lleva los ajustes y las reglas (`ConfigBackup` + `data/rules/ReglasJson.kt`).

- **Cada regla lleva su `canal`.** Es lo único que cambió de forma respecto al formato 5.
- **Las reglas se añaden**, nunca se sustituyen: una importación equivocada que vaciase la lista
  dejaría el teléfono sin entregar nada.
- **Los ajustes ya no se sustituyen enteros**, que era la regla de las cuatro anteriores.
  `ConfiguracionJson.aplicarSobre(actual, canales)` aplica los **comunes** y **los del canal del
  fichero**, y deja los demás: importar la copia de SMStoTelegram no puede dejar a cero el modo de
  entrega de WhatsApp. Los campos de cada canal son opcionales en el JSON por eso mismo.
- **Las credenciales no viajan** salvo que quien exporta marque la casilla —el fichero es JSON en
  claro—: el token y el `phone_number_id` de WhatsApp y el token del bot. **Una credencial ausente o
  en blanco no pisa la del teléfono.** La **SIM de envío no viaja nunca**: apunta a una tarjeta de
  *este* teléfono. La línea es la de siempre: *¿describe cómo reenviar, o identifica a este
  teléfono?*
- **Una regla cuyo destino no valida para su canal se rechaza y se cuenta aparte**, igual que una con
  una expresión que no compila o con un canal desconocido. Una regla que entra en silencio y no
  entrega nunca es mucho peor que una rechazada.

**Y es la vía de migración desde las cuatro.** El número de formato, que las dos veces anteriores
sirvió para *rechazar* ficheros —el mismo `destino` significaba otra cosa—, ahora **dice qué
aplicación lo escribió, y por tanto qué canal tiene cada destino**. Lo decide
`data/rules/ImportacionDeCopia`, Kotlin puro y con un fichero real de cada aplicación en sus tests:

| Fichero de | Formato | Canal de las reglas | Ajustes que aplica |
|---|---|---|---|
| SMSRelay | 6 | El de cada regla | Todos |
| SMStoWhats | 5 | WhatsApp | Comunes y de WhatsApp |
| SMStoTelegram | 4 | Telegram | Comunes y de Telegram |
| SMStoSMS | 1, 2 y 3 | SMS | Comunes y `protegerBucles` (el 1 y el 2 no traen ajustes) |
| SMSGateway | su propio 1 | API: **una** regla que casa con todo, destino la `urlTemplate`, plantilla `{mensaje}` | Comunes y `aceptarCertificadosInvalidos` |

- **SMSGateway se reconoce por su forma** —`urlTemplate` y ninguna `reglas`—, no por el número: un
  `version: 1` también puede ser de SMStoSMS. Su `timeoutSegundos` pasa a `timeoutEnvioSegundos`.
- **Los formatos 1 a 3 de SMStoSMS se aceptan**, al contrario que en SMStoWhats, que rechazaba el 3
  porque sus números nacionales no servían para WhatsApp. Aquí son reglas del canal SMS, y para la
  SIM un número nacional es justo lo correcto. El formato 1 no tenía `ignorar_mayusculas`: sus
  reglas entran distinguiendo mayúsculas, que es lo que hacían.
- **Al terminar, la pantalla dice de qué aplicación venía la copia**, y si alguno de los canales
  importados no puede entregar todavía —lo normal, porque las credenciales no viajan—, un diálogo
  lo dice canal por canal: *«3 regla(s) por Telegram: falta el token del bot»*. Es un diálogo y no un
  Snackbar porque es lo siguiente que hay que hacer.

Exportar en la aplicación vieja, importar en SMSRelay, rellenar credenciales, **y apagar la
recepción de la vieja**: con las dos activas, cada SMS se reenvía dos veces. Es lo correcto —son
dos aplicaciones— y es la razón de ese último paso.

### Ficheros clave

| Fichero | Cometido |
|------|------|
| `domain/transport/EnviadorDeMensajes.kt` | **La juntura.** Lo único que la tubería sabe de un medio |
| `domain/transport/Canal.kt` | Los cuatro canales y `POR_DEFECTO` |
| `domain/transport/Transportes.kt` | El mapa canal → transporte, con un motivo legible si falta uno |
| `domain/transport/ResultadoEnvio.kt` | `Enviado` / `ErrorTransitorio` / `ErrorPermanente` — toda la política de reintentos |
| `di/TransportModule.kt` | El multibinding: la única clase que nombra un transporte concreto |
| `domain/rules/EvaluadorDeReglas.kt` | Coincidencia, plantillas, resolución del destino por canal, validación de patrones |
| `domain/rules/PlantillaDeUrl.kt` | **Puro.** Validar y resolver la URL del canal API. Con tests |
| `domain/rules/NormalizadorTelefono.kt` | **Puro.** Comparar números, para las guardas del canal SMS. Con tests |
| `domain/usecase/ProcesarSmsEntranteUseCase.kt` | Guardas y topes → evaluar → crear reenvíos → encolar |
| `data/whatsapp/WhatsAppSender.kt` | El `POST` a `/messages`, en los dos modos de entrega |
| `data/whatsapp/MapeoDeRespuestas.kt` | **Puro:** (HTTP, cuerpo) → `ResultadoEnvio`, guiado por `error.code`, más el troceado. Con tests |
| `data/whatsapp/PlantillaDeWhatsApp.kt` | **Puro.** El contrato de la plantilla, `aplanar()` y el presupuesto. Con tests |
| `data/whatsapp/DestinoDeWhatsApp.kt` | Un número E.164. Con tests |
| `data/whatsapp/TokenDeAcceso.kt`, `IdNumeroTelefono.kt` | Lo que puede decirse de las credenciales sin preguntar a Meta. Con tests |
| `data/whatsapp/ComprobadorDeCuenta.kt`, `EstadoDeLaCuenta.kt` | El botón de Ajustes y el recuerdo de credenciales rechazadas |
| `data/whatsapp/ContadorDeMensajes.kt` | Mensajes de WhatsApp entregados este mes, para el Panel y el tope |
| `data/telegram/TelegramSender.kt` | `sendMessage` de la Bot API |
| `data/telegram/MapeoDeRespuestas.kt` | **Puro:** respuesta de Telegram → `ResultadoEnvio`, con `retry_after`. Con tests |
| `data/telegram/DestinoDeTelegram.kt`, `TokenDeBot.kt` | Forma de un chat y de un token, y `esInequivocamenteChat()`. Con tests |
| `data/telegram/DescubridorDeChats.kt`, `ComprobadorDelBot.kt`, `EstadoDelBot.kt` | `getUpdates`, `getMe` y el recuerdo de un token rechazado |
| `data/sms/SmsSender.kt` | `SmsManager` multiparte, esperando cada `sentIntent` |
| `data/sms/ProveedorDeSims.kt` | Las SIM del teléfono, para el selector de Ajustes |
| `data/api/ApiSender.kt` | El `GET`, sin redirecciones y con los certificados opcionales |
| `data/api/MapeoDeRespuestas.kt` | **Puro:** código HTTP → `ResultadoEnvio`. Con tests |
| `data/config/AppConfig.kt` | Todos los ajustes: credenciales de dos canales, SIM, guarda, certificados, entrega, reintentos, topes |
| `data/config/ModoDeEntrega.kt` | `AUTOMATICO` / `SOLO_TEXTO_LIBRE` / `SOLO_PLANTILLA` |
| `data/config/ConfigBackup.kt` | Exportar e importar: leer y escribir el fichero, y avisar de los canales sin preparar |
| `data/rules/ReglasJson.kt` | El formato 6 y `aplicarSobre` |
| `data/rules/ImportacionDeCopia.kt` | **Puro.** Qué entra de un fichero de cualquiera de las cinco aplicaciones. Con tests |
| `worker/ColaDeEnvios.kt` | El único sitio que encola un despacho; la restricción de red por canal |
| `worker/DispatchWorker.kt` | Entrega un reenvío por su canal; reintentos, fallo y `esperarSiLoPiden` |
| `worker/OrphanRescueWorker.kt` | Rescata SMS y reenvíos atascados cada 30 s |
| `data/local/db/Migraciones.kt` | Migraciones de Room escritas a mano. Vacío: ver «Base de datos» |
| `util/OptimizacionBateria.kt`, `InicioAutomatico.kt`, `ControlRecepcion.kt` | Doze, el inicio automático del fabricante y el interruptor de recepción |
| `data/update/*` | El comprobador de actualizaciones, `VersionPublicada` (con tests), la descarga |

### Idempotencia

De cliente, en dos niveles:

- `ProcesarSmsEntranteUseCase.procesar()` comprueba si el SMS ya tiene reenvíos antes de crear
  ninguno. Eso es lo que hace seguro ejecutar el rescate de huérfanos sobre un SMS a medias.
- `DispatchWorker` comprueba `reenvio.estado` al principio de cada intento y devuelve éxito sin
  tocar el transporte si ya está en `ENVIADO`.

`ColaDeEnvios` usa `ExistingWorkPolicy.KEEP`, así que un reenvío nunca tiene dos tareas
simultáneas. No se da por hecho que ningún servidor deduplique.

### Base de datos Room

Cuatro tablas, versión de esquema **1** — por cuarta vez, y por la misma razón: un `applicationId`
nuevo es otra aplicación con su propio directorio de datos, así que ningún teléfono tiene esta
base.

- `sms` — una fila por SMS recibido, más `estado` (`PENDIENTE`/`PROCESADO`/`SIN_REGLA`/`DESCARTADO`)
  y `motivo_descarte`.
- `reglas` — reglas ordenadas, con `canal` y `ignorar_mayusculas`. `plantilla` **no** tiene valor
  por defecto en SQL ni `canal` tampoco: los dos estarían escritos en dos sitios (ver el KDoc de
  `ReglaEntity`).
- `reenvios` — la unidad de despacho: una fila por destino, con su `canal`, `estado`, `intentos` y
  `ultimo_error`. La FK a `sms` es CASCADE; la FK a `reglas` es SET NULL, y `nombre_regla` y `canal`
  están copiados para que un reenvío encolado sobreviva a la edición o al borrado de la regla.
- `log_entries` — registro de auditoría, con `canal` nulable. Tipos: `SMS_RECIBIDO`,
  `REGLA_APLICADA`, `SMS_REENVIADO`, `SIN_REGLA`, `LIMITE_ALCANZADO`, `ERROR`, `SISTEMA`.

Modo WAL activado. Los esquemas se exportan a `app/schemas/` y se versionan. Sin
`fallbackToDestructiveMigration`.

> **`Migraciones.TODAS` está vacío, y es la última vez que puede estarlo.** En el momento en que la
> app se instale en un teléfono con reglas dentro —la fase 10—, el esquema deja de poder editarse en
> el sitio: cambiar la v1 sin migrar hace que Room aborte al abrir la base de datos. A partir de
> entonces todo cambio sube la versión y escribe su `Migration` en `data/local/db/Migraciones.kt`.

## Convenciones de nombres

El código mezcla idiomas deliberadamente, y el código nuevo debe seguir el mismo reparto:

- **Los miembros** —funciones, propiedades, parámetros, columnas de Room, constantes de enum— van en
  **español**: `guardar`, `observarTodos`, `telefono`, `fecha_recepcion`, `SMS_REENVIADO`. Los únicos
  miembros en inglés son sobrescrituras del framework (`onCreate`, `doWork`, `provideDatabase`).
- **Los nombres de tipo** llevan un **sufijo arquitectónico en inglés** sobre un sustantivo español
  o inglés: `SmsRepository`, `ReglaDao`, `ReenvioEntity`, `SettingsFragment`, `ApiSender`. Los tipos
  de dominio puro pueden ir enteros en español: `LogTipo`, `Canal`, `EvaluadorDeReglas`,
  `ImportacionDeCopia`.
- **Los casos de uso**: una frase verbal en español más `UseCase` — `ProcesarSmsEntranteUseCase`.
- **Los comentarios y el KDoc** van en **español**.
- **Todo fichero `.kt` lleva la cabecera LGPL-2.1 de cuatro líneas**, con `SMSRelay para Android` en
  la primera.

Este reparto se verificó contra el código previo a la primera migración, no se dio por supuesto:
de las 89 funciones del proyecto original con elección real de idioma, 89 estaban en español. No
"restaures" nombres en inglés: no había ninguno que restaurar.

## Preparación del dispositivo (teléfono dedicado de producción)

Estos pasos se hacen una sola vez en el dispositivo Android dedicado que recibe los SMS.

### Por qué hay que desactivar la optimización de batería

El **modo Doze** suspende los procesos en segundo plano cuando la pantalla lleva unos minutos
apagada, y WorkManager lo respeta: `DispatchWorker` puede retrasarse horas. Eximir a la app
garantiza que cada SMS se entregue a los pocos segundos de llegar, por cualquier canal.

### Opción A — desde la app

**Ajustes** → **Fiabilidad en segundo plano**. La tarjeta indica si la exención ya está concedida y
el botón abre la pantalla del sistema que la concede. Prueba tres destinos en orden —el diálogo de
exención de un toque, la lista completa de optimización de batería y la ficha de la app— porque
ningún fabricante garantiza el primero. La misma tarjeta lleva el botón del **inicio automático**
del fabricante.

### Opción B — comando ADB

```bash
adb shell dumpsys deviceidle whitelist +com.capicua.smsrelay
```

### Opción C — interfaz del dispositivo, a mano

**Android puro / Pixel:** Ajustes → Aplicaciones → SMSRelay → Batería → **Sin restricciones**.

**Samsung (One UI):** Ajustes → Mantenimiento del dispositivo → Batería → Límites de uso en segundo
plano → **Aplicaciones sin suspender** → Añadir → SMSRelay.

**Xiaomi / MIUI / HyperOS:** Ajustes → Aplicaciones → Administrar aplicaciones → SMSRelay → Ahorro
de batería → **Sin restricciones**; y en la misma ficha, activar **Inicio automático**.

**Huawei / EMUI:** Ajustes → Aplicaciones → SMSRelay → Consumo de batería → desactivar **Gestión
inteligente de energía** y elegir **Sin restricciones**.

**OnePlus / OxygenOS / ColorOS:** Ajustes → Aplicaciones → SMSRelay → Batería → Optimización de
batería → **No optimizar**.

> En cualquier fabricante se puede buscar "Optimización de batería" en el buscador de Ajustes,
> elegir **Todas las aplicaciones** y poner SMSRelay en **No optimizar**.

---

## Notas importantes

- **Que no arranque nada tras un reinicio no suele ser un fallo de `BootReceiver`.** Dos cosas fuera
  del alcance de la app lo rompen: la lista de **inicio automático** del fabricante (MIUI, HyperOS,
  EMUI, ColorOS y Funtouch la traen vacía, y no se puede conceder ni consultar desde código) y el
  **estado detenido** de Android: una app recién instalada que nadie ha abierto no recibe ningún
  broadcast, SMS incluidos. Después de instalar el APK, abre la app una vez.
- **Ninguna de las cinco aplicaciones de la casa está en Google Play, y esta tampoco puede.**
  `RECEIVE_SMS`, `READ_SMS` y `SEND_SMS` son permisos restringidos que exigen una declaración
  aprobada. La distribución es un APK firmado en GitHub Releases.
- **`libs.versions.toml` es la única fuente de verdad de las versiones.** Retrofit y OkHttp **no**
  están: SMSGateway usaba OkHttp para un único `GET`, y aquí ese GET, las llamadas de Meta, las de
  Telegram y la de GitHub son una docena de líneas de `HttpURLConnection` cada una, con
  `kotlinx.serialization` para las respuestas. `navigation-ui` tampoco: ya nadie llama a
  `setupWithNavController`. El toolchain es el mismo que el de las cuatro anteriores, y se subió en
  todas a la vez el 25 oct 2026.
- **`INTERNET` es estructural** —es por donde salen tres de los cuatro canales—, no un extra para
  la comprobación de versión.
- La variante debug usa `applicationId = com.capicua.smsrelay.debug`, así que convive con la de
  release. **No** comparten base de datos. Y las cuatro aplicaciones anteriores pueden estar
  instaladas a la vez que esta: con la recepción activa en dos, el mismo SMS sale dos veces.
- **Todo lo que tenga alcance por instalación debe derivarse de `applicationId`, nunca escribirse a
  mano.** La autoridad del FileProvider se lee `${applicationId}.provider` en el manifiesto y
  `BuildConfig.APPLICATION_ID + ".provider"` en `Constants`; los dos tienen que ir a la par. Una
  autoridad escrita a mano hace que debug y release no puedan convivir
  (`INSTALL_FAILED_CONFLICTING_PROVIDER`), y el síntoma aparece al instalar, nunca al compilar.
- **El icono naranja es un sistema compartido.** Fondo en degradado derivado de `md_theme_primary`
  (naranja, tono 40: los colores fríos ya eran de las otras cuatro), el bocadillo común con el
  glifo de esta app recortado, y una capa **monocroma**, sin la cual Android 13+ deja el icono
  fuera de los iconos temáticos. **El fondo del icono y el primary del tema se cambian juntos.** Es
  lo que se olvidó en dos mudanzas anteriores y por eso está en la fase 1 del masterplan.
- **Aquí el edge-to-edge es obligatorio.** Con `targetSdk 35`, Android 15 dibuja bajo las barras del
  sistema e ignora `fitsSystemWindows`. `MainActivity` reparte los insets (`systemBars or
  displayCutout`) y es el único sitio que lo hace. `ProbarReglasFragment` y `EditarReglaFragment`
  llevan su propio botón de atrás.
- **AGP 9 cambió tres cosas del build.** Kotlin va integrado en AGP: no hay plugin `kotlin-android`
  y `kotlinOptions` es `kotlin { compilerOptions { … } }`. El renombrado del APK pasa por
  `androidComponents.onVariants` y `VariantOutputImpl`, que es interna: si AGP la cambia, el APK
  vuelve a llamarse `app-release.apk`. Y R8 en modo completo estricto: un `-keep class X` ya no
  conserva el constructor, así que `proguard-rules.pro` lo pide con `{ <init>(); }`.
- **Catorce conjuntos de tests unitarios, 234 tests**, todos en Kotlin puro y sin dependencias de
  Android: `EvaluadorDeReglasTest` (51), `MapeoDeRespuestasTest` de WhatsApp (24),
  `PlantillaDeWhatsAppTest` (20), `MapeoDeRespuestasTest` de Telegram (18),
  `NormalizadorTelefonoTest` (18), `ImportacionDeCopiaTest` (17), `ReglasJsonTest` (14),
  `PlantillaDeUrlTest` (14), `DestinoDeTelegramTest` (12), `CredencialesTest` (12),
  `DestinoDeWhatsAppTest` (11), `VersionPublicadaTest` (10), `MapeoDeRespuestasTest` de la API (7) y
  `TokenDeBotTest` (6). No hay tests de instrumentación. **Los mapeos de respuestas, las plantillas
  y la importación están separados del código que hace la petición o lee el fichero para poder
  probarse sin red ni Android.** Mantén esa separación: son las piezas que más importa acertar.
- **Dos valores derivados en código están escritos también en los documentos**, y cambiar cualquiera
  obliga a cambiar los documentos: `PlantillaDeWhatsApp.PRESUPUESTO` (980) y
  `AppConfig.maxMensajesPorMes` (900), citados aquí, en `docs/ARCHITECTURE.md` y en
  `docs/CUENTA-WHATSAPP-BUSINESS.md`. El presupuesto ya se desvió una vez (992 en los documentos, 978
  en el código) porque ningún test afirmaba el literal; desde la fase 9 de SMSRelay
  `PlantillaDeWhatsAppTest` lo afirma.
- **Las guías de canal viven en `docs/`** y son lo que hay que actualizar cuando una consola cambie
  un botón — no el README, que solo enumera requisitos: `docs/CUENTA-WHATSAPP-BUSINESS.md` (la
  cuenta de Meta, la plantilla, el token permanente) y `docs/BOT-TELEGRAM.md` (BotFather, el
  `/start`, el chat). Son los únicos documentos que se quedan obsoletos solos.

## Los planes

Cinco ficheros MASTERPLAN recogen las cinco etapas del mismo esqueleto, fase a fase, con las
decisiones y las correcciones escritas a posteriori. **Cuando algo parezca raro, la razón suele estar
en el plan de una etapa más antigua.**

| Plan | Qué cuenta |
|---|---|
| `MASTERPLAN-GATEWAY.md` | El origen en SMSGateway: la tubería y por qué es así. Escrito a posteriori (25 oct 2026) |
| `MASTERPLAN-SMS.md` | SMSGateway → SMStoSMS: las reglas y `SmsManager` |
| `MASTERPLAN-TELEGRAM.md` | SMStoSMS → SMStoTelegram: la juntura de transporte |
| `MASTERPLAN-WHATSAPP.md` | SMStoTelegram → SMStoWhats: la Cloud API y las plantillas |
| `MASTERPLAN-RELAY.md` | SMStoWhats → SMSRelay: **los cuatro juntos**. El plan vigente |

Los cuatro primeros se conservan como historia, no como plan vigente. Varias cosas que dicen ya no
son verdad aquí —que no hay `SEND_SMS`, que el destino es siempre un E.164, que las guardas
antibucle no deben volver, que los ajustes se reemplazan al importar— y `MASTERPLAN-RELAY.md`
explica en cada caso qué cambió y por qué. **Lo que diga este fichero gana.**
