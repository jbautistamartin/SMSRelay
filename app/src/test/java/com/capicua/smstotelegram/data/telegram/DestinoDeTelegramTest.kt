// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de qué se acepta como destino de una regla.
 *
 * Sustituyen a los de `NormalizadorTelefono`, que comparaba teléfonos. La sustitución no es una
 * traducción: aquellos probaban que dos formas del mismo número se reconocían como iguales, y
 * esa pregunta aquí **no existe** —un chat de Telegram no vuelve nunca como SMS, así que no hay
 * bucle que detectar comparando destinos—. Queda una sola pregunta: ¿esto tiene forma de destino?
 */
class DestinoDeTelegramTest {

    // ── Las tres formas del Bot API ───────────────────────────────────────────

    @Test
    fun `un chat_id de persona se acepta`() {
        assertTrue(DestinoDeTelegram.pareceValido("123456789"))
    }

    @Test
    fun `un chat_id negativo de grupo o canal se acepta`() {
        // Es la forma que mas se escribe mal: quitarle el signo al copiarlo manda el mensaje
        // a una persona que no tiene nada que ver, o a ninguna parte.
        assertTrue(DestinoDeTelegram.pareceValido("-1001234567890"))
        assertTrue(DestinoDeTelegram.pareceValido("-987654321"))
    }

    @Test
    fun `un arroba usuario de canal se acepta`() {
        assertTrue(DestinoDeTelegram.pareceValido("@mi_canal"))
        assertTrue(DestinoDeTelegram.esUsuario("@mi_canal"))
    }

    @Test
    fun `los espacios alrededor no invalidan el destino`() {
        assertTrue(DestinoDeTelegram.pareceValido("  -1001234567890  "))
        assertEquals("-1001234567890", DestinoDeTelegram.normalizar("  -1001234567890  "))
    }

    // ── Lo que no vale ────────────────────────────────────────────────────────

    @Test
    fun `el vacio no es un destino`() {
        assertFalse(DestinoDeTelegram.pareceValido(""))
        assertFalse(DestinoDeTelegram.pareceValido("   "))
    }

    @Test
    fun `lo que no tiene forma de chat se rechaza`() {
        listOf(
            "mi canal",       // con espacio en medio
            "@ab",            // demasiado corto para un usuario de Telegram
            "@1canal",        // un usuario no empieza por digito
            "123-456",        // un guion en medio no es un signo
            "{telefono}",     // el marcador que dejo de existir en el destino
            "12.5"
        ).forEach { malo ->
            assertFalse("deberia rechazarse: '$malo'", DestinoDeTelegram.pareceValido(malo))
        }
    }

    // ── El error mas probable de todos ────────────────────────────────────────

    /**
     * Quien viene de SMStoSMS, o quien simplemente asume que un «destino» es un teléfono,
     * escribirá un número. Decirle «no tiene forma de chat» es verdad y no le sirve de nada;
     * reconocer el caso permite decirle qué hacer.
     */
    @Test
    fun `un numero de telefono se reconoce como tal para poder explicarlo`() {
        listOf("+34600112233", "+34 600 11 22 33", "(600) 112-233", "600 112 233").forEach { tel ->
            assertTrue("deberia detectarse como telefono: '$tel'", DestinoDeTelegram.pareceTelefono(tel))
            assertFalse(DestinoDeTelegram.pareceValido(tel))
        }
    }

    /**
     * La ambigüedad que este test fija, y que conviene no «arreglar».
     *
     * `600112233` son nueve dígitos seguidos: es a la vez un móvil español escrito a pelo y un
     * `chat_id` de persona perfectamente legítimo. No hay forma de distinguirlos, y gana el
     * chat, porque rechazar un `chat_id` válido rompe una configuración correcta con un error
     * que además sería falso. Un teléfono que se cuele por aquí acaba en un
     * `400 chat not found` con su motivo en el registro; un chat rechazado no tendría arreglo.
     */
    @Test
    fun `un numero sin separadores se trata como chat_id, no como telefono`() {
        assertTrue(DestinoDeTelegram.pareceValido("600112233"))
        assertFalse(DestinoDeTelegram.pareceTelefono("600112233"))
    }

    @Test
    fun `un chat_id valido no se confunde con un telefono`() {
        assertFalse(DestinoDeTelegram.pareceTelefono("123456789"))
        assertFalse(DestinoDeTelegram.pareceTelefono("-1001234567890"))
        assertFalse(DestinoDeTelegram.pareceTelefono("@mi_canal"))
    }

    @Test
    fun `un texto cualquiera no es un telefono ni un chat`() {
        assertFalse(DestinoDeTelegram.pareceTelefono("mi canal favorito"))
        assertFalse(DestinoDeTelegram.pareceValido("mi canal favorito"))
    }
}
