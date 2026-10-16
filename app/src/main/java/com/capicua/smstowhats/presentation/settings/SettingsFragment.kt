// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.presentation.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capicua.smstowhats.R
import com.capicua.smstowhats.data.config.AppConfig
import com.capicua.smstowhats.data.config.ResultadoCopia
import com.capicua.smstowhats.data.whatsapp.IdNumeroTelefono
import com.capicua.smstowhats.data.whatsapp.ResultadoDeCuenta
import com.capicua.smstowhats.data.whatsapp.TokenDeAcceso
import com.capicua.smstowhats.databinding.FragmentSettingsBinding
import com.capicua.smstowhats.util.InicioAutomatico
import com.capicua.smstowhats.util.OptimizacionBateria
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Ajustes generales: exención de batería, protecciones y política de reintentos.
 *
 * Las reglas de reenvío no se editan aquí: son una lista ordenada y tienen su propia pantalla.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private var cargaInicial = true

    // ── Selectores de fichero del sistema ─────────────────────────────────────
    //
    // La copia se escribe y se lee sobre el fichero que elige el operador, así que no hace
    // falta ningún permiso de almacenamiento.

    private val crearFichero = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.exportar(it) } }

    private val abrirFichero = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importar(it) } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configurarBateria()
        configurarAutoinicio()
        configurarCopia()
        configurarComprobacionDelBot()
        observarConfiguracion()
        observarComprobacionDeCuenta()
        observarCopias()
        configurarBotonGuardar()
    }

    /**
     * El estado de la exención se refresca al volver a la pantalla porque se concede **fuera**
     * de la aplicación: el usuario sale a Ajustes del sistema y vuelve, y lo que tiene que ver
     * al volver es el estado nuevo, no el que había cuando se fue.
     */
    override fun onResume() {
        super.onResume()
        pintarEstadoBateria()
    }

    // ── Comprobación del bot ──────────────────────────────────────────────────

    private fun configurarComprobacionDelBot() {
        binding.buttonValidarCuenta.setOnClickListener {
            // Se comprueba lo que hay escrito, no lo guardado: la gracia es saberlo antes de
            // guardar unas credenciales que no sirven.
            viewModel.comprobarCuenta(
                binding.editTextTokenAcceso.text?.toString().orEmpty(),
                binding.editTextIdNumero.text?.toString().orEmpty()
            )
        }
    }

    private fun observarComprobacionDeCuenta() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.comprobacion.collect { estado ->
                    val vista = binding.textViewResultadoCuenta
                    binding.buttonValidarCuenta.isEnabled =
                        estado != SettingsViewModel.EstadoComprobacion.Comprobando

                    when (estado) {
                        SettingsViewModel.EstadoComprobacion.Inactivo ->
                            vista.visibility = View.GONE

                        SettingsViewModel.EstadoComprobacion.Comprobando -> {
                            vista.visibility = View.VISIBLE
                            vista.setText(R.string.settings_cuenta_comprobando)
                            vista.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
                        }

                        is SettingsViewModel.EstadoComprobacion.Terminada -> {
                            vista.visibility = View.VISIBLE
                            pintarResultadoDeCuenta(vista, estado.resultado)
                        }
                    }
                }
            }
        }
    }

    private fun pintarResultadoDeCuenta(
        vista: android.widget.TextView,
        resultado: ResultadoDeCuenta
    ) {
        when (resultado) {
            is ResultadoDeCuenta.Correcto -> {
                // Ensenar de que numero se trata es lo que hace la respuesta comprobable: quien
                // la pidio puede reconocer su numero, o ver que ha pegado el id de otro.
                //
                // La calidad va detras y en su propia linea porque no es parte de la respuesta
                // a "sirven?": sirven igual con la calidad en amarillo. Es un aviso de que Meta
                // esta a punto de limitar el numero, y es el unico de los riesgos de este
                // proyecto que avisa antes de materializarse.
                vista.text = buildString {
                    append(
                        getString(
                            R.string.settings_cuenta_correcto,
                            resultado.nombre,
                            resultado.telefono
                        )
                    )
                    appendLine()
                    append(getString(R.string.settings_cuenta_calidad, resultado.calidad))
                }
                vista.setTextColor(colorDeCalidad(resultado.calidad))
            }

            is ResultadoDeCuenta.Rechazado -> {
                vista.text = getString(R.string.settings_cuenta_rechazado, resultado.motivo)
                vista.setTextColor(resources.getColor(R.color.status_failed, null))
            }

            is ResultadoDeCuenta.NoSePudo -> {
                // Sin red no se sabe si las credenciales valen: decir que estan mal seria mentir.
                vista.text = getString(R.string.settings_cuenta_sin_red, resultado.motivo)
                vista.setTextColor(resources.getColor(R.color.status_pending, null))
            }

            ResultadoDeCuenta.SinCredenciales -> {
                vista.setText(R.string.settings_cuenta_sin_credenciales)
                vista.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        }
    }

    /**
     * Color del resultado segun la calidad que Meta da al numero.
     *
     * Un `GREEN` se pinta como un exito y un `YELLOW` o un `RED` como un aviso, aunque las
     * credenciales sirvan en los tres casos: lo que esta en riesgo no es la configuracion, es
     * el numero. `UNKNOWN` es lo normal en un numero recien creado y no dice nada malo.
     */
    private fun colorDeCalidad(calidad: String): Int = when (calidad.uppercase()) {
        "RED" -> resources.getColor(R.color.status_failed, null)
        "YELLOW" -> resources.getColor(R.color.status_pending, null)
        else -> resources.getColor(R.color.status_delivered, null)
    }

    private fun colorDe(atributo: Int): Int {
        val valor = android.util.TypedValue()
        requireContext().theme.resolveAttribute(atributo, valor, true)
        return valor.data
    }

    // ── Exención de batería ───────────────────────────────────────────────────

    private fun configurarBateria() {
        binding.buttonBateria.setOnClickListener {
            if (!OptimizacionBateria.abrirAjuste(requireContext())) {
                Snackbar.make(
                    binding.root,
                    R.string.settings_bateria_sin_pantalla,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Pinta el estado actual de la exención.
     *
     * Cuando ya está concedida el botón se queda, pero como acción secundaria: sirve para
     * revisarlo, y algunos fabricantes revocan la exención por su cuenta tras una actualización.
     */
    private fun pintarEstadoBateria() {
        val exenta = OptimizacionBateria.estaExenta(requireContext())

        binding.textViewBateriaEstado.setText(
            if (exenta) R.string.settings_bateria_ok else R.string.settings_bateria_pendiente
        )
        binding.textViewBateriaEstado.setTextColor(
            resources.getColor(
                if (exenta) R.color.status_delivered else R.color.status_pending,
                null
            )
        )
        binding.textViewBateriaDetalle.setText(
            if (exenta) {
                R.string.settings_bateria_ok_detalle
            } else {
                R.string.settings_bateria_pendiente_detalle
            }
        )
        binding.buttonBateria.setText(
            if (exenta) R.string.settings_bateria_revisar else R.string.settings_bateria_activar
        )
    }

    // ── Inicio automático ─────────────────────────────────────────────────────

    /**
     * No hay estado que pintar: el permiso de inicio automático de los fabricantes no se puede
     * consultar desde la aplicación. Solo queda llevar al operador a la pantalla correcta.
     */
    private fun configurarAutoinicio() {
        binding.buttonAutoinicio.setOnClickListener {
            if (!InicioAutomatico.abrirAjuste(requireContext())) {
                Snackbar.make(
                    binding.root,
                    R.string.settings_autoinicio_sin_pantalla,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Copia de seguridad ────────────────────────────────────────────────────

    private fun configurarCopia() {
        binding.buttonExportar.setOnClickListener {
            crearFichero.launch(viewModel.nombreFicheroCopia())
        }
        // Importar cambia los ajustes y añade reglas: se avisa antes de qué va a pasar, porque
        // desde fuera «importar» puede entenderse como «sustituir».
        binding.buttonImportar.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_importar)
                .setMessage(R.string.settings_importar_confirmar)
                .setNegativeButton(R.string.cancelar, null)
                .setPositiveButton(R.string.settings_importar) { _, _ ->
                    abrirFichero.launch(arrayOf("application/json", "text/plain"))
                }
                .show()
        }
    }

    private fun observarCopias() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.copias.collect { resultado ->
                    val mensaje = when (resultado) {
                        is ResultadoCopia.Exportada ->
                            getString(R.string.settings_copia_exportada, resultado.reglas)

                        is ResultadoCopia.Importada -> when {
                            resultado.rechazadas > 0 -> getString(
                                R.string.settings_copia_importada_con_rechazos,
                                resultado.aceptadas, resultado.rechazadas
                            )
                            else -> getString(
                                R.string.settings_copia_importada, resultado.aceptadas
                            )
                        }

                        is ResultadoCopia.Fallo ->
                            getString(R.string.settings_copia_fallo, resultado.motivo)
                    }
                    Snackbar.make(binding.root, mensaje, Snackbar.LENGTH_LONG).show()

                    // Los campos en pantalla son los de antes de importar: hay que repintarlos
                    // con lo que acaba de entrar, o el siguiente «Guardar» desharía la copia.
                    //
                    // No se leen aquí: DataStore emite su valor nuevo cuando termina de
                    // escribir, y en este instante `config` puede seguir siendo el de antes.
                    // Rearmando la carga inicial, el repintado lo hace la emisión que llegue.
                    if (resultado is ResultadoCopia.Importada && resultado.conAjustes) {
                        cargaInicial = true
                    }
                }
            }
        }
    }

    private fun observarConfiguracion() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.config.collect { config ->
                    // config es null mientras DataStore no ha emitido su primer valor real;
                    // esperamos a ese valor antes de rellenar los campos.
                    if (config != null && cargaInicial) {
                        rellenarCampos(config)
                        cargaInicial = false
                    }
                }
            }
        }
    }

    private fun rellenarCampos(config: AppConfig) {
        binding.editTextTokenAcceso.setText(config.tokenAcceso)
        binding.editTextIdNumero.setText(config.idNumeroTelefono)
        binding.switchRecepcion.isChecked = config.recepcionActiva
        binding.editTextMaxReenviosMinuto.setText(config.maxReenviosPorMinuto.toString())
        binding.editTextTimeoutEnvio.setText(config.timeoutEnvioSegundos.toString())
        binding.editTextMaxReintentos.setText(config.maxReintentos.toString())
        binding.editTextIntervalo.setText(config.intervaloReintentoSegundos.toString())
    }

    private fun configurarBotonGuardar() {
        binding.buttonGuardar.setOnClickListener {
            val token = TokenDeAcceso.normalizar(
                binding.editTextTokenAcceso.text?.toString().orEmpty()
            )
            val idNumero = IdNumeroTelefono.normalizar(
                binding.editTextIdNumero.text?.toString().orEmpty()
            )
            val maxPorMinuto = binding.editTextMaxReenviosMinuto.entero(VALORES.maxReenviosPorMinuto)
            val timeoutEnvio = binding.editTextTimeoutEnvio.entero(VALORES.timeoutEnvioSegundos)
            val maxReintentos = binding.editTextMaxReintentos.entero(VALORES.maxReintentos)
            val intervalo = binding.editTextIntervalo.entero(VALORES.intervaloReintentoSegundos)

            if (!validar(
                    token, idNumero, maxPorMinuto, timeoutEnvio, maxReintentos, intervalo
                )
            ) {
                return@setOnClickListener
            }

            viewModel.guardar(
                AppConfig(
                    tokenAcceso = token,
                    idNumeroTelefono = idNumero,
                    maxReintentos = maxReintentos,
                    intervaloReintentoSegundos = intervalo,
                    timeoutEnvioSegundos = timeoutEnvio,
                    maxReenviosPorMinuto = maxPorMinuto,
                    recepcionActiva = binding.switchRecepcion.isChecked
                )
            )
            Snackbar.make(
                binding.root,
                if (binding.switchRecepcion.isChecked) {
                    R.string.settings_guardado
                } else {
                    // Apagar la recepción no es un ajuste más: la aplicación deja de hacer su
                    // trabajo y eso tiene que quedar dicho, no deducido.
                    R.string.settings_guardado_sin_recepcion
                },
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun validar(
        token: String,
        idNumero: String,
        maxPorMinuto: Int,
        timeoutEnvio: Int,
        maxReintentos: Int,
        intervalo: Int
    ): Boolean {
        var valido = true

        // Solo la forma. Si las credenciales sirven de verdad únicamente lo sabe Meta, y eso es
        // lo que comprueba el botón de comprobación; esto atrapa el fallo más común, que es
        // pegar algo que no es una credencial.
        if (TokenDeAcceso.pareceValido(token)) {
            binding.tilTokenAcceso.error = null
        } else {
            binding.tilTokenAcceso.error = getString(R.string.settings_token_acceso_invalido)
            valido = false
        }

        // El id tiene un error propio para el caso más probable de todos: pegar el número de
        // teléfono, que la consola de Meta enseña justo al lado. Decir «no tiene forma de id»
        // sería verdad y no resolvería nada.
        if (IdNumeroTelefono.pareceValido(idNumero)) {
            binding.tilIdNumero.error = null
        } else {
            binding.tilIdNumero.error = getString(
                if (IdNumeroTelefono.pareceTelefono(idNumero)) {
                    R.string.settings_id_numero_es_telefono
                } else {
                    R.string.settings_id_numero_invalido
                }
            )
            valido = false
        }

        valido = comprobarRango(
            binding.tilMaxReenviosMinuto, maxPorMinuto, 1, 100,
            getString(R.string.settings_error_rango, 1, 100)
        ) && valido

        valido = comprobarRango(
            binding.tilTimeoutEnvio, timeoutEnvio, 10, 300,
            getString(R.string.settings_error_rango_segundos, 10, 300)
        ) && valido

        valido = comprobarRango(
            binding.tilMaxReintentos, maxReintentos, 1, 100,
            getString(R.string.settings_error_rango, 1, 100)
        ) && valido

        valido = comprobarRango(
            binding.tilIntervalo, intervalo, 5, 3600,
            getString(R.string.settings_error_rango_segundos, 5, 3600)
        ) && valido

        return valido
    }

    private fun comprobarRango(
        campo: com.google.android.material.textfield.TextInputLayout,
        valor: Int,
        minimo: Int,
        maximo: Int,
        mensaje: String
    ): Boolean = if (valor < minimo || valor > maximo) {
        campo.error = mensaje
        false
    } else {
        campo.error = null
        true
    }

    private fun com.google.android.material.textfield.TextInputEditText.entero(porDefecto: Int): Int =
        text?.toString()?.trim()?.toIntOrNull() ?: porDefecto

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Valores por defecto, para no repetirlos cuando un campo se queda vacío. */
        val VALORES = AppConfig()
    }
}
