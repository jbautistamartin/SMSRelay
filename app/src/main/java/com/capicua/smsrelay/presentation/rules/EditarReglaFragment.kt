// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.presentation.rules

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.capicua.smsrelay.R
import com.capicua.smsrelay.data.telegram.ChatDescubierto
import com.capicua.smsrelay.data.telegram.ResultadoDescubrimiento
import com.capicua.smsrelay.databinding.FragmentEditarReglaBinding
import com.capicua.smsrelay.domain.model.Regla
import com.capicua.smsrelay.domain.rules.EvaluadorDeReglas
import com.capicua.smsrelay.domain.transport.Canal
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Alta y edición de una regla.
 *
 * El **canal** va antes que el destino porque decide qué es un destino válido: al cambiarlo
 * cambian la ayuda, el teclado, la validación y el límite de caracteres de la previsualización.
 *
 * Las expresiones regulares se validan **mientras se teclean**: una regla con un patrón roto se
 * descarta en silencio durante la evaluación, así que el único momento razonable para avisar es
 * aquí. La previsualización de la plantilla cumple la misma función con el texto de salida, e
 * incluye la longitud porque el transporte tiene un límite y pasarse de él es un fallo
 * permanente: el reenvío no se reintenta, se descarta.
 */
@AndroidEntryPoint
class EditarReglaFragment : Fragment() {

    private var _binding: FragmentEditarReglaBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EditarReglaViewModel by viewModels()
    private val args: EditarReglaFragmentArgs by navArgs()

    private var cargaInicial = true

    /** Para pedir el permiso de enviar SMS una vez por visita, no en cada cambio de canal. */
    private var permisoSmsPedido = false

    /**
     * El permiso de enviar SMS se pide aquí, al elegir el canal SMS, y no al arrancar la
     * aplicación: quien solo entrega por Telegram o WhatsApp no tiene por qué ver ese diálogo.
     */
    private val pedirPermisoEnviar = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (!concedido) {
            Snackbar.make(binding.root, R.string.regla_sms_permiso, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditarReglaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonVolver.setOnClickListener { findNavController().navigateUp() }

        val esNueva = args.reglaId == EditarReglaViewModel.NUEVA
        binding.textViewTitulo.setText(
            if (esNueva) R.string.regla_titulo_nueva else R.string.regla_titulo_editar
        )

        if (esNueva) {
            // Una regla nueva arranca con la plantilla por defecto, activa y sin distinguir
            // mayúsculas. Lo de las mayúsculas es lo que casi nadie quiere: un SMS escribe
            // «CODIGO», «Codigo» o «codigo» según el operador y el día, y una regla que
            // distinga deja de casar sin que nada lo avise. Quien necesite distinguirlas lo
            // apaga aquí, que es un interruptor a la vista; lo contrario era descubrirlo
            // cuando una regla no disparaba.
            binding.editTextPlantilla.setText(Regla.PLANTILLA_POR_DEFECTO)
            binding.switchActiva.isChecked = true
            binding.switchIgnorarMayusculas.isChecked = true
            cargaInicial = false
        }

        viewModel.cargar(args.reglaId)

        configurarSelectorDeCanal()
        configurarDescubrimientoDeChats()
        configurarAtajoRemitente()
        configurarValidacionEnVivo()
        configurarBotonGuardar()
        observarRegla()
        observarCanal()
        observarCanalesDesactivados()
        observarDescubrimiento()
        observarErrores()
        observarGuardado()

        actualizarPrevisualizacion()
    }

    // ── Canal ─────────────────────────────────────────────────────────────────

    /**
     * Habilita los chips de los canales que esta compilación entrega y avisa al ViewModel de cada
     * cambio. Los demás quedan a la vista pero deshabilitados: es más honrado que esconderlos, y
     * una regla importada con uno de esos canales se sigue pudiendo abrir y leer.
     *
     * Un canal **apagado en Ajustes** sí se esconde, porque eso lo decidió quien opera; ver
     * [observarCanalesDesactivados].
     */
    private fun configurarSelectorDeCanal() {
        val disponibles = viewModel.canalesDisponibles
        PresentacionDeCanal.entries.forEach { presentacion ->
            binding.chipGroupCanal.findViewById<View>(presentacion.chip).isEnabled =
                presentacion.canal in disponibles
        }
        binding.chipGroupCanal.setOnCheckedStateChangeListener { _, ids ->
            ids.firstOrNull()
                ?.let { PresentacionDeCanal.deChip(it) }
                ?.let { viewModel.elegirCanal(it.canal) }
        }
    }

    /**
     * Esconde los chips de los canales apagados en Ajustes, menos el del canal con el que se
     * guardó la regla: una regla de Telegram con Telegram apagado se tiene que poder abrir, leer
     * y pasar a otro canal.
     */
    private fun observarCanalesDesactivados() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Con la regla también: su canal original llega después que los ajustes.
                combine(viewModel.canalesDesactivados, viewModel.regla) { desactivados, _ ->
                    desactivados
                }.collect { desactivados ->
                    PresentacionDeCanal.entries.forEach { presentacion ->
                        binding.chipGroupCanal.findViewById<View>(presentacion.chip).visibility =
                            if (presentacion.canal !in desactivados ||
                                presentacion.canal == viewModel.canalOriginal
                            ) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    /** Repinta lo que depende del canal: el chip, la explicación, el destino y la previsualización. */
    private fun observarCanal() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.canal.collect { canal ->
                        val presentacion = PresentacionDeCanal.de(canal)
                        if (binding.chipGroupCanal.checkedChipId != presentacion.chip) {
                            binding.chipGroupCanal.check(presentacion.chip)
                        }
                        binding.textViewCanalAyuda.text = getString(
                            when {
                                canal !in viewModel.canalesDisponibles -> R.string.canal_no_disponible
                                canal in viewModel.canalesDesactivados.value -> R.string.canal_desactivado
                                else -> presentacion.ayudaCanal
                            }
                        )
                        binding.tilDestino.hint = getString(presentacion.etiquetaDestino)
                        binding.tilDestino.helperText = getString(presentacion.ayudaDestino)
                        binding.tilDestino.error = null
                        binding.editTextDestino.inputType = presentacion.tipoTeclado
                        binding.buttonDescubrirChats.visibility =
                            if (canal == Canal.TELEGRAM) View.VISIBLE else View.GONE
                        binding.buttonDestinoRemitente.visibility =
                            if (canal == Canal.SMS) View.VISIBLE else View.GONE
                        if (canal == Canal.SMS) pedirPermisoSmsSiFalta()
                        cambiarPlantillaPorDefecto(canal)
                    }
                }
                launch {
                    viewModel.limiteCaracteres.collect { actualizarPrevisualizacion() }
                }
            }
        }
    }

    /**
     * Pone la plantilla por defecto del canal, pero solo si la que hay es la por defecto de otro.
     *
     * El canal API arranca con `{mensaje}` a secas, porque el remitente y la fecha tienen sus
     * huecos en la URL; los demás, con remitente, fecha y cuerpo. Una plantilla que alguien ha
     * escrito no se toca nunca: cambiar de canal no debe borrar trabajo. Y solo en una regla
     * nueva: en una existente, `{mensaje}` a secas puede ser lo que alguien eligió.
     */
    private fun cambiarPlantillaPorDefecto(canal: Canal) {
        if (args.reglaId != EditarReglaViewModel.NUEVA) return
        val actual = binding.editTextPlantilla.text?.toString().orEmpty()
        val porDefecto = Regla.plantillaPorDefecto(canal)
        val esDeOtroCanal = actual != porDefecto &&
            (actual == Regla.PLANTILLA_POR_DEFECTO || actual == Regla.PLANTILLA_POR_DEFECTO_API)
        if (esDeOtroCanal) binding.editTextPlantilla.setText(porDefecto)
    }

    // ── Canal SMS ─────────────────────────────────────────────────────────────

    /**
     * Rellena el destino con el marcador que contesta al remitente.
     *
     * El marcador se podría teclear a mano, pero solo si se sabe que existe; el botón es lo que
     * convierte «devolver el SMS a quien lo mandó» en algo que se encuentra sin leer la
     * documentación. Solo se ve con el canal SMS, el único en que `{telefono}` es un destino.
     */
    private fun configurarAtajoRemitente() {
        binding.buttonDestinoRemitente.setOnClickListener {
            binding.editTextDestino.setText(EvaluadorDeReglas.MARCADOR_TELEFONO)
            binding.tilDestino.error = null
        }
    }

    private fun pedirPermisoSmsSiFalta() {
        if (permisoSmsPedido) return
        val concedido = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (concedido) return
        permisoSmsPedido = true
        pedirPermisoEnviar.launch(Manifest.permission.SEND_SMS)
    }

    // ── Chats del bot ─────────────────────────────────────────────────────────

    /**
     * Ofrece los chats que han escrito al bot, para elegir el destino de una lista.
     *
     * Nadie se sabe su `chat_id` —Telegram no lo muestra en ninguna pantalla— así que sin esto
     * configurar una regla de Telegram obliga a salir de la aplicación a buscarlo con un bot de
     * terceros. El botón solo se ve con el canal Telegram elegido.
     */
    private fun configurarDescubrimientoDeChats() {
        binding.buttonDescubrirChats.setOnClickListener {
            viewModel.descubrirChats()
        }
    }

    private fun observarDescubrimiento() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.descubrimiento.collect { estado ->
                    when (estado) {
                        EditarReglaViewModel.EstadoDescubrimiento.Inactivo -> {
                            binding.buttonDescubrirChats.isEnabled = true
                            binding.buttonDescubrirChats.setText(R.string.regla_descubrir_chats)
                        }

                        EditarReglaViewModel.EstadoDescubrimiento.Buscando -> {
                            binding.buttonDescubrirChats.isEnabled = false
                            binding.buttonDescubrirChats.setText(R.string.regla_chats_buscando)
                        }

                        is EditarReglaViewModel.EstadoDescubrimiento.Terminado -> {
                            binding.buttonDescubrirChats.isEnabled = true
                            binding.buttonDescubrirChats.setText(R.string.regla_descubrir_chats)
                            atenderDescubrimiento(estado.resultado)
                            viewModel.descubrimientoAtendido()
                        }
                    }
                }
            }
        }
    }

    private fun atenderDescubrimiento(resultado: ResultadoDescubrimiento) {
        when (resultado) {
            is ResultadoDescubrimiento.Encontrados -> elegirChat(resultado.chats)

            // Que no haya ninguno no es un error, y decirlo mal deja a la gente atascada: un
            // bot no puede escribir primero, así que lo que falta es un /start del otro lado.
            ResultadoDescubrimiento.Ninguno -> avisar(R.string.regla_chats_ninguno)

            ResultadoDescubrimiento.SinToken -> avisar(R.string.regla_chats_sin_token)

            is ResultadoDescubrimiento.NoSePudo -> Snackbar.make(
                binding.root,
                getString(R.string.regla_chats_error, resultado.motivo),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun elegirChat(chats: List<ChatDescubierto>) {
        // Se enseña el nombre y el tipo, no el id: el id es lo que se guarda, pero lo que
        // permite reconocer el chat es cómo se llama.
        val etiquetas = chats
            .map { "${it.nombre} · ${it.tipo}" }
            .toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.regla_chats_titulo)
            .setItems(etiquetas) { _, indice ->
                binding.editTextDestino.setText(chats[indice].id)
                binding.tilDestino.error = null
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun avisar(mensaje: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.regla_chats_titulo)
            .setMessage(mensaje)
            .setPositiveButton(R.string.permiso_entendido, null)
            .show()
    }

    // ── Validación en vivo ────────────────────────────────────────────────────

    private fun configurarValidacionEnVivo() {
        binding.editTextRegexTelefono.doAfterTextChanged { texto ->
            binding.tilRegexTelefono.error = viewModel.validarPatron(texto?.toString())
        }
        binding.editTextRegexMensaje.doAfterTextChanged { texto ->
            binding.tilRegexMensaje.error = viewModel.validarPatron(texto?.toString())
        }
        binding.editTextNombre.doAfterTextChanged { binding.tilNombre.error = null }
        binding.editTextDestino.doAfterTextChanged { binding.tilDestino.error = null }
        binding.editTextPlantilla.doAfterTextChanged {
            binding.tilPlantilla.error = null
            actualizarPrevisualizacion()
        }
    }

    /**
     * Pinta cómo quedaría el texto reenviado, usando un SMS de ejemplo fijo.
     *
     * El ejemplo es siempre el mismo a propósito: lo que se quiere ver es el efecto de la
     * plantilla, no el de unos datos cambiantes.
     */
    private fun actualizarPrevisualizacion() {
        val plantilla = binding.editTextPlantilla.text?.toString().orEmpty()
        if (plantilla.isBlank()) {
            binding.textViewPrevisualizacion.text = getString(R.string.regla_previsualizacion_vacia)
            binding.textViewLongitud.text = ""
            return
        }

        val texto = viewModel.previsualizar(
            plantilla = plantilla,
            telefono = EJEMPLO_TELEFONO,
            mensaje = EJEMPLO_MENSAJE,
            fecha = EJEMPLO_FECHA
        )
        binding.textViewPrevisualizacion.text = texto

        val limite = viewModel.limiteCaracteres.value
        binding.textViewLongitud.text =
            getString(R.string.regla_longitud, texto.length, limite)
        binding.textViewLongitud.setTextColor(
            resources.getColor(
                if (texto.length > limite) R.color.status_failed else R.color.status_delivered,
                null
            )
        )
    }

    // ── Observación ───────────────────────────────────────────────────────────

    private fun observarRegla() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.regla.collect { regla ->
                    if (regla != null && cargaInicial) {
                        rellenarCampos(regla)
                        cargaInicial = false
                    }
                }
            }
        }
    }

    private fun observarErrores() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.errores.collect { errores ->
                    binding.tilNombre.error = errores.nombre
                    binding.tilRegexTelefono.error = errores.regexTelefono
                    binding.tilRegexMensaje.error = errores.regexMensaje
                    binding.tilDestino.error = errores.destino
                    binding.tilPlantilla.error = errores.plantilla
                }
            }
        }
    }

    private fun observarGuardado() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.guardada.collect { guardada ->
                    if (guardada) findNavController().navigateUp()
                }
            }
        }
    }

    private fun rellenarCampos(regla: Regla) {
        binding.editTextNombre.setText(regla.nombre)
        binding.editTextRegexTelefono.setText(regla.regexTelefono.orEmpty())
        binding.editTextRegexMensaje.setText(regla.regexMensaje.orEmpty())
        binding.switchIgnorarMayusculas.isChecked = regla.ignorarMayusculas
        binding.editTextDestino.setText(regla.destino)
        binding.editTextPlantilla.setText(regla.plantilla)
        binding.switchActiva.isChecked = regla.activa
        binding.switchContinuar.isChecked = regla.continuar
        actualizarPrevisualizacion()
    }

    // ── Guardado ──────────────────────────────────────────────────────────────

    private fun configurarBotonGuardar() {
        binding.buttonGuardarRegla.setOnClickListener {
            viewModel.guardar(args.reglaId, construirBorrador())
        }
    }

    private fun construirBorrador() = Regla(
        id = args.reglaId,
        // El orden real lo asigna el repositorio al crear, o se conserva al actualizar.
        orden = 0,
        nombre = binding.editTextNombre.text?.toString()?.trim().orEmpty(),
        regexTelefono = binding.editTextRegexTelefono.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() },
        regexMensaje = binding.editTextRegexMensaje.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() },
        ignorarMayusculas = binding.switchIgnorarMayusculas.isChecked,
        canal = viewModel.canal.value,
        destino = binding.editTextDestino.text?.toString()?.trim().orEmpty(),
        plantilla = binding.editTextPlantilla.text?.toString()?.trim().orEmpty(),
        activa = binding.switchActiva.isChecked,
        continuar = binding.switchContinuar.isChecked
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val EJEMPLO_TELEFONO = "+34600112233"
        const val EJEMPLO_MENSAJE = "Tu codigo de verificacion es 4821"
        const val EJEMPLO_FECHA = "07/10/2026 19:30:00"
    }
}
