// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.domain.model

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
     * SMS descartado por el tope de reenvíos por minuto.
     *
     * Se llamaba `BUCLE_EVITADO` mientras existían las protecciones antibucle, que comparaban
     * el destino de una regla con el remitente del SMS. Con el destino convertido en un chat de
     * Telegram esa comparación dejó de significar nada y las guardias se fueron; el único
     * descarte que queda es el del límite de frecuencia, así que el tipo se llama por lo que de
     * verdad registra.
     */
    LIMITE_ALCANZADO,

    /** Error transitorio o permanente al intentar reenviar. */
    ERROR,

    /** Evento del sistema: inicio de la app, reinicio del dispositivo, limpieza periódica. */
    SISTEMA
}
