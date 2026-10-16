// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.whatsapp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recuerda si Meta ha rechazado las credenciales.
 *
 * ## Por qué esto existe
 * Un token revocado o un `phone_number_id` mal copiado no es un fallo de *un* reenvío: es un
 * fallo de configuración que hace fracasar **todos** por igual. Sin recordarlo, lo único que
 * queda es el registro, con cuatrocientas líneas idénticas de las que la primera era la
 * informativa, y el Panel diciendo que todo va bien mientras no sale nada.
 *
 * Con esto, el Panel puede avisar de que hay que ir a Ajustes, que es lo único que arregla el
 * problema. El reenvío concreto sigue cerrándose como fallido: esto no cambia su destino, solo
 * hace visible el motivo común.
 *
 * ## Por qué se persiste
 * No podría ser una variable en memoria. Esta aplicación no está «abierta»: cada SMS que llega
 * despierta el proceso, hace su trabajo y lo deja morir. Un flag en memoria se habría olvidado
 * antes de que alguien pudiera mirar el Panel.
 *
 * Vive en el mismo DataStore que los ajustes pero con su propia clave, igual que
 * `EstadoActualizacion`, y por el mismo motivo: no es configuración del reenvío y no debe viajar
 * en la copia de seguridad. Es un hecho sobre *este* teléfono y *este* momento.
 *
 * ## Por qué una sola bandera para dos credenciales
 * Las dos credenciales fallan con códigos distintos —el token con `190`, el id con `100`— y
 * sería posible guardar cuál de las dos ha fallado. No se hace porque la acción que hay que
 * tomar es la misma: ir a Ajustes y mirar las dos. Distinguirlas aquí añadiría un estado más
 * para ahorrar una mirada a un campo que está justo encima del otro.
 *
 * Lo que sí distingue es el **motivo** que queda en el registro, que lleva el `error.code` de
 * Meta dentro y apunta a la credencial concreta.
 */
@Singleton
class EstadoDeLaCuenta @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    /** true si el último intento de entrega fue rechazado por culpa de las credenciales. */
    val credencialRechazada: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Claves.CREDENCIAL_RECHAZADA] ?: false
    }

    suspend fun leerCredencialRechazada(): Boolean = credencialRechazada.first()

    /**
     * Anota que Meta ha rechazado las credenciales.
     *
     * Se llama desde el transporte cuando `MapeoDeRespuestas.esFalloDeCredencial()` dice que sí:
     * el token no vale, no tiene permisos, o el `phone_number_id` no existe.
     */
    suspend fun anotarCredencialRechazada() {
        dataStore.edit { it[Claves.CREDENCIAL_RECHAZADA] = true }
    }

    /**
     * Borra el aviso.
     *
     * Lo limpia un envío que sale bien —la prueba más directa de que las credenciales sirven— y
     * también guardarlas de nuevo en Ajustes, porque si no el aviso seguiría puesto después de
     * arreglarlo y mandaría a alguien a arreglar lo ya arreglado.
     */
    suspend fun olvidarCredencialRechazada() {
        if (!leerCredencialRechazada()) return
        dataStore.edit { it[Claves.CREDENCIAL_RECHAZADA] = false }
    }

    private object Claves {
        val CREDENCIAL_RECHAZADA = booleanPreferencesKey("credencial_rechazada")
    }
}
