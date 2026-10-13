// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.presentation.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstotelegram.data.config.ConfigDataStore
import com.capicua.smstotelegram.data.repository.ReglaRepository
import com.capicua.smstotelegram.domain.rules.EvaluadorDeReglas
import com.capicua.smstotelegram.domain.rules.NormalizadorTelefono
import com.capicua.smstotelegram.domain.rules.ResultadoEvaluacion
import com.capicua.smstotelegram.domain.transport.EnviadorDeMensajes
import com.capicua.smstotelegram.domain.transport.ResultadoEnvio
import com.capicua.smstotelegram.util.toDisplayString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Una coincidencia tal como se muestra en el panel de prueba. */
data class CoincidenciaProbada(
    val posicion: Int,
    val nombreRegla: String,
    val destino: String,
    val textoFinal: String
)

/** Resultado de probar las reglas con un SMS de ejemplo, listo para pintar. */
data class PruebaReglas(
    val reglasActivas: Int,
    val coincidencias: List<CoincidenciaProbada>,
    val avisos: List<String>
) {
    val hayCoincidencias: Boolean get() = coincidencias.isNotEmpty()
}

/** Estado del envío real de prueba. */
sealed interface EstadoEnvioPrueba {
    data object Inactivo : EstadoEnvioPrueba
    data object Enviando : EstadoEnvioPrueba
    data class Correcto(val destino: String) : EstadoEnvioPrueba
    data class Fallido(val motivo: String) : EstadoEnvioPrueba
}

/**
 * Prueba las reglas contra un SMS de ejemplo **sin enviar nada**.
 *
 * Es la herramienta que convierte una expresión regular en algo verificable: se pega un
 * remitente y un texto, y la pantalla dice qué regla casa, a qué destino iría y con qué texto.
 * Sin esto, comprobar una regla exige esperar a que llegue un SMS real.
 */
@HiltViewModel
class ProbarReglasViewModel @Inject constructor(
    private val reglaRepository: ReglaRepository,
    private val evaluador: EvaluadorDeReglas,
    private val enviador: EnviadorDeMensajes,
    private val configDataStore: ConfigDataStore
) : ViewModel() {

    /** Longitud máxima que admite el transporte, para mostrarla junto a cada texto final. */
    val limiteCaracteres: Int = enviador.limiteCaracteres

    private val _prueba = MutableStateFlow<PruebaReglas?>(null)
    val prueba: StateFlow<PruebaReglas?> = _prueba.asStateFlow()

    private val _envio = MutableStateFlow<EstadoEnvioPrueba>(EstadoEnvioPrueba.Inactivo)
    val envio: StateFlow<EstadoEnvioPrueba> = _envio.asStateFlow()

    /** Evalúa las reglas activas contra el ejemplo. No toca la radio ni la base de datos. */
    fun probar(telefono: String, mensaje: String) {
        viewModelScope.launch {
            val reglas = reglaRepository.obtenerActivas()
            val fecha = Instant.now().toDisplayString()

            val resultado = evaluador.evaluar(
                telefono = telefono,
                mensaje = mensaje,
                fecha = fecha,
                reglas = reglas
            )

            _prueba.value = PruebaReglas(
                reglasActivas = reglas.size,
                coincidencias = resultado.coincidencias.mapIndexed { indice, coincidencia ->
                    CoincidenciaProbada(
                        posicion = indice + 1,
                        nombreRegla = coincidencia.nombreRegla,
                        destino = coincidencia.destino,
                        textoFinal = coincidencia.textoFinal
                    )
                },
                avisos = construirAvisos(resultado, telefono)
            )
            _envio.value = EstadoEnvioPrueba.Inactivo
        }
    }

    /**
     * Envía de verdad el primer reenvío que resultaría de la prueba.
     *
     * Deliberadamente envía **solo el primero**, aunque haya varias coincidencias: una prueba
     * tiene que ser una sola cosa observable, y recibir tres mensajes de golpe no aclara cuál
     * vino de qué regla.
     */
    fun enviarPrueba() {
        val primera = _prueba.value?.coincidencias?.firstOrNull() ?: return
        if (_envio.value == EstadoEnvioPrueba.Enviando) return

        viewModelScope.launch {
            _envio.value = EstadoEnvioPrueba.Enviando
            val config = configDataStore.config.first()

            val resultado = enviador.enviar(
                destino = primera.destino,
                texto = primera.textoFinal,
                timeoutSegundos = config.timeoutEnvioSegundos
            )

            _envio.value = when (resultado) {
                is ResultadoEnvio.Enviado ->
                    EstadoEnvioPrueba.Correcto(primera.destino)
                is ResultadoEnvio.ErrorTransitorio ->
                    EstadoEnvioPrueba.Fallido(resultado.motivo)
                is ResultadoEnvio.ErrorPermanente ->
                    EstadoEnvioPrueba.Fallido(resultado.motivo)
            }
        }
    }

    fun limpiar() {
        _prueba.value = null
        _envio.value = EstadoEnvioPrueba.Inactivo
    }

    /**
     * Avisos que explican por qué el resultado es el que es. Son más útiles que el propio
     * resultado cuando una regla no casa y no se entiende el motivo.
     */
    private suspend fun construirAvisos(
        resultado: ResultadoEvaluacion,
        telefono: String
    ): List<String> =
        buildList {
            resultado.reglasInvalidas.forEach { invalida ->
                add(
                    "La regla «${invalida.nombreRegla}» se descarta: la expresión de " +
                        "${invalida.campo.name.lowercase()} «${invalida.patron}» no es válida " +
                        "(${invalida.motivo})"
                )
            }
            if (telefono.count { it.isDigit() } < 9 && telefono.isNotBlank()) {
                add(
                    "«$telefono» no tiene dígitos suficientes para ser un número: se tratará " +
                        "como remitente alfanumérico, igual que BANCO o AMAZON"
                )
            }

            // Las reglas que contestan al remitente quedan exentas de la protección antibucle,
            // y eso hay que decirlo donde se está comprobando la regla, no solo en la ayuda.
            val contestanAlRemitente = resultado.coincidencias.filter { it.respondeAlRemitente }
            if (contestanAlRemitente.isNotEmpty()) {
                val nombres = contestanAlRemitente.joinToString(", ") { "«${it.nombreRegla}»" }
                if (NormalizadorTelefono.esDestinoEnviable(telefono)) {
                    add(
                        "$nombres contesta al propio remitente ($telefono). La protección " +
                            "antibucle no la bloquea porque es intencionada: el límite de " +
                            "reenvíos por minuto es la única red si el otro extremo también " +
                            "responde automáticamente"
                    )
                } else {
                    add(
                        "$nombres contesta al remitente, pero a «$telefono» no se le puede " +
                            "enviar un SMS: ese reenvío se omitirá"
                    )
                }
            }
            enviador.motivoNoDisponible()?.let { motivo ->
                add("La prueba real no funcionará: $motivo")
            }
        }
}
