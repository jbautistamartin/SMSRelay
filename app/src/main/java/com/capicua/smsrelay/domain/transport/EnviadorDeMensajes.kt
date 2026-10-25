// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.domain.transport

/**
 * El medio por el que sale un reenvío.
 *
 * Es la juntura de la aplicación: todo lo que hay antes —recibir el SMS, persistirlo, evaluar
 * las reglas, crear un reenvío por destino, encolarlo, reintentarlo, rescatarlo si se queda a
 * medias— es agnóstico del medio y no cambia aunque cambie el medio. Lo que hay después es un
 * detalle de implementación que vive en `data`.
 *
 * Hoy la única implementación entrega por la Cloud API de WhatsApp, y **no está previsto que
 * haya dos**. El interfaz no existe para permitir varios transportes a la vez: existe porque
 * marca dónde está el corte, y porque es lo que ha permitido que cambiar de medio **dos veces**
 * —de `SmsManager` a Telegram, y de Telegram a la Cloud API— no tocase la tubería ni una sola
 * vez. Añadir un selector de transporte por regla duplicaría la pantalla de reglas, la de
 * ajustes y las guardias para un caso que esta aplicación no tiene.
 *
 * ## Qué no es responsabilidad del transporte
 * - **Reintentar.** Devuelve [ResultadoEnvio.ErrorTransitorio] y decide `DispatchWorker`.
 * - **Registrar en el log.** Devuelve un motivo legible y lo escribe quien llama.
 * - **Decidir el destino.** Eso lo hizo el evaluador de reglas mucho antes.
 */
interface EnviadorDeMensajes {

    /**
     * Longitud máxima de un mensaje, en caracteres, **con la configuración actual**.
     *
     * La pantalla de reglas la usa para avisar mientras se escribe la plantilla, que es el único
     * momento en que alguien puede hacer algo al respecto.
     *
     * ## Por qué suspende
     * Era una propiedad, y dejó de poder serlo cuando el límite pasó a depender de cómo esté
     * configurado el transporte: la Cloud API admite 4.096 caracteres en un mensaje de texto
     * libre y 1.024 renderizados en uno de plantilla, así que el límite que de verdad aplica no
     * se puede saber sin leer la configuración persistida, y eso suspende.
     *
     * Pasarse no pierde contenido —el transporte trocea— pero sí cuesta: cada trozo es un
     * mensaje más, y en modo plantilla cada mensaje se factura. Por eso el aviso merece ser
     * exacto en lugar de cómodo.
     */
    suspend fun limiteCaracteres(): Int

    /**
     * Entrega [texto] a [destino] y **espera la confirmación**.
     *
     * Esperar no es opcional ni es un detalle de este medio: si la función retornase antes de
     * saber el desenlace, todo reenvío parecería correcto y los fallos reales se perderían en
     * silencio. Cada implementación lo consigue como pueda —el SMS lo hacía suspendiendo hasta
     * el broadcast del `sentIntent`; aquí es esperar la respuesta HTTP— y todas respetan
     * [timeoutSegundos] como plazo máximo.
     *
     * @param destino identificador que entienda el transporte, tal como lo dejó la regla.
     * @param timeoutSegundos plazo tras el cual el intento se da por perdido y se reintenta.
     */
    suspend fun enviar(destino: String, texto: String, timeoutSegundos: Int): ResultadoEnvio

    /**
     * Explicación legible de por qué el transporte no puede enviar ahora mismo, o null si está
     * listo.
     *
     * Existe para que la pantalla de prueba pueda avisar **antes** de que alguien pulse enviar y
     * se quede sin saber por qué no pasó nada. No es una comprobación previa que [enviar] dé por
     * hecha: [enviar] valida lo suyo igualmente, porque entre una consulta y un envío puede
     * cambiar cualquier cosa.
     */
    suspend fun motivoNoDisponible(): String?
}
