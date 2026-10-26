// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [PlantillaDeUrl], la pieza de seguridad del canal API.
 *
 * Lo que de verdad fijan es que un SMS ajeno no puede elegir a qué servidor se llama ni cambiar
 * la forma de la URL: los marcadores solo valen después del servidor, y cada valor va codificado
 * entero.
 */
class PlantillaDeUrlTest {

    private fun resolver(plantilla: String, mensaje: String = "hola", telefono: String = "+34600112233") =
        PlantillaDeUrl.resolver(plantilla, mensaje, telefono, fechaIso = "2026-10-25T10:00:00Z")

    // ── Validación ────────────────────────────────────────────────────────────

    @Test
    fun `una plantilla con los marcadores en la query es valida`() {
        assertNull(
            PlantillaDeUrl.validar("https://api.ejemplo.com/sms?de={telefono}&txt={mensaje}&f={fecha}")
        )
    }

    @Test
    fun `los marcadores pueden ir en la ruta`() {
        assertNull(PlantillaDeUrl.validar("http://192.168.1.10:8080/avisos/{telefono}/{mensaje}"))
    }

    @Test
    fun `un marcador en el servidor se rechaza`() {
        assertNotNull(PlantillaDeUrl.validar("https://{mensaje}.ejemplo.com/x?t={mensaje}"))
        assertNotNull(PlantillaDeUrl.validar("https://{telefono}/x?t={mensaje}"))
    }

    @Test
    fun `un marcador en el puerto o en el usuario se rechaza`() {
        assertNotNull(PlantillaDeUrl.validar("https://ejemplo.com:{fecha}/x?t={mensaje}"))
        assertNotNull(PlantillaDeUrl.validar("https://{telefono}@ejemplo.com/x?t={mensaje}"))
    }

    @Test
    fun `una URL sin esquema http se rechaza`() {
        assertNotNull(PlantillaDeUrl.validar("ftp://ejemplo.com/?t={mensaje}"))
        assertNotNull(PlantillaDeUrl.validar("ejemplo.com/?t={mensaje}"))
        assertNotNull(PlantillaDeUrl.validar("{mensaje}"))
    }

    @Test
    fun `una URL sin servidor se rechaza`() {
        assertNotNull(PlantillaDeUrl.validar("https:///?t={mensaje}"))
    }

    @Test
    fun `una plantilla sin mensaje se rechaza`() {
        assertNotNull(PlantillaDeUrl.validar("https://ejemplo.com/?de={telefono}"))
    }

    @Test
    fun `una plantilla vacia se rechaza`() {
        assertNotNull(PlantillaDeUrl.validar("   "))
        assertFalse(PlantillaDeUrl.esValida(""))
    }

    @Test
    fun `el esquema no distingue mayusculas`() {
        assertTrue(PlantillaDeUrl.esValida("HTTPS://ejemplo.com/?t={mensaje}"))
    }

    // ── Resolución ────────────────────────────────────────────────────────────

    @Test
    fun `cada marcador se sustituye codificado`() {
        val url = resolver("https://e.com/?de={telefono}&t={mensaje}&f={fecha}", mensaje = "Tu código: 4821")

        assertEquals(
            "https://e.com/?de=%2B34600112233&t=Tu%20c%C3%B3digo%3A%204821&f=2026-10-25T10%3A00%3A00Z",
            url
        )
    }

    @Test
    fun `un SMS no puede añadir parametros ni cambiar la ruta`() {
        val url = resolver("https://e.com/api?t={mensaje}", mensaje = "x&admin=1/../#frag?y")

        assertEquals("https://e.com/api?t=x%26admin%3D1%2F..%2F%23frag%3Fy", url)
        assertEquals("https://e.com/api", PlantillaDeUrl.sinQuery(url))
    }

    @Test
    fun `unas llaves en el mensaje no forman un marcador`() {
        val url = resolver("https://e.com/?t={mensaje}&de={telefono}", mensaje = "{telefono}")

        assertEquals("https://e.com/?t=%7Btelefono%7D&de=%2B34600112233", url)
    }

    @Test
    fun `un marcador ausente simplemente no se usa`() {
        assertEquals("https://e.com/?t=hola", resolver("https://e.com/?t={mensaje}"))
    }

    @Test
    fun `sinQuery quita la query y el fragmento`() {
        assertEquals("https://e.com/a/b", PlantillaDeUrl.sinQuery("https://e.com/a/b?clave=secreta#x"))
    }
}
