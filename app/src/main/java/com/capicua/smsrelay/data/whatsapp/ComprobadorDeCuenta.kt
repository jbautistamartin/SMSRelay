// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.whatsapp

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

/** Lo que puede contestar [ComprobadorDeCuenta.comprobar]. */
sealed interface ResultadoDeCuenta {

    /**
     * Las credenciales sirven, y este es el número al que pertenecen.
     *
     * @param nombre nombre verificado del negocio, tal como lo ve quien recibe el mensaje.
     * @param telefono el número en formato legible, como lo enseña la consola.
     * @param calidad `GREEN`, `YELLOW`, `RED` o `UNKNOWN`. Ver el KDoc de la clase.
     * @param bloqueos por qué Meta no deja enviar desde este número aunque las credenciales
     *   sirvan; vacía si puede. Ver [SaludDelNumero].
     */
    data class Correcto(
        val nombre: String,
        val telefono: String,
        val calidad: String,
        val bloqueos: List<String> = emptyList()
    ) : ResultadoDeCuenta

    /** Meta dice que las credenciales no valen. No es un problema de red. */
    data class Rechazado(val motivo: String) : ResultadoDeCuenta

    /** No se pudo preguntar: sin red, Meta caída, respuesta ilegible. */
    data class NoSePudo(val motivo: String) : ResultadoDeCuenta

    /** No hay nada que comprobar porque falta alguna credencial. */
    data object SinCredenciales : ResultadoDeCuenta
}

/**
 * Pregunta a Meta si las credenciales sirven, consultando el número emisor.
 *
 * ## Por qué hace falta un botón para esto
 * La forma de las credenciales se puede comprobar sin red —eso son [TokenDeAcceso] e
 * [IdNumeroTelefono]— pero si *sirven* solo lo sabe Meta. Sin este botón, la única forma de
 * descubrir que algo está mal es esperar a que llegue un SMS y verlo marcado como fallido,
 * probablemente horas después y con el teléfono ya colocado en su sitio. Es exactamente la clase
 * de error que hay que descubrir mientras todavía tienes el móvil en la mano.
 *
 * Y no contesta «sí» o «no»: contesta **de qué número se trata**. Enseñar el nombre verificado y
 * el teléfono convierte la comprobación en algo verificable por la persona que la pide, que puede
 * reconocer su número o darse cuenta de que ha pegado el id de otro.
 *
 * ## No hay `getMe`, y lo que hay es mejor
 * El Bot API tenía una llamada hecha para esto. La Cloud API no, así que se consulta el propio
 * objeto del número: `GET /<phone_number_id>?fields=…`. Tiene la misma propiedad que hacía de
 * `getMe` la llamada correcta —no envía nada, no tiene efectos, no gasta cuota apreciable— y
 * **ejercita las dos credenciales a la vez**, que es justo lo que hace falta comprobar: el token
 * en la cabecera y el id en la ruta. Si cualquiera de los dos está mal, esto falla.
 *
 * ## `quality_rating`: información que Telegram no daba
 * Meta puntúa cada número emisor según cuánta gente lo bloquea o lo denuncia, y cuando baja a
 * `RED` **limita o bloquea el número**. Es el riesgo real de una aplicación que entrega mensajes
 * que el destinatario no ha pedido, y es el único de los riesgos de este proyecto que avisa
 * antes de materializarse. Por eso se trae aquí y se pinta en Ajustes: ver un `YELLOW` a tiempo
 * es poder hacer algo; descubrir el bloqueo es no poder.
 *
 * ## Que sirvan no es que se pueda enviar
 * Se pide también el `health_status` del número. Unas credenciales válidas de un número sin
 * registrar, o de una cuenta sin método de pago, pasan la consulta y fallan en cada envío con un
 * error que no explica nada. [SaludDelNumero] saca los motivos, y Ajustes los enseña.
 *
 * ## Por qué acepta credenciales candidatas
 * Se comprueban **antes** de guardar. Quien acaba de pegar un token quiere saber si sirve antes
 * de comprometerse, y obligar a guardar primero significaría dejar guardado algo que no vale.
 */
@Singleton
class ComprobadorDeCuenta @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val estadoDeLaCuenta: EstadoDeLaCuenta
) {

    /**
     * Comprueba las credenciales candidatas, o las guardadas si se omiten.
     *
     * Un resultado [ResultadoDeCuenta.Correcto] borra el aviso de «credencial rechazada»: acaba
     * de demostrarse que sirven. Un [ResultadoDeCuenta.Rechazado] **no** lo enciende, porque
     * puede tratarse de unas candidatas que ni se han guardado todavía; ese aviso habla de las
     * credenciales que la aplicación está usando, y lo pone quien intenta usarlas.
     */
    suspend fun comprobar(
        tokenCandidato: String? = null,
        idCandidato: String? = null
    ): ResultadoDeCuenta {
        val config = configDataStore.config.first()
        val token = TokenDeAcceso.normalizar(tokenCandidato ?: config.tokenAcceso)
        val id = IdNumeroTelefono.normalizar(idCandidato ?: config.idNumeroTelefono)

        if (token.isBlank() || id.isBlank()) return ResultadoDeCuenta.SinCredenciales

        if (!TokenDeAcceso.pareceValido(token)) {
            return ResultadoDeCuenta.Rechazado("el token no tiene forma de token de acceso")
        }
        if (!IdNumeroTelefono.pareceValido(id)) {
            return ResultadoDeCuenta.Rechazado(
                if (IdNumeroTelefono.pareceTelefono(id)) {
                    "eso es un número de teléfono; aquí va el id del número, que es otro dato"
                } else {
                    "el id del número no tiene la forma que da la consola de Meta"
                }
            )
        }

        val resultado = withContext(Dispatchers.IO) { preguntar(token, id) }

        // Que las credenciales sirvan borra el aviso: acaba de demostrarse lo contrario de lo
        // que decía. Se hace aquí, donde se puede suspender, y no dentro de interpretar(), que
        // así se queda siendo una función sin efectos.
        if (resultado is ResultadoDeCuenta.Correcto) {
            estadoDeLaCuenta.olvidarCredencialRechazada()
        }

        return resultado
    }

    private fun preguntar(token: String, id: String): ResultadoDeCuenta {
        var conexion: HttpURLConnection? = null
        return try {
            val url = "${WhatsAppSender.BASE}${WhatsAppSender.API}/$id?fields=$CAMPOS"
            conexion = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
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

            // El id va en la URL, así que la URL no se registra.
            Timber.d("ComprobadorDeCuenta: consulta del número → HTTP $codigo")

            interpretar(codigo, cuerpo)
        } catch (e: Exception) {
            Timber.w(e, "ComprobadorDeCuenta: no se pudo preguntar por el número")
            ResultadoDeCuenta.NoSePudo(e.message ?: "fallo de red")
        } finally {
            conexion?.disconnect()
        }
    }

    private fun interpretar(codigo: Int, cuerpo: String?): ResultadoDeCuenta {
        val respuesta = runCatching {
            cuerpo?.let { json.decodeFromString<NumeroJson>(it) }
        }.getOrNull()

        val mensajeDeError = respuesta?.error?.mensaje?.takeIf { it.isNotBlank() }

        if (MapeoDeRespuestas.esFalloDeCredencial(codigo, cuerpo)) {
            return ResultadoDeCuenta.Rechazado(
                mensajeDeError ?: "Meta no reconoce estas credenciales"
            )
        }

        if (codigo !in 200..299 || respuesta?.telefono == null) {
            return ResultadoDeCuenta.NoSePudo(
                mensajeDeError ?: "Meta respondió HTTP $codigo"
            )
        }

        return ResultadoDeCuenta.Correcto(
            nombre = respuesta.nombre?.takeIf { it.isNotBlank() } ?: "sin nombre verificado",
            telefono = respuesta.telefono,
            calidad = respuesta.calidad?.takeIf { it.isNotBlank() } ?: "UNKNOWN",
            bloqueos = SaludDelNumero.bloqueos(cuerpo)
        )
    }

    @Serializable
    private data class NumeroJson(
        @SerialName("verified_name") val nombre: String? = null,
        @SerialName("display_phone_number") val telefono: String? = null,
        @SerialName("quality_rating") val calidad: String? = null,
        @SerialName("error") val error: ErrorJson? = null
    )

    @Serializable
    private data class ErrorJson(
        @SerialName("message") val mensaje: String? = null
    )

    private companion object {
        const val CAMPOS = "verified_name,display_phone_number,quality_rating,health_status"
        const val TIMEOUT_MS = 15_000

        val json = Json { ignoreUnknownKeys = true }
    }
}
