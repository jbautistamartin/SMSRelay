// SMStoWhats para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstowhats.data.whatsapp

/**
 * El contrato de la plantilla, y las dos transformaciones que hacen que un SMS quepa en ella.
 *
 * ## Por qué esto existe, y por qué no es el plan B
 * La Cloud API solo deja mandar **texto libre** dentro de una ventana de 24 h que abre el
 * destinatario escribiendo al número emisor. Un reenviador de SMS es, por definición, un emisor
 * no solicitado: el SMS llega cuando llega y hay que entregarlo entonces, no cuando al
 * destinatario le apetezca abrir una ventana.
 *
 * Así que **la plantilla es el camino principal de esta aplicación** y el texto libre es la
 * optimización que se aplica cuando resulta que la ventana está abierta. Es lo contrario de lo
 * que uno supondría, y es la razón de que esta clase no sea un añadido opcional.
 *
 * ## El contrato: una sola variable
 * La plantilla la crea el operador en el Business Manager de Meta —esta aplicación no puede
 * crearla— y tiene que tener este cuerpo exacto:
 *
 * ```
 * Reenvío de SMS
 *
 * {{1}}
 *
 * — SMStoWhats
 * ```
 *
 * Tres propiedades, y las tres son a propósito:
 *
 * 1. **Los saltos de línea están en la plantilla, no en la variable.** Meta aprueba el cuerpo
 *    una vez, con su maquetación dentro. Lo que **no** puede llevar saltos de línea, ni
 *    tabuladores, ni más de cuatro espacios seguidos, es el *valor* que se pasa en `{{1}}`:
 *    hacerlo devuelve un error `132007` y el reenvío muere. De ahí [aplanar].
 * 2. **No empieza ni acaba con la variable**, que Meta no admite.
 * 3. **Una variable y no tres.** La alternativa —remitente, fecha y texto en tres huecos— daría
 *    mejor maquetación y rompería algo que importa más: habría una **segunda** vía de
 *    composición del mensaje, la de la plantilla, que ignoraría la `plantilla` de cada regla.
 *    Con una variable el texto lo compone siempre `EvaluadorDeReglas`, en los dos modos, y la
 *    plantilla de la regla sigue significando lo mismo. Se paga en estética y se cobra en no
 *    tener dos caminos.
 *
 * ## Por qué el presupuesto se calcula del contrato y no se pregunta
 * El límite de Meta es de 1.024 caracteres **renderizados**: el cuerpo aprobado más lo que se
 * sustituya dentro. La aplicación no conoce ese cuerpo —vive en la cuenta de Meta— así que el
 * presupuesto se deriva del contrato de arriba, que es parte de la especificación de esta
 * aplicación y está escrito en el texto de ayuda de Ajustes. Si alguien cambia el cuerpo
 * recomendado, cambia [CUERPO_RECOMENDADO] y el presupuesto se recalcula solo.
 */
object PlantillaDeWhatsApp {

    /**
     * Longitud máxima del cuerpo renderizado de un mensaje de plantilla.
     *
     * Un cuarto de lo que admite el texto libre, y esa es la otra cara del precio de poder
     * escribir fuera de la ventana.
     */
    const val LIMITE_RENDERIZADO = 1024

    /** El hueco de la plantilla. Una sola variable; ver el KDoc de la clase. */
    const val MARCADOR = "{{1}}"

    /** El cuerpo que el operador tiene que dar de alta en el Business Manager. */
    const val CUERPO_RECOMENDADO = "Reenvío de SMS\n\n$MARCADOR\n\n— SMStoWhats"

    /**
     * Lo que se reserva por si Meta cuenta algún carácter de forma distinta.
     *
     * Quedarse corto aquí no cuesta nada —un puñado de caracteres en un mensaje de 992— y
     * pasarse cuesta un `132005` que cierra el reenvío como fallido permanente.
     */
    private const val MARGEN = 16

    /**
     * Caracteres disponibles para el valor de [MARCADOR].
     *
     * Es el límite renderizado menos el texto fijo del contrato, menos el margen.
     */
    val PRESUPUESTO: Int =
        LIMITE_RENDERIZADO - (CUERPO_RECOMENDADO.length - MARCADOR.length) - MARGEN

    /**
     * Convierte un texto válido para WhatsApp en un valor válido para una variable de plantilla.
     *
     * Es la transformación que hace posible todo lo demás, y la que explica por qué el contrato
     * puede tener una sola variable: la maquetación la pone la plantilla aprobada, el contenido
     * entra en una línea.
     *
     * | Qué | En qué se convierte | Por qué |
     * |-----|---------------------|---------|
     * | Uno o varios saltos de línea | ` · ` | Meta rechaza el salto de línea en un parámetro |
     * | Tabuladores | un espacio | Meta los rechaza igual |
     * | Espacios seguidos | un espacio | Más de cuatro seguidos se rechazan |
     *
     * Los saltos consecutivos se colapsan en **un** separador: un SMS con una línea en blanco
     * en medio no debe salir con ` ·  · ` dentro.
     *
     * No pierde contenido: solo sustituye separadores por otros. Lo que se pierde es la
     * maquetación original del SMS, que es el precio de poder entregarlo fuera de la ventana.
     */
    fun aplanar(texto: String): String = texto
        // El recorte va **primero**: un salto de línea al principio o al final no es una
        // separación entre dos cosas, y convertirlo en separador deja un « · » colgando que el
        // recorte posterior ya no puede quitar porque no es un espacio.
        .trim()
        .replace(SALTOS, SEPARADOR)
        .replace(BLANCOS, " ")
        .trim()

    /**
     * Prepara el texto para ir en la plantilla: lo aplana y lo parte si no cabe.
     *
     * Partir en varios mensajes de plantilla **cuesta dinero** —cada uno se factura— y aun así
     * es lo correcto: truncar perdería contenido en silencio, y dejar que Meta conteste
     * `132005` convertiría un SMS largo en un reenvío fallido. Con el presupuesto en 992
     * caracteres hace falta un SMS concatenado largo más una plantilla generosa para llegar
     * aquí, igual que pasaba con los 4.096 del texto libre.
     *
     * Devuelve una lista vacía si al aplanar no queda nada, porque un parámetro vacío es un
     * error `131009` y quien llama tiene que poder decirlo con sus palabras.
     */
    fun prepararParametros(texto: String): List<String> {
        val plano = aplanar(texto)
        if (plano.isEmpty()) return emptyList()
        return MapeoDeRespuestas.trocear(plano, PRESUPUESTO)
    }

    /** Lo que se mete entre dos líneas al aplanarlas. */
    private const val SEPARADOR = " · "

    /**
     * Uno o más saltos de línea, **con todo el blanco que les rodee**.
     *
     * Absorber lo de alrededor es lo que evita que una línea con espacios de sobra al final deje
     * esos espacios pegados al separador, y lo que colapsa varios saltos seguidos en uno solo.
     */
    private val SALTOS = Regex("""\s*\R+\s*""")

    /**
     * Cualquier otro blanco, suelto o repetido.
     *
     * Suelto también, y no solo repetido: un tabulador único es ilegal en un parámetro de
     * plantilla igual que cinco espacios seguidos, y la primera versión de esto solo miró los
     * repetidos. Lo atraparon los tests.
     */
    private val BLANCOS = Regex("""\s+""")
}
