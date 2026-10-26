// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.rules

import com.capicua.smsrelay.data.config.AppConfig
import com.capicua.smsrelay.data.config.ModoDeEntrega
import com.capicua.smsrelay.domain.rules.EvaluadorDeReglas
import com.capicua.smsrelay.domain.transport.Canal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la importación de copias de **las cinco aplicaciones**.
 *
 * Cada fichero de prueba tiene la forma exacta que escribía su aplicación —sacada del
 * `ConfigBackup` y del `ReglasJson` de cada repositorio—, porque lo que se prueba es que un
 * fichero real de SMStoTelegram o de SMSGateway, guardado hace meses, entre en SMSRelay. Un
 * fichero inventado probaría el parseo y no la migración.
 */
class ImportacionDeCopiaTest {

    private val evaluador = EvaluadorDeReglas()

    /** Un teléfono con todo configurado, para ver qué pisa cada importación. */
    private val telefono = AppConfig(
        tokenAcceso = "EAAG-token",
        idNumeroTelefono = "123456789012345",
        tokenBot = "123456:ABC-bot",
        protegerBucles = true,
        aceptarCertificadosInvalidos = false,
        modoDeEntrega = ModoDeEntrega.SOLO_PLANTILLA,
        plantillaNombre = "reenvio_sms",
        maxMensajesPorMes = 500,
        maxReintentos = 10
    )

    private fun importar(contenido: String) =
        ImportacionDeCopia.interpretar(contenido, telefono, evaluador::validarPatron)

    private fun correcta(contenido: String) = importar(contenido) as CopiaInterpretada.Correcta

    // ── SMSRelay, formato 6 ───────────────────────────────────────────────────

    private val ficheroRelay = """
        {
          "version": 6,
          "exportado": "2026-10-25T10:00:00Z",
          "configuracion": {
            "max_reintentos": 3, "intervalo_reintento_segundos": 30, "timeout_envio_segundos": 60,
            "max_reenvios_por_minuto": 10, "recepcion_activa": true,
            "modo_entrega": "AUTOMATICO", "plantilla_nombre": "", "plantilla_idioma": "es",
            "max_mensajes_por_mes": 900, "token_acceso": null, "id_numero_telefono": null,
            "token_bot": null, "proteger_bucles": false, "aceptar_certificados_invalidos": true
          },
          "reglas": [
            { "nombre": "W", "canal": "WHATSAPP", "destino": "+34600112233", "plantilla": "{mensaje}" },
            { "nombre": "T", "canal": "TELEGRAM", "destino": "-1001234567890", "plantilla": "{mensaje}" },
            { "nombre": "S", "canal": "SMS", "destino": "{telefono}", "plantilla": "{mensaje}" },
            { "nombre": "A", "canal": "API", "destino": "https://api.example.com/sms?t={mensaje}", "plantilla": "{mensaje}" }
          ]
        }
    """.trimIndent()

    @Test
    fun `un fichero de SMSRelay trae cada regla por su canal`() {
        val copia = correcta(ficheroRelay)

        assertEquals("SMSRelay", copia.origen)
        assertEquals(listOf(Canal.WHATSAPP, Canal.TELEGRAM, Canal.SMS, Canal.API), copia.reglas.map { it.canal })
        assertEquals(listOf(0, 1, 2, 3), copia.reglas.map { it.orden })
        assertEquals(0, copia.rechazadas)
    }

    @Test
    fun `un fichero de SMSRelay aplica los ajustes de todos los canales`() {
        val config = correcta(ficheroRelay).config!!

        assertEquals(ModoDeEntrega.AUTOMATICO, config.modoDeEntrega)
        assertFalse(config.protegerBucles)
        assertTrue(config.aceptarCertificadosInvalidos)
        assertEquals(3, config.maxReintentos)
        // Sin credenciales en el fichero, se quedan las del teléfono.
        assertEquals("EAAG-token", config.tokenAcceso)
        assertEquals("123456:ABC-bot", config.tokenBot)
    }

    @Test
    fun `un canal desconocido rechaza la regla, no el fichero`() {
        val copia = correcta(
            """{"version":6,"exportado":"x","reglas":[
                {"nombre":"Fax","canal":"FAX","destino":"+34600112233"},
                {"nombre":"W","canal":"WHATSAPP","destino":"+34600112233"}]}"""
        )

        assertEquals(1, copia.reglas.size)
        assertEquals(1, copia.rechazadas)
    }

    @Test
    fun `un destino que no es de su canal se rechaza`() {
        val copia = correcta(
            """{"version":6,"exportado":"x","reglas":[
                {"nombre":"Chat por WhatsApp","canal":"WHATSAPP","destino":"-1001234567890"},
                {"nombre":"Sin mensaje","canal":"API","destino":"https://h/x"},
                {"nombre":"Alfanumerico","canal":"SMS","destino":"BANCO"},
                {"nombre":"Grupo por SMS","canal":"SMS","destino":"-1001234567890"},
                {"nombre":"Telefono por Telegram","canal":"TELEGRAM","destino":"+34 600 11 22 33"}]}"""
        )

        assertTrue(copia.reglas.isEmpty())
        assertEquals(5, copia.rechazadas)
    }

    @Test
    fun `una regla con una expresion que no compila se rechaza`() {
        val copia = correcta(
            """{"version":6,"exportado":"x","reglas":[
                {"nombre":"Rota","regex_mensaje":"(sin cerrar","canal":"WHATSAPP","destino":"+34600112233"}]}"""
        )

        assertEquals(1, copia.rechazadas)
    }

    @Test
    fun `una version futura se rechaza entera`() {
        val resultado = importar("""{"version":7,"exportado":"x","reglas":[]}""")

        assertTrue(resultado is CopiaInterpretada.Rechazada)
    }

    @Test
    fun `algo que no es JSON se rechaza con un motivo`() {
        val resultado = importar("esto no es json")

        assertEquals("el fichero no es JSON", (resultado as CopiaInterpretada.Rechazada).motivo)
    }

    // ── SMStoWhats, formato 5 ─────────────────────────────────────────────────

    private val ficheroWhats = """
        {
          "version": 5,
          "exportado": "2026-10-18T10:00:00Z",
          "configuracion": {
            "modo_entrega": "SOLO_TEXTO_LIBRE", "plantilla_nombre": "aviso", "plantilla_idioma": "es",
            "max_reintentos": 5, "intervalo_reintento_segundos": 30, "timeout_envio_segundos": 60,
            "max_reenvios_por_minuto": 10, "max_mensajes_por_mes": 300, "recepcion_activa": true,
            "token_acceso": "EAAG-del-fichero", "id_numero_telefono": "999999999999999"
          },
          "reglas": [
            { "nombre": "Banco", "regex_telefono": "^BANCO$", "regex_mensaje": null,
              "ignorar_mayusculas": true, "destino": "+34600112233",
              "plantilla": "{telefono} · {fecha}\n{mensaje}", "activa": true, "continuar": false }
          ]
        }
    """.trimIndent()

    @Test
    fun `un fichero de SMStoWhats trae reglas de WhatsApp`() {
        val copia = correcta(ficheroWhats)

        assertEquals("SMStoWhats", copia.origen)
        assertEquals(Canal.WHATSAPP, copia.reglas.single().canal)
        assertTrue(copia.reglas.single().ignorarMayusculas)
    }

    @Test
    fun `un fichero de SMStoWhats aplica WhatsApp y deja Telegram, SMS y API`() {
        val copia = correcta(ficheroWhats)
        val config = copia.config!!

        assertEquals(ModoDeEntrega.SOLO_TEXTO_LIBRE, config.modoDeEntrega)
        assertEquals(300, config.maxMensajesPorMes)
        assertEquals("EAAG-del-fichero", config.tokenAcceso)
        assertTrue(copia.conCredenciales)
        assertEquals("123456:ABC-bot", config.tokenBot)
        assertTrue(config.protegerBucles)
    }

    // ── SMStoTelegram, formato 4 ──────────────────────────────────────────────

    private val ficheroTelegram = """
        {
          "version": 4,
          "exportado": "2026-09-01T10:00:00Z",
          "configuracion": {
            "max_reintentos": 7, "intervalo_reintento_segundos": 30, "timeout_envio_segundos": 30,
            "max_reenvios_por_minuto": 20, "recepcion_activa": true, "token_bot": null
          },
          "reglas": [
            { "nombre": "Grupo", "regex_telefono": null, "regex_mensaje": "codigo",
              "ignorar_mayusculas": false, "destino": "-1001234567890",
              "plantilla": "{mensaje}", "activa": true, "continuar": true },
            { "nombre": "Canal", "destino": "@mi_canal_publico", "plantilla": "{mensaje}" }
          ]
        }
    """.trimIndent()

    @Test
    fun `un fichero de SMStoTelegram trae reglas de Telegram`() {
        val copia = correcta(ficheroTelegram)

        assertEquals("SMStoTelegram", copia.origen)
        assertEquals(listOf(Canal.TELEGRAM, Canal.TELEGRAM), copia.reglas.map { it.canal })
        assertEquals(0, copia.rechazadas)
    }

    @Test
    fun `un fichero de SMStoTelegram no toca los ajustes de WhatsApp`() {
        val config = correcta(ficheroTelegram).config!!

        assertEquals(7, config.maxReintentos)
        assertEquals(ModoDeEntrega.SOLO_PLANTILLA, config.modoDeEntrega)
        assertEquals("reenvio_sms", config.plantillaNombre)
        assertEquals(500, config.maxMensajesPorMes)
        // token_bot null: se queda el del teléfono.
        assertEquals("123456:ABC-bot", config.tokenBot)
    }

    // ── SMStoSMS, formatos 1, 2 y 3 ───────────────────────────────────────────

    @Test
    fun `un fichero de SMStoSMS del formato 3 trae reglas de SMS y la guarda antibucle`() {
        val copia = correcta(
            """
            {
              "version": 3,
              "exportado": "2026-06-01T10:00:00Z",
              "configuracion": {
                "max_reintentos": 10, "intervalo_reintento_segundos": 30, "timeout_envio_segundos": 60,
                "max_reenvios_por_minuto": 10, "recepcion_activa": true, "proteger_bucles": false
              },
              "reglas": [
                { "nombre": "Responder", "destino": "{telefono}", "plantilla": "Recibido", "ignorar_mayusculas": true },
                { "nombre": "A casa", "destino": "600 11 22 33", "plantilla": "{mensaje}" }
              ]
            }
            """.trimIndent()
        )

        assertEquals("SMStoSMS", copia.origen)
        assertEquals(listOf(Canal.SMS, Canal.SMS), copia.reglas.map { it.canal })
        assertFalse(copia.config!!.protegerBucles)
        assertEquals(ModoDeEntrega.SOLO_PLANTILLA, copia.config!!.modoDeEntrega)
    }

    @Test
    fun `un fichero de SMStoSMS del formato 1 entra sin tocar los ajustes`() {
        val copia = correcta(
            """{"version":1,"exportado":"2026-01-01T10:00:00Z","reglas":[
                {"nombre":"Viejo","regex_mensaje":"codigo","destino":"600112233","plantilla":"{mensaje}","activa":true,"continuar":false}]}"""
        )

        assertNull(copia.config)
        assertEquals(Canal.SMS, copia.reglas.single().canal)
        // El formato 1 no tenía ignorar_mayusculas: sus reglas distinguían mayúsculas.
        assertFalse(copia.reglas.single().ignorarMayusculas)
    }

    // ── SMSGateway ────────────────────────────────────────────────────────────

    private val ficheroGateway = """
        {
          "version": 1,
          "exportado": "2026-03-01T10:00:00Z",
          "urlTemplate": "https://api.example.com/sms?from={telefono}&text={mensaje}&at={fecha}",
          "timeoutSegundos": 15,
          "maxReintentos": 4,
          "intervaloReintentoSegundos": 120,
          "aceptarCertificadosInvalidos": true,
          "recepcionActiva": false
        }
    """.trimIndent()

    @Test
    fun `un fichero de SMSGateway se convierte en una regla API que casa con todo`() {
        val copia = correcta(ficheroGateway)
        val regla = copia.reglas.single()

        assertEquals("SMSGateway", copia.origen)
        assertEquals(Canal.API, regla.canal)
        assertEquals("https://api.example.com/sms?from={telefono}&text={mensaje}&at={fecha}", regla.destino)
        assertEquals("{mensaje}", regla.plantilla)
        assertNull(regla.regexTelefono)
        assertNull(regla.regexMensaje)
        assertFalse(copia.conCredenciales)
    }

    @Test
    fun `un fichero de SMSGateway trae sus reintentos, su plazo y los certificados`() {
        val config = correcta(ficheroGateway).config!!

        assertEquals(15, config.timeoutEnvioSegundos)
        assertEquals(4, config.maxReintentos)
        assertEquals(120, config.intervaloReintentoSegundos)
        assertTrue(config.aceptarCertificadosInvalidos)
        assertFalse(config.recepcionActiva)
        assertEquals(ModoDeEntrega.SOLO_PLANTILLA, config.modoDeEntrega)
    }

    @Test
    fun `una URL de SMSGateway que aqui no vale se cuenta como rechazada`() {
        // El marcador en el host lo aceptaba SMSGateway; aquí el cuerpo de un SMS no elige servidor.
        val copia = correcta(
            """{"version":1,"exportado":"x","urlTemplate":"https://{telefono}.example.com/?t={mensaje}"}"""
        )

        assertTrue(copia.reglas.isEmpty())
        assertEquals(1, copia.rechazadas)
    }

    // ── El canal de cada formato ──────────────────────────────────────────────

    @Test
    fun `cada formato anterior al 6 tiene un unico canal`() {
        assertEquals(Canal.SMS, ImportacionDeCopia.canalDeFormato(1))
        assertEquals(Canal.SMS, ImportacionDeCopia.canalDeFormato(3))
        assertEquals(Canal.TELEGRAM, ImportacionDeCopia.canalDeFormato(4))
        assertEquals(Canal.WHATSAPP, ImportacionDeCopia.canalDeFormato(5))
        assertNull(ImportacionDeCopia.canalDeFormato(6))
    }
}
