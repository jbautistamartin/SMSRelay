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

        // El plazo configurado gobierna los timeouts del socket, y el de la corrutina es solo
        // un respaldo con margen por encima. El orden importa y está explicado en el KDoc de
        // [plazos]: invertirlo abandona peticiones que siguen en vuelo y pueden entregarse.
        val plazos = Plazos.de(timeoutSegundos)
        val respaldoMs = plazos.totalMs * trozos.size + MARGEN_RESPALDO_MS

        return withTimeoutOrNull(respaldoMs) {
            enviarTrozos(token, destino, trozos, plazos)
        } ?: ResultadoEnvio.ErrorTransitorio(
            "Telegram no contestó en ${respaldoMs / 1000} s"
        )
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    /**
     * Reparto del plazo configurado entre conectar y leer.
     *
     * ## Por qué el timeout del socket tiene que saltar antes que el de la corrutina
     * `withTimeoutOrNull` **no puede interrumpir una lectura de socket bloqueante**. Cancela la
     * corrutina, sí, pero el hilo de `Dispatchers.IO` sigue dentro de `HttpURLConnection` hasta
     * que el propio socket se rinde. Lo que devuelve, por tanto, no es «la petición se ha
     * abortado» sino «he dejado de esperarla»: la petición puede completarse después, Telegram
     * puede entregar el mensaje, y el worker —que ya recibió un `ErrorTransitorio`— lo reintenta.
     * El resultado es un **mensaje duplicado en el chat de alguien**.
     *
     * El timeout del socket sí aborta de verdad. Así que es él quien debe gobernar el plazo, y
     * el de la corrutina queda como respaldo con margen por encima, para lo que el socket no
     * cubre.
     *
     * La primera versión de esta clase tenía los dos plazos como constantes independientes —15 s
     * para conectar y 20 s para leer— mientras el ajuste de la pantalla admitía valores **desde
     * 10 s**. Con el ajuste por debajo de 35 s, el respaldo saltaba siempre primero y el fallo
     * descrito arriba dejaba de ser teórico.
     */
    private data class Plazos(val conexionMs: Int, val lecturaMs: Int) {
        val totalMs: Long get() = (conexionMs + lecturaMs).toLong()

        companion object {
            /**
             * Reparte [segundos] en un tercio para conectar y dos tercios para leer.
             *
             * Conectar es lo rápido —un `SYN` y un saludo TLS— y leer es lo que puede tardar, así
             * que el reparto no es a la mitad. Los topes evitan los dos extremos: con el mínimo
             * del ajuste (10 s) conectar tendría 3 s, que es poco para TLS en una red móvil mala,
             * y con el máximo (300 s) tendría 100 s, que no sirve de nada porque una conexión que
             * no se establece en medio minuto no se va a establecer.
             */
            fun de(segundos: Int): Plazos {
                val totalMs = TimeUnit.SECONDS.toMillis(segundos.toLong()).toInt()
                val conexion = (totalMs / 3).coerceIn(MIN_CONEXION_MS, MAX_CONEXION_MS)
                return Plazos(
                    conexionMs = conexion,
                    lecturaMs = (totalMs - conexion).coerceAtLeast(MIN_LECTURA_MS)
                )
            }

            private const val MIN_CONEXION_MS = 5_000
            private const val MAX_CONEXION_MS = 30_000
            private const val MIN_LECTURA_MS = 5_000
        }
    }

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
        trozos: List<String>,
        plazos: Plazos
    ): ResultadoEnvio {
        for (trozo in trozos) {
            val resultado = enviarUno(token, destino, trozo, plazos)
            if (resultado !is ResultadoEnvio.Enviado) return resultado
        }
        return ResultadoEnvio.Enviado
    }

    private suspend fun enviarUno(
        token: String,
        destino: String,
        texto: String,
        plazos: Plazos
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
                    connectTimeout = plazos.conexionMs
                    readTimeout = plazos.lecturaMs
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
        /**
         * Margen del respaldo de la corrutina sobre la suma de los plazos del socket.
         *
         * Existe para que el respaldo no pre-empte nunca una petición que el socket todavía
         * está esperando legítimamente. Solo entra en juego si algo se queda colgado fuera de
         * lo que el socket vigila.
         */
        const val MARGEN_RESPALDO_MS = 10_000L

        val json = Json { ignoreUnknownKeys = true }
    }
}
