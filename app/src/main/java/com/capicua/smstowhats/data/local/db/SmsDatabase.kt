// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.capicua.smstowhats.data.local.db.dao.LogDao
import com.capicua.smstowhats.data.local.db.dao.ReenvioDao
import com.capicua.smstowhats.data.local.db.dao.ReglaDao
import com.capicua.smstowhats.data.local.db.dao.SmsDao
import com.capicua.smstowhats.data.local.db.entity.LogEntity
import com.capicua.smstowhats.data.local.db.entity.ReenvioEntity
import com.capicua.smstowhats.data.local.db.entity.ReglaEntity
import com.capicua.smstowhats.data.local.db.entity.SmsEntity

/**
 * Base de datos local de SMStoWhats.
 *
 * ## Por qué el esquema arranca otra vez en la versión 1
 * Es la **tercera** vez que pasa, y siempre por el mismo motivo. SMSGateway llegó a la v2 con una
 * migración 1→2 y el esquema volvió a la v1 al nacer SMStoSMS; SMStoSMS llegó otra vez a la v2
 * —la que añadió `reglas.ignorar_mayusculas`— y volvió a la v1 al nacer SMStoTelegram; y
 * SMStoTelegram se publicó en la v1, que es la que se hereda aquí sin cambios.
 *
 * Esta vez, de hecho, el esquema no cambia **nada**: las cuatro tablas son las mismas y
 * `reenvios.destino` sigue siendo un `TEXT`. Lo único que cambia es lo que ese texto significa
 * —un `chat_id` antes, un teléfono ahora—, y eso no es un cambio de esquema sino de contrato,
 * que es por lo que lo vigila el formato de la copia de seguridad (ver `ReglasExportadas`) y no
 * una migración.
 *
 * El `applicationId` es `com.capicua.smstowhats`, así que Android trata esto como una
 * aplicación distinta, con su propio directorio de datos, y además el fichero se llama
 * [NOMBRE_BD]. Ningún dispositivo tiene esta base de datos: no hay nada que migrar y arrastrar
 * la historia de migraciones de otra app solo obligaría a mantener código que nunca se
 * ejecutaría. Se colapsa en un esquema v1 limpio, con `ignorar_mayusculas` ya dentro.
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
        const val NOMBRE_BD = "smstowhats.db"
    }
}
