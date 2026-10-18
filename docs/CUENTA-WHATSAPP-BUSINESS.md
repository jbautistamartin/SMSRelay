# Montar la cuenta de WhatsApp Business, paso a paso

Esta guía cubre **todo lo que hay que hacer fuera de la aplicación** para que SMStoWhats pueda
entregar en WhatsApp: una **cuenta de WhatsApp Business** en la Cloud API de Meta. Es la parte
larga del montaje —la aplicación se instala en dos minutos y esto lleva entre media hora y un par
de días, según si Meta te pide verificar la empresa— y la mayor parte de lo que cuesta no es
técnico, es saber qué es cada cosa.

**Si es la primera vez, lee la sección «El mapa» antes de abrir ninguna consola.** Los cuatro
sitios donde esto se atasca —qué producto es, el número, el token y la plantilla— se atascan por
cosas que no se ven hasta que ya has hecho el paso siguiente.

Al final tendrás los **cuatro datos** que la aplicación necesita:

| Dato | Dónde se escribe en la app | Ejemplo |
|---|---|---|
| **Token de acceso** | Ajustes → Cuenta de WhatsApp → *Token de acceso* | `EAAG…` (cadena muy larga) |
| **Id del número** | Ajustes → Cuenta de WhatsApp → *Id del número* | `123456789012345` |
| **Nombre de la plantilla** | Ajustes → Entrega → *Nombre de la plantilla* | `sms_reenviado` |
| **Idioma de la plantilla** | Ajustes → Entrega → *Idioma de la plantilla* | `es` |

> **Antes de empezar, lo que no se puede deshacer fácilmente:** el número que uses en la Cloud API
> **no puede estar registrado en la app de WhatsApp** ni en WhatsApp Business. Si registras tu
> número personal en la Cloud API, lo pierdes como número de WhatsApp normal. Empieza con el
> **número de prueba** que Meta regala, que es gratis y no te obliga a nada.

---

## El mapa · qué es cada cosa, antes de tocar nada

Esta es la parte que hace perder más tiempo, y no es técnica: es de nombres. Meta llama cosas
parecidas a cuatro objetos distintos, y la documentación oficial mezcla los nombres viejos con los
nuevos. Leer esta sección entera antes de abrir ninguna consola ahorra la mayor parte de la
batalla.

### Lo primero: esto **no** es la app «WhatsApp Business»

| | Qué es | ¿Es lo de esta guía? |
|---|---|---|
| **WhatsApp** | La app de siempre, para personas | No |
| **WhatsApp Business** (app del Play Store) | Una app de móvil para atender clientes **a mano**. No tiene API ni se puede automatizar | **No.** Y además inutiliza el número: ver abajo |
| **WhatsApp Business Platform** → **Cloud API** | Un servicio web al que se le manda un `POST` y entrega el mensaje. Lo aloja Meta | **Sí. Esto es lo que usa SMStoWhats** |
| **WhatsApp Business Platform** → **On-Premises API** | Lo mismo pero en un servidor tuyo. Meta lo está retirando | No. Si un manual habla de *On-Premises*, no te sirve |

> **Si instalas la app «WhatsApp Business» en el número que vas a usar, lo quemas.** Un número
> solo puede estar en un sitio: o en una app de WhatsApp (normal o Business) o en la Cloud API. La
> guía insiste en esto más abajo porque es irreversible sin borrar la cuenta y esperar.

La plataforma también se llamó **«WhatsApp Business API»** durante años. Si buscas en Google,
casi todo lo que encuentres usará ese nombre viejo. Es la misma cosa.

### Las cuatro piezas, y de cuál sale cada dato

Nada de esto se crea aparte: configurando el producto WhatsApp en el paso 1 aparecen casi todas
solas. Lo que hace falta es **saber cuál es cuál**, porque los cuatro datos de la aplicación salen
de sitios distintos:

```
Cuenta de Facebook  (tu usuario personal; solo sirve para entrar)
│
└── Cuenta de empresa   ·  hoy «Business Portfolio», antes «Business Manager»
    │                      business.facebook.com
    │
    ├── App de Meta for Developers        ← aquí se añade el producto «WhatsApp»
    │   developers.facebook.com              y aquí vive el token *temporal* que NO sirve
    │
    ├── WABA · WhatsApp Business Account  ← la cuenta de WhatsApp de la empresa.
    │   │                                   Se crea sola al configurar el producto
    │   │
    │   ├── Número(s) de teléfono         →  de aquí sale el ID DEL NÚMERO
    │   │
    │   └── Plantillas de mensajes        →  de aquí salen NOMBRE e IDIOMA de la plantilla
    │
    └── Usuario de sistema                →  de aquí sale el TOKEN DE ACCESO que sí sirve
```

**«App» es la palabra más sobrecargada de todo esto.** En esta guía:

- **la app de Meta** es el objeto de `developers.facebook.com`, un contenedor técnico que nadie ve;
- **la aplicación** es SMStoWhats, lo que corre en el teléfono;
- **la app de WhatsApp Business** es la del Play Store, que no se usa aquí.

### Las dos consolas, y por qué te vas a equivocar de una

| Consola | Para qué | Lo que te da |
|---|---|---|
| **developers**.facebook.com | La app de Meta y las pruebas | El **id del número**, la lista blanca, y un token temporal de 24 h |
| **business**.facebook.com | La empresa: WABA, números, plantillas, facturación | El **token permanente**, las **plantillas**, la verificación de empresa |

El token que la aplicación necesita está en la **segunda**, aunque la primera te ponga uno delante
con un botón de copiar que parece justo lo que buscabas. Ese es el motivo número uno de
«funcionaba ayer y hoy no entrega nada»: el de la primera consola caduca en 24 horas.

### Donde está cada pantalla · enlaces directos

**Guárdate esta tabla.** Los menús de las consolas de Meta cambian de nombre y de sitio cada pocos
meses, pero estas URL van directas a la pantalla y se han mantenido. Si llegas por los menús y no
encuentras un rótulo, pega la URL en la barra de direcciones.

| Lo que buscas | Enlace directo |
|---|---|
| Crear la app / tus apps | [developers.facebook.com/apps](https://developers.facebook.com/apps) |
| **Id del número** y lista blanca | tu app → *WhatsApp* → *Configuración de la API* (no tiene URL fija: depende del id de la app) |
| **Token permanente** · usuarios del sistema | [business.facebook.com/latest/settings/system-users](https://business.facebook.com/latest/settings/system-users) |
| **Plantillas de mensajes** | [business.facebook.com/latest/whatsapp_manager/message_templates](https://business.facebook.com/latest/whatsapp_manager/message_templates) |
| Números de la WABA | [business.facebook.com/latest/whatsapp_manager/phone_numbers](https://business.facebook.com/latest/whatsapp_manager/phone_numbers) |
| Comprobar un token | [developers.facebook.com/tools/debug/accesstoken](https://developers.facebook.com/tools/debug/accesstoken) |
| Verificación de la empresa | [business.facebook.com/latest/settings/security_center](https://business.facebook.com/latest/settings/security_center) |

> **Si un enlace te deja en una pantalla vacía o te pide elegir**, es que tienes **más de una
> cuenta de empresa** y has caído en la que no es. El selector está arriba a la izquierda, y la
> correcta es **la que tiene tu WABA colgada**. Equivocarse de cuenta de empresa es la causa de
> casi todos los «aquí no me aparece esa opción».

### El orden correcto, y lo que tarda cada cosa

Hacerlo en otro orden es posible, pero te obliga a volver atrás:

| # | Paso | Dónde | Tiempo |
|---|---|---|---|
| 1 | Crear la app y añadir WhatsApp | developers | 5 min |
| 2 | Número de prueba + lista blanca del destinatario | developers | 5 min |
| 3 | Token de usuario de sistema | business | 10 min, y es el que más se tuerce |
| 4 | Comprobar las credenciales desde la aplicación | el teléfono | 1 min |
| 5 | Dar de alta la plantilla y esperar aprobación | business | 10 min + **hasta 24 h** de revisión |
| 6 | *(solo para producción)* número propio y verificación de empresa | business | **días** |

Con los pasos 1 a 4 la aplicación ya entrega **dentro de la ventana de 24 h** de un destinatario
que te haya escrito. Para que entregue siempre hace falta el paso 5.

---

## Paso 0 · Lo que necesitas tener a mano

- Una cuenta de Facebook. No hace falta tener página ni perfil de empresa todavía.
- Un **correo** al que llegue la verificación.
- Un **teléfono móvil** para recibir un SMS de confirmación de la cuenta de Facebook, que no es
  el mismo trámite que el número de WhatsApp.

No necesitas tarjeta de crédito para el número de prueba. Sí la necesitarás el día que pases a
un número de producción y gastes por encima del tramo gratuito.

---

## Paso 1 · Crear la app en Meta for Developers

1. Entra en **[developers.facebook.com](https://developers.facebook.com)** y pulsa
   **Iniciar sesión**, con tu cuenta de Facebook.
2. Arriba a la derecha, **Mis aplicaciones** → **Crear aplicación**.
3. En *Nombre de la aplicación* pon lo que quieras —`SMStoWhats`, por ejemplo—; es un nombre
   interno que el destinatario nunca ve.
4. Cuando pregunte **«¿Qué quieres que haga tu aplicación?»**, elige **Otro**.
5. En el tipo de aplicación, elige **Empresa** (*Business*).
6. Si te pide asociar una **cuenta de empresa** (Business Portfolio) y no tienes ninguna, deja que
   te la cree. Ese es tu Business Manager, y lo vas a necesitar en el paso 3.
7. Crear.

Ya dentro de la app, en el panel de productos, busca **WhatsApp** y pulsa **Configurar**. Esto es
lo que crea tu **WABA** (*WhatsApp Business Account*), que es la cuenta de WhatsApp de empresa
colgada de tu cuenta de empresa. Verás una pantalla titulada **API Setup** o
**Configuración de la API**: es la pantalla a la que vas a volver varias veces.

---

## Paso 2 · El número

En esa misma pantalla de *Configuración de la API* verás un desplegable **«De»** / **«From»**.

> **La regla que no se puede deshacer:** un número solo puede estar **en un sitio a la vez**, o en
> una app de WhatsApp (la normal o la Business del Play Store) o en la Cloud API. Registrar tu
> número personal en la Cloud API te lo quita como WhatsApp normal. **Empieza siempre por el
> número de prueba.**

### Opción A — El número de prueba (empieza por aquí)

Meta te crea **uno solo por app**, gratis y al instante. Tiene dos límites, y los dos te vienen
bien al principio:

- Solo puede escribir a **5 destinatarios** que tú metas en una lista blanca.
- Los mensajes son gratis, pero el número **no es tuyo**: Meta lo puede retirar, y no sirve para
  producción.

**La lista blanca no es opcional y es el fallo silencioso más común.** Sin ella, la aplicación
hace todo bien, Meta contesta que ha aceptado el mensaje, y al destinatario no le llega nada:

1. En *Configuración de la API*, en el desplegable **«Para»** / **«To»**, pulsa
   **Gestionar lista de números de teléfono**.
2. Añade el número **con prefijo internacional** (`+34…`). Sin el `+` y el prefijo no vale.
3. A ese teléfono le llega **un código por WhatsApp**, y hay que **introducirlo en la consola**.
   Mientras no se confirme, el número está en la lista pero no recibe.
4. Repite por cada destinatario al que vayas a reenviar, **incluido tu propio móvil si es ahí
   donde vas a probar**.

### Opción B — Un número propio (para producción)

Pulsa **Añadir número de teléfono** y sigue el flujo: nombre a mostrar, categoría de empresa, y
verificación del número por SMS o llamada. Lo que conviene saber **antes** de empezar:

- **Ese número no puede tener WhatsApp registrado.** Si lo tiene, hay que borrar la cuenta desde
  la propia app (*Ajustes → Cuenta → Eliminar mi cuenta*) y esperar unos minutos. Desinstalar la
  app **no** basta: la cuenta sigue existiendo en los servidores de Meta.
- **El nombre a mostrar lo revisa una persona en Meta** y lo rechaza si no se parece al nombre
  real de la empresa, o si parece el nombre de una app. No pongas «SMStoWhats» aquí si tu empresa
  se llama otra cosa. Un rechazo no bloquea nada: se vuelve a enviar con otro nombre.
- **Los límites de mensajería empiezan bajos** (del orden de unos cientos de destinatarios
  distintos al día) y Meta los sube solos según el volumen y la calidad. Para un reenviador de SMS
  casi nunca importa.
- **La verificación de empresa** —documentación fiscal de la sociedad— es lo que desbloquea los
  límites altos y quitar restricciones. Es un trámite de **días**, lo revisa una persona, y es la
  parte más lenta de todo el montaje. No hace falta para empezar con el número de prueba.

> **La SIM que recibe los SMS puede ser este mismo número**, siempre que no tenga WhatsApp
> instalado y registrado. Lo que no puede hacer es reenviarse a sí mismo: Meta devuelve
> `131021` y la aplicación lo marca como error permanente.

### El dato que te llevas de aquí: el **id del número**

En *Configuración de la API*, debajo del número, la consola enseña dos cosas juntas y parecidas:

| Lo que ves | Qué es | ¿Va en la aplicación? |
|---|---|---|
| `+1 555 012 3456` | El número de teléfono | **No** |
| **Identificador del número de teléfono** · `123456789012345` | 15 dígitos | **Sí. Este** |

Es el error de configuración más frecuente de todos, y la aplicación lo detecta: si escribes un
número de teléfono en ese campo, Ajustes te responde *«Eso es un número de teléfono. Aquí va el
id del número, que es otro dato de la misma pantalla de la consola»*.

> No lo confundas tampoco con el **identificador de la cuenta de WhatsApp Business** (la WABA),
> que aparece en la misma pantalla y también son 15 dígitos. El que va en la aplicación es el
> **del número**.

---

## Paso 3 · El token permanente

Este es **el paso que más se atasca** de toda la guía, por dos motivos: el token que la consola te
pone delante no es el que sirve, y el que sirve se genera en la otra consola después de conceder
dos permisos que no están juntos.

### 3.1 · Por qué el token fácil no vale

**El token que la consola te enseña en *Configuración de la API* caduca en unas 24 horas.** La
aplicación funcionaría hoy y mañana estaría muda sin que nada hubiera cambiado, y el síntoma sería
un error `190` en cada reenvío. Vale para probar un envío a mano con `curl` y para nada más.

No hay forma de mirar un token y saber cuál es: los dos empiezan por `EAA…` y los dos son larguísimos.
Lo que sí se puede es **preguntárselo a Meta**, y conviene hacerlo antes de pegarlo en la aplicación:

1. Abre **[developers.facebook.com/tools/debug/accesstoken](https://developers.facebook.com/tools/debug/accesstoken)**
2. Pega el token y pulsa **Depurar**.
3. Mira dos campos:
   - **Caduca** (*Expires*) → tiene que decir **Nunca**. Si dice una fecha, es el temporal.
   - **Permisos** (*Scopes*) → tienen que estar **`whatsapp_business_messaging`**,
     **`whatsapp_business_management`** y **`business_management`**. El imprescindible para
     entregar es el primero.

Si cualquiera de los dos no cuadra, el token no es el bueno y no hace falta seguir probando en la
aplicación.

### 3.2 · Generar el que sí vale · dónde exactamente

El token que sirve es un **token de usuario de sistema**, y vive en **otra consola**: en la de la
empresa (`business.facebook.com`), no en la de desarrolladores donde acabas de sacar el id del
número. No hay ningún botón en la consola de desarrolladores que lo genere.

**Ve directo a esta URL**, que es la pantalla exacta:

> ## [business.facebook.com/latest/settings/system-users](https://business.facebook.com/latest/settings/system-users)

Si prefieres llegar por los menús: **business.facebook.com** → **Configuración del negocio**
(*Business settings*) → en la **barra lateral**, **Usuarios del sistema** (*System users*).

> **«Usuarios del sistema» está en el primer nivel de la barra lateral.** Durante años estuvo
> dentro de un grupo llamado *Usuarios*, y la mitad de los manuales que hay por internet aún lo
> cuentan así. Si buscas *Usuarios* y abres *Personas*, estás en el sitio equivocado: eso es para
> dar de alta a compañeros, no para esto.

**Si «Usuarios del sistema» no te aparece**, es una de estas tres, en este orden de probabilidad:

1. Estás en la **cuenta de empresa equivocada** → cámbiala arriba a la izquierda.
2. No eres **administrador** de esa cuenta de empresa → quien la creó tiene que darte el rol.
3. Estás en *Meta Business Suite* y no en *Configuración del negocio* → usa la URL de arriba.

#### Los cuatro clics

1. Arriba a la derecha, **Añadir** / **Add+**. Ponle un nombre (`smstowhats-token`) y créalo.
2. Selecciónalo en la lista y pulsa **Asignar activos** (*Assign assets*). **Son dos activos, y
   están en dos pestañas distintas del mismo diálogo** — es aquí donde se atasca casi todo el
   mundo:

   | Pestaña | Qué seleccionar | Qué activar |
   |---|---|---|
   | **Aplicaciones** (*Apps*) | la app del paso 1 | **Gestionar la aplicación** (*Manage app*), bajo **Control total** |
   | **Cuentas de WhatsApp** (*WhatsApp accounts*) | tu WABA | **Gestionar cuentas de WhatsApp Business**, bajo **Control total** |

   Y pulsa **Asignar activos** para confirmar. Si dejas el diálogo sin confirmar, no se guarda
   nada y el token saldrá inútil.

3. Con el usuario del sistema seleccionado, **Generar token** (*Generate token*). Elige la **app**
   del paso 1 y, en **caducidad del token**, **Nunca** (*Never*).
4. Marca **los tres permisos** que pide la documentación de Meta:

   - **`whatsapp_business_messaging`** — enviar mensajes. Sin este no entrega nada.
   - **`whatsapp_business_management`** — leer la WABA, los números y las plantillas.
   - **`business_management`** — el que la guía oficial añade además de los dos anteriores.

   Si la lista no te ofrece los `whatsapp_*`, vuelve al punto 2: falta el activo de la WABA.

5. Genera y **copia el token ahora mismo**. **Es la única vez que te lo enseña**: esa pantalla no
   se puede volver a abrir. Si lo pierdes no se rompe nada —vuelves a *Generar token* y sacas
   otro—, pero el anterior deja de servirte porque no lo tienes.

Pégalo en **SMStoWhats → Ajustes → Cuenta de WhatsApp → Token de acceso**. Es una cadena larguísima
que empieza por `EAA…`; cópiala entera y sin espacios al principio ni al final.

### 3.3 · Comprobarlo sin salir de la consola

Antes de ir al teléfono, con el token y el id del número ya en la mano:

```bash
curl "https://graph.facebook.com/v22.0/<ID_DEL_NUMERO>?fields=display_phone_number,verified_name,quality_rating"   -H "Authorization: Bearer <TOKEN>"
```

Si contesta con el número y el nombre, las dos credenciales están bien y encajan entre sí. Si
contesta `190`, el token; si contesta `100`, el id. Es la misma pregunta que hace el botón
*Comprobar las credenciales* de la aplicación, por si prefieres hacerlo allí — y `v22.0` es la
versión de la Graph API que usa la aplicación (`WhatsAppSender.API`).

### 3.4 · «Permanente» no es «inmortal»

El token se cae si alguien revoca los permisos del usuario del sistema, si lo regeneras, si
sacan al usuario de la empresa, o si Meta lo invalida por un cambio en la cuenta. Cuando pasa, el
síntoma es un error `190` en cada reenvío, y el **Panel de la aplicación lo avisa en rojo** por
encima de todo lo demás. No te quedas sin saberlo.

El token no viaja en la copia de seguridad de la aplicación, a propósito: el fichero es JSON en
claro y el token es permiso para escribir en nombre de ese número.

---

## Paso 4 · Comprobar que las dos credenciales valen

Ya puedes dejar la consola. En el teléfono:

1. Abre SMStoWhats → **Ajustes** → **Cuenta de WhatsApp**.
2. Pega el **token de acceso** y el **id del número**.
3. Pulsa **Comprobar las credenciales**.

La aplicación pregunta a Meta y te contesta con **el nombre verificado, el número y la calidad**
que Meta le da a ese número. Es la comprobación que conviene hacer antes de seguir, porque te dice
*cuál* número es: si te has equivocado de id y has puesto el de otra app, aquí lo ves.

| Lo que responde | Qué significa |
|---|---|
| ✓ *Las credenciales sirven · Nombre · +34…* | Listo. Y confirma que es el número que esperabas |
| ✗ *Meta no acepta estas credenciales* | Token caducado o revocado (`190`), o id equivocado (`100`) |
| *No se pudo comprobar* | No hay red. No es un problema de credenciales |

La **calidad del número** (*quality rating*) es verde, amarilla o roja, y merece una mirada de vez
en cuando: es lo que Meta usa para limitar o bloquear un número por denuncias de los
destinatarios. Es el riesgo real de mandar mensajes a quien no los ha pedido.

---

## Paso 5 · La plantilla, que es el camino principal

**Esto no es opcional, y es lo contrario de lo que uno supondría.** La Cloud API solo deja mandar
texto libre durante las **24 horas** siguientes a que el destinatario escriba al número emisor.
Un SMS llega cuando llega, no cuando al destinatario le apetece escribirte, así que **sin
plantilla la aplicación solo entrega de vez en cuando**, por casualidad.

> **La plantilla se configura en dos sitios distintos, y hacen falta los dos.** Es la causa de la
> mitad de las vueltas en este paso:
>
> | Dónde | Qué se hace | Apartado |
> |---|---|---|
> | **En la consola de Meta** | Darla de alta y esperar a que la aprueben | **5.2** |
> | **En SMStoWhats → Ajustes → Entrega** | Escribir su **nombre** y su **idioma** | **5.4** |
>
> Darla de alta en Meta no basta: la aplicación no la descubre sola, hay que decirle cómo se llama.
> Y escribirla en Ajustes sin haberla dado de alta tampoco: cada envío fallará con un `132xxx`.

### 5.1 · Copia el cuerpo exacto

En la aplicación: **Ajustes → Entrega → Copiar el cuerpo**. Es este, y hay que darlo de alta
**tal cual, con sus líneas en blanco**:

```
Reenvío de SMS

{{1}}

— SMStoWhats
```

Las tres propiedades de ese cuerpo son deliberadas y **cambiarlo rompe cosas**:

- **Los saltos de línea están en la plantilla, no en la variable.** Meta rechaza un salto de
  línea, un tabulador o más de cuatro espacios seguidos *dentro* de `{{1}}`: devuelve `132007` y
  el reenvío muere. La aplicación aplana el texto del SMS por eso.
- **No empieza ni acaba con la variable**, que Meta no admite. De ahí el texto de delante y de
  detrás.
- **Una sola variable.** Remitente, fecha y texto en tres huecos maquetaría mejor y crearía una
  segunda vía de composición del mensaje que ignoraría la plantilla de cada regla.

### 5.2 · Darla de alta en Meta · dónde exactamente

Las plantillas no están en la consola de desarrolladores: están en el **WhatsApp Manager**, dentro
de la consola de la empresa. **Ve directo a esta URL**, que es la pantalla exacta:

> ## [business.facebook.com/latest/whatsapp_manager/message_templates](https://business.facebook.com/latest/whatsapp_manager/message_templates)

Por los menús: **business.facebook.com** → **WhatsApp Manager** → **Plantillas de mensajes**
(*Message templates*) → **Crear plantilla** (*Create template*).

> **Si no ves *WhatsApp Manager*** o la lista sale vacía, es la cuenta de empresa equivocada:
> cámbiala arriba a la izquierda. Es la misma confusión del paso 3, y la WABA solo cuelga de una.

Luego:

1. Pulsa **Crear plantilla**.
2. **Categoría: `Utility`** (*Utilidad*). No `Marketing`: las de utilidad se aprueban antes, cuestan
   menos por mensaje y no cuentan como publicidad. Si eliges mal, Meta puede recategorizarla por su
   cuenta y cambiarte el precio.
3. **Nombre:** minúsculas, números y guiones bajos; sin espacios, tildes ni mayúsculas.
   `sms_reenviado` sirve. **Apúntalo tal cual**, porque es lo que va en Ajustes.
4. **Idioma:** el que vayas a poner en Ajustes. Y aquí hay una trampa:
   > **`es` y `es_ES` son dos plantillas distintas para Meta.** No es «lo mismo pero más
   > específico»: si das de alta `es_ES` y en Ajustes escribes `es`, cada envío falla con un error
   > de la familia `132xxx` diciendo que la plantilla no existe. Elige uno y usa **exactamente**
   > ese en los dos sitios.
5. En **Cuerpo**, pega el texto del punto 5.1. **No añadas encabezado, pie ni botones**: no hacen
   falta y cada uno es una cosa más que puede provocar un rechazo.
6. Meta te pedirá un **ejemplo** para `{{1}}`. Ponlo **en una sola línea**, corto y verosímil:
   `+34600112233 · 17/10/2026 10:42 · Tu codigo es 4821`. Un ejemplo con salto de línea se rechaza,
   y un ejemplo que parezca publicidad hace que te recategoricen a `Marketing`.
7. Enviar a revisión.

### 5.3 · Mientras se revisa, y si te la rechazan

La plantilla tiene tres estados, y se ven en la misma lista de *Plantillas de mensajes*:

| Estado en WhatsApp Manager | Qué significa |
|---|---|
| **En revisión** (*In-Review*) | No se puede usar todavía. Las de `Utility` suelen tardar **menos de media hora**, pero Meta se reserva **hasta 24 h** |
| **Activa · calidad alta** (*Active – High Quality*) | Lista, y sin quejas de los destinatarios. Es el estado normal |
| **Activa · calidad media o baja** | Sigue entregando, pero Meta ya ha visto quejas o mensajes sin leer. Es el aviso previo a lo siguiente |
| **En pausa** (*Paused*) | **Deja de entregar temporalmente** por quejas de los destinatarios |
| **Deshabilitada** (*Disabled*) | **Deja de entregar**, y no se recupera. Hay que crear otra |
| **Rechazada** (*Rejected*) | Meta dice el motivo al abrirla. Se corrige y se vuelve a enviar, o se crea otra con otro nombre |

Solo la que esté **Activa** entrega. Y esa escala de calidad no es decorativa: es el riesgo real de
reenviar mensajes a quien no los ha pedido, porque lo que la mueve son las quejas de quien los
recibe.

Los rechazos que de verdad pasan con una plantilla como esta:

- **Variable al principio o al final del cuerpo.** Meta no lo admite. El cuerpo del punto 5.1 ya
  lleva texto delante y detrás justo por eso: no se lo quites.
- **Parámetros sin ejemplo**, o con un ejemplo que no se parece a lo que vas a mandar.
- **Categoría equivocada**: un cuerpo que suena a promoción no pasa como `Utility`.

> **Editar una plantilla aprobada la vuelve a poner en revisión**, y mientras está en revisión no
> entrega. Si tienes que cambiar el cuerpo en una instalación que ya funciona, crea **otra** con
> otro nombre, espera a que se apruebe, y entonces cambia el nombre en Ajustes.

### 5.4 · Escríbela en la app

**Ajustes → Entrega** → el **nombre** y el **idioma**, exactamente como los diste de alta —sin
espacios de más al pegar—. Deja el modo en **Automático**: intenta texto libre, que es gratis, y
salta a plantilla si la ventana está cerrada, dentro del mismo intento.

Para comprobar que los tres datos encajan, manda un SMS de prueba al teléfono y mira los **Logs**:
un `132xxx` ahí significa que el nombre o el idioma no coinciden con lo que hay dado de alta, y es
lo único que falla con la plantilla una vez aprobada.

> **Dentro de la plantilla caben 978 caracteres**, no los 4.096 del texto libre. Lo que pase de
> ahí se entrega en varios mensajes **y cada uno se factura**.

---

## Paso 6 · El dinero, antes de que llegue la factura

Hay dos tramos y conviene tenerlos claros desde el principio:

- Los primeros **1.000 mensajes de servicio** (texto libre) al mes por número son **gratis**.
- **Cada mensaje de plantilla se factura**, desde el primero.

Mil al mes son **treinta y tres al día**. Esa es la cifra con la que medir si tu caso cabe en el
tramo gratuito. Y lo que la mueve es cuántos SMS reenvías, no cuántas reglas tienes: una regla con
`continuar` que entrega a tres personas gasta tres mensajes por cada SMS.

La aplicación trae dos topes, y miden cosas distintas:

| Tope | Por defecto | Qué corta |
|---|---|---|
| **Reenvíos por minuto** | 10 | Una ráfaga: muchos SMS en el mismo minuto |
| **Mensajes al mes** | 900 | La factura. Por debajo del tramo gratuito de Meta |

Los 900 están puestos a propósito **por debajo de los 1.000 de Meta**, así que una instalación
que nadie toque no puede llegar a facturar nada. Un `0` desactiva el tope.

**El Panel enseña el contador del mes**, separando los mensajes de servicio de los de plantilla.
Es la única cifra de esta aplicación que cuesta dinero, y está ahí y no enterrada en Ajustes
porque, si no, el primer aviso de haberse pasado sería el recibo.

Lo que se descarta por tope se registra en los Logs como **límite alcanzado**. No se pierde en
silencio: queda escrito que un tope dijo que no.

---

## Lista de comprobación final

Antes de dar por montada la cuenta. Cada línea es un sitio donde esto se queda a medias sin
decírtelo:

**El producto**

- [ ] Lo que he montado es la **Cloud API**, y **no** he instalado la app «WhatsApp Business» en
      el número

**El número**

- [ ] Hay un número en *Configuración de la API*, y **no tiene WhatsApp registrado**
- [ ] Si es el número de prueba: el destinatario está en la lista blanca **y confirmó el código**
      que le llegó por WhatsApp
- [ ] Tengo el **id del número** (15 dígitos), no el número de teléfono ni el id de la WABA

**El token**

- [ ] Es de **usuario de sistema**, sacado de `business.facebook.com`, no el de la consola de
      desarrolladores
- [ ] El usuario del sistema tiene **los dos activos**, confirmados con *Asignar activos*: la app
      (*Gestionar la aplicación*) y la WABA (*Gestionar cuentas de WhatsApp Business*), las dos
      bajo **Control total**
- [ ] El depurador de tokens dice **Caduca: Nunca** y enseña los tres permisos
      `whatsapp_business_messaging`, `whatsapp_business_management` y `business_management`

**La plantilla**

- [ ] Está **Activa**, no *En revisión*
- [ ] El cuerpo es **exactamente** el del punto 5.1, con sus líneas en blanco
- [ ] La he dado de alta **en Meta** (5.2) **y** he escrito su nombre e idioma **en Ajustes →
      Entrega** (5.4). Hacen falta los dos sitios
- [ ] El **nombre** y el **idioma** de Ajustes coinciden carácter por carácter con los de Meta
      (`es` **no** es `es_ES`)

**Y antes de dejarlo funcionando**

- [ ] **Comprobar las credenciales** en la aplicación responde con el nombre y el número que
      esperaba
- [ ] He mandado un SMS de prueba y ha llegado a WhatsApp
- [ ] Sé cuántos mensajes al mes voy a mandar y si eso cabe en los 1.000 gratis

---

## Si algo no entrega

Los Logs de la aplicación guardan el **código de error de Meta** en cada fallo, y es lo que hay
que buscar. Los que de verdad se ven:

| Código | Qué pasa | Qué hacer |
|---|---|---|
| `190` · `0` · `3` | El token no vale: caducado o revocado | Generar otro de usuario de sistema, paso 3 |
| `100` | El **id del número** está mal | Es el `Phone number ID`, no el teléfono. Paso 2 |
| `131047` | La ventana de 24 h está cerrada | Normal. En modo *Automático* salta a plantilla solo |
| `131026` | Ese destinatario no tiene WhatsApp | Revisar el destino de la regla |
| `131021` | El emisor y el destinatario son el mismo número | Un número no puede reenviarse a sí mismo |
| `132000`…`132016` | La plantilla y sus parámetros no encajan | Nombre, idioma o cuerpo. Paso 5 |
| `131056` · `130429` | Demasiados mensajes demasiado rápido | Bajar el tope por minuto. Se reintenta solo |

**El caso sin código de error**, que es el que más desespera: la aplicación dice *entregado*, los
Logs no tienen ningún error, y a WhatsApp no llega nada. Si estás con el **número de prueba**, es
la lista blanca: el destinatario no está o no confirmó el código. Meta acepta el mensaje y lo
descarta sin avisar. Paso 2.

Tres avisos más que no son errores de Meta y que explican la mayoría de los «no recibo nada»:

- **Una aplicación recién instalada que nadie ha abierto no recibe ningún SMS.** Android no le
  entrega broadcasts hasta que alguien la lanza una vez desde el icono.
- **Sin inicio automático**, en Xiaomi, Huawei, Oppo y Vivo, tras reiniciar el teléfono no
  arranca nada. Ajustes tiene el botón que abre esa pantalla del sistema.

---

## Lo que esta guía no cubre a propósito

- **Webhooks.** La Cloud API puede avisar de mensajes entrantes y de confirmaciones de entrega,
  y para eso haría falta un servidor público. SMStoWhats **no levanta ninguno**: solo manda, y se
  queda con la respuesta HTTP de cada envío. No hay nada que configurar aquí.
- **Grupos.** La aplicación entrega solo a personas, por decisión. No hay ningún id de grupo que
  escribir en una regla.
- **Coexistence**, el flujo que permite tener un número a la vez en la Cloud API y en la app de
  WhatsApp Business. Se puede usar, pero añade condiciones y no hace falta para este caso.

Y un recordatorio que está también en «Acerca de» dentro de la aplicación, porque importa más que
todo lo anterior: **el cuerpo de los SMS que reenvíes sale a internet y pasa por los servidores
de Meta**, desde una cuenta de empresa registrada a tu nombre. Para Meta y para quien lo recibe,
el remitente eres tú. El que mandó el SMS original no lo sabe y no lo ha consentido.
