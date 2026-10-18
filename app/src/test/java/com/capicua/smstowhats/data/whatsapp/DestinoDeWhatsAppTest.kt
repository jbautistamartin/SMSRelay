// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobertura de la validación del destino.
 *
 * Lo que de verdad se prueba aquí es la decisión de exigir el `+`: es lo que convierte un caso
 * ambiguo —nueve dígitos que podrían ser un móvil nacional o un internacional completo— en dos
 * casos distinguibles, y por tanto en dos mensajes de error distintos.
 */
class DestinoDeWhatsAppTest {

    @Test
    fun `un numero internacional completo vale`() {
        assertTrue(DestinoDeWhatsApp.pareceValido("+34600112233"))
        assertTrue(DestinoDeWhatsApp.pareceValido("+1234567890"))
        assertTrue(DestinoDeWhatsApp.pareceValido("+447911123456"))
    }

    @Test
    fun `los separadores con los que la gente escribe un telefono se admiten`() {
        // Nadie escribe un telefono como lo quiere una API. Normalizar es mas util que
        // rechazar.
        assertTrue(DestinoDeWhatsApp.pareceValido("+34 600 11 22 33"))
        assertTrue(DestinoDeWhatsApp.pareceValido("+34-600-112-233"))
        assertTrue(DestinoDeWhatsApp.pareceValido("  +34 (600) 112233  "))
    }

    @Test
    fun `normalizar deja el mas y los digitos, y nada mas`() {
        assertEquals("+34600112233", DestinoDeWhatsApp.normalizar("+34 600 11 22 33"))
        assertEquals("+34600112233", DestinoDeWhatsApp.normalizar("  +34-600-112-233 "))
        assertEquals("+34600112233", DestinoDeWhatsApp.normalizar("+34 (600) 112.233"))
    }

    @Test
    fun `un mas que no este al principio no cuenta`() {
        assertEquals("34600112233", DestinoDeWhatsApp.normalizar("34+600112233"))
        assertFalse(DestinoDeWhatsApp.pareceValido("34+600112233"))
    }

    @Test
    fun `un numero nacional no vale y se reconoce como falta de prefijo`() {
        // Este es el caso para el que existe la clase: el error que va a cometer todo el mundo.
        listOf("600112233", "600 11 22 33", "91 123 45 67").forEach { nacional ->
            assertFalse("no deberia valer: '$nacional'", DestinoDeWhatsApp.pareceValido(nacional))
            assertTrue(
                "deberia detectarse la falta de prefijo: '$nacional'",
                DestinoDeWhatsApp.faltaPrefijo(nacional)
            )
        }
    }

    @Test
    fun `un numero valido no se acusa de faltarle el prefijo`() {
        assertFalse(DestinoDeWhatsApp.faltaPrefijo("+34600112233"))
        assertFalse(DestinoDeWhatsApp.faltaPrefijo("+34 600 112 233"))
    }

    @Test
    fun `lo que no es un telefono no vale ni le falta el prefijo`() {
        // Aqui el mensaje correcto es «no parece un telefono», no «falta el prefijo».
        listOf("", "   ", "@mi_canal", "un nombre", "-1001234567890").forEach { malo ->
            assertFalse("no deberia valer: '$malo'", DestinoDeWhatsApp.pareceValido(malo))
            assertFalse("no le falta el prefijo: '$malo'", DestinoDeWhatsApp.faltaPrefijo(malo))
        }
    }

    @Test
    fun `un chat_id de Telegram ya no vale`() {
        // La regresion que importa: un fichero de configuracion del formato 4 trae estos
        // destinos, y tienen que fallar la validacion en lugar de colarse.
        assertFalse(DestinoDeWhatsApp.pareceValido("-1001234567890"))
        assertFalse(DestinoDeWhatsApp.pareceValido("@mi_canal"))
        assertFalse(DestinoDeWhatsApp.pareceValido("123456789"))
    }

    @Test
    fun `el formato E164 acota la longitud por los dos lados`() {
        assertFalse("demasiado corto", DestinoDeWhatsApp.pareceValido("+341234"))
        assertTrue("el minimo de ocho digitos", DestinoDeWhatsApp.pareceValido("+34123456"))
        assertTrue("el maximo de quince", DestinoDeWhatsApp.pareceValido("+341234567890123"))
        assertFalse("pasarse de quince", DestinoDeWhatsApp.pareceValido("+3412345678901234"))
    }

    @Test
    fun `un prefijo no puede empezar por cero`() {
        assertFalse(DestinoDeWhatsApp.pareceValido("+0034600112233"))
    }

    @Test
    fun `lo que va al campo to de la API son solo digitos`() {
        assertEquals("34600112233", DestinoDeWhatsApp.aDestinatarioApi("+34600112233"))
        assertEquals("34600112233", DestinoDeWhatsApp.aDestinatarioApi("+34 600 11 22 33"))
    }
}
