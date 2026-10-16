# MASTERPLAN — SMStoTelegram → SMStoWhats

> Tercera mudanza del mismo esqueleto. La primera (`MASTERPLAN-SMS.md`) trajo la tubería desde
> SMSGateway; la segunda (`MASTERPLAN-TELEGRAM.md`) cambió el medio de salida de `SmsManager` a
> un bot de Telegram sin tocar la tubería. Esta cambia el medio otra vez, y es la primera en la
> que **el medio nuevo no puede hacer lo que hacía el anterior**.

---

## 1. Idea central

Recibir SMS en un teléfono dedicado y entregarlos en **WhatsApp**, por reglas con expresiones
regulares sobre el remitente y el texto. Todo lo que hay por encima de `EnviadorDeMensajes` se
queda como está.

Y ahí acaba el parecido con la fase anterior, porque **WhatsApp no tiene un Bot API**. No existe
nada equivalente a lo que hace hoy esta aplicación: un bot gratuito que escribe a quien quiera,
cuando quiera, el texto que quiera. Lo que hay es la **Cloud API de Meta**, que es una plataforma
de mensajería de empresa a cliente, con una forma muy concreta de permitir y de cobrar lo que se
manda. El trabajo de esta migración no es técnico: es encajar un reenviador de SMS dentro de esa
forma.

### La restricción que lo gobierna todo

La Cloud API distingue dos clases de mensaje, y la diferencia **no** es de formato:

| | Mensaje de servicio (texto libre) | Mensaje de plantilla |
|---|---|---|
| Cuándo se puede mandar | Solo dentro de una **ventana de 24 h** que abre el destinatario escribiendo al número | **Siempre**, sin contacto previo |
| Contenido | Texto libre, hasta **4.096** caracteres | Cuerpo aprobado por Meta, hasta **1.024** caracteres renderizados |
| Saltos de línea en el contenido variable | Sí | **No** (ni tabuladores, ni más de 4 espacios seguidos) |
| Aprobación previa | No | Sí, por plantilla y por idioma |
| Coste | Los primeros **1.000 al mes por número** son gratis; desde el 1 oct 2026 el resto se factura | Se factura **cada mensaje**, a la tarifa de su categoría |

Un reenviador de SMS es, por definición, un emisor **no solicitado**: el SMS llega cuando llega y
hay que entregarlo entonces, no cuando al destinatario le apetezca abrir una ventana. Por tanto:

> **Las plantillas no son el plan B de esta aplicación: son el camino principal.** El texto libre
> es la optimización que se aplica cuando resulta que la ventana está abierta.

Eso invierte lo que uno supondría al empezar, y es la conclusión de la que cuelgan las fases 3, 6
y 7. Conviene leerla antes de opinar que la fase 6 es scope de más.

---

## 2. Lo que hay que tener antes de escribir una línea de código

Nada de esto se puede probar sin una cuenta real, y montarla lleva más tiempo que la fase 3.
Va primero y va fuera del repositorio.

1. **App en Meta for Developers** con el producto WhatsApp añadido.
2. **Número de teléfono dedicado.** Un número en la Cloud API **no puede estar a la vez en la app
   de WhatsApp** (ni en WhatsApp Business), salvo por el flujo de *Coexistence*. Para empezar vale
   el **número de prueba** que Meta crea solo: es gratis, no exige verificación de empresa y
   admite hasta **5 destinatarios en lista blanca**, que es exactamente el tamaño de este caso de
   uso. El número de la SIM que recibe los SMS **sí** puede ser el número de la Cloud API, siempre
   que no tenga WhatsApp instalado y registrado — pero no se puede reenviar a sí mismo (error
   `131021`).
3. **Token permanente de usuario de sistema.** El token que la consola enseña en la pestaña de
   WhatsApp caduca en unas 24 h y no sirve para esto. El que sirve se genera creando un *System
   User* en el Business Manager, dándole acceso a la app y a la WABA, y pidiendo un token con
   caducidad **Nunca**. No es inmortal: se cae si se revocan los permisos o si se regenera.
4. **El `Phone Number ID`**, que es lo que va en la URL. No es el número de teléfono: es un id
   numérico que da la consola. Aquí hacen falta **dos credenciales** donde Telegram pedía una, y
   eso se nota en la pantalla de Ajustes.
5. **Una plantilla creada y aprobada**, con el contrato de la fase 6. Las de categoría *Utility*
   suelen aprobarse en menos de media hora, pero Meta puede **reclasificarla a Marketing** por su
   cuenta, antes o después de aprobarla, y entonces cuesta más por mensaje.

---

## 3. Decisiones cerradas

- **Transporte: Cloud API oficial de Meta.** Descartadas las pasarelas no oficiales
  (UltraMsg, Green API, Wassenger, CallMeBot) y la automatización de la app de WhatsApp por
  `AccessibilityService`. Las dos harían exactamente lo que hace hoy Telegram y con menos código,
  y las dos violan los Términos de WhatsApp: la primera mete además a un tercero más en el camino
  del cuerpo de todos los SMS, lo que choca de frente con el aviso de privacidad del proyecto; la
  segunda se rompe cada vez que WhatsApp rediseña su pantalla.
- **El destino de una regla vuelve a ser un número de teléfono**, en formato internacional.
- **Solo personas individuales. Nunca grupos.** Es una decisión tomada, no una limitación
  encontrada, y simplifica tres cosas: `DestinoDeWhatsApp` valida **una** forma —E.164— y no tiene
  que admitir ni distinguir ids de grupo; el `recipient_type` del JSON es siempre `individual`, sin
  ramificación; y desaparece el requisito de Cuenta Oficial de Empresa que arrastraban los grupos
  de la Cloud API. También quita presión al número de prueba: cinco destinatarios en lista blanca
  son cinco personas, que es de sobra.
- **`DescubridorDeChats` desaparece y no se sustituye.** Existía porque nadie se sabe su
  `chat_id`. Todo el mundo se sabe su número de teléfono. No hay `getUpdates` en la Cloud API y
  enumerar quién ha escrito exigiría levantar un webhook público; no hace falta.
- **Las dos guardias antibucle siguen borradas.** Ahora el destino y el remitente **sí** son
  identidades comparables —los dos son números de teléfono—, así que el argumento de
  `MASTERPLAN-TELEGRAM.md` ya no vale y hay que dar otro: un mensaje de WhatsApp no vuelve nunca
  como SMS, así que no hay bucle que cerrar. Lo único que sí existe es que Meta rechaza mandarse
  a sí mismo (`131021`), y eso se mapea como error permanente con su motivo. `NormalizadorTelefono`
  **no vuelve**: lo que hace falta es validar forma E.164, no comparar.
- **Sigue sin haber `parse_mode`.** WhatsApp interpreta `*negrita*` y `_cursiva_` en el cuerpo sin
  pedir permiso, así que el riesgo cambia de forma: no hay un `400 can't parse entities` que
  tumbe el reenvío, pero un SMS con asteriscos llegará con medio texto en negrita. Se acepta: es
  cosmético, y la alternativa —escapar el cuerpo— es interpretar contenido de un tercero, que es
  justo lo que este proyecto no hace.
- **Esquema de Room a v1 limpio, por tercera vez**, y por el mismo motivo de siempre: el
  `applicationId` nuevo es otra aplicación con su propio directorio de datos, así que ningún
  dispositivo tiene esta base. `Migraciones.TODAS` arranca vacío y deja de poder estarlo en
  cuanto el APK se instale en un teléfono con reglas dentro.
- **Formato de copia de seguridad: 5, y rechaza el 4.** Es la segunda vez que una importación se
  rompe a propósito, y por la misma razón exacta que la primera: un fichero del 4 tiene la misma
  forma, campo por campo, pero su `destino` es un `chat_id` y aquí es un teléfono. Importaría sin
  un solo error de parseo y dejaría un juego de reglas cuyos destinos no existen.

---

## 4. Lo que no se toca

Esto es el premio de la juntura, y conviene tenerlo delante para no «arreglar» nada de aquí:

`SmsReceiver` · `SmsIngestionService` · el outbox de Room · `EvaluadorDeReglas` ·
`ProcesarSmsEntranteUseCase` · `ColaDeEnvios` · `DispatchWorker` · `OrphanRescueWorker` ·
`HealthMonitorWorker` · `BootReceiver` · la idempotencia en sus dos niveles · la política de
reintentos · el historial · el registro · las cinco pestañas y su `ViewPager2` · `Pestana` ·
`NestedScrollableHost` · el comprobador de actualizaciones completo · `OptimizacionBateria` ·
`InicioAutomatico` · `ControlRecepcion` · los marcadores `{mensaje}` `{telefono}` `{fecha}` y el
orden en que se sustituyen.

**`ResultadoEnvio` tampoco se toca**, y es la pieza que mejor ha envejecido: `Enviado` /
`ErrorTransitorio(motivo, esperaSegundos)` / `ErrorPermanente(motivo)` encaja sobre los códigos de
la Cloud API sin añadir ni quitar nada. El `esperaSegundos` que se escribió para el `retry_after`
de Telegram sirve igual para un `130429`, con la diferencia de que Meta **no** dice cuánto
esperar: ahí irá `null`, que ya significaba «aplica el backoff de siempre».

**`EnviadorDeMensajes` tampoco cambia de firma.** Hay una tentación de añadirle algo para el modo
de entrega, y no hace falta: el modo es configuración del transporte y se lee dentro de la
implementación. `limiteCaracteres` sí pasa a ser **efectivo** —4.096 en texto libre, el presupuesto
de la plantilla en modo plantilla— pero eso es una línea en la implementación, no en el interfaz.

---

## 5. Fases

### Fase 1 — Renombrado integral y esquema v1 limpio

- `com.capicua.smstotelegram` → `com.capicua.smstowhats` (namespace, `applicationId`, árbol de
  paquetes, los 90 ficheros `.kt`).
- `SmsToTelegramApplication` → `SmsToWhatsApplication`.
- `app_name` → `SMStoWhats`; nombre del APK → `smstowhats-<versionName>.apk`.
- `URL_PROYECTO` y `ComprobadorDeActualizaciones.URL_API` → repositorio nuevo. **Ojo:** mientras
  el repositorio no exista, la API de GitHub contesta 404, que el comprobador ya trata como «no
  hay releases todavía» y no como error. No rompe nada durante el desarrollo.
- `versionCode = 1`, `versionName = "1.0.0"`; `CHANGELOG.md` a cero.
- La autoridad del `FileProvider` sigue derivándose de `applicationId` en los dos sitios
  (manifest y `Constants`). No volver a escribirla a mano: el síntoma aparece al instalar, nunca
  al compilar.
- Esquema de Room colapsado a v1, `app/schemas/` regenerado, `Migraciones.TODAS` vacío.

**Verificación:** `./gradlew assembleDebug test` y los 66 tests que sobreviven en verde (ver
fase 8).

### Fase 2 — Comprobar que la juntura aguanta

No es una fase de código, es una de lectura, y existe porque es la que evita las tres veces que
esto se podría hacer mal. Recorrer todo lo que está por encima de `EnviadorDeMensajes` y
confirmar que no nombra el medio. Lo que aparezca nombrándolo —y van a ser comentarios y textos,
no código— se anota para la fase 8.

Andamio: dejar un `TransporteSinConfigurar` que devuelve siempre `ErrorPermanente("transporte sin
configurar")`, como en la fase 2 de la migración anterior. Permite que la aplicación compile,
arranque y se navegue entera mientras la fase 3 se escribe.

### Fase 3 — Cliente de la Cloud API, texto libre

`data/telegram/` → `data/whatsapp/`.

| Sale | Entra | Qué cambia de verdad |
|---|---|---|
| `TelegramSender` | `WhatsAppSender` | `POST https://graph.facebook.com/v22.0/<phone_number_id>/messages`, con `Authorization: Bearer <token>`. El token va en **cabecera**, no en la URL — así que, a diferencia de Telegram, la URL sí se puede registrar |
| `MapeoDeRespuestas` | `MapeoDeRespuestas` | Reescrito: ya **no basta el código HTTP** |
| `DestinoDeTelegram` | `DestinoDeWhatsApp` | Un teléfono E.164. `pareceValido` y `pareceTelefono` se funden en una |
| `TokenDeBot` | `TokenDeAcceso` | Un token de Graph no tiene forma `id:cadena`. Validación deliberadamente laxa |
| — | `IdNumeroTelefono` | **Credencial nueva.** Numérica, 15-16 dígitos |

Lo importante de la fase es el mapeo, y lo importante del mapeo es que **la clasificación vive en
`error.code`, no en el estado HTTP**. Un `400` de la Cloud API puede ser cuatro averías
distintas, y tratarlas igual es lo que convierte un problema de configuración en cuatrocientas
líneas idénticas de registro:

| `error.code` | HTTP | Resultado | Por qué |
|---|---|---|---|
| — (`200` con `messages[].id`) | 200 | `Enviado` | Meta tiene el mensaje |
| `131047` | 400 | **Caso especial** | La ventana de 24 h está cerrada. No es un fallo: es «esto hay que mandarlo por plantilla». Lo atiende la fase 6 |
| `131026` | 400 | `ErrorPermanente` | El destino no es usuario de WhatsApp |
| `131021` | 400 | `ErrorPermanente` | Emisor y destinatario son el mismo número |
| `132000`, `132007`, `132012` | 400 | `ErrorPermanente` | La plantilla y sus parámetros no cuadran. Es configuración |
| `0`, `3`, `190` | 401/403 | `ErrorPermanente` **y fallo de credencial** | Token caducado, revocado o sin permisos |
| `130429`, `80007`, `131048`, `131056` | 429 | `ErrorTransitorio`, sin espera sugerida | Límite de caudal. Meta **no** manda `retry_after` |
| `131000`, `133004` | 5xx | `ErrorTransitorio` | Avería del lado de Meta |
| cualquier otro | — | `ErrorTransitorio` | Por prudencia, igual que antes |

`esFalloDeCredencial()` pasa a mirar el código, no el HTTP: con Telegram eran el 401 y el 404
porque el token iba en la ruta; aquí el token va en la cabecera y un 404 es, otra vez, un
`phone_number_id` mal escrito — que **también** es un fallo de credencial y también tiene que
encender el aviso del Panel.

`trocear` y `LIMITE_CARACTERES = 4096` se quedan **idénticos**: el límite del `text.body` de la
Cloud API es el mismo número que el de Telegram. El reparto de plazos de socket (`Plazos`) y el
razonamiento sobre `withTimeoutOrNull` se quedan tal cual: el problema que resuelven —abandonar
una petición que todavía puede entregarse, y duplicar el mensaje— es idéntico.

`TransportModule`: una línea.

### Fase 4 — Las dos credenciales, bien tratadas

- **Ajustes** pasa de un campo a dos: *Token de acceso* y *Id del número*. Los dos son
  credenciales: no se registran, se enmascaran y no viajan en la copia.
- `ComprobadorDelBot` → `ComprobadorDeCuenta`. No hay `getMe`; el equivalente es
  `GET /<phone_number_id>?fields=verified_name,display_phone_number,quality_rating`. Devuelve
  **de qué número se trata** —el nombre verificado y el número en formato legible—, que es la
  misma tranquilidad que daba enseñar el `@usuario` del bot, y de paso el `quality_rating`
  (`GREEN`/`YELLOW`/`RED`), que es información que Telegram no tenía: avisa de que Meta está a
  punto de limitar el número por denuncias.
- `EstadoDelBot` → `EstadoDeLaCuenta`, con el mismo papel y el mismo razonamiento sobre por qué
  se persiste (esta aplicación no está «abierta»: cada SMS despierta el proceso y lo deja morir).
- Se borran `DescubridorDeChats`, `ChatDescubierto`, `ResultadoDescubrimiento`, su botón en el
  editor de reglas y sus seis cadenas.

### Fase 5 — Reglas: el destino vuelve a ser un teléfono

- `EditarReglaViewModel` y `ProbarReglasViewModel` pasan a validar con `DestinoDeWhatsApp`.
- El mensaje de error se invierte: antes avisaba de que un teléfono no es un chat; ahora avisa de
  que **falta el prefijo internacional**, que va a ser el error real. Un `600112233` sin `+34` es
  el `chat_id` sin signo negativo de esta versión.
- Textos de ayuda y la pantalla de prueba.
- Copia de seguridad: formato **5**, rechazando el 4 con un motivo que diga *por qué* y no solo
  *que no*.
- `ConfiguracionJson.aDominio()` pasa a recibir **dos** credenciales actuales en lugar de una,
  por el mismo motivo por el que recibía la primera: los ajustes se **sustituyen** al importar, y
  sin eso un fichero sin credenciales dejaría la aplicación muda sin que nadie tocara nada.

### Fase 6 — Plantillas y modo de entrega

La fase que no existía en la migración anterior.

**El contrato: una sola variable.** El operador crea en el Business Manager una plantilla de
categoría *Utility* cuyo cuerpo es exactamente:

```
Reenvío de SMS

{{1}}

— SMStoWhats
```

Tres propiedades de ese cuerpo, y las tres son a propósito:

1. **Los saltos de línea están en la plantilla, no en la variable.** La plantilla se aprueba una
   vez con su maquetación dentro; lo que no puede llevar saltos de línea es el **valor** que se
   pasa en `{{1}}`.
2. **No empieza ni acaba con la variable**, que Meta no admite.
3. **Una variable y no tres.** La alternativa —`{{1}}` remitente, `{{2}}` fecha, `{{3}}` texto—
   daría mejor maquetación y rompería algo que importa más: `EvaluadorDeReglas` seguiría siendo la
   única fuente del texto en modo texto libre, pero en modo plantilla habría una **segunda** vía
   de composición que ignora la `plantilla` de la regla. Con una variable, el texto lo compone
   siempre el evaluador, en los dos modos, y la `plantilla` de cada regla sigue significando lo
   mismo. Se paga en estética y se cobra en no tener dos caminos.

Dos funciones puras nuevas, en el mismo sitio y por el mismo motivo que `MapeoDeRespuestas` —son
lo que se puede probar sin red:

- `aplanar(texto)`: `\n` → ` · `, tabuladores a espacio, colapsa los seguidos. Es lo que convierte
  un texto válido para WhatsApp en un valor válido para una variable de plantilla.
- `presupuesto(plantilla)`: 1.024 menos el texto fijo del cuerpo. Lo que sobra se trocea en
  mensajes de plantilla sucesivos, con el criterio de `trocear`, que ya existe.

**El modo de entrega**, en Ajustes, con tres valores:

| Modo | Qué hace |
|---|---|
| `AUTOMATICO` (por defecto, si hay plantilla configurada) | Intenta texto libre; si Meta contesta `131047`, reenvía **el mismo intento** como plantilla |
| `SOLO_TEXTO_LIBRE` | Nunca gasta una plantilla. Un `131047` es `ErrorPermanente` con un motivo que explica qué hacer |
| `SOLO_PLANTILLA` | No prueba el texto libre. Útil cuando se sabe que la ventana nunca va a estar abierta |

El salto a plantilla en `AUTOMATICO` va **dentro del mismo intento del worker**, no como
reintento: un reintento volvería a encontrarse la ventana cerrada y gastaría los diez intentos
para acabar en el mismo sitio. Es la primera vez que el transporte hace dos peticiones para un
reenvío, y es la razón de que `limiteCaracteres` tenga que ser el efectivo del modo configurado:
la pantalla de reglas avisa mientras se escribe la plantilla, que es el único momento en que
alguien puede hacer algo al respecto.

### Fase 7 — Límites y coste

**El tope por minuto cambia de significado por tercera vez.** Con SMS era un cortafuegos
económico; con Telegram pasó a proteger los límites de quien recibe, porque entregar era gratis.
Con la Cloud API vuelve a ser las dos cosas a la vez, y la económica aprieta más que nunca:

- El caudal de la Cloud API es holgado (del orden de 80 mensajes por segundo), así que por ahí
  no hay problema; lo que sí existe es el `131056`, demasiados mensajes al mismo destinatario en
  poco tiempo.
- Pero **los mensajes de servicio gratis son 1.000 al mes y por número**, y cada mensaje de
  plantilla se factura siempre. Mil al mes son **33 al día**. El valor por defecto de hoy —10 por
  minuto— son 14.400 al día: tres órdenes de magnitud por encima del primer techo que se va a
  encontrar.
- Propuesta: bajar el defecto y añadir un **tope mensual** explícito, que es el límite que de
  verdad importa.

`ContadorDeMensajes`: cuenta por mes natural, separando servicio de plantilla, en el mismo
DataStore que los ajustes pero con sus propias claves —igual que `EstadoActualizacion` y
`EstadoDeLaCuenta`, y por el mismo motivo: es un hecho sobre *este* teléfono y no debe viajar en
la copia. El Panel lo enseña. Sin esto, la primera noticia de que se ha pasado el tope es la
factura.

### Fase 8 — Documentación y privacidad

- `CLAUDE.md` reescrito. Hay afirmaciones que hoy son verdad y dejan de serlo: «el Bot API admite
  un mensaje por segundo y chat», «entregar por Telegram es gratis». Y una que sigue siéndolo y
  conviene repetir: el único sitio que nombra un transporte concreto es `TransportModule`.
- `README.md`, `docs/ARCHITECTURE.md`.
- **El aviso de privacidad de «Acerca de» empeora, y tiene que decirlo.** Hoy dice que el cuerpo
  del SMS llega a los servidores de Telegram. Ahora hay tres cosas más que son verdad y no lo
  eran: el destinatario **es Meta**; el reenvío sale de una **cuenta de empresa registrada a
  nombre de quien instala la aplicación**, con lo que eso implica en responsabilidad; y en modo
  plantilla se entrega a un número **sin contacto previo y sin que haya pedido nada**, lo cual,
  además de ser lo que hace falta para que la aplicación funcione, es exactamente lo que la
  política de Meta llama mensajería no solicitada. El aviso de responsabilidad que ya existe
  sostiene esto, pero hay que escribirlo: en una aplicación que manda los SMS de otros, esto no
  es texto de relleno.
- La cuenta de tests: de 100 hoy a ~114. Sobreviven intactos `EvaluadorDeReglasTest` (43),
  `ReglasJsonTest` (13) y `VersionPublicadaTest` (10) = **66**. Se reescriben
  `MapeoDeRespuestasTest` (~20, ahora sobre `error.code`), `DestinoDeWhatsAppTest` (~12) y
  `TokenDeAccesoTest` (~6). Se añade `PlantillaDeWhatsAppTest` (~10) para `aplanar` y
  `presupuesto`. Se va `DestinoDeTelegramTest`.

### Fase 9 — Dispositivo real y release 1.0.0

1. Con el **número de prueba** de Meta y 5 destinatarios en lista blanca: ventana abierta (texto
   libre), ventana cerrada (salto a plantilla), token revocado a mano (aviso del Panel),
   destinatario que no tiene WhatsApp, modo avión, reinicio del teléfono.
2. Exención de Doze, inicio automático, el interruptor de recepción.
3. Número de producción, verificación de empresa si hace falta, plantilla aprobada en producción
   —que es una plantilla **distinta** de la del número de prueba—.
4. Tag en `main` y `gh release create`.

---

## 6. Riesgos propios de entregar por la Cloud API

| Riesgo | Qué pasa | Mitigación |
|---|---|---|
| **Meta reclasifica la plantilla a Marketing** | Sube el precio por mensaje, y la categoría puede cambiar sin aviso previo | El contador de la fase 7 lo hace visible; se puede pedir revisión en 60 días |
| **El token de usuario de sistema deja de valer** | Nada se entrega, y el síntoma es un `190` en cada reenvío | El aviso del Panel de la fase 4, que es el mismo que ya existe |
| **Calidad del número en rojo** | Meta limita o bloquea el número por denuncias. Es el riesgo real de mandar a quien no lo ha pedido | El `quality_rating` del comprobador de la fase 4 lo enseña antes de que sea un bloqueo |
| **1.000 mensajes de servicio al mes** | Lo que era gratis empieza a costar sin que nada falle | Tope mensual y contador, fase 7 |
| **La ventana no se puede forzar** | Con `SOLO_TEXTO_LIBRE` y la ventana cerrada, no se entrega nada | Es la razón de que `AUTOMATICO` sea el defecto |
| **El número no puede estar en la app de WhatsApp** | Si alguien registra ese número en WhatsApp, la Cloud API deja de funcionar | Documentado en el punto 2 de la preparación; es la pega más fácil de olvidar |

---

## 7. Lo que se pierde respecto a Telegram, sin adornos

- **Grupos y canales.** Los de Telegram, con su `-100…`, no tienen equivalente razonable: la
  Cloud API admite grupos desde 2026, pero solo creados por ella, invite-only, de hasta 8
  participantes y exigiendo Cuenta Oficial de Empresa. **Aquí no cuenta como pérdida**, porque los
  destinos son siempre personas individuales por decisión (ver punto 3). Queda escrito para que
  nadie lo intente más adelante pensando que es cuestión de escribir otro id en la regla: no lo es.
- **Entregar gratis y sin límite.**
- **El descubrimiento de destinos**, que a cambio ya no hace falta.
- **La inmediatez sin condiciones.** Antes el mensaje salía siempre. Ahora sale siempre *si* hay
  plantilla aprobada, cuota y número en buen estado.

Lo que se gana es que es legal, es estable y no depende de que WhatsApp no se dé cuenta.
