// SMStoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstosms.presentation.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstosms.data.repository.ReglaRepository
import com.capicua.smstosms.domain.model.Regla
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Mensajes puntuales para la pantalla: resultado de una acción que hay que comunicar una vez. */
sealed interface AvisoReglas {
    data class Eliminada(val nombre: String) : AvisoReglas
}

/**
 * Edición de la lista de reglas.
 *
 * **Guardar y restaurar las reglas no está aquí, sino en Ajustes.** Las reglas son
 * configuración —son lo que decide a quién se reenvía qué—, y su copia viaja en el mismo
 * fichero que los demás ajustes: ver `data/config/ConfigBackup`. Esta pantalla edita la lista;
 * no la exporta.
 */
@HiltViewModel
class ReglasViewModel @Inject constructor(
    private val reglaRepository: ReglaRepository
) : ViewModel() {

    /** Lista reactiva de reglas, ya ordenada por prioridad. */
    val reglas: Flow<List<Regla>> = reglaRepository.observarTodas()

    private val _avisos = MutableSharedFlow<AvisoReglas>(extraBufferCapacity = 4)
    val avisos: SharedFlow<AvisoReglas> = _avisos.asSharedFlow()

    // ── Edición de la lista ───────────────────────────────────────────────────

    fun cambiarActiva(regla: Regla, activa: Boolean) {
        viewModelScope.launch { reglaRepository.cambiarActiva(regla.id, activa) }
    }

    fun eliminar(regla: Regla) {
        viewModelScope.launch {
            reglaRepository.eliminar(regla.id)
            _avisos.emit(AvisoReglas.Eliminada(regla.nombre))
        }
    }

    /** Duplica una regla, dejándola desactivada para que no empiece a reenviar sin revisarla. */
    fun duplicar(regla: Regla) {
        viewModelScope.launch {
            reglaRepository.crear(
                regla.copy(
                    id = 0,
                    nombre = "${regla.nombre} (copia)",
                    activa = false
                )
            )
        }
    }

    /**
     * Sube o baja una regla una posición.
     *
     * Se recalcula el orden de toda la lista en una transacción, en lugar de intercambiar dos
     * valores: así nunca quedan dos reglas con el mismo `orden`, lo que haría la evaluación no
     * determinista.
     */
    fun mover(desde: Int, hacia: Int, listaActual: List<Regla>) {
        if (desde == hacia || desde !in listaActual.indices || hacia !in listaActual.indices) return

        val reordenada = listaActual.toMutableList()
        val movida = reordenada.removeAt(desde)
        reordenada.add(hacia, movida)

        viewModelScope.launch {
            reglaRepository.reordenar(reordenada.map { it.id })
        }
    }

}
