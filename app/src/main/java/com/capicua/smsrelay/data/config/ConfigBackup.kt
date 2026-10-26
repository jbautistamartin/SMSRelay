// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.config

import android.content.Context
import android.net.Uri
import com.capicua.smsrelay.data.repository.ReglaRepository
import com.capicua.smsrelay.data.rules.CopiaInterpretada
import com.capicua.smsrelay.data.rules.ImportacionDeCopia
import com.capicua.smsrelay.data.rules.ReglasExportadas
import com.capicua.smsrelay.data.rules.aJson
import com.capicua.smsrelay.domain.model.Regla
import com.capicua.smsrelay.domain.rules.EvaluadorDeReglas
import com.capicua.smsrelay.domain.transport.Transportes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Cómo acabó una copia de seguridad. Lo traduce a texto la pantalla que la pidió. */
sealed interface ResultadoCopia {
    /**
     * @param conCredenciales si el fichero escrito lleva dentro credenciales de algún canal.
     *   La pantalla lo usa para cambiar el mensaje de confirmación: una copia con credenciales
     *   es un fichero que hay que custodiar, y eso se dice al terminar y no solo antes.
     */
    data class Exportada(val reglas: Int, val conCredenciales: Boolean) : ResultadoCopia
    /**
     * @param origen aplicación que escribió el fichero: SMSRelay o una de las cuatro anteriores.
     * @param avisos una línea por canal de las reglas importadas que hoy no puede entregar.
     */
    data class Importada(
        val origen: String,
        val aceptadas: Int,
        val rechazadas: Int,
        val conAjustes: Boolean,
        val conCredenciales: Boolean,
        val avisos: List<String> = emptyList()
    ) : ResultadoCopia
    data class Fallo(val motivo: String) : ResultadoCopia
}

/**
 * Copia de seguridad de la configuración completa: los ajustes generales y las reglas.
 *
 * Se escribe y se lee sobre un `Uri` del Storage Access Framework —el fichero lo elige el
 * operador—, así que no hace falta ningún permiso de almacenamiento.
 *
 * **Esto vive en `data/config` y no en la pantalla de reglas a propósito.** Las reglas son
 * configuración: lo que decide a quién se reenvía qué. Que su exportación colgara del menú de
 * la lista de reglas obligaba a hacer dos copias distintas, en dos sitios distintos, para
 * guardar una sola cosa.
 *
 * Al importar, los dos bloques se tratan distinto y no por capricho:
 *
 * - **Los ajustes se sustituyen, pero solo los del canal del fichero** y los comunes. Una copia
 *   de SMStoTelegram no sabe nada de WhatsApp, y no puede dejar a cero lo que no conoce. Ver
 *   `ConfiguracionJson.aplicarSobre`.
 * - **Las reglas se añaden** al final de las que ya hubiera. Importar no borra: una
 *   importación equivocada que vaciara la lista dejaría al teléfono sin reenviar nada y sin
 *   forma de recuperarlo. Si lo que se quiere es sustituirlas, se borran a mano antes.
 */
@Singleton
class ConfigBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configDataStore: ConfigDataStore,
    private val reglaRepository: ReglaRepository,
    private val evaluador: EvaluadorDeReglas,
    private val transportes: Transportes
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Sin esto, kotlinx.serialization omite los campos cuyo valor coincide con el
        // predeterminado — incluida la versión del formato, que es justo lo que el fichero
        // tiene que declarar para que una importación futura sepa qué está leyendo.
        encodeDefaults = true
    }

    /** Nombre sugerido para el fichero. */
    fun nombreFichero(): String = "smsrelay-configuracion-${Instant.now().epochSecond}.json"

    /**
     * Escribe los ajustes y el juego de reglas completo en el fichero elegido.
     *
     * @param incluirCredenciales si las credenciales de WhatsApp y el token del bot viajan
     *   dentro. **Falso por
     *   defecto**, y el valor por defecto importa: quien llame sin pensarlo escribe el fichero
     *   seguro. Activarlo es una decisión consciente de quien exporta, tomada en una casilla
     *   que explica lo que implica.
     */
    suspend fun exportar(
        destino: Uri,
        incluirCredenciales: Boolean = false
    ): ResultadoCopia = try {
        val reglas = reglaRepository.obtenerTodas()
        val contenido = json.encodeToString(
            ReglasExportadas.serializer(),
            ReglasExportadas(
                exportado = Instant.now().toString(),
                configuracion = configDataStore.config.first().aJson(incluirCredenciales),
                reglas = reglas.map { it.aJson() }
            )
        )

        withContext(Dispatchers.IO) {
            // "wt" trunca: si se sobrescribe un fichero mayor no quedan restos del anterior.
            context.contentResolver.openOutputStream(destino, "wt")
                ?.use { it.write(contenido.toByteArray(Charsets.UTF_8)) }
                ?: error("no se pudo abrir el fichero para escribir")
        }

        ResultadoCopia.Exportada(reglas.size, incluirCredenciales)
    } catch (e: Exception) {
        Timber.e(e, "Error exportando la configuración")
        ResultadoCopia.Fallo(e.message ?: "error al exportar")
    }

    /**
     * Lee un fichero de copia —de esta aplicación o de cualquiera de las cuatro anteriores— y
     * aplica lo que traiga.
     *
     * Qué entra y qué no lo decide `ImportacionDeCopia`, que es puro y tiene tests con un fichero
     * de cada aplicación; aquí solo se lee el fichero y se escribe el resultado.
     *
     * Al terminar se mira además si los canales de las reglas que entraron **pueden entregar**:
     * importar la copia de SMStoTelegram en un teléfono sin token deja reglas correctas que no
     * salen por ningún sitio, y eso hay que decirlo en el momento y no cuando llegue el primer
     * SMS.
     */
    suspend fun importar(origen: Uri): ResultadoCopia = try {
        val contenido = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(origen)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("no se pudo abrir el fichero para leer")
        }

        val actual = configDataStore.config.first()
        when (val copia = ImportacionDeCopia.interpretar(contenido, actual, evaluador::validarPatron)) {
            is CopiaInterpretada.Rechazada -> ResultadoCopia.Fallo(copia.motivo)
            is CopiaInterpretada.Correcta -> {
                copia.config?.let { configDataStore.guardar(it) }
                copia.reglas.forEach { regla ->
                    // crear() reasigna el orden al final de la lista; el del fichero solo
                    // mantiene el orden relativo.
                    reglaRepository.crear(regla)
                }

                ResultadoCopia.Importada(
                    origen = copia.origen,
                    aceptadas = copia.reglas.size,
                    rechazadas = copia.rechazadas,
                    conAjustes = copia.config != null,
                    conCredenciales = copia.conCredenciales,
                    avisos = avisosDeCanales(copia.reglas)
                )
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "Error importando la configuración")
        ResultadoCopia.Fallo(e.message ?: "el fichero no tiene el formato esperado")
    }

    /**
     * Una línea por canal de las reglas importadas que hoy no puede entregar, con su motivo.
     *
     * Se pregunta **después** de guardar los ajustes, porque una copia con credenciales es
     * justo lo que deja un canal listo, y una del formato 6 puede encender o apagar canales. Lo
     * normal al importar la copia de una aplicación vieja en un teléfono que tenía su canal
     * apagado es que las reglas entren y no hagan nada, y eso también hay que decirlo.
     */
    private suspend fun avisosDeCanales(reglas: List<Regla>): List<String> {
        val config = configDataStore.config.first()
        return reglas.groupBy { it.canal }.mapNotNull { (canal, deCanal) ->
            val motivo = if (!config.estaActivo(canal)) MOTIVO_CANAL_DESACTIVADO
                else transportes.de(canal)?.motivoNoDisponible()
                ?: if (transportes.de(canal) == null) transportes.motivoSinTransporte(canal) else null
            motivo?.let { "${deCanal.size} regla(s) por ${canal.etiqueta}: $it" }
        }
    }

    private companion object {
        const val MOTIVO_CANAL_DESACTIVADO = "el canal está desactivado en Ajustes"
    }
}
