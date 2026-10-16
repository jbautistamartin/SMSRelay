// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.telegram

import com.capicua.smstowhats.data.config.ConfigDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Un chat que ha escrito al bot y al que, por tanto, el bot puede escribir. */
data class ChatDescubierto(
    val id: String,
    /** Nombre legible: el de la persona, el del grupo o el del canal. */
    val nombre: String,
    /** `privado`, `grupo`, `supergrupo` o `canal`, ya traducido. */
    val tipo: String
)

/** Lo que puede contestar [DescubridorDeChats.descubrir]. */
sealed interface ResultadoDescubrimiento {
    data class Encontrados(val chats: List<ChatDescubierto>) : ResultadoDescubrimiento

    /** La llamada fue bien y no había ningún chat: nadie ha escrito al bot todavía. */
    data object Ninguno : ResultadoDescubrimiento

    data class NoSePudo(val motivo: String) : ResultadoDescubrimiento
    data object SinToken : ResultadoDescubrimiento
}

/**
 * Lista los chats que han escrito al bot, con `getUpdates`.
 *
 * ## Por qué esto es una función y no un texto de ayuda
 * Nadie se sabe su `chat_id`. Es un número de diez o trece dígitos que Telegram no muestra en
 * ninguna pantalla de su aplicación, y obtenerlo «a mano» significa hablar con un bot de terceros
 * o leer una URL de la API en el navegador. Pedirle eso a quien solo quiere que le lleguen los
 * SMS del banco es pedirle que abandone.
 *
 * Así que la pantalla de reglas ofrece una lista para elegir. Es el equivalente del botón
 * «Contestar al remitente» que tenía SMStoSMS: una función que nadie conoce no es una función.
 *
 * ## Lo que hay que saber antes
 * **Un bot no puede escribir primero.** Telegram lo prohíbe: hasta que una persona le manda algo
 * —un `/start` basta— el bot no tiene forma de dirigirse a ella y su chat no existe. Por eso esta
 * lista puede volver vacía y eso **no es un error**: significa «dile a quien va a recibir los
 * avisos que le escriba al bot». Es el problema de soporte número uno de cualquier aplicación con
 * bot, y la pantalla lo dice en lugar de dejar una lista vacía sin explicación.
 *
 * ## Limitación que conviene conocer
 * `getUpdates` devuelve la cola de mensajes pendientes, que Telegram **guarda solo 24 horas**. Un
 * chat que escribió la semana pasada no aparece aquí. No es un fallo que se pueda arreglar desde
 * esta aplicación: si no aparece, basta con que vuelva a escribir al bot.
 *
 * No se usa `offset` ni se confirman los updates a propósito: confirmarlos los borraría de la
 * cola, y entonces un segundo descubrimiento —o el de otra instalación del mismo bot— no vería
 * nada. Esto solo mira.
 */
@Singleton
class DescubridorDeChats @Inject constructor(
    private val configDataStore: ConfigDataStore
) {

    suspend fun descubrir(): ResultadoDescubrimiento {
        val token = TokenDeBot.normalizar(configDataStore.config.first().tokenBot)
        if (token.isBlank()) return ResultadoDescubrimiento.SinToken

        return withContext(Dispatchers.IO) { preguntar(token) }
    }

    private fun preguntar(token: String): ResultadoDescubrimiento {
        var conexion: HttpURLConnection? = null
        return try {
            conexion = (URL("$BASE$token/getUpdates").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                }

            val codigo = conexion.responseCode
            val cuerpo = runCatching {
                if (codigo in 200..299) {
                    conexion.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conexion.errorStream?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull()

            // Nunca la URL: lleva el token dentro.
            Timber.d("DescubridorDeChats: getUpdates → HTTP $codigo")

            if (codigo !in 200..299) {
                return ResultadoDescubrimiento.NoSePudo(
                    if (MapeoDeRespuestas.esFalloDeCredencial(codigo)) {
                        "Telegram no reconoce el token"
                    } else {
                        "Telegram respondió HTTP $codigo"
                    }
                )
            }

            interpretar(cuerpo)
        } catch (e: Exception) {
            Timber.w(e, "DescubridorDeChats: no se pudieron pedir los updates")
            ResultadoDescubrimiento.NoSePudo(e.message ?: "fallo de red")
        } finally {
            conexion?.disconnect()
        }
    }

    private fun interpretar(cuerpo: String?): ResultadoDescubrimiento {
        val respuesta = runCatching {
            cuerpo?.let { json.decodeFromString<UpdatesJson>(it) }
        }.getOrNull() ?: return ResultadoDescubrimiento.NoSePudo("respuesta ilegible")

        if (!respuesta.ok) {
            return ResultadoDescubrimiento.NoSePudo(
                respuesta.descripcion?.takeIf { it.isNotBlank() } ?: "Telegram devolvió ok:false"
            )
        }

        // Un mismo chat aparece una vez por mensaje enviado; se queda uno por id. Se mira
        // tanto `message` como `channel_post`, porque lo que llega de un canal no es un
        // «mensaje» para la API.
        val chats = respuesta.updates
            .mapNotNull { it.mensaje?.chat ?: it.publicacion?.chat }
            .distinctBy { it.id }
            .map { chat ->
                ChatDescubierto(
                    id = chat.id.toString(),
                    nombre = nombreDe(chat),
                    tipo = tipoLegible(chat.tipo)
                )
            }

        return if (chats.isEmpty()) {
            ResultadoDescubrimiento.Ninguno
        } else {
            ResultadoDescubrimiento.Encontrados(chats)
        }
    }

    /**
     * Nombre con el que se enseña un chat.
     *
     * Para una persona, Telegram manda nombre y apellido por separado y a veces solo uno; para
     * un grupo o un canal manda `title`. Si no viniera nada, el id es mejor que una fila vacía.
     */
    private fun nombreDe(chat: ChatJson): String = listOfNotNull(
        chat.titulo?.takeIf { it.isNotBlank() },
        listOfNotNull(
            chat.nombre?.takeIf { it.isNotBlank() },
            chat.apellido?.takeIf { it.isNotBlank() }
        ).joinToString(" ").takeIf { it.isNotBlank() },
        chat.usuario?.takeIf { it.isNotBlank() }?.let { "@$it" }
    ).firstOrNull() ?: chat.id.toString()

    private fun tipoLegible(tipo: String?): String = when (tipo) {
        "private" -> "privado"
        "group" -> "grupo"
        "supergroup" -> "supergrupo"
        "channel" -> "canal"
        else -> tipo.orEmpty()
    }

    @Serializable
    private data class UpdatesJson(
        @SerialName("ok") val ok: Boolean = false,
        @SerialName("description") val descripcion: String? = null,
        @SerialName("result") val updates: List<UpdateJson> = emptyList()
    )

    @Serializable
    private data class UpdateJson(
        @SerialName("message") val mensaje: MensajeJson? = null,
        @SerialName("channel_post") val publicacion: MensajeJson? = null
    )

    @Serializable
    private data class MensajeJson(
        @SerialName("chat") val chat: ChatJson? = null
    )

    @Serializable
    private data class ChatJson(
        @SerialName("id") val id: Long = 0,
        @SerialName("type") val tipo: String? = null,
        @SerialName("title") val titulo: String? = null,
        @SerialName("first_name") val nombre: String? = null,
        @SerialName("last_name") val apellido: String? = null,
        @SerialName("username") val usuario: String? = null
    )

    private companion object {
        const val BASE = "https://api.telegram.org/bot"
        const val TIMEOUT_MS = 15_000

        val json = Json { ignoreUnknownKeys = true }
    }
}
