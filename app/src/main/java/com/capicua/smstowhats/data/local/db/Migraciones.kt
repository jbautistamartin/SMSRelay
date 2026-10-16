// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.local.db

import androidx.room.migration.Migration

/**
 * Migraciones del esquema.
 *
 * Se escriben a mano y se registran en `DatabaseModule`. Nunca se recurre a
 * `fallbackToDestructiveMigration()`: borraría los SMS y los reenvíos pendientes, que es
 * justamente lo que el patrón outbox existe para no perder.
 *
 * ## Por qué está vacío
 * El esquema de esta aplicación nace en la versión 1 (ver el KDoc de [SmsDatabase]): ningún
 * dispositivo tiene todavía esta base de datos, así que no hay nada que migrar. Las migraciones
 * que SMStoSMS llegó a escribir no valen aquí —otro `applicationId`, otro fichero, otro juego de
 * datos— y arrastrarlas obligaría a mantener código que nunca se ejecutaría.
 *
 * Esto deja de ser cierto en cuanto la aplicación se instale con reglas dentro. A partir de ahí,
 * **cada cambio de esquema sube la versión y añade aquí su `Migration`**; cambiar la v1 en sitio
 * hace que Room aborte al abrir la base de datos de quien ya la tenía.
 */
object Migraciones {

    /** Todas las migraciones conocidas, en orden. Vacío mientras el esquema siga en la v1. */
    val TODAS: Array<Migration> = emptyArray()
}
