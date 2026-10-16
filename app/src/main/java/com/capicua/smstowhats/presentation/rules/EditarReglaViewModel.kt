// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.presentation.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstowhats.data.repository.ReglaRepository
import com.capicua.smstowhats.domain.model.Regla
import com.capicua.smstowhats.domain.rules.EvaluadorDeReglas
import com.capicua.smstowhats.data.telegram.DescubridorDeChats
import com.capicua.smstowhats.data.telegram.DestinoDeTelegram
import com.capicua.smstowhats.data.telegram.ResultadoDescubrimiento
import com.capicua.smstowhats.domain.transport.EnviadorDeMensajes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Errores de validación de una regla, uno por campo. Null significa que el campo está bien. */
data class ErroresRegla(
    val nombre: String? = null,
    val regexTelefono: String? = null,
    val regexMensaje: String? = null,
    val destino: String? = null,
    val plantilla: String? = null
) {
    val hayErrores: Boolean
        get() = listOf(nombre, regexTelefono, regexMensaje, destino, plantilla).any { it != null }
}

@HiltViewModel
class EditarReglaViewModel @Inject constructor(
    private val reglaRepository: ReglaRepository,
    private val evaluador: EvaluadorDeReglas,
    private val descubridorDeChats: DescubridorDeChats,
    enviador: EnviadorDeMensajes
) : ViewModel() {

    /** Longitud máxima que admite el transporte, para el contador de la previsualización. */
    val limiteCaracteres: Int = enviador.limiteCaracteres

    private val _regla = MutableStateFlow<Regla?>(null)
    /** Regla en edición. Null mientras se carga, o para una regla nueva sin valores. */
    val regla: StateFlow<Regla?> = _regla.asStateFlow()

    private val _errores = MutableStateFlow(ErroresRegla())
    val errores: StateFlow<ErroresRegla> = _errores.asStateFlow()

    /** En qué punto está la búsqueda de chats del bot. */
    sealed interface EstadoDescubrimiento {
        data object Inactivo : EstadoDescubrimiento
        data object Buscando : EstadoDescubrimiento
        data class Terminado(val resultado: ResultadoDescubrimiento) : EstadoDescubrimiento
    }

    private val _descubrimiento =
        MutableStateFlow<EstadoDescubrimiento>(EstadoDescubrimiento.Inactivo)
    val descubrimiento: StateFlow<EstadoDescubrimiento> = _descubrimiento.asStateFlow()

    /** Pregunta al bot quién le ha escrito, para poder elegir el destino de una lista. */
    fun descubrirChats() {
        if (_descubrimiento.value == EstadoDescubrimiento.Buscando) return
        viewModelScope.launch {
            _descubrimiento.value = EstadoDescubrimiento.Buscando
            _descubrimiento.value =
                EstadoDescubrimiento.Terminado(descubridorDeChats.descubrir())
        }
    }

    /** Devuelve el estado a reposo tras atender el resultado, para que no se repinte solo. */
    fun descubrimientoAtendido() {
        _descubrimiento.value = EstadoDescubrimiento.Inactivo
    }

    private val _guardada = MutableStateFlow(false)
    /** Pasa a true cuando la regla se ha persistido, para que la pantalla pueda cerrarse. */
    val guardada: StateFlow<Boolean> = _guardada.asStateFlow()

    /** Carga la regla a editar, o deja el formulario en blanco si [reglaId] es [NUEVA]. */
    fun cargar(reglaId: Long) {
        if (reglaId == NUEVA) return
        viewModelScope.launch {
            _regla.value = reglaRepository.obtenerPorId(reglaId)
        }
    }

    /**
     * Valida un patrón mientras se teclea, para avisar en el momento en lugar de dejar que la
     * regla «no funcione» sin explicación.
     *
     * @return el motivo del error, o null si el patrón compila o está vacío.
     */
    fun validarPatron(patron: String?): String? = evaluador.validarPatron(patron)

    /** Previsualiza el texto que saldría con esta plantilla, para el campo de ayuda. */
    fun previsualizar(plantilla: String, telefono: String, mensaje: String, fecha: String): String =
        evaluador.resolverPlantilla(plantilla, telefono, mensaje, fecha)

    /**
     * Valida y guarda. Si hay errores, los publica en [errores] y no escribe nada.
     *
     * @param reglaId [NUEVA] para crear, o el id existente para actualizar.
     */
    fun guardar(reglaId: Long, borrador: Regla) {
        val errores = validar(borrador)
        _errores.value = errores
        if (errores.hayErrores) return

        viewModelScope.launch {
            if (reglaId == NUEVA) {
                reglaRepository.crear(borrador)
            } else {
                // Se conserva el orden que ya tenía: editar una regla no debe cambiar su
                // prioridad en la lista.
                val orden = reglaRepository.obtenerPorId(reglaId)?.orden ?: borrador.orden
                reglaRepository.actualizar(borrador.copy(id = reglaId, orden = orden))
            }
            _guardada.value = true
        }
    }

    private fun validar(regla: Regla): ErroresRegla = ErroresRegla(
        nombre = if (regla.nombre.isBlank()) "Pon un nombre para reconocer la regla" else null,
        regexTelefono = evaluador.validarPatron(regla.regexTelefono),
        regexMensaje = evaluador.validarPatron(regla.regexMensaje),
        destino = validarDestino(regla.destino),
        plantilla = if (regla.plantilla.isBlank()) "La plantilla no puede estar vacía" else null
    )

    /**
     * Valida el destino y, sobre todo, **explica el error correcto**.
     *
     * El caso probable no es un destino con una letra de más: es que alguien escriba un número
     * de teléfono, porque viene de SMStoSMS o porque es lo que uno supone que va en un campo
     * llamado «destino». Decirle «no parece un chat de Telegram» es verdad y no le sirve de
     * nada; decirle «esto es un teléfono, aquí va un chat, usa el botón» le resuelve el
     * problema. Por eso hay tres mensajes y no uno.
     *
     * Los textos van literales aquí, como los de los demás campos de [ErroresRegla]: es la
     * convención que ya tenía la pantalla y mezclar literales con recursos sería peor que
     * cualquiera de las dos cosas por separado.
     */
    private fun validarDestino(destino: String): String? = when {
        destino.isBlank() ->
            "El chat de destino es obligatorio"

        DestinoDeTelegram.pareceValido(destino) -> null

        DestinoDeTelegram.pareceTelefono(destino) ->
            "Esto es un número de teléfono. Aquí va un chat de Telegram: usa «Elegir de los " +
                "chats del bot» para seleccionarlo"

        else ->
            "No parece un chat de Telegram. Un chat_id son solo dígitos, con un signo menos " +
                "delante si es un grupo o un canal"
    }

    companion object {
        /** Id que indica que se está creando una regla, no editando una existente. */
        const val NUEVA = 0L
    }
}
