// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.telegram

/**
 * Qué puede escribirse en el destino de una regla, ahora que un destino es un chat.
 *
 * Esto sustituye a `NormalizadorTelefono`, que comparaba números de teléfono. La sustitución no
 * es una traducción: **las dos preguntas que aquel contestaba aquí no existen**. No se pueden
 * comparar dos destinos para detectar un bucle, porque un chat de Telegram no vuelve nunca como
 * SMS; y no hay «destino inalcanzable» que se pueda deducir de la forma, porque un `chat_id`
 * perfectamente formado puede no existir y eso solo lo sabe Telegram.
 *
 * Queda una sola pregunta: ¿esto tiene forma de destino?
 *
 * ## Las tres formas que acepta el Bot API
 * | Forma | Ejemplo | Qué es |
 * |-------|---------|--------|
 * | Id positivo | `123456789` | Una persona |
 * | Id negativo | `-1001234567890` | Un grupo o un canal |
 * | `@usuario` | `@mi_canal` | Un canal **público** |
 *
 * El id negativo es el que más sorprende y el que más se escribe mal: los grupos tienen id
 * negativo, y los supergrupos y canales empiezan por `-100`. Quitarle el signo al copiarlo
 * manda el mensaje a una persona que no tiene nada que ver, o a ninguna parte.
 *
 * ## Por qué `@usuario` solo vale para canales públicos
 * Un bot no puede escribir a una persona por su `@usuario`: necesita su `chat_id` numérico, que
 * solo aparece cuando esa persona le escribe primero. Aceptar aquí un `@alguien` de una persona
 * daría un `400 Bad Request: chat not found` que no explica nada. El texto de ayuda de la
 * pantalla lo dice, y el descubrimiento de chats es la forma de no tener que saberlo.
 */
object DestinoDeTelegram {

    /** true si [destino] tiene forma de `chat_id` o de `@usuario` de canal. */
    fun pareceValido(destino: String): Boolean {
        val limpio = destino.trim()
        return ID.matches(limpio) || USUARIO.matches(limpio)
    }

    /** El destino tal como debe guardarse: sin espacios alrededor. */
    fun normalizar(destino: String): String = destino.trim()

    /** true si [destino] es un `@usuario` y no un id numérico. */
    fun esUsuario(destino: String): Boolean = USUARIO.matches(destino.trim())

    /**
     * true si [destino] es un chat de Telegram **y no puede ser otra cosa**: un id negativo o
     * un `@usuario`.
     *
     * Existe para la detección cruzada del editor de reglas. En SMSRelay el mismo campo de
     * destino admite un teléfono o un chat según el canal elegido, y el error más probable de la
     * aplicación es pegar un `-1001234567890` con el canal WhatsApp marcado. Decir entonces «no
     * tiene forma de teléfono» es verdad y no ayuda; decir «esto es un chat de Telegram: cambia
     * el canal» lo resuelve.
     *
     * Un id **positivo** no cuenta, por la misma ambigüedad que explica [pareceTelefono]:
     * `600112233` es a la vez un chat de persona y un móvil escrito sin prefijo, y en el canal
     * WhatsApp gana la segunda lectura, que tiene su propio mensaje.
     */
    fun esInequivocamenteChat(destino: String): Boolean {
        val limpio = destino.trim()
        return pareceValido(limpio) && (limpio.startsWith("-") || esUsuario(limpio))
    }

    /**
     * true si [destino] parece un número de teléfono y no un destino de Telegram.
     *
     * Existe para dar un mensaje de error **útil** en el caso más probable de todos: quien tiene
     * el canal Telegram marcado y escribe `+34600112233`, porque el destino que tenía en mente
     * era de WhatsApp o de SMS. Decirle «no tiene forma de chat» es verdad y no sirve de nada;
     * decirle «esto es un teléfono: cambia el canal, o elige un chat» le resuelve el problema.
     *
     * ## La ambigüedad, y por qué se resuelve a favor del chat
     * Un móvil español escrito a pelo —`600112233`— son nueve dígitos seguidos, y eso es **a la
     * vez** un teléfono y un `chat_id` perfectamente legítimo: los de persona andan por ahí de
     * longitud. No hay forma de distinguirlos, así que hay que elegir, y gana el chat: rechazar
     * un `chat_id` válido rompería una configuración correcta con un mensaje de error que además
     * sería falso. Esta función solo reconoce lo que **no puede** ser un chat: lo que lleva un
     * `+`, espacios, paréntesis o guiones.
     *
     * Que un `600112233` destinado a ser un teléfono pase por chat no es grave: Telegram
     * contestará `400 chat not found`, el reenvío se cerrará como fallido permanente y el motivo
     * quedará en el registro. Al revés —rechazar el chat de alguien— no habría forma de
     * arreglarlo desde la aplicación.
     */
    fun pareceTelefono(destino: String): Boolean {
        val limpio = destino.trim()
        if (pareceValido(limpio)) return false
        val digitos = limpio.count { it.isDigit() }
        return digitos >= 9 && limpio.all { it.isDigit() || it in " +-()" }
    }

    /** Un id de chat: opcionalmente negativo, y nada más que dígitos. */
    private val ID = Regex("""^-?\d{1,20}$""")

    /** Un `@usuario` de Telegram: letras, dígitos y guiones bajos, de 5 a 32 caracteres. */
    private val USUARIO = Regex("""^@[A-Za-z][A-Za-z0-9_]{4,31}$""")
}
