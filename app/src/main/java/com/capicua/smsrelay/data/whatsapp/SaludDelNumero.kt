// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.whatsapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lee el `health_status` del número emisor: si Meta le deja enviar y, si no, por qué.
 *
 * ## Por qué hace falta
 * Que el token y el id sirvan **no** significa que se pueda enviar. La fase 10 lo encontró en el
 * teléfono: «Comprobar» decía «✓ Las credenciales sirven» y cada envío volvía con un `500` y el
 * código `1`, *An unknown error has occurred*, que no dice nada. La razón estaba en el
 * `health_status` del número: el número sin registrar, el método de pago, la cuenta de WhatsApp
 * Business inactiva y la empresa sin verificar. Ninguno de esos problemas es de la aplicación,
 * pero quien opera tiene que poder leerlos en Ajustes y no en la consola de Meta.
 *
 * ## Qué cuenta como bloqueo
 * Los errores de cada entidad —número, cuenta, empresa, app— cuyo `can_send_message` es
 * `BLOCKED`. Los de las llamadas por SIP (`138xxx`) se ignoran: vienen en la misma entidad, y no
 * tienen nada que ver con enviar mensajes.
 *
 * Es Kotlin puro para poder probarlo sin red.
 */
object SaludDelNumero {

    /**
     * Los motivos por los que Meta bloquea el envío, uno por error, legibles y con su código.
     * Vacía si el número puede enviar, si el cuerpo no trae `health_status` o si no se entiende:
     * ante la duda no se inventa un bloqueo.
     */
    fun bloqueos(cuerpo: String?): List<String> {
        val salud = runCatching {
            cuerpo?.let { json.decodeFromString<Respuesta>(it).salud }
        }.getOrNull() ?: return emptyList()
        if (salud.puedeEnviar != BLOQUEADO) return emptyList()

        return salud.entidades
            .filter { it.puedeEnviar == BLOQUEADO }
            .flatMap { it.errores }
            .filterNot { it.codigo in LLAMADAS_SIP }
            .mapNotNull { error ->
                error.descripcion?.takeIf { it.isNotBlank() }?.let { "$it (${error.codigo})" }
            }
            .distinct()
    }

    private const val BLOQUEADO = "BLOCKED"
    private val LLAMADAS_SIP = 138000..138999

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Respuesta(@SerialName("health_status") val salud: Salud? = null)

    @Serializable
    private data class Salud(
        @SerialName("can_send_message") val puedeEnviar: String? = null,
        @SerialName("entities") val entidades: List<Entidad> = emptyList()
    )

    @Serializable
    private data class Entidad(
        @SerialName("can_send_message") val puedeEnviar: String? = null,
        @SerialName("errors") val errores: List<Error> = emptyList()
    )

    @Serializable
    private data class Error(
        @SerialName("error_code") val codigo: Int? = null,
        @SerialName("error_description") val descripcion: String? = null
    )
}
