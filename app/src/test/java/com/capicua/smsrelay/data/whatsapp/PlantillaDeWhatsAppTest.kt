// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobertura del contrato de la plantilla y de las dos transformaciones que lo sostienen.
 *
 * Lo que se prueba aquí no es cosmético: un salto de línea que se cuele en el parámetro devuelve
 * un `132007` y cierra el reenvío como fallido permanente. Es exactamente el tipo de fallo que
 * no se puede descubrir sin red y sin cuenta, y por eso [PlantillaDeWhatsApp] es puro.
 */
class PlantillaDeWhatsAppTest {

    // ── Lo que Meta prohíbe en un parámetro ───────────────────────────────────

    @Test
    fun `aplanar no deja ningun salto de linea`() {
        val smsMultilinea = "BANCO\nTu codigo es 4821\nNo lo compartas"
        val plano = PlantillaDeWhatsApp.aplanar(smsMultilinea)

        assertFalse(plano.contains('\n'))
        assertFalse(plano.contains('\r'))
    }

    @Test
    fun `aplanar tambien aplana el retorno de carro`() {
        // Un SMS no suele traer CRLF, pero una plantilla escrita en cualquier teclado si. Una
        // reescritura de la regex perdio el retorno de carro y nada lo habria notado, asi que
        // ahora tiene su propio test.
        assertEquals(
            "Primera · Segunda",
            PlantillaDeWhatsApp.aplanar("Primera\r\nSegunda")
        )
        assertEquals("a · b", PlantillaDeWhatsApp.aplanar("a\rb"))
    }

    @Test
    fun `aplanar no deja ningun tabulador`() {
        val plano = PlantillaDeWhatsApp.aplanar("Importe:\t42,50 EUR")

        assertFalse(plano.contains('\t'))
        assertEquals("Importe: 42,50 EUR", plano)
    }

    @Test
    fun `aplanar no deja mas de un espacio seguido`() {
        // Meta rechaza mas de cuatro seguidos. Se colapsan todos, que es mas simple y mas
        // seguro que contar hasta cuatro.
        val plano = PlantillaDeWhatsApp.aplanar("Hola          mundo")

        assertEquals("Hola mundo", plano)
        assertFalse(plano.contains("  "))
    }

    @Test
    fun `aplanar sustituye el salto de linea por un separador visible`() {
        // Sin separador, dos lineas se pegarian en una palabra: «4821No lo compartas».
        assertEquals(
            "BANCO · Tu codigo es 4821",
            PlantillaDeWhatsApp.aplanar("BANCO\nTu codigo es 4821")
        )
    }

    @Test
    fun `los saltos consecutivos se colapsan en un solo separador`() {
        // Un SMS con una linea en blanco en medio no debe salir con « ·  · » dentro.
        assertEquals(
            "Primera · Segunda",
            PlantillaDeWhatsApp.aplanar("Primera\n\n\nSegunda")
        )
    }

    @Test
    fun `los espacios alrededor de un salto no duplican separadores`() {
        assertEquals(
            "Primera · Segunda",
            PlantillaDeWhatsApp.aplanar("Primera   \n   Segunda")
        )
    }

    @Test
    fun `aplanar recorta los extremos`() {
        assertEquals("Hola", PlantillaDeWhatsApp.aplanar("\n  Hola  \n"))
    }

    @Test
    fun `aplanar no toca un texto que ya es de una linea`() {
        val texto = "Tu codigo es 4821"
        assertEquals(texto, PlantillaDeWhatsApp.aplanar(texto))
    }

    @Test
    fun `aplanar no pierde contenido`() {
        val original = "BANCO\nImporte:\t42,50\n\nSaldo: 118,00"
        val plano = PlantillaDeWhatsApp.aplanar(original)

        listOf("BANCO", "Importe:", "42,50", "Saldo:", "118,00").forEach {
            assertTrue("deberia conservar '$it'", plano.contains(it))
        }
    }

    // ── El contrato ───────────────────────────────────────────────────────────

    @Test
    fun `el cuerpo recomendado no empieza ni acaba con la variable`() {
        // Meta no lo admite, y es la restriccion que obliga a poner texto fijo a los dos lados.
        assertFalse(
            PlantillaDeWhatsApp.CUERPO_RECOMENDADO.startsWith(PlantillaDeWhatsApp.MARCADOR)
        )
        assertFalse(
            PlantillaDeWhatsApp.CUERPO_RECOMENDADO.endsWith(PlantillaDeWhatsApp.MARCADOR)
        )
    }

    @Test
    fun `el cuerpo recomendado tiene una sola variable`() {
        val apariciones = PlantillaDeWhatsApp.CUERPO_RECOMENDADO
            .split(PlantillaDeWhatsApp.MARCADOR)
            .size - 1
        assertEquals(1, apariciones)
    }

    @Test
    fun `el cuerpo recomendado si lleva saltos de linea`() {
        // Es el reparto que hace que todo funcione: la maquetacion va en la plantilla aprobada,
        // y el contenido entra aplanado en la variable.
        assertTrue(PlantillaDeWhatsApp.CUERPO_RECOMENDADO.contains('\n'))
    }

    @Test
    fun `el presupuesto deja sitio al texto fijo dentro del limite de Meta`() {
        val textoFijo =
            PlantillaDeWhatsApp.CUERPO_RECOMENDADO.length - PlantillaDeWhatsApp.MARCADOR.length

        assertTrue(
            "el presupuesto mas el texto fijo no puede pasar de 1024",
            PlantillaDeWhatsApp.PRESUPUESTO + textoFijo <= PlantillaDeWhatsApp.LIMITE_RENDERIZADO
        )
        assertTrue("y tiene que ser util", PlantillaDeWhatsApp.PRESUPUESTO > 900)
    }

    @Test
    fun `el presupuesto vale exactamente 980`() {
        // El literal está escrito en CLAUDE.md, en docs/ARCHITECTURE.md y en la guía de la
        // cuenta, y ya se desvió una vez (992 en los documentos, 978 en el código) porque ningún
        // test lo afirmaba. Si este falla porque cambió CUERPO_RECOMENDADO, cambia el número
        // aquí y en esos tres documentos a la vez.
        assertEquals(980, PlantillaDeWhatsApp.PRESUPUESTO)
    }

    @Test
    fun `el presupuesto es mucho menor que el del texto libre`() {
        // La otra cara del precio de poder escribir fuera de la ventana de 24 h.
        assertTrue(PlantillaDeWhatsApp.PRESUPUESTO < MapeoDeRespuestas.LIMITE_CARACTERES / 4)
    }

    // ── Preparar los parámetros ───────────────────────────────────────────────

    @Test
    fun `un sms normal da un solo parametro, ya aplanado`() {
        val parametros = PlantillaDeWhatsApp.prepararParametros("BANCO\nTu codigo es 4821")

        assertEquals(1, parametros.size)
        assertEquals("BANCO · Tu codigo es 4821", parametros.first())
    }

    @Test
    fun `un texto que no cabe en la plantilla se parte sin perder nada`() {
        val largo = "palabra ".repeat(200).trim()
        val parametros = PlantillaDeWhatsApp.prepararParametros(largo)

        assertTrue("deberia partirse", parametros.size > 1)
        parametros.forEach {
            assertTrue(
                "cada trozo tiene que caber en el presupuesto",
                it.length <= PlantillaDeWhatsApp.PRESUPUESTO
            )
        }
    }

    @Test
    fun `un texto que solo tiene espacios y saltos no da ningun parametro`() {
        // Un parametro vacio es un 131009. Devolver lista vacia deja que el transporte lo
        // explique con sus palabras en lugar de mandar la peticion y que falle.
        assertTrue(PlantillaDeWhatsApp.prepararParametros("   \n\n  \t ").isEmpty())
        assertTrue(PlantillaDeWhatsApp.prepararParametros("").isEmpty())
    }

    @Test
    fun `ningun parametro preparado lleva lo que Meta prohibe`() {
        val sms = "BANCO\n\nImporte:\t42,50 EUR\n     Saldo: 118,00\n" + "relleno ".repeat(300)

        PlantillaDeWhatsApp.prepararParametros(sms).forEach { parametro ->
            assertFalse("sin saltos", parametro.contains('\n'))
            assertFalse("sin tabuladores", parametro.contains('\t'))
            assertFalse("sin espacios seguidos", parametro.contains("     "))
            assertTrue("sin vacios", parametro.isNotEmpty())
        }
    }
}
