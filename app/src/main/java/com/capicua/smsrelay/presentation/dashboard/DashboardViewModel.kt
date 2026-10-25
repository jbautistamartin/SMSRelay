// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smsrelay.data.config.ConfigDataStore
import com.capicua.smsrelay.data.repository.ReenvioRepository
import com.capicua.smsrelay.data.repository.ReglaRepository
import com.capicua.smsrelay.data.repository.SmsRepository
import com.capicua.smsrelay.data.whatsapp.ContadorDeMensajes
import com.capicua.smsrelay.data.whatsapp.EstadoDeLaCuenta
import com.capicua.smsrelay.data.whatsapp.MensajesDelMes
import com.capicua.smsrelay.domain.transport.EnviadorDeMensajes
import com.capicua.smsrelay.domain.model.SmsConReenvios
import com.capicua.smsrelay.domain.usecase.ObtenerListaSmsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Cifras de cabecera de la pantalla de inicio. */
data class ResumenInicio(
    val totalSms: Int = 0,
    val reenviosPendientes: Int = 0,
    val reglasActivas: Int = 0
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val obtenerListaSms: ObtenerListaSmsUseCase,
    private val smsRepository: SmsRepository,
    private val enviador: EnviadorDeMensajes,
    private val estadoDeLaCuenta: EstadoDeLaCuenta,
    contadorDeMensajes: ContadorDeMensajes,
    configDataStore: ConfigDataStore,
    reenvioRepository: ReenvioRepository,
    reglaRepository: ReglaRepository
) : ViewModel() {

    /** Lista reactiva de SMS con sus reenvíos, más recientes primero. */
    val smsList: Flow<List<SmsConReenvios>> = obtenerListaSms()

    /**
     * Cifras de cabecera.
     *
     * Incluye las reglas activas a propósito: con cero reglas la aplicación recibe SMS y no
     * reenvía nada, y eso tiene que ser visible en la primera pantalla en lugar de parecer un
     * fallo de envío.
     */
    val resumen: Flow<ResumenInicio> = combine(
        smsRepository.observarContadorTotal(),
        reenvioRepository.observarContadorPendientes(),
        reglaRepository.observarContadorActivas()
    ) { total, pendientes, reglas ->
        ResumenInicio(total, pendientes, reglas)
    }

    /**
     * Lo entregado este mes y el tope configurado, para el Panel.
     *
     * Está en la primera pantalla porque es la única cifra de esta aplicación que **cuesta
     * dinero** y que nadie va a ir a buscar a propósito. Enterrada en Ajustes, la primera
     * noticia de haberse pasado sería la factura.
     *
     * Van juntos el contador y el tope porque por separado no dicen nada: «143 mensajes» no es
     * ni mucho ni poco hasta que se sabe contra qué.
     */
    val mensajesDelMes: Flow<Pair<MensajesDelMes, Int>> = combine(
        contadorDeMensajes.mesEnCurso,
        configDataStore.config
    ) { mes, config -> mes to config.maxMensajesPorMes }

    /**
     * Por qué no se puede entregar nada, o null si el transporte está listo.
     *
     * Es el aviso más importante del Panel, porque es el único fallo que afecta a **todos** los
     * reenvíos por igual: sin credenciales, o con unas que Meta ha rechazado, la aplicación
     * recibe SMS y los va marcando como fallidos uno a uno. En el registro eso son cuatrocientas
     * líneas idénticas de las que solo la primera informa; aquí es una línea en la primera
     * pantalla, que es donde alguien la va a leer.
     *
     * Se recalcula con cada cambio de la configuración, que es cuando puede dejar de ser cierto:
     * el motivo depende de las credenciales guardadas, del modo de entrega y de si el último
     * envío fue rechazado.
     */
    val avisoTransporte: Flow<String?> = combine(
        configDataStore.config,
        estadoDeLaCuenta.credencialRechazada
    ) { _, _ -> enviador.motivoNoDisponible() }

    fun limpiarTodos() {
        viewModelScope.launch {
            smsRepository.limpiarTodos()
        }
    }
}
