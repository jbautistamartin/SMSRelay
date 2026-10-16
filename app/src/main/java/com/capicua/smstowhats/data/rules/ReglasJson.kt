// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.rules

import com.capicua.smstowhats.data.config.AppConfig
import com.capicua.smstowhats.domain.model.Regla
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Formato de intercambio de la configuración completa, para clonarla entre dispositivos.
 *
 * **Las reglas son parte de la configuración, no una cosa aparte.** El fichero lleva los dos
 * bloques: los ajustes generales y el juego de reglas. Separarlos obligaría a exportar e
 * importar dos ficheros que solo tienen sentido juntos —unos reintentos sin las reglas a las
 * que se aplican no configuran nada— y a acertar con el orden al restaurarlos.
 *
 * El fichero lleva [version] por delante: si algún día cambia la forma de una regla, una
 * importación podrá reconocer el formato antiguo en lugar de fallar con un error de parseo
 * incomprensible.
 *
 * Los ids **no** se exportan a propósito. Un id es local a la base de datos del dispositivo;
 * reutilizarlo al importar provocaría colisiones con reglas existentes. Al importar, cada regla
 * entra como nueva. Lo que sí se conserva es el orden relativo, que es lo que tiene significado.
 */
@Serializable
data class ReglasExportadas(
    @SerialName("version") val version: Int = VERSION_ACTUAL,
    @SerialName("exportado") val exportado: String,
    /**
     * Ajustes generales. Es nulo en los ficheros del formato 1 y 2, que solo llevaban reglas:
     * al importar uno de aquellos se dejan los ajustes del dispositivo como estén.
     */
    @SerialName("configuracion") val configuracion: ConfiguracionJson? = null,
    @SerialName("reglas") val reglas: List<ReglaJson>
) {
    companion object {
        /**
         * Versión del formato.
         *
         * | Versión | Cambio                                                     | App |
         * |---------|------------------------------------------------------------|-----|
         * | 1       | Formato inicial.                                           | SMStoSMS |
         * | 2       | La regla gana `ignorar_mayusculas`.                        | SMStoSMS |
         * | 3       | El fichero gana el bloque `configuracion`.                 | SMStoSMS |
         * | 4       | **`destino` es un chat de Telegram, no un teléfono.**      | SMStoTelegram |
         *
         * Subir la versión al añadir un campo opcional puede parecer excesivo, porque un
         * fichero de la 1 se sigue importando sin problema. Lo que evita es el caso contrario:
         * una versión antigua de la aplicación leyendo un fichero de la 2 descartaría
         * `ignorar_mayusculas` en silencio —`ignoreUnknownKeys` está activado— y la regla
         * entraría distinguiendo mayúsculas sin avisar.
         *
         * ## Por qué la 4 rompe la compatibilidad hacia atrás, y es la primera que lo hace
         * Un fichero del formato 3 tiene **exactamente la misma forma** que uno del 4: ningún
         * campo cambió de nombre ni de tipo. Lo que cambió es lo que significa `destino`. En la 3
         * era un número de teléfono; en la 4 es un `chat_id` o un `@usuario` de Telegram.
         *
         * Eso lo convierte en el peor caso posible de importación: el fichero entraría sin un
         * solo error de parseo y dejaría un juego de reglas cuyos destinos **no existen**, que
         * fallarían uno por uno cuando llegara un SMS, horas después y sin que nada apuntase al
         * fichero importado. Por eso [MINIMA_ACEPTADA] es 4 y la importación de un fichero
         * anterior se rechaza explicando el motivo, en lugar de aceptarlo y romperse luego.
         */
        const val VERSION_ACTUAL = 4

        /**
         * Versión más antigua que se puede importar.
         *
         * Es la única del proyecto que no es 1, y el KDoc de [VERSION_ACTUAL] explica por qué:
         * los ficheros anteriores llevan números de teléfono donde ahora van chats.
         */
        const val MINIMA_ACEPTADA = 4
    }
}

/**
 * Los ajustes generales tal como viajan en el fichero.
 *
 * **No todo lo que está en `AppConfig` se exporta.** El fichero describe *cómo reenviar*, que es
 * algo que tiene sentido llevarse a otro teléfono; lo que identifica a este teléfono en concreto
 * se queda. El caso que fijó el criterio fue la SIM de envío, que apuntaba a una tarjeta de
 * *este* dispositivo y restaurada en otro mandaba los reenvíos por donde no era. Ese ajuste ya
 * no existe; el criterio lo hereda **el token del bot**, por un motivo peor: este fichero es
 * JSON en claro y el token permite escribir en nombre del bot.
 */
@Serializable
data class ConfiguracionJson(
    @SerialName("max_reintentos") val maxReintentos: Int,
    @SerialName("intervalo_reintento_segundos") val intervaloReintentoSegundos: Int,
    @SerialName("timeout_envio_segundos") val timeoutEnvioSegundos: Int,
    @SerialName("max_reenvios_por_minuto") val maxReenviosPorMinuto: Int,
    @SerialName("recepcion_activa") val recepcionActiva: Boolean = true
)

/** Convierte los ajustes a su forma exportable, dejando fuera lo que es de este teléfono. */
fun AppConfig.aJson() = ConfiguracionJson(
    // Si algún campo de AppConfig no debe viajar, el sitio de omitirlo es aquí, no en quien llama.
    maxReintentos = maxReintentos,
    intervaloReintentoSegundos = intervaloReintentoSegundos,
    timeoutEnvioSegundos = timeoutEnvioSegundos,
    maxReenviosPorMinuto = maxReenviosPorMinuto,
    recepcionActiva = recepcionActiva
)

/**
 * Convierte los ajustes importados al dominio.
 *
 * [tokenActual] es el token que ya tenía este teléfono. **No viene en el fichero y hay que
 * pasarlo**, porque los ajustes se *reemplazan* al importar: sin esto, importar una copia
 * dejaría `tokenBot` vacío y la aplicación se quedaría muda sin que nadie hubiera tocado el
 * token. Es el mismo papel que tenía `simActual` cuando el transporte era el SMS.
 */
fun ConfiguracionJson.aDominio(tokenActual: String) = AppConfig(
    tokenBot = tokenActual,
    maxReintentos = maxReintentos,
    intervaloReintentoSegundos = intervaloReintentoSegundos,
    timeoutEnvioSegundos = timeoutEnvioSegundos,
    maxReenviosPorMinuto = maxReenviosPorMinuto,
    recepcionActiva = recepcionActiva
)

/** Una regla tal como viaja en el fichero. */
@Serializable
data class ReglaJson(
    @SerialName("nombre") val nombre: String,
    @SerialName("regex_telefono") val regexTelefono: String? = null,
    @SerialName("regex_mensaje") val regexMensaje: String? = null,
    @SerialName("ignorar_mayusculas") val ignorarMayusculas: Boolean = false,
    @SerialName("destino") val destino: String,
    @SerialName("plantilla") val plantilla: String = Regla.PLANTILLA_POR_DEFECTO,
    @SerialName("activa") val activa: Boolean = true,
    @SerialName("continuar") val continuar: Boolean = false
)

/** Convierte una regla del dominio a su forma exportable, descartando el id local. */
fun Regla.aJson() = ReglaJson(
    nombre = nombre,
    regexTelefono = regexTelefono,
    regexMensaje = regexMensaje,
    ignorarMayusculas = ignorarMayusculas,
    destino = destino,
    plantilla = plantilla,
    activa = activa,
    continuar = continuar
)

/**
 * Convierte una regla importada al dominio.
 *
 * El [orden] lo decide quien importa, según la posición en el fichero; el id queda a 0 para que
 * Room asigne uno nuevo.
 */
fun ReglaJson.aDominio(orden: Int) = Regla(
    id = 0,
    orden = orden,
    nombre = nombre,
    regexTelefono = regexTelefono?.takeIf { it.isNotBlank() },
    regexMensaje = regexMensaje?.takeIf { it.isNotBlank() },
    ignorarMayusculas = ignorarMayusculas,
    destino = destino,
    plantilla = plantilla.ifBlank { Regla.PLANTILLA_POR_DEFECTO },
    activa = activa,
    continuar = continuar
)
