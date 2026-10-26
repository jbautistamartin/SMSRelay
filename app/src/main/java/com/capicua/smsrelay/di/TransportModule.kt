// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.di

import com.capicua.smsrelay.data.api.ApiSender
import com.capicua.smsrelay.data.sms.SmsSender
import com.capicua.smsrelay.data.telegram.TelegramSender
import com.capicua.smsrelay.data.whatsapp.WhatsAppSender
import com.capicua.smsrelay.domain.transport.Canal
import com.capicua.smsrelay.domain.transport.EnviadorDeMensajes
import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/** Clave del mapa de transportes: el [Canal] que entrega cada implementación. */
@MapKey
annotation class ClaveCanal(val value: Canal)

/**
 * Los transportes por los que salen los reenvíos, uno por [Canal].
 *
 * Es el **único** sitio del proyecto que nombra una implementación concreta del medio de salida.
 * Todo lo demás —el worker de despacho, la pantalla de prueba, el editor de reglas— depende de
 * [com.capicua.smsrelay.domain.transport.Transportes], que recibe este mapa.
 *
 * En las aplicaciones de las que nace SMSRelay esto era un único `@Binds`, y cambiar de medio era
 * cambiar esa línea: así se pasó de `SmsManager` a Telegram y de Telegram a la Cloud API sin
 * tocar la tubería. Aquí los medios no se sustituyen, se suman, y por eso cada uno entra con un
 * `@IntoMap` bajo su canal. Añadir un medio sigue siendo una entrada en este fichero; lo que ya
 * no basta es esa entrada sola, porque la regla tiene que poder elegirlo.
 *
 * Que una entrada falte no rompe la compilación ni el arranque: una regla con un canal sin
 * transporte se cierra como fallo permanente con un motivo que lo dice.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds
    @IntoMap
    @ClaveCanal(Canal.API)
    abstract fun bindApi(impl: ApiSender): EnviadorDeMensajes

    @Binds
    @IntoMap
    @ClaveCanal(Canal.SMS)
    abstract fun bindSms(impl: SmsSender): EnviadorDeMensajes

    @Binds
    @IntoMap
    @ClaveCanal(Canal.TELEGRAM)
    abstract fun bindTelegram(impl: TelegramSender): EnviadorDeMensajes

    @Binds
    @IntoMap
    @ClaveCanal(Canal.WHATSAPP)
    abstract fun bindWhatsApp(impl: WhatsAppSender): EnviadorDeMensajes
}
