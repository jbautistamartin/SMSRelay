// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.rules

import com.capicua.smsrelay.data.config.AppConfig
import com.capicua.smsrelay.data.config.ModoDeEntrega
import com.capicua.smsrelay.domain.model.Regla
import com.capicua.smsrelay.domain.transport.Canal
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del formato de intercambio, el 6.
 *
 * Lo que se verifica no es que kotlinx.serialization funcione, sino las decisiones propias del
 * formato: que los ids locales no viajen, que las credenciales tampoco salvo que se pidan, que
 * cada regla lleve su canal y que importar los ajustes de un canal no toque los de los demás.
 * Leer los ficheros de las aplicaciones anteriores es cosa de `ImportacionDeCopiaTest`.
 */
class ReglasJsonTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Sin esto, kotlinx.serialization omite los campos cuyo valor coincide con el
        // predeterminado — incluida la versión del formato, que es justo lo que el fichero
        // tiene que declarar para que una importación futura sepa qué está leyendo.
        encodeDefaults = true
    }

    private fun regla(
        id: Long = 42,
        orden: Int = 3,
        nombre: String = "Banco",
        regexTelefono: String? = "^BANCO$",
        regexMensaje: String? = "codigo",
        ignorarMayusculas: Boolean = false,
        canal: Canal = Canal.WHATSAPP,
        destino: String = "+34600112233",
        plantilla: String = "De {telefono}: {mensaje}",
        activa: Boolean = true,
        continuar: Boolean = false
    ) = Regla(
        // Argumentos nombrados a propósito: con posicionales, añadir un campo al modelo
        // compila mal o, peor, compila y cambia de sitio dos valores del mismo tipo.
        id = id,
        orden = orden,
        nombre = nombre,
        regexTelefono = regexTelefono,
        regexMensaje = regexMensaje,
        ignorarMayusculas = ignorarMayusculas,
        canal = canal,
        destino = destino,
        plantilla = plantilla,
        activa = activa,
        continuar = continuar
    )

    private val configCompleta = AppConfig(
        tokenAcceso = "EAAG-token",
        idNumeroTelefono = "123456789012345",
        tokenBot = "123456:ABC-bot",
        subscriptionId = 7,
        protegerBucles = false,
        aceptarCertificadosInvalidos = true,
        modoDeEntrega = ModoDeEntrega.SOLO_PLANTILLA,
        plantillaNombre = "reenvio_sms",
        plantillaIdioma = "en",
        maxReintentos = 4,
        intervaloReintentoSegundos = 90,
        timeoutEnvioSegundos = 20,
        maxReenviosPorMinuto = 3,
        maxMensajesPorMes = 500,
        recepcionActiva = false
    )

    // ── Ida y vuelta ──────────────────────────────────────────────────────────

    @Test
    fun `una regla sobrevive al viaje de ida y vuelta, canal incluido`() {
        val original = regla(canal = Canal.TELEGRAM, destino = "-1001234567890")

        val recuperada = original.aJson().aDominio(orden = 0, canal = Canal.TELEGRAM)

        assertEquals("TELEGRAM", original.aJson().canal)
        assertEquals(original.copy(id = 0, orden = 0), recuperada)
    }

    @Test
    fun `el id local no viaja en el fichero`() {
        val texto = json.encodeToString(ReglaJson.serializer(), regla(id = 42).aJson())

        assertFalse(texto.contains("\"id\""))
    }

    @Test
    fun `al importar el id queda a cero para que Room asigne uno nuevo`() {
        val recuperada = regla(id = 42).aJson().aDominio(orden = 5, canal = Canal.WHATSAPP)

        assertEquals(0L, recuperada.id)
        assertEquals(5, recuperada.orden)
    }

    @Test
    fun `una plantilla ausente toma la del canal`() {
        val sinPlantilla = ReglaJson(nombre = "x", destino = "https://h/?t={mensaje}")

        assertEquals(Regla.PLANTILLA_POR_DEFECTO_API, sinPlantilla.aDominio(0, Canal.API).plantilla)
        assertEquals(Regla.PLANTILLA_POR_DEFECTO, sinPlantilla.aDominio(0, Canal.SMS).plantilla)
    }

    @Test
    fun `los patrones en blanco se normalizan a null al importar`() {
        val importada = ReglaJson(nombre = "x", regexTelefono = "  ", regexMensaje = "", destino = "+34600112233")
            .aDominio(0, Canal.WHATSAPP)

        assertNull(importada.regexTelefono)
        assertNull(importada.regexMensaje)
    }

    @Test
    fun `el fichero declara la version 6`() {
        val texto = json.encodeToString(
            ReglasExportadas.serializer(),
            ReglasExportadas(exportado = "2026-10-25T10:00:00Z", reglas = emptyList())
        )

        assertEquals(6, ReglasExportadas.VERSION_ACTUAL)
        assertEquals(1, ReglasExportadas.MINIMA_ACEPTADA)
        assertTrue(texto.contains("\"version\": 6"))
    }

    // ── Ajustes: qué viaja ────────────────────────────────────────────────────

    @Test
    fun `por defecto ninguna credencial aparece en el fichero`() {
        val exportada = configCompleta.aJson()

        assertNull(exportada.tokenAcceso)
        assertNull(exportada.idNumeroTelefono)
        assertNull(exportada.tokenBot)
        assertFalse(exportada.traeCredenciales)
    }

    @Test
    fun `si se piden, las credenciales de los dos canales viajan`() {
        val exportada = configCompleta.aJson(incluirCredenciales = true)

        assertEquals("EAAG-token", exportada.tokenAcceso)
        assertEquals("123456789012345", exportada.idNumeroTelefono)
        assertEquals("123456:ABC-bot", exportada.tokenBot)
        assertTrue(exportada.traeCredenciales)
    }

    @Test
    fun `la SIM de envio no viaja nunca`() {
        val texto = json.encodeToString(
            ConfiguracionJson.serializer(), configCompleta.aJson(incluirCredenciales = true)
        )

        assertFalse(texto.contains("subscription"))
        assertFalse(texto.contains("sim"))
    }

    @Test
    fun `los ajustes de los cuatro canales sobreviven a la ida y vuelta`() {
        val recuperada = configCompleta.aJson(incluirCredenciales = true)
            .aplicarSobre(AppConfig(), Canal.entries.toSet())

        // La SIM es lo único que no viaja: se queda la del teléfono de destino.
        assertEquals(configCompleta.copy(subscriptionId = AppConfig.SIM_POR_DEFECTO), recuperada)
    }

    // ── Ajustes: qué se aplica ────────────────────────────────────────────────

    @Test
    fun `aplicar los ajustes de un canal no toca los de los demas`() {
        val delFichero = AppConfig(
            tokenBot = "999:otro",
            protegerBucles = true,
            aceptarCertificadosInvalidos = false,
            modoDeEntrega = ModoDeEntrega.SOLO_TEXTO_LIBRE,
            plantillaNombre = "otra",
            maxMensajesPorMes = 50,
            maxReintentos = 2
        ).aJson(incluirCredenciales = true)

        val resultado = delFichero.aplicarSobre(configCompleta, setOf(Canal.TELEGRAM))

        assertEquals("999:otro", resultado.tokenBot)
        assertEquals(2, resultado.maxReintentos)
        // WhatsApp, SMS y API, como estaban.
        assertEquals(ModoDeEntrega.SOLO_PLANTILLA, resultado.modoDeEntrega)
        assertEquals("reenvio_sms", resultado.plantillaNombre)
        assertEquals(500, resultado.maxMensajesPorMes)
        assertFalse(resultado.protegerBucles)
        assertTrue(resultado.aceptarCertificadosInvalidos)
        assertEquals("EAAG-token", resultado.tokenAcceso)
        assertEquals(7, resultado.subscriptionId)
    }

    @Test
    fun `importar sin credenciales conserva las del telefono`() {
        val resultado = AppConfig().aJson().aplicarSobre(configCompleta, Canal.entries.toSet())

        assertEquals("EAAG-token", resultado.tokenAcceso)
        assertEquals("123456789012345", resultado.idNumeroTelefono)
        assertEquals("123456:ABC-bot", resultado.tokenBot)
    }

    @Test
    fun `una credencial en blanco en el fichero no pisa la que ya habia`() {
        val delFichero = AppConfig().aJson().copy(tokenAcceso = "  ", tokenBot = "")

        val resultado = delFichero.aplicarSobre(configCompleta, Canal.entries.toSet())

        assertEquals("EAAG-token", resultado.tokenAcceso)
        assertEquals("123456:ABC-bot", resultado.tokenBot)
    }

    @Test
    fun `un ajuste de canal ausente en el fichero deja el del telefono`() {
        // Así llega un fichero del formato 5 leído con el modelo del 6: sin los campos nuevos.
        val delFichero = AppConfig().aJson().copy(protegerBucles = null, aceptarCertificadosInvalidos = null)

        val resultado = delFichero.aplicarSobre(configCompleta, Canal.entries.toSet())

        assertFalse(resultado.protegerBucles)
        assertTrue(resultado.aceptarCertificadosInvalidos)
    }
}
