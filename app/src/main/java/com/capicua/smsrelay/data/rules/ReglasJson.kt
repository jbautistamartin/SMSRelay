// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.rules

import com.capicua.smsrelay.data.config.AppConfig
import com.capicua.smsrelay.data.config.ModoDeEntrega
import com.capicua.smsrelay.domain.model.Regla
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
         * | 5       | **`destino` vuelve a ser un teléfono, en formato E.164.**  | SMStoWhats |
         *
         * Subir la versión al añadir un campo opcional puede parecer excesivo, porque un
         * fichero de la 1 se sigue importando sin problema. Lo que evita es el caso contrario:
         * una versión antigua de la aplicación leyendo un fichero de la 2 descartaría
         * `ignorar_mayusculas` en silencio —`ignoreUnknownKeys` está activado— y la regla
         * entraría distinguiendo mayúsculas sin avisar.
         *
         * ## Por qué la 4 y la 5 rompen la compatibilidad hacia atrás
         * Es el mismo problema dos veces, y la segunda vez es simétrica de la primera.
         *
         * Un fichero del formato 3 tiene **exactamente la misma forma** que uno del 4: ningún
         * campo cambió de nombre ni de tipo. Lo que cambió es lo que significa `destino`. En la 3
         * era un número de teléfono; en la 4 es un `chat_id` o un `@usuario` de Telegram. Y en la
         * 5 vuelve a ser un teléfono, pero **no el mismo teléfono que en la 3**: ahora tiene que
         * llevar prefijo internacional, porque va a WhatsApp y no a la radio del operador.
         *
         * Eso convierte a los dos en el peor caso posible de importación: el fichero entraría sin
         * un solo error de parseo y dejaría un juego de reglas cuyos destinos **no existen**, que
         * fallarían uno por uno cuando llegara un SMS, horas después y sin que nada apuntase al
         * fichero importado. Por eso [MINIMA_ACEPTADA] es 5 y la importación de un fichero
         * anterior se rechaza explicando el motivo, en lugar de aceptarlo y romperse luego.
         *
         * Que un fichero del 3 —cuyos destinos *son* teléfonos— tampoco se acepte es deliberado:
         * son teléfonos nacionales, escritos para marcarse desde ese país, y entrarían todos sin
         * prefijo para fallar todos igual.
         */
        const val VERSION_ACTUAL = 5

        /**
         * Versión más antigua que se puede importar.
         *
         * Nunca ha sido 1 desde que el medio de salida empezó a moverse, y el KDoc de
         * [VERSION_ACTUAL] explica por qué: los ficheros anteriores llevan en `destino` algo que
         * aquí no se puede entregar.
         */
        const val MINIMA_ACEPTADA = 5
    }
}

/**
 * Los ajustes generales tal como viajan en el fichero.
 *
 * **No todo lo que está en `AppConfig` se exporta.** El fichero describe *cómo reenviar*, que es
 * algo que tiene sentido llevarse a otro teléfono; lo que identifica a este teléfono en concreto
 * se queda. El caso que fijó el criterio fue la SIM de envío, que apuntaba a una tarjeta de
 * *este* dispositivo y restaurada en otro mandaba los reenvíos por donde no era.
 *
 * Ese criterio lo heredan ahora **las dos credenciales de la Cloud API**, cada una por su motivo:
 *
 * - El **token de acceso** no viaja porque este fichero es JSON en claro y el token permite
 *   escribir en nombre del número. Es el motivo del token del bot de Telegram, agravado.
 * - El **id del número** no viaja aunque no sea secreto, y este es el heredero directo de
 *   `simActual`: identifica el número emisor de *esta* cuenta, y restaurado en otro teléfono
 *   dejaría los reenvíos saliendo por donde no es.
 */
@Serializable
data class ConfiguracionJson(
    /**
     * Modo de entrega y plantilla. **Sí viajan**, al contrario que las credenciales.
     *
     * La línea que separa lo que viaja de lo que no es «¿describe cómo reenviar, o identifica a
     * este teléfono?». El nombre de la plantilla describe cómo reenviar: quien clona esta
     * configuración en otro teléfono va a querer el mismo modo y la misma plantilla, que habrá
     * dado de alta con las mismas instrucciones. El id del número y el token identifican a
     * *esta* cuenta, y se quedan.
     */
    @SerialName("modo_entrega") val modoEntrega: String = ModoDeEntrega.AUTOMATICO.name,
    @SerialName("plantilla_nombre") val plantillaNombre: String = "",
    @SerialName("plantilla_idioma") val plantillaIdioma: String = "es",
    @SerialName("max_reintentos") val maxReintentos: Int,
    @SerialName("intervalo_reintento_segundos") val intervaloReintentoSegundos: Int,
    @SerialName("timeout_envio_segundos") val timeoutEnvioSegundos: Int,
    @SerialName("max_reenvios_por_minuto") val maxReenviosPorMinuto: Int,
    @SerialName("max_mensajes_por_mes") val maxMensajesPorMes: Int = 900,
    @SerialName("recepcion_activa") val recepcionActiva: Boolean = true,

    /**
     * Las credenciales, **solo si quien exporta lo pidió expresamente**.
     *
     * Null es el valor normal y significa «esta copia no las lleva». Se escriben como `null`
     * explícito en el fichero en lugar de omitirse, y eso es deliberado: quien abra el JSON ve
     * de un vistazo que no hay secretos dentro, en vez de tener que saberse qué campos deberían
     * estar y no están.
     *
     * ## Por qué esto no sube la versión del formato
     * Son campos opcionales que solo se añaden. Un fichero sin ellos —todos los anteriores a
     * esta función— se importa exactamente igual que antes: [aDominio] cae en las credenciales
     * que ya tuviera el teléfono. Y una versión vieja de la aplicación leyendo un fichero que
     * sí las lleva las descarta por `ignoreUnknownKeys` y se queda con las suyas, que es el
     * comportamiento seguro. Ninguna de las dos direcciones rompe nada, así que subir la
     * versión solo serviría para rechazar copias que funcionan.
     *
     * Lo que **no** cambia es la línea de qué describe «cómo reenviar» y qué identifica a «este
     * teléfono»: las credenciales siguen siendo lo segundo. Lo que cambia es que ahora quien
     * exporta puede decidir llevárselas, sabiendo lo que eso significa.
     */
    @SerialName("token_acceso") val tokenAcceso: String? = null,
    @SerialName("id_numero_telefono") val idNumeroTelefono: String? = null
)

/**
 * Convierte los ajustes a su forma exportable, dejando fuera lo que es de este teléfono.
 *
 * @param incluirCredenciales si el token y el id del número viajan en el fichero. **Por
 *   defecto no**, y tiene que pedirlo expresamente quien exporta: el fichero es JSON en claro
 *   y el token es permiso para enviar mensajes como ese número. La pantalla que lo ofrece se
 *   encarga de decirlo con todas las letras.
 */
fun AppConfig.aJson(incluirCredenciales: Boolean = false) = ConfiguracionJson(
    // Si algún campo de AppConfig no debe viajar, el sitio de omitirlo es aquí, no en quien llama.
    tokenAcceso = tokenAcceso.takeIf { incluirCredenciales },
    idNumeroTelefono = idNumeroTelefono.takeIf { incluirCredenciales },
    modoEntrega = modoDeEntrega.name,
    plantillaNombre = plantillaNombre,
    plantillaIdioma = plantillaIdioma,
    maxReintentos = maxReintentos,
    intervaloReintentoSegundos = intervaloReintentoSegundos,
    timeoutEnvioSegundos = timeoutEnvioSegundos,
    maxReenviosPorMinuto = maxReenviosPorMinuto,
    maxMensajesPorMes = maxMensajesPorMes,
    recepcionActiva = recepcionActiva
)

/**
 * Convierte los ajustes importados al dominio.
 *
 * [tokenActual] e [idActual] son las credenciales que ya tenía este teléfono, y son el
 * **respaldo**: se usan cuando el fichero no trae las suyas, que es el caso normal. Hacen falta
 * porque los ajustes se *reemplazan* al importar: sin ellas, importar una copia sin credenciales
 * dejaría las dos vacías y la aplicación se quedaría muda sin que nadie hubiera tocado nada. Es
 * el mismo papel que tenía `simActual` cuando el transporte era el SMS.
 *
 * Son dos parámetros y no uno porque son dos credenciales independientes, y olvidar cualquiera
 * de las dos deja la aplicación igual de callada.
 *
 * **Si el fichero sí las trae, ganan las del fichero.** Es lo que se pidió al exportarlas: una
 * copia con credenciales existe precisamente para restaurar un teléfono entero o clonar la
 * configuración en otro, y dejarlas fuera al importar vaciaría de sentido la casilla. Cada una
 * se decide por separado —un fichero podría traer una y no la otra— y una cadena vacía cuenta
 * como ausente, porque no sirve para enviar y sustituir una credencial buena por nada es el
 * peor de los dos resultados posibles.
 */
fun ConfiguracionJson.aDominio(tokenActual: String, idActual: String) = AppConfig(
    tokenAcceso = tokenAcceso?.takeIf { it.isNotBlank() } ?: tokenActual,
    idNumeroTelefono = idNumeroTelefono?.takeIf { it.isNotBlank() } ?: idActual,
    modoDeEntrega = ModoDeEntrega.de(modoEntrega),
    plantillaNombre = plantillaNombre,
    plantillaIdioma = plantillaIdioma,
    maxReintentos = maxReintentos,
    intervaloReintentoSegundos = intervaloReintentoSegundos,
    timeoutEnvioSegundos = timeoutEnvioSegundos,
    maxReenviosPorMinuto = maxReenviosPorMinuto,
    maxMensajesPorMes = maxMensajesPorMes,
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
