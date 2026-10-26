// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.transport

/**
 * Resultado de intentar entregar un mensaje por el transporte configurado.
 *
 * La distinción entre transitorio y permanente es la que gobierna la política de reintentos de
 * `DispatchWorker`: un error transitorio vuelve a la cola, uno permanente cierra el reenvío. Esa
 * clasificación es la única cosa que el worker necesita saber, y es la razón de que este tipo
 * viva en `domain` y no junto a la implementación: **nada aquí nombra el medio**.
 *
 * Que la clasificación ya existiera así es lo que hizo que los **dos** cambios de medio fuesen
 * una fase de transporte y no una reescritura. Para el SMS, «transitorio» era no tener cobertura
 * o la radio apagada; para Telegram era un 429 o un 503; para la Cloud API es un `130429` o un
 * `133004`. El worker no distingue, y no debe.
 */
sealed interface ResultadoEnvio {

    /** El transporte confirmó la entrega. */
    data object Enviado : ResultadoEnvio

    /**
     * Fallo que puede desaparecer solo: sin red, el servicio no contesta, o la confirmación no
     * llegó dentro del plazo. Se reintenta con el backoff de WorkManager.
     *
     * @param esperaSegundos espera que el propio servicio ha pedido, si lo ha hecho. Se escribió
     *   para el `retry_after` que Telegram devolvía con un `429`: el servicio sabe mejor que
     *   nosotros cuánto hay que esperar, y reintentar antes solo gasta otro `429`. **Meta no
     *   manda nada parecido**, así que con la Cloud API va siempre null, que es lo que ya
     *   significaba «aplica el backoff de siempre». El campo se queda porque describe una
     *   propiedad del resultado, no una del medio.
     */
    data class ErrorTransitorio(
        val motivo: String,
        val esperaSegundos: Int? = null
    ) : ResultadoEnvio

    /**
     * Fallo que no se va a arreglar reintentando: el destino no existe, el mensaje es inválido
     * o falta la credencial. Se cierra el reenvío y se registra el motivo.
     */
    data class ErrorPermanente(val motivo: String) : ResultadoEnvio
}
