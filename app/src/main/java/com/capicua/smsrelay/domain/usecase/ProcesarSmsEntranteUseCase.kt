// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.usecase

import com.capicua.smsrelay.data.config.ConfigDataStore
import com.capicua.smsrelay.data.repository.LogRepository
import com.capicua.smsrelay.data.repository.ReenvioRepository
import com.capicua.smsrelay.data.repository.ReglaRepository
import com.capicua.smsrelay.data.repository.SmsRepository
import com.capicua.smsrelay.data.whatsapp.ContadorDeMensajes
import com.capicua.smsrelay.domain.model.EstadoSms
import com.capicua.smsrelay.domain.model.LogEntry
import com.capicua.smsrelay.domain.model.LogTipo
import com.capicua.smsrelay.domain.model.Reenvio
import com.capicua.smsrelay.domain.model.SmsMessage
import com.capicua.smsrelay.domain.rules.NormalizadorTelefono
import com.capicua.smsrelay.domain.transport.Canal
import com.capicua.smsrelay.domain.rules.EvaluadorDeReglas
import com.capicua.smsrelay.util.toDisplayString
import com.capicua.smsrelay.worker.ColaDeEnvios
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/**
 * Tramita un SMS entrante: lo evalúa contra las reglas, crea sus reenvíos y los encola.
 *
 * Es la única entrada de mensajes al sistema. Sustituye al `SaveSmsUseCase` de la versión HTTP,
 * que solo guardaba y encolaba porque el destino era siempre el mismo.
 *
 * ## Canal y destino de un reenvío
 * Los dos se copian de la regla que casó. El canal decide qué transporte lo entrega y si la cola
 * espera a tener red; el destino se guarda tal como lo escribió la regla, y **aquí no se resuelve
 * ningún marcador**: el cuerpo de un SMS de un tercero no puede decidir a dónde se manda nada.
 * Ver el KDoc de `Regla.destino`.
 *
 * ## Orden de las operaciones
 * ```
 * 1. INSERT del SMS en estado PENDIENTE        ← outbox: nada se pierde a partir de aquí
 * 2. Topes de frecuencia y de coste            ← si salta alguno: DESCARTADO y fin
 * 3. Evaluar reglas
 * 4. INSERT de los reenvíos (lote atómico)
 * 5. Marcar el SMS como PROCESADO o SIN_REGLA
 * 6. Encolar un worker por reenvío
 * ```
 *
 * Si el proceso muere a mitad, el rescate de huérfanos retoma el trabajo. Para que eso no
 * genere envíos duplicados, [procesar] es **idempotente**: antes de crear reenvíos comprueba si
 * el SMS ya los tiene, y en ese caso se limita a reencolar los que quedan. Es la misma defensa
 * que hace el worker al comprobar el estado antes de cada intento.
 */
class ProcesarSmsEntranteUseCase @Inject constructor(
    private val smsRepository: SmsRepository,
    private val reglaRepository: ReglaRepository,
    private val reenvioRepository: ReenvioRepository,
    private val logRepository: LogRepository,
    private val evaluador: EvaluadorDeReglas,
    private val colaDeEnvios: ColaDeEnvios,
    private val configDataStore: ConfigDataStore,
    private val contadorDeMensajes: ContadorDeMensajes
) {

    /** Persiste el SMS y lo tramita. Para mensajes recién recibidos. */
    suspend operator fun invoke(sms: SmsMessage) {
        smsRepository.guardar(sms)
        logRepository.insertar(
            LogEntry(
                tipo = LogTipo.SMS_RECIBIDO,
                smsId = sms.id,
                detalle = "SMS recibido de ${sms.telefono} (${sms.mensaje.length} caracteres)",
                timestamp = Instant.now()
            )
        )
        procesar(sms)
    }

    /**
     * Tramita un SMS ya persistido. Lo llama tanto [invoke] como el rescate de huérfanos para
     * los mensajes que se quedaron en PENDIENTE.
     */
    suspend fun procesar(sms: SmsMessage) {
        // ── Idempotencia: ¿ya se tramitó este SMS? ────────────────────────────
        val existentes = reenvioRepository.obtenerPorSms(sms.id)
        if (existentes.isNotEmpty()) {
            Timber.d("Procesar[${sms.id}]: ya tiene ${existentes.size} reenvío(s), solo reencolo")
            smsRepository.actualizarEstado(sms.id, EstadoSms.PROCESADO)
            colaDeEnvios.encolarVarios(existentes)
            return
        }

        val config = configDataStore.config.first()

        // ── Protección: el remitente es el destino de una regla SMS ───────────
        //
        // Si contesta un número al que reenviamos por SMS —u otro teléfono pasarela que nos
        // reenvía—, ese SMS entra por el receptor como cualquier otro. Reenviarlo otra vez
        // cierra el círculo y, con los reintentos detrás, convierte un error de configuración
        // en una factura. Solo se miran los destinos del canal SMS: un chat de Telegram, un
        // número de WhatsApp o una URL no pueden volver nunca como SMS.
        if (config.protegerBucles) {
            val destinos = reglaRepository.obtenerDestinosSmsActivos()
            if (NormalizadorTelefono.estaEnLista(sms.telefono, destinos)) {
                descartar(
                    sms,
                    "el remitente ${sms.telefono} es el destino de una regla SMS: posible bucle " +
                        "de reenvío"
                )
                return
            }
        }

        // ── Protección: límite de reenvíos por minuto ─────────────────────────
        //
        // Corta una avalancha: una ráfaga de SMS en un minuto.
        val desde = Instant.now().minus(1, ChronoUnit.MINUTES)
        val recientes = reenvioRepository.contarCreadosDesde(desde)
        if (recientes >= config.maxReenviosPorMinuto) {
            descartar(
                sms,
                "límite de ${config.maxReenviosPorMinuto} reenvíos por minuto alcanzado " +
                    "($recientes en los últimos 60 s)"
            )
            return
        }

        // ── Protección: tope de mensajes del mes ──────────────────────────────
        //
        // Es otra protección y no una variante de la anterior, porque mide otra cosa: aquella
        // corta una ráfaga, esta corta un mes. Y es la primera de este proyecto que mide
        // **dinero**: entregar por la Cloud API cuesta pasados los mil mensajes de servicio
        // gratuitos, y pasarse no da ningún error, da una factura.
        //
        // Se comprueba aquí, al crear el reenvío, y no al entregarlo, por dos razones. Una es
        // que así usa el mismo mecanismo que el tope por minuto —descartar el SMS y dejarlo en
        // el registro— en lugar de inventar una tercera forma de fallar. La otra es que un
        // reenvío cerrado como fallido al intentar entregarlo habría consumido ya su sitio en
        // la cola y sus reintentos para nada.
        //
        // Un tope de 0 lo desactiva, para quien prefiera no tener techo.
        if (config.maxMensajesPorMes > 0) {
            val mes = contadorDeMensajes.leerMesEnCurso()
            if (mes.total >= config.maxMensajesPorMes) {
                descartar(
                    sms,
                    "tope de ${config.maxMensajesPorMes} mensajes del mes alcanzado " +
                        "(${mes.servicio} de servicio y ${mes.plantilla} de plantilla " +
                        "entregados en ${mes.mes})"
                )
                return
            }
        }

        // ── Evaluación ────────────────────────────────────────────────────────
        val reglas = reglaRepository.obtenerActivas()
        val resultado = evaluador.evaluar(
            telefono = sms.telefono,
            mensaje = sms.mensaje,
            fecha = sms.fechaRecepcion.toDisplayString(),
            reglas = reglas,
            fechaIso = sms.fechaRecepcion.toString()
        )

        // Las reglas con una expresión regular rota no paran la evaluación, pero el operador
        // tiene que poder enterarse: de lo contrario la regla «no funciona» sin explicación.
        resultado.reglasInvalidas.forEach { invalida ->
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.ERROR,
                    smsId = sms.id,
                    detalle = "Regla «${invalida.nombreRegla}» descartada: " +
                        "la expresión de ${invalida.campo.name.lowercase()} " +
                        "«${invalida.patron}» no es válida (${invalida.motivo})",
                    timestamp = Instant.now()
                )
            )
        }

        if (!resultado.hayCoincidencias) {
            smsRepository.actualizarEstado(sms.id, EstadoSms.SIN_REGLA)
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.SIN_REGLA,
                    smsId = sms.id,
                    detalle = "Ninguna de las ${reglas.size} regla(s) activa(s) casó con el mensaje",
                    timestamp = Instant.now()
                )
            )
            Timber.i("Procesar[${sms.id}]: sin regla que case, nada que reenviar")
            return
        }

        // ── Protección: una regla SMS que reenviaría al propio remitente ──────
        //
        // Solo el canal SMS. En los otros tres la comparación no significa nada —un chat y un
        // teléfono son identidades sin relación, y un WhatsApp no vuelve como SMS: lo único
        // parecido lo vigila Meta sola con el error 131021—, y es el razonamiento de
        // MASTERPLAN-TELEGRAM y MASTERPLAN-WHATSAPP, que no se deshace.
        //
        // Una regla con `{telefono}` en el destino pide explícitamente contestar a quien
        // escribió, así que queda fuera: lo que se bloquea es la circularidad accidental, no la
        // configurada a propósito.
        val (validas, circulares) = if (config.protegerBucles) {
            resultado.coincidencias.partition { coincidencia ->
                coincidencia.canal != Canal.SMS ||
                    coincidencia.respondeAlRemitente ||
                    !NormalizadorTelefono.mismoNumero(coincidencia.destino, sms.telefono)
            }
        } else {
            resultado.coincidencias to emptyList()
        }

        circulares.forEach { coincidencia ->
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.LIMITE_ALCANZADO,
                    smsId = sms.id,
                    destino = coincidencia.destino,
                    canal = coincidencia.canal,
                    detalle = "Regla «${coincidencia.nombreRegla}» reenviaría por SMS al propio " +
                        "remitente ${sms.telefono}: omitida. Si la intención era contestarle, " +
                        "pon {telefono} como destino de la regla",
                    timestamp = Instant.now()
                )
            )
        }

        // ── Protección: contestar a quien no puede recibir un SMS ─────────────
        //
        // Solo afecta a las reglas que contestan al remitente. Una cabecera alfanumérica
        // (BANCO, AMAZON) no recibe SMS, así que el envío fallaría siempre y con reintentos
        // detrás: mejor decirlo aquí.
        val (enviables, inalcanzables) = validas.partition { coincidencia ->
            !coincidencia.respondeAlRemitente ||
                NormalizadorTelefono.esDestinoEnviable(coincidencia.destino)
        }

        inalcanzables.forEach { coincidencia ->
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.ERROR,
                    smsId = sms.id,
                    destino = coincidencia.destino,
                    canal = coincidencia.canal,
                    detalle = "Regla «${coincidencia.nombreRegla}» contesta al remitente, pero " +
                        "«${sms.telefono}» no es un número al que se pueda enviar un SMS: omitida",
                    timestamp = Instant.now()
                )
            )
        }

        if (enviables.isEmpty()) {
            descartar(
                sms,
                if (circulares.isNotEmpty()) {
                    "todas las reglas que casaron reenviaban por SMS al propio remitente"
                } else {
                    "las reglas que casaron contestaban a «${sms.telefono}», " +
                        "que no es un número al que se pueda enviar"
                }
            )
            return
        }

        // ── Creación de los reenvíos ──────────────────────────────────────────
        val ahora = Instant.now()
        val reenvios = enviables.map { coincidencia ->
            Reenvio(
                id = UUID.randomUUID().toString(),
                smsId = sms.id,
                reglaId = coincidencia.reglaId,
                nombreRegla = coincidencia.nombreRegla,
                canal = coincidencia.canal,
                destino = coincidencia.destino,
                textoFinal = coincidencia.textoFinal,
                fechaCreacion = ahora
            )
        }

        reenvioRepository.guardarVarios(reenvios)
        smsRepository.actualizarEstado(sms.id, EstadoSms.PROCESADO)

        reenvios.forEach { reenvio ->
            logRepository.insertar(
                LogEntry(
                    tipo = LogTipo.REGLA_APLICADA,
                    smsId = sms.id,
                    reenvioId = reenvio.id,
                    destino = reenvio.destino,
                    canal = reenvio.canal,
                    detalle = "Regla «${reenvio.nombreRegla}» → ${reenvio.canal.etiqueta} " +
                        reenvio.destino,
                    timestamp = Instant.now()
                )
            )
        }

        colaDeEnvios.encolarVarios(reenvios)

        Timber.i("Procesar[${sms.id}]: ${reenvios.size} reenvío(s) encolado(s)")
    }

    private suspend fun descartar(sms: SmsMessage, motivo: String) {
        smsRepository.marcarDescartado(sms.id, motivo)
        logRepository.insertar(
            LogEntry(
                tipo = LogTipo.LIMITE_ALCANZADO,
                smsId = sms.id,
                detalle = "SMS descartado: $motivo",
                timestamp = Instant.now()
            )
        )
        Timber.w("Procesar[${sms.id}]: descartado — $motivo")
    }
}
