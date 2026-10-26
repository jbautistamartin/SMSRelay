// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.transport

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Los transportes que hay en esta compilación, uno por [Canal].
 *
 * Es lo que piden el worker de despacho y las pantallas en lugar de un [EnviadorDeMensajes]
 * suelto: un reenvío lleva su canal y quien lo entrega busca aquí el transporte de ese canal.
 * El mapa lo rellena `di/TransportModule`, que sigue siendo el único sitio que nombra una
 * implementación concreta.
 *
 * **Que falte un canal no es un error de programación**, y por eso [de] devuelve null en lugar de
 * lanzar. Pasa mientras un transporte se está escribiendo —una regla puede apuntar a un canal que
 * esta compilación todavía no entrega— y pasará si algún día se retira uno. Quien llama lo
 * convierte en un motivo legible.
 */
@Singleton
class Transportes @Inject constructor(
    private val porCanal: Map<Canal, @JvmSuppressWildcards EnviadorDeMensajes>
) {

    /** El transporte de [canal], o null si esta compilación no entrega por él. */
    fun de(canal: Canal): EnviadorDeMensajes? = porCanal[canal]

    /** Canales que esta compilación sabe entregar, en el orden de [Canal]. */
    val disponibles: List<Canal> get() = Canal.entries.filter { it in porCanal }

    /** Motivo legible de que [canal] no tenga transporte, para el registro y la pantalla. */
    fun motivoSinTransporte(canal: Canal): String =
        "esta versión de la aplicación no entrega por ${canal.etiqueta}"
}
