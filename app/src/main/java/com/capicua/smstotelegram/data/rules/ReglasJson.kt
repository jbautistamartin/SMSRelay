// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.data.rules

import com.capicua.smstotelegram.data.config.AppConfig
import com.capicua.smstotelegram.domain.model.Regla
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
         * | Versión | Cambio                                       |
         * |---------|----------------------------------------------|
         * | 1       | Formato inicial.                             |
         * | 2       | La regla gana `ignorar_mayusculas`.          |
         * | 3       | El fichero gana el bloque `configuracion`.   |
         *
         * Subir la versión al añadir un campo opcional puede parecer excesivo, porque un
         * fichero de la 1 se sigue importando sin problema. Lo que evita es el caso contrario:
         * una versión antigua de la aplicación leyendo un fichero de la 2 descartaría
         * `ignorar_mayusculas` en silencio —`ignoreUnknownKeys` está activado— y la regla
         * entraría distinguiendo mayúsculas sin avisar. Con la versión por delante, esa
         * importación se rechaza con un mensaje que explica lo que pasa.
         */
        const val VERSION_ACTUAL = 3
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
    @SerialName("proteger_bucles") val protegerBucles: Boolean,
    @SerialName("recepcion_activa") val recepcionActiva: Boolean = true
)

/** Convierte los ajustes a su forma exportable, dejando fuera lo que es de este teléfono. */
fun AppConfig.aJson() = ConfiguracionJson(
    // Si algún campo de AppConfig no debe viajar, el sitio de omitirlo es aquí, no en quien llama.
    maxReintentos = maxReintentos,
    intervaloReintentoSegundos = intervaloReintentoSegundos,
    timeoutEnvioSegundos = timeoutEnvioSegundos,
    maxReenviosPorMinuto = maxReenviosPorMinuto,
    protegerBucles = protegerBucles,
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
    protegerBucles = protegerBucles,
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
