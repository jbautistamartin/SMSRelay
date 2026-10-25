// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.capicua.smsrelay.data.config.ConfigDataStore
import com.capicua.smsrelay.data.repository.LogRepository
import com.capicua.smsrelay.data.repository.ReenvioRepository
import com.capicua.smsrelay.domain.model.EstadoReenvio
import com.capicua.smsrelay.domain.model.LogEntry
import com.capicua.smsrelay.domain.model.LogTipo
import com.capicua.smsrelay.domain.transport.EnviadorDeMensajes
import com.capicua.smsrelay.domain.transport.ResultadoEnvio
import com.capicua.smsrelay.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant

/**
 * Worker que entrega un único [com.capicua.smsrelay.domain.model.Reenvio] a su destino.
 *
 * Trabaja sobre el id del reenvío, no sobre el del SMS: un mismo mensaje entrante puede tener
 * varios destinos y cada uno se reintenta por separado.
 *
 * **No sabe por qué medio sale.** Depende de [EnviadorDeMensajes] y de las tres respuestas que
 * ese interfaz admite, y eso es todo lo que necesita para decidir. Cuando el transporte era el
 * `SmsManager`, «transitorio» significaba sin cobertura o radio apagada; ahora significa otra
 * cosa y esta clase no se enteró.
 *
 * ## Estrategia de reintentos
 * | Resultado                            | Acción WorkManager | Estado en BD                |
 * |--------------------------------------|--------------------|-----------------------------|
 * | [ResultadoEnvio.Enviado]             | `Result.success()` | ENVIADO, fechaEnvio         |
 * | [ResultadoEnvio.ErrorTransitorio]    | `Result.retry()`   | intentos++, ultimoError     |
 * | [ResultadoEnvio.ErrorPermanente]     | `Result.failure()` | FALLIDO, ultimoError        |
 * | Máximo de reintentos alcanzado       | `Result.failure()` | FALLIDO, ultimoError        |
 *
 * ## Idempotencia
 * Es de cliente, como en la versión HTTP: al principio de cada intento se comprueba el estado
 * en la base de datos y, si ya está [EstadoReenvio.ENVIADO], se devuelve éxito sin tocar el
 * transporte. Un mensaje duplicado lo ve una persona, así que importa.
 */
@HiltWorker
class DispatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reenvioRepository: ReenvioRepository,
    private val logRepository: LogRepository,
    private val enviador: EnviadorDeMensajes,
    private val configDataStore: ConfigDataStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reenvioId = inputData.getString(Constants.WORKER_KEY_REENVIO_ID)
            ?: return Result.failure().also {
                Timber.e("DispatchWorker: sin reenvioId en inputData")
            }

        val config = configDataStore.config.first()

        // ── Obtener el reenvío ────────────────────────────────────────────────
        val reenvio = reenvioRepository.obtenerPorId(reenvioId)
            ?: return Result.failure().also {
                Timber.e("DispatchWorker[$reenvioId]: reenvío no encontrado en BD")
            }

        // ── Idempotencia: ya enviado ──────────────────────────────────────────
        if (reenvio.estado == EstadoReenvio.ENVIADO) {
            Timber.d("DispatchWorker[$reenvioId]: ya enviado, omitiendo")
            return Result.success()
        }

        // ── Fallo permanente previo: no insistir ──────────────────────────────
        if (reenvio.estado == EstadoReenvio.FALLIDO) {
            Timber.d("DispatchWorker[$reenvioId]: marcado como fallido, no se reintenta")
            return Result.failure()
        }

        // ── Guardia: máximo de reintentos ─────────────────────────────────────
        if (runAttemptCount >= config.maxReintentos) {
            val error = "Máximo de ${config.maxReintentos} reintentos alcanzado"
            Timber.w("DispatchWorker[$reenvioId]: $error")
            reenvioRepository.marcarComoFallido(reenvioId, error)
            registrar(LogTipo.ERROR, reenvio.smsId, reenvioId, reenvio.destino, error)
            return Result.failure()
        }

        Timber.d(
            "DispatchWorker[$reenvioId]: intento ${runAttemptCount + 1}/${config.maxReintentos} " +
                "→ ${reenvio.destino}"
        )

        val resultado = enviador.enviar(
            destino = reenvio.destino,
            texto = reenvio.textoFinal,
            timeoutSegundos = config.timeoutEnvioSegundos
        )

        return when (resultado) {
            is ResultadoEnvio.Enviado -> {
                reenvioRepository.marcarComoEnviado(reenvioId, Instant.now())
                registrar(
                    LogTipo.SMS_REENVIADO, reenvio.smsId, reenvioId, reenvio.destino,
                    "Reenviado a ${reenvio.destino}"
                )
                Timber.i("DispatchWorker[$reenvioId]: reenviado a ${reenvio.destino}")
                Result.success()
            }

            is ResultadoEnvio.ErrorPermanente -> {
                val error = "Fallo permanente: ${resultado.motivo}"
                reenvioRepository.marcarComoFallido(reenvioId, error)
                registrar(LogTipo.ERROR, reenvio.smsId, reenvioId, reenvio.destino, error)
                Timber.e("DispatchWorker[$reenvioId]: $error")
                Result.failure()
            }

            is ResultadoEnvio.ErrorTransitorio -> {
                val error = "Fallo transitorio: ${resultado.motivo}"
                reenvioRepository.registrarError(reenvioId, error)
                registrar(LogTipo.ERROR, reenvio.smsId, reenvioId, reenvio.destino, error)
                Timber.w("DispatchWorker[$reenvioId]: $error, reintentando")
                esperarSiLoPiden(resultado.esperaSegundos)
                Result.retry()
            }
        }
    }

    /**
     * Espera antes de devolver `retry()` cuando el transporte ha pedido una espera concreta.
     *
     * No es un adorno: WorkManager cuenta su backoff **desde que el worker termina**, así que
     * dormir aquí es la única forma que tiene esta clase de retrasar el siguiente intento. Sin
     * esto, una espera pedida por el servicio se ignoraría y el reintento llegaría otra vez
     * pronto, se comería otro rechazo y gastaría un intento de los diez.
     *
     * Se escribió para el `retry_after` de Telegram. **La Cloud API no manda ninguna espera**,
     * así que hoy esto no se ejecuta nunca: `esperaSegundos` llega siempre null y la función
     * retorna en la primera línea. Se queda porque el contrato de `ResultadoEnvio` admite la
     * espera, y el día que un medio la pida otra vez el worker ya sabe respetarla.
     *
     * La espera se **limita** a [MAX_ESPERA_SEGUNDOS]: un worker tiene unos diez minutos de
     * ejecución antes de que el sistema lo pare, y bloquear uno durante un cuarto de hora para
     * respetar un `retry_after` enorme cambiaría un problema por otro peor. Si la espera pedida
     * es mayor, se duerme el tope y se deja que el backoff normal haga el resto.
     */
    private suspend fun esperarSiLoPiden(segundos: Int?) {
        if (segundos == null || segundos <= 0) return
        val espera = segundos.coerceAtMost(MAX_ESPERA_SEGUNDOS)
        Timber.d("DispatchWorker: el transporte pidió esperar ${segundos}s, esperando ${espera}s")
        delay(espera * 1000L)
    }

    private suspend fun registrar(
        tipo: LogTipo,
        smsId: String,
        reenvioId: String,
        destino: String,
        detalle: String
    ) {
        logRepository.insertar(
            LogEntry(
                tipo = tipo,
                smsId = smsId,
                reenvioId = reenvioId,
                destino = destino,
                detalle = detalle,
                timestamp = Instant.now()
            )
        )
    }

    private companion object {
        /** Tope de la espera que puede pedir el transporte, en segundos. */
        const val MAX_ESPERA_SEGUNDOS = 120
    }
}
