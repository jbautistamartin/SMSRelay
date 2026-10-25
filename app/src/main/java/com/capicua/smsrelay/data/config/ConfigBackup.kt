// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.config

import android.content.Context
import android.net.Uri
import com.capicua.smsrelay.data.repository.ReglaRepository
import com.capicua.smsrelay.data.rules.ReglaJson
import com.capicua.smsrelay.data.rules.ReglasExportadas
import com.capicua.smsrelay.data.rules.aDominio
import com.capicua.smsrelay.data.rules.aJson
import com.capicua.smsrelay.data.whatsapp.DestinoDeWhatsApp
import com.capicua.smsrelay.domain.rules.EvaluadorDeReglas
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
     * @param conCredenciales si el fichero escrito lleva dentro el token y el id del número.
     *   La pantalla lo usa para cambiar el mensaje de confirmación: una copia con credenciales
     *   es un fichero que hay que custodiar, y eso se dice al terminar y no solo antes.
     */
    data class Exportada(val reglas: Int, val conCredenciales: Boolean) : ResultadoCopia
    data class Importada(
        val aceptadas: Int,
        val rechazadas: Int,
        val conAjustes: Boolean,
        val conCredenciales: Boolean
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
 * - **Los ajustes se sustituyen.** Son un juego de valores único: mezclarlos no significa nada.
 * - **Las reglas se añaden** al final de las que ya hubiera. Importar no borra: una
 *   importación equivocada que vaciara la lista dejaría al teléfono sin reenviar nada y sin
 *   forma de recuperarlo. Si lo que se quiere es sustituirlas, se borran a mano antes.
 */
@Singleton
class ConfigBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configDataStore: ConfigDataStore,
    private val reglaRepository: ReglaRepository,
    private val evaluador: EvaluadorDeReglas
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
     * @param incluirCredenciales si el token y el id del número viajan dentro. **Falso por
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
     * Lee un fichero de copia y aplica lo que traiga.
     *
     * Cada regla se valida antes de escribirla —expresiones que compilen y un destino con forma
     * de teléfono— y las que no pasan se cuentan aparte: una regla con una regex rota o un
     * número sin prefijo que entrase en silencio no reenviaría nunca y sería muy difícil de
     * diagnosticar.
     */
    suspend fun importar(origen: Uri): ResultadoCopia = try {
        val contenido = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(origen)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("no se pudo abrir el fichero para leer")
        }

        val fichero = json.decodeFromString(ReglasExportadas.serializer(), contenido)

        if (fichero.version > ReglasExportadas.VERSION_ACTUAL) {
            ResultadoCopia.Fallo(
                "el fichero usa el formato ${fichero.version} y esta versión " +
                    "entiende hasta el ${ReglasExportadas.VERSION_ACTUAL}"
            )
        } else if (fichero.version < ReglasExportadas.MINIMA_ACEPTADA) {
            // Un fichero de SMStoTelegram tiene la misma forma que uno de aquí, pero sus
            // destinos son chats. Entraría sin un error de parseo y dejaría un juego de reglas
            // que falla cuando llegue un SMS, sin que nada apunte al fichero importado.
            ResultadoCopia.Fallo(
                "el fichero usa el formato ${fichero.version}, de una versión en la que un " +
                    "destino era otra cosa: un chat de Telegram en el 4, un teléfono sin " +
                    "prefijo internacional en el 3 y anteriores. Aquí un destino es un " +
                    "teléfono con prefijo, así que esas reglas no servirían: hay que crearlas " +
                    "de nuevo"
            )
        } else {
            // Los ajustes se reemplazan, así que hay que pasarle las credenciales que ya
            // tenía este teléfono: si el fichero no las trae —el caso normal— son las que se
            // conservan, y sin ellas la importación las borraría. Si el fichero sí las trae,
            // aDominio() se queda con las del fichero.
            val traeCredenciales = fichero.configuracion?.let {
                !it.tokenAcceso.isNullOrBlank() || !it.idNumeroTelefono.isNullOrBlank()
            } ?: false

            fichero.configuracion?.let { importados ->
                val actual = configDataStore.config.first()
                // El token del bot no está todavía en el formato 5, así que se conserva siempre
                // el de este teléfono. El formato 6 lo trae como credencial opcional.
                configDataStore.guardar(
                    importados.aDominio(actual.tokenAcceso, actual.idNumeroTelefono)
                        .copy(tokenBot = actual.tokenBot)
                )
            }

            val (validas, invalidas) = fichero.reglas.partition { esValida(it) }
            validas.forEachIndexed { indice, regla ->
                // crear() reasigna el orden al final de la lista; el índice solo mantiene el
                // orden relativo del fichero.
                reglaRepository.crear(regla.aDominio(orden = indice))
            }

            ResultadoCopia.Importada(
                aceptadas = validas.size,
                rechazadas = invalidas.size,
                conAjustes = fichero.configuracion != null,
                conCredenciales = traeCredenciales
            )
        }
    } catch (e: Exception) {
        Timber.e(e, "Error importando la configuración")
        ResultadoCopia.Fallo(e.message ?: "el fichero no tiene el formato esperado")
    }

    /**
     * Una regla que entra tiene que poder entregar.
     *
     * El destino se valida por **forma**, no solo por no estar vacío, y eso es nuevo de la
     * versión 5. Antes bastaba con que hubiera algo escrito: un `chat_id` no se puede comprobar
     * sin preguntarle a Telegram, así que validar la forma no habría atrapado nada que importara.
     * Un teléfono sí se puede comprobar, y el fallo que atrapa es real: un fichero editado a
     * mano, o uno del formato 5 generado por otra versión, con un número sin prefijo.
     *
     * Es el mismo criterio que ya se aplicaba a las expresiones regulares, por el mismo motivo:
     * una regla que entra en silencio y no entrega nunca es mucho más difícil de diagnosticar
     * que una que se rechaza al importar y se cuenta aparte.
     */
    private fun esValida(regla: ReglaJson): Boolean =
        regla.nombre.isNotBlank() &&
            DestinoDeWhatsApp.pareceValido(regla.destino) &&
            evaluador.validarPatron(regla.regexTelefono) == null &&
            evaluador.validarPatron(regla.regexMensaje) == null
}
