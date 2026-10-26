// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.rules

import com.capicua.smsrelay.data.config.AppConfig
import com.capicua.smsrelay.data.config.ModoDeEntrega
import com.capicua.smsrelay.domain.model.Regla
import com.capicua.smsrelay.domain.transport.Canal
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
 * El fichero lleva [version] por delante, y en SMSRelay eso ya no sirve solo para rechazar lo
 * antiguo: **dice de qué aplicación viene**, y por tanto por qué canal salía cada regla. Leerlo
 * es cosa de `ImportacionDeCopia`.
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
         * | 5       | **`destino` vuelve a ser un teléfono, en formato E.164.**  | SMStoWhats |
         * | 6       | **Cada regla lleva su `canal`**, y el destino es del canal. | SMSRelay |
         *
         * SMSGateway tenía su propio formato 1, con otra forma —una `urlTemplate` y ninguna
         * regla— y se reconoce por esa forma, no por el número.
         *
         * ## Por qué la 6 vuelve a aceptar todo lo que la 4 y la 5 rechazaban
         * Un fichero del 3, del 4 y del 5 tiene **exactamente la misma forma**: lo que cambia es
         * lo que significa `destino`. SMStoTelegram y SMStoWhats rechazaban los anteriores al
         * suyo porque entrarían sin un error de parseo y dejarían reglas cuyos destinos no
         * existen. Eso seguía siendo cierto mientras hubiera un solo canal.
         *
         * Con cuatro, el número de versión **es** el canal: un fichero del 4 son reglas de
         * Telegram, uno del 3 son reglas de SMS con números nacionales —que es justo lo que el
         * canal SMS sabe marcar— y uno del 5 son reglas de WhatsApp. La ambigüedad que obligaba a
         * rechazarlos desaparece, y con ella el rechazo. Lo que no se sabe interpretar —una
         * versión futura— se sigue rechazando.
         */
        const val VERSION_ACTUAL = 6

        /** Versión más antigua que se puede importar: todas, desde la 1 de SMStoSMS. */
        const val MINIMA_ACEPTADA = 1
    }
}

/**
 * Los ajustes generales tal como viajan en el fichero.
 *
 * **No todo lo que está en `AppConfig` se exporta.** El fichero describe *cómo reenviar*, que es
 * algo que tiene sentido llevarse a otro teléfono; lo que identifica a este teléfono en concreto
 * se queda. El caso que fijó el criterio fue la SIM de envío, `subscriptionId`, que apunta a una
 * tarjeta de *este* dispositivo y restaurada en otro mandaría los reenvíos por donde no es. Sigue
 * sin viajar.
 *
 * Las **credenciales** —el token y el id del número de WhatsApp, y el token del bot de Telegram—
 * solo viajan si quien exporta lo pide: el fichero es JSON en claro, y cada una es permiso para
 * escribir en nombre de alguien.
 *
 * Los campos de cada canal son **opcionales a propósito**: un fichero del formato 4 no trae
 * nada de WhatsApp, y leerlo con valores por defecto pisaría la configuración de WhatsApp del
 * teléfono. `ImportacionDeCopia` decide qué campos aplica según de qué aplicación venga.
 */
@Serializable
data class ConfiguracionJson(
    // ── Comunes a todos los canales ──
    @SerialName("max_reintentos") val maxReintentos: Int,
    @SerialName("intervalo_reintento_segundos") val intervaloReintentoSegundos: Int,
    @SerialName("timeout_envio_segundos") val timeoutEnvioSegundos: Int,
    @SerialName("max_reenvios_por_minuto") val maxReenviosPorMinuto: Int,
    @SerialName("recepcion_activa") val recepcionActiva: Boolean = true,
    /**
     * Los canales apagados en Ajustes, por su nombre. Null en los ficheros anteriores al formato
     * 6, que no lo traen: importarlos no enciende ni apaga nada. Un nombre que no sea de ningún
     * canal se ignora.
     */
    @SerialName("canales_desactivados") val canalesDesactivados: List<String>? = null,

    // ── WhatsApp (formatos 5 y 6) ──
    /**
     * Modo de entrega y plantilla. **Sí viajan**, al contrario que las credenciales: describen
     * cómo reenviar, y quien clona esta configuración en otro teléfono va a querer el mismo modo
     * y la misma plantilla, que habrá dado de alta con las mismas instrucciones.
     */
    @SerialName("modo_entrega") val modoEntrega: String = ModoDeEntrega.AUTOMATICO.name,
    @SerialName("plantilla_nombre") val plantillaNombre: String = "",
    @SerialName("plantilla_idioma") val plantillaIdioma: String = "es",
    @SerialName("max_mensajes_por_mes") val maxMensajesPorMes: Int = 900,

    /**
     * Las credenciales de WhatsApp, **solo si quien exporta lo pidió expresamente**.
     *
     * Null es el valor normal y significa «esta copia no las lleva». Se escriben como `null`
     * explícito en el fichero en lugar de omitirse, y eso es deliberado: quien abra el JSON ve
     * de un vistazo que no hay secretos dentro, en vez de tener que saberse qué campos deberían
     * estar y no están.
     */
    @SerialName("token_acceso") val tokenAcceso: String? = null,
    @SerialName("id_numero_telefono") val idNumeroTelefono: String? = null,

    // ── Telegram (formatos 4 y 6) ──
    /** El token del bot, con el mismo trato que las credenciales de WhatsApp. */
    @SerialName("token_bot") val tokenBot: String? = null,

    // ── SMS (formatos 3 y 6) ──
    @SerialName("proteger_bucles") val protegerBucles: Boolean? = null,

    // ── API (formato 6; en SMSGateway, su propio formato) ──
    @SerialName("aceptar_certificados_invalidos") val aceptarCertificadosInvalidos: Boolean? = null
)

/**
 * Convierte los ajustes a su forma exportable, dejando fuera lo que es de este teléfono.
 *
 * @param incluirCredenciales si las credenciales de los canales viajan en el fichero. **Por
 *   defecto no**, y tiene que pedirlo expresamente quien exporta: el fichero es JSON en claro.
 *   La pantalla que lo ofrece se encarga de decirlo con todas las letras.
 */
fun AppConfig.aJson(incluirCredenciales: Boolean = false) = ConfiguracionJson(
    // Si algún campo de AppConfig no debe viajar, el sitio de omitirlo es aquí, no en quien llama.
    maxReintentos = maxReintentos,
    intervaloReintentoSegundos = intervaloReintentoSegundos,
    timeoutEnvioSegundos = timeoutEnvioSegundos,
    maxReenviosPorMinuto = maxReenviosPorMinuto,
    recepcionActiva = recepcionActiva,
    canalesDesactivados = canalesDesactivados.sortedBy { it.ordinal }.map { it.name },
    modoEntrega = modoDeEntrega.name,
    plantillaNombre = plantillaNombre,
    plantillaIdioma = plantillaIdioma,
    maxMensajesPorMes = maxMensajesPorMes,
    tokenAcceso = tokenAcceso.takeIf { incluirCredenciales },
    idNumeroTelefono = idNumeroTelefono.takeIf { incluirCredenciales },
    tokenBot = tokenBot.takeIf { incluirCredenciales },
    protegerBucles = protegerBucles,
    aceptarCertificadosInvalidos = aceptarCertificadosInvalidos
)

/** true si el bloque trae alguna credencial no vacía. */
val ConfiguracionJson.traeCredenciales: Boolean
    get() = listOf(tokenAcceso, idNumeroTelefono, tokenBot).any { !it.isNullOrBlank() }

/**
 * Aplica sobre [actual] los ajustes comunes y los de [canales], y deja los demás como estaban.
 *
 * Es lo que cambió de la regla de las cuatro aplicaciones anteriores, que **sustituían** los
 * ajustes enteros al importar. Con cuatro canales eso ya no vale: importar la copia de
 * SMStoTelegram no puede dejar a cero el modo de entrega de WhatsApp. Cada fichero sustituye
 * los ajustes de su canal y los comunes.
 *
 * Una credencial ausente o en blanco **no pisa** la del teléfono: sustituir una credencial que
 * funciona por nada es el peor de los resultados posibles. Si el fichero sí la trae, gana la del
 * fichero, que es lo que se pidió al exportarla.
 */
fun ConfiguracionJson.aplicarSobre(actual: AppConfig, canales: Set<Canal>): AppConfig {
    var config = actual.copy(
        maxReintentos = maxReintentos,
        intervaloReintentoSegundos = intervaloReintentoSegundos,
        timeoutEnvioSegundos = timeoutEnvioSegundos,
        maxReenviosPorMinuto = maxReenviosPorMinuto,
        recepcionActiva = recepcionActiva,
        canalesDesactivados = canalesDesactivados?.mapNotNull { Canal.de(it) }?.toSet()
            ?: actual.canalesDesactivados
    )
    if (Canal.WHATSAPP in canales) {
        config = config.copy(
            modoDeEntrega = ModoDeEntrega.de(modoEntrega),
            plantillaNombre = plantillaNombre,
            plantillaIdioma = plantillaIdioma,
            maxMensajesPorMes = maxMensajesPorMes,
            tokenAcceso = tokenAcceso?.takeIf { it.isNotBlank() } ?: config.tokenAcceso,
            idNumeroTelefono = idNumeroTelefono?.takeIf { it.isNotBlank() } ?: config.idNumeroTelefono
        )
    }
    if (Canal.TELEGRAM in canales) {
        config = config.copy(tokenBot = tokenBot?.takeIf { it.isNotBlank() } ?: config.tokenBot)
    }
    if (Canal.SMS in canales) {
        config = config.copy(protegerBucles = protegerBucles ?: config.protegerBucles)
    }
    if (Canal.API in canales) {
        config = config.copy(
            aceptarCertificadosInvalidos = aceptarCertificadosInvalidos ?: config.aceptarCertificadosInvalidos
        )
    }
    return config
}

/** Una regla tal como viaja en el fichero. */
@Serializable
data class ReglaJson(
    @SerialName("nombre") val nombre: String,
    @SerialName("regex_telefono") val regexTelefono: String? = null,
    @SerialName("regex_mensaje") val regexMensaje: String? = null,
    @SerialName("ignorar_mayusculas") val ignorarMayusculas: Boolean = false,
    /**
     * Nombre de la constante de [Canal]. Null en los ficheros anteriores al formato 6, donde el
     * canal lo dice la versión del fichero.
     */
    @SerialName("canal") val canal: String? = null,
    @SerialName("destino") val destino: String,
    /** Sin valor: lo pone [aDominio] según el canal, que es el que sabe cuál le corresponde. */
    @SerialName("plantilla") val plantilla: String = "",
    @SerialName("activa") val activa: Boolean = true,
    @SerialName("continuar") val continuar: Boolean = false
)

/** Convierte una regla del dominio a su forma exportable, descartando el id local. */
fun Regla.aJson() = ReglaJson(
    nombre = nombre,
    regexTelefono = regexTelefono,
    regexMensaje = regexMensaje,
    ignorarMayusculas = ignorarMayusculas,
    canal = canal.name,
    destino = destino,
    plantilla = plantilla,
    activa = activa,
    continuar = continuar
)

/**
 * Convierte una regla importada al dominio, con [canal] ya decidido por quien importa.
 *
 * El [orden] lo decide quien importa, según la posición en el fichero; el id queda a 0 para que
 * Room asigne uno nuevo.
 */
fun ReglaJson.aDominio(orden: Int, canal: Canal) = Regla(
    id = 0,
    orden = orden,
    nombre = nombre,
    regexTelefono = regexTelefono?.takeIf { it.isNotBlank() },
    regexMensaje = regexMensaje?.takeIf { it.isNotBlank() },
    ignorarMayusculas = ignorarMayusculas,
    canal = canal,
    destino = destino.trim(),
    plantilla = plantilla.ifBlank { Regla.plantillaPorDefecto(canal) },
    activa = activa,
    continuar = continuar
)
