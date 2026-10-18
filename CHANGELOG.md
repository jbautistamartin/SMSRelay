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
