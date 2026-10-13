// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.data.telegram

/**
 * Lo que se puede decir de un token **sin** preguntarle a Telegram.
 *
 * La única forma de saber si un token sirve es usarlo, y eso es lo que hace el botón de
 * validación de Ajustes. Pero comprobar la forma antes cuesta nada y atrapa el error más
 * frecuente de todos: pegar el token con un espacio delante, o pegar media línea del mensaje de
 * @BotFather en lugar del token. Decirlo al escribir es mucho mejor que descubrirlo cuando el
 * primer SMS se marque como fallido.
 *
 * Un token de @BotFather tiene la forma `<id numérico>:<cadena>`, por ejemplo
 * `123456789:AAEhBOweik6ad9r_QXZ2Ql3n1xWcCOPvj8s`. Ni el id ni la cadena tienen longitud fija
 * garantizada, así que la comprobación es **deliberadamente laxa**: rechaza lo que no puede ser
 * un token y no pretende certificar lo que sí. Un validador demasiado estricto que rechazara un
 * token legítimo del futuro sería peor que no validar nada.
 */
object TokenDeBot {

    /**
     * true si [token] tiene la forma de un token de bot.
     *
     * Un token **vacío es válido** aquí: significa «sin configurar», que es el estado inicial
     * legítimo de una instalación nueva. Quien necesite saber si se puede enviar pregunta a
     * `EnviadorDeMensajes.motivoNoDisponible()`, que es otra pregunta.
     */
    fun pareceValido(token: String): Boolean {
        val limpio = token.trim()
        return limpio.isEmpty() || FORMA.matches(limpio)
    }

    /**
     * El token tal como debe guardarse.
     *
     * Recorta los espacios, que es casi siempre lo que sobra al pegar desde Telegram, y lo que
     * convertiría la URL del Bot API en una URL inválida.
     */
    fun normalizar(token: String): String = token.trim()

    /**
     * El id del bot, que es la parte anterior a los dos puntos.
     *
     * No es secreto —lo lleva cualquier mensaje del bot— y sirve para enseñar *qué* token está
     * configurado sin enseñarlo. Null si el token no tiene forma de token.
     */
    fun idDelBot(token: String): String? =
        token.trim().substringBefore(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    /** `123456789:AAEh…` — números, dos puntos y una cadena suficientemente larga. */
    private val FORMA = Regex("""^\d{5,}:[A-Za-z0-9_-]{20,}$""")
}
