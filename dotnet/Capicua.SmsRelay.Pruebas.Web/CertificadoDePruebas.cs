// SMSRelay — Web de pruebas del canal API (heredada de SMSGateway)
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Capicua.SmsRelay.Pruebas.Web;

/// <summary>
/// El certificado del puerto HTTPS: autofirmado, nuevo en cada arranque y solo en memoria.
/// </summary>
/// <remarks>
/// Sirve para probar el canal API por HTTPS con «Aceptar certificados no válidos» encendido en el
/// teléfono, que es justo lo que ese ajuste existe para permitir. No se guarda en disco porque no
/// protege nada: es un servidor de pruebas que enseña en el navegador lo que recibe.
/// </remarks>
public static class CertificadoDePruebas
{
    public static X509Certificate2 Crear()
    {
        using var clave = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var peticion = new CertificateRequest("CN=SMSRelay Web de pruebas", clave, HashAlgorithmName.SHA256);
        peticion.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        peticion.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, false));
        peticion.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            [new Oid("1.3.6.1.5.5.7.3.1")], false)); // autenticación de servidor

        var nombres = new SubjectAlternativeNameBuilder();
        nombres.AddDnsName("localhost");
        nombres.AddDnsName(Environment.MachineName);
        peticion.CertificateExtensions.Add(nombres.Build());

        using var efimero = peticion.CreateSelfSigned(DateTimeOffset.Now.AddDays(-1), DateTimeOffset.Now.AddYears(1));
        // Exportado e importado como PFX: en Windows, SslStream no puede usar una clave efímera
        // que no ha pasado por un almacén ("No credentials are available in the security package").
        return X509CertificateLoader.LoadPkcs12(efimero.Export(X509ContentType.Pfx), null);
    }
}
