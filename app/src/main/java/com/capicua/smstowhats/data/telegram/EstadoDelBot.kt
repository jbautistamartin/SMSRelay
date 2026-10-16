// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.telegram

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
 * Recuerda si Telegram ha rechazado el token.
 *
 * ## Por qué esto existe
 * Un token mal escrito o revocado no es un fallo de *un* reenvío: es un fallo de configuración
 * que hace fracasar **todos** por igual. Sin recordarlo, lo único que queda es el registro, con
 * cuatrocientas líneas idénticas de las que la primera era la informativa, y el Panel diciendo
 * que todo va bien mientras no sale nada.
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
 */
@Singleton
class EstadoDelBot @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    /** true si el último intento de entrega fue rechazado por culpa del token. */
    val tokenRechazado: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Claves.TOKEN_RECHAZADO] ?: false
    }

    suspend fun leerTokenRechazado(): Boolean = tokenRechazado.first()

    /**
     * Anota que Telegram ha rechazado el token.
     *
     * Se llama desde el transporte al recibir un `401` o un `404`: la URL del Bot API lleva el
     * token dentro, así que un «no existe» en esa ruta es un token que no existe.
     */
    suspend fun anotarTokenRechazado() {
        dataStore.edit { it[Claves.TOKEN_RECHAZADO] = true }
    }

    /**
     * Borra el aviso.
     *
     * Lo limpia un envío que sale bien —la prueba más directa de que el token sirve— y también
     * guardar un token nuevo en Ajustes, porque si no el aviso seguiría puesto después de
     * arreglarlo y mandaría a nadie a arreglar lo ya arreglado.
     */
    suspend fun olvidarTokenRechazado() {
        if (!leerTokenRechazado()) return
        dataStore.edit { it[Claves.TOKEN_RECHAZADO] = false }
    }

    private object Claves {
        val TOKEN_RECHAZADO = booleanPreferencesKey("bot_token_rechazado")
    }
}
