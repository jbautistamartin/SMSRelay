// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.config

/**
 * Cómo sale un reenvío: como texto libre, como plantilla, o lo que haga falta.
 *
 * Existe porque la Cloud API tiene **dos formas de entregar un mensaje y no son
 * intercambiables**. El texto libre es gratis dentro de la cuota mensual y solo se puede mandar
 * dentro de una ventana de 24 h que abre el destinatario; la plantilla se puede mandar siempre y
 * se factura cada vez. Elegir mal una u otra no da un error de formato: da un mensaje que no se
 * entrega, o una factura.
 *
 * Es configuración del transporte y la lee el propio transporte. No llega por
 * `EnviadorDeMensajes`: el worker de despacho no sabe que existen dos formas de entregar, igual
 * que no sabía que un SMS podía ir en varias partes.
 */
enum class ModoDeEntrega {

    /**
     * Intenta texto libre y, si la ventana está cerrada, reenvía como plantilla.
     *
     * Es el valor por defecto, y es el único que entrega siempre sin gastar más de lo necesario:
     * gratis cuando se puede, de pago cuando no hay otra.
     *
     * El salto a plantilla ocurre **dentro del mismo intento del worker**, no como reintento. Un
     * reintento volvería a encontrarse la ventana cerrada y gastaría los diez intentos para
     * acabar en el mismo sitio.
     *
     * **Sin plantilla configurada se comporta como [SOLO_TEXTO_LIBRE]**, porque no hay nada a lo
     * que saltar. No es un error silencioso: el motivo del fallo dice que hace falta una
     * plantilla y dónde se configura.
     */
    AUTOMATICO,

    /**
     * Nunca gasta una plantilla.
     *
     * Para quien no quiere que se le facture nada por sorpresa. Lo que cuesta es que, con la
     * ventana cerrada, **no se entrega nada**: el reenvío se cierra como fallido permanente con
     * un motivo que explica qué pasó y qué hacer.
     *
     * Es un modo útil cuando el destinatario es uno mismo y contesta al reenvío de vez en
     * cuando, que renueva la ventana.
     */
    SOLO_TEXTO_LIBRE,

    /**
     * No prueba el texto libre: va directo a la plantilla.
     *
     * Útil cuando se sabe que la ventana nunca va a estar abierta, y ahorra la petición que en
     * [AUTOMATICO] se gasta para descubrirlo. Lo paga en cuota: factura también los mensajes que
     * habrían salido gratis.
     */
    SOLO_PLANTILLA;

    companion object {
        /**
         * Lee un modo guardado, tolerando lo que no reconozca.
         *
         * Un valor que no existe —de una versión futura, o de un fichero de copia editado a
         * mano— cae en [AUTOMATICO] en lugar de reventar: es el modo que entrega en más
         * situaciones, y por tanto el menos malo para un valor que no se entiende.
         */
        fun de(nombre: String?): ModoDeEntrega =
            entries.firstOrNull { it.name == nombre } ?: AUTOMATICO
    }
}
