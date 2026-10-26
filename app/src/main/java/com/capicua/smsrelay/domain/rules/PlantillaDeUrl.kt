// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.rules

import java.net.URI
import java.net.URLEncoder

/**
 * La plantilla de URL del canal API: el destino de una regla API, con los marcadores de
 * SMSGateway.
 *
 * Es **la pieza de seguridad del canal API**, y por eso es Kotlin puro y tiene tests propios.
 *
 * ## El problema que resuelve
 * En el destino de una regla no se resuelve ningún marcador, y la razón es una propiedad de
 * seguridad: el SMS de un tercero no debe decidir nunca adónde se manda nada. Una URL con
 * `{mensaje}` dentro parece romper eso, y no lo hace **con una condición**: los marcadores solo
 * pueden ir **después del servidor**, en la ruta o en la query, nunca en el esquema, el host ni
 * el puerto. Así un valor rellena un hueco, pero no elige a qué servidor se llama.
 *
 * La otra mitad de la condición es la codificación: cada valor se codifica entero con
 * [URLEncoder], así que no puede añadir un `/`, un `?`, un `&` ni un `#` que cambie la forma de
 * la URL. Un SMS que diga `x&admin=1` llega al servidor como el valor `x%26admin%3D1` de su
 * parámetro, no como un parámetro nuevo.
 *
 * ## Qué significa cada marcador aquí
 * - `{mensaje}` — el texto **ya compuesto por la plantilla de la regla**, no el cuerpo crudo del
 *   SMS: sigue habiendo una sola vía de composición, como en WhatsApp. La plantilla por defecto
 *   de una regla API es `{mensaje}`, así que una regla nueva manda lo mismo que SMSGateway.
 * - `{telefono}` — el remitente, tal como lo entrega la PDU.
 * - `{fecha}` — la fecha de recepción en **ISO-8601 UTC**. Es la única diferencia de significado
 *   de un marcador entre canales: en una plantilla de texto la lee una persona y va en hora
 *   local; aquí la lee una máquina, como en SMSGateway.
 */
object PlantillaDeUrl {

    const val MARCADOR_MENSAJE = "{mensaje}"
    const val MARCADOR_TELEFONO = "{telefono}"
    const val MARCADOR_FECHA = "{fecha}"

    private val MARCADORES = listOf(MARCADOR_MENSAJE, MARCADOR_TELEFONO, MARCADOR_FECHA)

    /**
     * Por qué [plantilla] no sirve, o null si sirve.
     *
     * Exige, por este orden: `http://` o `https://`; un servidor; que ningún marcador aparezca
     * antes de que empiece la ruta o la query; que lleve `{mensaje}`, porque sin él no se
     * entrega nada; y que, con los marcadores rellenos, sea una URL que Java sepa leer.
     */
    fun validar(plantilla: String): String? {
        val limpia = plantilla.trim()
        if (limpia.isEmpty()) return "La URL es obligatoria"

        val esquema = when {
            limpia.startsWith("https://", ignoreCase = true) -> "https://"
            limpia.startsWith("http://", ignoreCase = true) -> "http://"
            else -> return "La URL tiene que empezar por http:// o https://"
        }

        val autoridad = autoridadDe(limpia, esquema.length)
        if (autoridad.isEmpty()) return "Falta el servidor después de $esquema"

        if (MARCADORES.any { autoridad.contains(it) } || autoridad.contains('{')) {
            return "Los marcadores solo pueden ir en la ruta o en los parámetros, después del " +
                "servidor: un SMS no puede decidir a qué servidor se llama"
        }

        if (!limpia.contains(MARCADOR_MENSAJE)) {
            return "Falta {mensaje}: sin él la petición no lleva el SMS"
        }

        val ejemplo = resolver(limpia, mensaje = "x", telefono = "x", fechaIso = "x")
        return try {
            val uri = URI(ejemplo)
            if (uri.host.isNullOrBlank()) "El servidor de la URL no es válido" else null
        } catch (e: Exception) {
            "No es una URL válida: ${e.message}"
        }
    }

    fun esValida(plantilla: String): Boolean = validar(plantilla) == null

    /**
     * Rellena los marcadores de [plantilla], cada valor codificado entero.
     *
     * `{mensaje}` se sustituye **el último**, por la misma razón que en la plantilla de texto:
     * el cuerpo del SMS es contenido de terceros y no debe volver a escanearse. Aquí además ya
     * va codificado, así que unas llaves dentro de él no podrían formar un marcador, pero el
     * orden se mantiene igual en los dos sitios para que nadie tenga que pensar en ello.
     *
     * Los espacios van como `%20` y no como el `+` de [URLEncoder]: el `+` solo significa
     * espacio en la query, y un marcador puede estar en la ruta.
     */
    fun resolver(plantilla: String, mensaje: String, telefono: String, fechaIso: String): String =
        plantilla.trim()
            .replace(MARCADOR_TELEFONO, codificar(telefono))
            .replace(MARCADOR_FECHA, codificar(fechaIso))
            .replace(MARCADOR_MENSAJE, codificar(mensaje))

    /**
     * La URL sin la query, para el registro y los mensajes de error.
     *
     * La query es donde van el mensaje y, a menudo, una clave de la API. El servidor y la ruta
     * bastan para saber a quién se llamó.
     */
    fun sinQuery(url: String): String = url.substringBefore('?').substringBefore('#')

    private fun codificar(valor: String): String =
        URLEncoder.encode(valor, Charsets.UTF_8.name()).replace("+", "%20")

    /** Lo que va entre `esquema://` y el primer `/`, `?` o `#`: usuario, servidor y puerto. */
    private fun autoridadDe(url: String, desde: Int): String {
        val resto = url.substring(desde)
        val fin = resto.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (fin < 0) resto else resto.substring(0, fin)
    }
}
