// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.model

import com.capicua.smsrelay.domain.transport.Canal

/**
 * Regla de reenvío configurada por el operador.
 *
 * Una regla decide **por dónde** y **a quién** se reenvía un SMS entrante, y **con qué texto**.
 * Las reglas se evalúan en orden ascendente de [orden]: gana la primera que case, salvo
 * que lleve [continuar] activado, en cuyo caso la evaluación sigue con las siguientes.
 *
 * ## Criterios de coincidencia
 * - [regexTelefono] se aplica sobre el número del remitente **tal como llega en la PDU**,
 *   sin normalizar. Puede ser alfanumérico (p. ej. `BANCO`, `AMAZON`).
 * - [regexMensaje] se aplica sobre el cuerpo completo del SMS.
 * - Ambos son opcionales: null o vacío significa «no filtres por este campo». Una regla con
 *   los dos vacíos casa con **todos** los SMS, útil como última regla de la lista.
 * - La coincidencia es **parcial**: el patrón `codigo` casa con «Tu codigo es 4821».
 *   Para exigir el texto completo hay que anclarlo con `^…$`.
 * - Las expresiones distinguen mayúsculas, salvo que se active [ignorarMayusculas]. También
 *   se puede usar el modificador `(?i)` dentro de la propia expresión.
 *
 * ## Plantilla
 * [plantilla] define el texto que se envía al destino, con tres marcadores opcionales:
 * `{mensaje}`, `{telefono}` y `{fecha}`. El valor por defecto es [PLANTILLA_POR_DEFECTO].
 */
data class Regla(
    /** 0 mientras la regla no se ha persistido; Room asigna el valor real al insertar. */
    val id: Long = 0,

    /** Posición en la lista. Menor valor = mayor prioridad. */
    val orden: Int,

    /** Etiqueta legible para identificar la regla en la lista y en los logs. */
    val nombre: String,

    /** Expresión regular sobre el número del remitente. Null o vacío = no filtra. */
    val regexTelefono: String? = null,

    /** Expresión regular sobre el cuerpo del SMS. Null o vacío = no filtra. */
    val regexMensaje: String? = null,

    /**
     * Si true, [regexTelefono] y [regexMensaje] se aplican **sin distinguir mayúsculas**.
     *
     * Equivale a anteponer `(?i)` a las dos expresiones, pero sin tener que conocer el
     * modificador ni acordarse de ponerlo en ambas. Si además se escribe `(?i)` a mano, no
     * pasa nada: el resultado es el mismo.
     *
     * **Por defecto true**, que es lo contrario de lo que parece prudente y es lo que la
     * práctica pide: el mismo remitente escribe «CODIGO», «Codigo» o «codigo» según el
     * operador y el día, y una regla que distinga mayúsculas deja de casar sin que nada lo
     * avise —no hay error, simplemente no reenvía—. Distinguirlas es la excepción, y se pide
     * apagando el interruptor de la pantalla de la regla.
     *
     * Ojo: esto es el valor de una regla **nueva**. Las reglas ya guardadas conservan el que
     * tuvieran, y las que existían antes de que la columna apareciera siguen con `false`
     * —ver la migración v1 → v2—, porque cambiarles el criterio por detrás alteraría lo que
     * casan en una instalación que ya funciona.
     */
    val ignorarMayusculas: Boolean = true,

    /**
     * Por dónde sale el reenvío. Decide qué es un [destino] válido, y por eso el editor lo pide
     * antes que el destino.
     */
    val canal: Canal = Canal.POR_DEFECTO,

    /**
     * A quién se entrega si la regla casa, **en el idioma de [canal]**: un número E.164 para
     * WhatsApp, un `chat_id` o un `@canal` para Telegram, un teléfono para el SMS, una plantilla
     * de URL para la API. Lo valida el editor según el canal.
     *
     * **No admite marcadores**, y eso es una propiedad de seguridad: **el cuerpo de un SMS de un
     * tercero no puede decidir nunca a dónde se manda nada**. Permitirlo convertiría un SMS
     * cualquiera en un selector de destinatario —en WhatsApp, sobre una cuenta de empresa que
     * factura por mensaje—.
     *
     * Hay dos excepciones, acotadas a su canal y explicadas donde se resuelven: `{telefono}` en
     * el canal SMS, que contesta a quien escribió y no puede apuntar a nadie más, y los
     * marcadores de la URL de la API, que rellenan huecos *después* del servidor y no pueden
     * elegir a qué servidor se llama.
     */
    val destino: String,

    /** Plantilla del texto a enviar. Ver [PLANTILLA_POR_DEFECTO]. */
    val plantilla: String = PLANTILLA_POR_DEFECTO,

    /** Si false, la regla se ignora durante la evaluación sin necesidad de borrarla. */
    val activa: Boolean = true,

    /**
     * Si true, tras casar esta regla la evaluación continúa con las siguientes, permitiendo
     * que un mismo SMS se reenvíe a varios destinos. Si false, la evaluación se detiene aquí.
     */
    val continuar: Boolean = false
) {
    companion object {
        /**
         * Plantilla por defecto: el remitente y la fecha en una línea, y debajo el mensaje.
         *
         * Con el SMS bastaba `{mensaje}`, porque el reenvío llegaba como un SMS más y el
         * teléfono ya enseñaba quién lo mandaba. Por WhatsApp **todo llega del mismo número**:
         * sin el remitente en el texto, cuatro avisos seguidos son cuatro mensajes
         * indistinguibles y hay que adivinar cuál era del banco. El contexto que el SMS daba
         * gratis hay que ponerlo aquí a mano.
         *
         * Con la plantilla aprieta todavía más, porque el cuerpo que Meta aprueba es siempre el
         * mismo para todos los reenvíos: lo único que distingue un aviso de otro es lo que entre
         * en la variable.
         */
        const val PLANTILLA_POR_DEFECTO = "{telefono} · {fecha}\n{mensaje}"

        /**
         * La plantilla con la que arranca una regla del canal API: el cuerpo del SMS y nada
         * más, que es lo que SMSGateway metía en `{mensaje}`. El remitente y la fecha tienen
         * sus propios huecos en la URL, y meterlos también en el texto los duplicaría.
         */
        const val PLANTILLA_POR_DEFECTO_API = "{mensaje}"

        /** La plantilla con la que arranca una regla nueva de [canal]. */
        fun plantillaPorDefecto(canal: Canal): String =
            if (canal == Canal.API) PLANTILLA_POR_DEFECTO_API else PLANTILLA_POR_DEFECTO
    }
}
