// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.presentation.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstotelegram.data.config.AppConfig
import com.capicua.smstotelegram.data.config.ConfigBackup
import com.capicua.smstotelegram.data.config.ConfigDataStore
import com.capicua.smstotelegram.data.config.ResultadoCopia
import com.capicua.smstotelegram.util.ControlRecepcion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val configDataStore: ConfigDataStore,
    private val configBackup: ConfigBackup
) : AndroidViewModel(application) {

    /**
     * Estado actual de la configuración.
     *
     * Emite null mientras DataStore no ha cargado su primer valor, para que el fragment no
     * rellene los campos con los valores por defecto antes de que llegue lo realmente
     * persistido y el operador vea cambiar las cifras delante de sus ojos.
     */
    val config: StateFlow<AppConfig?> = configDataStore.config
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** Resultado de la última exportación o importación, para contarlo una sola vez. */
    private val _copias = MutableSharedFlow<ResultadoCopia>(extraBufferCapacity = 4)
    val copias: SharedFlow<ResultadoCopia> = _copias.asSharedFlow()

    /**
     * Guarda la configuración editada.
     *
     * El interruptor de recepción no es un valor más: además de persistirlo hay que encender o
     * apagar el receptor de SMS, que es lo que de verdad corta la entrada de mensajes.
     */
    fun guardar(config: AppConfig) {
        viewModelScope.launch {
            configDataStore.guardar(config)
            ControlRecepcion.aplicar(getApplication(), config.recepcionActiva)
        }
    }

    fun exportar(destino: Uri) {
        viewModelScope.launch { _copias.emit(configBackup.exportar(destino)) }
    }

    /**
     * Importa una copia.
     *
     * Si el fichero traía el interruptor de recepción apagado, hay que aplicarlo al receptor:
     * guardarlo en DataStore y no tocar el componente dejaría la aplicación diciendo una cosa
     * y haciendo otra.
     */
    fun importar(origen: Uri) {
        viewModelScope.launch {
            val resultado = configBackup.importar(origen)
            if (resultado is ResultadoCopia.Importada && resultado.conAjustes) {
                ControlRecepcion.aplicar(
                    getApplication(),
                    configDataStore.config.first().recepcionActiva
                )
            }
            _copias.emit(resultado)
        }
    }

    /** Nombre sugerido para el fichero de copia. */
    fun nombreFicheroCopia(): String = configBackup.nombreFichero()
}
