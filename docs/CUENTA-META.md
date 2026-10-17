# Montar la cuenta de Meta, paso a paso

Esta guía cubre **todo lo que hay que hacer fuera de la aplicación** para que SMStoWhats pueda
entregar en WhatsApp. Es la parte larga del montaje: la aplicación se instala en dos minutos y
esto lleva entre media hora y un par de días, según si Meta te pide verificar la empresa.

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

En esa misma pantalla de *API Setup* verás un desplegable **«From»** o **«Número de teléfono
remitente»**.

### Opción A — El número de prueba (recomendada para empezar)

Meta te crea uno solo, gratis. Tiene dos límites y los dos te vienen bien al principio:

- Solo puede escribir a **5 destinatarios** que tú metas en una lista blanca.
- Los mensajes de plantilla desde el número de prueba son gratis, pero el número **no es tuyo**:
  Meta lo puede retirar y no sirve para producción.

Para añadir destinatarios: en *API Setup*, en el desplegable **«To»** o **«Destinatario»**, pulsa
**Gestionar lista de números de teléfono** y añade cada número **con prefijo internacional**. A
cada uno le llega un código por WhatsApp que hay que confirmar. **Hazlo ahora con el número al
que vas a reenviar**: sin ese paso, la Cloud API no le entregará nada y el error no dirá nada
sobre listas blancas.

### Opción B — Un número propio

Pulsa **Añadir número de teléfono** y sigue el flujo: nombre a mostrar, categoría de empresa,
y verificación del número por SMS o llamada.

Tres cosas que conviene saber antes:

- **Ese número no puede tener WhatsApp registrado.** Si lo tiene, bórralo de WhatsApp primero
  (Ajustes → Cuenta → Eliminar mi cuenta) y espera unos minutos.
- **El nombre a mostrar lo revisa Meta** y lo rechaza si no se parece al nombre real de la
  empresa. No pongas «SMStoWhats» aquí si tu empresa se llama otra cosa.
- Para mandar a más de unos pocos destinatarios y para subir los límites te pedirá
  **verificación de empresa**: documentación fiscal, y eso ya es un trámite de días.

> **La SIM que recibe los SMS puede ser este mismo número**, siempre que no tenga WhatsApp
> instalado y registrado. Lo que no puede hacer es reenviarse a sí mismo: Meta devuelve
> `131021` y la aplicación lo marca como error permanente.

### El dato que te llevas de aquí

En *API Setup*, debajo del número, la consola enseña **«Phone number ID»** (*Identificador del
número de teléfono*): unos **15 dígitos**. **Ese** es el dato que va en la aplicación, y no el
número de teléfono, que aparece justo al lado.

Es el error de configuración más frecuente de todos, y la aplicación lo detecta: si escribes un
número de teléfono en ese campo, Ajustes te responde *«Eso es un número de teléfono. Aquí va el
id del número, que es otro dato de la misma pantalla de la consola»*.

---

## Paso 3 · El token permanente

**El token que la consola te enseña en *API Setup* no sirve.** Caduca en unas 24 horas, así que
la aplicación funcionaría hoy y mañana estaría muda, sin que nada hubiera cambiado. Vale para
probar un envío a mano con `curl` y para nada más.

El que sirve es un **token de usuario de sistema** con caducidad **Nunca**, y se saca del
Business Manager, no de la consola de desarrolladores:

1. Ve a **[business.facebook.com/settings](https://business.facebook.com/settings)** y elige tu
   cuenta de empresa.
2. En la columna izquierda: **Usuarios** → **Usuarios del sistema** → **Añadir**.
3. Ponle un nombre (`smstowhats-token`) y dale el rol **Empleado**. No necesita ser administrador.
4. Con el usuario del sistema seleccionado, pulsa **Añadir activos** y concédele acceso a **dos**
   cosas, que es donde se atasca casi todo el mundo:
   - En **Aplicaciones**, la app del paso 1, con permiso **Gestionar la aplicación**.
   - En **Cuentas de WhatsApp**, tu WABA, con permiso **Control total**.
5. Pulsa **Generar nuevo token**.
6. Elige la app del paso 1, y en **Caducidad** selecciona **Nunca**.
7. Marca los permisos **`whatsapp_business_messaging`** y **`whatsapp_business_management`**.
8. Genera, y **copia el token ahora**: es la única vez que te lo enseña. Si lo pierdes, hay que
   generar otro.

> **«Permanente» no es «inmortal».** El token se cae si alguien revoca los permisos del usuario
> del sistema, si lo regeneras, o si Meta lo invalida por un cambio en la cuenta. Cuando pasa, el
> síntoma es un error `190` en cada reenvío, y el **Panel de la aplicación lo avisa en rojo** por
> encima de todo lo demás. No te quedas sin saberlo.

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

### 5.2 · Darla de alta

1. En **[business.facebook.com](https://business.facebook.com)** → **WhatsApp Manager** →
   **Plantillas de mensajes** → **Crear plantilla**.
2. **Categoría: `Utility`** (*Utilidad*). No `Marketing`: las de utilidad se aprueban antes y
   cuestan menos por mensaje.
3. **Nombre:** en minúsculas, con números y guiones bajos. `sms_reenviado` sirve. Apúntalo tal
   cual, porque es lo que va en Ajustes.
4. **Idioma:** el que vayas a poner en Ajustes. `es` o `es_ES`; si no coinciden, cada envío falla.
5. En **Cuerpo**, pega el texto del punto 5.1. No añadas encabezado, pie ni botones.
6. Meta te pedirá un **ejemplo** para `{{1}}`: pon algo corto y verosímil, en una sola línea, como
   `+34600112233 · 17/10/2026 10:42 · Tu codigo es 4821`.
7. Enviar a revisión.

Las de `Utility` suelen aprobarse **en menos de media hora**. Mientras esté *en revisión* no se
puede usar.

### 5.3 · Escríbela en la app

**Ajustes → Entrega** → el **nombre** y el **idioma**, exactamente como los diste de alta. Deja el
modo en **Automático**: intenta texto libre, que es gratis, y salta a plantilla si la ventana está
cerrada, dentro del mismo intento.

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

Antes de dar por montada la cuenta:

- [ ] La app existe en Meta for Developers y tiene el producto WhatsApp
- [ ] Hay un número, y **no tiene WhatsApp registrado**
- [ ] Si es el número de prueba: **el destinatario está en la lista blanca y confirmó el código**
- [ ] Tengo el **Phone number ID** (15 dígitos), no el número de teléfono
- [ ] El token es de **usuario de sistema**, caducidad **Nunca**, con los dos permisos
- [ ] El usuario del sistema tiene acceso **a la app y a la WABA**
- [ ] **Comprobar las credenciales** responde con el nombre y el número que esperaba
- [ ] La plantilla está **aprobada**, con el cuerpo exacto, y su nombre e idioma están en Ajustes
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

Dos avisos que no son errores de Meta y que explican la mayoría de los «no recibo nada»:

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
