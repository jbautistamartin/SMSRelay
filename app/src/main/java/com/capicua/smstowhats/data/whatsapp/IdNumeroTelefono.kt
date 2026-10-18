// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.whatsapp

/**
 * El `phone_number_id` de la Cloud API: la segunda credencial.
 *
 * ## Por qué hay dos credenciales donde Telegram tenía una
 * El Bot API metía todo en la URL: `api.telegram.org/bot<token>/sendMessage`. El token
 * identificaba al bot *y* autorizaba la petición, así que con una sola cadena ya se podía
 * enviar. La Cloud API separa las dos cosas, y hace falta tenerlas las dos:
 *
 * | Credencial | Dónde va | Qué responde |
 * |------------|----------|--------------|
 * | Token de acceso | Cabecera `Authorization: Bearer` | ¿Tienes permiso? |
 * | `phone_number_id` | La ruta, `/<id>/messages` | ¿Desde qué número? |
 *
 * Eso tiene una consecuencia práctica en la pantalla de Ajustes —dos campos en lugar de uno— y
 * otra en el diagnóstico: fallan por sitios distintos y con códigos distintos, así que
 * `MapeoDeRespuestas` tiene que reconocer los dos para encender el aviso del Panel.
 *
 * ## Lo que confunde a todo el mundo
 * **No es el número de teléfono.** La consola de Meta enseña los dos juntos, uno debajo del
 * otro, y el instinto es copiar el que se reconoce. El `phone_number_id` es un identificador
 * interno de Meta, de unos quince dígitos, que no se parece a ningún teléfono y no tiene
 * prefijo. Copiar el teléfono en su lugar da un error `100` —«ese objeto no existe»— que
 * `MapeoDeRespuestas` clasifica como fallo de credencial precisamente para que el Panel mande a
 * mirar aquí.
 *
 * El texto de ayuda de Ajustes lo dice, porque es el error de configuración número uno de la
 * Cloud API.
 */
object IdNumeroTelefono {

    /**
     * true si [id] tiene forma de `phone_number_id`.
     *
     * Un id **vacío es válido** aquí, igual que en [TokenDeAcceso]: significa «sin configurar».
     */
    fun pareceValido(id: String): Boolean {
        val limpio = normalizar(id)
        return limpio.isEmpty() || FORMA.matches(limpio)
    }

    /** El id tal como debe guardarse: sin espacios alrededor. */
    fun normalizar(id: String): String = id.trim()

    /**
     * true si [id] parece un número de teléfono y no un `phone_number_id`.
     *
     * Existe para el mismo propósito que `DestinoDeWhatsApp.faltaPrefijo()`: dar el mensaje de
     * error útil en el error más probable. Lo que delata al teléfono es el `+` o los
     * separadores, que un id nunca lleva. Un teléfono escrito a pelo, sin `+`, es
     * indistinguible de un id corto y pasa; Meta contestará `100` y el Panel mandará aquí.
     */
    fun pareceTelefono(id: String): Boolean {
        val limpio = id.trim()
        if (limpio.isEmpty()) return false
        return limpio.any { it == '+' || it == ' ' || it == '-' || it == '(' } &&
            limpio.all { it.isDigit() || it in " +-()" }
    }

    /**
     * Entre 10 y 20 dígitos.
     *
     * Los de hoy tienen quince. El rango es holgado a propósito: es un identificador opaco de
     * Meta y su longitud no es un contrato.
     */
    private val FORMA = Regex("""^\d{10,20}$""")
}
