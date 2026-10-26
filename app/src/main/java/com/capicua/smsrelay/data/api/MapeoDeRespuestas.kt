// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.api

import com.capicua.smsrelay.domain.transport.ResultadoEnvio

/**
 * Traduce la respuesta de la API configurada a un [ResultadoEnvio].
 *
 * Es el reparto de SMSGateway, sin cambios, y vive aparte de quien hace la petición por lo mismo
 * que sus hermanos de `data/telegram/` y `data/whatsapp/`: es lo único del transporte que se
 * puede probar sin red, y `MapeoDeRespuestasTest` lo cubre.
 *
 * | Respuesta | Resultado | Por qué |
 * |-----------|-----------|---------|
 * | `2xx` | [ResultadoEnvio.Enviado] | El servidor tiene el mensaje |
 * | `429` | Transitorio | Demasiadas peticiones: esperar lo arregla |
 * | `4xx` salvo `429` | Permanente | La petición está mal, y un reintento manda los mismos bytes |
 * | `5xx` | Transitorio | Avería del servidor |
 * | `3xx` | Permanente | `HttpURLConnection` no sigue una redirección de `http` a `https`; hay que corregir la URL |
 * | Otro | Transitorio | Por prudencia: lo desconocido se reintenta |
 *
 * A diferencia de Telegram y de la Cloud API, aquí no se lee el cuerpo: es un servidor de quien
 * opera la aplicación, con el formato que él quiera, y SMSGateway nunca lo interpretó.
 */
object MapeoDeRespuestas {

    fun interpretarEnvio(codigo: Int): ResultadoEnvio = when (codigo) {
        in 200..299 -> ResultadoEnvio.Enviado

        429 -> ResultadoEnvio.ErrorTransitorio("HTTP 429 — demasiadas peticiones")

        in 300..399 -> ResultadoEnvio.ErrorPermanente(
            "HTTP $codigo — el servidor redirige; escribe en la regla la URL final"
        )

        in 400..499 -> ResultadoEnvio.ErrorPermanente("HTTP $codigo — el servidor rechazó la petición")

        in 500..599 -> ResultadoEnvio.ErrorTransitorio("HTTP $codigo — el servidor no está disponible")

        else -> ResultadoEnvio.ErrorTransitorio("HTTP $codigo")
    }

    /**
     * Longitud orientativa del texto que cabe en la URL.
     *
     * No es un límite del protocolo sino de los servidores: muchos rechazan con un `414` las URL
     * de más de unos 8 KB, y algunos proxies cortan en 2 KB. El texto además crece al
     * codificarse —un acento son seis caracteres—, así que el editor avisa pronto. Pasarse no
     * trocea, porque una petición por trozo no es lo que espera ningún servidor: si el servidor
     * no la acepta, el reenvío se cierra como fallido y el motivo lo dice.
     */
    const val LIMITE_CARACTERES = 1500
}
