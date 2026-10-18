// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.presentation.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstowhats.data.config.AppConfig
import com.capicua.smstowhats.data.config.ConfigBackup
import com.capicua.smstowhats.data.config.ConfigDataStore
import com.capicua.smstowhats.data.config.ResultadoCopia
import com.capicua.smstowhats.data.whatsapp.ComprobadorDeCuenta
import com.capicua.smstowhats.data.whatsapp.EstadoDeLaCuenta
import com.capicua.smstowhats.data.whatsapp.ResultadoDeCuenta
import com.capicua.smstowhats.util.ControlRecepcion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val configDataStore: ConfigDataStore,
    private val configBackup: ConfigBackup,
    private val estadoDeLaCuenta: EstadoDeLaCuenta,
    private val comprobadorDeCuenta: ComprobadorDeCuenta
) : AndroidViewModel(application) {

    /** En qué punto está la comprobación de las credenciales. */
    sealed interface EstadoComprobacion {
        data object Inactivo : EstadoComprobacion
        data object Comprobando : EstadoComprobacion
        data class Terminada(val resultado: ResultadoDeCuenta) : EstadoComprobacion
    }

    private val _comprobacion = MutableStateFlow<EstadoComprobacion>(EstadoComprobacion.Inactivo)
    val comprobacion: StateFlow<EstadoComprobacion> = _comprobacion.asStateFlow()

    /**
     * Pregunta a Meta por estas credenciales sin guardarlas.
     *
     * Se comprueba el texto que hay en los campos, no lo guardado: quien acaba de pegar unas
     * credenciales quiere saber si sirven **antes** de comprometerse.
     *
     * Van las dos juntas porque la consulta ejercita las dos a la vez —el token en la cabecera,
     * el id en la ruta— y porque comprobarlas por separado no es posible: sin id no hay URL a la
     * que llamar, y sin token no hay permiso para llamarla.
     */
    fun comprobarCuenta(token: String, idNumero: String) {
        if (_comprobacion.value == EstadoComprobacion.Comprobando) return
        viewModelScope.launch {
            _comprobacion.value = EstadoComprobacion.Comprobando
            _comprobacion.value = EstadoComprobacion.Terminada(
                comprobadorDeCuenta.comprobar(token, idNumero)
            )
        }
    }

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
            // Guardar las credenciales es el gesto de quien viene a arreglar el aviso de
            // «Meta las rechazó». Dejarlo puesto mandaría a arreglar lo ya arreglado; si las
            // nuevas tampoco sirven, el siguiente envío lo pondrá otra vez.
            estadoDeLaCuenta.olvidarCredencialRechazada()
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
