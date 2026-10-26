# Registro de cambios

Todas las versiones publicadas de **SMSRelay**, con lo que cambió en cada una.
El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones,
[SemVer](https://semver.org/lang/es/).

> **SMSRelay nace de [SMStoWhats](https://github.com/jbautistamartin/SMStoWhats)** y junta en una
> sola aplicación lo que hacían cuatro: SMSGateway (API REST), SMStoSMS (SMS), SMStoTelegram
> (Telegram) y SMStoWhats (WhatsApp). Es una **aplicación distinta**, con `applicationId` propio y
> base de datos propia, y puede convivir con las cuatro en el mismo dispositivo. Por eso este
> registro empieza de cero en lugar de continuar el de ninguna de ellas. El plan de la mudanza
> está en `MASTERPLAN-RELAY.md`.

---

## [No publicado]

### Añadido

- **Cuatro canales, elegidos regla a regla**: WhatsApp (Cloud API de Meta), Telegram (un bot, a
  personas, grupos y canales), SMS (desde la SIM de este teléfono) y API (un `GET` a una URL con
  plantilla). El destino se valida según el canal, y el editor reconoce un chat de Telegram pegado
  con otro canal marcado.
- **Vuelve Telegram**, con la comprobación del token y *Elegir de los chats del bot*.
- **Vuelve el SMS**: contestar a quien escribió con `{telefono}` como destino, elegir la SIM de
  envío y la guarda antibucle, solo para este canal. `SEND_SMS` y `READ_PHONE_STATE` se piden
  cuando hacen falta, no al instalar.
- **Vuelve la API REST**, con la URL en la regla —cada regla puede apuntar a una API distinta—,
  `{mensaje}`, `{telefono}` y `{fecha}` URL-codificados, **nunca en el nombre del servidor**, sin
  seguir redirecciones y con la opción de aceptar certificados no válidos.
- **Tarjeta *Canales* en Ajustes**, con el estado de los cuatro y cuántas reglas usa cada uno, y
  avisos en el Panel que nombran el canal que no puede entregar.
- **Filtro por canal en el registro**, y el canal en cada entrada, en la lista de reglas y en
  *Probar reglas*.
- **Importar las copias de las cuatro aplicaciones anteriores**: SMStoWhats, SMStoTelegram,
  SMStoSMS (cualquier versión) y SMSGateway, cada regla con el canal que le corresponde. Al
  terminar, la pantalla dice de qué aplicación venía la copia y qué canales faltan por preparar.
- **Guía del bot de Telegram** en `docs/BOT-TELEGRAM.md`.
- **Cada canal se puede desactivar** desde la tarjeta *Canales* de Ajustes. Sus reglas se
  conservan pero no reenvían —el registro dice cuáles habrían casado—, lo ya encolado se entrega,
  y su sección de Ajustes y su opción en el editor de reglas desaparecen.
- **Icono y paleta propios.** Naranja, el único hueco que quedaba lejos de los cuatro colores
  de la familia, y un bocadillo con un punto y tres puntas caladas: un mensaje que sale hacia
  varios sitios.

### Cambiado

- **Formato de copia 6**: cada regla lleva su canal, y el fichero los ajustes de los cuatro. Al
  importar se aplican los ajustes comunes y los del canal del fichero, sin tocar los demás, y una
  credencial en blanco ya no pisa la del teléfono. El token del bot viaja si se pide, como las
  credenciales de WhatsApp; la SIM no viaja nunca.
- **El tope mensual es solo de WhatsApp**: al agotarse, deja de reenviar por WhatsApp y los demás
  canales siguen. El tope por minuto cuenta los reenvíos de todos los canales juntos. Cada
  descarte dice en el registro qué tope o qué guarda lo disparó.
- **El aviso de privacidad y el descargo de responsabilidad hablan de los cuatro canales.** Lo que
  decían de WhatsApp se conserva entero.
- **Renombrado a SMSRelay**: `com.capicua.smsrelay`, APK `smsrelay-<versión>.apk`, base de datos
  y ajustes con nombre propio, repositorio `jbautistamartin/SMSRelay`.
- **Esquema de Room en una v1 limpia**, por cuarta vez: ningún teléfono tiene esta base de datos.
- **La firma de la plantilla de WhatsApp recomendada pasa a «— SMSRelay».** Una plantilla ya
  aprobada con la firma de SMStoWhats sigue sirviendo; el presupuesto dentro de la plantilla
  sube de 978 a 980 caracteres.
