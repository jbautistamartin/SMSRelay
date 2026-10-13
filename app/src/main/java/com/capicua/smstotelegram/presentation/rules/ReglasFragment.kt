// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.presentation.rules

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.capicua.smstotelegram.R
import com.capicua.smstotelegram.databinding.FragmentReglasBinding
import com.capicua.smstotelegram.domain.model.Regla
import com.capicua.smstotelegram.presentation.home.HomeFragmentDirections
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Lista de reglas de reenvío, ordenada por prioridad.
 *
 * Es la pantalla que sustituye al formulario de una sola URL de la versión HTTP.
 */
@AndroidEntryPoint
class ReglasFragment : Fragment() {

    private var _binding: FragmentReglasBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReglasViewModel by viewModels()
    private lateinit var adapter: ReglasAdapter

    /** Última lista emitida: la reordenación necesita saber el orden actual en pantalla. */
    private var reglasEnPantalla: List<Regla> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReglasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configurarLista()
        configurarAcciones()
        observarReglas()
        observarAvisos()
    }

    private fun configurarLista() {
        adapter = ReglasAdapter(
            alPulsar = { regla -> abrirEditor(regla.id) },
            alCambiarActiva = { regla, activa -> viewModel.cambiarActiva(regla, activa) },
            alSubir = { posicion -> viewModel.mover(posicion, posicion - 1, reglasEnPantalla) },
            alBajar = { posicion -> viewModel.mover(posicion, posicion + 1, reglasEnPantalla) },
            alAbrirMenu = { regla, ancla -> mostrarMenuRegla(regla, ancla) }
        )
        binding.recyclerViewReglas.adapter = adapter
    }

    private fun configurarAcciones() {
        binding.fabNuevaRegla.setOnClickListener {
            abrirEditor(EditarReglaViewModel.NUEVA)
        }
        binding.buttonProbar.setOnClickListener {
            findNavController().navigate(R.id.action_home_a_probar)
        }
    }

    /**
     * Las acciones cuelgan de `homeFragment`, no de esta pantalla: la lista de reglas es una
     * página del ViewPager2, así que el destino actual del NavController es el que aloja el
     * pager. `findNavController()` lo encuentra subiendo por los fragments padre.
     */
    private fun abrirEditor(reglaId: Long) {
        findNavController().navigate(
            HomeFragmentDirections.actionHomeAEditar(reglaId)
        )
    }

    // ── Observación ───────────────────────────────────────────────────────────

    private fun observarReglas() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.reglas.collect { reglas ->
                    reglasEnPantalla = reglas
                    adapter.submitList(reglas)

                    binding.textViewSinReglas.visibility =
                        if (reglas.isEmpty()) View.VISIBLE else View.GONE

                    val activas = reglas.count { it.activa }
                    binding.textViewResumen.text = resources.getQuantityString(
                        R.plurals.reglas_resumen, reglas.size, reglas.size, activas
                    )
                }
            }
        }
    }

    private fun observarAvisos() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.avisos.collect { aviso ->
                    val mensaje = when (aviso) {
                        is AvisoReglas.Eliminada ->
                            getString(R.string.reglas_eliminada, aviso.nombre)
                    }
                    Snackbar.make(binding.root, mensaje, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Menús ─────────────────────────────────────────────────────────────────

    private fun mostrarMenuRegla(regla: Regla, ancla: View) {
        PopupMenu(requireContext(), ancla).apply {
            menu.add(0, MENU_EDITAR, 0, R.string.reglas_editar)
            menu.add(0, MENU_DUPLICAR, 1, R.string.reglas_duplicar)
            menu.add(0, MENU_ELIMINAR, 2, R.string.reglas_eliminar)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDITAR -> abrirEditor(regla.id)
                    MENU_DUPLICAR -> viewModel.duplicar(regla)
                    MENU_ELIMINAR -> confirmarBorrado(regla)
                }
                true
            }
        }.show()
    }

    private fun confirmarBorrado(regla: Regla) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reglas_eliminar)
            .setMessage(getString(R.string.reglas_eliminar_confirmar, regla.nombre))
            .setNegativeButton(R.string.cancelar, null)
            .setPositiveButton(R.string.reglas_eliminar) { _, _ -> viewModel.eliminar(regla) }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerViewReglas.adapter = null
        _binding = null
    }

    private companion object {
        const val MENU_EDITAR = 1
        const val MENU_DUPLICAR = 2
        const val MENU_ELIMINAR = 3
    }
}
