// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.whatsapp

import com.capicua.smstowhats.domain.transport.ResultadoEnvio
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Traduce lo que contesta la Cloud API a un [ResultadoEnvio].
 *
 * Es la pieza más importante del transporte y la única que se puede probar sin red, y por eso
 * está separada de quien hace la petición: [interpretarEnvio] es una función pura de
 * (código HTTP, cuerpo) a resultado, y `MapeoDeRespuestasTest` la cubre caso por caso.
 *
 * ## Lo que cambia respecto a Telegram: el código HTTP ya no basta
 * Esta es la diferencia de fondo con la versión anterior de esta clase. El Bot API usaba el
 * estado HTTP como clasificación —un `400` era «este mensaje no se puede entregar», un `401` era
 * «el token no vale»— y bastaba con mirarlo. La Cloud API mete **cuatro averías distintas en el
 * mismo `400`**, y las distingue en `error.code` del cuerpo:
 *
 * | Mismo HTTP | `error.code` | Qué es en realidad |
 * |------------|--------------|--------------------|
 * | `400` | `131047` | La ventana de 24 h está cerrada |
 * | `400` | `131026` | El destinatario no tiene WhatsApp |
 * | `400` | `131021` | El destinatario es el propio número emisor |
 * | `400` | `132012` | La plantilla y sus parámetros no cuadran |
 *
 * Clasificarlas todas igual es lo que convierte un problema de configuración en cuatrocientas
 * líneas idénticas de registro, con la primera siendo la única informativa. Así que **la
 * clasificación vive en `error.code`** y el estado HTTP queda como red de seguridad para lo que
 * no traiga cuerpo legible.
 *
 * ## Por qué el reparto es este
 * Clasificar mal un error cuesta caro en las dos direcciones. Marcar como permanente algo
 * transitorio pierde un mensaje que habría llegado al segundo intento; marcar como transitorio
 * algo permanente gasta diez reintentos y diez líneas de log para acabar en el mismo sitio.
 *
 * El criterio que resuelve los casos dudosos es: **¿puede cambiar la respuesta si repito la
 * misma petición, byte por byte, dentro de unos minutos?** Si la respuesta es no —el destinatario
 * no existe, la plantilla no está aprobada, el token está revocado— es permanente. Si es sí —hay
 * demasiado tráfico, Meta está caída, no hay red— es transitorio.
 *
 * ## El `message` de Meta se incorpora tal cual
 * Es texto en inglés y poco bonito —«(#131026) Unable to deliver message. This may be because
 * the recipient is not a valid WhatsApp user»— pero es exactamente lo que hace falta leer en el
 * registro para entender qué pasó, y traducirlo a mano sería inventarse un diccionario que se
 * queda corto en cuanto Meta añada un caso. Lo que sí se añade por delante es el `error.code`,
 * porque es la clave con la que se busca en la documentación.
 */
object MapeoDeRespuestas {

    /**
     * Interpreta la respuesta de `POST /<phone_number_id>/messages`.
     *
     * @param codigo código HTTP devuelto.
     * @param cuerpo cuerpo de la respuesta, o null si no se pudo leer.
     */
    fun interpretarEnvio(codigo: Int, cuerpo: String?): ResultadoEnvio {
        val respuesta = interpretarCuerpo(cuerpo)
        val error = respuesta?.error

        // Un 2xx sin bloque `error` es una entrega aceptada. La Cloud API devuelve el wamid en
        // `messages[].id`, pero no se exige su presencia para dar el envío por bueno: si Meta
        // contestó 2xx y no se queja, tiene el mensaje.
        if (codigo in 200..299 && error == null) {
            return ResultadoEnvio.Enviado
        }

        val codigoError = error?.codigo
        val motivo = motivo(codigo, error)

        return when {
            // ── Credencial: no es este reenvío, es que no va a salir ninguno ──
            esFalloDeCredencial(codigo, cuerpo) -> ResultadoEnvio.ErrorPermanente(motivo)

            // ── La ventana de 24 h está cerrada ──
            // Permanente *en texto libre*, que es lo único que este transporte sabe hacer
            // todavía. El motivo dice qué hay que hacer, porque «HTTP 400» no lo dice.
            codigoError == VENTANA_CERRADA -> ResultadoEnvio.ErrorPermanente(
                "$motivo — la ventana de 24 h está cerrada: este destinatario solo puede " +
                    "recibir mensajes de plantilla hasta que vuelva a escribir al número"
            )

            // ── Límites de caudal ──
            // Meta **no** manda `retry_after` ni `Retry-After`, así que no hay espera que
            // sugerir: va null y el worker aplica su backoff de siempre. Es la única
            // diferencia práctica con el 429 de Telegram.
            codigoError in LIMITE || codigo == 429 ->
                ResultadoEnvio.ErrorTransitorio(motivo, esperaSegundos = null)

            // ── Averías del lado de Meta ──
            codigoError in TRANSITORIO || codigo in 500..599 ->
                ResultadoEnvio.ErrorTransitorio(motivo)

            // ── Permanentes conocidos ──
            codigoError in PERMANENTE -> ResultadoEnvio.ErrorPermanente(motivo)

            // ── Lo desconocido, repartido por el estado HTTP ──
            // Un 4xx que no reconocemos se cierra como permanente: el reintento manda los
            // **mismos bytes** a la misma URL, así que un «tu petición está mal» no puede
            // cambiar de respuesta por esperar. El motivo lleva el `error.code` dentro, que es
            // con lo que se busca en la documentación de Meta.
            //
            // Cualquier otra cosa —un 3xx inesperado, un código fuera de rango— se reintenta
            // por prudencia: lo desconocido que no es una queja explícita sobre la petición se
            // parece más a una avería que a un error nuestro.
            codigo in 400..499 -> ResultadoEnvio.ErrorPermanente(motivo)

            else -> ResultadoEnvio.ErrorTransitorio(motivo)
        }
    }

    /**
     * true si la respuesta indica que el problema son las credenciales y no este mensaje.
     *
     * Lo usa el transporte para distinguir «este reenvío no se puede entregar» de «ningún
     * reenvío se va a poder entregar hasta que alguien toque los ajustes», que es una avería
     * distinta y merece verse en el Panel y no enterrada en el registro del primero de
     * cuatrocientos reenvíos fallidos.
     *
     * **Son dos credenciales, y las dos caen aquí.** Con Telegram había una sola y el token iba
     * dentro de la URL, así que bastaba con el 401 y el 404. Aquí el token viaja en la cabecera
     * y el `phone_number_id` en la ruta, de modo que fallan por sitios distintos:
     *
     * | Código | Qué está mal |
     * |--------|--------------|
     * | `190`, `0`, `3` | El token: caducado, revocado o sin los permisos necesarios |
     * | `100` | El `phone_number_id`: Meta dice que ese objeto no existe |
     *
     * El `100` es el que sorprende, porque llega con un `400` y un texto genérico
     * («Unsupported post request»). Es el equivalente exacto del 404 de Telegram: lo que no
     * existe es la ruta, y la ruta la forma la credencial.
     */
    fun esFalloDeCredencial(codigo: Int, cuerpo: String?): Boolean {
        val codigoError = interpretarCuerpo(cuerpo)?.error?.codigo
        if (codigoError != null) return codigoError in CREDENCIAL
        // Sin cuerpo legible, el estado HTTP es lo único que queda.
        return codigo == 401 || codigo == 403
    }

    /**
     * Longitud máxima del `text.body` de un mensaje de texto.
     *
     * Es **el mismo número** que el límite del Bot API de Telegram, lo que permitió que
     * [trocear] sobreviviera literal al cambio de medio. No cuenta para los mensajes de
     * plantilla, que tienen su propio presupuesto y mucho más corto: ver `PlantillaDeWhatsApp`.
     */
    const val LIMITE_CARACTERES = 4096

    /**
     * Parte [texto] en trozos que quepan en [limite].
     *
     * Un SMS concatenado largo más una plantilla generosa es la única forma realista de pasarse
     * de 4.096 caracteres, así que esto casi nunca se usa en texto libre. Existe porque la
     * alternativa —cortar y tirar el resto— pierde contenido en silencio, y porque dejar que
     * Meta devuelva un error convertiría un mensaje largo en un reenvío fallido.
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

    // ── Códigos de error de la Cloud API ──────────────────────────────────────

    /** Re-engagement: han pasado más de 24 h desde el último mensaje del destinatario. */
    const val VENTANA_CERRADA = 131047

    /** El problema son las credenciales, no el mensaje. Ver [esFalloDeCredencial]. */
    private val CREDENCIAL = setOf(0, 3, 190, 100)

    /** Límites de caudal. Se reintentan sin espera sugerida: Meta no dice cuánto esperar. */
    private val LIMITE = setOf(
        80007,  // La WABA ha alcanzado su límite
        130429, // Caudal de la Cloud API superado
        131048, // El número emisor está restringido por historial de spam
        131056, // Demasiados mensajes al mismo destinatario en poco tiempo
        133016  // El número está temporalmente bloqueado por reintentos
    )

    /** Averías del lado de Meta. Se reintentan. */
    private val TRANSITORIO = setOf(
        1,      // Error de API no clasificado
        2,      // Servicio temporalmente no disponible
        131000, // Error desconocido; Meta recomienda reintentar
        133004  // Servidor temporalmente no disponible
    )

    /** Fallos que no se arreglan reintentando. Cierran el reenvío. */
    private val PERMANENTE = setOf(
        131008, // Falta un parámetro obligatorio
        131009, // Un parámetro tiene un valor inválido
        131021, // Emisor y destinatario son el mismo número
        131026, // El destinatario no es un usuario válido de WhatsApp
        131051, // Tipo de mensaje no soportado
        132000, // El número de parámetros no coincide con el de la plantilla
        132001, // La plantilla no existe en ese idioma
        132005, // El texto renderizado de la plantilla es demasiado largo
        132007, // El contenido de un parámetro viola el formato de plantilla
        132012, // Formato de parámetro inválido
        132015, // La plantilla está pausada por mala calidad
        132016, // La plantilla está deshabilitada
        368     // El número está bloqueado por incumplir las políticas
    )

    // ── Interno ───────────────────────────────────────────────────────────────

    private fun motivo(codigo: Int, error: ErrorJson?): String {
        val mensaje = error?.mensaje?.takeIf { it.isNotBlank() }
            ?: error?.datos?.detalles?.takeIf { it.isNotBlank() }
        return when {
            error?.codigo != null && mensaje != null ->
                "HTTP $codigo · error ${error.codigo} — $mensaje"
            error?.codigo != null -> "HTTP $codigo · error ${error.codigo}"
            mensaje != null -> "HTTP $codigo — $mensaje"
            else -> "HTTP $codigo"
        }
    }

    private fun interpretarCuerpo(cuerpo: String?): RespuestaJson? {
        if (cuerpo.isNullOrBlank()) return null
        return try {
            json.decodeFromString<RespuestaJson>(cuerpo)
        } catch (e: Exception) {
            // Un cuerpo ilegible no impide clasificar: el estado HTTP decide. Esto solo aporta
            // el código y el mensaje, y sin ellos el motivo queda con el HTTP a secas.
            null
        }
    }

    @Serializable
    private data class RespuestaJson(
        @SerialName("error") val error: ErrorJson? = null
    )

    @Serializable
    private data class ErrorJson(
        @SerialName("message") val mensaje: String? = null,
        @SerialName("code") val codigo: Int? = null,
        @SerialName("error_subcode") val subcodigo: Int? = null,
        @SerialName("error_data") val datos: ErrorDatosJson? = null
    )

    @Serializable
    private data class ErrorDatosJson(
        @SerialName("details") val detalles: String? = null
    )

    /** La respuesta trae campos que aquí no interesan, y traerá más con el tiempo. */
    private val json = Json { ignoreUnknownKeys = true }
}
