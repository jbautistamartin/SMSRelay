// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.capicua.smsrelay.data.local.db.dao.LogDao
import com.capicua.smsrelay.data.local.db.dao.ReenvioDao
import com.capicua.smsrelay.data.local.db.dao.ReglaDao
import com.capicua.smsrelay.data.local.db.dao.SmsDao
import com.capicua.smsrelay.data.local.db.entity.LogEntity
import com.capicua.smsrelay.data.local.db.entity.ReenvioEntity
import com.capicua.smsrelay.data.local.db.entity.ReglaEntity
import com.capicua.smsrelay.data.local.db.entity.SmsEntity

/**
 * Base de datos local de SMSRelay.
 *
 * ## Por qué el esquema arranca otra vez en la versión 1
 * Es la **cuarta** vez que pasa, y siempre por el mismo motivo. SMSGateway llegó a la v2 con una
 * migración 1→2 y el esquema volvió a la v1 al nacer SMStoSMS; SMStoSMS llegó otra vez a la v2
 * —la que añadió `reglas.ignorar_mayusculas`— y volvió a la v1 al nacer SMStoTelegram;
 * SMStoTelegram y SMStoWhats se publicaron en la v1, y SMSRelay vuelve a empezar en ella.
 *
 * El `applicationId` es `com.capicua.smsrelay`, así que Android trata esto como una aplicación
 * distinta, con su propio directorio de datos, y además el fichero se llama [NOMBRE_BD]. Ningún
 * dispositivo tiene esta base de datos: no hay nada que migrar, y arrastrar la historia de
 * migraciones de otra app solo obligaría a mantener código que nunca se ejecutaría. Lo que
 * viene de las aplicaciones anteriores entra por la copia de seguridad, no por aquí.
 *
 * Esto se puede hacer **una sola vez**, ahora. En cuanto la aplicación se instale en un teléfono
 * con reglas dentro, el esquema deja de ser editable en sitio y cualquier cambio tiene que subir
 * la versión y escribir su `Migration`.
 *
 * ## Tablas
 * | Tabla         | Papel                                                              |
 * |---------------|--------------------------------------------------------------------|
 * | `sms`         | Outbox de mensajes entrantes: se persiste antes de intentar nada.  |
 * | `reglas`      | Reglas de reenvío, ordenadas por prioridad.                        |
 * | `reenvios`    | Unidad de despacho: un envío a un destino, con su propio estado.   |
 * | `log_entries` | Registro de auditoría de todos los eventos del sistema.            |
 *
 * ## Migraciones
 * Nunca usar `fallbackToDestructiveMigration()`: perderíamos SMS y reenvíos pendientes, que es
 * justamente lo que el patrón outbox existe para no perder. Cada cambio de esquema sube la
 * versión y escribe su `Migration` en [Migraciones], que `DatabaseModule` registra.
 *
 * | Versión | Cambio                                                          |
 * |---------|-----------------------------------------------------------------|
 * | 1       | Esquema inicial: las cuatro tablas, con `ignorar_mayusculas`.    |
 */
@Database(
    entities = [
        SmsEntity::class,
        ReglaEntity::class,
        ReenvioEntity::class,
        LogEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SmsDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao
    abstract fun reglaDao(): ReglaDao
    abstract fun reenvioDao(): ReenvioDao
    abstract fun logDao(): LogDao

    companion object {
        const val NOMBRE_BD = "smsrelay.db"
    }
}
