# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run commands

> **Toolchain:** the project targets JVM 17 and builds with AGP 8.5.2. If the JDK on `PATH` is
> newer than 21, point `JAVA_HOME` at a JDK 17 or 21 — AGP 8.5.2 does not support JDK 25.
> `local.properties` is not versioned; if it is missing, set `ANDROID_HOME` instead.

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Release build (requiere las siguientes claves en local.properties):
#   KEYSTORE_PATH=<ruta al almacén, absoluta o relativa al módulo app/>
#   KEYSTORE_PASSWORD=<contraseña del almacén>
#   KEY_ALIAS=<alias de la clave>
#   KEY_PASSWORD=<contraseña de la clave>
# El almacén está fuera del repositorio y se comparte con las demás aplicaciones de
# la casa, así que KEYSTORE_PATH va relativa (`../../Claves/capicua.jks`): el file()
# de app/build.gradle.kts la resuelve desde el módulo, no desde la raíz.
# Sin las cuatro claves el build NO falla: el APK sale sin firmar.
# El APK resultante se genera como smstotelegram-<versionName>.apk
./gradlew assembleRelease

# Unit tests
./gradlew test

# Single test class
./gradlew test --tests "com.capicua.smstotelegram.domain.rules.EvaluadorDeReglasTest"

# Grant runtime permissions on a dedicated device (run after install)
# There is no SEND_SMS and no READ_PHONE_STATE: this app receives over the radio and
# delivers over the network. See "Permissions" below.
adb shell pm grant com.capicua.smstotelegram android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstotelegram android.permission.READ_SMS
adb shell pm grant com.capicua.smstotelegram android.permission.POST_NOTIFICATIONS

# Exempt the app from Doze mode / battery optimization (REQUIRED for reliable dispatch)
# Without this, WorkManager may be deferred indefinitely when the screen is off.
# See "Device setup" section below for the on-device UI alternative.
adb shell dumpsys deviceidle whitelist +com.capicua.smstotelegram

# Verify the app is in the whitelist
adb shell dumpsys deviceidle whitelist
# Expected: a line containing "com.capicua.smstotelegram"

# Remove from whitelist (if needed)
adb shell dumpsys deviceidle whitelist -com.capicua.smstotelegram

# Real-time log monitoring
adb logcat -s "SmsReceiver" "SmsIngestionService" "TelegramSender" "DispatchWorker" "OrphanRescueWorker" "HealthMonitorWorker"

# Simulate an incoming SMS on an emulator (no SIM required)
adb emu sms send +34600112233 "Tu codigo es 4821"
```

## Releases

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` (they go together: Android
   refuses to install an APK whose `versionCode` is not higher than the installed one).
2. **Write the entry in `CHANGELOG.md`**: what sits under `[No publicado]` becomes the new version
   with its date. That file is the summary of *what* changed; the GitHub release notes are the long
   form — how to install it, how to update from the previous version — and may repeat it.
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/smstotelegram-<versionName>.apk`,
   signed with the shared keystore (see the build commands above).
4. Tag on `main` and publish: `gh release create vX.Y.Z --notes-file <notes> <apk>`.

The APK keeps the same signing key release after release. That is what lets a user install the new
one on top without losing rules, settings or history — and also why an APK of a *different* key
(a debug build, say) cannot be upgraded in place.

## Architecture

Clean Architecture with strict 3-layer separation and MVVM in the presentation layer. Dependency injection throughout via Hilt.

```
Presentation  (Fragments + ViewModels)
    ↓ uses
Domain        (UseCases + rules engine + pure Kotlin models)
    ↓ uses interfaces, implemented by
Data          (Room · SmsManager · DataStore · WorkManager)
```

### SMS lifecycle

```
SIM → SmsReceiver.onReceive()           ← BroadcastReceiver (main thread, < 10 s)
        ↓ startForegroundService()
      SmsIngestionService               ← ForegroundService (must call startForeground() < 5 s)
        ↓ ProcesarSmsEntranteUseCase
      Room INSERT (SmsEntity)           ← outbox: persists BEFORE doing anything else
        ↓ rate limit
        ↓ EvaluadorDeReglas
      Room INSERT (ReenvioEntity × N)   ← one row per destination chat
        ↓ ColaDeEnvios.encolar()
      DispatchWorker.doWork()           ← one worker per reenvío, with linear backoff
        ↓ EnviadorDeMensajes.enviar()   ← the interface; TelegramSender implements it
      POST api.telegram.org/bot<token>/sendMessage → update Room
```

**Everything above the interface is medium-agnostic, and that is the point.** The outbox, the
client-side idempotency, the orphan rescue, the retry policy and the history were written for an
HTTP gateway, survived the move to `SmsManager`, and survived the move to Telegram without being
touched. `TransportModule` is the only class in the project that names a concrete transport, so
changing the medium is changing one `@Binds`.

`OrphanRescueWorker` self-chains every 30 s and rescues **two** things: SMS left in `PENDIENTE`
whose rule evaluation never completed, and `PENDIENTE` reenvíos with no live WorkManager job.

`HealthMonitorWorker` runs every 15 minutes and purges confirmed reenvíos, already-handled SMS
(> `RETENTION_DELIVERED_DAYS`, currently **30 days**) and old log entries
(> `RETENTION_LOGS_DAYS`, also 30 days).

`BootReceiver` re-enables WorkManager tasks after device restart.

### Forwarding mechanism

The operator configures an ordered list of **rules** on the Reglas screen; each rule decides,
for a given incoming SMS, **which Telegram chat** receives it and with what text.

A rule matches when **both** criteria hold. An empty criterion always holds, so a rule with both
empty matches every SMS:

- `regexTelefono` — applied to the sender **as the PDU delivers it**, unnormalized. May be
  alphanumeric (`BANCO`, `AMAZON`).
- `regexMensaje` — applied to the full SMS body.

Matching is **partial** (`Regex.containsMatchIn`): `codigo` matches "Tu codigo es 4821". Anchor
with `^…$` to require the whole text. Patterns are case-sensitive unless the rule sets
`ignorarMayusculas`, which applies `RegexOption.IGNORE_CASE` to **both** expressions at once;
an inline `(?i)` still works and the two are cumulative, not conflicting.

Rules are evaluated in ascending `orden`. The first match wins and evaluation stops, **unless**
the rule has `continuar = true`, in which case evaluation carries on and the same SMS can reach
several destinations.

The rule's `destino` is a Telegram chat, used **verbatim**, and **no marker is resolved in it**.
`DestinoDeTelegram` accepts the three forms the Bot API takes: a positive `chat_id` (a person), a
negative one (a group or channel, `-100…` for supergroups and channels), or an `@username` of a
**public channel**. A bot cannot address a person by `@username` — it needs the numeric id, which
only exists once that person has written to the bot.

That no marker is resolved there is a security property, not a simplification: **the SMS body can
never influence where anything is sent**. SMStoSMS did resolve `{telefono}` in the destination, to
reply to whoever wrote in; with Telegram there is no way to turn an SMS sender into a chat — they
are unrelated identities — so the marker left the destination and the "Contestar al remitente"
button went with it.

**Nobody knows their own `chat_id`.** Telegram does not show it in any of its screens, so the rule
editor has a *Elegir de los chats del bot* button that calls `getUpdates` and offers the list
(`DescubridorDeChats`). It is the direct replacement for the old reply-to-sender button, and for
the same reason: a value you have to know in advance does not get used. Two things that button
cannot hide, and that the screen therefore says out loud: **a bot cannot write first** (an empty
list means someone needs to send it a `/start`), and Telegram only keeps those updates for 24
hours.

The delivered text comes from the rule's `plantilla`, with three optional markers:

- `{mensaje}` — the received SMS body
- `{telefono}` — the sender's number
- `{fecha}` — reception date, formatted for display in **local** time (not ISO UTC: a person
  reads it on their phone)

`{mensaje}` is substituted **last** on purpose, so the SMS body — third-party content — is never
re-scanned and cannot inject markers.

**The default template is `{telefono} · {fecha}\n{mensaje}`, not `{mensaje}`.** With SMS the body
was enough: the forward arrived as another SMS and the phone already showed who sent it. Over
Telegram everything arrives from the same bot, so four alerts in a row without the sender inside
are four indistinguishable messages. The context SMS gave for free has to be put there by hand.

### The rate limit, and the two guards that no longer exist

SMStoSMS had three protections. **Two of them are gone, and deleting them was correct** — read
this before "restoring" anything that looks missing:

1. *An SMS whose sender is one of the configured destinations is discarded.*
2. *A matched rule whose destination equals the sender is skipped.*

Both compared a rule's `destino` against the SMS sender. The moment `destino` became a `chat_id`
that comparison stopped meaning anything: a Telegram chat and a phone number are unrelated
identities, so they cannot be "the same" and cannot close a circle. **A Telegram message never
comes back as an SMS** — it is delivered and it stays there. `AppConfig.protegerBucles`,
`NormalizadorTelefono` and `ReglaDao.obtenerDestinosActivos()` went with them.

What survives is `AppConfig.maxReenviosPorMinuto`, and **not for the original reason**. It used to
be a cost firewall: a forwarding loop with automatic retries behind it turned a misconfiguration
into a phone bill. Delivering over Telegram is free, so it now protects against the *recipient's*
limits — the Bot API takes roughly 30 messages per second overall, **one per second per chat**, and
about 20 per minute in a group. Going over does not cost money, it costs `429 Too Many Requests`,
and with a group as the destination that arrives quickly. The default of 10 per minute sits
comfortably under all three ceilings.

The excess is discarded and logged as `LogTipo.LIMITE_ALCANZADO` — renamed from `BUCLE_EVITADO`,
which was named after a protection that no longer exists.

### Tab navigation

The five top-level screens — Panel, Reglas, Logs, Ajustes, Acerca de — are **pages of a
`ViewPager2`**, not destinations of the navigation graph, so each is reachable both by tapping the
Bottom Navigation and by swiping left or right.

`nav_graph.xml` therefore holds only three destinations: `homeFragment`, which hosts the pager,
plus the two rule sub-screens `editarReglaFragment` and `probarReglasFragment`. Both actions hang
off `homeFragment`, because when `ReglasFragment` navigates, the NavController's current
destination is the one hosting the pager and not the rules list. `findNavController()` still
resolves from inside a page: it walks up the parent-fragment chain.

`Pestana` (`presentation/home/Pestana.kt`) is the single source of truth tying together three
things that used to have to agree by hand — pager position, Bottom Navigation menu id and
Fragment. Declaration order **is** page order, and `bottom_nav_menu.xml` must list its items in
that same order or the bar reads differently from the way it swipes.

`setupWithNavController` is gone. The bar belongs to `MainActivity` and the pager to
`HomeFragment`, and they do not share a lifetime — the pager is destroyed the moment you navigate
to the rule editor — so they coordinate through `PestanasViewModel`, an activity-scoped
`StateFlow` of the current tab. Re-selecting the tab that is already active emits nothing, and
that is what keeps the two-way sync (bar → pager, swipe → bar) from feeding back on itself.

Back on any tab other than Panel returns to Panel instead of closing the app; on Panel the gesture
keeps its normal meaning. The NavController back stack used to give that for free by undoing
visited tabs — a pager has no stack, so `HomeFragment` defines the behaviour explicitly.

**The master switch for reception lives in Ajustes**, at the top because everything below it is
moot while it is off. `recepcionActiva` is persisted like any other setting, but turning it off
does more than store a flag: `ControlRecepcion` disables the `SmsReceiver` **component** through
the `PackageManager`. An `if` at the top of `onReceive` would wake the app for every message that
reaches the phone just to drop it, and would force an async DataStore read on the main thread
inside the broadcast's ten-second budget. Disabling the component means the system stops
delivering the broadcast at all — nothing to race, nothing to decide. It stops the **inbound**
side only: whatever was already queued still gets forwarded, because discarding it would lose
messages the app had already accepted. The component state is persisted by the system and
survives reboots, so `SmsToSmsApplication.sincronizarRecepcion()` only matters after a
**reinstall**, where the component reverts to its manifest value and the saved setting has to win
again.

**«Acerca de» is where the donation link lives**, and the only screen that mentions it: a card
with an *Invitar a un café* button that opens `https://paypal.me/jbautistamartin` in the browser
(`URL_DONATIVO`, a private const in `AboutFragment`). The button's heart (`drawable/ic_corazon.xml`)
is the same four-Bézier path and the same gold as the one SilentRunner draws in its tray menu, so
the icon reads the same across the house; it carries `app:iconTint="@null"`, because tinting it with
the theme colour would throw away the only thing that identifies it. The intent is fired and
`ActivityNotFoundException` caught instead of asking the `PackageManager` first — same reasoning as
`OptimizacionBateria`: since Android 11 package visibility can claim there is no browser when there
is one. If nothing resolves, a Snackbar says so rather than leaving a dead button. It is optional
end to end: the app never nags and no feature depends on it.

**The same screen carries a *Código abierto* card** with a *Ver el proyecto en GitHub* button
(`URL_PROYECTO`, beside `URL_DONATIVO` in `AboutFragment`), and it sits immediately above the
donation card because it is what gives the donation its context: the code is published, and the
card says where. Its icon (`drawable/ic_codigo.xml`) is the opposite case to the heart — there is
no colour that identifies it, so it is left to be tinted by the theme. Both buttons go through the
same `abrirEnlace(url)`, and they share the Snackbar for a device with no browser: that string was
renamed from `about_donativo_sin_navegador` to `about_sin_navegador` when the second link arrived,
because it never said anything about donating.

**«Acerca de» also checks for new versions**, over the same `INTERNET` permission the delivery
uses. That permission used to carry a disclaimer here — *the app forwards SMS over the phone's
radio and no message content ever travels over the network* — and **that sentence is now false**.
It was true of SMStoSMS and it was kept written down for exactly as long as it was true. In this
app the SMS body goes out over the network by design, to Telegram's servers, and the privacy
notice in «Acerca de» says so in plain words because whoever installs it needs to know.

What the version check adds on top of that is `data/update/ComprobadorDeActualizaciones` asking
`api.github.com` for the latest release, and `DescargaDeApk` downloading the APK when someone
presses the button. Retrofit and OkHttp are still **not** here — both that request and the Bot API
calls are a dozen lines of `HttpURLConnection` each, with `kotlinx.serialization` for the
responses, which was already present for the rule export.

How it behaves, and why:

- **`MainActivity` asks at startup** and, if there is a newer version, puts a **badge on the
  «Acerca de» tab** — no dialog and no notification. This runs on a phone that forwards SMS with
  nobody looking at it; anything that puts itself in front at startup hides exactly what you came
  to see. The card in «Acerca de» is where it is explained, with the switch that turns the
  automatic question off and a *Buscar ahora* that ignores the daily interval.
- **One question a day, with `If-None-Match`.** The unauthenticated API allows 60 requests an hour
  per IP and a 304 does not count. The timestamp and the ETag live in `EstadoActualizacion`, in the
  **same DataStore** as the settings but with their own keys — deliberately **not** in `AppConfig`:
  that is forwarding configuration, it travels in the backup file, and whether this phone wants
  update notices has nothing to do with how another phone should forward SMS.
- **What was found is remembered, not just when it was asked.** `EstadoActualizacion` also stores
  the tag, the page and the APK of the release that was found, and `comprobar()` returns it from
  there while it still beats the installed version. Without that the notice lived only in the
  process that discovered it: the badge showed on the first launch of the day and was gone on the
  second, because the daily gate answered `SinPreguntar` and nothing remembered the finding. It is
  also the right answer to a **304** — «the latest release has not changed» does not mean it is the
  one installed — and it clears itself, because once the update is installed the comparison stops
  holding and `olvidarNovedad()` runs.
- **Versions are compared as numbers** (`VersionPublicada`), never as text: `"1.10.0" < "1.9.0"`
  alphabetically, which would silently stop the notice at the tenth release. It is the one piece of
  this that can be unit-tested without a network, and `VersionPublicadaTest` covers it.
- **A 404 means there are no releases yet**, not an error. No network means say nothing.
- **Installing is the system's job.** `DownloadManager` downloads the APK into the app's private
  external dir (no storage permission) and `REQUEST_INSTALL_PACKAGES` lets the app open Android's
  own installer dialog — silent installs need a device owner, which this is not. Since Android 8
  the manifest permission is not enough either: the user has to authorise *this* app in Settings,
  and that is checked **before** downloading three megabytes for nothing.
- **The signature is the security model.** Android only upgrades an APK signed with the same key,
  so nobody can slip a forged APK over this one and there are no hashes to verify here. The flip
  side is that it cannot install over a debug build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), which
  is why the card says the update keeps rules, settings and history.

**A horizontally scrollable child inside the pager needs `NestedScrollableHost`**
(`presentation/common/NestedScrollableHost.kt`, the AndroidX sample class adapted to this
codebase). Otherwise the two gestures are the same gesture and the pager wins: the filter carousel
on the Logs screen would stop scrolling, and dragging across it would change screens. The host
arbitrates — the child keeps the gesture while it still has travel in that direction and hands it
over at its edge. `fragment_logs.xml` is the only layout that needs it today; any new horizontal
scroller, carousel or slider placed on a tab needs the same wrapper.

### Key files

| File | Role |
|------|------|
| `domain/transport/EnviadorDeMensajes.kt` | **The seam.** The only thing the pipeline knows about the medium |
| `domain/transport/ResultadoEnvio.kt` | `Enviado` / `ErrorTransitorio` / `ErrorPermanente` — the whole retry policy |
| `di/TransportModule.kt` | The only class that names a concrete transport |
| `data/telegram/TelegramSender.kt` | The `POST` to `sendMessage`; implements the interface |
| `data/telegram/MapeoDeRespuestas.kt` | **Pure** (HTTP code, body) → `ResultadoEnvio`, plus chunking. Tested |
| `data/telegram/DestinoDeTelegram.kt` | What may be written as a destination. Replaces `NormalizadorTelefono` |
| `data/telegram/TokenDeBot.kt` | What can be said about a token without asking Telegram |
| `data/telegram/ComprobadorDelBot.kt` | `getMe`: the Settings button that says *which* bot it is |
| `data/telegram/DescubridorDeChats.kt` | `getUpdates`: the list of chats that wrote to the bot |
| `data/telegram/EstadoDelBot.kt` | Remembers a rejected token, so the Panel can say so |
| `domain/rules/EvaluadorDeReglas.kt` | Matching engine, template resolution, pattern validation |
| `domain/usecase/ProcesarSmsEntranteUseCase.kt` | Rate limit → evaluate → create reenvíos → enqueue |
| `worker/ColaDeEnvios.kt` | The only place that enqueues a dispatch job |
| `worker/DispatchWorker.kt` | Delivers one reenvío; handles retry/failure logic |
| `worker/OrphanRescueWorker.kt` | Rescues stalled SMS and reenvíos every 30 s |
| `data/config/AppConfig.kt` | Bot token, retries, timeout, rate limit |
| `data/rules/ReglasJson.kt` | Versioned rule export/import format |
| `data/local/db/Migraciones.kt` | Hand-written Room migrations, registered by `DatabaseModule` |
| `util/OptimizacionBateria.kt` | Doze exemption: status query + the Settings screen's button |
| `util/InicioAutomatico.kt` | OEM autostart screen (MIUI/EMUI/ColorOS/Funtouch) + the Settings button |
| `util/ControlRecepcion.kt` | Enables/disables the `SmsReceiver` component — the reception kill switch |
| `data/config/ConfigBackup.kt` | Export/import of the whole configuration: settings **and** rules |
| `data/update/ComprobadorDeActualizaciones.kt` | Asks GitHub for the latest release and decides whether it beats the installed one |
| `data/update/VersionPublicada.kt` | SemVer comparison — the only part of the check with unit tests |
| `data/update/DescargaDeApk.kt` | `DownloadManager` download plus the system installer intent |
| `data/update/EstadoActualizacion.kt` | Notice switch, last check and ETag, in their own DataStore keys |

### Delivery: what the transport owes the worker

`EnviadorDeMensajes.enviar()` **waits for the outcome**, and that requirement outlived two
transports. With `SmsManager` it meant suspending until the per-part `sentIntent` broadcast
arrived, because the send call returned before the message left the device; with Telegram it means
waiting for the HTTP response. The reason is the same either way: if the call returned before the
outcome was known, every delivery would look successful and real failures would vanish.

**No `parse_mode`. This is the mistake that would be easiest to make.** With `Markdown` or `HTML`,
any SMS containing a `_`, a `*`, a `[` or a `<` — a bank's link, an emoticon — makes Telegram
answer `400 Bad Request: can't parse entities`, and the reenvío is closed as a permanent failure:
the message **is not delivered** because someone wanted it in bold. The SMS body is third-party
content and is never interpreted, which is the same rule the evaluator already applies by
substituting `{mensaje}` last.

**Error mapping** lives in `MapeoDeRespuestas.interpretarEnvio()`, deliberately separated from the
code that makes the request so it can be unit-tested without a network:

| Response | Result |
|----------|--------|
| `200` with `ok:true` | `Enviado` |
| `429` | `ErrorTransitorio`, **carrying `parameters.retry_after`** |
| `5xx`, timeout, `IOException` | `ErrorTransitorio` |
| `400` (chat not found, text empty or too long) | `ErrorPermanente` |
| `401` / `404` | `ErrorPermanente` **and a credential failure** |
| `403` (bot blocked, or kicked from the group) | `ErrorPermanente` |
| anything else | `ErrorTransitorio`, out of caution |

Two of those deserve their own note:

- **A `404` is a bad token, not a missing chat.** The token sits *inside the Bot API URL*, so the
  whole path depends on it and a "does not exist" there means the token does not exist.
  `esFalloDeCredencial()` groups 401 and 404, and only those two light up the Panel warning via
  `EstadoDelBot` — a 400 or a 403 is a problem with *that* destination, and pointing at the token
  would send someone to look in the wrong place.
- **`retry_after` is honoured by sleeping before returning `retry()`.** WorkManager counts its
  backoff **from when the worker finishes**, so sleeping inside the worker is the only way it has
  to delay the next attempt. Capped at 120 s: a worker has about ten minutes before the system
  stops it, and blocking one for a quarter of an hour would trade one problem for a worse one.

**Over 4.096 characters** the text is split rather than truncated (`MapeoDeRespuestas.trocear`),
cutting at the last newline and failing over to the last space. It practically never happens — it
takes a long concatenated SMS plus a generous template — but truncating would lose content
silently, and letting Telegram answer `400` would turn a long message into a failed delivery. If
one chunk fails and an earlier one succeeded, the whole reenvío is retried, accepting a possible
duplicate: the same reasoning SMS multipart used.

### Idempotency

Client-side, at two levels:

- `ProcesarSmsEntranteUseCase.procesar()` checks whether the SMS already has reenvíos before
  creating any. That is what makes the orphan rescue safe to run on a half-processed SMS.
- `DispatchWorker` checks `reenvio.estado` at the start of every attempt and returns success
  without touching the transport if it is already `ENVIADO`.

`ColaDeEnvios` uses `ExistingWorkPolicy.KEEP`, so a reenvío never gets two concurrent jobs.

### Room database

Four tables, schema version **1** (see the KDoc on `SmsDatabase`: it restarted at 1 for the
second time, and for the same reason):

- `sms` — one row per received SMS. Only what arrived plus `estado`
  (`PENDIENTE`/`PROCESADO`/`SIN_REGLA`/`DESCARTADO`) and `motivo_descarte`.
- `reglas` — ordered rules, `ignorar_mayusculas` included from the start. `destino` holds a
  Telegram chat, and `plantilla` has **no** SQL default on purpose: it was written in two places
  and the two would have drifted (see the KDoc on `ReglaEntity`).
- `reenvios` — the dispatch unit: one row per destination chat, with its own `estado`, `intentos`
  and `ultimo_error`. FK to `sms` is CASCADE; FK to `reglas` is SET NULL, and `nombre_regla`
  is denormalized so history survives rule deletion. There is no `partes` column: that counted
  the fragments of a concatenated SMS.
- `log_entries` — audit log. Types: `SMS_RECIBIDO`, `REGLA_APLICADA`, `SMS_REENVIADO`,
  `SIN_REGLA`, `LIMITE_ALCANZADO`, `ERROR`, `SISTEMA`.

WAL mode enabled. Schemas are exported to `app/schemas/` and versioned. No
`fallbackToDestructiveMigration` — migrations must be explicit.

> **`Migraciones.TODAS` is empty, and that is the last time it can be.** The schema was collapsed
> into a clean v1 because no device has this database: a new `applicationId` is a different app
> with its own data directory. The moment the app is installed on a phone with rules in it the
> schema stops being editable in place — changing v1 without migrating makes Room abort when
> opening the database — so from then on every change bumps the version and writes its `Migration`
> in `data/local/db/Migraciones.kt`, which `DatabaseModule` registers.

## Naming conventions

The codebase mixes languages deliberately, and new code must follow the same split:

- **Members** — functions, properties, parameters, Room column names, enum constants — are in
  **Spanish**: `guardar`, `observarTodos`, `obtenerPendientes`, `telefono`, `fecha_recepcion`,
  `SMS_REENVIADO`. The only English members are framework overrides where there is no choice
  (`onCreate`, `doWork`, `onBindViewHolder`, `provideDatabase`).
- **Type names** carry an **English architectural suffix** on a Spanish or English noun:
  `SmsRepository`, `ReglaDao`, `ReenvioEntity`, `SettingsFragment`, `ReglasViewModel`.
  Pure-domain types may be fully Spanish: `LogTipo`, `EstadoSms`, `EvaluadorDeReglas`.
- **Comments and KDoc** are in **Spanish**.
- **Use-case class names follow the same rule:** a Spanish verb phrase plus the `UseCase`
  suffix — `ProcesarSmsEntranteUseCase`, `ObtenerListaSmsUseCase`. The English
  `GetSmsListUseCase` inherited from SMSGateway was renamed for consistency.

This split was verified against the pre-migration code, not assumed: of the 89 functions the
original project declared with a real choice of language, **89 were in Spanish and none in
English**, and all 13 Room columns were Spanish too. The only English members were framework
overrides. Do not "restore" English names — there were none to restore.

## Device setup (dedicated production phone)

These steps must be completed once on the dedicated Android device that receives the SMS and
delivers them to Telegram. Skip any step already done.

### Why battery optimization must be disabled

Android's **Doze mode** suspends background processes when the screen is off for more than a few
minutes. WorkManager respects Doze and may delay `DispatchWorker` by hours. Exempting the app
ensures every SMS is delivered within seconds of arrival, regardless of screen or battery state.

### Option A — in the app (no USB, no manufacturer-specific path)

**Ajustes** → **Fiabilidad en segundo plano**. The card states whether the exemption is already
granted and the button opens the system screen that grants it. It tries three destinations in
order — the one-tap exemption dialog, the full battery-optimization list, the app's Settings
entry — because no manufacturer guarantees the first. This is the route to prefer on a phone
you are holding; the status refreshes when you come back to the screen.

### Option B — ADB command (fastest when the phone is already plugged in)

```bash
adb shell dumpsys deviceidle whitelist +com.capicua.smstotelegram
```

Run once after install. To verify the app was added to the whitelist:

```bash
adb shell dumpsys deviceidle whitelist
# Expected output includes a line with: com.capicua.smstotelegram
```

### Option C — On-device UI, by hand

Only needed if the in-app button cannot open anything. The exact path varies by manufacturer;
use the closest match:

**Stock Android / Pixel**
1. **Ajustes** → **Aplicaciones** → **SMStoTelegram**
2. **Batería** → seleccionar **Sin restricciones**

**Samsung (One UI)**
1. **Ajustes** → **Mantenimiento del dispositivo** → **Batería**
2. **Límites de uso en segundo plano** → **Aplicaciones sin suspender** → **Añadir** → SMStoTelegram

**Xiaomi / MIUI / HyperOS**
1. **Ajustes** → **Aplicaciones** → **Administrar aplicaciones** → **SMStoTelegram**
2. **Ahorro de batería** → **Sin restricciones**
3. Volver a la ficha de la app → activar **Inicio automático**

**Huawei / EMUI**
1. **Ajustes** → **Aplicaciones** → **SMStoTelegram** → **Consumo de batería**
2. Desactivar **Gestión inteligente de energía** y seleccionar **Sin restricciones**

**OnePlus / OxygenOS / ColorOS**
1. **Ajustes** → **Aplicaciones** → **SMStoTelegram** → **Batería**
2. **Optimización de batería** → **No optimizar**

> **Nota:** en cualquier fabricante también puedes buscar "Optimización de batería" directamente
> en el buscador de Ajustes, seleccionar **Todas las aplicaciones** y cambiar SMStoTelegram
> a **No optimizar**.

### Other recommended settings (all manufacturers)

| Setting | Where | Value |
|---------|-------|-------|
| Inicio automático | Ajustes → Aplicaciones → SMStoTelegram | **Activado** |
| Ejecutar en segundo plano | Ajustes → Aplicaciones → SMStoTelegram → Batería | **Permitido** |
| Optimización de batería | Ajustes → Batería → Optimización → SMStoTelegram | **No optimizar** |

---

## Important notes

- `libs.versions.toml` is the single source of truth for all dependency versions. Retrofit and
  OkHttp were removed when the HTTP layer went away; do not re-add them. `navigation-ui` went
  the same way when the five tabs stopped being navigation destinations: nothing calls
  `setupWithNavController` any more.
  `kotlinx.serialization` **is** used, by the rule export/import format.
- **`SEND_SMS` is gone, and so is `READ_PHONE_STATE`.** The first went with the SMS transport —
  this app never sends an SMS — and the second with the SIM selector that chose which card to send
  from. Do not re-add either: nothing needs them, and `SEND_SMS` is a restricted permission.
  That said, **this does not open Google Play**: `RECEIVE_SMS` and `READ_SMS` belong to the same
  restricted group and still require an approved declaration. APK distribution is unaffected.
- **Nothing starting after a reboot is usually not a bug in `BootReceiver`.** The SMS receiver is
  declared in the manifest, so the system starts it on its own — the receiver only reschedules
  the background workers. Two things outside the app's reach break that: the OEM **autostart**
  list (MIUI, HyperOS, EMUI, ColorOS and Funtouch ship it empty, and the permission can neither
  be granted nor queried from code — `InicioAutomatico` opens that screen and Ajustes explains
  it), and Android's **stopped state**: a freshly installed app that nobody has opened yet
  receives no broadcasts at all, SMS included, until someone launches it once. After installing
  the APK, open the app once.
- **Rules are configuration, so their backup lives in Ajustes, not in the Reglas tab.** One file
  carries both blocks (`ConfigBackup` + format version **4**): settings are **replaced** on import,
  rules are **appended** — importing never deletes, because a mistaken import that emptied the
  list would leave the phone delivering nothing.
- **The bot token does not travel in the backup file**, and `ConfiguracionJson.aDominio()` takes
  the current token as an argument so that importing does not wipe it. That argument is not
  decoration: settings are *replaced* on import, so without it a file that has no token would
  leave `tokenBot` empty and the app silent without anyone touching it. It inherits the role
  `simActual` had when the transport was SMS, for a worse reason — the file is plain JSON and the
  token is permission to write as the bot.
- **Format 4 refuses to import files from format 3 or earlier**, and it is the first time an
  import in this project breaks on purpose. A format-3 file written by SMStoSMS has *exactly the
  same shape*: no field changed name or type. What changed is what `destino` means — a phone
  number there, a `chat_id` here. It would import without a single parse error and leave a rule
  set whose destinations do not exist, failing one by one when an SMS arrived, with nothing
  pointing back at the imported file.
- **«Acerca de» carries a liability disclaimer** next to the privacy notice: the app ships as-is
  and whoever installs and configures it is solely responsible for its use and for the law that
  applies to them. In an app that sends other people's SMS to a third-party service, that is not
  boilerplate — and the privacy notice is the single most important piece of text in the project.
  It states that the SMS body reaches Telegram's servers, that the message stays in the
  destination chat (and on the device of everyone in it, if that chat is a group or a channel),
  and that the original sender neither knows nor consented. Do not soften it.
- Debug variant uses `applicationId = com.capicua.smstotelegram.debug`, so it can coexist with
  the release build on the same device. They do **not** share a database.
- **Anything scoped per-install must derive from `applicationId`, never be typed out.** The
  FileProvider authority was hardcoded as `com.capicua.smstotelegram.provider`, so both variants
  declared the *same* authority — and an authority is unique device-wide, so Android refused to
  have both installed (`INSTALL_FAILED_CONFLICTING_PROVIDER`), defeating the very point of the
  `applicationIdSuffix`. It now reads `${applicationId}.provider` in the manifest and
  `BuildConfig.APPLICATION_ID + ".provider"` in `Constants`; the two have to stay in step. The
  symptom is misleading — it surfaces at install time, never at build time.
- **Edge-to-edge is mandatory here.** With `targetSdk 35`, Android 15 draws the window under the
  status and navigation bars and ignores `fitsSystemWindows`; there is no opt-out. `MainActivity`
  distributes the insets (`systemBars or displayCutout`) and is the only place that does, so any
  new top-level container inherits it. Without it the camera cutout covered each screen's header
  and the system navigation bar sat **on top of** the Bottom Navigation, which made the screens
  that are not in that bar impossible to leave. `ProbarReglasFragment` and `EditarReglaFragment`
  also carry their own back button, since neither has an entry in the bottom bar.
- **Known toolchain item:** the wrapper is Gradle 9.3.0 while AGP is 8.5.2. It builds, but warns
  about APIs that disappear in Gradle 10 — among them `android.applicationVariants.all`, the
  block that renames the release APK. Before moving to Gradle 10, either raise AGP or pin the
  wrapper to Gradle 8.x. Nothing is broken today, so this was deliberately left alone.
- **`INTERNET` is now load-bearing.** It is how messages are delivered, not an extra for the
  version check. The sentence that used to live here — *this app not needing the network is part
  of what it is* — belonged to SMStoSMS and no longer applies.
- **Six unit test suites, 100 tests**, all pure Kotlin with no Android dependencies:
  `EvaluadorDeReglasTest` (43), `MapeoDeRespuestasTest` (18), `DestinoDeTelegramTest` (10),
  `TokenDeBotTest` (6), `ReglasJsonTest` (13) and `VersionPublicadaTest` (10). Note the count went
  *down* from SMStoSMS: `NormalizadorTelefonoTest` left with the class it tested.
  `MapeoDeRespuestas` is kept separate from the code that makes the HTTP request **so that it can
  be tested without a network** — it is the piece it matters most to get right, and the only one
  testable at all without one. Keep that separation.
- **`MASTERPLAN_TELEGRAM.md` records the move from SMStoSMS to Telegram** phase by phase, with the
  decisions taken, the ones that were reshuffled mid-flight and why. `MASTERPLAN.md` records the
  earlier migration from SMSGateway. Read both before changing anything structural: between them
  they explain why several things that look like oversights are deliberate.
