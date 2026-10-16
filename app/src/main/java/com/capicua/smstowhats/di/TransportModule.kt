// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.di

import com.capicua.smstowhats.data.telegram.TelegramSender
import com.capicua.smstowhats.domain.transport.EnviadorDeMensajes
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Elige el transporte por el que salen los reenvíos.
 *
 * Es el **único** sitio del proyecto que nombra una implementación concreta del medio de salida.
 * Todo lo demás —el worker de despacho, la pantalla de prueba, el editor de reglas— depende de
 * [EnviadorDeMensajes], así que cambiar de medio es cambiar esta línea.
 *
 * Y eso es exactamente lo que pasó en la fase 3: salió el andamio `TransporteSinConfigurar` y
 * entró [TelegramSender], sin que se tocara ni el worker de despacho, ni el evaluador de reglas,
 * ni la cola, ni el rescate de huérfanos.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds
    @Singleton
    abstract fun bindEnviadorDeMensajes(impl: TelegramSender): EnviadorDeMensajes
}
