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
import com.capicua.smsrelay.domain.transport.Transportes
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
    private val transportes: Transportes,
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
     * Por qué no se puede entregar por alguno de los canales en uso, o null si todos están
     * listos.
     *
     * Es el aviso más importante del Panel, porque es el único fallo que afecta a **todos** los
     * reenvíos de un canal por igual: sin credenciales, o con unas que el servicio ha rechazado,
     * la aplicación recibe SMS y los va marcando como fallidos uno a uno. En el registro eso son
     * cuatrocientas líneas idénticas de las que solo la primera informa; aquí es una línea en la
     * primera pantalla, que es donde alguien la va a leer.
     *
     * **Solo mira los canales que usa alguna regla activa.** Con cuatro canales, lo normal es
     * tener uno o dos configurados; avisar de que falta el token de Telegram a quien solo
     * reenvía por SMS sería ruido, y el ruido enseña a no leer el aviso.
     *
     * Se recalcula con cada cambio de la configuración o de las reglas, que es cuando puede dejar
     * de ser cierto.
     */
    val avisoTransporte: Flow<String?> = combine(
        configDataStore.config,
        estadoDeLaCuenta.credencialRechazada,
        reglaRepository.observarTodas()
    ) { _, _, reglas ->
        reglas.filter { it.activa }
            .map { it.canal }
            .distinct()
            .sortedBy { it.ordinal }
            .mapNotNull { canal ->
                val motivo = transportes.de(canal)?.motivoNoDisponible()
                    ?: transportes.motivoSinTransporte(canal).takeIf { transportes.de(canal) == null }
                motivo?.let { "${canal.etiqueta}: $it" }
            }
            .joinToString("\n")
            .ifEmpty { null }
    }

    fun limpiarTodos() {
        viewModelScope.launch {
            smsRepository.limpiarTodos()
        }
    }
}
