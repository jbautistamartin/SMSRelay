// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.capicua.smstowhats.data.config.ConfigDataStore
import com.capicua.smstowhats.util.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Único punto por el que un reenvío entra en la cola de WorkManager.
 *
 * Centralizarlo aquí resuelve un problema que arrastraba la versión HTTP: el intervalo de
 * reintento era editable en la pantalla de configuración pero nunca se usaba, porque el
 * encolado leía una constante. Ahora el backoff sale de [ConfigDataStore], que es lo que el
 * operador cree estar ajustando.
 *
 * ## Por qué vuelve a haber restricción de red
 * Las tres versiones de este proyecto han respondido distinto a esta pregunta, y conviene saber
 * por qué para no «arreglar» lo que no está roto:
 *
 * - **SMSGateway** (HTTP a un servidor propio) no la ponía a propósito: Android no marca como
 *   «con internet» una red local sin salida, así que la restricción habría bloqueado justo el
 *   caso que esa versión servía.
 * - **SMStoSMS** no la necesitaba: un SMS sale por la radio del operador, no por datos, y la
 *   falta de cobertura ya la reportaba el propio envío como `RESULT_ERROR_NO_SERVICE`.
 * - **SMStoWhats** entrega por internet y sí la pone. Sin ella, cada reenvío encolado en un
 *   túnel se gastaría sus diez intentos contra una red que no está, y el reenvío acabaría
 *   `FALLIDO` por agotamiento en lugar de esperar a que vuelva la conexión.
 *
 * `NetworkType.CONNECTED` es el nivel correcto, no `UNMETERED`: un mensaje de texto por datos
 * móviles no es un coste que haya que ahorrar, y esperar al wifi sería dejar de reenviar.
 */
@Singleton
class ColaDeEnvios @Inject constructor(
    private val workManager: WorkManager,
    private val configDataStore: ConfigDataStore
) {

    /** Nombre único del trabajo asociado a un reenvío. */
    fun nombreUnico(reenvioId: String): String =
        "${Constants.WORKER_DISPATCH_TAG}_$reenvioId"

    /**
     * Encola el envío de un reenvío.
     *
     * Usa [ExistingWorkPolicy.KEEP]: si ya hay un trabajo para este reenvío —por ejemplo un
     * reintento en curso— no se duplica. Es la primera línea de defensa contra enviar dos
     * veces el mismo mensaje.
     */
    suspend fun encolar(reenvioId: String) {
        val config = configDataStore.config.first()

        val solicitud = OneTimeWorkRequestBuilder<DispatchWorker>()
            .setInputData(workDataOf(Constants.WORKER_KEY_REENVIO_ID to reenvioId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                config.intervaloReintentoSegundos.toLong(),
                TimeUnit.SECONDS
            )
            .addTag(Constants.WORKER_DISPATCH_TAG)
            .build()

        workManager.enqueueUniqueWork(nombreUnico(reenvioId), ExistingWorkPolicy.KEEP, solicitud)
    }

    /** Encola varios reenvíos, normalmente los derivados de un mismo SMS. */
    suspend fun encolarVarios(reenvioIds: List<String>) {
        reenvioIds.forEach { encolar(it) }
    }

    /**
     * true si el reenvío tiene un trabajo vivo en WorkManager.
     *
     * Lo consulta el rescate de huérfanos para no encolar dos veces lo que ya está en marcha.
     */
    suspend fun tieneTrabajoActivo(reenvioId: String): Boolean {
        val infos = workManager.getWorkInfosForUniqueWork(nombreUnico(reenvioId)).await()
        return infos.any { info ->
            info.state in listOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED
            )
        }
    }
}
