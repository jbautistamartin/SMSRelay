// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.data.config

/**
 * Ajustes generales de la aplicación, editables en la pantalla de Configuración y
 * persistidos en DataStore<Preferences>.
 *
 * Aquí no se decide **a quién** se reenvía nada: eso lo determinan las reglas, que viven en la
 * base de datos porque son una lista ordenada. Esto son los parámetros que afectan a todos los
 * reenvíos por igual.
 */
data class AppConfig(
    /**
     * Token de acceso de la Cloud API, tal como lo entrega la consola de Meta.
     *
     * Vacío significa «sin configurar», y es el valor con el que arranca una instalación nueva:
     * **no hay ninguna credencial en el código**. Una escrita en el fuente de un APK es una
     * credencial regalada —el APK se puede descomprimir— y además sería la misma para todo el
     * mundo.
     *
     * Es una credencial, y eso impone tres cosas que el resto de los ajustes no necesitan: no
     * se escribe nunca en el log, se enmascara en la pantalla, y no viaja en el fichero de
     * copia de seguridad.
     *
     * El que sirve es un token de **usuario de sistema** con caducidad «Nunca». El que la consola
     * enseña por defecto en la pestaña de WhatsApp caduca en unas 24 horas, y con él la
     * aplicación se queda muda al día siguiente sin que nadie haya tocado nada.
     */
    val tokenAcceso: String = "",

    /**
     * `phone_number_id` del número emisor: la segunda credencial.
     *
     * Vacío significa «sin configurar». Va en la ruta de la petición, así que sin él no hay URL
     * a la que llamar — ver [com.capicua.smsrelay.data.whatsapp.IdNumeroTelefono] para por qué
     * la Cloud API necesita dos cosas donde el Bot API de Telegram necesitaba una.
     *
     * Se trata como credencial aunque no sea secreto: no viaja en la copia de seguridad, porque
     * identifica a *este* número emisor y restaurarlo en otro teléfono con otra cuenta dejaría
     * los reenvíos saliendo por donde no es. Es el papel que tenía `simActual` cuando el
     * transporte era el SMS.
     */
    val idNumeroTelefono: String = "",

    /**
     * Cómo sale cada reenvío: texto libre, plantilla, o lo que haga falta.
     *
     * Ver [ModoDeEntrega]. El valor por defecto entrega siempre sin gastar más de lo necesario,
     * que es lo que hace falta en un teléfono que nadie está mirando.
     */
    val modoDeEntrega: ModoDeEntrega = ModoDeEntrega.AUTOMATICO,

    /**
     * Nombre de la plantilla aprobada en el Business Manager, o vacío si no hay ninguna.
     *
     * Vacío no es un error de configuración: es una instalación que solo va a entregar dentro de
     * la ventana de 24 h. Lo que sí hace es dejar [ModoDeEntrega.AUTOMATICO] sin nada a lo que
     * saltar, y el motivo del fallo lo dice.
     *
     * No es una credencial, así que **sí viaja en la copia de seguridad**: describe *cómo*
     * reenviar, que es lo que el fichero existe para llevarse a otro teléfono.
     */
    val plantillaNombre: String = "",

    /**
     * Código de idioma de la plantilla, tal como se dio de alta.
     *
     * Una plantilla no existe «en general»: existe en un idioma, y pedirla en otro devuelve un
     * `132001`. El valor por defecto es `es` porque es el idioma de la aplicación; una plantilla
     * dada de alta como `es_ES` necesita escribirlo así.
     */
    val plantillaIdioma: String = "es",

    /** Número máximo de intentos antes de dar un reenvío por fallido permanentemente. */
    val maxReintentos: Int = 10,

    /** Espera inicial entre reintentos, en segundos. WorkManager la aplica como backoff. */
    val intervaloReintentoSegundos: Int = 30,

    /**
     * Segundos que se espera la confirmación del transporte antes de dar el intento por perdido
     * y reintentar.
     *
     * Existe porque un envío nunca se confirma en el acto, pero el motivo ha cambiado con el
     * medio: con `SmsManager` se esperaba el broadcast del `sentIntent`, que llegaba cuando el
     * operador contestaba; ahora es el plazo de la petición HTTP. Lo que no cambia es que sin
     * esperar, todo parecería enviado.
     */
    val timeoutEnvioSegundos: Int = 60,

    /**
     * Tope de reenvíos creados por minuto. El exceso se descarta y queda registrado en el log.
     *
     * **Esta protección cambia de significado por tercera vez, y es la tercera vez que sobrevive
     * por un motivo distinto del que la hizo nacer.**
     *
     * | Transporte | Para qué servía |
     * |------------|-----------------|
     * | SMS | Cortafuegos económico: un bucle con reintentos detrás era una factura |
     * | Telegram | Los límites del Bot API: un mensaje por segundo y chat. Entregar era gratis |
     * | Cloud API | Las dos cosas a la vez, y la económica aprieta más que nunca |
     *
     * El caudal de la Cloud API es holgado —del orden de 80 mensajes por segundo— así que por
     * ahí no hay problema. Lo que existe es el error `131056`, demasiados mensajes al mismo
     * destinatario en poco tiempo, y sobre todo el coste: ver [maxMensajesPorMes].
     *
     * Esto sigue siendo lo que corta una **avalancha**: una ráfaga de SMS en un minuto. El techo
     * mensual es otra cosa y la vigila otro ajuste.
     */
    val maxReenviosPorMinuto: Int = 10,

    /**
     * Tope de mensajes entregados en el mes natural. Pasado el tope, los SMS se descartan y
     * queda registrado en el log.
     *
     * **Es el límite que de verdad importa ahora, y el primero de este proyecto que mide dinero
     * en lugar de tiempo.** Meta regala los primeros 1.000 mensajes de servicio al mes y por
     * número, y factura todos los de plantilla; pasarse no da ningún error, da una factura.
     *
     * Mil al mes son **treinta y tres al día**. El tope por minuto no protege de esto en
     * absoluto: 10 por minuto son 14.400 al día, tres órdenes de magnitud por encima del primer
     * techo que se va a encontrar. Son dos protecciones distintas porque miden cosas distintas:
     * una corta una ráfaga, la otra corta un mes.
     *
     * El valor por defecto es 900, por debajo de los 1.000 gratuitos, para que una instalación
     * que nadie ha configurado no llegue a facturar nada. Quien quiera gastar más lo sube a
     * conciencia; `0` lo desactiva.
     *
     * Lo cuenta `ContadorDeMensajes`, que cuenta **mensajes entregados** y no reenvíos: un SMS
     * largo se trocea y Meta factura por mensaje.
     */
    val maxMensajesPorMes: Int = 900,

    /**
     * Interruptor general de la recepción. En false la aplicación deja de leer los SMS que
     * llegan: no se guardan, no se evalúan reglas y no se reenvía nada.
     *
     * No es una comprobación que se haga al procesar cada mensaje: lo que se apaga es el
     * propio receptor del sistema (ver `util/ControlRecepcion`), de modo que el SMS ni
     * siquiera despierta a la aplicación. Lo que ya estuviera encolado se termina de enviar;
     * esto corta la entrada, no la salida.
     */
    val recepcionActiva: Boolean = true
)
