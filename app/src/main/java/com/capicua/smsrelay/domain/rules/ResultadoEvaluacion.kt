// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.rules

import com.capicua.smsrelay.domain.transport.Canal

/**
 * Resultado de evaluar las reglas contra un SMS entrante.
 *
 * [coincidencias] está en el mismo orden en que se aplicaron las reglas, de modo que la
 * primera posición corresponde a la regla de mayor prioridad que casó.
 *
 * [reglasInvalidas] recoge las reglas que no se pudieron evaluar porque su expresión regular
 * no compila. Se separan del resultado en lugar de hacer fallar toda la evaluación: una regla
 * mal escrita no debe impedir que las demás reenvíen. Quien llama debe registrarlas en el log.
 */
data class ResultadoEvaluacion(
    val coincidencias: List<Coincidencia> = emptyList(),
    val reglasInvalidas: List<ReglaInvalida> = emptyList()
) {
    /** true si al menos una regla casó y, por tanto, hay algo que reenviar. */
    val hayCoincidencias: Boolean get() = coincidencias.isNotEmpty()
}

/** Una regla que casó, con su canal, su destino y el texto ya resuelto. */
data class Coincidencia(
    val reglaId: Long,
    val nombreRegla: String,

    /** Por dónde sale, copiado de la regla. */
    val canal: Canal,

    /**
     * Destino final, en el formato de [canal]: el de la regla tal cual, salvo en el canal SMS
     * con `{telefono}`, donde es el remitente.
     */
    val destino: String,

    val textoFinal: String,

    /**
     * true si la regla, del canal SMS, declaraba `{telefono}` como destino: el reenvío vuelve al
     * remitente **a propósito**, y la guarda antibucle no debe bloquearlo.
     */
    val respondeAlRemitente: Boolean = false
)

/** Una regla descartada porque su expresión regular no compila. */
data class ReglaInvalida(
    val reglaId: Long,
    val nombreRegla: String,
    val campo: CampoRegla,
    val patron: String,
    val motivo: String
)

/** Campo de la regla sobre el que se aplica una expresión regular. */
enum class CampoRegla {
    TELEFONO,
    MENSAJE
}
