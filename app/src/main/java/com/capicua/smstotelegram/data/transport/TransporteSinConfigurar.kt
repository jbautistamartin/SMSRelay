// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.data.transport

import com.capicua.smstotelegram.domain.transport.EnviadorDeMensajes
import com.capicua.smstotelegram.domain.transport.ResultadoEnvio
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transporte que no entrega nada.
 *
 * Es el andamio de la fase 2: el envío por SMS ya se ha retirado y el de Telegram todavía no
 * existe, así que algo tiene que satisfacer [EnviadorDeMensajes] para que la aplicación compile,
 * arranque y se pueda recorrer entera. **Desaparece en la fase 3**, cuando `TelegramSender`
 * ocupe su `@Binds` en `TransportModule`.
 *
 * Devuelve [ResultadoEnvio.ErrorPermanente] y no transitorio **a propósito**: un transporte que
 * no existe no se va a arreglar esperando, y marcar el reenvío como fallido de una vez es más
 * honesto que dejarlo reintentando diez veces contra nada. El rescate de huérfanos tampoco lo
 * resucita, porque solo recoge lo que sigue `PENDIENTE`.
 */
@Singleton
class TransporteSinConfigurar @Inject constructor() : EnviadorDeMensajes {

    /**
     * Valor sin efecto real —aquí no se envía nada—, pero la pantalla de reglas lo pinta
     * mientras se escribe una plantilla y necesita un número. El transporte de verdad lo fija
     * con el límite que de verdad tiene.
     */
    override val limiteCaracteres: Int = 4096

    override suspend fun enviar(
        destino: String,
        texto: String,
        timeoutSegundos: Int
    ): ResultadoEnvio {
        Timber.w("TransporteSinConfigurar: se pidió enviar a '$destino' y no hay transporte")
        return ResultadoEnvio.ErrorPermanente(MOTIVO)
    }

    override suspend fun motivoNoDisponible(): String = MOTIVO

    private companion object {
        const val MOTIVO = "no hay ningún transporte configurado en esta versión"
    }
}
