// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [SaludDelNumero], con la forma de la respuesta que Meta dio en la fase 10 a un número
 * sin registrar de una cuenta sin verificar (recortada, y sin ids).
 */
class SaludDelNumeroTest {

    private val bloqueado = """
        {"display_phone_number":"+52 1 777 000 0000","status":"PENDING",
         "health_status":{"can_send_message":"BLOCKED","entities":[
           {"entity_type":"PHONE_NUMBER","can_send_message":"BLOCKED","errors":[
             {"error_code":141000,"error_description":"The phone number is not linked to your WhatsApp account."},
             {"error_code":138024,"error_description":"WhatsApp Business calling cannot use SIP because it is not enabled"}]},
           {"entity_type":"WABA","can_send_message":"BLOCKED","errors":[
             {"error_code":141006,"error_description":"There is an error with the payment method."}]},
           {"entity_type":"APP","can_send_message":"AVAILABLE","errors":[
             {"error_code":138025,"error_description":"This app cannot use SIP"}]}]}}
    """.trimIndent()

    @Test
    fun `un numero bloqueado devuelve sus motivos con el codigo`() {
        assertEquals(
            listOf(
                "The phone number is not linked to your WhatsApp account. (141000)",
                "There is an error with the payment method. (141006)"
            ),
            SaludDelNumero.bloqueos(bloqueado)
        )
    }

    @Test
    fun `los errores de llamadas por SIP no son un bloqueo de mensajes`() {
        assertTrue(SaludDelNumero.bloqueos(bloqueado).none { "SIP" in it })
    }

    @Test
    fun `un numero que puede enviar no tiene bloqueos`() {
        val disponible = """{"health_status":{"can_send_message":"AVAILABLE","entities":[]}}"""
        assertTrue(SaludDelNumero.bloqueos(disponible).isEmpty())
    }

    @Test
    fun `sin health_status o con un cuerpo ilegible no se inventa un bloqueo`() {
        assertTrue(SaludDelNumero.bloqueos("""{"display_phone_number":"+34 600"}""").isEmpty())
        assertTrue(SaludDelNumero.bloqueos("no es json").isEmpty())
        assertTrue(SaludDelNumero.bloqueos(null).isEmpty())
    }
}
