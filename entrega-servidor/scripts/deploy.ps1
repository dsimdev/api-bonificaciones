<#
.SYNOPSIS
  Compila, sube el jar por FTP y reinicia el servicio.

.DESCRIPTION
  Mismo enfoque que el deploy.ps1 de MotorFiscal, con una diferencia que importa: acá el jar que
  se deploya es el de PRODUCCIÓN, compilado con -PpanelBasePath. El basePath del panel queda
  horneado en el HTML y el JS, así que el jar que anda detrás del proxy anidado NO es el mismo que
  se prueba en local. Este script lo compila bien y verifica que esté subiendo ese, porque la
  alternativa es un deploy donde la API anda y el panel se ve en blanco.

  Corre el checklist antes de tocar el servidor: build + tests, que la versión del build coincida
  con .claude/VERSION, y que exista el tag. Después sube el jar con NOMBRE VERSIONADO, y recién ahí
  detiene el servicio para reemplazarlo.

  Ese orden es a propósito. En Windows un jar en uso está bloqueado: si se sube encima del que
  está corriendo, el FTP falla a la mitad y queda un jar corrupto. Subiendo primero con otro
  nombre, el gateway sigue andando mientras se transfiere y la ventana de caída es solo el
  reinicio. De paso quedan las versiones anteriores en el servidor, así que volver atrás es copiar
  un archivo.

.PARAMETER Servidor
  Host del servidor (para FTP y para reiniciar el servicio por WinRM).

.PARAMETER RutaVirtual
  Dónde lo cuelga IIS. TIENE que ser la misma que se le pasó a preparar-iis.ps1: de acá sale el
  -PpanelBasePath con el que se compila el panel.

.PARAMETER SinVerificar
  Saltea build y tests. Solo para reintentar una subida que falló por red.

.EXAMPLE
  .\scripts\deploy.ps1 -Servidor 10.0.0.5 -Usuario deploy
#>

param(
    [Parameter(Mandatory = $true)][string]$Servidor,
    [Parameter(Mandatory = $true)][string]$Usuario,
    [string]$RutaVirtual = 'api/bonificaciones',
    [string]$RutaRemota = '/bonificaciones',
    [string]$Servicio = 'bonificaciones',
    [string]$UrlSalud = '',
    [switch]$SinVerificar
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Paso($n, $texto) { Write-Host "`n[$n] $texto" -ForegroundColor Cyan }
function Bien($texto) { Write-Host "    $texto" -ForegroundColor Green }
function Mal($texto) { Write-Host "    $texto" -ForegroundColor Red }
function Ojo($texto) { Write-Host "    $texto" -ForegroundColor Yellow }

$version = (Get-Content .claude\VERSION -Raw).Trim()
$RutaVirtual = $RutaVirtual.Trim('/')
$basePath = "/$RutaVirtual/admin"

# El jar de produccion lleva el classifier "prod" (ver bonif-app/build.gradle.kts). Sin eso los
# dos builds se llamarian igual y un build local posterior pisaria en silencio el que ya estaba
# verificado -- paso dos veces en api-impuestos.
$jarLocal = "bonif-app\build\libs\bonif-app-$version-prod.jar"
if ($UrlSalud -eq '') { $UrlSalud = "https://$Servidor/$RutaVirtual/health" }

Write-Host "api-bonificaciones $version -> $Servidor/$RutaVirtual" -ForegroundColor White
Write-Host "  panel compilado para: $basePath" -ForegroundColor DarkGray

if (-not $SinVerificar) {
    Paso 1 'Build y tests'
    # Sin -PpanelBasePath: este build es el que corre los tests, y el panel local alcanza.
    & .\gradlew.bat build --console=plain -q
    if ($LASTEXITCODE -ne 0) { Mal 'El build fallo. No se sube nada.'; exit 1 }
    Bien 'verde'

    Paso 2 'Coherencia de version'
    $enGradle = (Select-String -Path build.gradle.kts -Pattern 'version = "([^"]+)"').Matches[0].Groups[1].Value
    if ($enGradle -ne $version) {
        Mal ".claude/VERSION dice $version y build.gradle.kts dice $enGradle. Se bumpean juntos."
        exit 1
    }
    $tag = git tag --list "v$version"
    if (-not $tag) { Mal "Falta el tag v$version. Crealo antes de deployar."; exit 1 }
    Bien "$version, con tag"

    Paso 3 "Compilando el jar de PRODUCCION (panelBasePath=$basePath)"
    # Este es el paso que no se puede saltear. El jar local y el de produccion difieren en el
    # basePath horneado en el panel: deployar el local deja la API andando y el panel en blanco
    # con 404 en la consola. En api-impuestos esa clase de bug llego a produccion tres veces.
    & .\gradlew.bat :bonif-app:bootJar --console=plain -q "-PpanelBasePath=$basePath"
    if ($LASTEXITCODE -ne 0) { Mal 'Fallo el build de produccion.'; exit 1 }
    Bien "compilado"
}

if (-not (Test-Path $jarLocal)) {
    Mal "No existe $jarLocal."
    Ojo 'Ese nombre lo produce el build con -PpanelBasePath. Corre sin -SinVerificar.'
    exit 1
}

# Que el panel de adentro del jar sea el de ESTA ruta. Es barato de verificar y es exactamente el
# error que no se nota hasta que alguien abre el panel en produccion.
Paso 4 'Verificando el basePath que quedo dentro del jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $jarLocal))
try {
    $index = $zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/admin/index.html' }
    if (-not $index) {
        Ojo 'El jar no tiene panel adentro (se compilo sin Node). La API va a andar; /admin no.'
    } else {
        $lector = New-Object System.IO.StreamReader($index.Open())
        $html = $lector.ReadToEnd()
        $lector.Close()
        if ($html -match [regex]::Escape("$basePath/_next/")) {
            Bien "el panel pide sus chunks en $basePath/_next/ -- correcto"
        } else {
            Mal "El panel NO esta compilado para $basePath."
            Ojo 'Deployarlo asi deja el panel en blanco con 404 en la consola del navegador.'
            Ojo "Recompilar: .\gradlew.bat :bonif-app:bootJar -PpanelBasePath=$basePath"
            exit 1
        }
    }
} finally {
    $zip.Dispose()
}

Paso 5 "Subiendo $jarLocal por FTP"
$credencial = Get-Credential -UserName $Usuario -Message "FTP en $Servidor"
$destino = "ftp://$Servidor$RutaRemota/bonificaciones-$version.jar"

# Con nombre versionado: el servicio sigue corriendo sobre bonificaciones.jar y el archivo nuevo
# no choca con ningun lock.
$cliente = New-Object System.Net.WebClient
$cliente.Credentials = $credencial.GetNetworkCredential()
try {
    $cliente.UploadFile($destino, 'STOR', (Resolve-Path $jarLocal))
    Bien "subido como bonificaciones-$version.jar"
} catch {
    Mal "Fallo la subida: $($_.Exception.Message)"
    exit 1
} finally {
    $cliente.Dispose()
}

Paso 6 'Reiniciando el servicio'
Write-Host '    Requiere permisos de administrador sobre el servidor.' -ForegroundColor DarkGray
try {
    Invoke-Command -ComputerName $Servidor -ErrorAction Stop -ScriptBlock {
        param($ruta, $ver, $svc)
        Stop-Service $svc -Force
        # El copy va DESPUES del stop: con el servicio corriendo el jar esta bloqueado.
        Copy-Item "$ruta\bonificaciones-$ver.jar" "$ruta\bonificaciones.jar" -Force
        Start-Service $svc
    } -ArgumentList 'C:\bonificaciones', $version, $Servicio
    Bien 'servicio reiniciado'
} catch {
    Mal "No se pudo reiniciar por WinRM: $($_.Exception.Message)"
    Write-Host ''
    Write-Host '    El jar YA ESTA SUBIDO. Para terminar a mano, en el servidor:' -ForegroundColor Yellow
    Write-Host "      Stop-Service $Servicio" -ForegroundColor Yellow
    Write-Host "      Copy-Item C:\bonificaciones\bonificaciones-$version.jar C:\bonificaciones\bonificaciones.jar -Force" -ForegroundColor Yellow
    Write-Host "      Start-Service $Servicio" -ForegroundColor Yellow
    exit 1
}

Paso 7 'Verificando a traves del proxy'
# A traves del proxy y no contra localhost: contra localhost el panel anda igual con el basePath
# mal, asi que esa verificacion no detecta nada.
$intentos = 0
while ($intentos -lt 20) {
    Start-Sleep -Seconds 3
    $intentos++
    try {
        $salud = Invoke-RestMethod -Uri $UrlSalud -TimeoutSec 5 -ErrorAction Stop
        if ($salud.version -ne $version) {
            # Sigue corriendo el jar viejo: el reinicio no tomo el archivo nuevo.
            Mal "Responde version $($salud.version), se esperaba $version."
            exit 1
        }
        if ($salud.origen -ne 'BASE') {
            # Levanto leyendo variables de entorno en vez de la base: las altas que se hagan por
            # el panel no van a estar al reiniciar.
            Mal "origen = $($salud.origen), se esperaba BASE. Revisar DB_URL/DB_USER/DB_PASSWORD."
            exit 1
        }
        Bien "$UrlSalud responde $($salud.version), leyendo de la base, $($salud.total) distribuidoras"

        # El panel es la otra mitad del deploy y es la que falla distinto: la API puede estar
        # perfecta y el panel en blanco.
        try {
            $panel = Invoke-WebRequest -Uri "https://$Servidor/$RutaVirtual/admin" -TimeoutSec 10 `
                -UseBasicParsing -ErrorAction Stop
            if ($panel.Content -match [regex]::Escape("/$RutaVirtual/admin/_next/")) {
                Bien 'el panel carga y pide sus chunks en la ruta correcta'
            } else {
                Mal 'El panel responde pero NO pide sus chunks en la ruta del proxy.'
                Ojo 'Se va a ver en blanco. Revisar con que -PpanelBasePath se compilo.'
                exit 1
            }
        } catch {
            Mal "El panel no responde: $($_.Exception.Message)"
            Ojo 'La API anda. Revisar la aplicacion de IIS (preparar-iis.ps1).'
            exit 1
        }

        Write-Host "`nDeploy completo." -ForegroundColor Green
        exit 0
    } catch { }
}
Mal "$UrlSalud no respondio a tiempo. Revisar C:\bonificaciones\logs en el servidor."
exit 1
