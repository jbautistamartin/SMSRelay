// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.whatsapp

/**
 * Lo que se puede decir de un token de acceso **sin** preguntarle a Meta.
 *
 * La única forma de saber si un token sirve es usarlo, y eso es lo que hace el botón de
 * comprobación de Ajustes. Pero mirar la forma antes cuesta nada y atrapa el error más frecuente
 * de todos: pegarlo con un espacio o un salto de línea delante, o pegar media frase de la consola
 * en lugar del token.
 *
 * ## Por qué esta validación es más laxa que la de Telegram
 * Un token de @BotFather tenía una forma reconocible —`<id numérico>:<cadena>`— y se podía
 * exigir. Un token de Graph **no tiene forma pública**: es una cadena opaca, larga, que Meta
 * puede cambiar de estructura cuando quiera. Los de usuario de sistema empiezan hoy por `EAA`,
 * y escribir eso aquí sería convertir un detalle de implementación de Meta en una regla de
 * validación que algún día rechazaría un token legítimo.
 *
 * Así que esto comprueba lo único que es seguro: que sea largo y que no tenga espacios. Un
 * validador estricto que rechazara un token bueno sería peor que no validar nada.
 *
 * ## Por qué no hay un «resumen» del token
 * `TokenDeBot` tenía `idDelBot()`, que extraía la parte anterior a los dos puntos para poder
 * enseñar *qué* token estaba configurado sin enseñarlo: esa parte no es secreta, la lleva
 * cualquier mensaje del bot. Un token de Graph no tiene ninguna parte así —es secreto de
 * principio a fin— y recortarle unos caracteres para pintarlos en pantalla sería filtrar un
 * trozo de credencial a cambio de nada.
 *
 * Lo que da esa tranquilidad aquí es [ComprobadorDeCuenta], que pregunta a Meta y contesta con
 * el nombre verificado y el número de teléfono. Es mejor información, y no sale del token.
 */
object TokenDeAcceso {

    /**
     * true si [token] tiene forma de token de acceso.
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
     * Recorta los espacios y saltos de línea, que es casi siempre lo que sobra al pegarlo desde
     * la consola de Meta, y lo que convertiría la cabecera `Authorization` en una cabecera
     * inválida.
     */
    fun normalizar(token: String): String = token.trim()

    /**
     * Una cadena larga sin espacios.
     *
     * El mínimo de 40 caracteres deja pasar cualquier token real —los de usuario de sistema
     * pasan de 180— y atrapa el error de pegar una palabra o un trozo. El juego de caracteres
     * es el de base64 URL-safe más el punto, que es lo que Meta usa hoy.
     */
    private val FORMA = Regex("""^[A-Za-z0-9_.\-]{40,}$""")
}
