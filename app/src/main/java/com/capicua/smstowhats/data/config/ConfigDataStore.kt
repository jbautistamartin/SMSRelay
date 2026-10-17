// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper sobre DataStore<Preferences> para los ajustes generales.
 *
 * - Las lecturas son reactivas (Flow).
 * - Las escrituras son atómicas (DataStore.edit usa transacción).
 * - Los valores por defecto están declarados una sola vez, en [AppConfig].
 */
@Singleton
class ConfigDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    /** Flujo reactivo con la configuración actual. Emite inmediatamente al suscribirse. */
    val config: Flow<AppConfig> = dataStore.data.map { prefs ->
        val porDefecto = AppConfig()
        AppConfig(
            tokenAcceso                = prefs[Keys.TOKEN_ACCESO]         ?: porDefecto.tokenAcceso,
            idNumeroTelefono           = prefs[Keys.ID_NUMERO]            ?: porDefecto.idNumeroTelefono,
            modoDeEntrega              = ModoDeEntrega.de(prefs[Keys.MODO_ENTREGA]),
            plantillaNombre            = prefs[Keys.PLANTILLA_NOMBRE]     ?: porDefecto.plantillaNombre,
            plantillaIdioma            = prefs[Keys.PLANTILLA_IDIOMA]     ?: porDefecto.plantillaIdioma,
            maxReintentos              = prefs[Keys.MAX_REINTENTOS]        ?: porDefecto.maxReintentos,
            intervaloReintentoSegundos = prefs[Keys.INTERVALO_REINTENTO]   ?: porDefecto.intervaloReintentoSegundos,
            timeoutEnvioSegundos       = prefs[Keys.TIMEOUT_ENVIO]         ?: porDefecto.timeoutEnvioSegundos,
            maxReenviosPorMinuto       = prefs[Keys.MAX_REENVIOS_MINUTO]   ?: porDefecto.maxReenviosPorMinuto,
            maxMensajesPorMes          = prefs[Keys.MAX_MENSAJES_MES]      ?: porDefecto.maxMensajesPorMes,
            recepcionActiva            = prefs[Keys.RECEPCION_ACTIVA]      ?: porDefecto.recepcionActiva
        )
    }

    /** Guarda toda la configuración de forma atómica. */
    suspend fun guardar(config: AppConfig) {
        dataStore.edit { prefs ->
            prefs[Keys.TOKEN_ACCESO]        = config.tokenAcceso
            prefs[Keys.ID_NUMERO]           = config.idNumeroTelefono
            prefs[Keys.MODO_ENTREGA]        = config.modoDeEntrega.name
            prefs[Keys.PLANTILLA_NOMBRE]    = config.plantillaNombre
            prefs[Keys.PLANTILLA_IDIOMA]    = config.plantillaIdioma
            prefs[Keys.MAX_REINTENTOS]      = config.maxReintentos
            prefs[Keys.INTERVALO_REINTENTO] = config.intervaloReintentoSegundos
            prefs[Keys.TIMEOUT_ENVIO]       = config.timeoutEnvioSegundos
            prefs[Keys.MAX_REENVIOS_MINUTO] = config.maxReenviosPorMinuto
            prefs[Keys.MAX_MENSAJES_MES]    = config.maxMensajesPorMes
            prefs[Keys.RECEPCION_ACTIVA]    = config.recepcionActiva
        }
    }

    private object Keys {
        val TOKEN_ACCESO        = stringPreferencesKey("token_acceso")
        val ID_NUMERO           = stringPreferencesKey("id_numero_telefono")
        val MODO_ENTREGA        = stringPreferencesKey("modo_entrega")
        val PLANTILLA_NOMBRE    = stringPreferencesKey("plantilla_nombre")
        val PLANTILLA_IDIOMA    = stringPreferencesKey("plantilla_idioma")
        val MAX_REINTENTOS      = intPreferencesKey("max_reintentos")
        val INTERVALO_REINTENTO = intPreferencesKey("intervalo_reintento")
        val TIMEOUT_ENVIO       = intPreferencesKey("timeout_envio_segundos")
        val MAX_REENVIOS_MINUTO = intPreferencesKey("max_reenvios_por_minuto")
        val MAX_MENSAJES_MES    = intPreferencesKey("max_mensajes_por_mes")
        val RECEPCION_ACTIVA    = booleanPreferencesKey("recepcion_activa")
    }
}
