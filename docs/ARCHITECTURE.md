# SMSRelay — Documentación Técnica

## Índice

1. [Descripción del sistema](#1-descripción-del-sistema)
2. [Arquitectura](#2-arquitectura)
3. [Diagrama de flujo](#3-diagrama-de-flujo)
4. [Diagrama de capas](#4-diagrama-de-capas)
5. [Dependencias](#5-dependencias)
6. [Cómo compilar](#6-cómo-compilar)
7. [Cómo instalar](#7-cómo-instalar)
8. [Permisos requeridos](#8-permisos-requeridos)
9. [Reglas de reenvío](#9-reglas-de-reenvío)
10. [Mecanismo de entrega](#10-mecanismo-de-entrega)
11. [Topes y guardas](#11-topes-y-guardas)
12. [Base de datos](#12-base-de-datos)
13. [Mantenimiento](#13-mantenimiento)
14. [Posibles incidencias](#14-posibles-incidencias)

---

## 1. Descripción del sistema

**SMSRelay** es una aplicación Android diseñada para ejecutarse en un **dispositivo con SIM** cuya
función es recibir los SMS que llegan a su número y reenviarlos según reglas con expresiones
regulares. **Cada regla elige su canal**:

| Canal | Entrega en | Medio |
|---|---|---|
| WhatsApp | El número de una persona | Cloud API de Meta, con una cuenta de empresa propia |
| Telegram | Una persona, un grupo o un canal | Bot API, con un bot propio |
| SMS | Otro teléfono, o quien escribió | `SmsManager`, desde la SIM del dispositivo |
| API | Un servidor propio | Un `GET` a una URL con plantilla |

Es la unión de las cuatro aplicaciones anteriores de la casa —SMSGateway, SMStoSMS,
SMStoTelegram y SMStoWhats—, que eran copias del mismo esqueleto con un medio de salida distinto
cada una. SMSRelay se copió de SMStoWhats, la más reciente, y cada una de las otras tres aportó **su
transporte**. Buena parte de este documento explica qué hubo que cambiar para que cuatro medios
convivan en una tubería que se diseñó para uno solo, y qué no hubo que cambiar en absoluto.

### Casos de uso típicos

- Un número corporativo que recibe códigos de verificación y los reparte: los del banco a una
  persona por WhatsApp, los de un proveedor a un grupo de Telegram.
- Alertas de un sistema de monitorización que llegan por SMS y deben alcanzar **a un grupo** de
  guardia por Telegram y, por si acaso, **por SMS** al móvil de quien esté de turno.
- Una pasarela hacia un sistema propio —tickets, un registro, un bot interno— que recibe cada SMS
  con un `GET`, igual que hacía SMSGateway.
- Una línea antigua que se quiere mantener operativa sin llevar encima su tarjeta, contestando
  automáticamente a quien escriba.

### Garantías del sistema

| Garantía | Mecanismo |
|----------|-----------|
| Ningún SMS entrante se pierde aunque el proceso sea eliminado | `ForegroundService` + outbox en Room |
| Una entrega solo se da por buena si el medio la confirma | Cada transporte espera la respuesta HTTP o el `sentIntent` |
| Ningún SMS se entrega dos veces por un reintento | Comprobación del estado al inicio de cada intento |
| Lo que se queda atascado se recupera solo | `OrphanRescueWorker` cada 30 s |
| Un canal que falla no arrastra a los demás | Un worker por reenvío, cada uno por su transporte |
| No se superan la cuota de WhatsApp ni una ráfaga razonable | Los topes de §11 |
| Un reenvío por SMS no se realimenta | Las guardas antibucle de §11 |
| Un fallo de credencial se ve, en lugar de repetirse 400 veces | `EstadoDeLaCuenta` / `EstadoDelBot` + aviso en el Panel |
| La app arranca sola tras un reinicio del dispositivo | `BootReceiver` + WorkManager |

### Lo que el sistema **no** garantiza

- **Lectura por el destinatario.** Una confirmación dice que el medio aceptó el mensaje, no que
  alguien lo haya visto.
- **Confidencialidad frente a terceros.** Por WhatsApp el contenido pasa por Meta; por Telegram,
  por Telegram; por SMS, por el operador; por la API, por el servidor configurado y sus
  intermediarios. Es inherente al diseño y está dicho en el aviso de privacidad de la aplicación.
- **Ausencia de duplicados en textos troceados.** Si un trozo falla después de que otro saliera, el
  reenvío se reintenta completo y el destino puede ver repetido el principio. Ver §10.

---

## 2. Arquitectura

La aplicación sigue **Clean Architecture** con separación estricta en tres capas y el patrón
**MVVM** en presentación. Toda la inyección de dependencias pasa por **Hilt**.

```
┌─────────────────────────────────────────────────────────┐
│  PRESENTATION                                           │
│  Fragments + ViewModels + Adapters                      │
│  Dashboard · Reglas · Probar · Logs · Settings · About  │
└───────────────────────────┬─────────────────────────────┘
                            │ usa
┌───────────────────────────▼─────────────────────────────┐
│  DOMAIN                                                 │
│  Modelos puros · EvaluadorDeReglas · PlantillaDeUrl ·   │
│  EnviadorDeMensajes · Canal · Transportes · UseCases    │
│  Sin una sola dependencia de Android                    │
└───────────────────────────┬─────────────────────────────┘
                            │ define interfaces,
                            │ implementadas por
┌───────────────────────────▼─────────────────────────────┐
│  DATA                                                   │
│  Room · DataStore · WorkManager                         │
│  WhatsAppSender · TelegramSender · SmsSender · ApiSender│
└─────────────────────────────────────────────────────────┘
```

**`EnviadorDeMensajes` es la juntura del sistema, y está en `domain` a propósito.** Define tres
cosas —entregar y esperar, el límite de caracteres, y por qué no se puede entregar ahora— y nada en
ella nombra un medio. Debajo hay **cuatro** implementaciones, y `di/TransportModule` sigue siendo la
**única** clase del proyecto que las nombra: un *multibinding* de Hilt que produce un
`Map<Canal, EnviadorDeMensajes>`, envuelto en `Transportes`.

Eso no es arquitectura por gusto: es lo que hizo que tres cambios de medio fueran una fase de
transporte y no una reescritura, y lo que hizo que la cuarta mudanza —juntar los cuatro— no tocara
la tubería. El outbox, la idempotencia de cliente, el rescate de huérfanos, la política de
reintentos y el historial se escribieron para una pasarela HTTP y siguen sin tocarse.

**Lo que sí cambió por encima de la juntura** es poco y está acotado: el `canal` pasó a ser un campo
de la regla, del reenvío y del registro; el worker pide el transporte del canal del reenvío; la cola
elige la restricción de red por canal; y el evaluador resuelve el destino según el canal. Nada de
eso sabe *cómo* entrega cada medio.

### Principios aplicados

- **La capa de dominio no conoce Android.** `EvaluadorDeReglas`, `PlantillaDeUrl` y
  `NormalizadorTelefono` son Kotlin puro, y por eso se prueban con JUnit a secas. El mismo
  criterio separó los tres `MapeoDeRespuestas` y `ImportacionDeCopia` del código que hace la
  petición o lee el fichero: son las piezas que más importa que estén bien y las únicas que se
  pueden probar sin red ni Android.
- **El evaluador recibe la fecha ya formateada**, no un `Instant`: la local para la plantilla y la
  ISO para la URL de la API. Es lo que lo mantiene determinista.
- **El destino se resuelve en el evaluador, no en el transporte.** El transporte recibe un destino
  y un texto ya hechos, y no decide nada.
- **Las reglas viven en la base de datos, no en DataStore.** Son una lista ordenada con identidad
  propia; DataStore guarda solo los ajustes escalares.
- **Un único punto de encolado.** Todo reenvío entra en WorkManager por `ColaDeEnvios`, que lee el
  backoff de la configuración y la restricción de red del canal.

---

## 3. Diagrama de flujo

```
                        ┌──────────────┐
                        │     SIM      │
                        └──────┬───────┘
                               │ SMS_RECEIVED (PDU)
                     ┌─────────▼──────────┐
                     │   SmsReceiver      │  BroadcastReceiver
                     │   onReceive()      │  hilo principal, < 10 s
                     └─────────┬──────────┘  concatena partes multipart
                               │ startForegroundService()
                   ┌───────────▼────────────┐
                   │  SmsIngestionService   │  startForeground() < 5 s
                   └───────────┬────────────┘  mantiene vivo el proceso
                               │
                 ┌─────────────▼──────────────┐
                 │ ProcesarSmsEntranteUseCase │
                 └─────────────┬──────────────┘
                               │
            ┌──────────────────▼───────────────────┐
            │ 1. INSERT sms (PENDIENTE)   ← outbox │
            └──────────────────┬───────────────────┘
                               │
            ┌──────────────────▼───────────────────┐
            │ 2. ¿el remitente es un destino SMS?  │──sí──→ DESCARTADO
            │    ¿se superó el tope por minuto?    │
            └──────────────────┬───────────────────┘
                               │ no
            ┌──────────────────▼───────────────────┐
            │ 3. EvaluadorDeReglas                 │
            │    reglas activas, por orden         │
            │    canal + destino resuelto + texto  │
            └──────────────────┬───────────────────┘
                               │
                  ┌────────────┴────────────┐
             sin coincidencias        con coincidencias
                  │                         │
              SIN_REGLA        ┌────────────▼─────────────┐
                               │ 4. guardas y topes de    │  omiten reenvíos sueltos;
                               │    canal (§11)           │  si no queda ninguno,
                               └────────────┬─────────────┘  DESCARTADO
                               ┌────────────▼─────────────┐
                               │ 5. INSERT reenvios × N   │
                               │    UPDATE sms PROCESADO  │
                               └────────────┬─────────────┘
                                            │ ColaDeEnvios.encolar()
                               ┌────────────▼─────────────┐
                               │     DispatchWorker       │  uno por reenvío
                               └────────────┬─────────────┘
                                            │ Transportes.de(canal)
          ┌──────────────────┬──────────────┼──────────────┬──────────────────┐
  ┌───────▼───────┐  ┌───────▼───────┐ ┌────▼────────┐ ┌───▼───────────┐
  │ WhatsAppSender│  │TelegramSender │ │  SmsSender  │ │   ApiSender   │
  │ POST /messages│  │POST           │ │ multiparte, │ │ GET a la URL  │
  │ texto libre o │  │/sendMessage   │ │ espera cada │ │ sin seguir    │
  │ plantilla     │  │sin parse_mode │ │ sentIntent  │ │ redirecciones │
  └───────┬───────┘  └───────┬───────┘ └────┬────────┘ └───┬───────────┘
          └──────────────────┴──────────────┼──────────────┘
                               ┌────────────▼─────────────┐
                               │  Enviado          → ENVIADO
                               │  ErrorTransitorio → retry
                               │  ErrorPermanente  → FALLIDO
                               └──────────────────────────┘
```

En paralelo, dos workers vigilan el sistema:

```
OrphanRescueWorker   cada 30 s (auto-encadenado)
  ├─ SMS en PENDIENTE sin evaluar      → vuelve a tramitarlos
  └─ reenvíos PENDIENTE sin worker vivo → los reencola, por su canal

HealthMonitorWorker  cada 15 min (periódico)
  ├─ borra reenvíos ENVIADO con más de 30 días
  ├─ borra SMS ya tramitados con más de 30 días (CASCADE sobre sus reenvíos)
  └─ borra entradas de log con más de 30 días
```

### Por qué el receptor delega en un servicio

`BroadcastReceiver.onReceive()` se ejecuta en el hilo principal con un límite de unos 10 segundos,
y el proceso puede ser eliminado en cuanto retorna. El `ForegroundService` mantiene el proceso
vivo —con notificación visible obligatoria— hasta que el `INSERT` en Room se completa. Eso es lo
que garantiza que **ningún SMS se pierde**, incluso con el sistema bajo presión de memoria.

Cada llamada a `onStartCommand()` recibe un `startId` único, y `stopSelf(startId)` solo detiene
el servicio si era el último activo. Dos SMS simultáneos se procesan sin interferirse.

---

## 4. Diagrama de capas

```
presentation/
├── dashboard/      SMS recibidos, reenvíos por canal, avisos por canal, mensajes de WhatsApp del mes
├── rules/          Lista, editor con el canal arriba y validación en vivo, panel de prueba
├── logs/           Registro con filtros por tipo y por canal, y exportación a fichero
├── settings/       Recepción, fiabilidad, canales, una sección por canal, límites, reintentos y copia
└── about/          Marcadores, privacidad, responsabilidad, actualizaciones, código abierto, donación

domain/
├── model/          SmsMessage · Regla · Reenvio · SmsConReenvios
│                   EstadoSms · EstadoReenvio · ResumenSms · LogEntry · LogTipo
├── rules/          EvaluadorDeReglas · ResultadoEvaluacion · PlantillaDeUrl · NormalizadorTelefono
├── transport/      EnviadorDeMensajes · ResultadoEnvio · Canal · Transportes  ← la juntura
└── usecase/        ProcesarSmsEntranteUseCase · ObtenerListaSmsUseCase

data/
├── api/            ApiSender · MapeoDeRespuestas
├── config/         AppConfig · ConfigDataStore · ModoDeEntrega · ConfigBackup
├── local/db/       SmsDatabase · entidades · DAOs · relaciones · Migraciones
├── repository/     Sms · Regla · Reenvio · Log (contrato + implementación)
├── rules/          ReglasJson (formato 6) · ImportacionDeCopia
├── sms/            SmsSender · ProveedorDeSims
├── telegram/       TelegramSender · MapeoDeRespuestas · DestinoDeTelegram · TokenDeBot
│                   ComprobadorDelBot · DescubridorDeChats · EstadoDelBot
├── whatsapp/       WhatsAppSender · MapeoDeRespuestas · PlantillaDeWhatsApp · TokenDeAcceso
│                   IdNumeroTelefono · DestinoDeWhatsApp · ComprobadorDeCuenta
│                   EstadoDeLaCuenta · ContadorDeMensajes
└── update/         ComprobadorDeActualizaciones · DescargaDeApk · VersionPublicada

worker/             DispatchWorker · ColaDeEnvios
                    OrphanRescueWorker · HealthMonitorWorker
receiver/           SmsReceiver · BootReceiver
service/            SmsIngestionService
di/                 DatabaseModule · ConfigModule · RepositoryModule
                    WorkerModule · TransportModule
util/               Constants · Extensions · OptimizacionBateria
                    InicioAutomatico · ControlRecepcion
```

---

## 5. Dependencias

### Versiones principales

| Componente | Versión |
|------------|---------|
| Kotlin | 2.4.21 |
| AGP | 9.4.1 |
| Gradle | 9.8.2 |
| KSP | 2.3.12 |
| compileSdk | 37 |
| targetSdk | 35 |
| minSdk | 26 |
| JVM target | 17 |
| Hilt | 2.60.1 |
| Room | 2.8.5 |
| WorkManager | 2.12.0 |
| DataStore Preferences | 1.3.0 |
| kotlinx.serialization | 1.12.0 |
| Coroutines | 1.11.0 |
| Material | 1.14.0 |
| Navigation | 2.10.2 |
| Timber | 5.0.1 |

`gradle/libs.versions.toml` es la única fuente de verdad de las versiones, y el toolchain es el
mismo que el de las cuatro aplicaciones anteriores: se subió en todas a la vez el 25 oct 2026.

**La red se usa sin ninguna dependencia de red.** SMSGateway usaba OkHttp 5 para un único `GET`;
aquí ese `GET`, las tres peticiones de Meta, las tres de Telegram (`sendMessage`, `getMe`,
`getUpdates`) y la de GitHub son una docena de líneas de `HttpURLConnection` cada una, con
`kotlinx.serialization` para las respuestas. Una dependencia nueva para eso no se paga.

### Nota sobre la cadena de herramientas

El wrapper es **Gradle 9.8.2** y AGP es **9.4.1**, con Kotlin integrado en AGP: no hay plugin
`kotlin-android`. La API de variantes antigua (`android.applicationVariants`) ya no existe, así
que el renombrado del APK de release pasa por `androidComponents.onVariants` y
`VariantOutputImpl`, que es interna de AGP: si una versión futura la cambia, el APK vuelve a
llamarse `app-release.apk`. R8 aplica el modo completo estricto, así que las reglas `-keep` de
clases que se instancian por reflexión piden el constructor con `{ <init>(); }`.

---

## 6. Cómo compilar

### Requisitos previos

- JDK **25** (Temurin 25). `gradle/gradle-daemon-jvm.properties` lo exige: Gradle se niega a
  arrancar con otro.
- Android SDK con la plataforma 37.
- `local.properties` con `sdk.dir`, o la variable `ANDROID_HOME` apuntando al SDK.

### Pasos

```bash
# 1. Clonar el proyecto
git clone <url-del-repositorio> && cd SMSRelay

# 2. Gradle exige el JDK 25: apunta a él si JAVA_HOME es otro
export JAVA_HOME="/ruta/al/jdk-25"

# 3. Build debug
./gradlew assembleDebug

# 4. Tests
./gradlew test

# 5. Build release (firma opcional)
./gradlew assembleRelease
```

### Firma de release

Las claves se leen de `local.properties`, que no se versiona:

```properties
KEYSTORE_PATH=../../Claves/capicua.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=capicua
KEY_PASSWORD=...
```

El almacén vive **fuera del repositorio** y lo comparten las demás aplicaciones de la casa: una
misma clave las firma todas, con una sola entrada cuyo alias es `capicua`. `KEYSTORE_PATH` se
escribe relativa para que el proyecto se pueda mover de sitio, y **resuelve desde el módulo
`app/`**, no desde la raíz, porque es ahí donde la interpreta el `file()` de `app/build.gradle.kts`.

Que la clave sea compartida tiene una consecuencia: toda actualización futura tiene que firmarse
con ese mismo almacén. Si se pierde, la aplicación instalada ya no se puede actualizar — solo
desinstalar y volver a instalar, perdiendo sus datos.

Si falta alguna de las cuatro claves, el build **no falla**: se omite la firma y el APK sale sin
firmar. Si están las cuatro pero la ruta o el alias son incorrectos, el build **sí** falla, en
`validateSigningRelease`.

### Variantes de build

| Variante | applicationId | Minificación | Firma |
|----------|---------------|--------------|-------|
| `debug` | `com.capicua.smsrelay.debug` | No | Clave de depuración |
| `release` | `com.capicua.smsrelay` | R8 activado | Clave compartida, si está configurada |

Los dos `applicationId` son distintos, así que las dos variantes pueden convivir en el mismo
dispositivo, y las dos con las cuatro aplicaciones anteriores. **No comparten base de datos**.

---

## 7. Cómo instalar

```bash
# 1. Habilitar depuración USB en el dispositivo
#    Ajustes → Acerca del teléfono → Número de compilación (×7)
#            → Opciones de desarrollador → Depuración USB

# 2. Verificar conexión
adb devices

# 3. Instalar
adb install app/build/outputs/apk/debug/app-debug.apk

# 4. Conceder permisos sin diálogos
adb shell pm grant com.capicua.smsrelay android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smsrelay android.permission.READ_SMS
adb shell pm grant com.capicua.smsrelay android.permission.POST_NOTIFICATIONS
#    Solo si alguna regla sale por SMS, y para elegir la SIM:
adb shell pm grant com.capicua.smsrelay android.permission.SEND_SMS
adb shell pm grant com.capicua.smsrelay android.permission.READ_PHONE_STATE

# 5. Eximir del modo Doze (OBLIGATORIO)
adb shell dumpsys deviceidle whitelist +com.capicua.smsrelay

# Verificar
adb shell dumpsys deviceidle whitelist | grep capicua
```

### Por qué el paso 5 es obligatorio

El **modo Doze** suspende los procesos en segundo plano cuando la pantalla lleva unos minutos
apagada. WorkManager respeta Doze y puede retrasar `DispatchWorker` horas. Eximir la aplicación es
lo que hace que un SMS se reenvíe en segundos, por cualquier canal, y no cuando al sistema le
parezca.

Sin cable, la propia aplicación lo resuelve: **Ajustes** → **Fiabilidad en segundo plano** dice
si la exención está concedida y el botón abre la pantalla del sistema que la concede.
`util/OptimizacionBateria.kt` consulta el estado con `PowerManager.isIgnoringBatteryOptimizations()`
y prueba tres destinos en cadena —el diálogo de exención directa, la lista completa de
optimización de batería y la ficha de la aplicación—, porque ningún fabricante garantiza el
primero. Se prueba lanzando y capturando `ActivityNotFoundException` en lugar de consultar al
`PackageManager`: desde Android 11 la visibilidad de paquetes puede decir que no hay nada cuando
sí lo hay.

Los pasos manuales desde la interfaz del dispositivo, fabricante por fabricante, están en
[`CLAUDE.md`](../CLAUDE.md).

### Probar sin SIM ni coste

Un emulador permite simular la recepción:

```bash
adb emu sms send +34600112233 "Tu codigo es 4821"
```

Para probar el canal SMS, dos emuladores pueden mandarse SMS usando su número de puerto (`5554`,
`5556`…) como número de teléfono. Para el canal API vale cualquier servidor que registre las
peticiones que recibe; desde el emulador, `10.0.2.2` es la máquina anfitriona.

---

## 8. Permisos requeridos

| Permiso | Tipo | Para qué |
|---------|------|----------|
| `RECEIVE_SMS` | Peligroso | Recibir el broadcast de SMS entrantes |
| `READ_SMS` | Peligroso | Leer el contenido de las PDU |
| `SEND_SMS` | Peligroso | Reenviar por el canal SMS. **Se pide al elegir ese canal** |
| `READ_PHONE_STATE` | Peligroso | Enumerar las SIM para elegir con cuál enviar. **Se pide al pulsar ese botón** |
| `INTERNET` | Normal | Entregar por WhatsApp, Telegram y la API, y comprobar versiones |
| `POST_NOTIFICATIONS` | Peligroso (API 33+) | Notificación del foreground service |
| `REQUEST_INSTALL_PACKAGES` | Normal | Abrir el instalador con el APK de una versión nueva |
| `FOREGROUND_SERVICE` | Normal | Ejecutar el servicio de ingesta |
| `FOREGROUND_SERVICE_DATA_SYNC` | Normal | Tipo de servicio declarado (API 34+) |
| `RECEIVE_BOOT_COMPLETED` | Normal | Reprogramar los workers tras reiniciar |
| `WAKE_LOCK` | Normal | Completar el trabajo con la pantalla apagada |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Normal | Solicitar la exención de Doze |

**`SEND_SMS` y `READ_PHONE_STATE` volvieron con el canal SMS**, y se piden en el momento en que
hacen falta: quien no use ese canal no ve nunca ninguno de los dos diálogos. Que estén en el
manifiesto tiene un precio que el masterplan reconoce: SMStoTelegram y SMStoWhats presumían de no
tenerlos. Ninguna de las cinco aplicaciones puede estar en Google Play sin una declaración
aprobada: `RECEIVE_SMS`, `READ_SMS` y `SEND_SMS` son del mismo grupo restringido.

También se declara `<uses-feature android:name="android.hardware.telephony" required="true" />`:
sin telefonía no entra ningún SMS y la aplicación no tiene nada que hacer.

### Seguridad del BroadcastReceiver

`SmsReceiver` se declara con `android:permission="android.permission.BROADCAST_SMS"`, de modo que
solo el proceso del sistema (radio/teléfono) puede dispararlo. Ninguna aplicación de terceros
puede inyectar un SMS falso.

El receptor de confirmaciones que usa `SmsSender` se registra en tiempo de ejecución como
`RECEIVER_NOT_EXPORTED`, con un `Intent` limitado al propio paquete y un `PendingIntent` inmutable
por parte: ninguna otra aplicación puede fingir una confirmación de envío, y el `requestCode` de
cada parte impide que dos entregas simultáneas se crucen las respuestas.

### Las credenciales

Hay tres: el **token de acceso** y el **`phone_number_id`** de WhatsApp, y el **token del bot** de
Telegram. Las reglas que el código respeta con las dos que son secretas —los dos tokens—: no se
escriben nunca en el registro —el de Meta va en la cabecera `Authorization` y el del bot en la
ruta, así que tampoco se registra la URL de Telegram—, se enmascaran en pantalla, y no viajan en la
copia de seguridad salvo que quien exporta lo pida expresamente.

La URL de la API puede llevar una clave dentro, así que el registro la escribe **sin la consulta**
(`PlantillaDeUrl.sinQuery()`).

---

## 9. Reglas de reenvío

Las reglas son el centro de la configuración. Viven en la tabla `reglas` y se editan en la
pestaña **Reglas**.

### Campos

| Campo | Obligatorio | Descripción |
|-------|-------------|-------------|
| `orden` | Automático | Prioridad. Menor valor = se evalúa antes |
| `nombre` | Sí | Etiqueta para reconocerla en la lista y en el registro |
| `canal` | Sí | `WHATSAPP` (por defecto), `TELEGRAM`, `SMS` o `API` |
| `regexTelefono` | No | Expresión sobre el remitente. Vacío = no filtra |
| `regexMensaje` | No | Expresión sobre el cuerpo. Vacío = no filtra |
| `ignorarMayusculas` | — | true = las dos expresiones se aplican sin distinguir mayúsculas |
| `destino` | Sí | Su forma depende del canal: ver «Destino» |
| `plantilla` | Sí | Texto a enviar. Por defecto depende del canal: ver «Plantilla» |
| `activa` | — | Si es false, la regla se ignora sin borrarla |
| `continuar` | — | Si es true, tras casar sigue evaluando las siguientes |

### Semántica de la coincidencia

Una regla casa cuando **ambos** criterios se cumplen. Un criterio vacío (null o en blanco) se
considera cumplido, de modo que **una regla sin patrones casa con todos los SMS** —que es
exactamente lo que hacía SMSGateway, y lo que crea la importación de una copia suya—. Es potente y
fácil de crear por descuido, así que la lista lo indica explícitamente.

### Mayúsculas

Por defecto las expresiones distinguen mayúsculas. `ignorarMayusculas` añade
`RegexOption.IGNORE_CASE` **a las dos a la vez** en el momento de compilar, de modo que el patrón
guardado no se toca. Es acumulativo con un `(?i)` escrito dentro del patrón, y la diferencia
práctica es el alcance: el ajuste afecta a las dos expresiones, el modificador solo a la suya.

`validarPatron()` no recibe el ajuste a propósito: ignorar mayúsculas no puede hacer que un patrón
válido deje de compilar.

La búsqueda es **parcial**, con `Regex.containsMatchIn`:

```
patrón: codigo          mensaje: "Tu codigo es 4821"     → CASA
patrón: ^codigo$        mensaje: "Tu codigo es 4821"     → NO casa
patrón: ^codigo$        mensaje: "codigo"                → CASA
patrón: CODIGO          mensaje: "tu codigo es 4821"     → NO casa
patrón: (?i)CODIGO      mensaje: "tu codigo es 4821"     → CASA
```

El remitente se compara **tal como llega en la PDU**, sin normalizar, porque muchos avisos llegan
de cabeceras alfanuméricas (`BANCO`, `AMAZON`) y no de un número.

### Orden de evaluación

```
reglas activas de canales encendidos, ordenadas por `orden` ascendente
  │
  ├─ regla con regex inválida  → se descarta, se anota en el log, sigue la evaluación
  ├─ regla que no casa         → sigue la evaluación
  └─ regla que casa            → una coincidencia: canal, destino resuelto, texto
                                 │
                                 ├─ continuar = false → FIN
                                 └─ continuar = true  → sigue la evaluación
```

Una expresión regular que no compila **no interrumpe la evaluación**: la regla se descarta y las
demás siguen funcionando, pero queda registrada como `ERROR` en el log. El editor valida los
patrones mientras se teclean para que ese caso no llegue a producción.

Con cuatro canales, `continuar` gana un uso que antes no tenía: **el mismo SMS por dos medios**,
con dos reglas iguales salvo el canal y el destino.

### Destino

El mismo campo significa una cosa distinta en cada canal:

| Canal | Forma | Validación | Ejemplo |
|-------|-------|------------|---------|
| WhatsApp | E.164: `+`, prefijo de país, 8 a 15 dígitos | `DestinoDeWhatsApp.pareceValido()` | `+34600112233` |
| Telegram | Un `chat_id` (negativo en grupos y canales) o un `@usuario` de canal | `DestinoDeTelegram.pareceValido()` | `-1001234567890`, `@avisos_de_casa` |
| SMS | Un teléfono tal como se marca, nacional o internacional, o `{telefono}` | Dígitos y `+ -()`, al menos 4 | `600112233`, `{telefono}` |
| API | Una URL `http(s)` con `{mensaje}` | `PlantillaDeUrl.validar()` | `https://h/sms?t={mensaje}&de={telefono}` |

Los separadores con los que la gente escribe un teléfono se normalizan en WhatsApp al guardar. En
SMS se dejan como estén: `SmsManager` los acepta y el número se marca tal cual.

#### El editor detecta el destino de otro canal

El error más probable de una aplicación con cuatro canales en el mismo campo es pegar un destino
con el canal equivocado. `DestinoDeTelegram.esInequivocamenteChat()` reconoce lo que solo puede ser
un chat —un id **negativo** o un `@usuario`; un id positivo podría ser un teléfono— y el editor dice
*«esto parece un chat de Telegram: cambia el canal»* en lugar de *«no tiene forma de teléfono»*, que
sería verdad y no ayudaría. La importación de copias aplica la misma comprobación.

#### Por qué el `+` es obligatorio en WhatsApp, y no en SMS

La Cloud API acepta el número con `+` y sin él, así que exigirlo no es un requisito técnico: es lo
que hace posible dar un error útil. `600112233` podría ser un móvil español sin prefijo —inservible
para Meta— o un número internacional completo de un país con prefijo corto. Exigiendo el `+` se
puede decir exactamente lo que pasa: *«falta el prefijo internacional, escribe +34…»*.
`DestinoDeWhatsApp.faltaPrefijo()` existe solo para ese mensaje, y no se dispara con lo que empieza
por `-`, que merece el mensaje del chat de Telegram.

En SMS, en cambio, un número nacional es exactamente lo correcto: es lo que la SIM sabe marcar.
Por eso las copias de SMStoSMS, con sus números nacionales, se importan sin rechazo (§9, «Exportar
e importar»).

#### Solo personas en WhatsApp

Por decisión. La Cloud API admite grupos desde 2026, pero solo creados por ella, por invitación,
de hasta ocho participantes y exigiendo Cuenta Oficial de Empresa. Para grupos está Telegram.

#### Qué marcadores se resuelven en el destino, y cuáles nunca

Es una propiedad de seguridad: **el cuerpo de un SMS ajeno no puede decidir adónde se entrega
nada**. Lo resuelve `EvaluadorDeReglas`, y el transporte recibe un destino ya hecho.

- **WhatsApp y Telegram: ninguno.** SMStoTelegram quitó `{telefono}` del destino porque un chat y
  un teléfono son identidades sin relación; SMStoWhats no lo devolvió aunque habría sido posible,
  porque una cuenta de empresa que factura por mensaje no debe escribir a quien un tercero decida.
- **SMS: `{telefono}`, y solo como destino entero.** Es la función original de SMStoSMS —contestar
  a quien escribió—, y la coincidencia queda marcada `respondeAlRemitente`. No elige a nadie nuevo:
  contesta a quien ya escribió. `{telefono}` mezclado con más texto se rechaza en el editor.
- **API: los tres, nunca en el servidor.** `PlantillaDeUrl.resolver()` sustituye `{telefono}`,
  `{fecha}` (en **ISO 8601 UTC**, como en SMSGateway: la lee una máquina) y, el último,
  `{mensaje}`, todos URL-codificados con `URLEncoder` y `+` convertido en `%20`.
  `PlantillaDeUrl.validar()` **rechaza cualquier marcador en la autoridad** —entre `://` y el
  primer `/`, `?` o `#`—, de modo que un SMS puede decidir *qué* se manda pero nunca *a qué
  servidor*. SMSGateway sí lo permitía.

### Plantilla

```
{mensaje}    → cuerpo del SMS recibido
{telefono}   → número del remitente
{fecha}      → fecha y hora de recepción, en hora local
```

`{mensaje}` se sustituye **en último lugar**, a propósito. El cuerpo del SMS es contenido ajeno: si
se sustituyera primero, un mensaje que contuviera literalmente `{telefono}` acabaría revelando el
número del remitente donde la plantilla no lo pedía.

```
plantilla: "De {telefono}: {mensaje}"
mensaje:   "hoy es {fecha}"
resultado: "De +34600112233: hoy es {fecha}"   ← el marcador del cuerpo queda literal
```

### La plantilla por defecto depende del canal

`Regla.plantillaPorDefecto(canal)`:

- **`{telefono} · {fecha}` con el mensaje debajo** para WhatsApp, Telegram y SMS. Con el SMS de
  SMStoSMS bastaba el cuerpo, porque el reenvío llegaba como un SMS más y el teléfono enseñaba de
  quién venía; por WhatsApp y Telegram **todos los avisos llegan del mismo emisor**, y por SMS desde
  SMSRelay, del número de la pasarela. Cuatro mensajes seguidos sin el remitente dentro son cuatro
  mensajes indistinguibles.
- **`{mensaje}`** para la API (`PLANTILLA_POR_DEFECTO_API`). El texto viaja dentro de la URL, que
  ya lleva sus propios `{telefono}` y `{fecha}` donde la API los espere.

Al cambiar de canal en una regla **nueva**, el editor cambia la plantilla por la del canal si
seguía siendo la por defecto de otro; en una regla existente no toca lo escrito.

### Longitud

Cada canal tiene su límite, y `EnviadorDeMensajes.limiteCaracteres()` lo dice:

| Canal | Límite | Qué pasa al pasarlo |
|-------|--------|---------------------|
| WhatsApp, texto libre | 4.096 | Se trocea |
| WhatsApp, plantilla | 980 (`PlantillaDeWhatsApp.PRESUPUESTO`) | Se trocea, **y cada trozo se factura** |
| Telegram | 4.096 | Se trocea |
| SMS | 160 por parte | Se fragmenta con `divideMessage` y llega como un solo SMS, facturado por parte |
| API | 1.500 | **No** se trocea: el reenvío falla con su motivo |

El editor y el panel de prueba muestran la longitud contra el límite del canal elegido. Trocear
parte el texto por el último salto de línea del trozo, o por el último espacio, y solo parte una
palabra cuando no queda hueco: cortar y tirar el resto perdería contenido en silencio. La API es la
excepción porque una petición por trozo no es lo que espera ningún servidor; URL-codificado, un
acento ocupa seis caracteres, y por eso el editor avisa pronto.

### Exportar e importar

La copia de seguridad se hace desde **Ajustes → Copia de seguridad**, no desde la pantalla de
Reglas: las reglas son configuración. Lo implementa `data/config/ConfigBackup`, sobre un `Uri` del
Storage Access Framework, sin ningún permiso de almacenamiento. Lo que entra lo decide
`data/rules/ImportacionDeCopia`, que es puro y está probado con un fichero real de cada aplicación.

Un fichero completo (formato 6):

```json
{
  "version": 6,
  "exportado": "2026-10-25T10:00:00Z",
  "configuracion": {
    "max_reintentos": 10,
    "intervalo_reintento_segundos": 30,
    "timeout_envio_segundos": 60,
    "max_reenvios_por_minuto": 10,
    "recepcion_activa": true,
    "modo_entrega": "AUTOMATICO",
    "plantilla_nombre": "reenvio_sms",
    "plantilla_idioma": "es",
    "max_mensajes_por_mes": 900,
    "token_acceso": null,
    "id_numero_telefono": null,
    "token_bot": null,
    "proteger_bucles": true,
    "aceptar_certificados_invalidos": false
  },
  "reglas": [
    {
      "nombre": "Banco",
      "regex_telefono": "^BANCO$",
      "regex_mensaje": null,
      "ignorar_mayusculas": false,
      "canal": "TELEGRAM",
      "destino": "-1001234567890",
      "plantilla": "{telefono} · {fecha}\n{mensaje}",
      "activa": true,
      "continuar": false
    }
  ]
}
```

| Versión | Cambio | App |
|---------|--------------------------------------------|-----|
| 1 | Formato inicial, solo reglas. | SMStoSMS |
| 2 | La regla gana `ignorar_mayusculas`. | SMStoSMS |
| 3 | El fichero gana el bloque `configuracion`. | SMStoSMS |
| 4 | **`destino` es un chat de Telegram, no un teléfono.** | SMStoTelegram |
| 5 | **`destino` vuelve a ser un teléfono, en E.164.** | SMStoWhats |
| 6 | **Cada regla lleva su `canal`**, y el fichero los ajustes de los cuatro. | SMSRelay |

SMSGateway tenía su propio formato 1 con otra forma —`urlTemplate` y los reintentos, sin reglas— y
se reconoce **por la forma**, no por el número.

#### El número de formato, que servía para rechazar, ahora sirve para importar

SMStoTelegram rechazó los ficheros del 3 y SMStoWhats los del 4 por la misma razón: tenían
**exactamente la misma forma** que los suyos, y lo único que cambiaba era lo que significaba
`destino`. Entrarían sin un error de parseo y dejarían reglas cuyos destinos no existen.

Con cuatro canales esa ambigüedad desaparece: **el número de formato dice qué aplicación escribió
el fichero, y por tanto por qué canal salían sus reglas**. `ReglasExportadas.MINIMA_ACEPTADA` es 1:

| Fichero de | Formato | Canal de las reglas | Ajustes que aplica |
|---|---|---|---|
| SMSRelay | 6 | El de cada regla; uno desconocido rechaza **esa regla** | Todos |
| SMStoWhats | 5 | WhatsApp | Comunes y de WhatsApp |
| SMStoTelegram | 4 | Telegram | Comunes y de Telegram |
| SMStoSMS | 1, 2 y 3 | SMS | Comunes y `proteger_bucles` (el 1 y el 2 no traen ajustes) |
| SMSGateway | su 1 | API: **una** regla que casa con todo, destino la `urlTemplate`, plantilla `{mensaje}` | Comunes (`timeoutSegundos` → `timeoutEnvioSegundos`) y `aceptarCertificadosInvalidos` |

Lo único que se sigue rechazando entero es lo que no se sabe interpretar: un formato **posterior**
al 6, o algo que no es JSON.

#### Los ajustes ya no se sustituyen enteros

Era la regla de las cuatro anteriores, y con cuatro canales no vale: importar la copia de
SMStoTelegram no puede dejar a cero el modo de entrega de WhatsApp. `ConfiguracionJson.aplicarSobre`
aplica los **comunes** —reintentos, intervalo, plazo, tope por minuto, recepción— y **los del canal
del fichero**, y deja los demás como estaban. Por eso los campos de cada canal son opcionales en el
JSON: un fichero del 4 no trae nada de WhatsApp, y leerlo con valores por defecto pisaría lo que
hubiera.

#### Lo que no viaja en el fichero

Los **ids** no viajan: son locales a la base de datos de cada dispositivo.

| Dato | ¿Viaja? | Por qué |
|------|---------|---------|
| Modo de entrega, nombre e idioma de la plantilla, topes, reintentos, plazo | **Sí** | Describen cómo reenviar |
| Guarda antibucle, aceptar certificados no válidos | **Sí** | Igual |
| Token de acceso, `phone_number_id`, token del bot | **Solo si se pide** | El fichero es JSON en claro, y cada uno es permiso para escribir en nombre de alguien |
| SIM de envío (`subscriptionId`) | **Nunca** | Apunta a una tarjeta de *este* teléfono; restaurada en otro mandaría los SMS por donde no es |

**Una credencial ausente o en blanco en el fichero no pisa la del teléfono.** Sustituir una
credencial que funciona por nada es el peor resultado posible de una importación.

#### Las reglas se añaden, y se validan por canal

Las reglas se añaden al final, conservando el orden relativo del fichero. Importar nunca borra: una
importación equivocada que vaciara la lista dejaría el teléfono sin reenviar nada. Cada regla se
valida antes de escribirse —nombre no vacío, expresiones que compilen y **un destino con la forma
de su canal**— y las rechazadas se cuentan aparte. Una URL de SMSGateway con un marcador en el
servidor se cuenta como rechazada.

#### Lo que se dice al terminar

El Snackbar dice **de qué aplicación venía la copia** y cuántas reglas entraron y cuántas no. Y
como las credenciales normalmente no viajan, lo normal es que algún canal importado no pueda
entregar todavía: `ConfigBackup` pregunta a cada transporte de las reglas importadas su
`motivoNoDisponible()` **después** de guardar los ajustes, y si alguno tiene motivo, un diálogo lo
dice canal por canal —*«3 regla(s) por Telegram: falta el token del bot»*—. Es un diálogo y no un
Snackbar porque es lo siguiente que hay que hacer.

---

## 10. Mecanismo de entrega

### La juntura, y qué le debe el transporte al worker

`DispatchWorker` no sabe por qué medio sale un reenvío. Pide `Transportes.de(reenvio.canal)` y
depende solo de `EnviadorDeMensajes`, que declara tres cosas:

| Miembro | Para qué |
|---------|----------|
| `enviar(destino, texto, timeoutSegundos)` | Entregar y **esperar** el desenlace |
| `limiteCaracteres(): Int` | Avisar mientras se escribe la plantilla, que es cuando se puede hacer algo |
| `motivoNoDisponible(): String?` | Avisar **antes** de que falle: en el Panel, en Ajustes y al importar |

`motivoNoDisponible` devuelve el **motivo en texto** y no un booleano porque la pantalla tiene que
poder decir *por qué*, y los motivos cambian con el medio: falta el token, Telegram lo rechazó,
falta el permiso de SMS. `limiteCaracteres` **suspende** porque en WhatsApp depende de la
configuración (texto libre o plantilla); es el único cambio de firma que la juntura ha sufrido.

**Esperar el desenlace no es un detalle del medio.** Con `SmsManager` significa suspender hasta que
llegue el broadcast del `sentIntent` de cada parte, porque la llamada de envío retorna antes de que
el mensaje salga del dispositivo; con los otros tres, esperar la respuesta HTTP. Si la llamada
retornase antes de conocer el resultado, **todas las entregas parecerían correctas y los fallos
reales desaparecerían**.

**`Transportes.de()` devuelve null en lugar de lanzar** si un canal no tiene transporte. No es un
error de programación: pasó mientras se escribían los transportes —una regla podía apuntar a un
canal que la compilación aún no entregaba— y pasaría si algún día se retira uno. El worker lo
convierte en un `ErrorPermanente` con el motivo de `motivoSinTransporte()`.

**La restricción de red es del canal** (`ColaDeEnvios.redNecesaria`):

| Canal | Restricción | Por qué |
|---|---|---|
| WhatsApp, Telegram | `CONNECTED` | Entregan por internet; sin la restricción, un reenvío encolado en un túnel se gastaría sus diez intentos contra una red que no está |
| SMS | `NOT_REQUIRED` | Sale por la radio. La falta de cobertura la reporta el propio envío |
| API | `NOT_REQUIRED` | Un servidor en la red local es un caso legítimo, y Android no marca como «con internet» una red sin salida |

`CONNECTED` y no `UNMETERED`: un mensaje de texto por datos móviles no cuesta nada que importe.

### El cuerpo del SMS no se interpreta nunca

Es la misma regla que el evaluador aplica sustituyendo `{mensaje}` en último lugar, y en la entrega
se concreta canal por canal:

- **Telegram: sin `parse_mode`.** Ponerlo haría que cualquier SMS con un `_`, un `*`, un `[` o un `<`
  volviera como `400 can't parse entities` y el reenvío muriera por haber querido negrita.
- **WhatsApp: el formato se acepta tal como salga.** WhatsApp no tiene ese interruptor:
  **interpreta `*negrita*` y `_cursiva_` siempre**. Escapar el cuerpo sería interpretar contenido
  ajeno para modificarlo, y el mensaje llega entero de las dos formas.
- **WhatsApp: `preview_url` en `false`, y eso no es estética.** En `true`, WhatsApp **descarga** el
  primer enlace del cuerpo para su vista previa, y como el cuerpo es un SMS ajeno, entregar un
  mensaje provocaría una petición de los servidores de Meta a una URL elegida por quien mandó ese
  SMS: desde confirmar que un enlace de phishing tiene público hasta quemar un enlace de un solo
  uso. No es configurable.
- **API: URL-codificado**, y nunca en el servidor (§9).

### WhatsApp

#### Texto libre, plantillas, y por qué las plantillas son el camino principal

**Esta es la subsección que hay que leer antes que ninguna otra de las de WhatsApp.** La Cloud API
tiene dos clases de mensaje y no son intercambiables:

| | Mensaje de servicio (texto libre) | Mensaje de plantilla |
|---|---|---|
| Cuándo se puede mandar | Solo dentro de una **ventana de 24 h** que abre el destinatario escribiendo al número emisor | **Siempre**, sin contacto previo |
| Contenido | Texto libre, hasta **4.096** caracteres | Un cuerpo que Meta ha aprobado, hasta **1.024** renderizados |
| Saltos de línea en la parte variable | Sí | **No**, ni tabuladores, ni más de cuatro espacios seguidos |
| Aprobación previa | No | Sí, por plantilla y por idioma |
| Coste | Los primeros **1.000 al mes y por número**, gratis; el resto se factura desde el 1 oct 2026 | **Cada** mensaje se factura |

Un reenviador de SMS es, por definición, un emisor **no solicitado**: el SMS llega cuando llega y
hay que entregarlo entonces. Por tanto:

> **Las plantillas no son el plan B de este canal: son el camino principal.** El texto libre es la
> optimización que se aplica cuando resulta que la ventana está abierta.

#### El contrato de la plantilla: una sola variable

La plantilla la crea el operador en el Business Manager —esta aplicación no puede crearla— y su
cuerpo tiene que ser exactamente `PlantillaDeWhatsApp.CUERPO_RECOMENDADO`:

```
Reenvío de SMS

{{1}}

— SMSRelay
```

Tres propiedades, y las tres son a propósito:

1. **Los saltos de línea están en la plantilla, no en la variable.** Meta aprueba el cuerpo una
   vez, con su maquetación dentro. Lo que **no** puede llevar saltos de línea, tabuladores ni más
   de cuatro espacios seguidos es el *valor* que se pasa en `{{1}}`: hacerlo devuelve un `132007`
   y el reenvío muere. De ahí `aplanar()`, que convierte los saltos en ` · ` y colapsa el resto.
2. **No empieza ni acaba con la variable**, que Meta no admite.
3. **Una variable y no tres.** Remitente, fecha y texto en tres huecos daría mejor maquetación y
   rompería algo que importa más: habría una **segunda** vía de composición del mensaje que
   ignoraría la `plantilla` de cada regla. Con una variable el texto lo compone siempre
   `EvaluadorDeReglas`, en los dos modos y en los cuatro canales.

`PlantillaDeWhatsApp.PRESUPUESTO` se deriva de ese contrato —1.024 menos el texto fijo menos un
margen— porque la aplicación **no conoce el cuerpo aprobado**. Hoy vale **980**: subió de 978 al
cambiar la firma de `— SMStoWhats` a `— SMSRelay`, y una plantilla aprobada con la firma vieja
sigue sirviendo porque le sobran dos caracteres. `PlantillaDeWhatsAppTest` afirma el literal.

#### Los tres modos

`ModoDeEntrega`, en `data/config`, porque es configuración del transporte y la lee el transporte.

| Modo | Qué hace |
|------|----------|
| `AUTOMATICO` | Intenta texto libre; si Meta contesta `131047`, reenvía como plantilla |
| `SOLO_TEXTO_LIBRE` | Nunca gasta una plantilla. Con la ventana cerrada, no entrega |
| `SOLO_PLANTILLA` | No prueba el texto libre. Entrega siempre y factura siempre |

**El salto a plantilla en `AUTOMATICO` ocurre dentro del mismo intento del worker**, no como
reintento: la ventana seguiría cerrada diez intentos después, porque lo que la abre es que el
destinatario escriba. El transporte tiene un tipo interno `Entrega` que lleva el `ResultadoEnvio`
más un booleano `ventanaCerrada`, y eso se queda dentro del transporte en lugar de subir al worker.

**`AUTOMATICO` sin plantilla configurada se comporta como `SOLO_TEXTO_LIBRE`**, y el motivo que
queda en el registro dice que hace falta una plantilla y dónde se configura.

#### Mapeo de respuestas

Lo implementa `data/whatsapp/MapeoDeRespuestas.interpretarEnvio()`, **pura**, con
`MapeoDeRespuestasTest`. **El código HTTP no basta**: la Cloud API mete **cuatro averías distintas
en el mismo `400`** y las distingue en `error.code`.

| `error.code` | Interpretación | Acción WorkManager | Estado |
|--------------|----------------|--------------------|--------|
| — (2xx sin bloque `error`) | Entregado | `success()` | `ENVIADO` |
| `131047` | **Ventana de 24 h cerrada** | En `AUTOMATICO`, reenvía por plantilla | — |
| `131026` | El destino no tiene WhatsApp | `failure()` | `FALLIDO` |
| `131021` | Destino igual al número emisor | `failure()` | `FALLIDO` |
| `132000` `132001` `132005` `132007` `132012` `132015` `132016` | La plantilla y sus parámetros no cuadran | `failure()` | `FALLIDO` |
| `0` `3` `190` | **El token** | `failure()` | `FALLIDO` |
| `100` | **El `phone_number_id`** | `failure()` | `FALLIDO` |
| `130429` `80007` `131048` `131056` | Límite de caudal | `retry()`, sin espera sugerida | `PENDIENTE`, intentos++ |
| `131000` `133004`, timeout, `IOException` | Avería de Meta o sin red | `retry()` | `PENDIENTE`, intentos++ |
| Un `4xx` desconocido | La petición está mal | `failure()` | `FALLIDO` |
| Cualquier otra cosa | Desconocido, por prudencia | `retry()` | `PENDIENTE`, intentos++ |

- **Un `100` es un `phone_number_id` malo**, no un recurso que falta: lo que no existe es la ruta, y
  la ruta la forma la credencial. `esFalloDeCredencial()` agrupa `190`/`0`/`3` (el token) y `100`
  (el id).
- **Un `4xx` desconocido se cierra**: el reintento manda los mismos bytes a la misma URL.
- **El `131047` no se clasifica, se intercepta** con `esVentanaCerrada()`, para que el worker no se
  entere de que hay dos formas de entregar.
- **Un fallo de credencial no es un fallo de este reenvío**: con una credencial mal puesta fallan
  todos. `WhatsAppSender` lo anota en `EstadoDeLaCuenta`, persistido —la aplicación no está
  «abierta», cada SMS despierta el proceso y lo deja morir—, y el Panel lo enseña en rojo
  **diciendo WhatsApp**. Un envío correcto lo borra, y guardar las credenciales también.
- **Meta no manda ninguna espera**: `esperaSegundos` llega siempre null.

### Telegram

`TelegramSender` hace `POST /bot<token>/sendMessage` con el texto plano. La correspondencia la hace
`data/telegram/MapeoDeRespuestas`, **pura**, con su test:

| Respuesta | Resultado |
|---|---|
| `200` con `ok: true` | `Enviado` |
| `400` | `ErrorPermanente` — el chat no existe, el bot no está en él, el texto no vale |
| `401`, `404` | `ErrorPermanente` **y fallo de credencial**: la URL lleva el token dentro, así que un 404 es un token mal escrito |
| `403` | `ErrorPermanente` — el bot fue bloqueado o expulsado del chat |
| `429` | `ErrorTransitorio` **con `retry_after`** |
| `5xx`, timeout, `IOException` | `ErrorTransitorio` |

**`retry_after` duerme al worker.** Es la razón de que `ResultadoEnvio.ErrorTransitorio` tenga un
`esperaSegundos`: `DispatchWorker.esperarSiLoPiden()` duerme lo pedido, con un tope de
**120 segundos** (`MAX_ESPERA_SEGUNDOS`). WorkManager cuenta su backoff desde que el worker
termina, así que dormir dentro es la única forma de retrasar el siguiente intento; y un worker tiene
unos diez minutos antes de que el sistema lo pare, así que un `retry_after` enorme se respeta hasta
el tope y el backoff hace el resto. Ese código no dejó de existir en SMStoWhats —retornaba en su
primera línea— y volvió a dormir en cuanto llegó un transporte que manda la espera.

Un fallo de credencial lo anota `EstadoDelBot`, con el mismo papel que `EstadoDeLaCuenta`.

`DescubridorDeChats` usa `getUpdates` para listar quién ha escrito al bot en las últimas 24 horas;
es lo que hay detrás de *Elegir de los chats del bot* en el editor. Preparar el bot está en
[`BOT-TELEGRAM.md`](BOT-TELEGRAM.md).

### SMS

`SmsSender` divide el texto con `SmsManager.divideMessage`, crea un `PendingIntent` de confirmación
por parte y llama a `sendMultipartTextMessage`. Luego **suspende** hasta que llegan todas las
confirmaciones o vence el plazo de envío:

| Confirmación | Resultado |
|---|---|
| Todas las partes `RESULT_OK` | `Enviado` |
| `RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_RADIO_OFF`, sin confirmación en plazo | `ErrorTransitorio` — sin cobertura o modo avión; esperar lo arregla |
| `RESULT_ERROR_NULL_PDU`, un destino que `SmsManager` no acepta | `ErrorPermanente` |
| `RESULT_ERROR_GENERIC_FAILURE` | `ErrorTransitorio`, por prudencia |

- **La SIM** es `AppConfig.subscriptionId`; `SIM_POR_DEFECTO` (-1) usa la de SMS predeterminada del
  sistema. Una SIM guardada que ya no está en el teléfono no se sustituye sola: el envío falla con
  su motivo, y el selector de Ajustes la deja de ofrecer.
- **Sin `SEND_SMS` concedido** no se intenta: `motivoNoDisponible()` lo dice, y `enviar()` devuelve
  `ErrorPermanente` con el mismo motivo en lugar de dejar que `SmsManager` lance.
- Si una parte falla y otra salió, el reenvío se reintenta completo: el destinatario puede ver el
  principio dos veces, que es mejor que darle por bueno un mensaje al que le falta el final.

### API

`ApiSender` hace un `GET` a la URL ya resuelta, con `HttpURLConnection`:

- **Sin seguir redirecciones** (`instanceFollowRedirects = false`): seguir un 3xx mandaría el SMS a
  un sitio que nadie configuró. Un 3xx es `ErrorPermanente` con la ubicación en el motivo.
- **`aceptarCertificadosInvalidos`** instala, solo para esa conexión, un `SSLSocketFactory` y un
  verificador de nombre que aceptan cualquier cosa. Es para APIs en la red local con certificados
  propios, es global y no por regla, como en SMSGateway, y el aviso de privacidad dice lo que cuesta.
- **El registro nunca lleva la URL entera**: `PlantillaDeUrl.sinQuery()` le quita la consulta.

La correspondencia de `data/api/MapeoDeRespuestas`, **pura**, con su test:

| Código | Resultado | Por qué |
|---|---|---|
| `2xx` | `Enviado` | — |
| `429` | `ErrorTransitorio` | Demasiadas peticiones: esperar lo arregla |
| `3xx` | `ErrorPermanente` | No se siguen redirecciones |
| `4xx` salvo `429` | `ErrorPermanente` | La petición está mal, y un reintento manda los mismos bytes |
| `5xx` | `ErrorTransitorio` | El servidor no está disponible |
| Cualquier otra cosa, timeout, `IOException` | `ErrorTransitorio` | Por prudencia |

La API no tiene credenciales que la aplicación conozca —si las hay, van dentro de la URL—, así que
`motivoNoDisponible()` devuelve siempre null: la API está siempre «lista» hasta que falla.

### Textos troceados

Si un trozo falla después de que otro haya salido, **el reenvío entero se reintenta completo**,
aceptando que el destino pueda ver repetido el principio. Es el mismo criterio en WhatsApp,
Telegram y SMS: dar por bueno un mensaje al que le falta el final es peor que duplicar su principio.

### Idempotencia

Es de cliente, en dos niveles:

- **Antes de crear reenvíos:** `ProcesarSmsEntranteUseCase.procesar()` comprueba si el SMS ya
  tiene reenvíos. Si los tiene, no crea más y solo reencola los pendientes. Esto es lo que hace
  seguro que el rescate de huérfanos reprocese un SMS que se quedó a medias.
- **Antes de cada intento:** `DispatchWorker` lee el estado del reenvío y devuelve éxito sin
  tocar el transporte si ya está `ENVIADO`.

Además, `ColaDeEnvios` usa `ExistingWorkPolicy.KEEP`, así que un reenvío nunca tiene dos trabajos
simultáneos. No se da por hecho que ningún medio deduplique.

---

## 11. Topes y guardas

Hay dos topes y dos guardas, y desde SMSRelay **no todos son de todos los canales**. Es la sección
que más cambió con la mudanza, porque cada aplicación anterior tenía las protecciones de su medio
y aquí conviven los cuatro.

| Protección | Alcance | Cuándo | Qué hace al saltar |
|---|---|---|---|
| Tope por minuto | **Global** | Antes de evaluar | Descarta el SMS |
| Guarda: el remitente es un destino SMS | Solo SMS | Antes de evaluar | Descarta el SMS |
| Guarda: una regla SMS reenviaría al propio remitente | Solo SMS | Tras evaluar, por coincidencia | Omite ese reenvío |
| Tope mensual | **Solo WhatsApp** | Tras evaluar, por coincidencia | Omite los reenvíos de WhatsApp |

Si después de las omisiones no queda ningún reenvío, el SMS se descarta con el motivo de lo que
saltó. Todo queda como `LogTipo.LIMITE_ALCANZADO`, con el canal cuando es por reenvío, y el detalle
**empieza por el nombre** de lo que saltó: *Tope por minuto*, *Tope mensual de WhatsApp*,
*Guarda antibucle de SMS* o *Canal desactivado* (ver más abajo).

### Canales desactivados

La tarjeta *Canales* de Ajustes lleva un interruptor por canal, para quien no va a usar alguno
(`AppConfig.canalesDesactivados`; se guardan los apagados para que un canal futuro llegue
encendido). Un canal apagado:

- **conserva sus reglas**, que se ven atenuadas en la lista y con «canal desactivado», y se
  **apartan antes de evaluar**, como si estuvieran inactivas: no consumen la primera coincidencia.
  Se evalúan aparte solo para el registro: cada una que habría casado deja un `LIMITE_ALCANZADO`
  que empieza por *Canal desactivado*, y si no queda nada más que reenviar el SMS se descarta con
  ese motivo. *Probar reglas* lo dice igual;
- **entrega lo que ya estaba encolado**, como el interruptor de la recepción: corta lo nuevo, no lo
  que estaba en camino;
- **esconde su sección de Ajustes y su chip del editor** —salvo el de la regla que se edita, que
  se tiene que poder abrir y cambiar de canal—, y el Panel deja de avisar de él. La validación de
  «Guardar» no mira los campos de un canal apagado, porque no se ven;
- con el SMS apagado, la guarda antibucle del remitente no se aplica: no sale ningún SMS.

El interruptor se aplica al momento (`ConfigDataStore.cambiarCanal`, que escribe una sola clave) y
no deja apagar el último canal encendido: para dejar de reenviar está el de la recepción. Los
canales apagados viajan en la copia del formato 6; un fichero anterior no enciende ni apaga nada, y
si una importación trae reglas de un canal apagado el diálogo de avisos lo dice.

### Las guardas antibucle, que volvieron solo para el SMS

Reenviar por SMS tiene una propiedad que los otros tres medios no tienen: **puede realimentarse**.
Si el destino contesta —u otra pasarela devuelve el mensaje—, la respuesta entra por `SmsReceiver`
como cualquier otro SMS; si casa con una regla, se reenvía; y con los reintentos detrás, un error de
configuración se convierte en una factura. De ahí las dos guardas de SMStoSMS, que vuelven con su
`AppConfig.protegerBucles` (activado por defecto), su `NormalizadorTelefono` y su
`ReglaDao.obtenerDestinosSmsActivos()`:

1. **El remitente es el destino de una regla SMS activa** → el SMS se descarta.
2. **Una regla SMS que casa tiene por destino al propio remitente** → esa coincidencia se omite. Una
   regla con `{telefono}` de destino queda fuera: pide expresamente contestar, y lo que se bloquea
   es la circularidad accidental, no la configurada a propósito.

**Solo miran el canal SMS**, y es el razonamiento de SMStoTelegram y SMStoWhats, que no se deshace:
un mensaje de WhatsApp o de Telegram no vuelve nunca como SMS, ni una petición a una API. Para esos
tres canales no hay círculo que cerrar, y comparar su destino con el remitente no significaría
nada. Lo que volvió con el canal SMS fue el círculo, y con él las guardas, **solo para él**.

Una tercera comprobación no es una guarda sino una evidencia: una regla que **contesta** a un
remitente alfanumérico (`BANCO`) se omite y se registra como `ERROR`, porque a una cabecera no se le
puede mandar un SMS y el envío fallaría siempre, con reintentos detrás.

### El tope por minuto: `maxReenviosPorMinuto`, global

`AppConfig.maxReenviosPorMinuto` (10 por defecto) limita cuántos reenvíos pueden crearse en los
últimos 60 segundos, **entre todos los canales**. Se mira antes de evaluar, porque corta una
**ráfaga** de SMS entrantes, y eso se ve sin saber por dónde va a salir cada uno.

Cambia de significado por **cuarta** vez, y es la cuarta vez que sobrevive por un motivo distinto
del que lo hizo nacer:

| Aplicación | Para qué servía |
|------------|-----------------|
| SMSGateway, SMStoSMS | Cortafuegos económico: un bucle con reintentos detrás era una factura |
| SMStoTelegram | Los límites del Bot API: un mensaje por segundo y chat. Entregar era gratis |
| SMStoWhats | Las dos cosas a la vez, y la económica apretando más que nunca |
| SMSRelay | Las cuatro a la vez: protege a todos los medios juntos |

Su valor por defecto, 10, ya era el del medio más estricto, así que no hubo que recalcularlo.

### El tope mensual: `maxMensajesPorMes`, solo WhatsApp

**Es la protección que mide dinero**, y por eso es solo de WhatsApp (decisión B del masterplan):

| Qué | Cuánto |
|-----|--------|
| Mensajes de servicio gratis | **1.000 al mes y por número** |
| Mensajes de servicio pasados los mil | Se facturan (desde el 1 oct 2026) |
| Mensajes de plantilla | Se facturan **todos**, siempre |

Telegram no factura, el SMS lo cobra el operador con su propia tarifa y la API es de quien la
configura: contar sus envíos en un tope pensado para la factura de Meta lo agotaría antes de tiempo
sin proteger de nada.

Mil al mes son **treinta y tres al día**; 10 por minuto son 14.400 al día. Son dos protecciones
distintas porque miden cosas distintas: **una corta una ráfaga, la otra corta un mes**.

`AppConfig.maxMensajesPorMes` vale 900 por defecto —por debajo del tramo gratuito— y un `0` lo
desactiva. **Se aplica después de evaluar, a cada coincidencia de WhatsApp**, no antes al SMS
entero: en SMStoWhats agotar el mes descartaba el SMS, y con cuatro canales eso dejaría de reenviar
también por Telegram, por SMS y por la API. Ahora se omiten los reenvíos de WhatsApp y los demás
siguen saliendo.

`ContadorDeMensajes` lleva la cuenta, por mes natural y separando servicio de plantilla:

- Cuenta **mensajes de WhatsApp entregados, no reenvíos**: un SMS largo se trocea y Meta factura
  por mensaje.
- Distingue servicio de plantilla porque tienen precios distintos, y porque cuántos han tenido que
  ir por plantilla mide cuánto se entrega fuera de la ventana de 24 h.
- El cambio de mes se resuelve **dentro de la misma transacción** que el incremento, sin trabajo
  programado.
- Vive en el DataStore de los ajustes con sus propias claves, porque no es configuración: es un
  hecho sobre *este* teléfono y *este* mes.

El Panel lo enseña **si hay alguna regla activa por WhatsApp**. Es la única cifra de la aplicación
que cuesta dinero y que nadie iría a buscar; sin reglas de WhatsApp, no significa nada.

---

## 12. Base de datos

### Esquema

SQLite vía Room, versión **1**, con WAL activado.

```sql
CREATE TABLE sms (
    id               TEXT    PRIMARY KEY NOT NULL,  -- UUID de recepción
    telefono         TEXT    NOT NULL,              -- tal como llega en la PDU
    mensaje          TEXT    NOT NULL,              -- partes multipart concatenadas
    fecha_recepcion  INTEGER NOT NULL,              -- epoch ms
    estado           TEXT    NOT NULL,              -- EstadoSms
    motivo_descarte  TEXT                           -- solo si estado = DESCARTADO
);
CREATE INDEX index_sms_estado ON sms(estado);
CREATE INDEX index_sms_fecha_recepcion ON sms(fecha_recepcion);

CREATE TABLE reglas (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    orden              INTEGER NOT NULL,             -- prioridad ascendente
    nombre             TEXT    NOT NULL,
    regex_telefono     TEXT,                         -- null = no filtra
    regex_mensaje      TEXT,                         -- null = no filtra
    ignorar_mayusculas INTEGER NOT NULL DEFAULT 0,
    canal              TEXT    NOT NULL,             -- Canal; sin DEFAULT: ver más abajo
    destino            TEXT    NOT NULL,             -- su forma depende del canal
    plantilla          TEXT    NOT NULL,             -- sin DEFAULT: ver más abajo
    activa             INTEGER NOT NULL DEFAULT 1,
    continuar          INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX index_reglas_orden ON reglas(orden);

CREATE TABLE reenvios (
    id              TEXT    PRIMARY KEY NOT NULL,   -- UUID
    sms_id          TEXT    NOT NULL,
    regla_id        INTEGER,                         -- null si la regla se borró
    nombre_regla    TEXT    NOT NULL,                -- copia: sobrevive al borrado
    canal           TEXT    NOT NULL,                -- copia: por dónde se entrega
    destino         TEXT    NOT NULL,                -- ya resuelto
    texto_final     TEXT    NOT NULL,                -- congelado al evaluar
    estado          TEXT    NOT NULL,                -- EstadoReenvio
    intentos        INTEGER NOT NULL DEFAULT 0,
    ultimo_error    TEXT,
    fecha_creacion  INTEGER NOT NULL,
    fecha_envio     INTEGER,
    FOREIGN KEY (sms_id)   REFERENCES sms(id)    ON DELETE CASCADE,
    FOREIGN KEY (regla_id) REFERENCES reglas(id) ON DELETE SET NULL
);
CREATE INDEX index_reenvios_sms_id   ON reenvios(sms_id);
CREATE INDEX index_reenvios_regla_id ON reenvios(regla_id);
CREATE INDEX index_reenvios_estado   ON reenvios(estado);

CREATE TABLE log_entries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    tipo        TEXT    NOT NULL,                    -- LogTipo
    sms_id      TEXT,
    reenvio_id  TEXT,
    destino     TEXT,                                -- desnormalizado
    canal       TEXT,                                -- null si no es de ningún canal
    detalle     TEXT    NOT NULL,
    timestamp   INTEGER NOT NULL
);
CREATE INDEX index_log_entries_sms_id    ON log_entries(sms_id);
CREATE INDEX index_log_entries_timestamp ON log_entries(timestamp);
```

### Estados

```
EstadoSms
  PENDIENTE   recibido, las reglas aún no se han evaluado
  PROCESADO   evaluado, al menos un reenvío creado
  SIN_REGLA   evaluado, ninguna regla casó. No es un error
  DESCARTADO  un tope o una guarda lo cortó, o no quedó ningún reenvío que hacer

EstadoReenvio
  PENDIENTE   pendiente de entregar, o con un intento fallido que se reintentará
  ENVIADO     el medio confirmó la entrega
  FALLIDO     fallo permanente, o reintentos agotados

LogTipo
  SMS_RECIBIDO · REGLA_APLICADA · SMS_REENVIADO
  SIN_REGLA · LIMITE_ALCANZADO · ERROR · SISTEMA
```

### Decisiones de diseño

- **`sms` no guarda estado de envío.** Los intentos, los errores y las fechas de confirmación están
  en `reenvios`, una fila por destino. Un SMS que casa con dos reglas —quizá por dos canales— tiene
  dos reenvíos con suertes posiblemente distintas.
- **`reenvios.canal` está copiado de la regla**, igual que `nombre_regla`. Un reenvío ya encolado
  tiene que poder entregarse aunque la regla se edite o se borre entretanto, y el rescate de
  huérfanos tiene que saber por dónde reintentar sin volver a mirar la regla.
- **`texto_final` y `destino` se congelan** en el momento de la evaluación, con los marcadores ya
  resueltos. Editar o borrar la regla después no altera lo que ya estaba encolado.
- **`nombre_regla` está desnormalizado** para que el historial siga siendo legible cuando la regla
  se borre: la clave ajena es `SET NULL`, no `CASCADE`.
- **`log_entries.destino` y `log_entries.canal` también están desnormalizados**, para leer y filtrar
  el registro sin cruzar tablas, y para que siga teniendo sentido cuando el reenvío se haya purgado.
  `canal` es nulable: un SMS recibido o un mensaje del sistema no son de ningún canal.
- **`reglas.canal` y `reglas.plantilla` no tienen valor por defecto en SQL.** Los dos estarían
  escritos dos veces —ahí y en `Canal.POR_DEFECTO` o `Regla.plantillaPorDefecto()`—, y al cambiar
  el del dominio dejarían de coincidir. Ninguna inserción usa el de la entidad: toda fila viene de
  un `Regla`.

### Por qué el esquema empieza en la versión 1, otra vez

Es la cuarta vez que pasa, y por el mismo motivo. Con `applicationId = com.capicua.smsrelay`,
Android trata esto como una aplicación distinta, con su propio directorio de datos. Ningún
dispositivo tiene esta base de datos: no hay nada que migrar, y arrastrar la historia de
migraciones de otra aplicación obligaría a mantener código que nunca se ejecutaría. Las columnas
`canal` se añadieron directamente a la v1.

**Esto se puede hacer una sola vez.** En cuanto la aplicación se instale en un teléfono con reglas
dentro, el esquema deja de ser editable en sitio. Las copias de las aplicaciones anteriores no
pasan por Room: entran por la importación de §9.

### Migraciones

Los esquemas se exportan a `app/schemas/` y **se versionan**: son la referencia para escribir
migraciones. Nunca se usa `fallbackToDestructiveMigration()`, porque perdería SMS y reenvíos
pendientes; si falta una migración, se prefiere que la aplicación falle de forma ruidosa.

Las migraciones se escriben a mano en `data/local/db/Migraciones.kt` y `DatabaseModule` las
registra con `addMigrations()`.

| Versión | Cambio |
|---------|--------|
| 1 | Esquema inicial con las cuatro tablas, `ignorar_mayusculas` y las tres columnas `canal` incluidas |

`Migraciones.TODAS` está **vacío**, y es lo último que puede estarlo.

Un detalle que se paga caro si se pasa por alto: el valor por defecto del `ALTER TABLE` de una
migración futura tiene que coincidir con el `defaultValue` declarado en la entidad. Si no
coinciden, Room valida el esquema después de migrar, encuentra la diferencia y aborta — con la base
de datos ya migrada.

---

## 13. Mantenimiento

### Tareas periódicas automáticas

| Worker | Intervalo | Qué hace |
|--------|-----------|----------|
| `OrphanRescueWorker` | 30 s (auto-encadenado) | Retoma SMS sin evaluar y reencola reenvíos sin worker vivo |
| `HealthMonitorWorker` | 15 min (periódico) | Purga reenvíos enviados, SMS tramitados y logs antiguos |

`OrphanRescueWorker` se auto-encadena porque WorkManager no admite trabajos periódicos de menos
de 15 minutos, y media hora de retraso en un reenvío es inaceptable.

Ninguno de los dos borra nada pendiente: los SMS en `PENDIENTE` y los reenvíos sin confirmar se
conservan por antiguos que sean, porque todavía tienen trabajo que hacer.

### Ajustar las políticas de retención

En `util/Constants.kt`:

```kotlin
const val RETENTION_DELIVERED_DAYS = 30L   // SMS tramitados y reenvíos enviados
const val RETENTION_LOGS_DAYS      = 30L   // entradas del registro
```

### Parámetros ajustables en la app

En **Ajustes**, persistidos en DataStore, por secciones:

| Sección | Parámetro | Por defecto | Rango | Qué controla |
|---------|-----------|-------------|-------|--------------|
| Recepción | Leer los SMS que llegan | Activado | — | Interruptor general: al apagarlo se deshabilita el componente `SmsReceiver` |
| Cuenta de WhatsApp | Token de acceso | Vacío | — | La credencial de la Cloud API |
| Cuenta de WhatsApp | Id del número | Vacío | — | El `phone_number_id`. **No es el número de teléfono** |
| Entrega | Modo de entrega | Automático | — | Texto libre, plantilla, o texto libre con salto a plantilla |
| Entrega | Nombre e idioma de la plantilla | Vacío, `es` | — | Vacío significa «solo entrego dentro de la ventana de 24 h» |
| Bot de Telegram | Token del bot | Vacío | — | La credencial de la Bot API |
| SMS | SIM de envío | La predeterminada | — | Con qué SIM salen los reenvíos por SMS |
| SMS | Proteger de bucles | Activado | — | Las dos guardas antibucle del canal SMS. §11 |
| API | Aceptar certificados no válidos | Desactivado | — | Para servidores en la red local con certificado propio |
| Límites | Máximo de reenvíos por minuto | 10 | 1–100 | Corta una ráfaga, entre todos los canales. §11 |
| Límites | Tope de mensajes de WhatsApp al mes | 900 | 0–100.000 | Corta un mes de WhatsApp. `0` lo desactiva. §11 |
| Envío y reintentos | Espera de confirmación | 60 s | 10–300 | Cuánto se espera la respuesta del medio o el `sentIntent` |
| Envío y reintentos | Máximo de reintentos | 10 | 1–100 | Cuándo se da una entrega por perdida |
| Envío y reintentos | Intervalo inicial de reintento | 30 s | 5–3600 | Backoff lineal de WorkManager |

**Guardar parte de la configuración cargada**, no de una `AppConfig()` nueva: con tantas secciones,
un ajuste que la pantalla no pintara se perdería en cada «Guardar».

Encima de las secciones de canal, la tarjeta **Canales** enseña los cuatro con su estado —el
`motivoNoDisponible()` de cada transporte— y cuántas reglas activas usa cada uno. Se refresca al
volver a la pantalla, porque un permiso o una credencial cambian fuera de ella.

Los dos tokens se enmascaran en pantalla —con el ojo para comprobar lo que se ha pegado— y **no se
escriben nunca en el registro**. El id del número no se enmascara: no es secreto, y hay que poder
compararlo de un vistazo con el que enseña la consola de Meta, que es donde está el error más
frecuente de toda la configuración de WhatsApp.

Cada credencial tiene su botón de comprobación, y los dos responden con **quién** es y no con un
«correcto» que obligase a creerse la pantalla:

- *Comprobar las credenciales* (WhatsApp) consulta el objeto del número y devuelve su **nombre
  verificado**, su **teléfono** y su `quality_rating` (`GREEN`/`YELLOW`/`RED`). Esa consulta
  ejercita **las dos credenciales a la vez** —el token en la cabecera, el id en la ruta—. El
  `quality_rating` es el riesgo real de entregar mensajes que el destinatario no ha pedido: cuando
  baja a rojo, Meta limita o bloquea el número.
- *Comprobar el token* (Telegram) llama a `getMe` y devuelve el nombre y el `@usuario` del bot.

Los dos distinguen «no hay red» de «la credencial está mal»: decir lo segundo cuando pasa lo
primero mandaría a cambiar algo que funciona.

La sección *SMS* pide `SEND_SMS` con su propio botón, y la SIM se elige de un desplegable que solo
se puede rellenar tras conceder `READ_PHONE_STATE` con *Elegir la SIM de envío*.

Apagar «Leer los SMS que llegan» no pone un `if` al principio de `onReceive`: deshabilita el
componente con el `PackageManager` (`util/ControlRecepcion`), de modo que el sistema deja de
entregarle el broadcast. Ni se arranca el proceso, ni hay que leer DataStore —que es asíncrono—
en el hilo principal del broadcast, ni puede colarse un SMS por una carrera entre el ajuste y el
mensaje. Corta la **entrada**: lo que ya estaba guardado y encolado se sigue reenviando. El
estado del componente lo persiste el sistema y sobrevive a los reinicios; lo único que lo
devuelve al valor del manifiesto es una reinstalación, y por eso `SmsRelayApplication` lo vuelve a
sincronizar con el ajuste guardado al arrancar. **Es también el último paso de una migración**
desde una de las aplicaciones anteriores: con las dos recibiendo, cada SMS sale dos veces.

### Lo que el teléfono tiene que permitir

Dos ajustes del sistema deciden si la aplicación funciona de verdad, y ninguno se puede conceder
desde el código:

| Ajuste | Se puede consultar | Qué pasa sin él | Dónde está en la app |
|--------|--------------------|-----------------|----------------------|
| Exención de batería (Doze) | Sí, `PowerManager` | Las entregas se retrasan horas con la pantalla apagada | Ajustes → Fiabilidad en segundo plano (`util/OptimizacionBateria`) |
| Inicio automático del fabricante | **No**, no hay API | Tras reiniciar el teléfono no se recibe nada hasta abrir la app a mano | Ajustes → Fiabilidad en segundo plano (`util/InicioAutomatico`) |

El inicio automático es cosa de MIUI, HyperOS, EMUI, ColorOS y Funtouch: su lista viene vacía y
lo que no esté en ella no recibe `BOOT_COMPLETED` ni despierta en segundo plano. Como no se puede
consultar, la pantalla no pinta estado: explica para qué sirve y abre la pantalla del sistema.

Hay un tercer caso que no es un ajuste sino una regla de Android: una aplicación **recién
instalada que nadie ha abierto todavía**, o una que se ha forzado a detener, no recibe ningún
broadcast —tampoco el de SMS— hasta que se abre una primera vez. Después de instalar el APK hay
que abrirla una vez.

### Monitorización vía ADB

```bash
# Logs en tiempo real
adb logcat -s "SmsReceiver" "SmsIngestionService" "DispatchWorker" \
              "WhatsAppSender" "TelegramSender" "SmsSender" "ApiSender" \
              "OrphanRescueWorker" "HealthMonitorWorker"

# Base de datos
adb shell run-as com.capicua.smsrelay ls databases/
adb exec-out run-as com.capicua.smsrelay cat databases/smsrelay.db > smsrelay.db
sqlite3 smsrelay.db "SELECT estado, COUNT(*) FROM sms GROUP BY estado;"
sqlite3 smsrelay.db "SELECT canal, destino, estado, intentos, ultimo_error FROM reenvios;"
sqlite3 smsrelay.db "SELECT canal, estado, COUNT(*) FROM reenvios GROUP BY canal, estado;"

# Configuración — OJO: este fichero contiene los dos tokens
adb shell run-as com.capicua.smsrelay cat files/datastore/smsrelay_config.preferences_pb

# Estado de los workers
adb shell dumpsys jobscheduler | grep -A 5 capicua
```

### Exportar el registro desde la app

**Logs** → **Exportar** genera un fichero de texto en la caché y lo comparte mediante
`FileProvider`. Incluye el tipo, el canal, el SMS, el destino y el detalle de cada entrada. Exporta
el registro **entero**, sin aplicar los filtros de la pantalla.

Su autoridad se deriva del `applicationId` (`${applicationId}.provider` en el manifiesto y
`BuildConfig.APPLICATION_ID + ".provider"` en `Constants`) y **no se escribe a mano**: una
autoridad es única en todo el dispositivo, así que con el nombre fijo las variantes de
depuración y de publicación declaraban la misma y Android se negaba a tener las dos instaladas
(`INSTALL_FAILED_CONFLICTING_PROVIDER`), que es justo lo que el `applicationIdSuffix` pretendía
evitar. Ese fallo no se ve al compilar: aparece al instalar.

---

## 14. Posibles incidencias

### El SMS se recibe pero no se entrega

Antes de mirar las reglas, **mira el Panel**: si un canal usado por alguna regla activa no puede
entregar —falta una credencial, Meta o Telegram la han rechazado, falta el permiso de SMS—, hay un
aviso en rojo arriba que **nombra el canal**, y por ese canal no va a salir nada hasta que se
arregle en Ajustes. La tarjeta **Canales** de Ajustes dice lo mismo para los cuatro.

Si no hay aviso, lo más probable es que **ninguna regla case**. La pantalla de Inicio lo dice: el
SMS aparece como «Sin regla». Para confirmarlo:

1. **Reglas** → **Probar**, pega el remitente y el texto exactos, y mira qué dice y por qué canal
   saldría.
2. Revisa que la regla esté **activa**. Una desactivada no se evalúa.
3. Comprueba las mayúsculas: los patrones las distinguen salvo con **Ignorar mayúsculas**.
4. Comprueba el remitente: si llega como `BANCO` y tu patrón espera `^\+34`, no casará nunca.

Si la regla sí casa pero la entrega no sale, revisa el registro filtrando por **Errores** y por el
**canal**: el motivo lleva lo que contestó el medio, que suele decir exactamente qué pasó.

### El SMS aparece como «Descartado», o falta un reenvío

El registro filtrado por **Descartados** lo dice, y la primera palabra del detalle dice qué lo
cortó:

- ***Tope por minuto*** — una ráfaga. Si pasa con tráfico normal, sube el límite en Ajustes. Es de
  todos los canales: corta el SMS entero.
- ***Tope mensual de WhatsApp*** — se agotó la cuota del mes que tú fijaste. Solo omite los
  reenvíos de WhatsApp; los demás canales siguen saliendo, así que el SMS solo aparece descartado si
  todas sus reglas eran de WhatsApp. El contador del Panel lo confirma.
- ***Guarda antibucle de SMS*** — el remitente es el destino de una regla SMS, o una regla SMS
  reenviaría al propio remitente. Si la intención era contestarle, el destino tiene que ser
  `{telefono}`. Si se trata de otra pasarela que de verdad debe recibir y devolver, se puede apagar
  *Proteger de bucles* en Ajustes → SMS, sabiendo lo que se arriesga.
- ***Canal desactivado*** — la regla casaría, pero su canal está apagado en Ajustes → Canales. Si no
  era la intención, enciéndelo; la regla no hay que tocarla.

Una regla que contesta por SMS a un remitente alfanumérico se omite como **Error**, no como
descarte: a `BANCO` no se le puede mandar un SMS.

### El registro dice «error 131026»

«Unable to deliver message. This may be because the recipient is not a valid WhatsApp user.» Las
causas, por frecuencia:

1. **Falta el prefijo internacional.** El editor de reglas lo avisa al escribir, pero una regla
   importada o editada de otra forma puede colarse. El destino va siempre `+34600112233`.
2. **Ese número no tiene WhatsApp.** No hay nada que la aplicación pueda hacer.
3. **El prefijo está mal.** Un `+1` donde iba un `+34` apunta a un número que existe y no es el
   que se quería.

Es un fallo permanente: no se reintenta, porque reintentar no lo arregla.

### El registro dice «error 131047»

La **ventana de 24 h está cerrada**: ese destinatario no ha escrito al número emisor en las
últimas 24 horas, así que Meta no permite mandarle texto libre.

Qué hacer depende del modo de entrega:

- En **Automático** con plantilla configurada, esto no debería llegar al registro como fallo: la
  aplicación reenvía por plantilla en el mismo intento. Si aparece, es que no hay plantilla
  configurada.
- En **Solo texto libre**, es el comportamiento esperado y el motivo lo dice. Para que se
  entregue, o el destinatario escribe al número, o hay que cambiar de modo.

### El registro dice «error 132001» o «132012»

La plantilla. Un `132001` es que **no existe en ese idioma**: comprueba el nombre y el código de
idioma en Ajustes contra lo que diga el Business Manager. Un `132012` es que **el parámetro no
tiene el formato válido**, que casi siempre significa un salto de línea donde no puede haberlo —y
eso no debería pasar, porque `PlantillaDeWhatsApp.aplanar()` lo evita; si pasa, es un fallo de la
aplicación y hay un test que escribir.

### El registro dice «error 100»

El **id del número** no existe para Meta. El error de configuración número uno de la Cloud API es
pegar el número de teléfono donde va el `phone_number_id`: la consola los enseña uno debajo del
otro. El Panel lo marca como fallo de credencial precisamente para mandar a mirar ahí.

### Telegram: «chat not found», «Forbidden» o «Unauthorized»

- **`400 chat not found`** — el bot no conoce ese chat. Un bot no puede escribir primero: la persona
  tiene que mandarle `/start`, o el bot tiene que estar en el grupo. En un canal, el bot tiene que
  ser administrador. Ver [`BOT-TELEGRAM.md`](BOT-TELEGRAM.md).
- **`403 Forbidden`** — el bot fue bloqueado por esa persona o expulsado del grupo.
- **`401 Unauthorized` o `404`** — el **token**: la URL lo lleva dentro. Es un fallo de credencial,
  y el Panel lo dice en rojo.

### SMS: «sin servicio de red», «radio apagada» o «falta el permiso»

- **Sin servicio o radio apagada** — sin cobertura o en modo avión. Es transitorio: el reenvío
  espera y se reintenta. La cola no exige red de datos para el canal SMS, así que un sitio con
  cobertura y sin datos sigue entregando.
- **Falta el permiso `SEND_SMS`** — se concede en Ajustes → SMS, o al editar una regla del canal.
  Es permanente para ese reenvío; los posteriores saldrán en cuanto se conceda.
- **Sale por la SIM equivocada** — en un teléfono con dos, Ajustes → SMS → *Elegir la SIM de
  envío*. Sin elegir, sale por la de SMS predeterminada del sistema.

### API: un 3xx, un 4xx o un error de certificado

- **Un `3xx`** — el servidor redirige, y SMSRelay no sigue redirecciones a propósito. Pon en la
  regla la URL final, normalmente la misma con `https://` o con la barra final que pide el
  servidor.
- **Un `4xx`** — la petición está mal para ese servidor: la ruta, la clave de la consulta o el
  formato de los parámetros. Es permanente, porque un reintento manda los mismos bytes.
- **Un error de certificado** — el servidor usa uno propio o caducado. Si es tuyo y está en tu red,
  Ajustes → API → *Aceptar certificados no válidos*, sabiendo que la conexión deja de garantizar que
  el servidor sea el que dice ser.
- El registro escribe la URL **sin la consulta**, que es donde suelen ir el mensaje y la clave.
  Para ver la petición entera, mira el registro del servidor.

### Las entregas fallan con «Too Many Requests»

Un `429`, en cualquiera de los tres canales de red. Se trata como transitorio:

- **Telegram** dice cuánto esperar con `retry_after`, y el worker lo respeta hasta 120 segundos.
  El límite es de **un mensaje por segundo por chat**, y en un grupo unos 20 por minuto.
- **Meta** no dice cuánto esperar, y se aplica el backoff de WorkManager. El techo que se ve es el
  `131056`: demasiados mensajes al mismo destinatario en poco tiempo.
- **Una API** propia lo dirá en sus propios términos.

Si es constante, lo que toca es bajar `Máximo de reenvíos por minuto` o repartir los avisos en
varios destinos.

### Las entregas fallan con un error de red

Sin conexión no se entrega nada por WhatsApp ni por Telegram, pero **no se pierde nada**: el
reenvío queda `PENDIENTE` y `ColaDeEnvios` impone `NetworkType.CONNECTED` a esos dos canales, así
que el worker no se ejecuta hasta que haya red en lugar de gastar sus intentos. El SMS y la API no
llevan esa restricción —el SMS sale por la radio, y una API puede estar en la red local—, así que
un fallo de red de la API sí gasta intentos.

```bash
adb shell dumpsys connectivity | grep -i "NetworkAgentInfo"
```

### Nada se entrega con la pantalla apagada

Falta la exención del modo Doze:

```bash
adb shell dumpsys deviceidle whitelist | grep capicua
# Si no aparece:
adb shell dumpsys deviceidle whitelist +com.capicua.smsrelay
```

En Xiaomi, Huawei y Samsung hace falta además activar el **inicio automático** y quitar la app de
la lista de aplicaciones suspendidas. Ver [`CLAUDE.md`](../CLAUDE.md).

### La app no recibe SMS tras reiniciar el dispositivo

```bash
# Verificar que el permiso de arranque está declarado y concedido
adb shell dumpsys package com.capicua.smsrelay | grep -i boot

# Verificar que los workers están programados
adb shell dumpsys jobscheduler | grep -A 5 capicua
```

En algunos fabricantes hay que abrir la aplicación una vez después de instalarla para que el
`BOOT_COMPLETED` se entregue.

### `ForegroundServiceStartNotAllowedException`

A partir de Android 12 no se puede iniciar un foreground service desde segundo plano, salvo en
casos exentos. La recepción de un SMS **es** uno de ellos, así que no debería ocurrir. Si aparece
en el log, comprueba que el crash viene de `SmsReceiver` y no de otra ruta, y que el permiso
`RECEIVE_SMS` está concedido: sin él, el broadcast no otorga la exención.

### Un mensaje llegó duplicado

Tres causas posibles:

1. **Hay dos aplicaciones recibiendo.** SMSRelay y una de las cuatro anteriores, o la variante debug
   y la release, con la recepción activa en las dos: cada una reenvía el SMS. Apaga la recepción en
   la que sobre.
2. **El texto se troceó** —más de 4.096 caracteres en WhatsApp o Telegram, más de 980 en una
   plantilla, varias partes en SMS— y un trozo falló después de que otro saliera: el reenvío se
   reintenta completo y el principio se repite. Es el comportamiento documentado (§10). Para
   evitarlo, acorta la plantilla: el editor muestra la longitud contra el límite del canal.
3. **El medio aceptó el mensaje y la respuesta se perdió** por un corte de red justo después. El
   worker lo da por transitorio y reintenta, que es lo correcto: perder un mensaje es peor que
   duplicarlo. La idempotencia de cliente solo protege de reintentos sobre un reenvío ya marcado
   como `ENVIADO`, y en este caso nunca llegó a marcarse.

### La base de datos crece sin control

`HealthMonitorWorker` purga cada 15 minutos, pero solo lo ya tramitado. Si crece, es que hay
mucho pendiente acumulado:

```sql
SELECT estado, COUNT(*) FROM sms GROUP BY estado;
SELECT estado, COUNT(*) FROM reenvios GROUP BY estado;
```

Muchos `PENDIENTE` apuntan a un problema de entrega sostenido, no de retención —empezando por una
credencial—; agrupar por `canal` dice cuál. Muchos `FALLIDO` se purgan con el resto de SMS tramitados a los 30 días.

### Consumo de batería excesivo

Normalmente es `OrphanRescueWorker` encontrando trabajo cada 30 segundos, lo que significa que
hay reenvíos atascados reintentándose en bucle. Revisa los pendientes y sus `ultimo_error`; si
son fallos permanentes mal clasificados, el worker seguirá insistiendo hasta agotar los
reintentos.

El caso más probable es una **credencial rechazada**: cada reenvío intenta, falla y vuelve.
El aviso del Panel está precisamente para que eso se vea antes de notarlo en la batería.

Subir `Intervalo inicial de reintento` y bajar `Máximo de reintentos` reduce el consumo a costa
de tardar más en entregar lo que sí es recuperable.
