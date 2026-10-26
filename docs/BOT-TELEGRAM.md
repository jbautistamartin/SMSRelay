# Preparar el bot de Telegram

Esta guía cubre **lo que hay que hacer fuera de la aplicación** para que SMSRelay entregue por
Telegram: crear el bot, conseguir que el chat de destino exista y encontrarlo. Es mucho más corta
que la de WhatsApp (`docs/CUENTA-WHATSAPP-BUSINESS.md`) porque Telegram no pide ni empresa, ni
verificación, ni plantillas, ni factura: un bot se crea en un minuto y entregar es gratis.

Lo que sigue se trajo del README de SMStoTelegram, la aplicación que entregaba solo por Telegram
y de la que SMSRelay heredó el canal.

---

## 1. Crear el bot con @BotFather

1. En Telegram, abre un chat con **@BotFather** (el de la marca de verificación azul).
2. Escribe `/newbot`.
3. Te pide un **nombre** —el que verán quienes reciban los avisos, por ejemplo *Avisos de casa*— y
   un **nombre de usuario**, que tiene que terminar en `bot` (`avisos_casa_bot`).
4. Responde con el **token**: algo como `123456789:AAH…` — números, dos puntos y una cadena larga.

**El token es la llave del bot.** Quien lo tenga puede escribir en su nombre y leer lo que le
manden. No lo pegues en ningún sitio más que en la aplicación, y si se te escapa, `/revoke` en
@BotFather lo invalida y te da otro.

## 2. Pegarlo en la aplicación

**Ajustes → Bot de Telegram → Token del bot**, y luego **Comprobar el token**. Si sirve, la pantalla
dice el nombre y el `@usuario` del bot; si no, dice lo que contestó Telegram.

Mientras falte el token, o Telegram lo haya rechazado, el Panel lo dice en rojo, y la tarjeta
*Canales* de Ajustes marca Telegram como no disponible.

## 3. Que el chat de destino exista

**Un bot no puede escribir primero.** Hasta que alguien le escribe, para el bot ese chat no existe y
cualquier envío vuelve como `chat not found`.

| Destino | Qué hacer |
|---|---|
| **Una persona** | Que abra el bot (búscalo por su `@usuario`) y le mande `/start` |
| **Un grupo** | Añade el bot al grupo y escribe cualquier cosa en él |
| **Un canal** | Añade el bot como **administrador** del canal, con permiso para publicar |

## 4. Encontrar el chat al crear la regla

Nadie se sabe su `chat_id`: es un número que la aplicación de Telegram no enseña en ninguna
pantalla. Para eso está **Elegir de los chats del bot** en el editor de la regla, con el canal
Telegram elegido: pregunta al bot quién le ha escrito y ofrece la lista.

- **Telegram guarda esos mensajes 24 horas.** Un chat que escribió la semana pasada no sale en la
  lista. No es un fallo: que vuelva a escribir al bot y aparecerá.
- Un **canal público** no necesita nada de esto: vale su `@usuario` directamente, siempre que el
  bot sea administrador. Para una **persona**, en cambio, el `@usuario` **no sirve**: un bot
  necesita su `chat_id` numérico.
- Los `chat_id` de grupos y canales son **negativos** y empiezan por `-100`. El editor reconoce uno
  pegado con el canal WhatsApp o SMS elegido y te dice que cambies el canal.

Ejemplos de reglas:

```
# Solo los SMS del banco, al chat privado de una persona
Remitente: ^BANCO$          Mensaje: (vacío)       → Telegram · 123456789

# Todo lo que llegue de un número, a una persona y a un grupo
Remitente: ^\+34700        Mensaje: (vacío)        → Telegram · 123456789    [seguir evaluando]
Remitente: ^\+34700        Mensaje: (vacío)        → Telegram · -1001234567890

# Las alertas de la alarma, a un canal público del que el bot es administrador
Remitente: (?i)alarma       Mensaje: (vacío)        → Telegram · @avisos_de_casa
```

Los `chat_id` de los ejemplos son inventados.

## 5. Lo que conviene saber

- **Todos los avisos llegan del mismo bot**, así que deja `{telefono}` y `{fecha}` en la plantilla:
  sin ellos, cuatro avisos seguidos son cuatro mensajes indistinguibles.
- **Sin formato.** La aplicación no manda `parse_mode`: un SMS con `_`, `*` o `<` llega tal cual en
  lugar de volver como `can't parse entities`.
- **Límites.** Telegram admite del orden de un mensaje por segundo y por chat. Si lo pasas contesta
  con un `retry_after`, y el envío espera lo que pide antes de reintentar (hasta dos minutos por
  intento). Por encima de 4.096 caracteres el texto se trocea.
- **Privacidad.** Los mensajes pasan por los servidores de Telegram y se quedan en el chat; en un
  grupo o un canal los lee todo el que esté dentro, también quien entre después. El aviso completo
  está en *Acerca de*.
