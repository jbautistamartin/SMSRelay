# Registro de cambios

Todas las versiones publicadas de **SMStoWhats**, con lo que cambió en cada una.
El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones,
[SemVer](https://semver.org/lang/es/).

> **SMStoWhats nace de [SMStoTelegram](https://github.com/jbautistamartin/SMStoTelegram)**, que
> recibe SMS y los entrega en Telegram a través de un bot. Esta aplicación hace lo mismo con la
> entrada y cambia la salida: entrega en WhatsApp por la Cloud API de Meta. Son **aplicaciones
> distintas**, con `applicationId` propio y base de datos propia, y pueden convivir en el mismo
> dispositivo. Por eso este registro empieza de cero en lugar de continuar el de aquella.

---

## [No publicado]

### Cambiado

- **Toolchain al día.** AGP 8.5.2 → 9.4.1, Kotlin 2.0.21 → 2.4.21, KSP 2.3.12, Gradle 9.3.0 →
  9.8.2, Hilt 2.60.1, Room 2.8.5, WorkManager 2.12.0 y el resto de dependencias a su última
  versión estable. `compileSdk` pasa a 37; `targetSdk` sigue en 35, así que el
  comportamiento en el teléfono no cambia. Para compilar hace falta **Temurin 25** y la
  **plataforma 37** del SDK. Se hizo a la vez en las cuatro aplicaciones de la familia.

### Corregido

- **Reglas de R8 con el paquete de SMStoTelegram.** `proguard-rules.pro` venía copiado tal cual y
  las reglas de los enum que se guardan como texto y de los Fragments apuntaban a
  `com.capicua.smstotelegram`, así que en esta aplicación no protegían nada. Ahora apuntan a
  `com.capicua.smstowhats`.

### Documentación

- **`MASTERPLAN-GATEWAY.md`**, escrito a posteriori: el origen de SMSGateway y de la tubería que
  heredaron las otras tres. Lo llevan las cuatro, para que todas tengan la misma estructura de
  planes.

## [1.0.0] - 2026-10-18

**La cadena funciona de punta a punta.** Un SMS recibido en la SIM del teléfono dedicado llega a
un WhatsApp a través de la Cloud API de Meta. Eso es lo que separaba a esta aplicación del 1.0 y
es lo único que cambia en esta versión: **no hay cambios de código respecto a la 0.10.0**.

### Qué se ha validado, y con qué

- Con el **número de prueba** de Meta y destinatarios en lista blanca, que es lo que permite
  operar sin verificación de empresa.
- Las ocho fases anteriores siguen cubiertas por los **138 tests unitarios**, todos en verde.
- El APK de release compila, firma con la clave compartida de la casa y entra encima de la
  instalación anterior conservando reglas, ajustes e historial.

### Qué sigue pendiente, y conviene saberlo antes de instalar

El 1.0.0 dice que **la aplicación funciona**, no que tu cuenta de Meta vaya a dejarte usarla en
producción mañana. Para pasar de las pruebas a un número propio hacen falta dos cosas que no
dependen de este código y que llevan días:

1. **Verificación de empresa.** Sin ella Meta no deja registrar un número propio, y sin registrar
   no se entrega nada. Es el paso 2 (Opción B) de
   [`docs/CUENTA-WHATSAPP-BUSINESS.md`](docs/CUENTA-WHATSAPP-BUSINESS.md).
2. **Registrar el número** y dar de alta la plantilla **en la WABA de producción**: las
   plantillas cuelgan de la WABA y la del número de prueba no viaja.

Tampoco se puede saber todavía, porque el número de prueba no lo dice: si el nombre a mostrar
será aceptable, cuáles son los límites reales de mensajería, ni cómo evolucionará la calidad del
número con destinatarios que no te conocen. Eso último es el riesgo de verdad de esta
aplicación y está explicado en el aviso de privacidad.

## [0.10.0] - 2026-10-18

Sigue siendo un **preestreno**: la fase 9 del
[`MASTERPLAN-WHATSAPP.md`](MASTERPLAN-WHATSAPP.md) no está cerrada y por eso no es la 1.0.0.

### Corregido

- **La versión de la Graph API sube de `v22.0` a `v26.0`.** Meta deprecó la v22.0 y lo dice en
  una cabecera de respuesta que el cuerpo del error no menciona
  (`x-ad-api-version-warning: The call has been auto-upgraded to v26.0 as v22.0 has been
  deprecated`). Pedir una versión deprecada funciona hasta que deja de hacerlo.

  **No era la causa del incidente que lo destapó**, y queda escrito en el código para que nadie
  lo dé por arreglado por ahí: aquellos `HTTP 500 · error 1` venían de que el número emisor no
  estaba **registrado** en la Cloud API, y Meta ya servía la llamada auto-promocionada a v26.0.

- **Dos cifras obsoletas en el KDoc de `PlantillaDeWhatsApp`**, que decían 992 donde el código
  calcula 978.

### Añadido

- **La copia de seguridad puede llevar las credenciales, si se piden.** Una casilla nueva en
  Ajustes → Copia de seguridad, **apagada por defecto**, decide si el token de acceso y el id del
  número viajan dentro del fichero. Sin marcarla, el comportamiento es el de siempre y las copias
  antiguas se siguen importando igual.

  Hasta ahora las credenciales nunca viajaban, y eso hacía que una copia no sirviese para lo que
  más falta hace: restaurar un teléfono entero o clonar la configuración en otro. El precio es
  que el fichero pasa a ser un secreto —quien lo abra puede enviar mensajes en tu nombre y con
  cargo a tu cuenta—, así que la casilla enseña un aviso en rojo mientras está encendida, y el
  mensaje de confirmación al terminar también lo dice.

  La casilla **no se recuerda**: vuelve a estar apagada cada vez que se entra en Ajustes. No es un
  ajuste de la aplicación, es una decisión sobre esa exportación concreta.

  Cada credencial se decide por separado al importar, y una cadena vacía cuenta como ausente:
  sustituir una credencial buena por nada es el peor de los dos resultados posibles.

### Documentación

- **`docs/CUENTA-WHATSAPP-BUSINESS.md` gana el paso que faltaba: registrar el número.** No vivía
  en ninguna parte de la guía, no lo pide ninguna pantalla de las dos consolas, y sin él no se
  entrega ni un mensaje. Un número puede estar **verificado y sin registrar** a la vez —
  `code_verification_status: VERIFIED` con `status: PENDING`— y el síntoma es un `HTTP 500` con
  `error.code` 1 que no apunta a ningún sitio. La guía explica ahora cómo detectarlo, cómo
  registrarlo y cómo reconocer el síntoma.

- **La verificación de empresa deja de describirse como opcional.** Decía que servía «si hacen
  falta los límites altos». Es falso: **sin ella Meta no deja registrar un número propio**, y por
  tanto no se entrega nada. Corregido en la guía y en el punto 9.4 del masterplan, donde pasa a
  ser el primer paso del paso a producción.

- **`CLAUDE.md` pasa íntegramente al español** y gana una sección con el estado real de la fase 9:
  qué está verificado desde el editor y qué solo puede cerrarse con un teléfono y una cuenta de
  Meta.

## [0.9.0] - 2026-10-17

### Documentación

- **`docs/CUENTA-META.md` pasa a llamarse `docs/CUENTA-WHATSAPP-BUSINESS.md`.** El nombre decía
  «Meta», que es la empresa, y no lo que la guía monta: una cuenta de **WhatsApp Business** en la
  Cloud API. Quien buscaba cómo montar la cuenta de WhatsApp no la encontraba por el nombre.

- **La guía explica ahora qué es cada cosa antes de pedir el primer clic.** La sección nueva
  «El mapa» separa la app «WhatsApp Business» del Play Store —que no se usa y que **quema el
  número** si se instala— de la Cloud API, dibuja la jerarquía *cuenta de empresa → app de Meta →
  WABA → número / plantillas / usuario de sistema* diciendo de cuál sale cada uno de los cuatro
  datos de la aplicación, y explica por qué hay **dos consolas** y cuál de las dos tiene el token
  que sirve. Con una tabla de rutas por pantalla y otra del orden correcto con lo que tarda cada
  paso.

- **El «dónde exactamente» del token y de la plantilla, con enlace directo.** La guía daba rutas
  de menú —*Configuración del negocio → Usuarios → Usuarios del sistema*, *WhatsApp Manager →
  Plantillas*— y esos rótulos ya no son los de Meta: «Usuarios del sistema» es hoy un ítem de
  primer nivel de la barra lateral, el botón de los activos se llama *Asignar activos* y la URL de
  la configuración del negocio es `/latest/settings`. Verificado contra la documentación actual de
  Meta: ahora cada una de las dos pantallas lleva su **URL directa** destacada, más una tabla de
  enlaces directos a las siete pantallas de la consola, porque los menús cambian y las URL no.
  Y los permisos del token son **tres**, no dos: `business_management` iba sin documentar.

- **La plantilla se configura en dos sitios y la guía no lo decía de frente.** Darla de alta en
  Meta (5.2) no basta —la aplicación no la descubre sola— y escribirla en Ajustes (5.4) sin haberla
  dado de alta tampoco. Ahora el paso 5 abre con esa tabla, y la lista de comprobación lo pregunta.
  Con los estados reales que enseña WhatsApp Manager, que son siete y no tres: *en pausa* y
  *deshabilitada* dejan de entregar por quejas de los destinatarios.

- **Los tres pasos que más se atascan, reescritos** con el síntoma de cada fallo, que casi siempre
  es silencioso:
  - *el número*: la lista blanca del número de prueba —Meta acepta el mensaje y lo descarta sin
    avisar si el destinatario no confirmó su código—, qué libera un número que ya tiene WhatsApp,
    y los tres identificadores de 15 dígitos que es fácil confundir;
  - *el token*: el depurador de tokens de Meta para comprobar **Caduca: Nunca** y los dos permisos
    antes de pegarlo, los dos activos que hay que conceder en pestañas distintas, y que un permiso
    que falta da el mismo error que un token caducado;
  - *la plantilla*: que **`es` y `es_ES` son dos plantillas distintas**, los estados de revisión,
    los motivos de rechazo reales, y que editar una aprobada la deja sin entregar mientras se
    revisa.

### Cambiado

- **«Ignorar mayúsculas» viene activado en las reglas nuevas.** Es lo contrario de lo que parece
  prudente y es lo que pide la práctica: el mismo remitente escribe «CODIGO», «Codigo» o «codigo»
  según el operador y el día, y una regla que distinga deja de casar sin dar ningún error —no
  falla, simplemente no reenvía—. Distinguir mayúsculas pasa a ser la excepción, y se pide
  apagando el interruptor que ya existía en la pantalla de la regla.

  **Las reglas ya guardadas no cambian.** Conservan el criterio que tuvieran: cambiárselo por
  detrás alteraría en silencio lo que casan en un teléfono que ya está funcionando. Esto solo
  afecta a las reglas que se creen a partir de ahora.

- **Orden común en «Configuración».** Las cuatro aplicaciones de la familia presentan ahora las
  secciones en el mismo orden: **Recepción de SMS**, **Fiabilidad en segundo plano**, la sección
  propia del destino, **Límites**, **Envío y reintentos** y **Copia de seguridad**. Quien salta de
  una a otra encuentra lo mismo en el mismo sitio.

- **Las secciones de cuenta y entrega ya no van primeras**, sino terceras, donde las otras tres
  ponen su destino. Que sean los únicos ajustes que una instalación nueva trae vacíos lo siguen
  diciendo el subtítulo de la pantalla y el aviso del panel.

- **Icono verde.** El fondo del icono y el primario del tema pasan del azul heredado de
  SMStoTelegram al verde de Material 3 (tono 40). Con el azul, el icono era idéntico al de
  SMStoTelegram —mismo fondo, mismo dibujo, misma capa monocroma— y a 48 dp en el lanzador se leían
  como la misma aplicación. «Recibido» y «enviando» siguen en azul en el registro: en verde se
  confundirían con «entregado» y «reenviado».

La aplicación está completa y verificada por los tests, pero **no se ha probado en un dispositivo
real con una cuenta de Meta**. Eso es la fase 9 del `MASTERPLAN-WHATSAPP.md` y es lo que falta
para poder publicar una 1.0.0.

### Añadido

- **Copiar una entrada del registro con una pulsación larga.** Copia la misma línea que escribe
  el fichero exportado —fecha, tipo y detalle—, para pegarla en un correo o en un parte de
  incidencia sin tener que exportar el registro entero. El aviso se enseña hasta Android 12;
  desde Android 13 lo saca el propio sistema, con vista previa de lo copiado, y poner otro encima
  serían dos avisos de lo mismo.

- **Entrega por WhatsApp.** Los SMS recibidos se entregan como mensajes de WhatsApp a través de
  la Cloud API de Meta, al número de una persona.
- **Dos modos de entrega y uno automático.** El texto libre es gratis pero solo se puede mandar
  dentro de la ventana de 24 h que abre el destinatario; las plantillas se pueden mandar siempre
  y se facturan. El modo automático intenta el texto libre y salta a plantilla si hace falta,
  dentro del mismo intento.
- **Contador de gasto en el Panel.** Los mensajes entregados este mes contra el tope configurado,
  separando los de servicio de los de plantilla.
- **Tope de mensajes al mes**, 900 por defecto, por debajo del tramo gratuito de Meta.
- **Comprobación de credenciales** que responde con el nombre verificado, el número y la calidad
  que Meta da al número emisor.
- El cuerpo exacto que tiene que tener la plantilla, copiable al portapapeles desde Ajustes.
- **`docs/CUENTA-WHATSAPP-BUSINESS.md`**, la guía que monta la cuenta de Meta paso a paso: la app en Meta for
  Developers, el número, el token permanente de usuario de sistema, la plantilla y el gasto, con
  una lista de comprobación y los códigos de error de Meta que de verdad se ven. Es el requisito
  más largo del montaje y el README solo lo enumeraba.

### Cambiado

- El **destino de una regla vuelve a ser un número de teléfono**, en formato internacional, y
  siempre una persona.
- **Dos credenciales** donde Telegram pedía una: el token de acceso y el id del número.
- El formato de la copia de seguridad sube a **5** y rechaza los anteriores: un fichero del 4
  tiene la misma forma pero sus destinos son chats de Telegram.
- El teclado del campo de destino vuelve a ser el de teléfono, que trae el «+».

### Eliminado

- El **descubrimiento de chats** y su botón. Existía porque nadie se sabe su `chat_id`; todo el
  mundo se sabe su número de teléfono.
- **Los grupos como destino**, por decisión: los de la Cloud API son invite-only, de ocho
  participantes y exigen Cuenta Oficial de Empresa.
