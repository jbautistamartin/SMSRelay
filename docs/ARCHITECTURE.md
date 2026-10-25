# SMStoWhats — Documentación Técnica

## Índice

1. [Descripción del sistema](#1-descripción-del-sistema)
2. [Arquitectura](#2-arquitectura)
3. [Diagrama de flujo](#3-diagrama-de-flujo)
4. [Diagrama de capas](#4-diagrama-de-capas)
5. [Dependencias](#5-dependencias)
6. [Cómo compilar](#6-cómo-compilar)
7. [Cómo instalar](#7-cómo-instalar)
8. [Permisos requeridos](#8-permisos-requeridos)
9. [Reglas de entrega](#9-reglas-de-entrega)
10. [Mecanismo de entrega](#10-mecanismo-de-entrega)
11. [Los dos topes](#11-los-dos-topes)
12. [Base de datos](#12-base-de-datos)
13. [Mantenimiento](#13-mantenimiento)
14. [Posibles incidencias](#14-posibles-incidencias)

---

## 1. Descripción del sistema

**SMStoWhats** es una aplicación Android diseñada para ejecutarse en un **dispositivo con
SIM** cuya función es recibir los SMS que llegan a su número y entregarlos en WhatsApp —a una
persona, un grupo o un canal— a través de un bot propio, decidiendo el destino mediante reglas
con expresiones regulares.

Nace de [SMStoSMS](https://github.com/jbautistamartin/SMStoSMS), que hace lo mismo con la entrada
y reenvía como SMS. **Lo único que cambió es el medio de salida**, y buena parte de este documento
existe para explicar qué consecuencias tuvo ese cambio en un sistema que se diseñó alrededor de
otro medio.

### Casos de uso típicos

- Un número corporativo que recibe códigos de verificación y los reparte al chat de la persona
  responsable de cada servicio.
- Alertas de un sistema de monitorización que llegan por SMS y deben alcanzar a **un grupo** de
  guardia, de una sola entrega y sin repetir el envío por cada persona.
- Una línea antigua que se quiere mantener operativa sin llevar encima su tarjeta, viendo lo que
  llega donde uno ya está mirando.

### Garantías del sistema

| Garantía | Mecanismo |
|----------|-----------|
| Ningún SMS entrante se pierde aunque el proceso sea eliminado | `ForegroundService` + outbox en Room |
| Una entrega solo se da por buena si Meta la confirma | Espera de la respuesta HTTP de la Cloud API |
| Ningún SMS se entrega dos veces por un reintento | Comprobación del estado al inicio de cada intento |
| Lo que se queda atascado se recupera solo | `OrphanRescueWorker` cada 30 s |
| No se superan los límites de la Cloud API ni la cuota del mes | Los dos topes de §11 |
| Un fallo de credencial se ve, en lugar de repetirse 400 veces | `EstadoDeLaCuenta` + aviso en el Panel |
| La app arranca sola tras un reinicio del dispositivo | `BootReceiver` + WorkManager |

### Lo que el sistema **no** garantiza

- **Lectura por el destinatario.** Un `2xx` confirma que Meta aceptó el mensaje y lo
  puso en el chat, no que alguien lo haya visto.
- **Confidencialidad frente a Meta.** El contenido de los SMS pasa por sus servidores. Es
  inherente al diseño y está dicho en el aviso de privacidad de la propia aplicación.
- **Ausencia de duplicados en textos de más de 4.096 caracteres.** En ese caso el mensaje se
  trocea; si un trozo falla después de que otro saliera, el reenvío se reintenta completo y el
  chat puede ver repetido el principio. Ver §10.

---

## 2. Arquitectura

La aplicación sigue **Clean Architecture** con separación estricta en tres capas y el patrón
**MVVM** en presentación. Toda la inyección de dependencias pasa por **Hilt**.

```
┌─────────────────────────────────────────────────────────┐
│  PRESENTATION                                           │
│  Fragments + ViewModels + Adapters                      │
│  Dashboard · Reglas · Probar · Logs · Settings · About   │
└───────────────────────────┬─────────────────────────────┘
                            │ usa
┌───────────────────────────▼─────────────────────────────┐
│  DOMAIN                                                 │
│  Modelos puros · EvaluadorDeReglas ·                    │
│  EnviadorDeMensajes (el transporte) · UseCases          │
│  Sin una sola dependencia de Android                    │
└───────────────────────────┬─────────────────────────────┘
                            │ define interfaces,
                            │ implementadas por
┌───────────────────────────▼─────────────────────────────┐
│  DATA                                                   │
│  Room · Cloud API de WhatsApp · DataStore · WorkManager │
└─────────────────────────────────────────────────────────┘
```

**`EnviadorDeMensajes` es la juntura del sistema, y está en `domain` a propósito.** Define tres
cosas —entregar y esperar, el límite de caracteres, y por qué no se puede entregar ahora— y nada
en ella nombra el medio. `data/whatsapp/WhatsAppSender` la implementa y `di/TransportModule` es la
**única** clase del proyecto que menciona una implementación concreta.

Eso no es arquitectura por gusto: es lo que ha hecho que los dos cambios de medio fueran una
fase de transporte y no una reescritura. El outbox, la idempotencia de cliente, el rescate de
huérfanos, la política de reintentos y el historial se escribieron para una pasarela HTTP,
sobrevivieron al SMS, sobrevivieron a Telegram y han sobrevivido a la Cloud API sin tocarse.

### Principios aplicados

- **La capa de dominio no conoce Android.** `EvaluadorDeReglas` es Kotlin puro, y por eso se
  puede probar con JUnit a secas: sin Robolectric, sin instrumentación y sin base de datos en
  memoria. El mismo criterio llevó a separar `MapeoDeRespuestas` del código que hace la petición
  HTTP: la clasificación de las respuestas de la Cloud API es la pieza que más importa que esté bien
  y la única del transporte que se puede probar sin red.
- **El evaluador recibe la fecha ya formateada**, no un `Instant`. Es lo que lo mantiene
  determinista: no depende de la zona horaria ni del idioma del dispositivo, y quien llama
  decide el formato.
- **Las reglas viven en la base de datos, no en DataStore.** Son una lista ordenada con
  identidad propia, consultable y migrable; DataStore guarda solo los ajustes escalares.
- **Un único punto de encolado.** Todo reenvío entra en WorkManager por `ColaDeEnvios`, que lee
  el backoff de la configuración. Centralizarlo evitó el error que arrastraba la versión HTTP,
  donde el intervalo configurable nunca se aplicaba porque el encolado leía una constante.

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
            │ 2. ¿se superó el límite por minuto?  │──sí──→ DESCARTADO
            └──────────────────┬───────────────────┘
                               │ no
            ┌──────────────────▼───────────────────┐
            │ 3. EvaluadorDeReglas                 │
            │    reglas activas, por orden         │
            └──────────────────┬───────────────────┘
                               │
                  ┌────────────┴────────────┐
             sin coincidencias        con coincidencias
                  │                         │
              SIN_REGLA        ┌────────────▼─────────────┐
                               │ 4. INSERT reenvios × N   │
                               │    UPDATE sms PROCESADO  │
                               └────────────┬─────────────┘
                                            │ ColaDeEnvios.encolar()
                               ┌────────────▼─────────────┐
                               │     DispatchWorker       │  uno por reenvío
                               └────────────┬─────────────┘
                                            │ EnviadorDeMensajes
                               ┌────────────▼─────────────┐
                               │      WhatsAppSender      │
                               │  POST /sendMessage       │
                               │  texto plano, sin        │
                               │  parse_mode              │
                               │  … suspende …            │
                               └────────────┬─────────────┘
                                            │ respuesta HTTP
                               ┌────────────▼─────────────┐
                               │  200 ok:true → ENVIADO   │
                               │  429 / 5xx   → retry     │
                               │  400/401/403 → FALLIDO   │
                               └──────────────────────────┘
```

**Entre el paso 3 y el 4 no queda ninguna comprobación, y eso es un cambio.** SMStoSMS filtraba
ahí dos cosas: las coincidencias cuyo destino era el propio remitente, y los remitentes
alfanuméricos a los que no se puede enviar un SMS. Las dos comparaban un destino con un número de
teléfono, y dejaron de significar nada cuando el destino pasó a ser un `chat_id`. Ver §11.

En paralelo, dos workers vigilan el sistema:

```
OrphanRescueWorker   cada 30 s (auto-encadenado)
  ├─ SMS en PENDIENTE sin evaluar      → vuelve a tramitarlos
  └─ reenvíos PENDIENTE sin worker vivo → los reencola

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
├── dashboard/      SMS recibidos, con regla aplicada, destinos y estado por reenvío
├── rules/          Lista ordenada, editor con validación en vivo, panel de prueba
├── logs/           Registro con filtros por tipo y exportación a fichero
├── settings/       Credenciales, entrega, recepción, topes, reintentos y copia
└── about/          Versión, autoría, página del proyecto, licencia

domain/
├── model/          SmsMessage · Regla · Reenvio · SmsConReenvios
│                   EstadoSms · EstadoReenvio · ResumenSms · LogEntry · LogTipo
├── rules/          EvaluadorDeReglas · ResultadoEvaluacion
├── transport/      EnviadorDeMensajes · ResultadoEnvio   ← la juntura del medio
└── usecase/        ProcesarSmsEntranteUseCase · ObtenerListaSmsUseCase

data/
├── config/         AppConfig · ConfigDataStore · ConfigBackup
├── local/db/       SmsDatabase · entidades · DAOs · relaciones · Migraciones
├── repository/     Sms · Regla · Reenvio · Log (contrato + implementación)
├── rules/          ReglasJson: formato de intercambio versionado
├── whatsapp/       WhatsAppSender · MapeoDeRespuestas · PlantillaDeWhatsApp · TokenDeAcceso
│                   DestinoDeWhatsApp · ComprobadorDeCuenta
│                   DescubridorDeChats · EstadoDeLaCuenta
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

`gradle/libs.versions.toml` es la única fuente de verdad de las versiones.

**La red se usa sin ninguna dependencia de red.** OkHttp y Retrofit se eliminaron cuando
desapareció la capa HTTP de SMSGateway y **no han vuelto**: toda la comunicación con Meta son
tres peticiones —enviar un texto, enviar una plantilla y consultar el número emisor—, cada una
una docena de líneas de
`HttpURLConnection`, con `kotlinx.serialization` para las respuestas, que ya estaba aquí por el
formato de exportación de reglas. Una dependencia nueva para eso no se paga. El permiso `INTERNET`
sí se declara, y es el permiso central de la aplicación.

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
git clone <url-del-repositorio> && cd SMStoSMS

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
KEY_ALIAS=...
KEY_PASSWORD=...
```

El almacén vive **fuera del repositorio** y lo comparten las demás aplicaciones de la casa: una
misma clave las firma todas. `KEYSTORE_PATH` admite ruta absoluta, pero se escribe relativa para
que el proyecto se pueda mover de sitio, y **resuelve desde el módulo `app/`**, no desde la raíz,
porque es ahí donde la interpreta el `file()` de `app/build.gradle.kts`.

Que la clave sea compartida tiene una consecuencia: toda actualización futura tiene que firmarse
con ese mismo almacén. Si se pierde, la aplicación instalada ya no se puede actualizar — solo
desinstalar y volver a instalar, perdiendo sus datos.

Si falta alguna de las cuatro, el build **no falla**: se omite la configuración de firma y el APK
sale sin firmar. Es deliberado, para que clonar el repositorio y compilar funcione sin tener que
conseguir primero el almacén de claves.

### Variantes de build

| Variante | applicationId | Minificación | Firma |
|----------|---------------|--------------|-------|
| `debug` | `com.capicua.smstowhats.debug` | No | Clave de depuración |
| `release` | `com.capicua.smstowhats` | R8 activado | Clave propia, si está configurada |

Los dos `applicationId` son distintos, así que las dos variantes pueden convivir en el mismo
dispositivo. **No comparten base de datos**: son aplicaciones separadas para Android.

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
adb shell pm grant com.capicua.smstowhats android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstowhats android.permission.READ_SMS
adb shell pm grant com.capicua.smstowhats android.permission.POST_NOTIFICATIONS

# 5. Eximir del modo Doze (OBLIGATORIO)
adb shell dumpsys deviceidle whitelist +com.capicua.smstowhats

# Verificar
adb shell dumpsys deviceidle whitelist | grep capicua
```

### Por qué el paso 5 es obligatorio

El **modo Doze** suspende los procesos en segundo plano cuando la pantalla lleva unos minutos
apagada. WorkManager respeta Doze y puede retrasar `DispatchWorker` horas. Eximir la
aplicación es lo que hace que un SMS se reenvíe en segundos y no cuando al sistema le parezca.

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

Para probar también el envío, dos emuladores pueden mandarse SMS usando su número de puerto
(`5554`, `5556`…) como número de teléfono.

---

## 8. Permisos requeridos

| Permiso | Tipo | Para qué |
|---------|------|----------|
| `RECEIVE_SMS` | Peligroso | Recibir el broadcast de SMS entrantes |
| `READ_SMS` | Peligroso | Leer el contenido de las PDU |
| `INTERNET` | Normal | **Entregar los mensajes a WhatsApp**, y comprobar versiones |
| `POST_NOTIFICATIONS` | Peligroso (API 33+) | Notificación del foreground service |
| `REQUEST_INSTALL_PACKAGES` | Normal | Abrir el instalador con el APK de una versión nueva |
| `FOREGROUND_SERVICE` | Normal | Ejecutar el servicio de ingesta |
| `FOREGROUND_SERVICE_DATA_SYNC` | Normal | Tipo de servicio declarado (API 34+) |
| `RECEIVE_BOOT_COMPLETED` | Normal | Reprogramar los workers tras reiniciar |
| `WAKE_LOCK` | Normal | Completar el trabajo con la pantalla apagada |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Normal | Solicitar la exención de Doze |

**No se declara `SEND_SMS`**, que era un permiso restringido, ni `READ_PHONE_STATE`, que servía
para enumerar las SIM del selector de envío. Esta aplicación no envía ningún SMS: recibe por la
radio y entrega por internet.

Perderlos **no abre Google Play**: `RECEIVE_SMS` y `READ_SMS` pertenecen al mismo grupo
restringido y su publicación sigue exigiendo una declaración aprobada. Para distribución por APK
no supone ningún problema.

También se declara `<uses-feature android:name="android.hardware.telephony" required="true" />`:
sin telefonía no entra ningún SMS y la aplicación no tiene nada que hacer.

### Seguridad del BroadcastReceiver

`SmsReceiver` se declara con `android:permission="android.permission.BROADCAST_SMS"`, de modo que
solo el proceso del sistema (radio/teléfono) puede dispararlo. Ninguna aplicación de terceros
puede inyectar un SMS falso.

El receptor de confirmaciones de envío que tenía `SmsSender` ya no existe: con la Cloud API la
confirmación es la respuesta HTTP de la propia petición, así que no hay ningún broadcast que
pueda falsificarse ni respuestas que puedan cruzarse entre dos entregas simultáneas.

**El token de acceso es la credencial del sistema**, y de ahí tres reglas que el código respeta:
no se escribe nunca en el registro —va en la cabecera `Authorization`, así que la cabecera
tampoco—, se enmascara en pantalla, y no viaja en el fichero de copia de seguridad.

El `phone_number_id` no es secreto, pero tampoco se registra —va en la URL— ni viaja en la copia,
por un motivo distinto: identifica al número emisor de *esta* cuenta.

---

## 9. Reglas de reenvío

Las reglas son el centro de la configuración. Viven en la tabla `reglas` y se editan en la
pestaña **Reglas**.

### Campos

| Campo | Obligatorio | Descripción |
|-------|-------------|-------------|
| `orden` | Automático | Prioridad. Menor valor = se evalúa antes |
| `nombre` | Sí | Etiqueta para reconocerla en la lista y en el registro |
| `regexTelefono` | No | Expresión sobre el remitente. Vacío = no filtra |
| `regexMensaje` | No | Expresión sobre el cuerpo. Vacío = no filtra |
| `ignorarMayusculas` | — | true = las dos expresiones se aplican sin distinguir mayúsculas |
| `destino` | Sí | Número de WhatsApp en formato internacional: `+34600112233`. **No admite marcadores** |
| `plantilla` | Sí | Texto a enviar. Por defecto `{telefono} · {fecha}` y el mensaje debajo |
| `activa` | — | Si es false, la regla se ignora sin borrarla |
| `continuar` | — | Si es true, tras casar sigue evaluando las siguientes |

### Semántica de la coincidencia

Una regla casa cuando **ambos** criterios se cumplen. Un criterio vacío (null o en blanco) se
considera cumplido, de modo que **una regla sin patrones casa con todos los SMS**. Es potente y
fácil de crear por descuido, así que la lista lo indica explícitamente.

### Mayúsculas

Por defecto las expresiones distinguen mayúsculas. `ignorarMayusculas` añade
`RegexOption.IGNORE_CASE` **a las dos a la vez** en el momento de compilar, de modo que el
patrón guardado no se toca: lo que cambia es cómo se compila, no lo que el operador escribió.

Es acumulativo con un `(?i)` escrito dentro del patrón —activar los dos no es un error, solo
redundante—, y la diferencia práctica es el alcance: el ajuste afecta a las dos expresiones de
la regla, el modificador solo a aquella en la que se escribe.

`validarPatron()` no recibe el ajuste a propósito: ignorar mayúsculas no puede hacer que un
patrón válido deje de compilar, así que la validación del formulario es la misma en ambos casos.

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
reglas activas, ordenadas por `orden` ascendente
  │
  ├─ regla con regex inválida  → se descarta, se anota en el log, sigue la evaluación
  ├─ regla que no casa         → sigue la evaluación
  └─ regla que casa            → crea un reenvío
                                 │
                                 ├─ continuar = false → FIN
                                 └─ continuar = true  → sigue la evaluación
```

Una expresión regular que no compila **no interrumpe la evaluación**: la regla se descarta y las
demás siguen funcionando. Pero queda registrada como `ERROR` en el log, porque si no, la regla
«no funcionaría» sin explicación. El editor valida los patrones mientras se teclean para que ese
caso no llegue a producción.

### Destino

Un número de teléfono en formato internacional, usado **tal cual**. `DestinoDeWhatsApp` admite
una sola forma:

| Forma | Ejemplo | Qué es |
|-------|---------|--------|
| E.164 | `+34600112233` | Una persona con WhatsApp en ese número |

Los separadores con los que la gente escribe un teléfono —espacios, guiones, puntos, paréntesis—
se normalizan al guardar. Lo que **no** se admite es un número sin prefijo internacional, y eso es
una decisión de diseño que conviene no deshacer.

#### Por qué el `+` es obligatorio

La Cloud API acepta el número con `+` y sin él, así que exigirlo no es un requisito técnico: es lo
que hace posible dar un error útil.

`600112233` son nueve dígitos que podrían ser un móvil español escrito a pelo —inservible, le
falta el prefijo— o un número internacional completo de un país con prefijo corto. No hay forma de
distinguirlos mirándolos. Exigiendo el `+` la ambigüedad desaparece y se puede decir exactamente
lo que pasa: *«falta el prefijo internacional, escribe +34…»*, que es el error que comete todo el
mundo. Decirle «no tiene forma de teléfono» sería verdad y no serviría de nada.

`DestinoDeWhatsApp.faltaPrefijo()` existe solo para ese mensaje, y **no se dispara con lo que
empieza por `-`**: un `chat_id` de Telegram como `-1001234567890` merece el otro mensaje, porque
no es un teléfono al que le falte el prefijo. Lo pilló un test.

#### Solo personas, nunca grupos

Por decisión, no por limitación encontrada. La Cloud API admite grupos desde 2026, pero solo
creados por ella, invite-only, de hasta ocho participantes y exigiendo Cuenta Oficial de Empresa.
Mantenerse en personas deja una forma que validar en lugar de dos y fija `recipient_type` en
`individual` sin ramificación.

#### En el destino no se resuelve ningún marcador

Es una propiedad de seguridad, y es el único punto de esta migración donde el argumento hubo que
**reescribirlo** en lugar de heredarlo.

SMStoSMS sí resolvía `{telefono}` ahí, para contestar a quien escribió. Con Telegram el marcador
se fue porque la sustitución era imposible: el remitente de un SMS y un chat son identidades sin
relación. Ahora el destino vuelve a ser un teléfono, así que **sí sería posible** — y sigue sin
hacerse, por un motivo mejor: el cuerpo de un SMS ajeno no puede decidir a dónde se entrega nada,
y menos sobre una cuenta de empresa que factura por mensaje.

#### `NormalizadorTelefono` no vuelve

El destino vuelve a ser un teléfono, pero las dos preguntas que aquella clase contestaba siguen sin
existir: no hay bucle que detectar —un mensaje de WhatsApp no vuelve nunca como SMS— y «alcanzable»
solo lo sabe Meta. Lo que hace falta es validar una forma, no comparar dos valores.

### Plantilla

```
{mensaje}    → cuerpo del SMS recibido
{telefono}   → número del remitente
{fecha}      → fecha y hora de recepción, en hora local
```

`{mensaje}` se sustituye **en último lugar**, a propósito. El cuerpo del SMS es contenido ajeno:
si se sustituyera primero, un mensaje que contuviera literalmente `{telefono}` acabaría
revelando el número del remitente en un sitio donde la plantilla no lo pedía. Sustituyéndolo al
final, el texto recibido nunca se vuelve a escanear.

```
plantilla: "De {telefono}: {mensaje}"
mensaje:   "hoy es {fecha}"
resultado: "De +34600112233: hoy es {fecha}"   ← el marcador del cuerpo queda literal
```

### Por qué la plantilla por defecto dejó de ser `{mensaje}`

Con el SMS bastaba el cuerpo: el reenvío llegaba como un SMS más y el teléfono ya enseñaba de
quién venía. Por WhatsApp **todos los avisos llegan del mismo número**, así que cuatro mensajes
seguidos sin el remitente dentro son cuatro mensajes indistinguibles, y hay que adivinar cuál era
del banco. El contexto que el SMS daba gratis hay que ponerlo a mano, y de ahí que
`Regla.PLANTILLA_POR_DEFECTO` sea ahora `{telefono} · {fecha}` con el mensaje debajo.

### Longitud

Un mensaje de texto libre admite **4.096 caracteres**, frente a los 160 de un SMS GSM-7, así que en
la práctica la plantilla no tiene techo: hace falta un SMS concatenado muy largo más una plantilla
generosa para pasarse. El editor y el panel de prueba muestran la longitud contra ese límite —ya
no el número de partes, que era una cuenta de fragmentos de SMS—, y en rojo si se pasa, porque
pasarse **no** es un cargo extra: es un fallo permanente.

Si aun así se supera, `MapeoDeRespuestas.trocear()` parte el texto por el último salto de línea
del trozo, o por el último espacio si no hay ninguno, y solo parte una palabra cuando no queda
hueco. Cortar y tirar el resto perdería contenido en silencio, y dejar que Meta conteste
`400` convertiría un mensaje largo en una entrega fallida.

### Exportar e importar

La copia de seguridad se hace desde **Configuración**, no desde la pantalla de Reglas: las
reglas son configuración —son lo que decide a quién se reenvía qué—, y tenerlas en otro sitio
obligaba a guardar dos ficheros, en dos pantallas distintas, para una sola cosa. Lo implementa
`data/config/ConfigBackup`, sobre un `Uri` del Storage Access Framework, de modo que el fichero
lo elige el operador y no hace falta ningún permiso de almacenamiento.

Un fichero completo (formato 4):

```json
{
  "version": 4,
  "exportado": "2026-10-12T22:00:00Z",
  "configuracion": {
    "max_reintentos": 10,
    "intervalo_reintento_segundos": 30,
    "timeout_envio_segundos": 60,
    "max_reenvios_por_minuto": 10,
    "recepcion_activa": true
  },
  "reglas": [
    {
      "nombre": "Banco",
      "regex_telefono": "^BANCO$",
      "regex_mensaje": "",
      "destino": "-1001234567890",
      "plantilla": "De {telefono}: {mensaje}",
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

#### La 4 es la primera versión que rompe la compatibilidad hacia atrás

Y conviene entender por qué, porque no salta a la vista. Un fichero del formato 3 tiene
**exactamente la misma forma** que uno del 4: ningún campo cambió de nombre ni de tipo. Lo que
cambió es lo que significa `destino`.

Eso lo convierte en el peor caso posible de importación: el fichero entraría **sin un solo error
de parseo** y dejaría un juego de reglas cuyos destinos no existen, que fallarían uno por uno
cuando llegara un SMS, horas después y sin nada que apuntase al fichero importado. Por eso
`ReglasExportadas.MINIMA_ACEPTADA` es 4 y la importación de un fichero anterior se rechaza
explicando el motivo.

#### Lo que no viaja en el fichero

Los **ids** no viajan: son locales a la base de datos de cada dispositivo y reutilizarlos
provocaría colisiones.

**Las dos credenciales tampoco**, y aquí hay un detalle que cuesta caro si se pasa por alto. Los
ajustes se *reemplazan* al importar, así que una credencial ausente del fichero entraría como
cadena vacía y la aplicación se quedaría muda sin que nadie lo hubiera tocado.
`ConfiguracionJson.aDominio()` recibe **las dos** actuales como argumentos precisamente para
devolvérselas; son dos parámetros y no uno porque olvidar cualquiera deja la aplicación igual de
callada.

La línea que separa lo que viaja de lo que no es «¿describe *cómo* reenviar, o identifica a *este*
teléfono?»:

| Dato | ¿Viaja? | Por qué |
|------|---------|---------|
| Modo de entrega, nombre e idioma de la plantilla | **Sí** | Describen cómo reenviar. Quien clone esto a otro teléfono querrá lo mismo |
| Los dos topes, reintentos, timeout | **Sí** | Igual |
| Token de acceso | No | Este fichero es JSON en claro y el token es permiso para escribir en nombre del número |
| `phone_number_id` | No | Hereda el papel de `simActual`: identifica *esta* cuenta, y restaurado en otra enviaría por donde no es |

Los dos bloques se tratan distinto al importar, y no por capricho:

- **Los ajustes se sustituyen.** Son un juego de valores único: mezclarlos no significa nada.
- **Las reglas se añaden** al final, conservando el orden relativo del fichero. Importar nunca
  borra: una importación equivocada que vaciara la lista dejaría el teléfono sin reenviar nada.
  Cada regla se valida antes de escribir —expresiones que compilen, nombre no vacío y un destino
  con **forma de teléfono E.164**— y las rechazadas se cuentan aparte. La validación del destino
  por forma es nueva del formato 5: un `chat_id` no se podía comprobar sin preguntar a Telegram,
  un teléfono sí, y lo que atrapa es un fichero editado a mano con un número sin prefijo.

Un fichero de los formatos 1 y 2 no lleva `configuracion` y se sigue importando: entran sus
reglas y los ajustes del teléfono se quedan como estén.

---

## 10. Mecanismo de entrega

### Modo de entrega: texto libre, plantillas, y por qué las plantillas son el camino principal

**Esta es la subsección que hay que leer antes que ninguna otra de las que siguen.** La Cloud API
tiene dos clases de mensaje y no son intercambiables:

| | Mensaje de servicio (texto libre) | Mensaje de plantilla |
|---|---|---|
| Cuándo se puede mandar | Solo dentro de una **ventana de 24 h** que abre el destinatario escribiendo al número emisor | **Siempre**, sin contacto previo |
| Contenido | Texto libre, hasta **4.096** caracteres | Un cuerpo que Meta ha aprobado, hasta **1.024** renderizados |
| Saltos de línea en la parte variable | Sí | **No**, ni tabuladores, ni más de cuatro espacios seguidos |
| Aprobación previa | No | Sí, por plantilla y por idioma |
| Coste | Los primeros **1.000 al mes y por número**, gratis; el resto se factura desde el 1 oct 2026 | **Cada** mensaje se factura |

Un reenviador de SMS es, por definición, un emisor **no solicitado**: el SMS llega cuando llega y
hay que entregarlo entonces, no cuando al destinatario le apetezca abrir una ventana. Por tanto:

> **Las plantillas no son el plan B de esta aplicación: son el camino principal.** El texto libre
> es la optimización que se aplica cuando resulta que la ventana está abierta.

Eso invierte lo que uno supondría, y es la razón de que `PlantillaDeWhatsApp` no sea un añadido
opcional.

#### El contrato de la plantilla: una sola variable

La plantilla la crea el operador en el Business Manager —esta aplicación no puede crearla— y su
cuerpo tiene que ser exactamente `PlantillaDeWhatsApp.CUERPO_RECOMENDADO`:

```
Reenvío de SMS

{{1}}

— SMStoWhats
```

Tres propiedades, y las tres son a propósito:

1. **Los saltos de línea están en la plantilla, no en la variable.** Meta aprueba el cuerpo una
   vez, con su maquetación dentro. Lo que **no** puede llevar saltos de línea, tabuladores ni más
   de cuatro espacios seguidos es el *valor* que se pasa en `{{1}}`: hacerlo devuelve un `132007`
   y el reenvío muere. De ahí `aplanar()`, que convierte los saltos en ` · ` y colapsa el resto.
2. **No empieza ni acaba con la variable**, que Meta no admite. De ahí el texto fijo a los dos
   lados.
3. **Una variable y no tres.** Remitente, fecha y texto en tres huecos daría mejor maquetación y
   rompería algo que importa más: habría una **segunda** vía de composición del mensaje, la de la
   plantilla, que ignoraría la `plantilla` de cada regla. Con una variable el texto lo compone
   siempre `EvaluadorDeReglas`, en los dos modos, y la plantilla de la regla sigue significando lo
   mismo. Se paga en estética y se cobra en no tener dos caminos.

`PlantillaDeWhatsApp.PRESUPUESTO` se deriva de ese contrato —1.024 menos el texto fijo menos un
margen— porque la aplicación **no conoce el cuerpo aprobado**: vive en la cuenta de Meta. Si se
cambia `CUERPO_RECOMENDADO`, el presupuesto se recalcula solo.

#### Los tres modos

`ModoDeEntrega`, en `data/config`, porque es configuración del transporte y la lee el transporte:
`DispatchWorker` no se entera de que existen dos formas de entregar.

| Modo | Qué hace |
|------|----------|
| `AUTOMATICO` | Intenta texto libre; si Meta contesta `131047`, reenvía como plantilla |
| `SOLO_TEXTO_LIBRE` | Nunca gasta una plantilla. Con la ventana cerrada, no entrega |
| `SOLO_PLANTILLA` | No prueba el texto libre. Entrega siempre y factura siempre |

**El salto a plantilla en `AUTOMATICO` ocurre dentro del mismo intento del worker**, no como
reintento. Devolver un `ErrorTransitorio` y dejar que `DispatchWorker` lo reintentara tendría el
mismo resultado diez veces: la ventana seguiría cerrada, porque lo que la abre es que el
destinatario escriba.

Es la primera vez que este transporte hace **dos peticiones distintas** para un reenvío. El worker
no se entera, y eso es lo correcto. Para conseguirlo, el transporte tiene un tipo interno `Entrega`
que lleva el `ResultadoEnvio` más un booleano `ventanaCerrada`: eso es lo único que `ResultadoEnvio`
no puede decir, y se queda dentro del transporte en lugar de subir hasta el worker.

**`AUTOMATICO` sin plantilla configurada se comporta como `SOLO_TEXTO_LIBRE`**, porque no hay nada
a lo que saltar. No es un fallo silencioso: el motivo que queda en el registro dice que hace falta
una plantilla y dónde se configura.

### El interfaz, y qué le debe el transporte al worker

`DispatchWorker` no sabe por qué medio sale un reenvío. Depende de `EnviadorDeMensajes`, que
declara tres cosas:

| Miembro | Para qué |
|---------|----------|
| `enviar(destino, texto, timeoutSegundos)` | Entregar y **esperar** el desenlace |
| `limiteCaracteres(): Int` | Avisar mientras se escribe la plantilla, que es cuando se puede hacer algo |
| `motivoNoDisponible(): String?` | Avisar **antes** de pulsar enviar, y en el Panel |

Los tres salieron de necesidades reales de quien llama, no de imaginar un interfaz genérico.
`motivoNoDisponible` devuelve el **motivo en texto** y no un booleano porque la pantalla tiene que
poder decir *por qué* no se puede entregar, y los motivos cambian con el medio.

`limiteCaracteres` **suspende**, y es el único cambio de firma que la juntura ha sufrido en tres
migraciones. Era una propiedad, y dejó de poder serlo cuando el límite pasó a depender de la
configuración: 4.096 caracteres en texto libre y 978 dentro de una plantilla, así que el que
aplica no se puede saber sin leer DataStore. Lo que lo forzó no fue el medio —fue que por primera
vez **el límite es configurable**. Los dos ViewModel que lo pintan lo exponen como `StateFlow<Int>`
con el valor del texto libre como inicial.

**Esperar el desenlace no es un detalle del medio.** Con `SmsManager` significaba suspender hasta
que llegara el broadcast del `sentIntent`, porque la llamada de envío retornaba antes de que el
mensaje saliera del dispositivo; con la Cloud API significa esperar la respuesta HTTP. El motivo es el
mismo en los dos casos: si la llamada retornase antes de conocer el resultado, **todas las
entregas parecerían correctas y los fallos reales desaparecerían**.

### Texto plano, y `preview_url` en false

El cuerpo del SMS es contenido de un tercero y **no se interpreta nunca**. Es la misma regla que
el evaluador ya aplica sustituyendo `{mensaje}` en último lugar, y aquí se concreta en dos cosas.

**El formato se acepta tal como salga.** Con Telegram el peligro era `parse_mode`: ponerlo hacía
que cualquier SMS con un `_` o un `*` devolviera `400 can't parse entities` y el reenvío muriera
por haber querido ponerlo en negrita. WhatsApp no tiene ese interruptor: **interpreta `*negrita*`
y `_cursiva_` siempre**, y un SMS con asteriscos llegará con medio texto en negrita. Se acepta.
Escapar el cuerpo sería *interpretar* contenido ajeno para modificarlo, que es lo que este
proyecto no hace en ningún sitio, y el mensaje llega entero de las dos formas.

**`preview_url` va en `false`, y eso no es estética.** En `true`, WhatsApp **descarga** el primer
enlace que encuentre en el cuerpo para pintar su tarjeta de vista previa. Como el cuerpo es un SMS
ajeno, entregar un mensaje provocaría una petición de los servidores de Meta a una URL que ha
puesto quien mandó ese SMS: desde confirmar que un enlace de phishing tiene público hasta quemar
un enlace de un solo uso. No es configurable.

### Mapeo de respuestas

Lo implementa `MapeoDeRespuestas.interpretarEnvio()`, una función **pura** de (código HTTP,
cuerpo) a `ResultadoEnvio`, separada a propósito de quien hace la petición para que se pueda
probar sin red. `MapeoDeRespuestasTest` la cubre caso por caso.

**La diferencia de fondo con la versión de Telegram: el código HTTP ya no basta.** La Cloud API
mete **cuatro averías distintas en el mismo `400`** y las distingue en `error.code` del cuerpo. El
estado HTTP queda como red de seguridad para lo que no traiga cuerpo legible.

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
| `runAttemptCount >= maxReintentos` | Agotado | `failure()` | `FALLIDO` |

El `message` que devuelve Meta se incorpora al motivo tal cual, con el `error.code` por delante.
Es texto en inglés y poco bonito —«(#131026) Unable to deliver message»—, pero es exactamente lo
que hace falta leer en el registro, y el código es la clave con la que se busca en la
documentación de Meta.

Cuatro filas de esa tabla merecen explicación aparte.

#### Un `100` es un `phone_number_id` malo, no un recurso que falta

Llega como un `400` con un texto genérico («Unsupported post request. Object with ID … does not
exist»). Es el equivalente exacto del `404` de Telegram: lo que no existe es la **ruta**, y la
ruta la forma la credencial. `MapeoDeRespuestas.esFalloDeCredencial()` agrupa por tanto los dos
grupos — `190`/`0`/`3` para el token y `100` para el id — porque la Cloud API necesita dos
credenciales donde el Bot API necesitaba una, y fallan por sitios distintos.

#### Un `4xx` desconocido se cierra, no se reintenta

Apartándose de lo que el MASTERPLAN esbozó. El reintento manda los **mismos bytes** a la misma
URL, así que un «tu petición está mal» no puede cambiar de respuesta por esperar: diez intentos
acaban en el mismo sitio gastando diez líneas de registro. Lo desconocido que **no** es un `4xx`
sí se reintenta, por prudencia.

#### El `131047` no se clasifica, se intercepta

`MapeoDeRespuestas.esVentanaCerrada()` es un predicado aparte y no un caso nuevo de
`ResultadoEnvio`, porque `ResultadoEnvio` lo lee `DispatchWorker` y el worker no debe enterarse de
que hay dos formas de entregar. Ver «Modo de entrega».

#### Un fallo de credencial no es un fallo de este reenvío

Con una credencial mal puesta **fallan todos los reenvíos por igual**, y enterrar eso en el
registro del primero de cuatrocientos desperdicia la única información útil que hay. `WhatsAppSender` lo anota
en `EstadoDeLaCuenta`, que lo persiste —esta aplicación no está «abierta»: cada SMS despierta el
proceso y lo deja morir, así que un flag en memoria se habría olvidado antes de que nadie mirase—
y el Panel lo enseña en rojo por encima de todo lo demás.

Un envío correcto lo borra, porque es la mejor prueba posible de que las credenciales sirven.
Guardarlas de nuevo en Ajustes también, porque es el gesto de quien viene a arreglarlo.

Solo los códigos de credencial lo encienden. Un `131026` es un problema de **ese destino
concreto**, y mandar a revisar los ajustes sería mandar a mirar donde no es.

#### Meta no manda ninguna espera

Telegram decía en `parameters.retry_after` cuántos segundos había que esperar ante un `429`, y
`ResultadoEnvio.ErrorTransitorio` sigue teniendo un `esperaSegundos` para eso. **La Cloud API no
manda nada equivalente**, así que hoy llega siempre null, que es lo que ya significaba «aplica el
backoff de siempre».

`DispatchWorker.esperarSiLoPiden()` por tanto no duerme nunca: retorna en su primera línea. Se
queda escrito, con su tope de **120 segundos**, porque el contrato de `ResultadoEnvio` admite la
espera y el día que un medio la pida otra vez el worker ya sabe respetarla. El razonamiento sigue
siendo válido: WorkManager cuenta su backoff desde que el worker termina, así que dormir dentro es
la única forma de retrasar el siguiente intento, y un worker tiene unos diez minutos antes de que
el sistema lo pare.

### Textos de más de 4.096 caracteres

Se trocean, y si un trozo falla después de que otro haya salido, **el reenvío entero se reintenta
completo**, aceptando que el chat pueda ver repetido el principio. Es exactamente el criterio que
se aplicaba a las partes de un SMS concatenado: dar por bueno un mensaje al que le falta el final
es peor que duplicar su principio.

### Idempotencia

Es de cliente, en dos niveles:

- **Antes de crear reenvíos:** `ProcesarSmsEntranteUseCase.procesar()` comprueba si el SMS ya
  tiene reenvíos. Si los tiene, no crea más y solo reencola los pendientes. Esto es lo que hace
  seguro que el rescate de huérfanos reprocese un SMS que se quedó a medias.
- **Antes de cada intento:** `DispatchWorker` lee el estado del reenvío y devuelve éxito sin
  tocar el transporte si ya está `ENVIADO`.

Además, `ColaDeEnvios` usa `ExistingWorkPolicy.KEEP`, así que un reenvío nunca tiene dos trabajos
simultáneos.

---

## 11. Los dos topes

Esta sección era **«Protecciones antibucle y de coste»** y describía tres protecciones. Dos han
desaparecido. Esto explica por qué, porque desde fuera parecen una omisión y no lo son.

### Qué protegía SMStoSMS, y de qué

Reenviar SMS tenía dos propiedades que una petición HTTP no tenía: **costaba dinero** y **podía
realimentarse**. Si el destino contestaba, su respuesta entraba por `SmsReceiver` como cualquier
otro SMS; si casaba con una regla, se reenviaba; y con los reintentos automáticos detrás, un error
de configuración se convertía en una factura. De ahí tres protecciones:

1. El remitente es uno de los destinos configurados → el SMS se descarta.
2. El destino de una regla es el propio remitente → esa coincidencia se omite.
3. Tope de reenvíos creados por minuto.

### Por qué las dos primeras ya no existen

Las dos comparaban el `destino` de una regla con el remitente del SMS. En el momento en que
`destino` pasó a ser un `chat_id`, esa comparación dejó de significar nada: un chat de Telegram y
un número de teléfono son identidades sin relación.

**Ahora el `destino` vuelve a ser un teléfono, así que la comparación sería posible otra vez — y
sigue sin merecer la pena.** El motivo cambió, y es más fuerte que el anterior: el bucle es
imposible por construcción, porque **un mensaje de WhatsApp no vuelve nunca como SMS**. El SMS
entra por la radio y el reenvío sale por la red; no hay realimentación que detectar. Lo único de
esa forma lo vigila Meta sola, rechazando con el error `131021` un envío del número emisor a sí
mismo.

Así que **no se restauran**, y esta vez el argumento no depende de que los identificadores sean
incomparables.

Se fueron con ellas `AppConfig.protegerBucles` —un interruptor que ya no apagaba nada—,
`NormalizadorTelefono` entero con sus dieciocho tests, y `ReglaDao.obtenerDestinosActivos()`, que
solo servía a la primera.

> Mantener código que no puede ejecutarse no es prudencia: es dejar una trampa para quien lea el
> fichero el año que viene y dé por hecho que algo lo protege.

### El primero: `maxReenviosPorMinuto`

`AppConfig.maxReenviosPorMinuto` (10 por defecto, rango 1–100) limita cuántos reenvíos pueden
crearse en los últimos 60 segundos. El exceso se descarta, el SMS queda `DESCARTADO` con su motivo
y se registra como `LogTipo.LIMITE_ALCANZADO`.

**Cambia de significado por tercera vez**, y es la tercera vez que sobrevive por un motivo
distinto del que lo hizo nacer:

| Transporte | Para qué servía |
|------------|-----------------|
| SMS | Cortafuegos económico: un bucle con reintentos detrás era una factura |
| Telegram | Los límites del Bot API: un mensaje por segundo y chat. Entregar era gratis |
| Cloud API | Las dos cosas a la vez, y la económica aprieta más que nunca |

Lo que sigue haciendo es cortar una **avalancha**: una ráfaga de SMS en un minuto. El caudal de la
propia Cloud API es holgado —del orden de 80 mensajes por segundo— así que ese no es el techo; el
que existe es el `131056`, demasiados mensajes al mismo destinatario en poco tiempo.

### El segundo: `maxMensajesPorMes`

Y es el que de verdad importa ahora. **Es la primera protección de este proyecto que mide
dinero.**

| Qué | Cuánto |
|-----|--------|
| Mensajes de servicio gratis | **1.000 al mes y por número** |
| Mensajes de servicio pasados los mil | Se facturan (desde el 1 oct 2026) |
| Mensajes de plantilla | Se facturan **todos**, siempre |

Mil al mes son **treinta y tres al día**. El tope por minuto no protege de esto en absoluto: 10
por minuto son 14.400 al día, tres órdenes de magnitud por encima del primer techo que se va a
encontrar. Son dos protecciones distintas porque miden cosas distintas: **una corta una ráfaga, la
otra corta un mes**.

`AppConfig.maxMensajesPorMes` vale 900 por defecto —por debajo del tramo gratuito, para que una
instalación que nadie ha configurado no llegue a facturar nada— y un `0` lo desactiva. Se
comprueba en el mismo sitio y con el mismo mecanismo que el tope por minuto: descartar el SMS y
dejar el motivo en el registro, en lugar de inventar una tercera forma de fallar.

`ContadorDeMensajes` lleva la cuenta, por mes natural y separando servicio de plantilla:

- Cuenta **mensajes entregados y no reenvíos**. Un SMS largo se trocea y Meta factura por mensaje,
  así que contar reenvíos daría una cifra que no se parece a la factura, que es justo lo que esto
  existe para anticipar.
- Distingue servicio de plantilla porque tienen precios distintos, y porque la cifra interesante
  no es el total: es **cuántos han tenido que ir por plantilla**, que mide cuánto se está
  entregando fuera de la ventana de 24 h.
- El cambio de mes se resuelve **dentro de la misma transacción** que el incremento, no con un
  trabajo programado: una pieza más que puede no ejecutarse, en una aplicación cuyos trabajos
  periódicos ya pelean con Doze.
- Vive en el DataStore de los ajustes con sus propias claves, igual que `EstadoDeLaCuenta`, porque
  no es configuración: es un hecho sobre *este* teléfono y *este* mes.

El Panel lo enseña en la primera pantalla. Es la única cifra de esta aplicación que cuesta dinero
y que nadie va a ir a buscar a propósito; enterrada en Ajustes, la primera noticia de haberse
pasado sería la factura.

### El tipo de log, renombrado

`BUCLE_EVITADO` pasó a `LIMITE_ALCANZADO`. Se llamaba por una protección que ya no existe, y el
filtro de la pantalla de Logs se llama ahora **Descartados** en lugar de **Bucles**.

El nombre ha envejecido mejor que su motivo: cuando se renombró había **un** descarte posible, el
del límite de frecuencia, y ahora hay dos. «Un tope dijo que no» es exactamente lo que registra.

Renombrar un valor de enum que se guarda como texto en la base de datos habría sido una migración
si hubiera datos instalados. No los había, y era la última ocasión de hacerlo gratis.

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
    id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    orden           INTEGER NOT NULL,                -- prioridad ascendente
    nombre          TEXT    NOT NULL,
    regex_telefono  TEXT,                            -- null = no filtra
    regex_mensaje   TEXT,                            -- null = no filtra
    destino         TEXT    NOT NULL,                -- chat_id o @usuario de canal
    plantilla       TEXT    NOT NULL,                -- sin DEFAULT: ver mas abajo
    activa          INTEGER NOT NULL DEFAULT 1,
    continuar       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX index_reglas_orden ON reglas(orden);

CREATE TABLE reenvios (
    id              TEXT    PRIMARY KEY NOT NULL,   -- UUID
    sms_id          TEXT    NOT NULL,
    regla_id        INTEGER,                         -- null si la regla se borró
    nombre_regla    TEXT    NOT NULL,                -- copia: sobrevive al borrado
    destino         TEXT    NOT NULL,
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
  PROCESADO   evaluado, al menos una regla casó
  SIN_REGLA   evaluado, ninguna regla casó. No es un error
  DESCARTADO  bloqueado por el límite por minuto

EstadoReenvio
  PENDIENTE   pendiente de entregar, o con un intento fallido que se reintentará
  ENVIADO     Meta confirmó la entrega
  FALLIDO     fallo permanente, no se volverá a intentar

LogTipo
  SMS_RECIBIDO · REGLA_APLICADA · SMS_REENVIADO
  SIN_REGLA · LIMITE_ALCANZADO · ERROR · SISTEMA
```

### Decisiones de diseño

- **`sms` no guarda estado de envío.** Los intentos, los errores y las fechas de confirmación
  están en `reenvios`, una fila por destino. Un SMS que casa con dos reglas tiene dos reenvíos
  con suertes posiblemente distintas, y un único campo `enviado` no podría representarlo.
- **`texto_final` se congela** en el momento de la evaluación. Editar o borrar la regla después
  no altera lo que ya estaba encolado.
- **`nombre_regla` está desnormalizado** para que el historial siga siendo legible cuando la
  regla se borre: la clave ajena es `SET NULL`, no `CASCADE`.
- **`log_entries.destino` también está desnormalizado**, para poder leer el registro de principio
  a fin sin cruzar tablas, y para que siga teniendo sentido cuando el reenvío se haya purgado.

### Por qué el esquema empieza en la versión 1, otra vez

Es la segunda vez que pasa, y por el mismo motivo. SMSGateway llegó a la v2 con una migración
1→2 y el esquema volvió a la v1 al nacer SMStoSMS; SMStoSMS llegó otra vez a la v2 —la que añadió
`reglas.ignorar_mayusculas`— y el esquema vuelve a la v1 al nacer SMStoWhats.

Con `applicationId = com.capicua.smstowhats`, Android trata esto como una aplicación distinta,
con su propio directorio de datos, y el fichero se llama `smstowhats.db`. Ningún dispositivo
tiene esta base de datos: no hay nada que migrar, y arrastrar la historia de migraciones de otra
aplicación obligaría a mantener código que nunca se ejecutaría. Se colapsó en un esquema v1 limpio
con `ignorar_mayusculas` ya dentro.

**Esto se puede hacer una sola vez.** En cuanto la aplicación se instale en un teléfono con reglas
dentro, el esquema deja de ser editable en sitio.

### Migraciones

Los esquemas se exportan a `app/schemas/` y **se versionan**: son la referencia para escribir
migraciones. Nunca se usa `fallbackToDestructiveMigration()`, porque perdería SMS y reenvíos
pendientes; si falta una migración, se prefiere que la aplicación falle de forma ruidosa.

Las migraciones se escriben a mano en `data/local/db/Migraciones.kt` y `DatabaseModule` las
registra con `addMigrations()`.

| Versión | Cambio |
|---------|--------|
| 1 | Esquema inicial con las cuatro tablas, `ignorar_mayusculas` incluida |

`Migraciones.TODAS` está **vacío**, y es lo último que puede estarlo: a partir de la primera
instalación con reglas dentro, cambiar la v1 sin migrar hace que Room aborte al abrir la base de
datos de quien ya la tenía.

Dos cosas que el esquema de esta aplicación **no** tiene, y conviene saber que es a propósito:

- **`reenvios.partes` no existe.** Contaba los fragmentos de un SMS concatenado. Un mensaje de
  la Cloud API admite 4.096 caracteres de texto libre y no se tarifica por partes.
- **`reglas.plantilla` no tiene `defaultValue`.** Lo tenía —`'{mensaje}'`— y eso significaba que la
  plantilla por defecto estaba escrita dos veces: ahí y en `Regla.PLANTILLA_POR_DEFECTO`. Al
  cambiar la del dominio habrían dejado de coincidir. Ninguna inserción usa la de la entidad
  —toda fila viene de un `Regla`— así que quitarla deja una sola fuente de verdad.

Un detalle que se paga caro si se pasa por alto: el valor por defecto del `ALTER TABLE` tiene
que coincidir con el `defaultValue` declarado en la entidad. Si no coinciden, Room valida el
esquema después de migrar, encuentra la diferencia y aborta — con la base de datos ya migrada.

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

En **Configuración**, persistidos en DataStore:

| Parámetro | Por defecto | Rango | Qué controla |
|-----------|-------------|-------|--------------|
| Token de acceso | Vacío | — | La credencial de la Cloud API. Sin ella no se entrega nada |
| Id del número | Vacío | — | El `phone_number_id`. **No es el número de teléfono** |
| Modo de entrega | Automático | — | Texto libre, plantilla, o texto libre con salto a plantilla |
| Nombre de la plantilla | Vacío | — | Vacío significa «solo entrego dentro de la ventana de 24 h» |
| Idioma de la plantilla | `es` | — | El código con el que se dio de alta |
| Leer los SMS que llegan | Activado | — | Interruptor general: al apagarlo se deshabilita el componente `SmsReceiver` |
| Máximo de reenvíos por minuto | 10 | 1–100 | Corta una ráfaga. §11 |
| Tope de mensajes al mes | 900 | 0–100.000 | Corta un mes. `0` lo desactiva. §11 |
| Espera de confirmación | 60 s | 10–300 | Cuánto se espera la respuesta de Meta |
| Máximo de reintentos | 10 | 1–100 | Cuándo se da una entrega por perdida |
| Intervalo inicial de reintento | 30 s | 5–3600 | Backoff lineal de WorkManager |

El token se enmascara en pantalla —con el ojo para comprobar lo que se ha pegado—, **no se
escribe nunca en el registro** y **no viaja en la copia de seguridad**. El id del número no se
enmascara, porque no es secreto y a cambio hay que poder compararlo de un vistazo con el que
enseña la consola de Meta, que es donde está el error más frecuente de toda la configuración.

El botón *Comprobar las credenciales* consulta el objeto del número y responde con su **nombre
verificado** y su **teléfono**, en lugar de un «correcto» que obligase a creerse la pantalla. No
hay `getMe` en la Cloud API, y lo que hay es mejor: esa consulta ejercita **las dos credenciales a
la vez** —el token en la cabecera, el id en la ruta—, así que si cualquiera de las dos está mal,
falla.

De paso trae el `quality_rating` del número (`GREEN`/`YELLOW`/`RED`), que es información que
Telegram no daba. Meta puntúa cada número emisor según cuánta gente lo bloquea o lo denuncia, y
cuando baja a rojo **limita o bloquea el número**. Es el riesgo real de entregar mensajes que el
destinatario no ha pedido, y el único de los riesgos de este proyecto que avisa antes de
materializarse.

Distingue cuatro resultados, y no dos: que no haya red no es que las credenciales estén mal, y
decirlo mandaría a cambiar algo que funciona.

Apagar «Leer los SMS que llegan» no pone un `if` al principio de `onReceive`: deshabilita el
componente con el `PackageManager` (`util/ControlRecepcion`), de modo que el sistema deja de
entregarle el broadcast. Ni se arranca el proceso, ni hay que leer DataStore —que es asíncrono—
en el hilo principal del broadcast, ni puede colarse un SMS por una carrera entre el ajuste y el
mensaje. Corta la **entrada**: lo que ya estaba guardado y encolado se sigue reenviando. El
estado del componente lo persiste el sistema y sobrevive a los reinicios; lo único que lo
devuelve al valor del manifiesto es una reinstalación, y por eso `SmsToWhatsApplication` lo
vuelve a sincronizar con el ajuste guardado al arrancar.

### Lo que el teléfono tiene que permitir

Dos ajustes del sistema deciden si la aplicación funciona de verdad, y ninguno se puede conceder
desde el código:

| Ajuste | Se puede consultar | Qué pasa sin él | Dónde está en la app |
|--------|--------------------|-----------------|----------------------|
| Exención de batería (Doze) | Sí, `PowerManager` | Las entregas se retrasan horas con la pantalla apagada | Configuración → botón de batería (`util/OptimizacionBateria`) |
| Inicio automático del fabricante | **No**, no hay API | Tras reiniciar el teléfono no se recibe nada hasta abrir la app a mano | Configuración → botón de inicio automático (`util/InicioAutomatico`) |

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
adb logcat -s "SmsReceiver" "SmsIngestionService" "WhatsAppSender" \
              "DispatchWorker" "OrphanRescueWorker" "HealthMonitorWorker"

# Base de datos
adb shell run-as com.capicua.smstowhats ls databases/
adb exec-out run-as com.capicua.smstowhats cat databases/smstowhats.db > smstowhats.db
sqlite3 smstowhats.db "SELECT estado, COUNT(*) FROM sms GROUP BY estado;"
sqlite3 smstowhats.db "SELECT destino, estado, intentos, ultimo_error FROM reenvios;"

# Configuración — OJO: este fichero contiene el token de acceso
adb shell run-as com.capicua.smstowhats cat files/datastore/smstowhats_config.preferences_pb

# Estado de los workers
adb shell dumpsys jobscheduler | grep -A 5 capicua
```

### Exportar el registro desde la app

**Logs** → **Exportar** genera un fichero de texto en la caché y lo comparte mediante
`FileProvider`. Incluye el tipo, el SMS, el destino y el detalle de cada entrada.

Su autoridad se deriva del `applicationId` (`${applicationId}.provider` en el manifiesto y
`BuildConfig.APPLICATION_ID + ".provider"` en `Constants`) y **no se escribe a mano**: una
autoridad es única en todo el dispositivo, así que con el nombre fijo las variantes de
depuración y de publicación declaraban la misma y Android se negaba a tener las dos instaladas
(`INSTALL_FAILED_CONFLICTING_PROVIDER`), que es justo lo que el `applicationIdSuffix` pretendía
evitar. Ese fallo no se ve al compilar: aparece al instalar.

---

## 14. Posibles incidencias

### El SMS se recibe pero no se entrega

Antes de mirar las reglas, **mira el Panel**: si faltan las credenciales, o Meta las ha
rechazado, o se ha agotado el tope del mes, hay
un aviso en rojo arriba y nada se va a entregar hasta que se arregle en Ajustes.

Si no hay aviso, lo más probable es que **ninguna regla case**. La pantalla de Inicio lo dice: el
SMS aparece como «Sin regla». Para confirmarlo:

1. **Reglas** → **Probar**, pega el remitente y el texto exactos, y mira qué dice.
2. Revisa que la regla esté **activa**. Una desactivada no se evalúa.
3. Comprueba las mayúsculas: los patrones las distinguen. `CODIGO` no casa con `codigo`; usa
   `(?i)`.
4. Comprueba el remitente: si llega como `BANCO` y tu patrón espera `^\+34`, no casará nunca.

Si la regla sí casa pero la entrega no sale, revisa el registro filtrando por **Errores**: el
motivo lleva el `error.code` y el `message` que devolvió Meta, que suelen decir exactamente qué
pasó. El código es con lo que se busca en la documentación.

### El SMS aparece como «Descartado»

Solo hay un motivo posible: **se superó el límite de reenvíos por minuto**. El motivo está en la
propia fila de Inicio y en el registro, filtrando por **Descartados**.

Si pasa con tráfico normal, sube el límite en Configuración. Si pasa en ráfagas, es lo que el
límite existe para evitar. **Mira también el contador del Panel**: si el descarte dice «tope de
mensajes del mes», lo que se ha agotado es la cuota, y subir el tope por minuto no arregla nada.

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

### Las entregas fallan con «Too Many Requests»

Un `429`. Se trata como transitorio y se reintenta con el backoff de WorkManager; Meta no dice
cuánto esperar, al contrario que Telegram.
Si es constante, hay más tráfico del que el destinatario admite —recuerda que el límite por
chat es de **un mensaje por segundo**, y en un grupo unos 20 por minuto— y lo que toca es bajar
`Máximo de reenvíos por minuto` o repartir los avisos en varios chats.

### Las entregas fallan con un error de red

Sin conexión no se entrega nada, pero **no se pierde nada**: el reenvío queda `PENDIENTE` y
`ColaDeEnvios` impone `NetworkType.CONNECTED`, así que el worker no se ejecuta hasta que haya red
en lugar de gastar sus intentos contra una red que no está.

```bash
adb shell dumpsys connectivity | grep -i "NetworkAgentInfo"
```

### Nada se entrega con la pantalla apagada

Falta la exención del modo Doze:

```bash
adb shell dumpsys deviceidle whitelist | grep capicua
# Si no aparece:
adb shell dumpsys deviceidle whitelist +com.capicua.smstowhats
```

En Xiaomi, Huawei y Samsung hace falta además activar el **inicio automático** y quitar la app de
la lista de aplicaciones suspendidas. Ver [`CLAUDE.md`](../CLAUDE.md).

### La app no recibe SMS tras reiniciar el dispositivo

```bash
# Verificar que el permiso de arranque está declarado y concedido
adb shell dumpsys package com.capicua.smstowhats | grep -i boot

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

### Un mensaje llegó duplicado al chat

Dos causas posibles:

1. **El texto pasaba de 4.096 caracteres** y se troceó: si un trozo falló después de que otro
   saliera, el reenvío se reintenta completo y el principio se repite. Es el comportamiento
   documentado (§10). Para evitarlo, acorta la plantilla: el editor muestra la longitud.
2. **Meta aceptó el mensaje y la respuesta se perdió** por un corte de red justo después. El
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

Muchos `PENDIENTE` apuntan a un problema de entrega sostenido, no de retención —empezando por el
token—. Muchos `FALLIDO` se purgan con el resto de SMS tramitados a los 30 días.

### Consumo de batería excesivo

Normalmente es `OrphanRescueWorker` encontrando trabajo cada 30 segundos, lo que significa que
hay reenvíos atascados reintentándose en bucle. Revisa los pendientes y sus `ultimo_error`; si
son fallos permanentes mal clasificados, el worker seguirá insistiendo hasta agotar los
reintentos.

El caso más probable es una **credencial rechazada**: cada reenvío intenta, falla y vuelve.
El aviso del Panel está precisamente para que eso se vea antes de notarlo en la batería.

Subir `Intervalo inicial de reintento` y bajar `Máximo de reintentos` reduce el consumo a costa
de tardar más en entregar lo que sí es recuperable.
