// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.api

import com.capicua.smsrelay.data.config.ConfigDataStore
import com.capicua.smsrelay.domain.rules.PlantillaDeUrl
import com.capicua.smsrelay.domain.transport.EnviadorDeMensajes
import com.capicua.smsrelay.domain.transport.ResultadoEnvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Entrega mensajes con una petición `GET` a la API de quien opera la aplicación.
 *
 * Es lo que hacía SMSGateway con todos los SMS, y aquí es una regla del canal API. El destino
 * que recibe es la URL **ya resuelta**: los marcadores los rellenó `EvaluadorDeReglas` con
 * [PlantillaDeUrl], que es donde vive la condición de seguridad. Este transporte no sustituye
 * nada, y el `texto` que recibe solo sirve para comprobar que hay algo que mandar: ya va dentro
 * de la URL.
 *
 * SMSGateway usaba OkHttp. Aquí es `HttpURLConnection`, el patrón de la casa desde
 * SMStoTelegram: una petición no paga una dependencia.
 *
 * ## La URL no se registra entera
 * La query lleva el mensaje y, con frecuencia, una clave de la API. Los `Timber` de esta clase
 * hablan del servidor y la ruta ([PlantillaDeUrl.sinQuery]) y del código de respuesta.
 */
@Singleton
class ApiSender @Inject constructor(
    private val configDataStore: ConfigDataStore
) : EnviadorDeMensajes {

    override suspend fun limiteCaracteres(): Int = MapeoDeRespuestas.LIMITE_CARACTERES

    /**
     * Siempre null: el canal API no tiene credencial global. Lo que puede estar mal es la URL de
     * cada regla, y eso lo valida el editor al guardarla.
     */
    override suspend fun motivoNoDisponible(): String? = null

    override suspend fun enviar(
        destino: String,
        texto: String,
        timeoutSegundos: Int
    ): ResultadoEnvio {
        if (texto.isEmpty()) {
            return ResultadoEnvio.ErrorPermanente("el texto a enviar está vacío")
        }
        if (!destino.startsWith("http://", ignoreCase = true) &&
            !destino.startsWith("https://", ignoreCase = true)
        ) {
            return ResultadoEnvio.ErrorPermanente("el destino no es una URL http o https")
        }

        val aceptarInvalidos = configDataStore.config.first().aceptarCertificadosInvalidos
        val totalMs = TimeUnit.SECONDS.toMillis(timeoutSegundos.toLong()).toInt()
        // Un tercio para conectar y el resto para leer, con el socket gobernando el plazo y la
        // corrutina solo de respaldo: el razonamiento entero está en TelegramSender.Plazos.
        val conexionMs = (totalMs / 3).coerceIn(5_000, 30_000)
        val lecturaMs = (totalMs - conexionMs).coerceAtLeast(5_000)

        return withTimeoutOrNull((conexionMs + lecturaMs).toLong() + MARGEN_RESPALDO_MS) {
            pedir(destino, conexionMs, lecturaMs, aceptarInvalidos)
        } ?: ResultadoEnvio.ErrorTransitorio("el servidor no contestó a tiempo")
    }

    private suspend fun pedir(
        url: String,
        conexionMs: Int,
        lecturaMs: Int,
        aceptarInvalidos: Boolean
    ): ResultadoEnvio = withContext(Dispatchers.IO) {
        val visible = PlantillaDeUrl.sinQuery(url)
        var conexion: HttpURLConnection? = null
        try {
            conexion = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = conexionMs
                readTimeout = lecturaMs
                // Una redirección de http a https no se sigue y la de https a http no debe
                // seguirse; mejor decir que la URL no es la final.
                instanceFollowRedirects = false
                if (aceptarInvalidos && this is HttpsURLConnection) {
                    sslSocketFactory = fabricaSinVerificar
                    setHostnameVerifier { _, _ -> true }
                }
            }

            val codigo = conexion.responseCode
            // El cuerpo no se interpreta, pero se lee y se descarta para que la conexión se
            // pueda reutilizar.
            runCatching {
                (if (codigo in 200..299) conexion.inputStream else conexion.errorStream)
                    ?.use { it.readBytes() }
            }

            Timber.d("ApiSender: GET $visible → HTTP $codigo")
            MapeoDeRespuestas.interpretarEnvio(codigo)

        } catch (e: MalformedURLException) {
            ResultadoEnvio.ErrorPermanente("la URL no es válida: ${e.message}")
        } catch (e: Exception) {
            // Sin red, DNS, timeout, certificado rechazado: todo transitorio, como en SMSGateway.
            // Un certificado no se arregla solo, pero quien lo arregle no debería tener que
            // volver a crear los reenvíos que esperaban.
            Timber.w(e, "ApiSender: fallo llamando a $visible")
            ResultadoEnvio.ErrorTransitorio("${e.javaClass.simpleName}: ${e.message ?: "fallo de red"}")
        } finally {
            conexion?.disconnect()
        }
    }

    private companion object {
        const val MARGEN_RESPALDO_MS = 10_000L

        /**
         * Confía en cualquier certificado. Solo se usa con `aceptarCertificadosInvalidos`, que
         * existe para un servidor de la red local con un certificado autofirmado y que Ajustes
         * avisa que no se use en Internet.
         */
        val fabricaSinVerificar: SSLSocketFactory by lazy {
            val confiarEnTodo = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS")
                .apply { init(null, arrayOf(confiarEnTodo), SecureRandom()) }
                .socketFactory
        }
    }
}
