// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.whatsapp

/**
 * Qué puede escribirse en el destino de una regla, ahora que un destino vuelve a ser un
 * teléfono.
 *
 * Esto sustituye a `DestinoDeTelegram`, que validaba `chat_id` y `@usuario`. Y cierra un círculo
 * curioso: el destino de una regla era un teléfono en SMStoSMS, pasó a ser un chat en
 * SMStoTelegram y vuelve a ser un teléfono aquí. Pero **`NormalizadorTelefono` no vuelve con él**,
 * porque las dos preguntas que aquella clase contestaba siguen sin existir:
 *
 * - *¿Son el mismo destino, para detectar un bucle?* No hay bucle que detectar: un mensaje de
 *   WhatsApp no vuelve nunca como SMS. Lo único parecido lo vigila Meta sola, rechazando con el
 *   error `131021` un envío del número emisor a sí mismo.
 * - *¿Es alcanzable?* Un teléfono perfectamente formado puede no tener WhatsApp, y eso solo lo
 *   sabe Meta (error `131026`).
 *
 * Queda una sola pregunta: ¿esto tiene forma de destino?
 *
 * ## Solo personas individuales
 * Por decisión del proyecto, no por limitación encontrada. La Cloud API admite grupos desde 2026,
 * pero solo creados por ella, de hasta ocho participantes y exigiendo Cuenta Oficial de Empresa.
 * Aquí los destinos son siempre personas, lo que deja esta clase con **una** forma que validar en
 * lugar de dos, y el `recipient_type` del JSON fijo en `individual` sin ramificación.
 *
 * ## Por qué el `+` es obligatorio
 * Es la decisión de diseño de esta clase, y es lo que hace posible dar un error útil.
 *
 * La Cloud API acepta el número con `+` y sin él, así que exigirlo no es un requisito técnico.
 * El problema que resuelve es el de la ambigüedad: `600112233` son nueve dígitos que podrían ser
 * un móvil español escrito a pelo —inservible, porque falta el prefijo— o un número
 * internacional completo de un país con prefijo corto. No hay forma de distinguirlos mirando.
 *
 * Con el `+` obligatorio la ambigüedad desaparece, y a cambio se puede decir exactamente lo que
 * pasa: *«falta el prefijo internacional, escribe +34…»*. Es el error que va a cometer todo el
 * mundo, y decirlo así lo resuelve; decir «no tiene forma de teléfono» sería verdad y no
 * serviría de nada.
 *
 * Es el mismo razonamiento que hacía `DestinoDeTelegram.pareceTelefono()` —existir solo para dar
 * un mensaje de error útil en el error más probable— aplicado al error más probable de ahora.
 */
object DestinoDeWhatsApp {

    /** true si [destino] tiene forma de teléfono en formato internacional. */
    fun pareceValido(destino: String): Boolean = E164.matches(normalizar(destino))

    /**
     * El destino tal como debe guardarse: `+` y dígitos, sin separadores.
     *
     * Quita espacios, guiones, puntos y paréntesis, que es como la gente escribe los teléfonos
     * en todas partes menos en una API.
     */
    fun normalizar(destino: String): String {
        val limpio = destino.trim().filter { it.isDigit() || it == '+' }
        // Un `+` que no esté al principio no significa nada: se descarta junto con los demás.
        return if (limpio.startsWith("+")) "+" + limpio.drop(1).filter { it.isDigit() }
        else limpio.filter { it.isDigit() }
    }

    /**
     * true si [destino] parece un número nacional, sin prefijo internacional.
     *
     * Es el caso que va a pasar siempre: quien configura la aplicación tiene el número en la
     * agenda tal como se marca en su país. Ver el KDoc de la clase para por qué esto se puede
     * distinguir y antes no se podía.
     */
    fun faltaPrefijo(destino: String): Boolean {
        if (pareceValido(destino)) return false
        // Un número de teléfono no empieza por un signo menos, así que lo que lo lleva no es un
        // teléfono al que le falte el prefijo: es otra cosa, y merece el otro mensaje. El caso
        // concreto es un `chat_id` de Telegram —`-1001234567890`—, que [normalizar] dejaría en
        // trece dígitos y pasaría por un número al que solo le falta el `+`.
        if (destino.trim().startsWith("-")) return false
        val digitos = normalizar(destino)
        return !digitos.startsWith("+") && digitos.length in 6..15
    }

    /**
     * El número tal como va en el campo `to` del JSON: solo dígitos.
     *
     * Meta acepta las dos formas, pero los ejemplos de la documentación usan el número sin `+`
     * y no hay motivo para apartarse de ellos. El `+` se guarda porque es para que lo lea una
     * persona; se quita porque es para que lo lea una API.
     */
    fun aDestinatarioApi(destino: String): String =
        normalizar(destino).removePrefix("+")

    /**
     * E.164: un `+`, un primer dígito distinto de cero y entre 8 y 15 dígitos en total.
     *
     * El tope de 15 es el del propio estándar. El mínimo de 8 es prudente —hay planes de
     * numeración más cortos— pero por debajo de eso no cabe un prefijo de país más un número,
     * así que casi siempre significa que falta algo.
     */
    private val E164 = Regex("""^\+[1-9]\d{7,14}$""")
}
