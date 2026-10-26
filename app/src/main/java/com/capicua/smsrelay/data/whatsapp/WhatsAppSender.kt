// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.whatsapp

import com.capicua.smsrelay.data.config.AppConfig
import com.capicua.smsrelay.data.config.ConfigDataStore
import com.capicua.smsrelay.data.config.ModoDeEntrega
import com.capicua.smsrelay.domain.transport.EnviadorDeMensajes
import com.capicua.smsrelay.domain.transport.ResultadoEnvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
    private val estadoDeLaCuenta: EstadoDeLaCuenta,
    private val contadorDeMensajes: ContadorDeMensajes
) : EnviadorDeMensajes {

    /**
     * El límite que aplica **con la configuración actual**.
     *
     * En `SOLO_PLANTILLA` es el presupuesto de la variable de la plantilla; en los otros dos
     * modos es el del texto libre. En `AUTOMATICO` el límite honesto sería «el del texto libre
     * si la ventana está abierta y el de la plantilla si no», que no se puede saber sin
     * preguntar, así que se enseña el del texto libre: es el camino que se intenta primero y el
     * que va a usarse casi siempre.
     */
    override suspend fun limiteCaracteres(): Int =
        if (configDataStore.config.first().modoDeEntrega == ModoDeEntrega.SOLO_PLANTILLA) {
            PlantillaDeWhatsApp.PRESUPUESTO
        } else {
            MapeoDeRespuestas.LIMITE_CARACTERES
        }

    override suspend fun motivoNoDisponible(): String? {
        val config = configDataStore.config.first()
        return when {
            config.tokenAcceso.isBlank() ->
                "no hay ningún token de acceso configurado en Ajustes"

            config.idNumeroTelefono.isBlank() ->
                "no hay ningún id de número configurado en Ajustes"

            estadoDeLaCuenta.leerCredencialRechazada() ->
                "Meta rechazó las credenciales configuradas: revísalas en Ajustes"

            config.modoDeEntrega == ModoDeEntrega.SOLO_PLANTILLA &&
                config.plantillaNombre.isBlank() ->
                "el modo de entrega es «solo plantilla» y no hay ninguna configurada en Ajustes"

            else -> null
        }
    }

    /**
     * Entrega [texto] a [destino] por el camino que diga el modo configurado.
     *
     * ## El salto a plantilla va dentro del intento, no como reintento
     * En `AUTOMATICO` se prueba el texto libre y, si Meta contesta que la ventana está cerrada,
     * se reenvía **aquí mismo** como plantilla. Devolver un `ErrorTransitorio` y dejar que
     * `DispatchWorker` lo reintentara tendría el mismo resultado diez veces: la ventana seguiría
     * cerrada, porque lo que la abre es que el destinatario escriba.
     *
     * Es la primera vez que este transporte hace dos peticiones distintas para un reenvío. El
     * worker no se entera, y eso es lo correcto: el modo de entrega es del transporte.
     */
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

        // El plazo configurado gobierna los timeouts del socket, y el de la corrutina es solo
        // un respaldo con margen por encima. El orden importa y está explicado en el KDoc de
        // [Plazos]: invertirlo abandona peticiones que siguen en vuelo y pueden entregarse.
        val plazos = Plazos.de(timeoutSegundos)
        val credenciales = Credenciales(token, idNumero, destino)

        if (config.modoDeEntrega == ModoDeEntrega.SOLO_PLANTILLA) {
            return porPlantilla(credenciales, texto, config, plazos).resultado
        }

        val libre = porTextoLibre(credenciales, texto, plazos)

        val puedeSaltar = config.modoDeEntrega == ModoDeEntrega.AUTOMATICO &&
            config.plantillaNombre.isNotBlank()

        return if (libre.ventanaCerrada && puedeSaltar) {
            Timber.i("WhatsAppSender: ventana cerrada para '$destino', va por plantilla")
            porPlantilla(credenciales, texto, config, plazos).resultado
        } else {
            libre.resultado
        }
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

    /** Lo que hace falta para una petición, agrupado para no arrastrar tres parámetros. */
    private data class Credenciales(
        val token: String,
        val idNumero: String,
        val destino: String
    )

    /**
     * Resultado de un camino de entrega.
     *
     * [ventanaCerrada] es lo único que `ResultadoEnvio` no puede decir, y es lo que decide si
     * hay que probar el otro camino. Vive aquí, en el transporte, para que no tenga que subir
     * hasta `DispatchWorker`, que no sabe que hay dos caminos.
     */
    private data class Entrega(
        val resultado: ResultadoEnvio,
        val ventanaCerrada: Boolean = false
    )

    // ── Texto libre ───────────────────────────────────────────────────────────

    private suspend fun porTextoLibre(
        credenciales: Credenciales,
        texto: String,
        plazos: Plazos
    ): Entrega {
        // Pasarse de 4.096 caracteres es casi imposible y aun así posible. Trocear nunca pierde
        // contenido; dejar que Meta conteste con un error convertiría un mensaje largo en un
        // reenvío fallido.
        val trozos = MapeoDeRespuestas.trocear(texto, MapeoDeRespuestas.LIMITE_CARACTERES)
        if (trozos.size > 1) {
            Timber.d("WhatsAppSender: el texto va en ${trozos.size} mensajes por longitud")
        }

        return conRespaldo(plazos, trozos.size) {
            enviarTrozos(
                credenciales = credenciales,
                trozos = trozos,
                plazos = plazos,
                alEntregar = contadorDeMensajes::contarServicio,
                cuerpo = { cuerpoDeTexto(credenciales, it) }
            )
        }
    }

    // ── Plantilla ─────────────────────────────────────────────────────────────

    private suspend fun porPlantilla(
        credenciales: Credenciales,
        texto: String,
        config: AppConfig,
        plazos: Plazos
    ): Entrega {
        if (config.plantillaNombre.isBlank()) {
            return Entrega(
                ResultadoEnvio.ErrorPermanente(
                    "hace falta una plantilla para entregar a este número y no hay ninguna " +
                        "configurada en Ajustes"
                )
            )
        }

        // Aplanar puede dejar vacío un texto que solo tuviera espacios y saltos. Un parámetro
        // vacío es un 131009, y decirlo aquí explica mucho mejor lo que ha pasado.
        val parametros = PlantillaDeWhatsApp.prepararParametros(texto)
        if (parametros.isEmpty()) {
            return Entrega(
                ResultadoEnvio.ErrorPermanente(
                    "el texto se queda vacío al prepararlo para la plantilla"
                )
            )
        }
        if (parametros.size > 1) {
            // Esto cuesta dinero: cada trozo es un mensaje de plantilla facturado.
            Timber.w(
                "WhatsAppSender: el texto va en ${parametros.size} mensajes de plantilla, " +
                    "y cada uno se factura"
            )
        }

        return conRespaldo(plazos, parametros.size) {
            enviarTrozos(
                credenciales = credenciales,
                trozos = parametros,
                plazos = plazos,
                alEntregar = contadorDeMensajes::contarPlantilla,
                cuerpo = { cuerpoDePlantilla(credenciales, it, config) }
            )
        }
    }

    // ── Mecánica común ────────────────────────────────────────────────────────

    /**
     * Envuelve el envío en el respaldo de la corrutina, proporcional al número de peticiones.
     */
    private suspend fun conRespaldo(
        plazos: Plazos,
        peticiones: Int,
        bloque: suspend () -> Entrega
    ): Entrega {
        val respaldoMs = plazos.totalMs * peticiones + MARGEN_RESPALDO_MS
        return withTimeoutOrNull(respaldoMs) { bloque() }
            ?: Entrega(
                ResultadoEnvio.ErrorTransitorio("Meta no contestó en ${respaldoMs / 1000} s")
            )
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
        credenciales: Credenciales,
        trozos: List<String>,
        plazos: Plazos,
        alEntregar: suspend () -> Unit,
        cuerpo: (String) -> String
    ): Entrega {
        for (trozo in trozos) {
            val entrega = enviarUno(credenciales, cuerpo(trozo), plazos)
            if (entrega.resultado !is ResultadoEnvio.Enviado) return entrega
            // Se cuenta aquí, por trozo entregado, y no una vez por reenvío: Meta factura por
            // mensaje, así que contar reenvíos daría una cifra que no se parece a la factura.
            alEntregar()
        }
        return Entrega(ResultadoEnvio.Enviado)
    }

    /** El JSON de un mensaje de texto libre. */
    private fun cuerpoDeTexto(credenciales: Credenciales, texto: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                ponerSobre(credenciales)
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

    /**
     * El JSON de un mensaje de plantilla, con el contrato de una sola variable.
     *
     * El valor que entra en `{{1}}` viene ya aplanado por [PlantillaDeWhatsApp]: un salto de
     * línea aquí devuelve un `132007` y cierra el reenvío.
     */
    private fun cuerpoDePlantilla(
        credenciales: Credenciales,
        parametro: String,
        config: AppConfig
    ): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            ponerSobre(credenciales)
            put("type", "template")
            put(
                "template",
                buildJsonObject {
                    put("name", config.plantillaNombre)
                    put("language", JsonObject(mapOf("code" to JsonPrimitive(config.plantillaIdioma))))
                    put(
                        "components",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "body")
                                    put(
                                        "parameters",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", parametro)
                                                }
                                            )
                                        )
                                    )
                                }
                            )
                        )
                    )
                }
            )
        }
    )

    /** El sobre común a los dos tipos de mensaje. */
    private fun JsonObjectBuilder.ponerSobre(credenciales: Credenciales) {
        put("messaging_product", "whatsapp")
        // Fijo: los destinos de esta aplicación son siempre personas, nunca grupos.
        put("recipient_type", "individual")
        put("to", DestinoDeWhatsApp.aDestinatarioApi(credenciales.destino))
    }

    private suspend fun enviarUno(
        credenciales: Credenciales,
        cuerpoPeticion: String,
        plazos: Plazos
    ): Entrega = withContext(Dispatchers.IO) {

        val destino = credenciales.destino
        var conexion: HttpURLConnection? = null
        try {
            val url = "$BASE$API/${credenciales.idNumero}/messages"
            conexion = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = plazos.conexionMs
                readTimeout = plazos.lecturaMs
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${credenciales.token}")
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
            Entrega(
                resultado = MapeoDeRespuestas.interpretarEnvio(codigo, cuerpo),
                ventanaCerrada = MapeoDeRespuestas.esVentanaCerrada(cuerpo)
            )

        } catch (e: Exception) {
            // Sin red, DNS caído, timeout de socket: todo transitorio. El outbox no pierde
            // nada y el worker volverá cuando haya conexión.
            Timber.w(e, "WhatsAppSender: fallo de red enviando a '$destino'")
            Entrega(
                ResultadoEnvio.ErrorTransitorio(
                    "${e.javaClass.simpleName}: ${e.message ?: "fallo de red"}"
                )
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
         *
         * ## Por qué se subió de la v22.0 a la v26.0 (18 oct 2026)
         * Meta la deprecó, y lo dice en una cabecera de respuesta que el cuerpo del error no
         * menciona: `x-ad-api-version-warning: The call has been auto-upgraded to v26.0 as
         * v22.0 has been deprecated`. Pedir una versión deprecada funciona hasta que deja de
         * hacerlo, así que se sube.
         *
         * **No fue la causa del incidente que lo destapó, y conviene dejarlo escrito para que
         * nadie lo dé por arreglado.** El síntoma eran tres SMS agotando sus diez reintentos
         * contra un `HTTP 500` con `error.code` **1** («An unknown error has occurred»). Se
         * reprodujo con `curl`, fuera de la aplicación, con la misma petición byte por byte: el
         * mismo 500. Y en esa respuesta Meta devolvía ya `facebook-api-version: v26.0`, es
         * decir, **la llamada se estaba sirviendo auto-promocionada y fallaba igual**. La causa
         * estaba en el estado de la cuenta, no en la versión ni en esta clase.
         *
         * Lo que sí deja ese incidente como método, y vale para el siguiente:
         * - Un fallo de entrega **sin código específico de WhatsApp** en el registro —ni
         *   `131047`, ni `131026`, ni `132xxx`— significa que Meta no llegó a evaluar el
         *   mensaje. No busques la causa en el mensaje.
         * - Las **cabeceras** de la respuesta dicen cosas que el cuerpo calla.
         * - Reproducir con `curl` antes de tocar código: separa en un minuto «la app construye
         *   mal la petición» de «la cuenta no está lista».
         */
        const val API = "v26.0"

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
