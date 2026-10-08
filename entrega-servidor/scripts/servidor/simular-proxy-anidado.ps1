<#
.SYNOPSIS
  Reproduce LOCALMENTE, sin IIS, la topologia real de produccion: el gateway colgado como
  aplicacion anidada bajo un sitio que ya existe (ver web.config y preparar-iis.ps1).

.DESCRIPTION
  Copiado de api-impuestos, y ese script existe por un motivo que acá aplica igual: en MotorFiscal
  TRES bugs de produccion distintos (el redirect de /admin, y dos veces las URLs de Swagger)
  fueron la MISMA clase de error -- una URL absoluta que la app arma para SI MISMA, que es
  invisible cuando se prueba contra localhost directo. Las tres veces se encontro DESPUES de un
  deploy real, porque no habia forma de probar la topologia anidada sin tocar el servidor.

  Esta es la forma. Replica exactamente lo que hace el web.config real -- pelar el prefijo de la
  ruta virtual y reenviar, SIN agregar X-Forwarded-Prefix ni ningun header equivalente -- para
  poder recorrer toda la superficie de la app en esta topologia antes de pedirle a nadie que
  reinicie nada.

  No es un proxy generico: es deliberadamente TAN simple como el rewrite real (ver
  scripts/servidor/web.config), para que lo que funciona o rompe aca sea lo que funciona o rompe
  en IIS. Si manana cambia el rewrite, este script tiene que cambiar con el.

  ⚠️ Para que la prueba sirva, el jar tiene que ser el de PRODUCCION, compilado con
  -PpanelBasePath: el basePath queda horneado en el HTML y el JS. Con el jar local, el panel va a
  pedir sus chunks en la raiz del dominio y se va a ver en blanco -- que es, justamente, lo que
  este script esta hecho para mostrar antes de un deploy.

.PARAMETER RutaVirtual
  Mismo nombre y mismo default que preparar-iis.ps1 -RutaVirtual: donde IIS lo cuelga en
  produccion.

.PARAMETER PuertoProxy
  Donde escucha este script, o sea lo que el navegador usa para simular "el dominio de la tienda".
  Default 9000: no pisa ni el 8081 nuestro ni el 8080 de MotorFiscal.

.PARAMETER PuertoGateway
  Donde ya tiene que estar corriendo el jar, arrancado aparte como siempre.

.EXAMPLE
  # Terminal 1: el jar de PRODUCCION
  .\gradlew.bat bootJar -PpanelBasePath=/api/bonificaciones/admin
  java -jar bonif-app\build\libs\bonif-app-X.Y.Z-prod.jar

  # Terminal 2: el simulador
  .\scripts\servidor\simular-proxy-anidado.ps1

  # Y recorrer como si fuera produccion:
  #   http://localhost:9000/api/bonificaciones/health
  #   http://localhost:9000/api/bonificaciones/admin
  #   http://localhost:9000/api/bonificaciones/swagger-ui/index.html
#>
param(
    [string]$RutaVirtual = 'api/bonificaciones',
    [int]$PuertoProxy = 9000,
    [int]$PuertoGateway = 8081
)

Add-Type -AssemblyName System.Net.Http

$prefijo = "/$RutaVirtual".TrimEnd('/')
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$PuertoProxy/")
$listener.Start()

$cliente = New-Object System.Net.Http.HttpClient
$cliente.Timeout = [TimeSpan]::FromSeconds(30)

# Headers que HttpListenerResponse/HttpClient ya manejan por su cuenta -- copiarlos a mano tira
# excepcion ("estos headers se pisan solos") en vez de romper el proxy.
$headersDeSaltear = @('Transfer-Encoding', 'Content-Length', 'Connection', 'Keep-Alive')

Write-Host "Simulando IIS (web.config real, sin X-Forwarded-Prefix):"
Write-Host "  http://localhost:$PuertoProxy$prefijo/...  ->  http://localhost:$PuertoGateway/..."
Write-Host "Ctrl+C para frenar."

try {
    while ($listener.IsListening) {
        $contexto = $listener.GetContext()
        $req = $contexto.Request
        $resp = $contexto.Response
        try {
            $path = $req.Url.AbsolutePath
            if ($path -eq $prefijo -or $path.StartsWith("$prefijo/")) {
                $resto = $path.Substring($prefijo.Length)
                if ([string]::IsNullOrEmpty($resto)) { $resto = '/' }
                $destino = "http://localhost:$PuertoGateway$resto$($req.Url.Query)"

                $pedido = New-Object System.Net.Http.HttpRequestMessage(
                    [System.Net.Http.HttpMethod]::new($req.HttpMethod), $destino)
                if ($req.HasEntityBody) {
                    $ms = New-Object System.IO.MemoryStream
                    $req.InputStream.CopyTo($ms)
                    $pedido.Content = New-Object System.Net.Http.ByteArrayContent(, $ms.ToArray())
                    if ($req.ContentType) {
                        $pedido.Content.Headers.TryAddWithoutValidation('Content-Type', $req.ContentType) | Out-Null
                    }
                }
                foreach ($clave in $req.Headers.AllKeys) {
                    if ($clave -notin @('Host', 'Content-Length', 'Content-Type')) {
                        $pedido.Headers.TryAddWithoutValidation($clave, $req.Headers[$clave]) | Out-Null
                    }
                }
                # A proposito NO se agrega X-Forwarded-Prefix ni Forwarded: el web.config real
                # tampoco lo hace. Es justo la ausencia que rompe cualquier URL absoluta.

                $respuesta = $cliente.SendAsync($pedido).GetAwaiter().GetResult()
                $resp.StatusCode = [int]$respuesta.StatusCode
                foreach ($h in $respuesta.Headers) {
                    if ($h.Key -notin $headersDeSaltear) {
                        try { foreach ($v in $h.Value) { $resp.Headers.Add($h.Key, $v) } } catch {}
                    }
                }
                $cuerpo = $respuesta.Content.ReadAsByteArrayAsync().Result
                foreach ($h in $respuesta.Content.Headers) {
                    if ($h.Key -eq 'Content-Type') { $resp.ContentType = ($h.Value -join ';') }
                }
                $resp.ContentLength64 = $cuerpo.Length
                $resp.OutputStream.Write($cuerpo, 0, $cuerpo.Length)
            }
            else {
                # Fuera de la ruta virtual: en produccion real esto cae en el sitio de la
                # tienda, no en el gateway -- aca simplemente no esta enrutado.
                $resp.StatusCode = 404
                $msg = [System.Text.Encoding]::UTF8.GetBytes(
                    "Fuera de $prefijo -- fuera de la app anidada, no enrutado (simulado).")
                $resp.ContentLength64 = $msg.Length
                $resp.OutputStream.Write($msg, 0, $msg.Length)
            }
        }
        catch {
            $resp.StatusCode = 502
            $msg = [System.Text.Encoding]::UTF8.GetBytes("Error de proxy: $($_.Exception.Message)")
            $resp.ContentLength64 = $msg.Length
            $resp.OutputStream.Write($msg, 0, $msg.Length)
        }
        finally {
            $resp.OutputStream.Close()
        }
    }
}
finally {
    $listener.Stop()
}
