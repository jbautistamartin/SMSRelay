// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.presentation.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capicua.smstowhats.R
import com.capicua.smstowhats.databinding.FragmentDashboardBinding
import com.capicua.smstowhats.presentation.dashboard.adapter.SmsListAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var adapter: SmsListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SmsListAdapter()
        binding.recyclerViewSms.adapter = adapter

        binding.fabBorrar.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Borrar SMS")
                .setMessage("Se eliminarán todos los mensajes. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar") { _, _ -> viewModel.limpiarTodos() }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.smsList.collect { mensajes ->
                        adapter.submitList(mensajes)
                        binding.textViewEmpty.visibility =
                            if (mensajes.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    // El aviso del transporte va en rojo y por encima del de «sin reglas»:
                    // sin poder entregar, lo que digan las reglas da igual.
                    viewModel.avisoTransporte.collect { motivo ->
                        binding.textViewAvisoTransporte.visibility =
                            if (motivo == null) View.GONE else View.VISIBLE
                        if (motivo != null) {
                            binding.textViewAvisoTransporte.text =
                                getString(R.string.dashboard_aviso_transporte, motivo)
                        }
                    }
                }
                launch {
                    // Un tope de 0 significa «sin tope», y entonces enseñar «143 de 0» sería
                    // absurdo: se dice que no hay tope y se deja la cifra sola.
                    viewModel.mensajesDelMes.collect { (mes, tope) ->
                        binding.textViewMensajesMes.text = if (tope > 0) {
                            getString(
                                R.string.dashboard_mensajes_mes,
                                mes.total, tope, mes.servicio, mes.plantilla
                            )
                        } else {
                            getString(
                                R.string.dashboard_mensajes_mes_sin_tope,
                                mes.total, mes.servicio, mes.plantilla
                            )
                        }
                    }
                }
                launch {
                    viewModel.resumen.collect { resumen ->
                        binding.textViewResumen.text = getString(
                            R.string.dashboard_resumen,
                            resumen.totalSms,
                            resumen.reenviosPendientes,
                            resumen.reglasActivas
                        )
                        // Sin reglas activas la app recibe y no reenvía: hay que decirlo aquí,
                        // o parecerá que los envíos fallan.
                        binding.textViewAvisoSinReglas.visibility =
                            if (resumen.reglasActivas == 0) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}