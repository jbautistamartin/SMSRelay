// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.capicua.smstotelegram.data.config.ConfigDataStore
import com.capicua.smstotelegram.data.repository.LogRepository
import com.capicua.smstotelegram.data.repository.ReenvioRepository
import com.capicua.smstotelegram.domain.model.EstadoReenvio
import com.capicua.smstotelegram.domain.model.LogEntry
import com.capicua.smstotelegram.domain.model.LogTipo
import com.capicua.smstotelegram.domain.transport.EnviadorDeMensajes
import com.capicua.smstotelegram.domain.transport.ResultadoEnvio
import com.capicua.smstotelegram.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant

/**
 * Worker que entrega un único [com.capicua.smstotelegram.domain.model.Reenvio] a su destino.
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
                Result.retry()
            }
        }
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
}
