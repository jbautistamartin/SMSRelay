// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.presentation.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smsrelay.data.repository.ReglaRepository
import com.capicua.smsrelay.domain.model.Regla
import com.capicua.smsrelay.domain.rules.EvaluadorDeReglas
import com.capicua.smsrelay.domain.rules.NormalizadorTelefono
import com.capicua.smsrelay.domain.rules.PlantillaDeUrl
import com.capicua.smsrelay.data.telegram.DescubridorDeChats
import com.capicua.smsrelay.data.telegram.DestinoDeTelegram
import com.capicua.smsrelay.data.telegram.ResultadoDescubrimiento
import com.capicua.smsrelay.data.whatsapp.DestinoDeWhatsApp
import com.capicua.smsrelay.data.whatsapp.MapeoDeRespuestas
import com.capicua.smsrelay.domain.transport.Canal
import com.capicua.smsrelay.domain.transport.Transportes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
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
    private val transportes: Transportes,
    private val descubridorDeChats: DescubridorDeChats
) : ViewModel() {

    private val _canal = MutableStateFlow(Canal.POR_DEFECTO)
    /** Canal elegido en el selector. Lo cambia el fragment; de él dependen el límite y la validación. */
    val canal: StateFlow<Canal> = _canal.asStateFlow()

    /** Canales que esta compilación sabe entregar. Los demás salen deshabilitados en el selector. */
    val canalesDisponibles: List<Canal> get() = transportes.disponibles

    fun elegirCanal(canal: Canal) {
        _canal.value = canal
    }

    /**
     * Longitud máxima que admite el transporte del canal elegido, con su configuración actual.
     *
     * Es un [StateFlow] y no un `Int` por dos motivos que se suman: el límite cambia con el canal
     * —y el canal se elige en esta misma pantalla—, y dentro de un canal puede depender de la
     * configuración, como en WhatsApp, donde son 4.096 caracteres en texto libre y bastantes
     * menos dentro de una plantilla. Leerlo suspende.
     *
     * Arranca en el límite del texto libre de WhatsApp, que es el canal por defecto, para que el
     * contador no parpadee ni enseñe un cero en el primer repintado.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val limiteCaracteres: StateFlow<Int> = _canal
        .flatMapLatest { canal ->
            flow { emit(transportes.de(canal)?.limiteCaracteres() ?: LIMITE_SIN_TRANSPORTE) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MapeoDeRespuestas.LIMITE_CARACTERES
        )

    /** En qué punto está la búsqueda de chats del bot. Solo tiene sentido con el canal Telegram. */
    sealed interface EstadoDescubrimiento {
        data object Inactivo : EstadoDescubrimiento
        data object Buscando : EstadoDescubrimiento
        data class Terminado(val resultado: ResultadoDescubrimiento) : EstadoDescubrimiento
    }

    private val _descubrimiento =
        MutableStateFlow<EstadoDescubrimiento>(EstadoDescubrimiento.Inactivo)
    val descubrimiento: StateFlow<EstadoDescubrimiento> = _descubrimiento.asStateFlow()

    /** Pregunta al bot quién le ha escrito. Ver [DescubridorDeChats] para sus límites. */
    fun descubrirChats() {
        if (_descubrimiento.value == EstadoDescubrimiento.Buscando) return
        viewModelScope.launch {
            _descubrimiento.value = EstadoDescubrimiento.Buscando
            _descubrimiento.value =
                EstadoDescubrimiento.Terminado(descubridorDeChats.descubrir())
        }
    }

    /** El fragment ya ha enseñado el resultado; volver a reposo para no enseñarlo dos veces. */
    fun descubrimientoAtendido() {
        _descubrimiento.value = EstadoDescubrimiento.Inactivo
    }

    private val _regla = MutableStateFlow<Regla?>(null)
    /** Regla en edición. Null mientras se carga, o para una regla nueva sin valores. */
    val regla: StateFlow<Regla?> = _regla.asStateFlow()

    private val _errores = MutableStateFlow(ErroresRegla())
    val errores: StateFlow<ErroresRegla> = _errores.asStateFlow()

    private val _guardada = MutableStateFlow(false)
    /** Pasa a true cuando la regla se ha persistido, para que la pantalla pueda cerrarse. */
    val guardada: StateFlow<Boolean> = _guardada.asStateFlow()

    /** Carga la regla a editar, o deja el formulario en blanco si [reglaId] es [NUEVA]. */
    fun cargar(reglaId: Long) {
        if (reglaId == NUEVA) return
        viewModelScope.launch {
            val regla = reglaRepository.obtenerPorId(reglaId)
            regla?.let { _canal.value = it.canal }
            _regla.value = regla
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
        destino = validarDestino(regla.canal, regla.destino),
        plantilla = if (regla.plantilla.isBlank()) "La plantilla no puede estar vacía" else null
    )

    /**
     * Valida el destino según el canal y, sobre todo, **explica el error correcto**.
     *
     * Cada canal tiene su forma de destino. Aquí solo se valida la de los canales que esta
     * compilación entrega; la de cada uno la trae su transporte.
     *
     * El caso probable no es un destino con una letra de más: es que **falte el prefijo
     * internacional**, porque el número está en la agenda tal como se marca en cada país.
     * Decirle «no tiene forma de teléfono» a quien ha escrito `600112233` es verdad y no le
     * sirve de nada; decirle «falta el prefijo, escribe +34…» le resuelve el problema.
     *
     * Es el mismo criterio que tenía esta función cuando el destino era un chat de Telegram,
     * aplicado al error más probable de ahora. Entonces el aviso útil era «esto es un teléfono,
     * aquí va un chat»; ahora es justo el contrario, y por eso los tres mensajes se quedan en
     * tres aunque hayan cambiado los tres.
     *
     * Los textos van literales aquí, como los de los demás campos de [ErroresRegla]: es la
     * convención que ya tenía la pantalla y mezclar literales con recursos sería peor que
     * cualquiera de las dos cosas por separado.
     */
    private fun validarDestino(canal: Canal, destino: String): String? = when {
        transportes.de(canal) == null ->
            "Esta versión todavía no entrega por ${canal.etiqueta}: elige otro canal"

        destino.isBlank() ->
            "El destino es obligatorio"

        canal == Canal.WHATSAPP -> validarDestinoWhatsApp(destino)

        canal == Canal.TELEGRAM -> validarDestinoTelegram(destino)

        canal == Canal.SMS -> validarDestinoSms(destino)

        // La validación entera, la de seguridad incluida, es la de PlantillaDeUrl.
        canal == Canal.API -> PlantillaDeUrl.validar(destino)

        else -> null
    }

    /**
     * Un número al que se pueda mandar un SMS, o `{telefono}` para contestar a quien escribió.
     *
     * Se valida poco a propósito, como en SMStoSMS: un número corto de servicio tiene cuatro
     * dígitos y un número nacional va sin prefijo, y los dos son destinos legítimos de un SMS.
     * Lo que sí se atrapa es un chat de Telegram, que con cuatro canales en el mismo campo es el
     * error con más probabilidades.
     */
    private fun validarDestinoSms(destino: String): String? {
        val limpio = destino.trim()
        return when {
            limpio == EvaluadorDeReglas.MARCADOR_TELEFONO -> null

            evaluador.respondeAlRemitente(limpio) ->
                "Para contestar al remitente, el destino tiene que ser {telefono} y nada más"

            DestinoDeTelegram.esInequivocamenteChat(limpio) ->
                "Esto parece un chat de Telegram, no un teléfono. Si es para Telegram, cambia el " +
                    "canal de la regla"

            NormalizadorTelefono.esDestinoEnviable(limpio) &&
                limpio.all { it.isDigit() || it in " +-()" } -> null

            else ->
                "No parece un número de teléfono. Escríbelo tal como lo marcarías, o pon " +
                    "{telefono} para contestar a quien mandó el SMS"
        }
    }

    /**
     * Un número E.164. El error más probable es que falte el prefijo, y el segundo, desde que
     * los cuatro canales comparten este campo, que lo escrito sea un chat de Telegram.
     */
    private fun validarDestinoWhatsApp(destino: String): String? = when {
        DestinoDeWhatsApp.pareceValido(destino) -> null

        DestinoDeWhatsApp.faltaPrefijo(destino) ->
            "Falta el prefijo internacional. Escríbelo empezando por «+» y el prefijo del " +
                "país, por ejemplo +34600112233"

        DestinoDeTelegram.esInequivocamenteChat(destino) ->
            "Esto parece un chat de Telegram, no un teléfono. Si es para Telegram, cambia el " +
                "canal de la regla"

        else ->
            "No parece un número de teléfono. Va en formato internacional: «+», el prefijo " +
                "del país y el número, sin espacios ni guiones"
    }

    /**
     * Un `chat_id` o un `@canal`. El caso probable es el inverso del de WhatsApp: un teléfono
     * escrito con el canal Telegram marcado.
     */
    private fun validarDestinoTelegram(destino: String): String? = when {
        DestinoDeTelegram.pareceValido(destino) -> null

        DestinoDeTelegram.pareceTelefono(destino) ->
            "Esto es un número de teléfono, no un chat de Telegram. Si es para WhatsApp o SMS, " +
                "cambia el canal; si es para Telegram, usa «Elegir de los chats del bot»"

        else ->
            "No parece un chat de Telegram. Un chat_id son solo dígitos, con un signo menos " +
                "delante si es un grupo o un canal"
    }

    companion object {
        /** Id que indica que se está creando una regla, no editando una existente. */
        const val NUEVA = 0L

        /** Lo que enseña el contador con un canal sin transporte: nada que medir. */
        private const val LIMITE_SIN_TRANSPORTE = 0
    }
}
