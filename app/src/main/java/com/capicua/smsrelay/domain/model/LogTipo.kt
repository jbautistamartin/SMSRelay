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
     * Un SMS o un reenvío que no sale porque un ajuste dijo que no. El detalle empieza siempre
     * por lo que lo dijo: *Tope por minuto*, *Tope mensual de WhatsApp*, *Guarda antibucle de SMS*
     * o *Canal desactivado*.
     *
     * Se llamaba `BUCLE_EVITADO` mientras existían las protecciones antibucle de SMStoSMS.
     * SMStoTelegram lo renombró cuando el destino pasó a ser un chat y la comparación dejó de
     * significar nada, y el nombre que quedó envejeció mejor que su motivo: en SMSRelay las
     * guardas antibucle volvieron, solo para el canal SMS, y caben aquí con los topes y los
     * canales apagados. El tipo se llama por lo que de verdad registra, que es «un límite
     * configurado dijo que no».
     */
    LIMITE_ALCANZADO,

    /** Error transitorio o permanente al intentar reenviar. */
    ERROR,

    /** Evento del sistema: inicio de la app, reinicio del dispositivo, limpieza periódica. */
    SISTEMA
}
