# MASTERPLAN — SMStoWhats → SMSRelay

> Cuarta mudanza del mismo esqueleto, y la primera que **no cambia el medio de salida: los junta**.
> Las tres anteriores (`MASTERPLAN-SMS.md`, `MASTERPLAN-TELEGRAM.md`, `MASTERPLAN-WHATSAPP.md`)
> sustituyeron un transporte por otro sin tocar la tubería. Esta deja de sustituir: SMSRelay
> entrega por **los cuatro** —API REST, SMS, Telegram y WhatsApp— y cada regla elige por cuál.
>
> Escrito el 25 oct 2026, **pendiente de revisión**. Ninguna fase está empezada. Las decisiones
> que todavía puede cambiar quien revisa están juntas en el punto 9.

---

## 1. Idea central

Mantener cuatro aplicaciones que son copias unas de otras tiene un coste que ya se ha pagado
varias veces: un arreglo en la tubería son cuatro arreglos, la subida de toolchain del 25 oct
2026 se hizo cuatro veces, y la pestaña «Acerca de» obliga a reconciliar cuatro pantallas cada
vez que cambia una. Las cuatro hacen lo mismo hasta `EnviadorDeMensajes` y solo se separan
por debajo.

SMSRelay es esa observación llevada al código: **una tubería, cuatro transportes debajo de la
juntura, y el canal como un campo más de la regla.**

```
                                     ┌─ SMS        (SmsManager)          ← de SMStoSMS
SIM → … → reglas → reenvío(canal) ──┼─ Telegram   (Bot API)             ← de SMStoTelegram
                                     ├─ WhatsApp   (Cloud API de Meta)   ← de SMStoWhats
                                     └─ API REST   (GET con plantilla)   ← de SMSGateway
```

### La decisión que esta mudanza deshace

Conviene leerlo antes que nada, porque el código heredado dice lo contrario. El KDoc de
`EnviadorDeMensajes` afirma hoy:

> *«Hoy la única implementación entrega por la Cloud API de WhatsApp, y **no está previsto que
> haya dos**. […] Añadir un selector de transporte por regla duplicaría la pantalla de reglas, la
> de ajustes y las guardias para un caso que esta aplicación no tiene.»*

Era verdad para una aplicación por medio. **El caso ahora sí existe**: es exactamente el motivo
de esta aplicación. El coste que el KDoc anticipaba es real —la pantalla de reglas, la de ajustes
y las guardias crecen— y se acepta a cambio de no mantener cuatro proyectos. Lo que **no** cambia
es el contrato de la juntura: el transporte espera la confirmación real, no reintenta, no
registra y no decide el destino. Lo que cambia es que hay cuatro implementaciones y alguien tiene
que elegir entre ellas, y ese alguien es la regla.

Ese KDoc se reescribe en la fase 2, no se deja como está «porque es historia»: un comentario que
contradice el código es peor que no tener comentario.

---

## 2. Lo que ya está hecho

- **La carpeta `SMSRelay/`** es una copia literal de `SMStoWhats` en su commit `c30b5c1`
  (toolchain al día), con el historial de Git completo: el linaje se lee con `git log` igual que
  en las anteriores.
- **Rama `feature/20261025_SMSRelay`**, sacada de `develop`. Sin remoto todavía: el repositorio
  `github.com/jbautistamartin/SMSRelay` no existe (ver punto 9, decisión F).
- Este fichero.

> **Corregido en la fase 1.** Este punto decía que `local.properties` apuntaba todavía al almacén
> viejo (`../smsgateway-release.jks`, alias `smsgateway`). Era falso: ese valor es el del
> `local.properties` de la carpeta raíz `C:\Proyectos\SMS`, que no usa ningún build. El de
> `SMSRelay/` se copió de SMStoWhats ya corregido (`../../Claves/capicua.jks`, alias `capicua`).

---

## 3. Decisiones cerradas

- **Nombre `SMSRelay`, `applicationId` `com.capicua.smsrelay`.** *Relay* es retransmitir: recibir
  un mensaje y pasarlo a otro sitio, sin decir a cuál. Es el primer nombre de la casa que no
  lleva el destino dentro, y es a propósito: el siguiente canal que se añada no obliga a
  renombrar nada.
- **Hereda de SMStoWhats**, la más reciente, porque es la única que ya tiene a la vez la juntura
  de transporte, el `limiteCaracteres` suspendido, el formato de copia más nuevo y el toolchain
  al día. Las otras tres aportan **su transporte**, no su esqueleto.
- **El canal es un campo de la regla, no un ajuste global.** Una regla dice *qué* SMS, *adónde*,
  *por dónde* y *con qué texto*. Con un canal global no habría forma de mandar los códigos del
  banco por Telegram y las alertas de la alarma por SMS, que es lo que justifica tener una sola
  aplicación en lugar de cuatro.
- **Un reenvío lleva su canal copiado**, igual que lleva `nombre_regla`. Un reenvío ya encolado
  tiene que poder entregarse aunque la regla se edite o se borre entretanto, y el rescate de
  huérfanos tiene que saber por dónde reintentar sin volver a mirar la regla.
- **`TransportModule` sigue siendo la única clase que nombra transportes concretos**, ahora como
  un *multibinding* de Hilt (`Map<Canal, EnviadorDeMensajes>`). El worker pide el transporte del
  canal del reenvío y no sabe nada más; cuál es cada uno sigue sin salir de `di/`.
- **Las credenciales siguen sin viajar en la copia**, canal por canal, con la misma línea de
  siempre —«¿describe *cómo* reenviar, o identifica a *este* teléfono?»—: el token del bot, el
  token de Meta, el `phone_number_id` y la SIM se quedan; los modos, las plantillas, los topes y
  las reglas viajan.
- **Esquema de Room a v1 limpio, por cuarta vez**, y por el mismo motivo exacto: un
  `applicationId` nuevo es otra aplicación con otro directorio de datos, así que ningún teléfono
  tiene esta base. `Migraciones.TODAS` arranca vacío, y deja de poder estarlo en cuanto el APK
  se instale en un teléfono con reglas dentro.
- **Formato de copia 6, y esta vez importa todos los anteriores.** Las dos veces que una
  importación se rompió a propósito fue porque el mismo campo `destino` significaba otra cosa
  según la aplicación que escribió el fichero. Esa misma razón es ahora la que permite
  importarlos: **el número de formato dice qué aplicación lo escribió, y por tanto qué canal
  tiene cada destino.** Es la vía de migración desde las cuatro (fase 7).
- **Retrofit y OkHttp siguen fuera.** SMSGateway usa OkHttp 5 para un único `GET`; aquí se
  reescribe con `HttpURLConnection`, como el resto de llamadas de red del proyecto.
- **Versión `1.0.0`, `versionCode 1`, `CHANGELOG.md` a cero.** Como en cada mudanza: es otra
  aplicación, no la 2.0 de ninguna de las cuatro.

---

## 4. Lo que no se toca

El premio de la juntura, por cuarta vez:

`SmsReceiver` · `SmsIngestionService` · el outbox de Room · `EvaluadorDeReglas` (salvo el canal
que pasa de la regla a la coincidencia) · `ColaDeEnvios` · `OrphanRescueWorker` ·
`HealthMonitorWorker` · `BootReceiver` · la idempotencia en sus dos niveles · la política de
reintentos · el historial · las cinco pestañas y su `ViewPager2` · `Pestana` ·
`NestedScrollableHost` · el comprobador de actualizaciones · `OptimizacionBateria` ·
`InicioAutomatico` · `ControlRecepcion` · los marcadores `{mensaje}` `{telefono}` `{fecha}` y el
orden en que se sustituyen.

**`ResultadoEnvio` tampoco se toca.** Ya sirve a los cuatro: `ErrorTransitorio.esperaSegundos`
se escribió para el `retry_after` de Telegram, que vuelve; con WhatsApp y con la API es `null`.
`DispatchWorker.esperarSiLoPiden()`, que hoy retorna en su primera línea, **vuelve a dormir** con
Telegram: es justo para lo que el CLAUDE.md de SMStoWhats dice que se quedó.

**La firma de `EnviadorDeMensajes` no cambia.** Lo que cambia es cuántas implementaciones hay y
quién elige.

---

## 5. Fases

### Fase 1 — Renombrado integral, **icono nuevo** y esquema v1 · `[x]` completada el 25 oct 2026

- `com.capicua.smstowhats` → `com.capicua.smsrelay` (namespace, `applicationId`, árbol de
  paquetes, los 99 ficheros `.kt` —92 de `main` y 7 de tests— con su cabecera LGPL apuntando a
  SMSRelay).
- `SmsToWhatsApplication` → `SmsRelayApplication`.
- `app_name` → `SMSRelay`; APK → `smsrelay-<versionName>.apk`.
- `URL_PROYECTO` y `ComprobadorDeActualizaciones.URL_API` → repositorio nuevo. Un 404 mientras no
  exista se trata como «no hay releases», así que no rompe nada en desarrollo.
- `versionCode = 1`, `versionName = "1.0.0"`, `CHANGELOG.md` a cero.
- Autoridad del `FileProvider` derivada de `applicationId`, en los dos sitios.
- Esquema de Room colapsado a v1, `app/schemas/` regenerado, `Migraciones.TODAS` vacío.
- `local.properties`: `KEYSTORE_PATH=../../Claves/capicua.jks`, `KEY_ALIAS=capicua` (ya lo estaba).

#### El icono va en esta fase, y no al final

**Se ha olvidado dos veces, y las dos por el mismo motivo**: el renombrado cambia el nombre y el
`applicationId`, la aplicación compila, se instala, funciona, y el icono es el de la anterior.
En SMStoSMS lo cazó la primera instalación (ronda 1 del 8 oct, «el icono del lanzador es el de
SMSGateway»); en SMStoWhats estuvo con el azul de SMStoTelegram —byte a byte el mismo icono—
desde el 15 hasta el 17 oct (`3d340fd`). Las dos veces las dos aplicaciones convivían en el mismo
teléfono y se confundían, que es el único problema que el icono existe para evitar.

Por eso aquí **el renombrado no está terminado hasta que el icono es otro**, y la fase no se
cierra sin la lista entera:

| # | Qué | Fichero |
|---|---|---|
| 1 | Color nuevo de la familia (ver abajo) en `md_theme_primary` **y** en toda la paleta M3 derivada | `values/colors.xml` (no hay `values-night/`) |
| 2 | Fondo del icono: degradado derivado del primario nuevo, con el mismo reparto aclarado/oscurecido | `drawable/ic_launcher_background.xml` |
| 3 | Glifo nuevo calado en el bocadillo común | `drawable/ic_launcher_foreground.xml` |
| 4 | **La misma ruta** en la capa monocroma (sin ella Android 13+ deja el icono fuera de los temáticos) | `drawable/ic_launcher_monochrome.xml` |
| 5 | Los comentarios de los tres XML, que hoy cuentan la historia de SMStoWhats | los mismos |
| 6 | Los colores de estado del registro: siguen distinguiéndose del primario nuevo | `values/colors.xml` |
| 7 | Comprobación en el lanzador **con las otras cuatro instaladas al lado** | teléfono |

El punto 7 es el que de verdad cierra la fase: las dos veces anteriores el fallo estaba a la
vista en cuanto había dos iconos juntos en la pantalla.

**Color: naranja.** Los cuatro tonos de la casa son violeta (`#6750A4`, ~262°), azul
(`#0061A4`, ~207°), turquesa (`#006874`, ~186°) y verde (`#006E1C`, ~140°): todos están en la
mitad fría del círculo. El naranja (~30°) es el único hueco que queda lejos de los cuatro, y
también del rojo de error del tema (~0°), que no puede ser el primario de nadie. Se usa el tono
40 de la paleta tonal de M3, con el mismo criterio que el verde: una familia de color propia, no
la de ninguna marca. Valor exacto (del orden de `#8F4C00`) y contraste con `on_primary` se cierran
en la fase. **Ojo con el dorado del corazón de donativo** (`ic_corazon.xml`), que no se tiñe: hay
que mirar que la tarjeta de «Acerca de» no quede con dos naranjas que compiten.

**Glifo: reparto.** El bocadillo se hereda, como en las tres anteriores —la entrada sigue siendo
un SMS—, y lo de dentro dice por dónde sale. Si dentro de SMStoSMS hay una flecha de reenvío y
dentro de SMStoWhats un avión de papel, aquí tiene que decir «a varios sitios»: **un punto de
origen y tres puntas que se abren en abanico**. Las restricciones son las que ya documenta el
foreground de SMStoWhats y conviene no reaprender:

- Es un **hueco** con `fillType="evenOdd"` en la misma ruta, no una figura encima.
- Cada subruta es un polígono **simple**, y ninguna toca a otra: bajo `evenOdd` un cruce se cuenta
  dos veces y vuelve a rellenarse. Cuatro piezas separadas —el punto y tres puntas— cumplen eso
  sin esfuerzo; un abanico dibujado con trazos que se cortan, no.
- Todo dentro de los 72 dp centrales, y el grupo al 85 % que ya existe.
- Que se lea a 48 dp. Tres puntas finas desaparecen a ese tamaño; mejor gruesas y pocas.

**Verificación:** `./gradlew assembleDebug test`, los 138 tests heredados en verde, y la tabla
del icono completa con su punto 7.

> **Cómo se cerró.** Los 138 tests en verde. El color quedó en `#A23F00` (~23°), un naranja
> rojizo y no el `#8F4C00` previsto: aquel caía en el ámbar (~35°) que el registro ya usa para
> «sin regla» y para el tope (`#8A5000`), y la marca se habría confundido con un aviso. Blanco
> sobre él da un contraste de 6,5:1. El glifo quedó en **tres puntas apiladas** y no en abanico:
> un abanico que nace del punto obliga a que las puntas se junten en el origen, y bajo `evenOdd`
> eso es justo lo que no puede pasar. Se renderizó a 216 y a 48 px antes de darlo por bueno, y
> en la segunda pasada se agrandaron el punto y las puntas porque a 48 px se leían como rayas.
>
> **El punto 7 —el lanzador con las otras cuatro al lado— queda pendiente del teléfono**: instalar
> en el teléfono pasarela es cosa de quien lo opera, y se hace en la fase 10.
>
> La firma de la plantilla recomendada de WhatsApp pasa a «— SMSRelay» y el presupuesto sube de
> 978 a 980. Una plantilla aprobada con la firma vieja sigue sirviendo: le sobran dos caracteres
> que caben en el margen de 16.

### Fase 2 — La juntura se abre · `[x]`

La fase de estructura. Al cerrarla, la aplicación sigue entregando **solo por WhatsApp**, pero
por el camino nuevo.

- **`domain/transport/Canal.kt`**: `enum class Canal { API, SMS, TELEGRAM, WHATSAPP }`. Vive en
  dominio porque la regla lo lleva; **no** sabe nada de cómo se implementa cada uno.
- **`Regla.canal`** y **`Reenvio.canal`**; columnas `reglas.canal` y `reenvios.canal`, `TEXT NOT
  NULL` y **sin valor por defecto en SQL**, por la misma razón que `plantilla`: un defecto escrito
  en dos sitios acaba divergiendo. El defecto vive en el dominio.
- **`EvaluadorDeReglas`** pasa el canal de la regla a la coincidencia. Nada más: la evaluación no
  depende del canal.
- **`TransportModule`** pasa de un `@Binds` a un `@IntoMap` por canal con un `@MapKey` propio.
- **`DispatchWorker`** pide `transportes[reenvio.canal]`. Un canal sin transporte registrado es
  `ErrorPermanente` con motivo legible, no un `NullPointerException`: es lo que va a pasar durante
  las fases 3 a 5, cuando haya reglas con canales que todavía no existen.
- **`EnviadorDeMensajes`**: se reescribe su KDoc (punto 1). La firma, igual.
- **El editor de reglas** gana el selector de canal arriba del todo —antes que el destino, porque
  el canal decide qué es un destino válido—, con solo WhatsApp disponible por ahora.

**Verificación:** los tests en verde y una entrega real por WhatsApp idéntica a la de SMStoWhats.
Si algo cambia en lo que ve quien recibe, la fase está mal.

> **Cómo se cerró (25 oct 2026).** 140 tests en verde: los 138 heredados y dos nuevos en
> `EvaluadorDeReglasTest` que fijan que el canal viaja de la regla a la coincidencia y que no
> interviene en si la regla casa. Lo que ve quien recibe no cambia: el texto se compone igual y
> sale por el mismo `WhatsAppSender`. La entrega real queda para la fase 10, con el resto de
> comprobaciones en el teléfono.
>
> Tres cosas que el plan no decía y salieron al escribirlo:
>
> - **`ColaDeEnvios` decide la restricción de red por canal.** Era `CONNECTED` para todo, y
>   eso habría dejado un reenvío por SMS esperando una red que no necesita. API y SMS van con
>   `NOT_REQUIRED` (la API puede estar en la red local, y si no hay ruta el intento falla y se
>   reintenta como cualquier otro); Telegram y WhatsApp siguen con `CONNECTED`. Por eso
>   `encolar()` recibe ahora el `Reenvio` entero y no solo su id.
> - **`Transportes`** envuelve el mapa de Hilt. Nadie más que él toca el
>   `Map<Canal, EnviadorDeMensajes>`, y es quien redacta el motivo de un canal sin transporte,
>   para que el worker, la prueba y el editor digan lo mismo.
> - **La pantalla de prueba y el Panel también eran consumidores del transporte único.** La
>   prueba lleva ahora el canal y el límite de caracteres en cada coincidencia —cada regla puede
>   salir por un canal distinto—, y el aviso del Panel combina solo los canales que usa alguna
>   regla activa: avisar de un token de Telegram ausente a quien solo reenvía por SMS sería
>   ruido.

### Fase 3 — Vuelve Telegram · `[x]`

De SMStoTelegram, casi sin tocar: `data/telegram/` entero —`TelegramSender`, `MapeoDeRespuestas`,
`DestinoDeTelegram`, `TokenDeBot`, `ComprobadorDelBot`, `EstadoDelBot`— y **`DescubridorDeChats`
vuelve**, porque el motivo por el que se fue era el canal y no la aplicación: nadie se sabe su
`chat_id`. Su botón aparece en el editor **solo con el canal Telegram elegido**.

- Hay dos `MapeoDeRespuestas`, en `data/telegram/` y `data/whatsapp/`. Se quedan con su nombre:
  el paquete los distingue, y cada uno se lee mejor al lado de su transporte que con un prefijo.
- `esFalloDeCredencial()` vuelve a ser el 401/404 **para Telegram** y el `190`/`100` **para
  WhatsApp**. El aviso del Panel tiene que decir *de qué canal* es la credencial rechazada.
- `AppConfig.tokenBot` vuelve, con sus tres obligaciones de credencial.
- **Detección cruzada de destinos.** `faltaPrefijo()` ya se niega a dispararse con algo que empiece
  por `-` «porque un `chat_id` de Telegram merece el otro mensaje». Ese otro mensaje ahora existe:
  un `-100…` con el canal WhatsApp elegido dice *«esto parece un chat de Telegram: cambia el
  canal»*, y un `+34…` con el canal Telegram, lo contrario. Va a ser el error más común de la
  aplicación nueva, más que el prefijo.
- Tests de SMStoTelegram que vuelven: `DestinoDeTelegramTest` (10), `MapeoDeRespuestasTest` de
  Telegram (18) y `TokenDeBotTest` (6).

> **Cómo se cerró (25 oct 2026).** 176 tests en verde: los 140 de la fase 2, los 34 que vuelven
> de SMStoTelegram y dos nuevos en `DestinoDeTelegramTest` para la detección cruzada. Las siete
> clases de `data/telegram/` entraron con dos cambios: `limiteCaracteres` pasa a ser la
> `suspend fun` del contrato actual, y `DestinoDeTelegram` gana `esInequivocamenteChat()`.
>
> - **La detección cruzada vive en `DestinoDeTelegram`, no en el editor.** Es la única parte
>   que se puede probar sin Android: un id negativo o un `@canal` son chats sin discusión; un
>   id positivo no, porque `600112233` es también un móvil sin prefijo, y con el canal WhatsApp
>   gana esa lectura y su mensaje. El editor ordena los mensajes: falta de prefijo, chat de
>   Telegram y, si no, forma inválida.
> - **El aviso del Panel dice de qué canal es** porque cada motivo sale prefijado con la
>   etiqueta del canal, y el flujo escucha también `EstadoDelBot.tokenRechazado`.
> - **Ajustes lleva la sección del bot** detrás de la cuenta de WhatsApp, con su propio estado
>   de comprobación: comprobar una credencial no debe bloquear el botón de la otra.
> - **Lo que no se hizo aquí, a propósito:** el token del bot todavía no viaja en la copia de
>   seguridad. El formato 5 no tiene dónde ponerlo, e importar **conserva** el del teléfono en
>   lugar de borrarlo. Entra en el formato 6, en la fase 7.

### Fase 4 — Vuelve el SMS · `[x]`

De SMStoSMS. Es la fase con más que reconciliar, porque SMStoSMS es anterior a la juntura y
enviaba en línea.

- `SmsSender` se adapta a `EnviadorDeMensajes`: espera los `sentIntent` de cada parte, como
  siempre; su `ResultadoEnvio` propio desaparece en favor del de la juntura.
- **`SEND_SMS` y `READ_PHONE_STATE` vuelven al manifiesto**, y vuelven como permisos **que se
  piden solo si hay una regla por SMS**. Una instalación que solo usa Telegram no tiene por qué
  enseñar el diálogo de enviar SMS. `READ_PHONE_STATE`, además, solo hace falta con dos SIM.
- `ProveedorDeSims` y `AppConfig.subscriptionId` (la SIM de salida), que no viaja en la copia.
- **Las guardas antibucle vuelven, pero solo para el canal SMS.** Es el único canal en el que el
  argumento de SMStoSMS sigue valiendo: un reenvío por SMS **sí** puede volver a entrar por la
  radio de otro teléfono pasarela. Telegram, WhatsApp y la API no pueden volver nunca como SMS,
  así que comparar sus destinos con el remitente sigue sin significar nada (el razonamiento de
  `MASTERPLAN-TELEGRAM.md` y `MASTERPLAN-WHATSAPP.md`, que no se deshace). Vuelven
  `NormalizadorTelefono` (y sus 18 tests), `AppConfig.protegerBucles` y
  `ReglaDao.obtenerDestinosActivos()`, este último **filtrando por canal SMS**.
- **`{telefono}` como destino vuelve, solo en el canal SMS**: es contestar a quien escribió, que
  era una función de SMStoSMS. En los otros tres sigue sin resolverse ningún marcador en el
  destino, por la razón que da el `CLAUDE.md` de SMStoWhats y que aquí no cambia: el cuerpo del SMS
  de un tercero no decide adónde se manda nada.
- `limiteCaracteres()` del SMS: se trocea en multiparte, así que el aviso es de **coste** (cuántas
  partes), no de pérdida.
- Ajustes: sección «SMS» con la SIM y la protección de bucles.

> **Cómo se cerró (25 oct 2026).** Tests en verde (198, 0 fallos), con los 18 de
> `NormalizadorTelefonoTest` de vuelta y la sección de destino de `EvaluadorDeReglasTest`
> reescrita para los cuatro canales.
>
> - **`{telefono}` se resuelve en el evaluador, no en el transporte**, al revés de lo que decía
>   el KDoc de la fase 2: el transporte solo recibe destino y texto y no sabe quién escribió. La
>   coincidencia lleva `respondeAlRemitente`, y solo con el canal SMS.
> - **Las guardas registran `LIMITE_ALCANZADO`** en lugar de resucitar `BUCLE_EVITADO`: «una
>   protección dijo que no» es lo que ese tipo ya significa.
> - **Los permisos no se piden al arrancar.** `SEND_SMS` se pide al elegir el canal SMS en el
>   editor o desde Ajustes; `READ_PHONE_STATE`, solo con el botón «Elegir la SIM de envío».
> - **Ajustes guarda a partir de la configuración cargada** (`copy`) y no de un `AppConfig()`
>   nuevo, para que los campos que una pantalla no enseña no vuelvan a su valor por defecto.
> - Importar el formato 5 conserva la SIM y la guarda antibucle del teléfono hasta el formato 6.

### Fase 5 — Vuelve la API REST · `[x]`

De SMSGateway, que es la más lejana: no tiene reglas, no tiene juntura y manda **todos** los SMS a
**una** URL. En SMSRelay eso es una regla que casa con todo y tiene canal API.

- **El destino de una regla API es la plantilla de URL**, con los marcadores de SMSGateway:
  `{mensaje}`, `{telefono}` y `{fecha}`, cada uno URL-encodeado.
- **Esto parece contradecir que en el destino no se resuelve ningún marcador, y no lo hace**, pero
  solo con una condición que se valida al guardar: **los marcadores solo pueden ir después del
  servidor** —en la ruta o en la query—, nunca en el esquema, el host ni el puerto. Codificado, un
  valor no puede añadir `/`, `?` ni `&`; puesto después del host, no puede elegir a qué servidor
  se llama. La propiedad que importa —*el SMS de un tercero no decide adónde va nada*— se mantiene:
  rellena huecos, no elige destino.
- **`{mensaje}` en la URL es el texto compuesto por la regla**, no el cuerpo crudo: así sigue
  habiendo una sola vía de composición, como en WhatsApp. La plantilla por defecto de una regla API
  es `{mensaje}`, de modo que una regla nueva se comporta exactamente como SMSGateway.
- **`{fecha}` en la URL va en ISO-8601 UTC**, no en hora local: la lee una máquina, como en
  SMSGateway. Es la única diferencia de significado de un marcador entre canales, y se documenta
  donde se sustituye.
- `aceptarCertificadosInvalidos` vuelve como ajuste global de la sección «API», ahora con un
  `SSLSocketFactory` sobre `HttpURLConnection`.
- **La URL sí viaja en la copia**, igual que en SMSGateway, y con el mismo aviso: suele llevar el
  dominio y a veces una clave. Es una decisión revisable (punto 9, decisión A).
- Mapeo de SMSGateway sin cambios: `2xx` → `Enviado`; `4xx` salvo `429` → `ErrorPermanente`;
  `429`, `5xx`, timeout e `IOException` → `ErrorTransitorio`. Va a `data/api/MapeoDeRespuestas.kt`,
  puro y con tests.
- **`PlantillaDeUrl`**, puro: sustituir, codificar y validar que no haya marcadores antes de la
  ruta. Con tests: es la pieza de seguridad de esta fase.

> **Cómo se cerró (25 oct 2026).** 220 tests en verde: 14 de `PlantillaDeUrlTest`, 7 del
> `MapeoDeRespuestasTest` de `data/api/` y uno nuevo en `EvaluadorDeReglasTest`.
>
> - **`PlantillaDeUrl` vive en `domain/rules/` y no en `data/api/`**, porque la usa el
>   evaluador: la URL se rellena al evaluar, igual que `{telefono}` en el canal SMS, ya que el
>   transporte solo recibe destino y texto. `ApiSender` hace un `GET` a lo que le llega. Los
>   valores se codifican con `URLEncoder` y los espacios como `%20`, porque un marcador puede
>   estar en la ruta y allí `+` no es un espacio.
> - **La validación rechaza también un marcador en el usuario o en el puerto**, no solo en el
>   host: todo lo que hay entre `://` y el primer `/`, `?` o `#`.
> - **El `reenvio.destino` guarda la URL ya rellena**, con el mensaje dentro. Es lo que hace
>   idempotente el reintento —se llama exactamente a la misma URL—, pero significa que la URL
>   entera, clave incluida si la lleva, queda en la base de datos como el resto del reenvío. Los
>   `Timber` del transporte registran solo la parte sin query.
> - **Un `3xx` es permanente**, que SMSGateway no distinguía: no se siguen redirecciones,
>   porque la de `https` a `http` no debe seguirse, y el motivo pide escribir la URL final.
> - `{fecha}` en ISO-8601 UTC entra como parámetro `fechaIso` de `evaluar()`.
> - La plantilla de una regla nueva cambia a `{mensaje}` al elegir el canal API, y vuelve a la
>   de siempre al salir, pero solo si nadie la ha tocado.

### Fase 6 — Ajustes, Panel y prueba de reglas por canal · `[x]`

- **Ajustes** mantiene el orden común de la casa —recepción, fiabilidad, destino, límites, envío y
  reintentos, copia— y la sección «destino» pasa a ser «Canales», con una tarjeta por canal. Cada
  tarjeta dice si el canal está **configurado** y si **alguna regla lo usa**; una regla activa por
  un canal sin configurar es un aviso, no un detalle.
- **Panel**: un aviso por credencial rechazada, diciendo el canal; el contador de mensajes de
  WhatsApp del mes solo si alguna regla usa WhatsApp.
- **Probar reglas**: enseña el canal de cada coincidencia y usa el `motivoNoDisponible()` de *ese*
  transporte.
- **Lista de reglas**: el canal de cada regla a la vista, con un icono por canal en el tono del
  tema (no en los colores de las marcas).
- **Registro**: el canal en cada entrada de reenvío, y como filtro.

> **Cómo se cerró (25 oct 2026).** 220 tests en verde; esta fase no añade lógica pura que probar.
> Parte del trabajo ya estaba hecho en fases anteriores: la prueba de reglas es por canal desde
> la 2, y el aviso del Panel nombra el canal desde la 3.
>
> - **Ajustes no se reordenó en tarjetas, sino que ganó un resumen.** La sección «Canales» abre
>   con una línea por canal —listo o qué le falta, y cuántas reglas activas lo usan—, en ámbar
>   cuando hay reglas activas y el canal no puede entregar. Debajo siguen las subsecciones de
>   cada canal tal como entraron en las fases 3 a 5. Es la mitad barata de lo que pedía el plan
>   y dice lo mismo; repartirlas en tarjetas plegables queda para cuando se vea en el teléfono.
> - **`log_entries` gana la columna `canal`**, nullable, en el esquema v1, que todavía se puede
>   editar porque no hay ninguna instalación. El filtro por canal es un segundo carrusel de
>   chips que se combina con el de tipo, y deja fuera las entradas que no son de un reenvío.
> - **La lista de reglas enseña el canal como texto** («WhatsApp · +34…») y no con un icono: es
>   más claro y no obliga a dibujar cuatro iconos que no se parezcan a las marcas. La URL de una
>   regla API se enseña sin query.
> - El contador del mes del Panel se esconde si ninguna regla activa usa WhatsApp.

### Fase 7 — Copia de seguridad: formato 6 y la migración desde las cuatro · `[x]`

El formato 6 añade `canal` a cada regla. Importar acepta:

| Fichero de | Formato | Qué se hace |
|---|---|---|
| SMSRelay | 6 | Tal cual |
| SMStoWhats | 5 | Cada regla, canal WhatsApp. Modo de entrega, plantilla y tope mensual a sus ajustes |
| SMStoTelegram | 4 | Cada regla, canal Telegram |
| SMStoSMS | 3 | Cada regla, canal SMS. Su `protegerBucles` a su ajuste |
| SMSGateway | su formato 1 (otra forma: `urlTemplate`, sin reglas) | **Una regla** que casa con todo, canal API, destino la `urlTemplate`, plantilla `{mensaje}`. `aceptarCertificadosInvalidos` a su ajuste |

- **Los ajustes ya no se pueden sustituir enteros al importar**, que era la regla de las cuatro.
  Importar la copia de SMStoTelegram no puede dejar a cero el modo de entrega de WhatsApp. Cada
  fichero sustituye **los ajustes de su canal y los comunes**, y deja los demás como estaban.
- El fichero de SMSGateway **se reconoce por su forma** y no por su número: un `version: 1` a
  secas puede ser también un formato antiguo de SMStoSMS. Qué eran exactamente los formatos 1 y 2
  de SMStoSMS se comprueba en el Git de aquel repositorio al empezar la fase; si alguno no lleva
  números internacionales, se rechaza con el mismo argumento que dio SMStoWhats para el 3.
- Las reglas importadas cuyo destino no valida para su canal se rechazan y se cuentan aparte, como
  en el formato 5. Las credenciales no vienen en ningún fichero, así que importar deja **cada canal
  usado sin configurar**, y la pantalla tiene que decirlo al terminar: *«3 reglas por Telegram:
  falta el token del bot»*.
- `ReglasJsonTest` crece con un fichero real de cada una de las cuatro aplicaciones como fixture.

Esto es lo que convierte «instalar SMSRelay» en una migración de verdad: exportar en la
aplicación vieja, importar en la nueva, rellenar credenciales.

> **Cómo se cerró (25 oct 2026).** 233 tests en verde. La decisión de qué entra salió de
> `ConfigBackup` a `data/rules/ImportacionDeCopia.kt`, Kotlin puro, para poder probarla con un
> fichero de cada aplicación: `ImportacionDeCopiaTest` (17) lleva uno de SMSRelay, SMStoWhats,
> SMStoTelegram, SMStoSMS 1 y 3 y SMSGateway con la forma exacta que escribía cada una, y
> `ReglasJsonTest` se reescribió (14) para el formato 6 y `ConfiguracionJson.aplicarSobre`, que
> sustituye a `aDominio(token, id)` y aplica los comunes más los del canal del fichero.
> `MINIMA_ACEPTADA` baja a 1.
>
> Tres cosas se apartaron de lo escrito arriba:
>
> - **Los formatos 1 y 2 de SMStoSMS se aceptan.** El Git de SMStoSMS dice que tienen la forma
>   del 3, el 1 sin `ignorar_mayusculas` y los dos sin ajustes, y que sus destinos son números
>   nacionales. Lo que obligó a SMStoWhats a rechazar el 3 era que sus números no valían para
>   WhatsApp; aquí son reglas del canal SMS, y para marcar desde la SIM un número nacional es
>   justo lo correcto. El argumento de rechazo no aplica.
> - **Una credencial en blanco no pisa la del teléfono, y el formato 6 sí puede traer el token del
>   bot** si quien exporta marca la casilla, igual que las dos de WhatsApp. La SIM sigue sin viajar.
> - **El aviso de canales sin preparar es un diálogo, no un Snackbar**: `ConfigBackup` pregunta
>   a cada transporte de las reglas importadas su `motivoNoDisponible()` *después* de guardar los
>   ajustes, y lo que devuelve es lo siguiente que hay que hacer, así que no puede irse solo.
>   El Snackbar dice además de qué aplicación venía la copia.

### Fase 8 — Límites por canal · `[ ]`

- **El tope por minuto sigue siendo uno y global**, cortando ráfagas de SMS entrantes antes de
  saber por dónde van a salir. Cambia de significado por cuarta vez: ya no protege a un medio, los
  protege a todos a la vez, así que su defecto lo marca el más estricto de los que estén en uso.
- **El tope mensual sigue siendo de WhatsApp** —mide el dinero de Meta y no tiene sentido en
  Telegram—, salvo lo que se decida en el punto 9, decisión B.
- Telegram: el `retry_after` vuelve a dormir al worker (ver punto 4).
- `LogTipo.LIMITE_ALCANZADO` dice qué tope y, si es por canal, cuál.

### Fase 9 — Documentación y privacidad · `[ ]`

- `CLAUDE.md` reescrito. Muchas afirmaciones de hoy dejan de ser verdad en esta aplicación: «no
  hay `SEND_SMS`», «el destino es siempre un E.164», «`TransportModule` es un `@Binds`», «los
  ajustes se reemplazan al importar».
- `README.md`, `docs/ARCHITECTURE.md`, y la guía de cada canal: `docs/CUENTA-WHATSAPP-BUSINESS.md`
  se queda, y se escribe la equivalente para Telegram (crear el bot con BotFather, añadirlo al
  grupo, descubrir el chat), que hoy solo vive dentro del README de SMStoTelegram.
- **El aviso de privacidad tiene que hablar de los cuatro canales**, y no se puede promediar. Lo
  que dice de WhatsApp —Meta, cuenta de empresa a nombre de quien opera, mensajería no solicitada,
  facturación— se queda entero y **no se suaviza** (es la instrucción literal del CLAUDE.md de
  SMStoWhats). Se añade lo de Telegram (los servidores de Telegram), lo de la API (el servidor que
  configure quien opera, con los certificados sin validar si así se eligió) y lo del SMS (el
  operador de telefonía). La estructura natural es: lo común primero, y un párrafo por canal.
- «Acerca de» mantiene las seis tarjetas en el orden común.
- `CLAUDE.md` de la carpeta raíz: SMSRelay en la tabla, en el linaje y en la tabla de planes.
- Tests: de 138 a **~220**. Vuelven 34 de Telegram y 18 de `NormalizadorTelefono`; se añaden
  `PlantillaDeUrlTest`, el mapeo de la API, el de canales y las importaciones de la fase 7.
  `PlantillaDeWhatsAppTest` debería **afirmar el literal** del presupuesto, que es la deuda que el
  CLAUDE.md de SMStoWhats deja escrita.

### Fase 10 — Dispositivo real y release 1.0.0 · `[ ]`

Como en SMStoWhats: las nueve anteriores se cierran con tests; esta, cuando un SMS real sale por
cada canal real. La batería de 9.2 de `MASTERPLAN-WHATSAPP.md` se repite **por canal**, más lo que
solo existe aquí:

| # | Qué se provoca | Qué tiene que pasar |
|---|---|---|
| 1 | Un SMS que casa con cuatro reglas, una por canal, con `continuar` | Cuatro reenvíos, cuatro entregas, cada una por su canal |
| 2 | Credencial de Telegram revocada con WhatsApp sano | Telegram falla y avisa **diciendo Telegram**; WhatsApp sigue entregando |
| 3 | Reenvío por SMS a otro pasarela que lo devuelve | La guarda antibucle lo corta |
| 4 | URL de API con un marcador en el host | No se puede guardar la regla |
| 5 | Importar una copia real de cada una de las cuatro | Reglas con su canal; aviso de credenciales que faltan |
| 6 | Instalar con las cuatro aplicaciones viejas en el mismo teléfono | Cinco iconos distinguibles; cinco apps recibiendo el mismo SMS sin interferir |

La fila 6 tiene una pega que conviene saber de antemano: con SMSRelay y la vieja instaladas y las
dos activas, **cada SMS se reenvía dos veces**. Es lo correcto —son dos aplicaciones— y es la
razón de que la migración termine apagando la recepción de la vieja.

---

## 6. Riesgos

| Riesgo | Qué pasa | Mitigación |
|---|---|---|
| **Un transporte arrastra a otro** | Un fallo de un canal tapa los demás, o un `retry_after` de Telegram retrasa un SMS | Cada reenvío es un worker independiente; la fila 2 de la fase 10 lo comprueba |
| **Pantalla de reglas recargada** | Cuatro formas de destino, tres botones de ayuda que dependen del canal | El canal arriba y todo lo demás condicionado a él; lo que no aplica no se pinta |
| **Permisos de más** | La app pide `SEND_SMS` aunque nadie mande SMS | Pedirlo solo al guardar la primera regla por SMS (fase 4) |
| **Doble entrega durante la migración** | La vieja y la nueva reenvían el mismo SMS | Documentado; el último paso de la migración es apagar la vieja |
| **El linaje se rompe al revés** | Un arreglo en una de las cuatro viejas no llega a SMSRelay, o al contrario | Punto 9, decisión D: decidir pronto qué pasa con las cuatro |
| **El icono se olvida otra vez** | Ver fase 1 | La tabla de la fase 1 y su punto 7 |

---

## 7. Lo que se gana y lo que se pierde, sin adornos

**Se gana:** un solo proyecto que mantener; un arreglo de la tubería es un arreglo; varios canales
por SMS con `continuar`; una sola subida de toolchain; una pestaña «Acerca de»; una migración
real desde las cuatro.

**Se pierde:**
- **Simplicidad de cada una.** SMStoTelegram era la aplicación más sencilla de las cuatro de
  configurar —un token y un `chat_id`—, y en SMSRelay es una de cuatro tarjetas.
- **El argumento de permisos mínimos.** SMStoTelegram y SMStoWhats presumían de no tener
  `SEND_SMS`; esta lo tiene en el manifiesto aunque no lo pida.
- **SMSGateway como pasarela pura.** Allí no había reglas; aquí, aunque una regla con todo vacío
  la reproduce, hay que saber crearla.

---

## 8. Orden y dependencias

Fase 1 → 2 son secuenciales y bloquean todo. **3, 4 y 5 son independientes entre sí** (cada una
es un transporte debajo de la juntura) y se pueden hacer en cualquier orden. 6 necesita las tres.
7 necesita 2 y conviene después de 3-5 para probarlo con todos los canales. 8 y 9 al final; 10
cierra.

---

## 9. Decisiones para la revisión

Lo de arriba está escrito con una opción elegida en cada caso. Estas son las que merece la pena
que alguien confirme antes de empezar, con la recomendada primero:

| | Decisión | Recomendada | Alternativa |
|---|---|---|---|
| **A** | Dónde vive la URL de la API | **En el destino de la regla**: permite varias APIs y es lo que hace del canal un canal | Un ajuste global, como en SMSGateway; más simple, pero la API sería el único canal con un solo destino |
| **B** | Tope mensual | **Solo WhatsApp**, que es lo que mide | Global, contando todos los canales; protege también del coste de los SMS |
| **C** | Color del icono | **Naranja**, tono 40 | Otro tono cálido; los fríos están todos ocupados |
| **D** | Qué pasa con las cuatro | **Una última versión de cada una** cuya tarjeta de actualizaciones diga que la aplicación sigue en SMSRelay, y después archivar los repositorios | Mantenerlas en paralelo (vuelve el problema de los cuatro sitios) o archivarlas sin aviso (las copias instaladas no se enterarían nunca: el comprobador solo mira su propio repositorio) |
| **E** | Formato 1 y 2 de SMStoSMS al importar | **Decidir en la fase 7** tras mirar qué eran | — |
| **F** | Crear `github.com/jbautistamartin/SMSRelay` | **Al cerrar la fase 1**, para que el comprobador de versiones apunte a algo real | Al publicar la 1.0.0 |
