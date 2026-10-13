// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.data.telegram

import com.capicua.smstotelegram.domain.transport.ResultadoEnvio
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Traduce lo que contesta el Bot API a un [ResultadoEnvio].
 *
 * Es la pieza más importante del transporte y la única que se puede probar sin red, y por eso
 * está separada de quien hace la petición: [interpretarEnvio] es una función pura de
 * (código HTTP, cuerpo) a resultado, y `MapeoDeRespuestasTest` la cubre caso por caso.
 *
 * ## Por qué el reparto es este
 * Clasificar mal un error cuesta caro en las dos direcciones. Marcar como permanente algo
 * transitorio pierde un mensaje que habría llegado al segundo intento; marcar como transitorio
 * algo permanente gasta diez reintentos y diez líneas de log para acabar en el mismo sitio.
 *
 * | Respuesta | Resultado | Por qué |
 * |-----------|-----------|---------|
 * | `200` con `ok: true` | [ResultadoEnvio.Enviado] | Telegram tiene el mensaje |
 * | `429` | Transitorio **con espera** | Hay demasiado tráfico; la respuesta dice cuánto esperar |
 * | `5xx` | Transitorio | Avería del lado de Telegram |
 * | `400` | Permanente | Chat inexistente, texto vacío o demasiado largo: reintentar no lo arregla |
 * | `401` | Permanente | Token inválido o revocado. Es un fallo de **configuración** |
 * | `403` | Permanente | El bot está bloqueado o lo echaron del grupo. Nadie va a recibir nada |
 * | `404` | Permanente | La URL lleva el token dentro: un 404 aquí es un token mal escrito |
 * | Otro | Transitorio | Por prudencia: lo desconocido se reintenta, no se descarta |
 *
 * El `description` que devuelve Telegram se incorpora al motivo tal cual. Es texto en inglés y
 * poco bonito —«Bad Request: chat not found»—, pero es exactamente lo que hace falta leer en el
 * registro para entender qué pasó, y traducirlo a mano sería inventarse un diccionario que se
 * queda corto en cuanto Telegram añada un caso.
 */
object MapeoDeRespuestas {

    /**
     * Interpreta la respuesta de `sendMessage`.
     *
     * @param codigo código HTTP devuelto.
     * @param cuerpo cuerpo de la respuesta, o null si no se pudo leer.
     */
    fun interpretarEnvio(codigo: Int, cuerpo: String?): ResultadoEnvio {
        val respuesta = interpretarCuerpo(cuerpo)
        val detalle = respuesta?.descripcion?.takeIf { it.isNotBlank() }

        // Un 200 con ok:false no está documentado, pero si llegara, lo que manda es ok.
        if (codigo == 200) {
            return if (respuesta == null || respuesta.ok) {
                ResultadoEnvio.Enviado
            } else {
                ResultadoEnvio.ErrorPermanente(motivo(codigo, detalle))
            }
        }

        return when (codigo) {
            429 -> ResultadoEnvio.ErrorTransitorio(
                motivo = motivo(codigo, detalle ?: "demasiadas peticiones"),
                esperaSegundos = respuesta?.parametros?.esperarSegundos
            )

            400, 401, 403, 404 -> ResultadoEnvio.ErrorPermanente(motivo(codigo, detalle))

            in 500..599 -> ResultadoEnvio.ErrorTransitorio(
                motivo(codigo, detalle ?: "Telegram no está disponible")
            )

            else -> ResultadoEnvio.ErrorTransitorio(motivo(codigo, detalle))
        }
    }

    /**
     * true si [codigo] indica que el problema es el token y no este mensaje.
     *
     * Lo usa el transporte para distinguir «este reenvío no se puede entregar» de «ningún
     * reenvío se va a poder entregar hasta que alguien toque los ajustes», que es una avería
     * distinta y merece verse en el Panel y no enterrada en el registro del primero de
     * cuatrocientos reenvíos fallidos.
     */
    fun esFalloDeCredencial(codigo: Int): Boolean = codigo == 401 || codigo == 404

    /**
     * Longitud máxima de un mensaje de texto en el Bot API.
     *
     * No es un límite de bytes sino de caracteres UTF-16, que es como los cuenta Telegram.
     */
    const val LIMITE_CARACTERES = 4096

    /**
     * Parte [texto] en trozos que quepan en [limite].
     *
     * Un SMS concatenado largo más una plantilla generosa es la única forma realista de pasarse
     * de 4.096 caracteres, así que esto casi nunca se usa. Existe porque la alternativa —cortar
     * y tirar el resto— pierde contenido en silencio, y porque dejar que Telegram devuelva un
     * `400` convertiría un mensaje largo en un reenvío fallido.
     *
     * Corta por el último salto de línea del trozo, y si no hay, por el último espacio; solo
     * parte una palabra cuando no hay ningún hueco donde cortar. Devuelve una lista de un
     * elemento —el propio texto— en el caso normal.
     */
    fun trocear(texto: String, limite: Int = LIMITE_CARACTERES): List<String> {
        if (texto.length <= limite) return listOf(texto)

        val trozos = mutableListOf<String>()
        var resto = texto
        while (resto.length > limite) {
            val ventana = resto.substring(0, limite)
            val corte = ventana.lastIndexOf('\n')
                .takeIf { it > 0 }
                ?: ventana.lastIndexOf(' ').takeIf { it > 0 }
                ?: limite

            trozos += resto.substring(0, corte).trimEnd()
            resto = resto.substring(corte).trimStart()
        }
        if (resto.isNotEmpty()) trozos += resto
        return trozos
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private fun motivo(codigo: Int, detalle: String?): String =
        if (detalle != null) "HTTP $codigo — $detalle" else "HTTP $codigo"

    private fun interpretarCuerpo(cuerpo: String?): RespuestaJson? {
        if (cuerpo.isNullOrBlank()) return null
        return try {
            json.decodeFromString<RespuestaJson>(cuerpo)
        } catch (e: Exception) {
            // Un cuerpo ilegible no cambia la clasificación: el código HTTP ya decide. Esto
            // solo aporta el `description`, y sin él el motivo queda con el código a secas.
            null
        }
    }

    @Serializable
    private data class RespuestaJson(
        @SerialName("ok") val ok: Boolean = false,
        @SerialName("description") val descripcion: String? = null,
        @SerialName("parameters") val parametros: ParametrosJson? = null
    )

    @Serializable
    private data class ParametrosJson(
        @SerialName("retry_after") val esperarSegundos: Int? = null
    )

    /** La respuesta trae campos que aquí no interesan, y traerá más con el tiempo. */
    private val json = Json { ignoreUnknownKeys = true }
}
