// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.transport

/**
 * Por dónde sale un reenvío.
 *
 * Es lo que diferencia a SMSRelay de las cuatro aplicaciones de las que nace: cada una de ellas
 * tenía un único medio de salida, fijado en el código, y aquí **cada regla elige el suyo**. Vive
 * en el dominio porque la regla lo lleva y el reenvío lo hereda; lo que no sabe es cómo se
 * entrega por cada uno, que es cosa de las implementaciones de [EnviadorDeMensajes] y solo las
 * nombra `di/TransportModule`.
 *
 * Se persiste por su [name], así que **renombrar una constante es un cambio de esquema**: las
 * filas guardadas con el nombre viejo dejarían de leerse. Añadir una nueva no rompe nada.
 *
 * | Canal | Destino de una regla | De dónde viene |
 * |-------|----------------------|----------------|
 * | [API] | Una plantilla de URL a la que se hace un `GET` | SMSGateway |
 * | [SMS] | Un número de teléfono, o `{telefono}` para contestar al remitente | SMStoSMS |
 * | [TELEGRAM] | Un `chat_id` o un `@canal` | SMStoTelegram |
 * | [WHATSAPP] | Un número en formato E.164 | SMStoWhats |
 */
enum class Canal(
    /** Nombre para mostrar en la interfaz y en el registro. */
    val etiqueta: String
) {
    API("API"),
    SMS("SMS"),
    TELEGRAM("Telegram"),
    WHATSAPP("WhatsApp");

    companion object {
        /**
         * Canal de una regla nueva y de una regla que llegue sin canal.
         *
         * Es WhatsApp porque es el medio de la aplicación de la que SMSRelay es copia, y por
         * tanto el único que funcionaba al empezar la mudanza. No es una preferencia de diseño:
         * el editor de reglas pide elegir el canal antes que el destino, que es lo que de verdad
         * importa.
         */
        val POR_DEFECTO = WHATSAPP

        /** El canal cuyo [name] es [nombre], o null si no hay ninguno. */
        fun de(nombre: String?): Canal? = entries.firstOrNull { it.name == nombre }
    }
}
