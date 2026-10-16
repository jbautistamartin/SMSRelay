// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.whatsapp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Lo entregado en un mes, separado por lo que cuesta cada cosa. */
data class MensajesDelMes(
    /** El mes al que corresponde la cuenta, en formato `2026-10`. */
    val mes: String,
    /** Mensajes de texto libre. Los primeros 1.000 del mes son gratis. */
    val servicio: Int,
    /** Mensajes de plantilla. Se facturan todos. */
    val plantilla: Int
) {
    val total: Int get() = servicio + plantilla
}

/**
 * Cuenta lo que se ha entregado este mes.
 *
 * ## Por qué esto existe
 * Porque es la primera vez en la historia de este proyecto que **entregar cuesta dinero y nada
 * falla cuando se pasa**. Con SMS costaba, pero el operador mandaba la factura y el tope por
 * minuto hacía de cortafuegos. Con Telegram era gratis. Con la Cloud API:
 *
 * - Los **mensajes de servicio** —el texto libre— son gratis los primeros **1.000 al mes y por
 *   número**; desde el 1 de octubre de 2026, el resto se factura.
 * - Los **mensajes de plantilla** se facturan todos, siempre.
 *
 * Mil al mes son treinta y tres al día. Sin esto, la primera noticia de haberse pasado es la
 * factura, y para entonces ya es tarde para decidir otra cosa. Con esto, el Panel lo enseña y el
 * tope mensual de `AppConfig` lo corta.
 *
 * ## Por qué cuenta entregas y no reenvíos
 * Un reenvío puede acabar en varios mensajes —un SMS largo se trocea— y Meta factura **por
 * mensaje**, no por reenvío. Contar reenvíos daría una cifra que no se parece a la factura, que
 * es justo lo que esto existe para anticipar. Así que lo incrementa el transporte, una vez por
 * petición que Meta acepta.
 *
 * ## Por qué distingue servicio de plantilla
 * Porque tienen precios distintos y cuotas distintas, y porque la cifra interesante no es el
 * total: es cuántos han tenido que ir por plantilla. Ese número es la medida de cuánto se está
 * entregando fuera de la ventana de 24 h, que es lo que de verdad cuesta.
 *
 * ## Por qué vive aquí y no en AppConfig
 * Mismo criterio que `EstadoDeLaCuenta` y `EstadoActualizacion`: está en el mismo DataStore que
 * los ajustes pero con sus propias claves, porque **no es configuración**. Es un hecho sobre
 * *este* teléfono y *este* mes, y no tiene nada que decir sobre cómo debe reenviar otro.
 *
 * ## El cambio de mes
 * No hay nada programado que lo detecte, y no hace falta: cada lectura y cada escritura
 * comprueban el mes guardado y, si no es el actual, la cuenta vale cero. Un trabajo periódico
 * para poner contadores a cero sería una pieza más que puede no ejecutarse, en una aplicación
 * cuyos trabajos periódicos ya pelean con Doze.
 */
@Singleton
class ContadorDeMensajes @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    /** Lo entregado en el mes en curso. Emite cero el día 1, sin que nadie lo reinicie. */
    val mesEnCurso: Flow<MensajesDelMes> = dataStore.data.map { prefs ->
        val mes = mesActual()
        if (prefs[Claves.MES] != mes) {
            MensajesDelMes(mes, servicio = 0, plantilla = 0)
        } else {
            MensajesDelMes(
                mes = mes,
                servicio = prefs[Claves.SERVICIO] ?: 0,
                plantilla = prefs[Claves.PLANTILLA] ?: 0
            )
        }
    }

    suspend fun leerMesEnCurso(): MensajesDelMes = mesEnCurso.first()

    /** Anota un mensaje de texto libre entregado. */
    suspend fun contarServicio() = contar(Claves.SERVICIO)

    /** Anota un mensaje de plantilla entregado. */
    suspend fun contarPlantilla() = contar(Claves.PLANTILLA)

    // ── Interno ───────────────────────────────────────────────────────────────

    /**
     * Incrementa un contador, poniendo a cero los dos si ha cambiado el mes.
     *
     * El cambio de mes se resuelve **dentro de la misma transacción** que el incremento. Hacerlo
     * fuera dejaría una ventana en la que dos entregas simultáneas del día 1 podrían poner a
     * cero una encima de la otra y perder la primera.
     */
    private suspend fun contar(clave: Preferences.Key<Int>) {
        val mes = mesActual()
        dataStore.edit { prefs ->
            if (prefs[Claves.MES] != mes) {
                prefs[Claves.MES] = mes
                prefs[Claves.SERVICIO] = 0
                prefs[Claves.PLANTILLA] = 0
                Timber.i("ContadorDeMensajes: mes nuevo ($mes), la cuenta arranca de cero")
            }
            prefs[clave] = (prefs[clave] ?: 0) + 1
        }
    }

    private fun mesActual(): String = YearMonth.now().toString()

    private object Claves {
        val MES = stringPreferencesKey("mensajes_mes")
        val SERVICIO = intPreferencesKey("mensajes_servicio")
        val PLANTILLA = intPreferencesKey("mensajes_plantilla")
    }

    companion object {
        /**
         * Mensajes de servicio gratis al mes y por número, según Meta.
         *
         * No se usa como tope —el tope lo pone el operador en `AppConfig`— sino como referencia
         * para el texto que explica el ajuste. Si Meta lo cambia, cambia aquí.
         */
        const val SERVICIO_GRATIS_AL_MES = 1_000
    }
}
