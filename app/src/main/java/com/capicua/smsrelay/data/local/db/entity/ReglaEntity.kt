// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidad Room para la tabla de reglas de reenvío.
 *
 * Tabla: [reglas]
 *
 * - [orden]           Prioridad. Menor valor = se evalúa antes. Indexado para ordenar la lista.
 * - [regexTelefono]   Expresión regular sobre el remitente. Null = no filtra por teléfono.
 * - [regexMensaje]    Expresión regular sobre el cuerpo. Null = no filtra por mensaje.
 * - [ignorarMayusculas] true = las dos expresiones se aplican sin distinguir mayúsculas.
 * - [canal]           Por dónde sale: el `name` de un `Canal`.
 * - [destino]         A quién se entrega, en el formato del canal.
 * - [plantilla]       Texto a enviar, con marcadores `{mensaje}`, `{telefono}` y `{fecha}`.
 * - [activa]          false = la regla se ignora sin borrarla.
 * - [continuar]       true = tras casar, sigue evaluando las reglas siguientes.
 *
 * Las expresiones se guardan como texto sin compilar: validarlas es responsabilidad de la
 * pantalla de reglas y del evaluador, no de la base de datos.
 */
@Entity(
    tableName = "reglas",
    indices = [Index(value = ["orden"])]
)
data class ReglaEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "orden")
    val orden: Int,

    @ColumnInfo(name = "nombre")
    val nombre: String,

    @ColumnInfo(name = "regex_telefono")
    val regexTelefono: String? = null,

    @ColumnInfo(name = "regex_mensaje")
    val regexMensaje: String? = null,

    @ColumnInfo(name = "ignorar_mayusculas", defaultValue = "0")
    val ignorarMayusculas: Boolean = false,

    /**
     * Sin `defaultValue`, por el mismo motivo que [plantilla]: el canal por defecto ya está
     * escrito en `Canal.POR_DEFECTO`, y escribirlo otra vez aquí son dos sitios que divergen.
     */
    @ColumnInfo(name = "canal")
    val canal: String,

    @ColumnInfo(name = "destino")
    val destino: String,

    /**
     * Sin `defaultValue` y sin valor por defecto en Kotlin, a propósito.
     *
     * Antes tenía `'{mensaje}'` en los dos sitios, y eso significaba que la plantilla por
     * defecto estaba escrita dos veces: aquí y en `Regla.PLANTILLA_POR_DEFECTO`. Al cambiar la
     * del dominio —el remitente y la fecha delante, porque en Telegram todo llega del mismo
     * bot— las dos habrían dejado de coincidir, y la de aquí es la que se usaría si algún día
     * alguien insertara una fila sin la columna.
     *
     * Ninguna inserción lo hace: toda fila viene de un `Regla`, que ya trae su plantilla
     * resuelta. Así que el valor por defecto sobra, y quitarlo deja una sola fuente de verdad
     * en lugar de dos que hay que acordarse de sincronizar.
     */
    @ColumnInfo(name = "plantilla")
    val plantilla: String,

    @ColumnInfo(name = "activa", defaultValue = "1")
    val activa: Boolean = true,

    @ColumnInfo(name = "continuar", defaultValue = "0")
    val continuar: Boolean = false
)
