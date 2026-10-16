// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la comprobación de forma del token.
 *
 * La comprobación es laxa a propósito: rechaza lo que no puede ser un token y no pretende
 * certificar lo que sí. Estos tests fijan ese equilibrio, porque la tentación al leerlo luego
 * será apretarlo, y un validador que rechace un token legítimo deja la aplicación inservible
 * con un mensaje que dice que el token está mal cuando está bien.
 */
class TokenDeBotTest {

    private val valido = "123456789:AAEhBOweik6ad9r_QXZ2Ql3n1xWcCOPvj8s"

    @Test
    fun `un token de BotFather se acepta`() {
        assertTrue(TokenDeBot.pareceValido(valido))
    }

    @Test
    fun `el vacio se acepta porque significa sin configurar`() {
        // Es el estado inicial legitimo de una instalacion nueva. Si se puede enviar o no es
        // otra pregunta, y la contesta motivoNoDisponible().
        assertTrue(TokenDeBot.pareceValido(""))
        assertTrue(TokenDeBot.pareceValido("   "))
    }

    @Test
    fun `los espacios al pegar no invalidan el token`() {
        assertTrue(TokenDeBot.pareceValido("  $valido  "))
        assertEquals(valido, TokenDeBot.normalizar("  $valido  "))
    }

    @Test
    fun `lo que no es un token se rechaza`() {
        listOf(
            "esto no es un token",
            "123456789",                       // sin la segunda parte
            "123456789:",                      // la segunda parte vacia
            ":AAEhBOweik6ad9r_QXZ2Ql3n1xWcC",  // sin id
            "abc:AAEhBOweik6ad9r_QXZ2Ql3n1xW", // el id no es numerico
            "123:corta",                       // la cadena es demasiado corta
            "Use this token to access the HTTP API: $valido" // media linea de @BotFather
        ).forEach { malo ->
            assertFalse("deberia rechazarse: '$malo'", TokenDeBot.pareceValido(malo))
        }
    }

    @Test
    fun `el id del bot es la parte anterior a los dos puntos`() {
        assertEquals("123456789", TokenDeBot.idDelBot(valido))
    }

    @Test
    fun `sin dos puntos no hay id que extraer`() {
        assertNull(TokenDeBot.idDelBot("123456789"))
        assertNull(TokenDeBot.idDelBot(""))
    }
}
