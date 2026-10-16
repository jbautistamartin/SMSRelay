# SMStoWhats

[![Última versión](https://img.shields.io/github/v/release/jbautistamartin/SMStoWhats?label=descargar&color=2E7D32)](https://github.com/jbautistamartin/SMStoWhats/releases/latest)
[![Licencia](https://img.shields.io/badge/licencia-LGPL--2.1-blue)](LICENSE)
[![Donar con PayPal](https://img.shields.io/badge/Donar-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/jbautistamartin)

Aplicación Android que convierte un dispositivo con SIM en un **puente de SMS a WhatsApp**:
recibe los mensajes que llegan a su número y los entrega automáticamente en WhatsApp, al número
de una persona, a través de la Cloud API de Meta y una cuenta de empresa propia, según reglas con
expresiones regulares sobre el remitente y el texto.

> **Esta aplicación nace de [SMStoTelegram](https://github.com/jbautistamartin/SMStoTelegram)**,
> que hace lo mismo con la entrada y entrega en Telegram a través de un bot. Son aplicaciones
> **distintas**, con `applicationId` propio y base de datos propia, y pueden convivir en el mismo
> dispositivo.
>
> **Antes de empezar, lee [Lo que WhatsApp no te deja hacer](#lo-que-whatsapp-no-te-deja-hacer).**
> Entregar en WhatsApp no se parece a entregar en Telegram: hace falta una cuenta de empresa de
> Meta, un número dedicado, y hay un límite de 24 horas que condiciona todo lo demás.

**Versión actual: [v1.0.0](https://github.com/jbautistamartin/SMStoWhats/releases/tag/v1.0.0)** —
descarga el APK firmado desde la
[página de releases](https://github.com/jbautistamartin/SMStoWhats/releases). No está en
Google Play: `RECEIVE_SMS` pertenece a un grupo de permisos restringido cuya publicación exige
una declaración aprobada. Lo que cambia en cada versión está en [`CHANGELOG.md`](CHANGELOG.md).

---

## Para qué sirve

El caso típico: un número al que llegan avisos —códigos de verificación, notificaciones de un
proveedor, alertas de un sistema de monitorización— y una o varias personas que necesitan
recibirlos donde ya están mirando, que es WhatsApp.

Las reglas permiten separar ese tráfico: los SMS del banco a una persona, los de la alarma a otra,
y el resto a ninguna parte.

Frente a reenviar por SMS, entregar en WhatsApp cambia dos cosas a favor:

- **Caben 4.096 caracteres** por mensaje en lugar de 160, así que no hay mensajes troceados.
- **Llega a donde la gente mira**, sin que el destinatario tenga que instalar nada nuevo.

Y añade una que hay que tener presente: **el contenido de los SMS sale a internet** y pasa por
los servidores de Meta, desde una cuenta de empresa registrada a tu nombre. Está explicado en el
[aviso legal](#aviso-legal) y dentro de la propia aplicación.

---

## Lo que WhatsApp no te deja hacer

Esta sección está antes que las características a propósito. **WhatsApp no tiene nada parecido a
un bot de Telegram**: no hay forma gratuita de escribir a quien quieras, cuando quieras, el texto
que quieras. Lo que hay es la Cloud API de Meta, que es una plataforma de empresa a cliente, y
tiene estas reglas:

| | Texto libre | Plantilla |
|---|---|---|
| Cuándo se puede mandar | Solo en las **24 h** siguientes a que el destinatario te escriba | **Siempre** |
| Longitud | 4.096 caracteres | 1.024, contando el texto fijo |
| Saltos de línea dentro del mensaje | Sí | **No** |
| Hay que pedir permiso a Meta | No | Sí, por plantilla y por idioma |
| Coste | Los primeros **1.000 al mes** gratis | **Cada** mensaje se factura |

Un SMS llega cuando llega, no cuando al destinatario le apetece escribirte. Así que, en la
práctica, **las plantillas son el camino principal** y el texto libre es lo que se aprovecha
cuando la ventana está abierta. La aplicación hace las dos cosas y decide sola, pero conviene
saberlo antes de montar nada.

Lo que hace falta tener, y no se puede hacer desde la aplicación:

1. Una **app en Meta for Developers** con el producto WhatsApp.
2. Un **número dedicado**. Un número en la Cloud API no puede estar al mismo tiempo en la app de
   WhatsApp. Para empezar vale el número de prueba que Meta crea solo: es gratis, no pide
   verificación de empresa y admite hasta **5 destinatarios**.
3. Un **token de usuario de sistema** con caducidad «Nunca». El que la consola enseña por defecto
   caduca en 24 horas y dejaría la aplicación muda al día siguiente.
4. El **`Phone number ID`**, que no es el número de teléfono.
5. Una **plantilla aprobada**, con el cuerpo exacto que la propia aplicación te deja copiar en
   **Ajustes → Entrega**.

Y lo que se pierde respecto a Telegram, sin adornos:

- **Grupos.** Esta aplicación entrega solo a personas, por decisión. La Cloud API admite grupos
  desde 2026, pero solo creados por ella, de hasta ocho participantes y con Cuenta Oficial de
  Empresa.
- **Entregar gratis y sin límite.**
- **La inmediatez sin condiciones.** Sale siempre *si* hay plantilla aprobada, cuota y número en
  buen estado.

Lo que se gana es que es legal, es estable y no depende de que WhatsApp no se dé cuenta.

---

## Características principales

- **Reglas ordenadas** — expresiones regulares sobre el remitente y el mensaje; gana la primera
  que casa, y una regla puede dejar que la evaluación continúe para entregar a varios números
- **Plantilla por regla** — el texto que sale se compone con `{mensaje}`, `{telefono}` y
  `{fecha}`. Conviene dejar los tres: en WhatsApp todo llega del mismo número, y sin el remitente
  no se sabe de quién era cada SMS
- **Texto libre o plantilla, decidido solo** — intenta el texto libre, que es gratis, y si la
  ventana de 24 horas está cerrada reenvía como plantilla sin gastar un reintento
- **Las credenciales se comprueban antes de usarlas** — un botón pregunta a Meta y responde con el
  nombre verificado y el número, en lugar de descubrir el fallo cuando llegue el primer SMS. De
  paso enseña la **calidad** que Meta da al número, que es el único aviso que llega antes de un
  bloqueo
- **Contador de gasto en el Panel** — los mensajes entregados este mes contra el tope
  configurado, separando los gratis de los facturados. Es la única cifra de la aplicación que
  cuesta dinero
- **Tope mensual** — pasado el tope, los SMS se descartan y queda en el registro. Por defecto 900,
  por debajo de los 1.000 gratuitos de Meta, para que una instalación recién hecha no facture nada
- **Panel de prueba** — se pega un SMS de ejemplo y la app dice qué regla casa, a qué número iría
  y con qué texto, sin entregar nada
- **Sin pérdida de mensajes** — patrón outbox: el SMS se persiste en Room antes de hacer nada más
- **Confirmación real de entrega** — se espera la respuesta de la Cloud API; una entrega solo se
  da por buena cuando Meta la confirma
- **Mayúsculas opcionales** — cada regla decide si sus expresiones distinguen mayúsculas
- **Aviso cuando no se puede entregar** — sin credenciales, o con unas rechazadas, el Panel lo
  dice. Es el único fallo que afecta a todas las entregas por igual
- **Reintentos automáticos** — WorkManager con backoff configurable y rescate cada 30 segundos de
  lo que se quede atascado. Los cuatro códigos de límite de Meta se reintentan; los errores de
  configuración, no, porque reintentar los mismos bytes no cambia la respuesta
- **Arranque automático** — los workers se reactivan tras reiniciar el dispositivo, y Ajustes
  lleva al permiso de inicio automático del fabricante, que es lo que lo impide en Xiaomi,
  Huawei, Oppo y Vivo
- **Interruptor de recepción** — un ajuste apaga la lectura de SMS sin desinstalar ni
  desconfigurar nada; lo que ya estuviera en cola se termina de entregar
- **Registro de auditoría** — cada paso queda anotado, con filtros y exportación a fichero
- **Copia de seguridad** — ajustes y reglas en un solo fichero JSON, desde la pestaña
  Configuración. **Las credenciales no viajan en él**, y tampoco el id del número: identifica a
  esta cuenta y restaurado en otro teléfono enviaría por donde no es
- **Aviso de versión nueva** — comprueba una vez al día si hay un release más reciente, lo marca
  con un punto en la pestaña **Acerca de** y desde ahí se descarga e instala; se puede apagar

---

## Arquitectura

```
Presentation  →  Domain  →  Data
   MVVM          Reglas      Room · Cloud API · DataStore · WorkManager
```

Clean Architecture con tres capas estrictas, inyección de dependencias con **Hilt** y
reactividad completa mediante **Kotlin Flow**.

El recorrido de cada SMS:

```
SIM → SmsReceiver → SmsIngestionService → Room (outbox)
                                             ↓
                                 tope por minuto y del mes
                                             ↓
                                      EvaluadorDeReglas
                                             ↓
                                  reenvíos (uno por número)
                                             ↓
                                       DispatchWorker
                                             ↓
                        WhatsAppSender → texto libre o plantilla
```

**El único punto de la cadena que sabe de WhatsApp es `WhatsAppSender`.** Todo lo que hay antes
—recibir, persistir, evaluar reglas, encolar, reintentar, rescatar lo atascado— es agnóstico del
medio, y es lo que ha permitido cambiar de medio **dos veces** —de SMS a Telegram, y de Telegram a
la Cloud API— sin tocar la tubería ni una sola vez: `TransportModule` es la única clase que nombra
una implementación concreta del transporte.

Consulta [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) para la documentación técnica completa y
los tres MASTERPLAN para la historia de las tres mudanzas:
[`MASTERPLAN-WHATSAPP.md`](MASTERPLAN-WHATSAPP.md) desde SMStoTelegram,
[`MASTERPLAN-TELEGRAM.md`](MASTERPLAN-TELEGRAM.md) desde SMStoSMS y
[`MASTERPLAN-SMS.md`](MASTERPLAN-SMS.md) desde SMSGateway.

---

## Stack tecnológico

| Categoría | Tecnología |
|-----------|-----------|
| Lenguaje | Kotlin 2.0.21 |
| SDK mínimo | Android 8.0 (API 26) |
| SDK objetivo | Android 15 (API 35) |
| Inyección de dependencias | Hilt 2.52 |
| Base de datos local | Room 2.6.1 + WAL mode |
| Tareas en background | WorkManager 2.9.1 |
| Entrega de mensajes | Cloud API de WhatsApp vía `HttpURLConnection` |
| Configuración | DataStore Preferences 1.1.1 |
| Serialización | kotlinx.serialization 1.7.3 |
| Concurrencia | Coroutines + Flow 1.9.0 |
| UI | Material Design 3 + Navigation Component |

La red se usa con `HttpURLConnection` y `kotlinx.serialization`, sin Retrofit ni OkHttp: toda la
comunicación con Meta son dos peticiones —enviar un mensaje y consultar el número emisor— y una
dependencia nueva para eso no se paga.

---

## Requisitos

- Android Studio Ladybug (2024.2.1) o superior
- **JDK 17 o 21** — AGP 8.5.2 no admite JDK 25
- Android SDK con API 35
- Dispositivo Android 8.0+ con SIM activa y conexión a internet
- Una **cuenta de WhatsApp Business de Meta** con la Cloud API, un número dedicado y una
  plantilla aprobada. Ver [Lo que WhatsApp no te deja hacer](#lo-que-whatsapp-no-te-deja-hacer):
  es el requisito largo, y no se puede resolver desde la aplicación

---

## Instalar la versión publicada

La vía corta si no vas a tocar el código:

1. Descarga el APK de la
   [última release](https://github.com/jbautistamartin/SMStoWhats/releases/latest).
2. Pásalo al teléfono e instálalo, aceptando «instalar de orígenes desconocidos» cuando lo pida.
3. **Abre la aplicación una vez** y concede los permisos que pide. Abrirla no es opcional: una
   aplicación recién instalada que nadie ha abierto todavía no recibe **ningún** broadcast de
   Android, los SMS incluidos. Es la causa número uno de «la he instalado y no recibe nada».
4. **Monta la cuenta de Meta**: una app en Meta for Developers con el producto WhatsApp, un
   número (el de prueba sirve para empezar), un token de usuario de sistema con caducidad «Nunca»
   y el `Phone number ID`. Ver
   [Lo que WhatsApp no te deja hacer](#lo-que-whatsapp-no-te-deja-hacer).
5. **Ajustes → Cuenta de WhatsApp** → pega el token y el id del número, y pulsa **Comprobar las
   credenciales**. Debe responder con el nombre verificado y el número.
6. **Ajustes → Entrega** → copia el cuerpo de la plantilla, dale de alta esa plantilla en el
   Business Manager y, cuando Meta la apruebe, escribe aquí su nombre y su idioma. Sin plantilla
   solo se entrega dentro de la ventana de 24 horas.
7. **Ajustes → Fiabilidad en segundo plano → Desactivar ahorro de batería.** Sin esto el modo
   Doze puede retrasar las entregas horas con la pantalla apagada.

> El APK está firmado con la clave de Capicua. Las futuras actualizaciones se firman con la
> misma: un APK de otra procedencia no se instalará encima, habrá que desinstalar primero y se
> perderán las reglas. Haz antes una copia desde **Config → Copia de seguridad → Exportar**.

---

## Compilar e instalar desde el código

### 1. Compilar

```bash
# Si el JDK del PATH es más nuevo que el 21
export JAVA_HOME="/ruta/al/jdk-21"

# Build debug
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk

# Build release (requiere claves de firma en local.properties; ver CLAUDE.md)
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/smstowhats-<versionName>.apk
```

Sin las claves de firma el build de release **no falla**: produce un APK sin firmar, que habrá
que firmar aparte antes de instalarlo.

### 2. Instalar mediante ADB

> Requiere [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
> y el dispositivo en modo depuración USB (**Ajustes → Opciones de desarrollador → Depuración USB**).

```bash
# Instalar APK debug
adb install app/build/outputs/apk/debug/app-debug.apk

# Reemplazar una versión ya instalada conservando sus datos
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verificar que quedó instalada
adb shell pm list packages | grep capicua
```

Alternativamente, `./gradlew installDebug` compila e instala en un solo paso.

### 3. Conceder permisos y configurar el dispositivo

```bash
adb shell pm grant com.capicua.smstowhats android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstowhats android.permission.READ_SMS
adb shell pm grant com.capicua.smstowhats android.permission.POST_NOTIFICATIONS

# Excluir de la optimización de batería (obligatorio para una entrega fiable)
adb shell dumpsys deviceidle whitelist +com.capicua.smstowhats
```

Sin la exención de batería, el modo Doze de Android puede retrasar las entregas horas cuando la
pantalla está apagada.

Si no tienes el cable a mano, la propia app lo resuelve: **Ajustes** → **Fiabilidad en segundo
plano** dice si la exención está concedida y el botón abre la pantalla del sistema que la
concede. Los pasos manuales por fabricante están en [`CLAUDE.md`](CLAUDE.md).

---

## Primeros pasos en la app

0. **Antes de nada**, monta la cuenta de Meta: ver
   [Lo que WhatsApp no te deja hacer](#lo-que-whatsapp-no-te-deja-hacer). Sin eso no hay nada que
   hacer aquí.
1. **Ajustes → Cuenta de WhatsApp**: pega el **token de acceso** y el **id del número**, y pulsa
   **Comprobar las credenciales**. Debe contestar con el nombre y el número que esperas.
2. **Ajustes → Entrega**: deja el modo en **Automático**, copia el cuerpo de la plantilla con
   **Copiar el cuerpo**, dale de alta esa plantilla en el Business Manager y, cuando Meta la
   apruebe, escribe aquí su nombre y su idioma.
3. **Reglas** → **+** para crear la primera regla.
4. Rellena el nombre y al menos un criterio. El destino es el número de WhatsApp de la persona,
   **con prefijo internacional**: `+34600112233`. Deja los dos criterios vacíos solo si quieres
   entregar *todos* los SMS.
5. **Probar** → pega un SMS de ejemplo y comprueba que la regla casa y que el texto sale como
   esperas, antes de que llegue uno real.
6. Opcionalmente, **Enviar de prueba** entrega un mensaje real al primer número para confirmar el
   circuito completo. Consume cuota como cualquier otro.

Mientras no haya ninguna regla activa, la pantalla de Inicio lo advierte: los SMS se reciben y se
marcan como «sin regla», pero no se entrega nada. Y si faltan las credenciales, o Meta las ha
rechazado, el Panel lo dice en rojo por encima de todo lo demás.

### Las reglas, en detalle

Una regla casa cuando se cumplen **sus dos criterios**. Un criterio vacío se considera cumplido.

| Campo | Qué hace |
|-------|----------|
| Expresión del remitente | Se aplica al número tal como llega, sin normalizar. Puede ser alfanumérico: `BANCO`, `AMAZON` |
| Expresión del mensaje | Se aplica al texto completo del SMS |
| Número de destino | Dónde se entrega: un número de WhatsApp con prefijo internacional, `+34600112233`. Siempre una persona, nunca un grupo. No admite marcadores |
| Plantilla | Qué texto se envía, con `{mensaje}`, `{telefono}` y `{fecha}` |
| Ignorar mayúsculas | Aplica las dos expresiones sin distinguir mayúsculas de minúsculas |
| Activa | Desactívala para que no se evalúe, sin borrarla |
| Seguir evaluando | Tras casar, continúa con las reglas siguientes |

La búsqueda es **parcial**: el patrón `codigo` casa con «Tu codigo es 4821». Para exigir el texto
completo, ánclalo con `^…$`.

Las expresiones **distinguen mayúsculas** salvo que actives **Ignorar mayúsculas** en la regla,
que las aplica sin distinguir a las dos a la vez. También puedes escribir `(?i)` dentro de una
expresión concreta si solo quieres que afecte a esa; activar las dos cosas no da problemas.

Ejemplos:

```
# Solo los SMS del banco, a una persona
Remitente: ^BANCO$          Mensaje: (vacío)       → +34600112233

# Cualquier mensaje con un código de verificación, al mismo número
Remitente: (vacío)          Mensaje: (?i)codigo    → +34600112233

# Todo lo que llegue de un número concreto, a dos personas
Remitente: ^\+34700        Mensaje: (vacío)        → +34600112233     [seguir evaluando]
Remitente: ^\+34700        Mensaje: (vacío)        → +34600445566

# Las alertas de la alarma, a quien esté de guardia
Remitente: (?i)alarma       Mensaje: (vacío)        → +34600778899
```

### El prefijo internacional es obligatorio

El destino se escribe **siempre** con `+` y el prefijo del país: `+34600112233`. La Cloud API
aceptaría el número sin el `+`, así que no es un requisito técnico — es lo que permite dar un
error útil.

`600112233` son nueve dígitos que podrían ser un móvil español escrito a pelo, al que le falta el
prefijo y no sirve, o un número internacional completo de un país con prefijo corto. Mirándolo no
hay forma de saberlo. Exigiendo el `+` la ambigüedad desaparece y la aplicación puede decirte lo
que de verdad arregla el problema: *falta el prefijo internacional*. Es el error que comete todo
el mundo.

### El destinatario tiene que poder recibirte

Dos condiciones que no dependen de la aplicación:

- **Tiene que tener WhatsApp** en ese número. Si no, Meta contesta que no es un usuario válido y
  el reenvío se cierra como fallido.
- **Para recibir texto libre, tiene que haberte escrito en las últimas 24 horas.** Fuera de esa
  ventana solo le llegan plantillas. Con el modo **Automático** la aplicación se encarga, pero si
  no tienes plantilla configurada, esos SMS no se entregan y el motivo queda en el registro.

### Por qué conviene dejar `{telefono}` en la plantilla

Por SMS, el reenvío llegaba como un SMS más y el teléfono ya enseñaba de quién venía. En WhatsApp
**todos los avisos llegan del mismo número**: cuatro mensajes seguidos sin el remitente dentro son
cuatro mensajes indistinguibles. La plantilla por defecto —`{telefono} · {fecha}` y el mensaje
debajo— pone el contexto que el SMS daba gratis.

Con una plantilla de Meta aprieta más todavía: el cuerpo aprobado es **idéntico** en todos los
reenvíos, así que lo único que distingue un aviso de otro es lo que entre en la variable.

---

## Permisos

| Permiso | Para qué |
|---------|----------|
| `RECEIVE_SMS` | Recibir el broadcast de SMS entrantes |
| `READ_SMS` | Leer el contenido de las PDU |
| `INTERNET` | Entregar los mensajes a WhatsApp, y comprobar si hay versión nueva |
| `POST_NOTIFICATIONS` | Notificación del servicio en primer plano (Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Mantener vivo el proceso durante la ingesta |
| `RECEIVE_BOOT_COMPLETED` | Reactivar los workers tras reiniciar |
| `WAKE_LOCK` | Completar el trabajo en curso con la pantalla apagada |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Solicitar la exención de Doze |
| `REQUEST_INSTALL_PACKAGES` | Abrir el instalador del sistema con el APK de una versión nueva |

**No se declara `SEND_SMS`.** Esta aplicación no envía ningún SMS: recibe por la radio y entrega
por internet. Tampoco `READ_PHONE_STATE`, que en SMStoSMS servía para elegir con qué SIM enviar.

`RECEIVE_SMS` y `READ_SMS` pertenecen a un grupo de permisos restringido por Google Play, cuya
publicación exige una declaración aprobada. Para distribución por APK, como es el caso, no supone
ningún problema.

---

## Estructura del proyecto

```
app/src/main/java/com/capicua/smstowhats/
├── data/
│   ├── config/          # AppConfig, ConfigDataStore, ConfigBackup
│   ├── local/db/        # Room: entidades, DAOs, relaciones, SmsDatabase, Migraciones
│   ├── repository/      # Sms, Regla, Reenvio y Log + sus implementaciones
│   ├── rules/           # ReglasJson: formato de exportación
│   ├── whatsapp/        # WhatsAppSender, MapeoDeRespuestas, PlantillaDeWhatsApp,
│   │                    # DestinoDeWhatsApp, TokenDeAcceso, IdNumeroTelefono,
│   │                    # ComprobadorDeCuenta, EstadoDeLaCuenta, ContadorDeMensajes
│   └── update/          # Comprobación de versiones y descarga del APK
├── di/                  # Módulos Hilt: Database, Config, Repository, Worker, Transport
├── domain/
│   ├── model/           # SmsMessage, Regla, Reenvio, estados
│   ├── rules/           # EvaluadorDeReglas
│   ├── transport/       # EnviadorDeMensajes y ResultadoEnvio: la juntura del medio
│   └── usecase/         # ProcesarSmsEntranteUseCase, ObtenerListaSmsUseCase
├── presentation/
│   ├── home/            # Pestana, HomeFragment y el ViewPager2 de las cinco pestañas
│   ├── common/          # NestedScrollableHost: reparte el gesto dentro del pager
│   ├── dashboard/       # Inicio: SMS recibidos y qué se hizo con ellos
│   ├── rules/           # Lista de reglas, editor y panel de prueba
│   ├── logs/            # Registro con filtros y exportación
│   ├── settings/        # Credenciales, entrega, recepción, topes y copia de seguridad
│   └── about/           # Acerca de
├── receiver/            # SmsReceiver, BootReceiver
├── service/             # SmsIngestionService
├── util/                # Constants, Extensions, OptimizacionBateria, InicioAutomatico,
│                       # ControlRecepcion
├── worker/              # DispatchWorker, ColaDeEnvios, OrphanRescue, HealthMonitor
└── SmsToWhatsApplication.kt
app/schemas/             # Esquemas Room exportados (versionados)
docs/
└── ARCHITECTURE.md      # Documentación técnica completa
```

---

## Base de datos

SQLite vía Room, cuatro tablas:

- **`sms`** — mensajes recibidos, con su estado de tramitación
- **`reglas`** — reglas de reenvío, ordenadas por prioridad
- **`reenvios`** — una entrega a un chat, con su propio estado, intentos y último error
- **`log_entries`** — registro de auditoría de todos los eventos

Los esquemas se exportan a `app/schemas/` y se versionan: son la referencia para escribir
migraciones. No se usa `fallbackToDestructiveMigration`, porque perdería mensajes pendientes.

---

## Tests

```bash
./gradlew test
```

Ciento treinta y tres tests sobre las piezas que se pueden probar sin red, sin cuenta de Meta y
sin Android, que son también las que más importa que estén bien:

| Suite | Qué fija |
|-------|----------|
| `EvaluadorDeReglasTest` | El motor de reglas, y que en el destino no se resuelve ningún marcador |
| `MapeoDeRespuestasTest` | Qué respuesta de la Cloud API es transitoria y cuál permanente, por `error.code` |
| `PlantillaDeWhatsAppTest` | El contrato de la plantilla y que ningún parámetro lleve lo que Meta prohíbe |
| `ReglasJsonTest` | El formato de intercambio, y que las credenciales **no** aparecen en él |
| `CredencialesTest` | La forma de las dos credenciales, sin apretarla hasta rechazar una legítima |
| `DestinoDeWhatsAppTest` | Qué se acepta como número, y que «falta el prefijo» se distinga de «no es un teléfono» |
| `VersionPublicadaTest` | La comparación de versiones como números y no como texto |

Son Kotlin puro, sin Android, sin Robolectric y sin base de datos en memoria.

Las reglas de la plantilla no se pueden comprobar de ninguna otra forma: un salto de línea dentro
de un parámetro es un error de Meta que cierra el reenvío, y estos tests pillaron dos fallos reales
en el aplanado que ninguna lectura del código habría encontrado.

---

## Contribuir

1. Haz un fork del repositorio
2. Crea una rama para tu feature: `git checkout -b feature/nombre-feature`
3. Respeta las convenciones de nombres del proyecto, descritas en [`CLAUDE.md`](CLAUDE.md)
4. Realiza tus cambios y añade tests
5. Abre un Pull Request con una descripción clara del cambio

---

## Apoyar el proyecto

SMStoWhats es gratuito y de código abierto. Si te resulta útil, puedes invitarme a un café:

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

Esta aplicación **entrega el contenido de mensajes SMS en WhatsApp**. Eso implica que datos
personales —códigos de acceso, avisos bancarios, comunicaciones privadas— salen del dispositivo
que los recibió, **viajan por internet y pasan por los servidores de Meta**, que es una empresa
ajena a este proyecto, antes de quedar guardados en la conversación de destino.

Ni Capicua ni el autor reciben ninguno de esos mensajes. Meta sí, y sus propias condiciones y su
política de privacidad son las que rigen lo que hace con ellos.

Y hay algo más que no había cuando el destino era un bot: **los reenvíos salen de una cuenta de
empresa de WhatsApp registrada a tu nombre**. Ante Meta y ante quien los recibe, el emisor eres
tú, no esta aplicación.

Antes de desplegarla, considera:

- El **remitente original no sabe** que su mensaje se está reenviando y no ha dado su
  consentimiento para ello.
- El mensaje **queda en la conversación de destino** de forma indefinida, y en el teléfono de
  quien lo recibe.
- Fuera de la ventana de 24 horas, los mensajes se entregan **mediante plantillas aprobadas por
  Meta**, que llegan a un número sin que esa persona haya escrito antes ni haya pedido nada. En
  los términos de Meta eso es mensajería no solicitada: si quien recibe los bloquea o los
  denuncia, **Meta puede limitar o bloquear tu número**.
- Cada mensaje de plantilla **se factura**, y los de texto libre también pasados los mil primeros
  del mes. El tope mensual de la aplicación corta antes, pero el importe es tuyo.
- El contenido de los SMS queda almacenado también en este dispositivo durante el periodo de
  retención configurado, y el registro de auditoría guarda los números implicados.
- El **token de acceso es una credencial**: quien lo obtenga puede escribir en nombre de tu
  número. No viaja en el fichero de copia de seguridad, pero sí está guardado en este
  dispositivo.

El autor no se hace responsable de:

- Pérdida, alteración o interceptación de mensajes durante el tránsito o el almacenamiento, ni
  del tratamiento que Meta haga de ellos.
- El importe que Meta te facture por los mensajes entregados, ni de ninguna restricción,
  limitación o bloqueo que Meta aplique a tu número o a tu cuenta.
- Uso indebido de la aplicación para capturar o redirigir comunicaciones sin el consentimiento de
  los remitentes.
- Daños directos o indirectos derivados del uso, mal uso o imposibilidad de uso del software.
- Incumplimientos normativos (RGPD, LOPD u otras regulaciones de privacidad) derivados de una
  configuración o despliegue inadecuados, incluida la transferencia de datos personales a un
  tercero que esta aplicación realiza por diseño.

**El operador es el único responsable** de garantizar que el uso de esta aplicación cumple con la
legislación vigente en su jurisdicción, incluyendo la obtención del consentimiento necesario para
el tratamiento de los datos personales contenidos en los SMS.

---

## Desarrollo asistido por IA

El diseño, la arquitectura y el código de este proyecto se desarrollaron con asistencia de
[Claude](https://claude.ai) (Anthropic). El código fue revisado, validado y adaptado por el autor
antes de cada commit.
