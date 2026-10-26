// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.presentation.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capicua.smsrelay.R
import com.capicua.smsrelay.data.config.AppConfig
import com.capicua.smsrelay.data.config.ResultadoCopia
import com.capicua.smsrelay.data.config.ModoDeEntrega
import com.capicua.smsrelay.data.telegram.ResultadoDelBot
import com.capicua.smsrelay.data.telegram.TokenDeBot
import com.capicua.smsrelay.data.whatsapp.IdNumeroTelefono
import com.capicua.smsrelay.data.whatsapp.PlantillaDeWhatsApp
import com.capicua.smsrelay.data.whatsapp.ResultadoDeCuenta
import com.capicua.smsrelay.data.whatsapp.TokenDeAcceso
import com.capicua.smsrelay.databinding.FragmentSettingsBinding
import com.capicua.smsrelay.domain.transport.Canal
import com.capicua.smsrelay.util.InicioAutomatico
import com.capicua.smsrelay.util.OptimizacionBateria
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

    /** SIM elegida en el selector; se guarda con el resto al pulsar Guardar. */
    private var simElegida = AppConfig.SIM_POR_DEFECTO

    /**
     * Lo último que llegó de DataStore. Guardar parte de aquí y no de un `AppConfig()` vacío,
     * para que un ajuste que esta pantalla no enseña no vuelva a su valor por defecto.
     */
    private var configCargada: AppConfig? = null

    // ── Permisos del canal SMS ────────────────────────────────────────────────

    private val pedirPermisoEnviar = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        pintarEstadoSms()
        viewModel.refrescarCanales()
        if (!concedido) {
            Snackbar.make(binding.root, R.string.settings_sms_permiso_denegado, Snackbar.LENGTH_LONG)
                .show()
        }
    }

    private val pedirPermisoSims = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { configurarSelectorSim() }

    // ── Selectores de fichero del sistema ─────────────────────────────────────
    //
    // La copia se escribe y se lee sobre el fichero que elige el operador, así que no hace
    // falta ningún permiso de almacenamiento.

    private val crearFichero = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        // La casilla se lee aquí y no cuando se pulsó el botón: entre una cosa y otra está el
        // selector de ficheros del sistema, y lo que vale es lo que esté marcado al confirmar.
        uri?.let { viewModel.exportar(it, binding.switchIncluirCredenciales.isChecked) }
    }

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
        configurarSms()
        observarCanales()
        configurarCopia()
        configurarComprobacionDeCuenta()
        configurarComprobacionDelBot()
        configurarPlantilla()
        observarConfiguracion()
        observarComprobacionDeCuenta()
        observarComprobacionDelBot()
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
        // Los dos permisos del canal SMS también se pueden conceder fuera, en la ficha de la
        // aplicación, y lo que hay que ver al volver es el estado nuevo.
        pintarEstadoSms()
        configurarSelectorSim()
        viewModel.refrescarCanales()
    }

    // ── Entrega: la plantilla ─────────────────────────────────────────────────

    /**
     * Enseña el cuerpo exacto que hay que dar de alta en el Business Manager, y lo copia.
     *
     * El botón de copiar no es una comodidad: escribir ese cuerpo a mano es la forma segura de
     * equivocarse en una línea en blanco, y una plantilla aprobada con la maquetación mal puesta
     * no se puede corregir —hay que dar de alta otra y esperar a que la aprueben—.
     */
    private fun configurarPlantilla() {
        binding.textViewPlantillaContrato.text = PlantillaDeWhatsApp.CUERPO_RECOMENDADO
        binding.textViewPlantillaCoste.text =
            getString(R.string.settings_plantilla_coste, PlantillaDeWhatsApp.PRESUPUESTO)

        binding.buttonCopiarPlantilla.setOnClickListener {
            val portapapeles = requireContext()
                .getSystemService(android.content.ClipboardManager::class.java)
            portapapeles?.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    getString(R.string.settings_plantilla_contrato_titulo),
                    PlantillaDeWhatsApp.CUERPO_RECOMENDADO
                )
            )
            Snackbar.make(
                binding.root,
                R.string.settings_plantilla_copiado,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    // ── Comprobación de las credenciales ──────────────────────────────────────────────────

    private fun configurarComprobacionDeCuenta() {
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

    // ── Canales ───────────────────────────────────────────────────────────────

    private fun observarCanales() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.estadoCanales.collect { estados -> pintarCanales(estados) }
            }
        }
    }

    /**
     * Una línea por canal, con su interruptor. Verde si está listo, ámbar si tiene reglas activas
     * y no puede entregar —eso es un aviso, no un detalle—, y en el color tenue de siempre si no
     * lo usa nadie, esté como esté: un canal sin configurar que no usa ninguna regla no es un
     * problema. Un canal apagado dice cuántas reglas suyas se están saltando.
     *
     * De paso esconde la sección de cada canal apagado: sus ajustes no hacen nada mientras lo
     * esté, y una pantalla con cuatro secciones es larga para quien usa uno.
     */
    private fun pintarCanales(estados: List<SettingsViewModel.EstadoCanal>) {
        val contenedor = binding.layoutEstadoCanales
        contenedor.removeAllViews()
        val encendidos = estados.count { it.activo }
        estados.forEach { estado ->
            val fila = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val linea = android.widget.TextView(requireContext()).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, 4, 0, 4)
            }
            val interruptor = com.google.android.material.materialswitch.MaterialSwitch(requireContext()).apply {
                isChecked = estado.activo
                contentDescription = getString(R.string.settings_canal_interruptor, estado.canal.etiqueta)
                setOnCheckedChangeListener { vista, marcado ->
                    // Sin ningún canal la aplicación recibe SMS y no puede reenviar nada: para
                    // eso ya está el interruptor de la recepción, que lo dice con todas las letras.
                    if (!marcado && estado.activo && encendidos <= 1) {
                        vista.isChecked = true
                        Snackbar.make(binding.root, R.string.settings_canal_ultimo, Snackbar.LENGTH_LONG).show()
                    } else if (marcado != estado.activo) {
                        viewModel.cambiarCanal(estado.canal, marcado)
                    }
                }
            }
            val etiqueta = estado.canal.etiqueta
            when {
                !estado.activo -> {
                    linea.text = if (estado.reglasActivas > 0) {
                        getString(R.string.settings_canal_desactivado_con_reglas, etiqueta, estado.reglasActivas)
                    } else {
                        getString(R.string.settings_canal_desactivado, etiqueta)
                    }
                    linea.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
                estado.motivo == null && estado.reglasActivas > 0 -> {
                    linea.text = getString(R.string.settings_canal_listo, etiqueta, estado.reglasActivas)
                    linea.setTextColor(resources.getColor(R.color.status_delivered, null))
                }
                estado.motivo == null -> {
                    linea.text = getString(R.string.settings_canal_sin_uso, etiqueta)
                    linea.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
                estado.reglasActivas > 0 -> {
                    linea.text = getString(
                        R.string.settings_canal_falta, etiqueta, estado.reglasActivas, estado.motivo
                    )
                    linea.setTextColor(resources.getColor(R.color.status_pending, null))
                }
                else -> {
                    linea.text = getString(R.string.settings_canal_falta_sin_uso, etiqueta, estado.motivo)
                    linea.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
            }
            fila.addView(
                linea,
                android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            fila.addView(interruptor)
            contenedor.addView(fila)
        }

        val activo = { canal: Canal -> estados.firstOrNull { it.canal == canal }?.activo != false }
        binding.layoutSeccionWhatsapp.isVisible = activo(Canal.WHATSAPP)
        binding.layoutSeccionTelegram.isVisible = activo(Canal.TELEGRAM)
        binding.layoutSeccionSms.isVisible = activo(Canal.SMS)
        binding.layoutSeccionApi.isVisible = activo(Canal.API)
        // El tope mensual vive en «Protecciones», pero es de WhatsApp.
        binding.tilMaxMensajesMes.isVisible = activo(Canal.WHATSAPP)
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    private fun configurarSms() {
        binding.buttonSmsPermiso.setOnClickListener {
            pedirPermisoEnviar.launch(Manifest.permission.SEND_SMS)
        }
        binding.buttonSimDetectar.setOnClickListener {
            pedirPermisoSims.launch(Manifest.permission.READ_PHONE_STATE)
        }
        binding.dropdownSim.setOnItemClickListener { _, _, posicion, _ ->
            val sims = viewModel.simsDisponibles
            simElegida =
                if (posicion == 0) AppConfig.SIM_POR_DEFECTO else sims[posicion - 1].subscriptionId
        }
    }

    private fun pintarEstadoSms() {
        val puede = viewModel.puedeEnviarSms
        binding.textViewSmsPermiso.setText(
            if (puede) R.string.settings_sms_permiso_ok else R.string.settings_sms_permiso_falta
        )
        binding.textViewSmsPermiso.setTextColor(
            resources.getColor(if (puede) R.color.status_delivered else R.color.status_failed, null)
        )
        binding.buttonSmsPermiso.isVisible = !puede
    }

    /**
     * El selector de SIM, solo si hay dos o más.
     *
     * Sin `READ_PHONE_STATE` no se puede saber cuántas hay, así que en su lugar sale el botón que
     * pide el permiso. Con una sola, ni selector ni botón: no hay nada que elegir.
     */
    private fun configurarSelectorSim() {
        val tienePermiso = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val sims = viewModel.simsDisponibles

        binding.layoutSimDetectar.isVisible = !tienePermiso
        binding.tilSim.isVisible = sims.size >= 2
        if (sims.size < 2) return

        val etiquetas = buildList {
            add(getString(R.string.settings_sim_por_defecto))
            addAll(sims.map { it.etiqueta })
        }
        binding.dropdownSim.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, etiquetas)
        )
        pintarSimElegida()
    }

    private fun pintarSimElegida() {
        val sims = viewModel.simsDisponibles
        if (sims.size < 2) return
        val elegida = sims.firstOrNull { it.subscriptionId == simElegida }
        binding.dropdownSim.setText(
            elegida?.etiqueta ?: getString(R.string.settings_sim_por_defecto),
            false
        )
    }

    // ── Comprobación del bot de Telegram ──────────────────────────────────────

    private fun configurarComprobacionDelBot() {
        binding.buttonValidarBot.setOnClickListener {
            // Lo escrito, no lo guardado: la gracia es saberlo antes de guardar un token que no
            // sirve.
            viewModel.comprobarBot(binding.editTextTokenBot.text?.toString().orEmpty())
        }
    }

    private fun observarComprobacionDelBot() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.comprobacionBot.collect { estado ->
                    val vista = binding.textViewResultadoBot
                    binding.buttonValidarBot.isEnabled =
                        estado != SettingsViewModel.EstadoComprobacionBot.Comprobando

                    when (estado) {
                        SettingsViewModel.EstadoComprobacionBot.Inactivo ->
                            vista.visibility = View.GONE

                        SettingsViewModel.EstadoComprobacionBot.Comprobando -> {
                            vista.visibility = View.VISIBLE
                            vista.setText(R.string.settings_bot_comprobando)
                            vista.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
                        }

                        is SettingsViewModel.EstadoComprobacionBot.Terminada -> {
                            vista.visibility = View.VISIBLE
                            pintarResultadoDelBot(vista, estado.resultado)
                        }
                    }
                }
            }
        }
    }

    private fun pintarResultadoDelBot(vista: android.widget.TextView, resultado: ResultadoDelBot) {
        when (resultado) {
            is ResultadoDelBot.Correcto -> {
                // Enseñar de qué bot se trata es lo que hace la respuesta comprobable: quien
                // la pidió puede reconocer su bot, o ver que ha pegado el de otro.
                vista.text = getString(
                    R.string.settings_bot_correcto, resultado.nombre, resultado.usuario
                )
                vista.setTextColor(resources.getColor(R.color.status_delivered, null))
            }

            is ResultadoDelBot.Rechazado -> {
                vista.text = getString(R.string.settings_bot_rechazado, resultado.motivo)
                vista.setTextColor(resources.getColor(R.color.status_failed, null))
            }

            is ResultadoDelBot.NoSePudo -> {
                // Sin red no se sabe si el token vale: decir que está mal sería mentir.
                vista.text = getString(R.string.settings_bot_sin_red, resultado.motivo)
                vista.setTextColor(resources.getColor(R.color.status_pending, null))
            }

            ResultadoDelBot.SinToken -> {
                vista.setText(R.string.settings_bot_sin_token)
                vista.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        }
    }

    private fun pintarResultadoDeCuenta(
        vista: android.widget.TextView,
        resultado: ResultadoDeCuenta
    ) {
        when (resultado) {
            is ResultadoDeCuenta.Correcto -> {
                // Enseñar de qué número se trata es lo que hace la respuesta comprobable: quien
                // la pidió puede reconocer su número, o ver que ha pegado el id de otro.
                //
                // La calidad va detrás y en su propia línea porque no es parte de la respuesta
                // a «¿sirven?»: sirven igual con la calidad en amarillo. Es un aviso de que Meta
                // está a punto de limitar el número, y es el único de los riesgos de este
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
                // Sin red no se sabe si las credenciales valen: decir que están mal sería mentir.
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
     * Color del resultado según la calidad que Meta da al número.
     *
     * Un `GREEN` se pinta como un éxito y un `YELLOW` o un `RED` como un aviso, aunque las
     * credenciales sirvan en los tres casos: lo que está en riesgo no es la configuración, es
     * el número. `UNKNOWN` es lo normal en un número recién creado y no dice nada malo.
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
        // El aviso solo se enseña con la casilla encendida: un texto en rojo permanente sobre
        // algo que no está ocurriendo se deja de leer, y entonces tampoco se lee cuando sí.
        binding.switchIncluirCredenciales.setOnCheckedChangeListener { _, marcado ->
            binding.textCredencialesAviso.isVisible = marcado
        }

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
                        is ResultadoCopia.Exportada -> when {
                            // Que el fichero lleva credenciales se dice también al terminar, no
                            // solo en el aviso de antes: entre marcar la casilla y elegir dónde
                            // guardar se pasa por el selector del sistema, y es justo ahí donde
                            // se decide la carpeta que importa.
                            resultado.conCredenciales -> getString(
                                R.string.settings_copia_exportada_credenciales, resultado.reglas
                            )
                            else -> getString(
                                R.string.settings_copia_exportada, resultado.reglas
                            )
                        }

                        is ResultadoCopia.Importada -> when {
                            resultado.rechazadas > 0 -> getString(
                                R.string.settings_copia_importada_con_rechazos,
                                resultado.origen, resultado.aceptadas, resultado.rechazadas
                            )
                            resultado.conCredenciales -> getString(
                                R.string.settings_copia_importada_credenciales,
                                resultado.origen, resultado.aceptadas
                            )
                            else -> getString(
                                R.string.settings_copia_importada,
                                resultado.origen, resultado.aceptadas
                            )
                        }

                        is ResultadoCopia.Fallo ->
                            getString(R.string.settings_copia_fallo, resultado.motivo)
                    }
                    Snackbar.make(binding.root, mensaje, Snackbar.LENGTH_LONG).show()

                    // Que un canal importado no pueda entregar no cabe en un Snackbar que se va
                    // solo: es lo que hay que arreglar a continuación, y se queda en pantalla.
                    if (resultado is ResultadoCopia.Importada && resultado.avisos.isNotEmpty()) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.settings_copia_avisos_titulo)
                            .setMessage(
                                getString(
                                    R.string.settings_copia_avisos,
                                    resultado.origen,
                                    resultado.avisos.joinToString("\n") { "• $it" }
                                )
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }

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
                    if (config != null) configCargada = config
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
        binding.editTextTokenBot.setText(config.tokenBot)
        binding.switchProtegerBucles.isChecked = config.protegerBucles
        binding.switchCertificados.isChecked = config.aceptarCertificadosInvalidos
        simElegida = config.subscriptionId
        pintarSimElegida()
        binding.editTextPlantillaNombre.setText(config.plantillaNombre)
        binding.editTextPlantillaIdioma.setText(config.plantillaIdioma)
        binding.radioGroupModo.check(
            when (config.modoDeEntrega) {
                ModoDeEntrega.AUTOMATICO -> R.id.radio_modo_automatico
                ModoDeEntrega.SOLO_TEXTO_LIBRE -> R.id.radio_modo_texto_libre
                ModoDeEntrega.SOLO_PLANTILLA -> R.id.radio_modo_plantilla
            }
        )
        binding.switchRecepcion.isChecked = config.recepcionActiva
        binding.editTextMaxReenviosMinuto.setText(config.maxReenviosPorMinuto.toString())
        binding.editTextMaxMensajesMes.setText(config.maxMensajesPorMes.toString())
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
            val tokenBot = TokenDeBot.normalizar(
                binding.editTextTokenBot.text?.toString().orEmpty()
            )
            val plantillaNombre =
                binding.editTextPlantillaNombre.text?.toString()?.trim().orEmpty()
            val plantillaIdioma =
                binding.editTextPlantillaIdioma.text?.toString()?.trim().orEmpty()
            val modo = modoElegido()
            val maxPorMinuto = binding.editTextMaxReenviosMinuto.entero(VALORES.maxReenviosPorMinuto)
            val maxPorMes = binding.editTextMaxMensajesMes.entero(VALORES.maxMensajesPorMes)
            val timeoutEnvio = binding.editTextTimeoutEnvio.entero(VALORES.timeoutEnvioSegundos)
            val maxReintentos = binding.editTextMaxReintentos.entero(VALORES.maxReintentos)
            val intervalo = binding.editTextIntervalo.entero(VALORES.intervaloReintentoSegundos)

            if (!validar(
                    token, idNumero, tokenBot, modo, plantillaNombre, plantillaIdioma,
                    maxPorMinuto, maxPorMes, timeoutEnvio, maxReintentos, intervalo
                )
            ) {
                return@setOnClickListener
            }

            viewModel.guardar(
                (configCargada ?: AppConfig()).copy(
                    tokenAcceso = token,
                    idNumeroTelefono = idNumero,
                    tokenBot = tokenBot,
                    subscriptionId = simElegida,
                    protegerBucles = binding.switchProtegerBucles.isChecked,
                    aceptarCertificadosInvalidos = binding.switchCertificados.isChecked,
                    modoDeEntrega = modo,
                    plantillaNombre = plantillaNombre,
                    plantillaIdioma = plantillaIdioma,
                    maxReintentos = maxReintentos,
                    intervaloReintentoSegundos = intervalo,
                    timeoutEnvioSegundos = timeoutEnvio,
                    maxReenviosPorMinuto = maxPorMinuto,
                    maxMensajesPorMes = maxPorMes,
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

    /** El modo marcado en los tres botones. */
    private fun modoElegido(): ModoDeEntrega = when (binding.radioGroupModo.checkedRadioButtonId) {
        R.id.radio_modo_texto_libre -> ModoDeEntrega.SOLO_TEXTO_LIBRE
        R.id.radio_modo_plantilla -> ModoDeEntrega.SOLO_PLANTILLA
        else -> ModoDeEntrega.AUTOMATICO
    }

    private fun validar(
        token: String,
        idNumero: String,
        tokenBot: String,
        modo: ModoDeEntrega,
        plantillaNombre: String,
        plantillaIdioma: String,
        maxPorMinuto: Int,
        maxPorMes: Int,
        timeoutEnvio: Int,
        maxReintentos: Int,
        intervalo: Int
    ): Boolean {
        var valido = true
        // Los campos de un canal apagado están escondidos, y un error en un campo que no se ve
        // dejaría «Guardar» sin hacer nada y sin explicación. Se guardan tal como estaban.
        val whatsapp = configCargada?.estaActivo(Canal.WHATSAPP) != false
        val telegram = configCargada?.estaActivo(Canal.TELEGRAM) != false

        // Solo la forma. Si las credenciales sirven de verdad únicamente lo sabe Meta, y eso es
        // lo que comprueba el botón de comprobación; esto atrapa el fallo más común, que es
        // pegar algo que no es una credencial.
        if (!whatsapp || TokenDeAcceso.pareceValido(token)) {
            binding.tilTokenAcceso.error = null
        } else {
            binding.tilTokenAcceso.error = getString(R.string.settings_token_acceso_invalido)
            valido = false
        }

        // El id tiene un error propio para el caso más probable de todos: pegar el número de
        // teléfono, que la consola de Meta enseña justo al lado. Decir «no tiene forma de id»
        // sería verdad y no resolvería nada.
        if (!whatsapp || IdNumeroTelefono.pareceValido(idNumero)) {
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

        // Lo mismo para el bot: solo la forma, que atrapa pegar media línea del mensaje de
        // @BotFather. Si sirve lo dice el botón de comprobación.
        if (!telegram || TokenDeBot.pareceValido(tokenBot)) {
            binding.tilTokenBot.error = null
        } else {
            binding.tilTokenBot.error = getString(R.string.settings_token_bot_invalido)
            valido = false
        }

        // La plantilla puede no estar configurada: eso significa «solo entrego dentro de la
        // ventana de 24 h», que es un estado legítimo. Lo que no es legítimo es pedir el modo
        // «solo plantilla» sin tenerla, porque entonces no se entrega nada nunca.
        if (!whatsapp || plantillaNombre.isEmpty() || NOMBRE_PLANTILLA.matches(plantillaNombre)) {
            binding.tilPlantillaNombre.error = null
        } else {
            binding.tilPlantillaNombre.error =
                getString(R.string.settings_plantilla_nombre_invalido)
            valido = false
        }

        if (whatsapp && modo == ModoDeEntrega.SOLO_PLANTILLA && plantillaNombre.isEmpty()) {
            binding.tilPlantillaNombre.error = getString(R.string.settings_plantilla_falta)
            valido = false
        }

        if (!whatsapp || IDIOMA_PLANTILLA.matches(plantillaIdioma)) {
            binding.tilPlantillaIdioma.error = null
        } else {
            binding.tilPlantillaIdioma.error =
                getString(R.string.settings_plantilla_idioma_invalido)
            valido = false
        }

        valido = comprobarRango(
            binding.tilMaxReenviosMinuto, maxPorMinuto, 1, 100,
            getString(R.string.settings_error_rango, 1, 100)
        ) && valido

        // El cero es legitimo y significa «sin tope», asi que el rango empieza en 0 y no en 1.
        valido = comprobarRango(
            binding.tilMaxMensajesMes, maxPorMes, 0, 100_000,
            getString(R.string.settings_error_rango, 0, 100_000)
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

        /**
         * Nombre de plantilla tal como lo admite Meta: minúsculas, dígitos y guiones bajos.
         *
         * Lo valida la propia consola al darla de alta, así que aquí solo sirve para atrapar un
         * nombre copiado con mayúsculas o con un espacio, que daría un `132001` al entregar.
         */
        val NOMBRE_PLANTILLA = Regex("""^[a-z0-9_]{1,512}$""")

        /** Un código de idioma: `es`, `es_ES`, `pt_BR`. */
        val IDIOMA_PLANTILLA = Regex("""^[a-z]{2}(_[A-Z]{2})?$""")
    }
}
