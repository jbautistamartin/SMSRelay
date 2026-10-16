// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.whatsapp

import com.capicua.smstowhats.data.config.ConfigDataStore
import com.capicua.smstowhats.domain.transport.EnviadorDeMensajes
import com.capicua.smstowhats.domain.transport.ResultadoEnvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entrega mensajes por la Cloud API de WhatsApp.
 *
 * Una petición `POST` a `https://graph.facebook.com/<version>/<phone_number_id>/messages`. Sin
 * Retrofit y sin OkHttp: el patrón de la casa para esto es `HttpURLConnection` más
 * `kotlinx.serialization`, el mismo que usa `ComprobadorDeActualizaciones`, y una dependencia
 * nueva para una petición no se paga.
 *
 * ## Texto plano, y esto sigue siendo lo que más fácil sería hacer mal
 * Con Telegram el peligro era `parse_mode`: ponerlo hacía que un SMS con un `_` o un `*`
 * devolviera `400 can't parse entities` y el reenvío muriera por haber querido poner algo en
 * negrita. Aquí el peligro cambia de forma y se suaviza: **WhatsApp interpreta `*negrita*` y
 * `_cursiva_` siempre, sin que haya nada que desactivar**. Un SMS con asteriscos llegará con
 * medio texto en negrita.
 *
 * Se acepta, y la alternativa se descarta a propósito: escapar el cuerpo sería *interpretar*
 * contenido de un tercero para modificarlo, que es justo lo que este proyecto no hace en ningún
 * sitio. El resultado es cosmético y el mensaje llega entero, que es lo que importa.
 *
 * ## `preview_url` en false, y no es un detalle estético
 * Si se deja en true, WhatsApp **descarga** el primer enlace que encuentre en el cuerpo para
 * pintar su tarjeta de vista previa. Como el cuerpo es un SMS de un tercero, eso significa que
 * entregar un mensaje provocaría una petición de los servidores de Meta a una URL que la ha
 * puesto quien mandó el SMS: desde confirmar que el enlace de un phishing tiene público hasta
 * marcar un enlace de un solo uso como ya visitado. Va en false y no es configurable.
 *
 * ## Las credenciales no se registran
 * El token va en la cabecera `Authorization`, así que la cabecera no se registra. El
 * `phone_number_id` va en la URL, así que la URL tampoco. Los `Timber` de esta clase hablan del
 * destino y del código de respuesta. Una credencial en `adb logcat` es una credencial publicada.
 *
 * ## Qué no hace
 * No reintenta —eso es de `DispatchWorker`— ni escribe en el registro de la aplicación. Devuelve
 * un [ResultadoEnvio] con un motivo legible y quien llama decide.
 */
@Singleton
class WhatsAppSender @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val estadoDeLaCuenta: EstadoDeLaCuenta
) : EnviadorDeMensajes {

    override val limiteCaracteres: Int = MapeoDeRespuestas.LIMITE_CARACTERES

    override suspend fun motivoNoDisponible(): String? {
        val config = configDataStore.config.first()
        return when {
            config.tokenAcceso.isBlank() ->
                "no hay ningún token de acceso configurado en Ajustes"

            config.idNumeroTelefono.isBlank() ->
                "no hay ningún id de número configurado en Ajustes"

            estadoDeLaCuenta.leerCredencialRechazada() ->
                "Meta rechazó las credenciales configuradas: revísalas en Ajustes"

            else -> null
        }
    }

    override suspend fun enviar(
        destino: String,
        texto: String,
        timeoutSegundos: Int
    ): ResultadoEnvio {

        val config = configDataStore.config.first()
        val token = config.tokenAcceso
        val idNumero = config.idNumeroTelefono

        if (token.isBlank()) {
            return ResultadoEnvio.ErrorPermanente("no hay token de acceso configurado")
        }
        if (idNumero.isBlank()) {
            return ResultadoEnvio.ErrorPermanente("no hay id de número configurado")
        }
        if (destino.isBlank()) {
            return ResultadoEnvio.ErrorPermanente("el destino está vacío")
        }
        if (texto.isEmpty()) {
            return ResultadoEnvio.ErrorPermanente("el texto a enviar está vacío")
        }

        // Pasarse de 4.096 caracteres es casi imposible y aun así posible. Trocear nunca pierde
        // contenido; dejar que Meta conteste con un error convertiría un mensaje largo en un
        // reenvío fallido.
        val trozos = MapeoDeRespuestas.trocear(texto, limiteCaracteres)
        if (trozos.size > 1) {
            Timber.d("WhatsAppSender: el texto va en ${trozos.size} mensajes por longitud")
        }

        // El plazo configurado gobierna los timeouts del socket, y el de la corrutina es solo
        // un respaldo con margen por encima. El orden importa y está explicado en el KDoc de
        // [Plazos]: invertirlo abandona peticiones que siguen en vuelo y pueden entregarse.
        val plazos = Plazos.de(timeoutSegundos)
        val respaldoMs = plazos.totalMs * trozos.size + MARGEN_RESPALDO_MS

        return withTimeoutOrNull(respaldoMs) {
            enviarTrozos(token, idNumero, destino, trozos, plazos)
        } ?: ResultadoEnvio.ErrorTransitorio(
            "Meta no contestó en ${respaldoMs / 1000} s"
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
     * abortado» sino «he dejado de esperarla»: la petición puede completarse después, Meta
     * puede entregar el mensaje, y el worker —que ya recibió un `ErrorTransitorio`— lo reintenta.
     * El resultado es un **mensaje duplicado en el chat de alguien**.
     *
     * El timeout del socket sí aborta de verdad. Así que es él quien debe gobernar el plazo, y
     * el de la corrutina queda como respaldo con margen por encima, para lo que el socket no
     * cubre.
     *
     * Esto se escribió para Telegram, se arregló para Telegram y se hereda sin un cambio: el
     * problema no era del Bot API, era de cómo `HttpURLConnection` y las corrutinas conviven.
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
        idNumero: String,
        destino: String,
        trozos: List<String>,
        plazos: Plazos
    ): ResultadoEnvio {
        for (trozo in trozos) {
            val resultado = enviarUno(token, idNumero, destino, trozo, plazos)
            if (resultado !is ResultadoEnvio.Enviado) return resultado
        }
        return ResultadoEnvio.Enviado
    }

    private suspend fun enviarUno(
        token: String,
        idNumero: String,
        destino: String,
        texto: String,
        plazos: Plazos
    ): ResultadoEnvio = withContext(Dispatchers.IO) {

        var conexion: HttpURLConnection? = null
        try {
            val cuerpoPeticion = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("messaging_product", "whatsapp")
                    // Fijo: los destinos de esta aplicación son siempre personas, nunca grupos.
                    put("recipient_type", "individual")
                    put("to", DestinoDeWhatsApp.aDestinatarioApi(destino))
                    put("type", "text")
                    put(
                        "text",
                        JsonObject(
                            mapOf(
                                // Ver el KDoc de la clase: en true, Meta descargaría los enlaces
                                // que vengan dentro de un SMS de un tercero.
                                "preview_url" to JsonPrimitive(false),
                                "body" to JsonPrimitive(texto)
                            )
                        )
                    )
                }
            )

            conexion = (URL("$BASE$API/$idNumero/messages").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "POST"
                    connectTimeout = plazos.conexionMs
                    readTimeout = plazos.lecturaMs
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }

            conexion.outputStream.use { it.write(cuerpoPeticion.toByteArray(Charsets.UTF_8)) }

            val codigo = conexion.responseCode
            // Un error viene por errorStream, no por inputStream, y es donde está el bloque
            // `error` con el `code` que decide la clasificación. Leerlo no es opcional aquí:
            // sin él, los cuatro 400 distintos de la Cloud API son indistinguibles.
            val cuerpo = runCatching {
                if (codigo in 200..299) {
                    conexion.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conexion.errorStream?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull()

            Timber.d("WhatsAppSender: mensaje a '$destino' → HTTP $codigo")

            anotarEstadoDeCredencial(codigo, cuerpo)
            MapeoDeRespuestas.interpretarEnvio(codigo, cuerpo)

        } catch (e: Exception) {
            // Sin red, DNS caído, timeout de socket: todo transitorio. El outbox no pierde
            // nada y el worker volverá cuando haya conexión.
            Timber.w(e, "WhatsAppSender: fallo de red enviando a '$destino'")
            ResultadoEnvio.ErrorTransitorio(
                "${e.javaClass.simpleName}: ${e.message ?: "fallo de red"}"
            )
        } finally {
            conexion?.disconnect()
        }
    }

    /**
     * Mantiene al día el aviso de «las credenciales no sirven» que mira el Panel.
     *
     * Un envío correcto lo borra porque es la mejor prueba posible de que las credenciales van
     * bien, y dejarlo puesto mandaría a alguien a arreglar algo que ya funciona.
     */
    private suspend fun anotarEstadoDeCredencial(codigo: Int, cuerpo: String?) {
        if (MapeoDeRespuestas.esFalloDeCredencial(codigo, cuerpo)) {
            Timber.e("WhatsAppSender: Meta rechazó las credenciales (HTTP $codigo)")
            estadoDeLaCuenta.anotarCredencialRechazada()
        } else if (codigo in 200..299) {
            estadoDeLaCuenta.olvidarCredencialRechazada()
        }
    }

    internal companion object {
        /** Host de la Graph API. El `phone_number_id` se concatena tras la versión. */
        const val BASE = "https://graph.facebook.com/"

        /**
         * Versión de la Graph API que se pide explícitamente.
         *
         * Nunca se llama sin versión. Una URL sin versión usa la más reciente que Meta tenga
         * publicada, lo que convierte cualquier cambio suyo en un cambio de comportamiento de
         * esta aplicación sin que nadie haya tocado nada. Subirla es una decisión, y se toma
         * leyendo el registro de cambios de Meta.
         */
        const val API = "v22.0"

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
