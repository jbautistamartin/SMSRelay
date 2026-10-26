// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.model

/** Categoría de un evento del registro de auditoría. */
enum class LogTipo {
    /** SMS recibido por la SIM y persistido en la base de datos local. */
    SMS_RECIBIDO,

    /** Una regla casó con el SMS y se creó su reenvío. */
    REGLA_APLICADA,

    /**
     * Reenvío confirmado por el transporte: el mensaje está entregado.
     *
     * Conserva el nombre que tenía cuando el transporte era el SMS porque sigue describiendo
     * lo que pasó —un SMS se reenvió— y renombrarlo no aclararía nada.
     */
    SMS_REENVIADO,

    /** Ninguna regla casó con el SMS. No hay nada que reenviar. */
    SIN_REGLA,

    /**
     * SMS descartado por un tope configurado: el de reenvíos por minuto o el de mensajes al mes.
     *
     * Se llamaba `BUCLE_EVITADO` mientras existían las protecciones antibucle, que comparaban el
     * destino de una regla con el remitente del SMS. Aquel nombre murió cuando el destino pasó a
     * ser un chat y la comparación dejó de significar nada.
     *
     * **El nombre que quedó ha envejecido mejor que su motivo original.** Hoy el destino vuelve a
     * ser un teléfono, así que la comparación volvería a ser posible —y sigue sin servir de nada,
     * porque un mensaje de WhatsApp no vuelve nunca como SMS—. Y en cambio los descartes han
     * pasado de uno a dos: al límite de frecuencia se le ha unido el tope de mensajes del mes, que
     * es el primero de este proyecto que mide dinero. El tipo se sigue llamando por lo que de
     * verdad registra, que es «un tope dijo que no».
     */
    LIMITE_ALCANZADO,

    /** Error transitorio o permanente al intentar reenviar. */
    ERROR,

    /** Evento del sistema: inicio de la app, reinicio del dispositivo, limpieza periódica. */
    SISTEMA
}
