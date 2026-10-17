// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.domain.model

/**
 * Regla de reenvío configurada por el operador.
 *
 * Una regla decide **a qué número** se reenvía un SMS entrante y **con qué texto**.
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
     */
    val ignorarMayusculas: Boolean = false,

    /**
     * Número de WhatsApp que recibe el mensaje si la regla casa.
     *
     * En formato internacional, con `+` y prefijo de país: ver `DestinoDeWhatsApp`. Siempre una
     * persona, nunca un grupo.
     *
     * **No admite marcadores**, y eso es una propiedad de seguridad que sobrevivió al cambio de
     * medio aunque su argumento original ya no valga. Cuando el destino era un teléfono y la
     * salida era un SMS, se podía escribir `{telefono}` para contestar al remitente; con un chat
     * de Telegram eso dejó de tener sentido porque eran identidades sin relación. Ahora el
     * destino vuelve a ser un teléfono y el marcador *podría* resolverse otra vez.
     *
     * No se hace, y a propósito: **el cuerpo de un SMS no puede influir nunca en a dónde se
     * manda nada**. Permitirlo convertiría un SMS de un tercero en un selector de destinatario
     * sobre una cuenta de empresa que factura por mensaje.
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
    }
}
