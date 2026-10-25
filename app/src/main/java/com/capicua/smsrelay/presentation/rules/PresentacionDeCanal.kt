// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.presentation.rules

import android.text.InputType
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.capicua.smsrelay.R
import com.capicua.smsrelay.domain.transport.Canal

/**
 * Cómo se pinta cada [Canal] en el editor de reglas.
 *
 * Existe para que el editor no reparta `when (canal)` por todo el fragment: el canal cambia a la
 * vez el chip marcado, la explicación de debajo, la etiqueta y la ayuda del destino y el teclado.
 * Si algo de eso se cambiara en un sitio y no en otro, la pantalla diría una cosa y validaría
 * otra.
 *
 * Es presentación y no dominio a propósito: `Canal` no sabe de recursos de Android, y así se
 * puede seguir usando en los tests de Kotlin puro.
 */
enum class PresentacionDeCanal(
    val canal: Canal,
    @IdRes val chip: Int,
    @StringRes val ayudaCanal: Int,
    @StringRes val etiquetaDestino: Int,
    @StringRes val ayudaDestino: Int,
    val tipoTeclado: Int
) {
    WHATSAPP(
        Canal.WHATSAPP,
        R.id.chip_canal_whatsapp,
        R.string.canal_ayuda_whatsapp,
        R.string.regla_destino_whatsapp,
        R.string.regla_destino_ayuda_whatsapp,
        InputType.TYPE_CLASS_PHONE
    ),
    TELEGRAM(
        Canal.TELEGRAM,
        R.id.chip_canal_telegram,
        R.string.canal_ayuda_telegram,
        R.string.regla_destino_telegram,
        R.string.regla_destino_ayuda_telegram,
        // Un @canal lleva arroba y el teclado de teléfono no la tiene.
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    ),
    SMS(
        Canal.SMS,
        R.id.chip_canal_sms,
        R.string.canal_ayuda_sms,
        R.string.regla_destino_sms,
        R.string.regla_destino_ayuda_sms,
        // Texto y no teléfono: `{telefono}` es un destino válido y lleva llaves.
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    ),
    API(
        Canal.API,
        R.id.chip_canal_api,
        R.string.canal_ayuda_api,
        R.string.regla_destino_api,
        R.string.regla_destino_ayuda_api,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    );

    companion object {
        fun de(canal: Canal): PresentacionDeCanal = entries.first { it.canal == canal }

        fun deChip(@IdRes chip: Int): PresentacionDeCanal? = entries.firstOrNull { it.chip == chip }
    }
}
