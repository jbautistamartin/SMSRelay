// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.config

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
     * a la que llamar — ver [com.capicua.smstowhats.data.whatsapp.IdNumeroTelefono] para por qué
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
     * **Es la única de las tres protecciones originales que sigue viva, y ya no por el mismo
     * motivo.** Cuando el transporte era el SMS, esto era un cortafuegos económico: un bucle de
     * reenvíos con los reintentos detrás se convertía en una factura, y por eso había además dos
     * guardias antibucle que comparaban destinos con remitentes.
     *
     * Entregar por Telegram es gratis y no puede formar bucles —un mensaje de Telegram no vuelve
     * nunca como SMS—, así que aquellas dos guardias se fueron. Esta se queda porque los límites
     * de quien recibe siguen existiendo: el Bot API admite del orden de 30 mensajes por segundo
     * en total, **uno por segundo y chat** y unos 20 por minuto en un grupo. Pasarse no cuesta
     * dinero, cuesta `429 Too Many Requests`, y con un grupo de destino eso llega enseguida.
     *
     * El valor por defecto —10 por minuto— queda holgadamente por debajo de cualquiera de esos
     * techos y es más que suficiente para los SMS que recibe un teléfono real.
     */
    val maxReenviosPorMinuto: Int = 10,

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
