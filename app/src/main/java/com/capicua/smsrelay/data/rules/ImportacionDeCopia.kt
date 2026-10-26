// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.rules

import com.capicua.smsrelay.data.config.AppConfig
import com.capicua.smsrelay.data.telegram.DestinoDeTelegram
import com.capicua.smsrelay.data.whatsapp.DestinoDeWhatsApp
import com.capicua.smsrelay.domain.model.Regla
import com.capicua.smsrelay.domain.rules.EvaluadorDeReglas
import com.capicua.smsrelay.domain.rules.NormalizadorTelefono
import com.capicua.smsrelay.domain.rules.PlantillaDeUrl
import com.capicua.smsrelay.domain.transport.Canal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lo que sale de leer un fichero de copia, antes de escribir nada. */
sealed interface CopiaInterpretada {

    /**
     * @param origen aplicación que escribió el fichero, para decirlo al terminar.
     * @param config los ajustes ya aplicados sobre los del teléfono, o null si el fichero no
     *   traía ajustes y hay que dejarlos como están.
     * @param reglas las reglas que pasan la validación de su canal, en el orden del fichero.
     * @param rechazadas las que no la pasan, que se cuentan aparte.
     */
    data class Correcta(
        val origen: String,
        val config: AppConfig?,
        val reglas: List<Regla>,
        val rechazadas: Int,
        val conCredenciales: Boolean
    ) : CopiaInterpretada

    data class Rechazada(val motivo: String) : CopiaInterpretada
}

/**
 * Lee un fichero de copia de **cualquiera de las cinco aplicaciones** y decide qué entra.
 *
 * Es la pieza que convierte «instalar SMSRelay» en una migración de verdad: exportar en la
 * aplicación vieja, importar aquí, rellenar credenciales. Es Kotlin puro —no escribe nada, no
 * lee ficheros— para que `ImportacionDeCopiaTest` pueda probarla con un fichero real de cada
 * aplicación.
 *
 * | Fichero de | Formato | Canal de las reglas | Ajustes que aplica |
 * |---|---|---|---|
 * | SMSRelay | 6 | El de cada regla | Todos |
 * | SMStoWhats | 5 | WhatsApp | Comunes y de WhatsApp |
 * | SMStoTelegram | 4 | Telegram | Comunes y de Telegram |
 * | SMStoSMS | 1, 2 y 3 | SMS | Comunes y de SMS (el 1 y el 2 no traen ajustes) |
 * | SMSGateway | su 1 | API, en **una** regla que casa con todo | Comunes y de API |
 *
 * Los formatos 1 y 2 de SMStoSMS se comprobaron en el Git de aquel repositorio: tienen la misma
 * forma que el 3, sin `ignorar_mayusculas` el 1 y sin bloque de ajustes los dos, y sus destinos
 * son teléfonos nacionales, que es lo que el canal SMS sabe marcar. No hay motivo para
 * rechazarlos.
 */
object ImportacionDeCopia {

    /** Nombre de la regla que se crea a partir de un fichero de SMSGateway. */
    const val NOMBRE_REGLA_GATEWAY = "Todos los SMS (de SMSGateway)"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param actual la configuración del teléfono, sobre la que se aplica la del fichero.
     * @param validarPatron el de `EvaluadorDeReglas`: una regla con una expresión que no compila
     *   no entra.
     */
    fun interpretar(
        contenido: String,
        actual: AppConfig,
        validarPatron: (String?) -> String?
    ): CopiaInterpretada {
        val raiz = try {
            json.parseToJsonElement(contenido).jsonObject
        } catch (e: Exception) {
            return CopiaInterpretada.Rechazada("el fichero no es JSON")
        }

        // SMSGateway se reconoce por la forma y no por el número: un `version: 1` a secas
        // también puede ser el primer formato de SMStoSMS.
        if ("urlTemplate" in raiz && "reglas" !in raiz) {
            return desdeGateway(raiz, actual, validarPatron)
        }

        val fichero = try {
            json.decodeFromJsonElement(ReglasExportadas.serializer(), raiz)
        } catch (e: Exception) {
            return CopiaInterpretada.Rechazada("el fichero no tiene el formato de una copia")
        }

        if (fichero.version > ReglasExportadas.VERSION_ACTUAL) {
            return CopiaInterpretada.Rechazada(
                "el fichero usa el formato ${fichero.version} y esta versión entiende hasta " +
                    "el ${ReglasExportadas.VERSION_ACTUAL}"
            )
        }
        if (fichero.version < ReglasExportadas.MINIMA_ACEPTADA) {
            return CopiaInterpretada.Rechazada("el fichero declara un formato ${fichero.version}, que no existe")
        }

        val canalDelFormato = canalDeFormato(fichero.version)
        val canalesDeAjustes = canalDelFormato?.let { setOf(it) } ?: Canal.entries.toSet()

        val reglas = mutableListOf<Regla>()
        var rechazadas = 0
        fichero.reglas.forEach { regla ->
            // En el formato 6 el canal es de cada regla. Uno que no se conoce —de una versión
            // futura— no se adivina: la regla se rechaza.
            val canal = if (canalDelFormato != null) canalDelFormato
            else Canal.de(regla.canal) ?: if (regla.canal == null) Canal.POR_DEFECTO else null

            val candidata = canal?.let { regla.aDominio(orden = reglas.size, canal = it) }
            if (candidata != null && esValida(candidata, validarPatron)) reglas += candidata
            else rechazadas++
        }

        return CopiaInterpretada.Correcta(
            origen = origenDeFormato(fichero.version),
            config = fichero.configuracion?.aplicarSobre(actual, canalesDeAjustes),
            reglas = reglas,
            rechazadas = rechazadas,
            conCredenciales = fichero.configuracion?.traeCredenciales == true
        )
    }

    /**
     * El canal de todas las reglas de un fichero de [version], o null si cada regla lleva el
     * suyo. Ver la tabla de [ReglasExportadas.VERSION_ACTUAL].
     */
    fun canalDeFormato(version: Int): Canal? = when (version) {
        in 1..3 -> Canal.SMS
        4 -> Canal.TELEGRAM
        5 -> Canal.WHATSAPP
        else -> null
    }

    private fun origenDeFormato(version: Int): String = when (version) {
        in 1..3 -> "SMStoSMS"
        4 -> "SMStoTelegram"
        5 -> "SMStoWhats"
        else -> "SMSRelay"
    }

    /**
     * Un fichero de SMSGateway: una URL y unos reintentos, sin reglas.
     *
     * En SMSRelay eso es **una regla** que casa con todo, por el canal API, con la URL de
     * destino y `{mensaje}` de plantilla, que es exactamente lo que SMSGateway hacía con cada
     * SMS. Su `timeoutSegundos` era el de la petición HTTP, que aquí es el plazo de envío.
     */
    private fun desdeGateway(
        raiz: JsonObject,
        actual: AppConfig,
        validarPatron: (String?) -> String?
    ): CopiaInterpretada {
        fun entero(campo: String, porDefecto: Int) = raiz[campo]?.jsonPrimitive?.intOrNull ?: porDefecto
        fun booleano(campo: String, porDefecto: Boolean) =
            raiz[campo]?.jsonPrimitive?.booleanOrNull ?: porDefecto

        val config = actual.copy(
            timeoutEnvioSegundos = entero("timeoutSegundos", actual.timeoutEnvioSegundos),
            maxReintentos = entero("maxReintentos", actual.maxReintentos),
            intervaloReintentoSegundos = entero("intervaloReintentoSegundos", actual.intervaloReintentoSegundos),
            recepcionActiva = booleano("recepcionActiva", actual.recepcionActiva),
            aceptarCertificadosInvalidos =
                booleano("aceptarCertificadosInvalidos", actual.aceptarCertificadosInvalidos)
        )

        val url = raiz["urlTemplate"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val regla = Regla(
            orden = 0,
            nombre = NOMBRE_REGLA_GATEWAY,
            canal = Canal.API,
            destino = url,
            plantilla = Regla.PLANTILLA_POR_DEFECTO_API
        )
        val valida = esValida(regla, validarPatron)

        return CopiaInterpretada.Correcta(
            origen = "SMSGateway",
            config = config,
            reglas = if (valida) listOf(regla) else emptyList(),
            rechazadas = if (valida) 0 else 1,
            conCredenciales = false
        )
    }

    /**
     * Una regla que entra tiene que poder entregar: un nombre, expresiones que compilen y un
     * destino con la forma de **su** canal.
     *
     * Una regla que entra en silencio y no entrega nunca es mucho más difícil de diagnosticar
     * que una que se rechaza al importar y se cuenta aparte.
     */
    fun esValida(regla: Regla, validarPatron: (String?) -> String?): Boolean =
        regla.nombre.isNotBlank() &&
            destinoValido(regla.canal, regla.destino) &&
            validarPatron(regla.regexTelefono) == null &&
            validarPatron(regla.regexMensaje) == null

    /**
     * La forma de destino de cada canal, la misma que exige el editor de reglas.
     *
     * El editor da un mensaje distinto para cada error; aquí basta con saber si entra.
     */
    fun destinoValido(canal: Canal, destino: String): Boolean {
        val limpio = destino.trim()
        return when (canal) {
            Canal.WHATSAPP -> DestinoDeWhatsApp.pareceValido(limpio)
            Canal.TELEGRAM -> DestinoDeTelegram.pareceValido(limpio)
            Canal.SMS -> limpio == EvaluadorDeReglas.MARCADOR_TELEFONO ||
                (NormalizadorTelefono.esDestinoEnviable(limpio) &&
                    limpio.all { it.isDigit() || it in " +-()" })
            Canal.API -> PlantillaDeUrl.esValida(limpio)
        }
    }
}
