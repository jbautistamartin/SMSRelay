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
 * Cobertura de las dos credenciales de la Cloud API.
 *
 * Van juntas en un fichero porque contestan la misma pregunta —«¿esto tiene forma de
 * credencial?»— y porque lo que importa de las dos es lo mismo: que **vacío sea válido**, que es
 * el estado de una instalación nueva, y que la validación no sea tan estricta como para rechazar
 * algo legítimo.
 */
class CredencialesTest {

    // ── Token de acceso ───────────────────────────────────────────────────────

    @Test
    fun `un token de usuario de sistema vale`() {
        val token = "EAA" + "a1B2c3D4e5_F6g7-H8i9".repeat(5)
        assertTrue(TokenDeAcceso.pareceValido(token))
    }

    @Test
    fun `un token vacio vale porque significa sin configurar`() {
        assertTrue(TokenDeAcceso.pareceValido(""))
        assertTrue(TokenDeAcceso.pareceValido("   "))
    }

    @Test
    fun `normalizar el token recorta espacios y saltos de linea`() {
        val token = "EAA" + "x".repeat(50)
        assertEquals(token, TokenDeAcceso.normalizar("  $token \n"))
        assertTrue(TokenDeAcceso.pareceValido("  $token \n"))
    }

    @Test
    fun `lo que no puede ser un token se rechaza`() {
        listOf(
            "corto",
            "EAAconespacio " + "x".repeat(40),
            "token con espacios dentro que es largo pero no sirve",
            "x".repeat(39)
        ).forEach { malo ->
            assertFalse("deberia rechazarse: '$malo'", TokenDeAcceso.pareceValido(malo))
        }
    }

    @Test
    fun `un token del largo minimo vale`() {
        assertTrue(TokenDeAcceso.pareceValido("x".repeat(40)))
    }

    // ── Id del numero ─────────────────────────────────────────────────────────

    @Test
    fun `un id de numero vale`() {
        assertTrue(IdNumeroTelefono.pareceValido("123456789012345"))
        assertTrue(IdNumeroTelefono.pareceValido("1234567890"))
    }

    @Test
    fun `un id vacio vale porque significa sin configurar`() {
        assertTrue(IdNumeroTelefono.pareceValido(""))
        assertTrue(IdNumeroTelefono.pareceValido("  "))
    }

    @Test
    fun `normalizar el id recorta los espacios`() {
        assertEquals("123456789012345", IdNumeroTelefono.normalizar("  123456789012345 "))
        assertTrue(IdNumeroTelefono.pareceValido("  123456789012345 "))
    }

    @Test
    fun `un telefono en el campo del id se reconoce como tal`() {
        // El error de configuracion numero uno de la Cloud API: la consola ensena el id y el
        // telefono uno debajo del otro, y el instinto es copiar el que se reconoce.
        listOf("+34600112233", "+34 600 11 22 33", "600-112-233").forEach { telefono ->
            assertFalse("no deberia valer: '$telefono'", IdNumeroTelefono.pareceValido(telefono))
            assertTrue(
                "deberia detectarse como telefono: '$telefono'",
                IdNumeroTelefono.pareceTelefono(telefono)
            )
        }
    }

    @Test
    fun `un id valido no se acusa de ser un telefono`() {
        assertFalse(IdNumeroTelefono.pareceTelefono("123456789012345"))
    }

    @Test
    fun `un telefono sin separadores pasa por id, y eso esta aceptado`() {
        // Es la ambiguedad irreducible: «600112233» son nueve digitos y no hay forma de saber
        // que pretendian ser. Meta contestara 100 y el Panel mandara a mirar aqui.
        assertTrue(IdNumeroTelefono.pareceValido("600112233600"))
        assertFalse(IdNumeroTelefono.pareceTelefono("600112233600"))
    }

    @Test
    fun `un id con letras o demasiado corto se rechaza`() {
        listOf("12345", "abc123456789012", "1234567890123456789012").forEach { malo ->
            assertFalse("deberia rechazarse: '$malo'", IdNumeroTelefono.pareceValido(malo))
        }
    }
}
