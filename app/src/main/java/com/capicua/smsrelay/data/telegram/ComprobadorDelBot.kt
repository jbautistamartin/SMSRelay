// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.telegram

import com.capicua.smsrelay.data.config.ConfigDataStore
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

/** Lo que puede contestar [ComprobadorDelBot.comprobar]. */
sealed interface ResultadoDelBot {

    /** El token sirve, y este es el bot al que pertenece. */
    data class Correcto(val usuario: String, val nombre: String) : ResultadoDelBot

    /** Telegram dice que el token no vale. No es un problema de red. */
    data class Rechazado(val motivo: String) : ResultadoDelBot

    /** No se pudo preguntar: sin red, Telegram caído, respuesta ilegible. */
    data class NoSePudo(val motivo: String) : ResultadoDelBot

    /** No hay nada que comprobar porque no hay token. */
    data object SinToken : ResultadoDelBot
}

/**
 * Pregunta a Telegram si un token sirve, con `getMe`.
 *
 * ## Por qué hace falta un botón para esto
 * La forma del token se puede comprobar sin red —eso es [TokenDeBot]— pero si *sirve* solo lo
 * sabe Telegram. Sin este botón, la única forma de descubrir que el token está mal es esperar a
 * que llegue un SMS y verlo marcado como fallido, probablemente horas después y con el teléfono
 * ya colocado en su sitio. Es exactamente la clase de error que hay que descubrir mientras
 * todavía tienes el móvil en la mano.
 *
 * Y no contesta «sí» o «no»: contesta **de qué bot se trata**. Enseñar el `@usuario` convierte
 * la validación en algo comprobable por la persona que la pide, que puede reconocer su bot o
 * darse cuenta de que ha pegado el token de otro.
 *
 * ## Por qué acepta un token candidato
 * Se valida **antes** de guardar. Quien acaba de pegar un token quiere saber si sirve antes de
 * comprometerse, y obligar a guardar primero significaría dejar guardado un token que no vale.
 *
 * `getMe` es la llamada correcta para esto: no envía nada, no tiene efectos y no gasta cuota
 * apreciable.
 */
@Singleton
class ComprobadorDelBot @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val estadoDelBot: EstadoDelBot
) {

    /**
     * Comprueba [tokenCandidato], o el que esté guardado si se omite.
     *
     * Un resultado [ResultadoDelBot.Correcto] borra el aviso de «token rechazado»: acaba de
     * demostrarse que el token sirve. Un [ResultadoDelBot.Rechazado] **no** lo enciende, porque
     * puede tratarse de un candidato que ni se ha guardado todavía; ese aviso habla del token
     * que la aplicación está usando, y lo pone quien intenta usarlo.
     */
    suspend fun comprobar(tokenCandidato: String? = null): ResultadoDelBot {
        val token = TokenDeBot.normalizar(
            tokenCandidato ?: configDataStore.config.first().tokenBot
        )
        if (token.isBlank()) return ResultadoDelBot.SinToken
        if (!TokenDeBot.pareceValido(token)) {
            return ResultadoDelBot.Rechazado("el token no tiene la forma de un token de bot")
        }

        val resultado = withContext(Dispatchers.IO) { preguntar(token) }

        // Que el token sirva borra el aviso de «Telegram rechazó el token»: acaba de
        // demostrarse lo contrario. Se hace aquí, donde se puede suspender, y no dentro de
        // interpretar(), que así se queda siendo una función sin efectos.
        if (resultado is ResultadoDelBot.Correcto) {
            estadoDelBot.olvidarTokenRechazado()
        }

        return resultado
    }

    private fun preguntar(token: String): ResultadoDelBot {
        var conexion: HttpURLConnection? = null
        return try {
            conexion = (URL("$BASE$token/getMe").openConnection() as HttpURLConnection).apply {
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
            Timber.d("ComprobadorDelBot: getMe → HTTP $codigo")

            interpretar(codigo, cuerpo)
        } catch (e: Exception) {
            Timber.w(e, "ComprobadorDelBot: no se pudo preguntar por el bot")
            ResultadoDelBot.NoSePudo(e.message ?: "fallo de red")
        } finally {
            conexion?.disconnect()
        }
    }

    private fun interpretar(codigo: Int, cuerpo: String?): ResultadoDelBot {
        val respuesta = runCatching {
            cuerpo?.let { json.decodeFromString<GetMeJson>(it) }
        }.getOrNull()

        if (MapeoDeRespuestas.esFalloDeCredencial(codigo)) {
            return ResultadoDelBot.Rechazado(
                respuesta?.descripcion?.takeIf { it.isNotBlank() } ?: "Telegram no reconoce el token"
            )
        }

        if (codigo !in 200..299 || respuesta?.ok != true || respuesta.bot == null) {
            return ResultadoDelBot.NoSePudo(
                respuesta?.descripcion?.takeIf { it.isNotBlank() } ?: "Telegram respondió HTTP $codigo"
            )
        }

        return ResultadoDelBot.Correcto(
            usuario = respuesta.bot.usuario.orEmpty(),
            nombre = respuesta.bot.nombre.orEmpty()
        )
    }

    @Serializable
    private data class GetMeJson(
        @SerialName("ok") val ok: Boolean = false,
        @SerialName("description") val descripcion: String? = null,
        @SerialName("result") val bot: BotJson? = null
    )

    @Serializable
    private data class BotJson(
        @SerialName("username") val usuario: String? = null,
        @SerialName("first_name") val nombre: String? = null
    )

    private companion object {
        const val BASE = "https://api.telegram.org/bot"
        const val TIMEOUT_MS = 15_000

        val json = Json { ignoreUnknownKeys = true }
    }
}
