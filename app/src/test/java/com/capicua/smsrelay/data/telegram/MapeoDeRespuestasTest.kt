// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.telegram

import com.capicua.smsrelay.domain.transport.ResultadoEnvio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del mapeo de respuestas del Bot API.
 *
 * Es lo único del transporte que se puede probar sin red, y también lo que más importa que esté
 * bien: clasificar un error como permanente cuando era transitorio pierde un mensaje que habría
 * llegado al segundo intento, y al contrario gasta diez reintentos para acabar igual.
 *
 * Los cuerpos son respuestas reales del Bot API, con su `description` en inglés tal como llegan.
 */
class MapeoDeRespuestasTest {

    // ── Entrega correcta ──────────────────────────────────────────────────────

    @Test
    fun `un 200 con ok true es entregado`() {
        val cuerpo = """{"ok":true,"result":{"message_id":42,"text":"hola"}}"""

        assertEquals(
            ResultadoEnvio.Enviado,
            MapeoDeRespuestas.interpretarEnvio(200, cuerpo)
        )
    }

    @Test
    fun `un 200 sin cuerpo legible se da por entregado`() {
        // El codigo HTTP es la fuente de verdad; el cuerpo solo aporta el motivo.
        assertEquals(ResultadoEnvio.Enviado, MapeoDeRespuestas.interpretarEnvio(200, null))
        assertEquals(ResultadoEnvio.Enviado, MapeoDeRespuestas.interpretarEnvio(200, "no es json"))
    }

    @Test
    fun `un 200 con ok false es permanente`() {
        val resultado = MapeoDeRespuestas.interpretarEnvio(200, """{"ok":false}""")

        assertTrue(resultado is ResultadoEnvio.ErrorPermanente)
    }

    // ── Transitorios ──────────────────────────────────────────────────────────

    @Test
    fun `un 429 es transitorio y trae la espera que pide Telegram`() {
        val cuerpo = """
            {"ok":false,"error_code":429,"description":"Too Many Requests: retry after 17",
             "parameters":{"retry_after":17}}
        """.trimIndent()

        val resultado = MapeoDeRespuestas.interpretarEnvio(429, cuerpo)

        assertTrue(resultado is ResultadoEnvio.ErrorTransitorio)
        assertEquals(17, (resultado as ResultadoEnvio.ErrorTransitorio).esperaSegundos)
    }

    @Test
    fun `un 429 sin retry_after sigue siendo transitorio sin espera`() {
        val resultado = MapeoDeRespuestas.interpretarEnvio(429, """{"ok":false}""")

        assertTrue(resultado is ResultadoEnvio.ErrorTransitorio)
        assertNull((resultado as ResultadoEnvio.ErrorTransitorio).esperaSegundos)
    }

    @Test
    fun `los 5xx son transitorios porque la averia es de Telegram`() {
        listOf(500, 502, 503, 599).forEach { codigo ->
            assertTrue(
                "HTTP $codigo deberia ser transitorio",
                MapeoDeRespuestas.interpretarEnvio(codigo, null) is ResultadoEnvio.ErrorTransitorio
            )
        }
    }

    @Test
    fun `un codigo desconocido se trata como transitorio por prudencia`() {
        // Descartar un mensaje por un codigo que no sabemos interpretar es la peor opcion.
        assertTrue(
            MapeoDeRespuestas.interpretarEnvio(418, null) is ResultadoEnvio.ErrorTransitorio
        )
    }

    // ── Permanentes ───────────────────────────────────────────────────────────

    @Test
    fun `un chat inexistente es permanente`() {
        val cuerpo =
            """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}"""

        val resultado = MapeoDeRespuestas.interpretarEnvio(400, cuerpo)

        assertTrue(resultado is ResultadoEnvio.ErrorPermanente)
        assertTrue(
            "el motivo debe llevar la explicacion de Telegram",
            (resultado as ResultadoEnvio.ErrorPermanente).motivo.contains("chat not found")
        )
    }

    @Test
    fun `un bot bloqueado por el destinatario es permanente`() {
        val cuerpo =
            """{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}"""

        assertTrue(
            MapeoDeRespuestas.interpretarEnvio(403, cuerpo) is ResultadoEnvio.ErrorPermanente
        )
    }

    @Test
    fun `un token invalido es permanente`() {
        val cuerpo = """{"ok":false,"error_code":401,"description":"Unauthorized"}"""

        assertTrue(
            MapeoDeRespuestas.interpretarEnvio(401, cuerpo) is ResultadoEnvio.ErrorPermanente
        )
    }

    // ── Fallo de credencial, que es otra cosa ─────────────────────────────────

    /**
     * Un 404 en el Bot API no es «el chat no existe»: el token va **dentro de la URL**, así que
     * la ruta entera depende de él y un «no existe» ahí significa que el token no existe.
     */
    @Test
    fun `401 y 404 se reconocen como fallo de credencial`() {
        assertTrue(MapeoDeRespuestas.esFalloDeCredencial(401))
        assertTrue(MapeoDeRespuestas.esFalloDeCredencial(404))
    }

    @Test
    fun `un 400 o un 403 no son fallo de credencial`() {
        // El token esta bien; el problema es este destino concreto. Avisar de que hay que
        // revisar el token mandaria a mirar donde no es.
        assertFalse(MapeoDeRespuestas.esFalloDeCredencial(400))
        assertFalse(MapeoDeRespuestas.esFalloDeCredencial(403))
        assertFalse(MapeoDeRespuestas.esFalloDeCredencial(429))
        assertFalse(MapeoDeRespuestas.esFalloDeCredencial(200))
    }

    // ── Troceado por longitud ─────────────────────────────────────────────────

    @Test
    fun `un texto que cabe no se trocea`() {
        val texto = "Tu codigo es 4821"

        assertEquals(listOf(texto), MapeoDeRespuestas.trocear(texto))
    }

    @Test
    fun `un texto justo en el limite no se trocea`() {
        val texto = "a".repeat(MapeoDeRespuestas.LIMITE_CARACTERES)

        assertEquals(1, MapeoDeRespuestas.trocear(texto).size)
    }

    @Test
    fun `un texto mas largo que el limite se parte y no pierde nada`() {
        val texto = "a".repeat(MapeoDeRespuestas.LIMITE_CARACTERES + 10)

        val trozos = MapeoDeRespuestas.trocear(texto)

        assertEquals(2, trozos.size)
        assertEquals(texto.length, trozos.sumOf { it.length })
        trozos.forEach {
            assertTrue(it.length <= MapeoDeRespuestas.LIMITE_CARACTERES)
        }
    }

    @Test
    fun `el corte prefiere un salto de linea`() {
        val primera = "a".repeat(30)
        val segunda = "b".repeat(30)

        val trozos = MapeoDeRespuestas.trocear("$primera\n$segunda", limite = 40)

        assertEquals(listOf(primera, segunda), trozos)
    }

    @Test
    fun `sin saltos de linea el corte cae en un espacio y no parte palabras`() {
        val trozos = MapeoDeRespuestas.trocear("palabra ".repeat(10).trim(), limite = 20)

        trozos.forEach { trozo ->
            assertTrue("no debe partir una palabra: '$trozo'", trozo.split(" ").all { it == "palabra" })
        }
        assertEquals(10, trozos.sumOf { it.split(" ").size })
    }

    @Test
    fun `una palabra mas larga que el limite se parte porque no hay alternativa`() {
        val texto = "x".repeat(25)

        val trozos = MapeoDeRespuestas.trocear(texto, limite = 10)

        assertEquals(texto, trozos.joinToString(""))
        trozos.forEach { assertTrue(it.length <= 10) }
    }
}
