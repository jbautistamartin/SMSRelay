// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.whatsapp

import com.capicua.smsrelay.domain.transport.ResultadoEnvio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobertura del mapeo de respuestas de la Cloud API.
 *
 * Es la pieza del transporte que se puede probar sin red, y la que más cuesta si se equivoca:
 * clasificar un error permanente como transitorio gasta diez reintentos, y al contrario pierde
 * un mensaje que habría llegado.
 *
 * El eje de estas pruebas es el que distingue a este mapeo del de Telegram: **cuatro averías
 * distintas comparten el mismo `400`**, así que varios casos se diferencian solo en el
 * `error.code` del cuerpo.
 */
class MapeoDeRespuestasTest {

    // ── Entrega aceptada ──────────────────────────────────────────────────────

    @Test
    fun `un 200 con messages es un envio aceptado`() {
        val cuerpo = """
            {"messaging_product":"whatsapp",
             "contacts":[{"input":"34600112233","wa_id":"34600112233"}],
             "messages":[{"id":"wamid.HBgLMzQ2MDAxMTIyMzMVAgARGBI5","message_status":"accepted"}]}
        """.trimIndent()
        assertEquals(ResultadoEnvio.Enviado, MapeoDeRespuestas.interpretarEnvio(200, cuerpo))
    }

    @Test
    fun `un 200 sin cuerpo legible tambien es aceptado`() {
        // Si Meta contestó 2xx y no se queja, tiene el mensaje. Exigir el wamid convertiría un
        // cuerpo ilegible en un reenvío duplicado.
        assertEquals(ResultadoEnvio.Enviado, MapeoDeRespuestas.interpretarEnvio(200, null))
        assertEquals(ResultadoEnvio.Enviado, MapeoDeRespuestas.interpretarEnvio(200, "no es json"))
    }

    @Test
    fun `un 200 que trae bloque error no es un envio aceptado`() {
        val resultado = MapeoDeRespuestas.interpretarEnvio(
            200,
            """{"error":{"message":"algo raro","code":131000}}"""
        )
        assertTrue(resultado is ResultadoEnvio.ErrorTransitorio)
    }

    // ── La ventana de 24 h ────────────────────────────────────────────────────

    @Test
    fun `el 131047 es permanente y el motivo explica que hace falta una plantilla`() {
        val cuerpo = """
            {"error":{"message":"(#131047) Re-engagement message",
                      "code":131047,
                      "error_data":{"details":"More than 24 hours have passed"}}}
        """.trimIndent()
        val resultado = MapeoDeRespuestas.interpretarEnvio(400, cuerpo)

        assertTrue(resultado is ResultadoEnvio.ErrorPermanente)
        // Lo que tiene que quedar en el registro no es «HTTP 400», es qué hacer.
        assertTrue((resultado as ResultadoEnvio.ErrorPermanente).motivo.contains("plantilla"))
        assertTrue(resultado.motivo.contains("131047"))
    }

    // ── Los cuatro 400 distintos ──────────────────────────────────────────────

    @Test
    fun `los cuatrocientos con codigo conocido se cierran como permanentes`() {
        val codigos = listOf(
            131026, // el destinatario no tiene WhatsApp
            131021, // destinatario igual al emisor
            132000, // el numero de parametros no cuadra
            132001, // la plantilla no existe
            132007, // el parametro viola el formato
            132012, // formato de parametro invalido
            131008, // falta un parametro obligatorio
            368     // numero bloqueado por politicas
        )
        codigos.forEach { codigo ->
            val resultado = MapeoDeRespuestas.interpretarEnvio(
                400, """{"error":{"message":"x","code":$codigo}}"""
            )
            assertTrue(
                "el codigo $codigo deberia ser permanente",
                resultado is ResultadoEnvio.ErrorPermanente
            )
        }
    }

    @Test
    fun `el mensaje de Meta se incorpora al motivo tal cual`() {
        val cuerpo = """
            {"error":{"message":"(#131026) Unable to deliver message","code":131026}}
        """.trimIndent()
        val resultado = MapeoDeRespuestas.interpretarEnvio(400, cuerpo)

        val motivo = (resultado as ResultadoEnvio.ErrorPermanente).motivo
        assertTrue(motivo.contains("Unable to deliver message"))
        assertTrue(motivo.contains("131026"))
    }

    @Test
    fun `sin message se usa el details de error_data`() {
        val cuerpo = """
            {"error":{"code":131026,"error_data":{"details":"no es usuario de WhatsApp"}}}
        """.trimIndent()
        val resultado = MapeoDeRespuestas.interpretarEnvio(400, cuerpo)

        assertTrue(
            (resultado as ResultadoEnvio.ErrorPermanente).motivo
                .contains("no es usuario de WhatsApp")
        )
    }

    // ── Credenciales ──────────────────────────────────────────────────────────

    @Test
    fun `los codigos de token son fallo de credencial`() {
        listOf(190, 0, 3).forEach { codigo ->
            val cuerpo = """{"error":{"message":"token","code":$codigo}}"""
            assertTrue(
                "el codigo $codigo deberia ser fallo de credencial",
                MapeoDeRespuestas.esFalloDeCredencial(401, cuerpo)
            )
            assertTrue(MapeoDeRespuestas.interpretarEnvio(401, cuerpo) is ResultadoEnvio.ErrorPermanente)
        }
    }

    @Test
    fun `el 100 es fallo de credencial porque lo que falla es el id del numero`() {
        // Llega con un 400 y un texto generico. Es el equivalente exacto del 404 de Telegram:
        // lo que no existe es la ruta, y la ruta la forma la credencial.
        val cuerpo = """
            {"error":{"message":"Unsupported post request. Object with ID '123' does not exist",
                      "code":100,"error_subcode":33}}
        """.trimIndent()
        assertTrue(MapeoDeRespuestas.esFalloDeCredencial(400, cuerpo))
        assertTrue(MapeoDeRespuestas.interpretarEnvio(400, cuerpo) is ResultadoEnvio.ErrorPermanente)
    }

    @Test
    fun `un problema del destinatario no es fallo de credencial`() {
        // Si lo fuera, el Panel mandaria a mirar los ajustes por un numero mal escrito en una
        // regla, que es el sitio equivocado.
        listOf(131026, 131021, 131047, 130429, 132012).forEach { codigo ->
            assertFalse(
                "el codigo $codigo no deberia ser fallo de credencial",
                MapeoDeRespuestas.esFalloDeCredencial(400, """{"error":{"code":$codigo}}""")
            )
        }
    }

    @Test
    fun `sin cuerpo legible el 401 y el 403 siguen siendo fallo de credencial`() {
        assertTrue(MapeoDeRespuestas.esFalloDeCredencial(401, null))
        assertTrue(MapeoDeRespuestas.esFalloDeCredencial(403, null))
        assertFalse(MapeoDeRespuestas.esFalloDeCredencial(400, null))
        assertFalse(MapeoDeRespuestas.esFalloDeCredencial(200, null))
    }

    // ── Limites de caudal ─────────────────────────────────────────────────────

    @Test
    fun `los codigos de limite se reintentan sin espera sugerida`() {
        // La diferencia practica con el 429 de Telegram: Meta no manda retry_after, asi que no
        // hay espera que pasar al worker y se aplica el backoff de siempre.
        listOf(80007, 130429, 131048, 131056, 133016).forEach { codigo ->
            val resultado = MapeoDeRespuestas.interpretarEnvio(
                429, """{"error":{"message":"rate","code":$codigo}}"""
            )
            assertTrue(
                "el codigo $codigo deberia ser transitorio",
                resultado is ResultadoEnvio.ErrorTransitorio
            )
            assertNull((resultado as ResultadoEnvio.ErrorTransitorio).esperaSegundos)
        }
    }

    @Test
    fun `un 429 sin codigo reconocible tambien se reintenta`() {
        val resultado = MapeoDeRespuestas.interpretarEnvio(429, null)
        assertTrue(resultado is ResultadoEnvio.ErrorTransitorio)
    }

    // ── Averias de Meta ───────────────────────────────────────────────────────

    @Test
    fun `los cinco centenares se reintentan`() {
        listOf(500, 502, 503, 504).forEach { codigo ->
            assertTrue(
                "el HTTP $codigo deberia ser transitorio",
                MapeoDeRespuestas.interpretarEnvio(codigo, null) is ResultadoEnvio.ErrorTransitorio
            )
        }
    }

    @Test
    fun `los codigos de averia de Meta se reintentan aunque lleguen con un 400`() {
        listOf(1, 2, 131000, 133004).forEach { codigo ->
            val resultado = MapeoDeRespuestas.interpretarEnvio(
                400, """{"error":{"code":$codigo}}"""
            )
            assertTrue(
                "el codigo $codigo deberia ser transitorio",
                resultado is ResultadoEnvio.ErrorTransitorio
            )
        }
    }

    // ── Lo desconocido ────────────────────────────────────────────────────────

    @Test
    fun `un cuatrocientos desconocido se cierra como permanente`() {
        // El reintento manda los mismos bytes a la misma URL: un «tu peticion esta mal» no
        // cambia de respuesta por esperar. El motivo lleva el codigo para poder buscarlo.
        val resultado = MapeoDeRespuestas.interpretarEnvio(
            400, """{"error":{"message":"algo nuevo","code":999999}}"""
        )
        assertTrue(resultado is ResultadoEnvio.ErrorPermanente)
        assertTrue((resultado as ResultadoEnvio.ErrorPermanente).motivo.contains("999999"))
    }

    @Test
    fun `un codigo http fuera de rango se reintenta por prudencia`() {
        assertTrue(
            MapeoDeRespuestas.interpretarEnvio(302, null) is ResultadoEnvio.ErrorTransitorio
        )
    }

    // ── Troceado ──────────────────────────────────────────────────────────────

    @Test
    fun `un texto corto no se trocea`() {
        val texto = "Tu codigo es 4821"
        assertEquals(listOf(texto), MapeoDeRespuestas.trocear(texto))
    }

    @Test
    fun `un texto justo en el limite no se trocea`() {
        val texto = "a".repeat(MapeoDeRespuestas.LIMITE_CARACTERES)
        assertEquals(1, MapeoDeRespuestas.trocear(texto).size)
    }

    @Test
    fun `un texto que se pasa del limite se trocea sin perder nada`() {
        val texto = "a".repeat(MapeoDeRespuestas.LIMITE_CARACTERES + 10)
        val trozos = MapeoDeRespuestas.trocear(texto)

        assertEquals(2, trozos.size)
        trozos.forEach { assertTrue(it.length <= MapeoDeRespuestas.LIMITE_CARACTERES) }
        assertEquals(texto.length, trozos.sumOf { it.length })
    }

    @Test
    fun `trocear corta por el salto de linea`() {
        val primera = "primera linea corta"
        val segunda = "segunda linea que ya no cabe en el limite"
        val trozos = MapeoDeRespuestas.trocear("$primera\n$segunda", limite = 40)

        assertEquals(primera, trozos.first())
    }

    @Test
    fun `trocear corta por el espacio si no hay salto de linea`() {
        val trozos = MapeoDeRespuestas.trocear("palabra ".repeat(10).trim(), limite = 20)

        assertTrue(trozos.size > 1)
        trozos.forEach { assertFalse("no deberia partir palabras", it.contains("palabr\n")) }
        trozos.forEach { assertTrue(it.length <= 20) }
    }

    @Test
    fun `trocear parte una palabra solo si no hay donde cortar`() {
        val texto = "a".repeat(25)
        val trozos = MapeoDeRespuestas.trocear(texto, limite = 10)

        assertEquals(3, trozos.size)
        assertEquals(25, trozos.sumOf { it.length })
    }

    @Test
    fun `el limite de caracteres es el mismo que tenia Telegram`() {
        // No es casualidad ni es una copia: el text-body de la Cloud API admite exactamente el
        // mismo numero. Es lo que permitio que trocear sobreviviera literal al cambio de medio.
        assertEquals(4096, MapeoDeRespuestas.LIMITE_CARACTERES)
    }
}
