// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.api

import com.capicua.smsrelay.domain.transport.ResultadoEnvio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests del reparto de respuestas del canal API, el de SMSGateway. */
class MapeoDeRespuestasTest {

    @Test
    fun `cualquier 2xx es un envio correcto`() {
        listOf(200, 201, 202, 204, 299).forEach {
            assertEquals("HTTP $it", ResultadoEnvio.Enviado, MapeoDeRespuestas.interpretarEnvio(it))
        }
    }

    @Test
    fun `un 4xx distinto de 429 es permanente`() {
        listOf(400, 401, 403, 404, 405, 414, 422).forEach {
            assertTrue("HTTP $it", MapeoDeRespuestas.interpretarEnvio(it) is ResultadoEnvio.ErrorPermanente)
        }
    }

    @Test
    fun `un 429 es transitorio`() {
        assertTrue(MapeoDeRespuestas.interpretarEnvio(429) is ResultadoEnvio.ErrorTransitorio)
    }

    @Test
    fun `un 5xx es transitorio`() {
        listOf(500, 502, 503, 504).forEach {
            assertTrue("HTTP $it", MapeoDeRespuestas.interpretarEnvio(it) is ResultadoEnvio.ErrorTransitorio)
        }
    }

    @Test
    fun `una redireccion es permanente y lo explica`() {
        val resultado = MapeoDeRespuestas.interpretarEnvio(301)

        assertTrue(resultado is ResultadoEnvio.ErrorPermanente)
        assertTrue((resultado as ResultadoEnvio.ErrorPermanente).motivo.contains("redirige"))
    }

    @Test
    fun `un codigo desconocido se reintenta por prudencia`() {
        assertTrue(MapeoDeRespuestas.interpretarEnvio(-1) is ResultadoEnvio.ErrorTransitorio)
        assertTrue(MapeoDeRespuestas.interpretarEnvio(100) is ResultadoEnvio.ErrorTransitorio)
    }

    @Test
    fun `el motivo lleva el codigo HTTP`() {
        val resultado = MapeoDeRespuestas.interpretarEnvio(404) as ResultadoEnvio.ErrorPermanente

        assertTrue(resultado.motivo.startsWith("HTTP 404"))
    }
}
