// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.rules

import com.capicua.smstowhats.data.config.AppConfig
import com.capicua.smstowhats.domain.model.Regla
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del formato de intercambio de reglas.
 *
 * Lo que se verifica no es que kotlinx.serialization funcione, sino las decisiones propias del
 * formato: que los ids locales no viajen, que el token del bot tampoco, que el orden relativo
 * se conserve y que un fichero escrito por otra versión no reviente el parseo.
 */
class ReglasJsonTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Sin esto, kotlinx.serialization omite los campos cuyo valor coincide con el
        // predeterminado — incluida la versión del formato, que es justo lo que el fichero
        // tiene que declarar para que una importación futura sepa qué está leyendo.
        encodeDefaults = true
    }

    private fun regla(
        id: Long = 42,
        orden: Int = 3,
        nombre: String = "Banco",
        regexTelefono: String? = "^BANCO$",
        regexMensaje: String? = "codigo",
        ignorarMayusculas: Boolean = false,
        destino: String = "+34600112233",
        plantilla: String = "De {telefono}: {mensaje}",
        activa: Boolean = true,
        continuar: Boolean = false
    ) = Regla(
        // Argumentos nombrados a propósito: con posicionales, añadir un campo al modelo
        // compila mal o, peor, compila y cambia de sitio dos valores del mismo tipo.
        id = id,
        orden = orden,
        nombre = nombre,
        regexTelefono = regexTelefono,
        regexMensaje = regexMensaje,
        ignorarMayusculas = ignorarMayusculas,
        destino = destino,
        plantilla = plantilla,
        activa = activa,
        continuar = continuar
    )

    // ── Ida y vuelta ──────────────────────────────────────────────────────────

    @Test
    fun `una regla sobrevive al viaje de ida y vuelta`() {
        val original = regla()

        val recuperada = original.aJson().aDominio(orden = 0)

        assertEquals(original.nombre, recuperada.nombre)
        assertEquals(original.regexTelefono, recuperada.regexTelefono)
        assertEquals(original.regexMensaje, recuperada.regexMensaje)
        assertEquals(original.destino, recuperada.destino)
        assertEquals(original.plantilla, recuperada.plantilla)
        assertEquals(original.activa, recuperada.activa)
        assertEquals(original.continuar, recuperada.continuar)
    }

    @Test
    fun `el id local no viaja en el fichero`() {
        val serializado = json.encodeToString(ReglaJson.serializer(), regla(id = 42).aJson())

        assertFalse("el id no debe aparecer en el JSON", serializado.contains("\"id\""))
        assertFalse("el orden tampoco: lo decide quien importa", serializado.contains("\"orden\""))
    }

    @Test
    fun `al importar el id queda a cero para que Room asigne uno nuevo`() {
        val importada = regla(id = 42).aJson().aDominio(orden = 7)

        assertEquals(0L, importada.id)
        assertEquals(7, importada.orden)
    }

    // ── Conjunto completo ─────────────────────────────────────────────────────

    @Test
    fun `el orden relativo del fichero se conserva al importar`() {
        val reglas = listOf(
            regla(nombre = "Primera", destino = "+34600000001"),
            regla(nombre = "Segunda", destino = "+34600000002"),
            regla(nombre = "Tercera", destino = "+34600000003")
        )

        val fichero = ReglasExportadas(
            exportado = "2026-10-07T19:30:00Z",
            reglas = reglas.map { it.aJson() }
        )
        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            json.encodeToString(ReglasExportadas.serializer(), fichero)
        )

        val importadas = recuperado.reglas.mapIndexed { indice, r -> r.aDominio(indice) }

        assertEquals(listOf("Primera", "Segunda", "Tercera"), importadas.map { it.nombre })
        assertEquals(listOf(0, 1, 2), importadas.map { it.orden })
    }

    @Test
    fun `el fichero declara la version del formato`() {
        val serializado = json.encodeToString(
            ReglasExportadas.serializer(),
            ReglasExportadas(exportado = "2026-10-07T19:30:00Z", reglas = emptyList())
        )

        assertTrue(serializado.contains("\"version\""))
        assertEquals(5, ReglasExportadas.VERSION_ACTUAL)
    }

    /**
     * La versión mínima aceptada no es 1, y es la segunda vez que se rompe la compatibilidad
     * hacia atrás a propósito.
     *
     * El test existe para que bajarla sea un acto deliberado y no un descuido: un fichero del
     * formato 4 tiene exactamente la misma forma que uno del 5 —ningún campo cambió de nombre
     * ni de tipo— y entraría sin un solo error de parseo dejando un juego de reglas cuyos
     * destinos son chats de Telegram que aquí no existen.
     */
    @Test
    fun `no se aceptan ficheros anteriores al formato 5`() {
        assertEquals(5, ReglasExportadas.MINIMA_ACEPTADA)
    }

    // ── La configuración viaja con las reglas ─────────────────────────────────

    @Test
    fun `los ajustes sobreviven a la ida y vuelta`() {
        val original = AppConfig(
            tokenAcceso = "EAAno-deberia-viajar",
            idNumeroTelefono = "111111111111111",
            maxReintentos = 7,
            intervaloReintentoSegundos = 45,
            timeoutEnvioSegundos = 90,
            maxReenviosPorMinuto = 3,
            recepcionActiva = false
        )

        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            json.encodeToString(
                ReglasExportadas.serializer(),
                ReglasExportadas(
                    exportado = "2026-10-10T22:00:00Z",
                    configuracion = original.aJson(),
                    reglas = emptyList()
                )
            )
        )

        // Las credenciales no viajan en el fichero y al importar se conservan las que tenga
        // el dispositivo de destino. Son dos, y olvidar cualquiera de las dos deja la
        // aplicacion callada.
        val importado = recuperado.configuracion!!.aDominio(
            tokenActual = "EAAconservado",
            idActual = "222222222222222"
        )

        assertEquals(
            original.copy(
                tokenAcceso = "EAAconservado",
                idNumeroTelefono = "222222222222222"
            ),
            importado
        )
    }

    private val credenciales = AppConfig(
        tokenAcceso = "EAAhBOweik6ad9rQXZ2Ql3n1xWcCOPvj8sAAAAAAAAAAAA",
        idNumeroTelefono = "123456789012345"
    )

    private fun exportar(config: AppConfig, incluirCredenciales: Boolean = false) =
        json.encodeToString(
            ReglasExportadas.serializer(),
            ReglasExportadas(
                exportado = "2026-10-12T20:00:00Z",
                configuracion = config.aJson(incluirCredenciales),
                reglas = listOf(regla().aJson())
            )
        )

    /**
     * Este fichero es JSON en claro que se guarda donde el operador diga y se lleva a otro
     * teléfono. Que las credenciales no viajen **por defecto** no es una preferencia: es lo que
     * impide que una copia de seguridad hecha sin pensar reparta permiso para escribir en
     * nombre del número.
     *
     * El valor por defecto del parámetro es parte de lo que se comprueba aquí: quien llame a
     * `aJson()` sin argumentos tiene que obtener el fichero seguro.
     */
    @Test
    fun `por defecto las credenciales no aparecen en el fichero exportado`() {
        val serializado = exportar(credenciales)

        assertFalse("el token no debe aparecer", serializado.contains("EAAhBOweik"))
        assertFalse("ni el id del numero", serializado.contains("123456789012345"))
        // Los campos sí salen, con valor null: el fichero dice explícitamente que no lleva
        // secretos en vez de callárselo, que es más fácil de auditar de un vistazo.
        assertTrue("el campo debe estar, a null", serializado.contains("\"token_acceso\": null"))
    }

    /**
     * El caso contrario, que es la razón de que exista el parámetro: quien lo pide a sabiendas
     * se lleva las credenciales dentro, porque si no una copia no sirve para restaurar un
     * teléfono entero.
     */
    @Test
    fun `si se piden, las credenciales viajan en el fichero`() {
        val serializado = exportar(credenciales, incluirCredenciales = true)

        assertTrue("el token debe aparecer", serializado.contains("EAAhBOweik"))
        assertTrue("y el id del numero", serializado.contains("123456789012345"))
    }

    /**
     * Un fichero sin credenciales no puede dejar mudo al teléfono que lo importa.
     *
     * Es el modo de fallo que justifica los dos parámetros de [aDominio]: los ajustes se
     * *reemplazan* al importar, así que sin respaldo una copia normal borraría el token.
     */
    @Test
    fun `importar sin credenciales conserva las del telefono`() {
        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            exportar(credenciales)
        )

        val importado = recuperado.configuracion!!.aDominio("EAAya-configurado", "999888777666555")

        assertEquals("EAAya-configurado", importado.tokenAcceso)
        assertEquals("999888777666555", importado.idNumeroTelefono)
    }

    /** Y si el fichero las trae, ganan: es exactamente lo que se pidió al exportarlas. */
    @Test
    fun `importar con credenciales sustituye las del telefono`() {
        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            exportar(credenciales, incluirCredenciales = true)
        )

        val importado = recuperado.configuracion!!.aDominio("EAAya-configurado", "999888777666555")

        assertEquals(credenciales.tokenAcceso, importado.tokenAcceso)
        assertEquals("123456789012345", importado.idNumeroTelefono)
    }

    /**
     * Una credencial en blanco en el fichero cuenta como ausente.
     *
     * Sustituir una credencial que funciona por una cadena vacía es el peor de los dos
     * resultados posibles: deja la aplicación muda y sin rastro de por qué. Pasa con un fichero
     * exportado desde un teléfono a medio configurar, que es un caso corriente.
     */
    @Test
    fun `una credencial vacia en el fichero no pisa la que ya habia`() {
        val aMedias = AppConfig(tokenAcceso = "", idNumeroTelefono = "123456789012345")

        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            exportar(aMedias, incluirCredenciales = true)
        )

        val importado = recuperado.configuracion!!.aDominio("EAAya-configurado", "999888777666555")

        assertEquals("el token vacío no debe pisar", "EAAya-configurado", importado.tokenAcceso)
        assertEquals("el id sí viene en el fichero", "123456789012345", importado.idNumeroTelefono)
    }

    /**
     * Los ficheros de los formatos 1 y 2 solo llevaban reglas. Tienen que seguir entrando, y
     * dejar los ajustes del dispositivo como estén en lugar de pisarlos con valores por
     * defecto.
     */
    @Test
    fun `un fichero sin bloque de configuracion se sigue importando`() {
        val soloReglas = """
            {
              "version": 2,
              "exportado": "2026-10-07T19:30:00Z",
              "reglas": [ { "nombre": "Simple", "destino": "+34600112233" } ]
            }
        """.trimIndent()

        val fichero = json.decodeFromString(ReglasExportadas.serializer(), soloReglas)

        assertNull(fichero.configuracion)
        assertEquals(1, fichero.reglas.size)
    }

    // ── Tolerancia ────────────────────────────────────────────────────────────

    @Test
    fun `un fichero minimo se acepta y los campos opcionales toman su valor por defecto`() {
        val minimo = """
            {
              "exportado": "2026-10-07T19:30:00Z",
              "reglas": [ { "nombre": "Simple", "destino": "+34600112233" } ]
            }
        """.trimIndent()

        val fichero = json.decodeFromString(ReglasExportadas.serializer(), minimo)
        val regla = fichero.reglas.single().aDominio(orden = 0)

        assertEquals(ReglasExportadas.VERSION_ACTUAL, fichero.version)
        assertEquals("Simple", regla.nombre)
        assertEquals(Regla.PLANTILLA_POR_DEFECTO, regla.plantilla)
        assertTrue(regla.activa)
        assertFalse(regla.continuar)
        assertNull(regla.regexTelefono)
        assertNull(regla.regexMensaje)
        assertFalse(regla.ignorarMayusculas)
    }

    @Test
    fun `un fichero del formato 1 se importa y la regla distingue mayusculas`() {
        // Compatibilidad hacia atrás: los ficheros exportados antes de que existiera la
        // opción no la traen, y la regla tiene que entrar con el comportamiento que tenía.
        val formato1 = """
            {
              "version": 1,
              "exportado": "2026-10-07T19:30:00Z",
              "reglas": [
                { "nombre": "Banco", "regex_mensaje": "codigo", "destino": "+34600112233" }
              ]
            }
        """.trimIndent()

        val regla = json.decodeFromString(ReglasExportadas.serializer(), formato1)
            .reglas.single().aDominio(orden = 0)

        assertFalse(regla.ignorarMayusculas)
    }

    @Test
    fun `ignorar_mayusculas sobrevive a exportar e importar`() {
        val original = Regla(
            orden = 0,
            nombre = "Sin cajas",
            regexMensaje = "hola",
            ignorarMayusculas = true,
            destino = "+34600112233"
        )

        val recuperada = json.decodeFromString(
            ReglasExportadas.serializer(),
            json.encodeToString(
                ReglasExportadas.serializer(),
                ReglasExportadas(exportado = "2026-10-08T20:00:00Z", reglas = listOf(original.aJson()))
            )
        ).reglas.single().aDominio(orden = 0)

        assertTrue(recuperada.ignorarMayusculas)
    }

    @Test
    fun `se ignoran los campos desconocidos de una version futura`() {
        val conExtras = """
            {
              "version": 1,
              "exportado": "2026-10-07T19:30:00Z",
              "inventado": "algo",
              "reglas": [
                { "nombre": "Simple", "destino": "+34600112233", "otro_campo": 123 }
              ]
            }
        """.trimIndent()

        val fichero = json.decodeFromString(ReglasExportadas.serializer(), conExtras)

        assertEquals(1, fichero.reglas.size)
    }

    @Test
    fun `los patrones en blanco se normalizan a null al importar`() {
        val conBlancos = ReglaJson(
            nombre = "Blancos",
            regexTelefono = "   ",
            regexMensaje = "",
            destino = "+34600112233",
            plantilla = "  "
        )

        val regla = conBlancos.aDominio(orden = 0)

        assertNull(regla.regexTelefono)
        assertNull(regla.regexMensaje)
        assertEquals(Regla.PLANTILLA_POR_DEFECTO, regla.plantilla)
    }
}
