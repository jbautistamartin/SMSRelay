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

- **Icono y paleta propios.** Naranja, el único hueco que quedaba lejos de los cuatro colores
  de la familia, y un bocadillo con un punto y tres puntas caladas: un mensaje que sale hacia
  varios sitios.

### Cambiado

- **Renombrado a SMSRelay**: `com.capicua.smsrelay`, APK `smsrelay-<versión>.apk`, base de datos
  y ajustes con nombre propio, repositorio `jbautistamartin/SMSRelay`.
- **Esquema de Room en una v1 limpia**, por cuarta vez: ningún teléfono tiene esta base de datos.
- **La firma de la plantilla de WhatsApp recomendada pasa a «— SMSRelay».** Una plantilla ya
  aprobada con la firma de SMStoWhats sigue sirviendo; el presupuesto dentro de la plantilla
  sube de 978 a 980 caracteres.
