// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.data.telegram

import com.capicua.smstotelegram.data.config.ConfigDataStore
import com.capicua.smstotelegram.domain.transport.EnviadorDeMensajes
import com.capicua.smstotelegram.domain.transport.ResultadoEnvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entrega mensajes por el Bot API de Telegram.
 *
 * Una petición `POST` a `https://api.telegram.org/bot<token>/sendMessage` con un JSON de dos
 * campos. Sin Retrofit y sin OkHttp: el patrón de la casa para esto es `HttpURLConnection` más
 * `kotlinx.serialization`, el mismo que usa `ComprobadorDeActualizaciones`, y una dependencia
 * nueva para una petición no se paga.
 *
 * ## Texto plano, sin `parse_mode`
 * Esta es la decisión que más fácil sería tomar mal. Con `parse_mode` en `Markdown` o `HTML`,
 * cualquier SMS que lleve un `_`, un `*`, un `[` o un `<` —un enlace de un banco, una fórmula,
 * una carita— hace que Telegram devuelva `400 Bad Request: can't parse entities` y el reenvío se
 * marque como fallido permanente. El mensaje **no se entrega** por haber querido ponerlo en
 * negrita.
 *
 * El cuerpo del SMS es contenido de un tercero y no se interpreta nunca. Es la misma regla que
 * el evaluador ya aplica al sustituir `{mensaje}` en último lugar para que no pueda inyectar
 * marcadores, y se toma aquí por el mismo motivo.
 *
 * ## El token no se registra
 * Va dentro de la URL, así que la URL tampoco se registra: los `Timber` de esta clase hablan del
 * destino y del código de respuesta, nunca de la ruta. Un token en `adb logcat` es un token
 * publicado.
 *
 * ## Qué no hace
 * No reintenta —eso es de `DispatchWorker`— ni escribe en el registro de la aplicación. Devuelve
 * un [ResultadoEnvio] con un motivo legible y quien llama decide.
 */
@Singleton
class TelegramSender @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val estadoDelBot: EstadoDelBot
) : EnviadorDeMensajes {

    override val limiteCaracteres: Int = MapeoDeRespuestas.LIMITE_CARACTERES

    override suspend fun motivoNoDisponible(): String? {
        val token = configDataStore.config.first().tokenBot
        return when {
            token.isBlank() ->
                "no hay ningún token de bot configurado en Ajustes"

            estadoDelBot.leerTokenRechazado() ->
                "Telegram rechazó el token configurado: revísalo en Ajustes"

            else -> null
        }
    }

    override suspend fun enviar(
        destino: String,
        texto: String,
        timeoutSegundos: Int
    ): ResultadoEnvio {

        val token = configDataStore.config.first().tokenBot
        if (token.isBlank()) {
            return ResultadoEnvio.ErrorPermanente("no hay token de bot configurado")
        }
        if (destino.isBlank()) {
            return ResultadoEnvio.ErrorPermanente("el destino está vacío")
        }
        if (texto.isEmpty()) {
            return ResultadoEnvio.ErrorPermanente("el texto a enviar está vacío")
        }

        // Pasarse de 4.096 caracteres es casi imposible y aun así posible. Trocear nunca
        // pierde contenido; dejar que Telegram conteste 400 convertiría un mensaje largo en un
        // reenvío fallido.
        val trozos = MapeoDeRespuestas.trocear(texto, limiteCaracteres)
        if (trozos.size > 1) {
            Timber.d("TelegramSender: el texto va en ${trozos.size} mensajes por longitud")
        }

        val plazoMs = TimeUnit.SECONDS.toMillis(timeoutSegundos.toLong())

        return withTimeoutOrNull(plazoMs) {
            enviarTrozos(token, destino, trozos)
        } ?: ResultadoEnvio.ErrorTransitorio(
            "Telegram no contestó en $timeoutSegundos s"
        )
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    /**
     * Envía los trozos en orden y se detiene en el primero que falle.
     *
     * Si falla el segundo de tres, el resultado es ese fallo y el reenvío entero se reintenta,
     * aceptando que el destino pueda ver repetido el primero. Es exactamente el criterio que ya
     * se aplicaba a las partes de un SMS concatenado: dar por bueno un mensaje al que le falta
     * el final es peor que duplicar su principio.
     */
    private suspend fun enviarTrozos(
        token: String,
        destino: String,
        trozos: List<String>
    ): ResultadoEnvio {
        for (trozo in trozos) {
            val resultado = enviarUno(token, destino, trozo)
            if (resultado !is ResultadoEnvio.Enviado) return resultado
        }
        return ResultadoEnvio.Enviado
    }

    private suspend fun enviarUno(
        token: String,
        destino: String,
        texto: String
    ): ResultadoEnvio = withContext(Dispatchers.IO) {

        var conexion: HttpURLConnection? = null
        try {
            // chat_id es un string en el JSON a propósito: vale un id numérico («-1001234»)
            // y vale un «@canal», y Telegram acepta ambos en ese campo.
            val cuerpoPeticion = JsonObject(
                mapOf(
                    "chat_id" to JsonPrimitive(destino),
                    "text" to JsonPrimitive(texto)
                )
            ).let { json.encodeToString(JsonObject.serializer(), it) }

            conexion = (URL("$BASE$token/sendMessage").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_CONEXION_MS
                    readTimeout = TIMEOUT_LECTURA_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }

            conexion.outputStream.use { it.write(cuerpoPeticion.toByteArray(Charsets.UTF_8)) }

            val codigo = conexion.responseCode
            // Un error viene por errorStream, no por inputStream, y es donde está el
            // `description` que explica qué pasó. Leerlo es la mitad del diagnóstico.
            val cuerpo = runCatching {
                if (codigo in 200..299) {
                    conexion.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conexion.errorStream?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull()

            Timber.d("TelegramSender: sendMessage a '$destino' → HTTP $codigo")

            anotarEstadoDeCredencial(codigo)
            MapeoDeRespuestas.interpretarEnvio(codigo, cuerpo)

        } catch (e: Exception) {
            // Sin red, DNS caído, timeout de socket: todo transitorio. El outbox no pierde
            // nada y el worker volverá cuando haya conexión.
            Timber.w(e, "TelegramSender: fallo de red enviando a '$destino'")
            ResultadoEnvio.ErrorTransitorio(
                "${e.javaClass.simpleName}: ${e.message ?: "fallo de red"}"
            )
        } finally {
            conexion?.disconnect()
        }
    }

    /**
     * Mantiene al día el aviso de «el token no sirve» que mira el Panel.
     *
     * Un envío correcto lo borra porque es la mejor prueba posible de que el token va bien, y
     * dejarlo puesto mandaría a alguien a arreglar algo que ya funciona.
     */
    private suspend fun anotarEstadoDeCredencial(codigo: Int) {
        if (MapeoDeRespuestas.esFalloDeCredencial(codigo)) {
            Timber.e("TelegramSender: Telegram rechazó el token (HTTP $codigo)")
            estadoDelBot.anotarTokenRechazado()
        } else if (codigo in 200..299) {
            estadoDelBot.olvidarTokenRechazado()
        }
    }

    private companion object {
        /** El token se concatena aquí. Esta constante nunca se registra con el token pegado. */
        const val BASE = "https://api.telegram.org/bot"
        const val TIMEOUT_CONEXION_MS = 15_000
        const val TIMEOUT_LECTURA_MS = 20_000

        val json = Json { ignoreUnknownKeys = true }
    }
}
