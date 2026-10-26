# SMSRelay

[![Última versión](https://img.shields.io/github/v/release/jbautistamartin/SMSRelay?label=descargar&color=E65100)](https://github.com/jbautistamartin/SMSRelay/releases/latest)
[![Licencia](https://img.shields.io/badge/licencia-LGPL--2.1-blue)](LICENSE)
[![Donar con PayPal](https://img.shields.io/badge/Donar-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/jbautistamartin)

Aplicación Android que convierte un dispositivo con SIM en un **repetidor de SMS**: recibe los
mensajes que llegan a su número y los reenvía automáticamente según reglas con expresiones
regulares sobre el remitente y el texto. **Cada regla elige por dónde sale el mensaje**:

| Canal | Entrega en |
|---|---|
| **WhatsApp** | El número de una persona, a través de la Cloud API de Meta y una cuenta de empresa propia |
| **Telegram** | Una persona, un grupo o un canal, a través de un bot |
| **SMS** | Otro teléfono, desde la SIM de este. También puede contestar a quien escribió |
| **API** | Una API REST propia, con una petición GET a una URL con plantilla |

> **SMSRelay junta en una sola aplicación a las cuatro anteriores de la casa:**
> [SMSGateway](https://github.com/jbautistamartin/SMSGateway) (API),
> [SMStoSMS](https://github.com/jbautistamartin/SMStoSMS) (SMS),
> [SMStoTelegram](https://github.com/jbautistamartin/SMStoTelegram) (Telegram) y
> [SMStoWhats](https://github.com/jbautistamartin/SMStoWhats) (WhatsApp). Hace lo mismo que
> cualquiera de ellas, y además lo que ninguna podía: mandar los códigos del banco por Telegram y
> las alertas de la alarma por SMS desde el mismo teléfono. Si vienes de una de ellas, ver
> [Migrar desde las aplicaciones anteriores](#migrar-desde-las-aplicaciones-anteriores).

**Todavía no hay versión publicada.** La 1.0.0 saldrá cuando un SMS real se vea salir por cada uno
de los cuatro canales, que es lo que los tests no pueden demostrar por sí solos. Lo que cambia en
cada versión está en [`CHANGELOG.md`](CHANGELOG.md).

No está en Google Play: `RECEIVE_SMS`, `READ_SMS` y `SEND_SMS` pertenecen a un grupo de permisos
restringido cuya publicación exige una declaración aprobada.

---

## Para qué sirve

El caso típico: un número al que llegan avisos —códigos de verificación, notificaciones de un
proveedor, alertas de un sistema de monitorización— y una o varias personas, o un sistema, que
necesitan recibirlos en otro sitio.

Las reglas separan ese tráfico: los SMS del banco a una persona por WhatsApp, los de la alarma a un
grupo de Telegram, los de un proveedor a la API de tu sistema de tickets, y el resto a ninguna
parte. Con **Seguir evaluando**, un mismo SMS puede salir por varios canales a la vez.

**Qué canal conviene**, en una línea cada uno:

- **Telegram** es el más fácil de montar —un bot en un minuto, gratis, sin límites que importen— y
  el único que entrega en grupos.
- **WhatsApp** llega a donde la gente ya mira, pero exige una cuenta de empresa de Meta, un número
  dedicado y plantillas aprobadas, y se factura. Lee
  [Lo que WhatsApp no te deja hacer](#lo-que-whatsapp-no-te-deja-hacer) antes de elegirlo.
- **SMS** no necesita internet ni cuentas de nadie, y cuesta lo que cueste un SMS en tu tarifa.
- **API** es para máquinas: un `GET` a la URL que quieras, con el texto, el remitente y la fecha
  donde los espere tu servidor.

Todos menos el SMS sacan **el contenido de los SMS a internet**. Está explicado en el
[aviso legal](#aviso-legal) y dentro de la propia aplicación.

---

## Lo que WhatsApp no te deja hacer

**WhatsApp no tiene nada parecido a un bot de Telegram**: no hay forma gratuita de escribir a quien
quieras, cuando quieras, el texto que quieras. Lo que hay es la Cloud API de Meta, que es una
plataforma de empresa a cliente:

| | Texto libre | Plantilla |
|---|---|---|
| Cuándo se puede mandar | Solo en las **24 h** siguientes a que el destinatario te escriba | **Siempre** |
| Longitud | 4.096 caracteres | 1.024, contando el texto fijo |
| Saltos de línea dentro del mensaje | Sí | **No** |
| Hay que pedir permiso a Meta | No | Sí, por plantilla y por idioma |
| Coste | Los primeros **1.000 al mes** gratis | **Cada** mensaje se factura |

Un SMS llega cuando llega, así que **las plantillas son el camino principal** y el texto libre es
lo que se aprovecha cuando la ventana está abierta. La aplicación hace las dos cosas y decide sola.

Lo que hace falta tener, y no se puede hacer desde la aplicación: una **app en Meta for Developers**
con el producto WhatsApp, un **número dedicado** (para empezar vale el de prueba de Meta, gratis y
con hasta 5 destinatarios), un **token de usuario de sistema** con caducidad «Nunca», el **`Phone
number ID`** y una **plantilla aprobada** con el cuerpo que la aplicación te deja copiar en
**Ajustes → Entrega**. Para un número propio hace falta además **verificar la empresa**, un trámite
de días.

> **Cada clic de eso está en [`docs/CUENTA-WHATSAPP-BUSINESS.md`](docs/CUENTA-WHATSAPP-BUSINESS.md)**,
> con una lista de comprobación y los códigos de error de Meta que de verdad se ven.

Los destinos de WhatsApp son siempre **personas**: para grupos está Telegram. Y como cada mensaje de
plantilla se factura, la aplicación lleva un **tope mensual de WhatsApp** (900 por defecto, por
debajo del tramo gratuito) que, al agotarse, deja de reenviar por WhatsApp sin tocar los demás
canales.

---

## Características principales

- **Cuatro canales, elegidos regla a regla**, con el destino validado según el canal: un número
  E.164 para WhatsApp, un chat para Telegram, un teléfono para SMS, una URL para la API. Si pegas un
  chat de Telegram con WhatsApp marcado, el editor te dice que cambies el canal.
- **Reglas con expresiones regulares** sobre el remitente y el texto, con orden, **Ignorar
  mayúsculas** y **Seguir evaluando**, y una pantalla para **probarlas** con un SMS de ejemplo antes
  de que llegue uno real, que dice por qué canal saldría cada uno.
- **Plantillas de texto** con `{mensaje}`, `{telefono}` y `{fecha}`.
- **Contestar al remitente** por SMS, con `{telefono}` como destino.
- **URL de API con marcadores** en la ruta o en la consulta, URL-codificados, nunca en el servidor.
- **Nada se pierde**: cada SMS se guarda antes de intentar nada, cada reenvío se reintenta con
  espera, y lo que se quede a medias se rescata solo.
- **Un canal que falla no arrastra a los demás**: cada reenvío es una tarea independiente, y el
  Panel dice qué canal tiene un problema.
- **Protecciones**: un tope de reenvíos por minuto, el tope mensual de WhatsApp y una guarda
  antibucle para el canal SMS.
- **Historial y registro** filtrables por tipo y por canal.
- **Copia de seguridad** de reglas y ajustes, que **importa también las copias de las cuatro
  aplicaciones anteriores**.
- **Interruptor general** de la recepción, y aviso de versión nueva desde GitHub.

---

## Arquitectura

```
SIM → SmsReceiver → SmsIngestionService → Room (outbox) → reglas → reenvío por canal
    → WorkManager → transporte del canal ─┬─ WhatsApp  (Cloud API de Meta)
                                          ├─ Telegram  (Bot API)
                                          ├─ SMS       (SmsManager)
                                          └─ API       (GET con plantilla)
```

Clean Architecture en tres capas, MVVM en presentación e inyección con Hilt. Todo lo que está por
encima de los transportes es común a los cuatro canales; los transportes viven debajo de un único
interfaz, `EnviadorDeMensajes`, y esperan la confirmación real de cada envío. El detalle está en
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) y en [`CLAUDE.md`](CLAUDE.md); la historia de cómo
se llegó aquí, en los cinco `MASTERPLAN-*.md`.

---

## Stack tecnológico

| Categoría | Tecnología |
|-----------|-----------|
| Lenguaje | Kotlin 2.4.21 |
| SDK mínimo | Android 8.0 (API 26) |
| SDK objetivo | Android 15 (API 35) |
| Inyección de dependencias | Hilt 2.60.1 |
| Base de datos local | Room 2.8.5 + WAL mode |
| Tareas en background | WorkManager 2.12.0 |
| Entrega | `HttpURLConnection` (WhatsApp, Telegram, API) y `SmsManager` (SMS) |
| Configuración | DataStore Preferences 1.3.0 |
| Serialización | kotlinx.serialization 1.12.0 |
| Concurrencia | Coroutines + Flow 1.11.0 |
| UI | Material Design 3 + Navigation Component |

Sin Retrofit ni OkHttp: cada llamada de red del proyecto es una docena de líneas de
`HttpURLConnection`, y una dependencia nueva para eso no se paga.

---

## Requisitos

- Android Studio compatible con AGP 9.4 (2026.2.1 o superior)
- **JDK 25** (Temurin 25) — `gradle/gradle-daemon-jvm.properties` lo exige
- Android SDK con la plataforma 37
- Dispositivo Android 8.0+ con SIM activa; con conexión a internet si alguna regla sale por
  WhatsApp, Telegram o una API fuera de la red local
- Lo que pida cada canal que uses:
  - **WhatsApp**: una cuenta de WhatsApp Business de Meta —
    [`docs/CUENTA-WHATSAPP-BUSINESS.md`](docs/CUENTA-WHATSAPP-BUSINESS.md)
  - **Telegram**: un bot creado con @BotFather — [`docs/BOT-TELEGRAM.md`](docs/BOT-TELEGRAM.md)
  - **SMS**: saldo o tarifa en la SIM
  - **API**: un servidor que acepte un `GET`

---

## Instalar la versión publicada

1. Descarga el APK de la
   [última release](https://github.com/jbautistamartin/SMSRelay/releases/latest).
2. Pásalo al teléfono e instálalo, aceptando «instalar de orígenes desconocidos» cuando lo pida.
3. **Abre la aplicación una vez** y concede los permisos que pide. Abrirla no es opcional: una
   aplicación recién instalada que nadie ha abierto todavía no recibe **ningún** broadcast de
   Android, los SMS incluidos.
4. **Ajustes → Fiabilidad en segundo plano → Desactivar ahorro de batería**, y en Xiaomi, Huawei,
   Oppo o Vivo, también el **inicio automático**. Sin esto el modo Doze puede retrasar las entregas
   horas con la pantalla apagada.
5. Configura **los canales que vayas a usar**, cada uno en su sección de Ajustes. La tarjeta
   **Canales**, arriba, dice cuáles están listos.

> El APK está firmado con la clave de Capicua, la misma que las cuatro aplicaciones anteriores. Las
> actualizaciones se firman con la misma: un APK de otra procedencia no se instalará encima.

---

## Compilar e instalar desde el código

### 1. Compilar

```bash
# Gradle exige el JDK 25; si JAVA_HOME apunta a otro, Android Studio usa el suyo
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk

# Compilación release (requiere claves de firma en local.properties; ver CLAUDE.md)
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/smsrelay-<versionName>.apk
```

### 2. Instalar mediante ADB

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
# Reemplazar una versión ya instalada conservando sus datos
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

La variante debug se instala como `com.capicua.smsrelay.debug` y convive con la de release.

### 3. Conceder permisos y configurar el dispositivo

```bash
adb shell pm grant com.capicua.smsrelay android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smsrelay android.permission.READ_SMS
adb shell pm grant com.capicua.smsrelay android.permission.POST_NOTIFICATIONS
# Solo si alguna regla sale por SMS
adb shell pm grant com.capicua.smsrelay android.permission.SEND_SMS
# Solo para elegir con qué SIM enviar, en un teléfono con dos
adb shell pm grant com.capicua.smsrelay android.permission.READ_PHONE_STATE

# Excluir de la optimización de batería (obligatorio para una entrega fiable)
adb shell dumpsys deviceidle whitelist +com.capicua.smsrelay
```

---

## Primeros pasos en la app

1. **Prepara el canal**, en Ajustes:
   - **WhatsApp** — *Cuenta de WhatsApp*: el token y el id del número, y **Comprobar las
     credenciales**. *Entrega*: copia el cuerpo de la plantilla, dalo de alta en Meta y, cuando lo
     aprueben, escribe su nombre y su idioma.
   - **Telegram** — *Bot de Telegram*: el token de @BotFather y **Comprobar el token**. Quien vaya a
     recibir los avisos tiene que mandar `/start` al bot.
   - **SMS** — *SMS*: concede el permiso de enviar y, si el teléfono tiene dos SIM, elige cuál.
   - **API** — *API*: nada, salvo que tu servidor use un certificado propio.
2. **Reglas** → **+**. Elige el **canal** arriba: el destino, sus botones de ayuda y la plantilla
   por defecto cambian con él. Rellena el nombre y al menos un criterio; deja los dos vacíos solo si
   quieres reenviar *todos* los SMS.
3. **Probar** → pega un SMS de ejemplo y comprueba que la regla casa, por qué canal saldría y que el
   texto sale como esperas.

Mientras no haya ninguna regla activa, el Panel lo advierte: los SMS se reciben y se marcan como
«sin regla», pero no se reenvía nada. Y si un canal usado por alguna regla no puede entregar —falta
una credencial, Meta o Telegram la han rechazado, falta el permiso de SMS—, el Panel lo dice en rojo
y **nombra el canal**.

### Las reglas, en detalle

| Campo | Qué hace |
|-------|----------|
| Canal | Por dónde sale: WhatsApp, Telegram, SMS o API |
| Expresión del remitente | Se aplica al número tal como llega, sin normalizar. Puede ser alfanumérico: `BANCO`, `AMAZON` |
| Expresión del mensaje | Se aplica al texto completo del SMS |
| Destino | Depende del canal: ver la tabla de abajo |
| Plantilla | Qué texto se envía, con `{mensaje}`, `{telefono}` y `{fecha}` |
| Ignorar mayúsculas | Aplica las dos expresiones sin distinguir mayúsculas de minúsculas |
| Activa | Desactívala para que no se evalúe, sin borrarla |
| Seguir evaluando | Tras casar, continúa con las reglas siguientes |

| Canal | Destino |
|---|---|
| WhatsApp | Un número en formato internacional: `+34600112233`. **El `+` y el prefijo son obligatorios** |
| Telegram | Un `chat_id` —negativo si es un grupo o un canal— o el `@usuario` de un canal público. **Elegir de los chats del bot** lo busca por ti |
| SMS | Un teléfono tal como lo marcarías, o `{telefono}` para contestar a quien escribió |
| API | Una URL `http://` o `https://` con `{mensaje}` en algún sitio; `{telefono}` y `{fecha}` también valen, pero **nunca en el nombre del servidor** |

La búsqueda es **parcial**: el patrón `codigo` casa con «Tu codigo es 4821». Para exigir el texto
completo, ánclalo con `^…$`. Las expresiones distinguen mayúsculas salvo que actives **Ignorar
mayúsculas**, o escribas `(?i)` dentro de una.

Ejemplos:

```
# Los SMS del banco, a una persona por WhatsApp
Remitente: ^BANCO$          Mensaje: (vacío)        → WhatsApp · +34600112233

# Los códigos de verificación, a un grupo de Telegram y además por SMS a otro móvil
Remitente: (vacío)          Mensaje: (?i)codigo     → Telegram · -1001234567890   [seguir evaluando]
Remitente: (vacío)          Mensaje: (?i)codigo     → SMS · 600112233

# Las alertas de la alarma, a la API del sistema de tickets
Remitente: (?i)alarma       Mensaje: (vacío)        → API · https://tickets.example.com/alta?de={telefono}&texto={mensaje}

# Contestar a todo el que escriba
Remitente: (vacío)          Mensaje: (vacío)        → SMS · {telefono}    plantilla: Recibido, gracias
```

Los números y chats de los ejemplos son inventados.

### Por qué conviene dejar `{telefono}` en la plantilla

Por WhatsApp y por Telegram **todos los avisos llegan del mismo emisor**, y por SMS del número de
este teléfono: cuatro mensajes seguidos sin el remitente dentro son cuatro mensajes
indistinguibles. La plantilla por defecto —`{telefono} · {fecha}` y el mensaje debajo— pone el
contexto que el SMS original daba gratis. Para la API la plantilla por defecto es solo `{mensaje}`:
el remitente y la fecha van en la URL, donde tu servidor los espere.

---

## Migrar desde las aplicaciones anteriores

SMSRelay es **otra aplicación** —otro `applicationId`, otra base de datos—, así que no se instala
encima de ninguna de las cuatro: se instala al lado. Lo que sí hace es **leer sus copias de
seguridad**:

1. En la aplicación vieja, **Ajustes → Copia de seguridad → Exportar**.
2. En SMSRelay, **Ajustes → Copia de seguridad → Importar** ese fichero.
3. Rellena las credenciales que falten: las copias no las llevan salvo que se pidiera al exportar.
   Al terminar de importar, SMSRelay dice qué canales importados no pueden entregar todavía.
4. **Apaga la recepción en la aplicación vieja** (Ajustes, arriba del todo), o desinstálala. Con
   las dos activas, cada SMS se reenvía dos veces.

| Copia de | Qué entra |
|---|---|
| SMStoWhats | Sus reglas, por WhatsApp, con su modo de entrega, su plantilla y su tope mensual |
| SMStoTelegram | Sus reglas, por Telegram |
| SMStoSMS (cualquier versión) | Sus reglas, por SMS, con su guarda antibucle |
| SMSGateway | **Una regla** por la API, que casa con todo, con la URL de la pasarela como destino |

Los reintentos, el plazo de envío, el tope por minuto y el interruptor de recepción se toman de la
copia en todos los casos; **los ajustes de los demás canales no se tocan**. Importar nunca borra
reglas: las añade al final.

Una URL de SMSGateway con un marcador en el nombre del servidor (`https://{telefono}.ejemplo.com`)
no entra: SMSRelay no deja que el texto de un SMS elija servidor.

---

## Permisos

| Permiso | Para qué |
|---------|----------|
| `RECEIVE_SMS` | Recibir el broadcast de SMS entrantes |
| `READ_SMS` | Leer el contenido de las PDU |
| `SEND_SMS` | Reenviar por el canal SMS. **Solo se pide al elegir ese canal** |
| `READ_PHONE_STATE` | Enumerar las SIM para elegir con cuál enviar. **Solo se pide al pulsar ese botón** |
| `INTERNET` | Entregar por WhatsApp, Telegram y la API, y comprobar si hay versión nueva |
| `POST_NOTIFICATIONS` | Notificación del servicio en primer plano (Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Mantener vivo el proceso durante la ingesta |
| `RECEIVE_BOOT_COMPLETED` | Reactivar los workers tras reiniciar |
| `WAKE_LOCK` | Completar el trabajo en curso con la pantalla apagada |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Solicitar la exención de Doze |
| `REQUEST_INSTALL_PACKAGES` | Abrir el instalador del sistema con el APK de una versión nueva |

Quien no use el canal SMS no ve nunca el diálogo de `SEND_SMS` ni el de `READ_PHONE_STATE`, aunque
los dos estén declarados.

---

## Estructura del proyecto

```
app/src/main/java/com/capicua/smsrelay/
├── data/
│   ├── api/          ← ApiSender y su mapeo de respuestas
│   ├── config/       ← AppConfig, DataStore, ModoDeEntrega, ConfigBackup
│   ├── local/db/     ← Room: entidades, DAOs, SmsDatabase, Migraciones
│   ├── repository/   ← repositorios sobre Room
│   ├── rules/        ← formato de copia (ReglasJson) e ImportacionDeCopia
│   ├── sms/          ← SmsSender y ProveedorDeSims
│   ├── telegram/     ← TelegramSender, el bot y sus chats
│   ├── update/       ← comprobador de versión nueva y descarga del APK
│   └── whatsapp/     ← WhatsAppSender, la plantilla, la cuenta y el contador del mes
├── di/               ← módulos de Hilt; TransportModule une cada canal con su transporte
├── domain/
│   ├── model/        ← Regla, SmsMessage, Reenvio, LogEntry…
│   ├── rules/        ← EvaluadorDeReglas, PlantillaDeUrl, NormalizadorTelefono
│   ├── transport/    ← EnviadorDeMensajes, Canal, Transportes, ResultadoEnvio
│   └── usecase/      ← ProcesarSmsEntranteUseCase y compañía
├── presentation/     ← Panel, Reglas (lista, editor, prueba), Logs, Ajustes, Acerca de
├── receiver/         ← SmsReceiver, BootReceiver
├── service/          ← SmsIngestionService
├── util/             ← Doze, inicio automático, interruptor de recepción
└── worker/           ← DispatchWorker, OrphanRescueWorker, HealthMonitorWorker, ColaDeEnvios
```

---

## Base de datos

Room, cuatro tablas —`sms`, `reglas`, `reenvios` y `log_entries`—, con el **canal** guardado en la
regla, en cada reenvío y en el registro. Un reenvío lleva copiados su canal y el nombre de su
regla, para poder entregarse aunque la regla se edite o se borre mientras espera. Los SMS atendidos
y el registro se purgan a los 30 días.

---

## Tests

241 tests unitarios en 15 clases, todos en Kotlin puro, sin Android, sin Robolectric y sin base de
datos en memoria:

| Suite | Qué fija |
|-------|----------|
| `EvaluadorDeReglasTest` | El motor de reglas, y qué marcadores se resuelven en el destino de cada canal |
| `MapeoDeRespuestasTest` (×3) | Qué respuesta de Meta, de Telegram o de una API es transitoria y cuál permanente |
| `PlantillaDeWhatsAppTest` | El contrato de la plantilla, y que ningún parámetro lleve lo que Meta prohíbe |
| `PlantillaDeUrlTest` | Que la URL de una API se valide y se codifique bien, y que un SMS no pueda elegir servidor |
| `ImportacionDeCopiaTest` | Que una copia real de cada una de las cinco aplicaciones entre con su canal |
| `ReglasJsonTest` | El formato de copia, y que las credenciales y la SIM **no** aparezcan en él |
| `NormalizadorTelefonoTest` | La comparación de números de la guarda antibucle |
| `DestinoDeWhatsAppTest`, `DestinoDeTelegramTest` | Qué se acepta como destino en cada canal |
| `CredencialesTest`, `TokenDeBotTest` | La forma de las credenciales, sin rechazar una legítima |
| `VersionPublicadaTest` | La comparación de versiones como números y no como texto |

```bash
./gradlew test
```

---

## Contribuir

1. Haz un fork del repositorio
2. Crea una rama para tu feature: `git checkout -b feature/nombre-feature`
3. Respeta las convenciones de nombres del proyecto, descritas en [`CLAUDE.md`](CLAUDE.md)
4. Realiza tus cambios y añade tests
5. Abre un Pull Request con una descripción clara del cambio

---

## Apoyar el proyecto

SMSRelay es gratuito y de código abierto. Si te resulta útil, puedes invitarme a un café:

[![Donar con PayPal](https://img.shields.io/badge/Donar-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/jbautistamartin)

El enlace también está dentro de la aplicación, en la pestaña **Acerca de** («Invitar a un café»).
Es completamente opcional: la aplicación no muestra avisos ni limita ninguna función.

---

## Licencia

Este proyecto se distribuye bajo la **GNU Lesser General Public License v2.1**.
Consulta el fichero [`LICENSE`](LICENSE) para más detalles.

---

## Aviso legal

Este software se proporciona **"tal cual"**, sin garantía de ningún tipo, expresa o implícita.

Esta aplicación **reenvía el contenido de mensajes SMS** fuera del dispositivo que los recibió.
Datos personales —códigos de acceso, avisos bancarios, comunicaciones privadas— salen de ese
teléfono por el canal que diga cada regla. Ni Capicua ni el autor reciben ninguno de esos mensajes.

Antes de desplegarla, considera lo que es común a todos los canales:

- El **remitente original no sabe** que su mensaje se está reenviando y no ha dado su
  consentimiento para ello.
- El mensaje **queda guardado donde se entrega**, de forma indefinida: en la conversación de
  destino y en el teléfono de quien lo recibe, o en el servidor de la API.
- El contenido de los SMS queda almacenado también en este dispositivo durante el periodo de
  retención configurado, y el registro de auditoría guarda los números implicados.
- Las **credenciales** —el token de Meta, el token del bot— son permiso para escribir en tu nombre.
  No viajan en la copia de seguridad salvo que lo pidas, pero sí están guardadas en este
  dispositivo.

Y lo que es de cada canal:

- **WhatsApp.** El contenido viaja por internet y **pasa por los servidores de Meta**, una empresa
  ajena a este proyecto, cuyas condiciones rigen lo que hace con él. Los reenvíos salen de una
  **cuenta de empresa de WhatsApp registrada a tu nombre**: ante Meta y ante quien los recibe, el
  emisor eres tú. Fuera de la ventana de 24 horas, los mensajes se entregan **mediante plantillas
  aprobadas por Meta**, que llegan a un número sin que esa persona haya escrito antes ni haya
  pedido nada; en los términos de Meta eso es mensajería no solicitada, y si quien recibe los
  bloquea o los denuncia, **Meta puede limitar o bloquear tu número**. Cada mensaje de plantilla
  **se factura**, y los de texto libre pasados los mil primeros del mes. El tope mensual de la
  aplicación corta antes, pero el importe es tuyo.
- **Telegram.** El contenido pasa por los servidores de Telegram, otra empresa ajena, y queda en
  el chat. En un grupo o un canal lo lee todo el que esté dentro, también quien entre después.
- **SMS.** El mensaje sale desde tu SIM, lo cobra tu operador según tu tarifa, y quien lo recibe ve
  tu número como remitente.
- **API.** El contenido va dentro de la URL de una petición a un servidor que eliges tú, y queda en
  sus registros y en los de cualquier intermediario. Si activas **aceptar certificados no
  válidos**, la conexión deja de garantizar que el servidor sea el que dice ser.

El autor no se hace responsable de:

- Pérdida, alteración o interceptación de mensajes durante el tránsito o el almacenamiento, ni del
  tratamiento que Meta, Telegram, tu operador o el servidor de la API hagan de ellos.
- El importe que Meta te facture o que tu operador te cobre, ni de ninguna restricción, limitación
  o bloqueo que Meta aplique a tu número o a tu cuenta, o Telegram a tu bot.
- Uso indebido de la aplicación para capturar o redirigir comunicaciones sin el consentimiento de
  los remitentes.
- Daños directos o indirectos derivados del uso, mal uso o imposibilidad de uso del software.
- Incumplimientos normativos (RGPD, LOPD u otras regulaciones de privacidad) derivados de una
  configuración o despliegue inadecuados, incluida la transferencia de datos personales a
  terceros que esta aplicación realiza por diseño.

**El operador es el único responsable** de garantizar que el uso de esta aplicación cumple con la
legislación vigente en su jurisdicción, incluyendo la obtención del consentimiento necesario para
el tratamiento de los datos personales contenidos en los SMS.

---

## Desarrollo asistido por IA

El diseño, la arquitectura y el código de este proyecto se desarrollaron con asistencia de
[Claude](https://claude.ai) (Anthropic). El código fue revisado, validado y adaptado por el autor
antes de cada commit.
