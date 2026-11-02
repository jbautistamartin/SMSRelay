// SMSRelay — Web de pruebas del canal API (heredada de SMSGateway)
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using Capicua.SmsRelay.Pruebas.Web;
using Capicua.SmsRelay.Pruebas.Web.Endpoints;
using Capicua.SmsRelay.Pruebas.Web.Services;

// Publicada (el zip del release), la raíz es la carpeta del ejecutable, con su wwwroot y su
// sms_historial.json, se lance desde donde se lance. Con `dotnet run`, la del proyecto, como siempre.
var publicada = Directory.Exists(Path.Combine(AppContext.BaseDirectory, "wwwroot"));
var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = publicada ? AppContext.BaseDirectory : null
});

// En toda la red, para que el teléfono llegue: es lo que prueba. HTTP en el 5000 y HTTPS en el 5001
// con un certificado autofirmado que se genera al arrancar. No se usa el de desarrollo de .NET,
// porque en la versión publicada no existe y Kestrel no arrancaría; para que el teléfono acepte
// este hay que encender «Aceptar certificados no válidos» en los ajustes del canal API.
builder.WebHost.ConfigureKestrel(kestrel =>
{
    kestrel.ListenAnyIP(Puertos.Http);
    kestrel.ListenAnyIP(Puertos.Https, escucha => escucha.UseHttps(CertificadoDePruebas.Crear()));
});

builder.Services.AddSingleton<SmsHub>();
builder.Services.AddSingleton<LogFileService>();

var app = builder.Build();

app.UseDefaultFiles();   // sirve index.html automáticamente en /
app.UseStaticFiles();    // sirve wwwroot/

app.MapSmsEndpoints();

// Restaurar historial desde disco antes de aceptar peticiones
var logFile = app.Services.GetRequiredService<LogFileService>();
var hub     = app.Services.GetRequiredService<SmsHub>();

var historialPrevio = await logFile.CargarHistorialAsync();
if (historialPrevio.Count > 0)
{
    hub.InicializarHistorial(historialPrevio);
    app.Logger.LogInformation("Historial restaurado: {Count} SMS previos", historialPrevio.Count);
}

app.Logger.LogInformation("Historial de SMS → {Ruta}", logFile.RutaJson);

app.Run();
