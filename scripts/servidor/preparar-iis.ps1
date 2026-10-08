<#
.SYNOPSIS
  Cuelga api-bonificaciones de un sitio de IIS que YA existe, como aplicacion anidada
  (ej. tudominio.com/api/bonificaciones) -- sin tocar el sitio ni las otras aplicaciones.

.DESCRIPTION
  Mucho mas corto que el preparar-iis.ps1 de MotorFiscal, y a proposito: ese tenia que resolver un
  IIS vacio (crear el sitio, el binding HTTPS, instalar ARR y URL Rewrite). Aca eso ya esta hecho,
  porque MotorFiscal corre en este mismo servidor detras de este mismo IIS. Lo unico que falta es
  una aplicacion mas.

  Una "aplicacion" de IIS tiene su PROPIO web.config y su PROPIO application pool: IIS no mezcla
  su configuracion con la del sitio padre ni con la de sus hermanas. Por eso esto no puede romper
  lo que ya funciona ahi.

  ⚠️ NO probado contra un servidor real. Si `New-Item -type Application` da un error raro, el
  camino manual por IIS Manager es mas confiable que insistir con PowerShell:
    Sites -> [tu sitio] -> click derecho en la carpeta "api" -> Add Application
    Alias: bonificaciones   Physical path: la misma que -RutaFisica
  Despues copiar el web.config a esa carpeta a mano.

.PARAMETER SitioExistente
  Nombre EXACTO del sitio de IIS, tal cual lo muestra `Get-Website`. Correrlo primero.

.PARAMETER RutaVirtual
  La ruta dentro del sitio. Default 'api/bonificaciones'.

  ⚠️ Lo que se pase aca tiene que coincidir con el -PpanelBasePath con el que se compilo el jar
  (/<RutaVirtual>/admin). Si no coinciden, la API anda y EL PANEL QUEDA EN BLANCO con 404 en la
  consola del navegador. Es el bug que en api-impuestos llego a produccion tres veces.

.PARAMETER RutaFisica
  Carpeta para el web.config de la aplicacion. Default 'C:\inetpub\bonificaciones'.
  No tiene contenido: lo unico que hay ahi es el web.config que hace el proxy.

.EXAMPLE
  Get-Website
  .\preparar-iis.ps1 -SitioExistente 'tienda.midominio.com'
#>

param(
    [Parameter(Mandatory = $true)][string]$SitioExistente,
    [string]$RutaVirtual = 'api/bonificaciones',
    [string]$RutaFisica = 'C:\inetpub\bonificaciones',
    [string]$Puerto = '8081'
)

$ErrorActionPreference = 'Stop'

function Paso($n, $texto) { Write-Host "`n[$n] $texto" -ForegroundColor Cyan }
function Bien($texto) { Write-Host "    $texto" -ForegroundColor Green }
function Ya($texto) { Write-Host "    $texto (ya estaba)" -ForegroundColor DarkGray }
function Mal($texto) { Write-Host "    $texto" -ForegroundColor Red }
function Ojo($texto) { Write-Host "    $texto" -ForegroundColor Yellow }

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
        ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Mal 'Hace falta correr esto como administrador.'
    exit 1
}

Import-Module WebAdministration -ErrorAction Stop

$RutaVirtual = $RutaVirtual.Trim('/')

Write-Host "api-bonificaciones -> $SitioExistente/$RutaVirtual -> localhost:$Puerto" -ForegroundColor White

# --- 1. Que el sitio exista de verdad -----------------------------------------------------------
Paso 1 "El sitio '$SitioExistente'"
$sitio = Get-Website -Name $SitioExistente -ErrorAction SilentlyContinue
if (-not $sitio) {
    Mal "No existe un sitio llamado '$SitioExistente'."
    Ojo 'Los que hay:'
    Get-Website | ForEach-Object { Ojo "  $($_.Name)" }
    exit 1
}
Bien "encontrado (estado: $($sitio.State))"

# --- 2. ARR y URL Rewrite -----------------------------------------------------------------------
# No se instalan desde aca: si MotorFiscal anda en este servidor, ya estan. Si faltan, instalarlos
# reinicia IIS y eso lo decide quien administra el servidor, no un script nuestro.
Paso 2 'ARR y URL Rewrite (los necesita el proxy)'
$rewrite = Test-Path 'HKLM:\SOFTWARE\Microsoft\IIS Extensions\URL Rewrite'
$arr = (Get-WebGlobalModule -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*ApplicationRequestRouting*' })
if ($rewrite) { Bien 'URL Rewrite instalado' } else { Mal 'FALTA URL Rewrite' }
if ($arr) { Bien 'ARR instalado' } else { Mal 'FALTA Application Request Routing' }
if (-not ($rewrite -and $arr)) {
    Ojo 'Sin esos dos modulos el proxy no funciona. Si MotorFiscal ya corre detras de este IIS,'
    Ojo 'tendrian que estar: verificar que este script este corriendo en el servidor correcto.'
    Ojo 'Descargas: https://www.iis.net/downloads/microsoft/url-rewrite'
    Ojo '            https://www.iis.net/downloads/microsoft/application-request-routing'
    exit 1
}

# El proxy global de ARR tiene que estar habilitado. Lo esta si MotorFiscal anda, pero se verifica
# porque sin esto el rewrite devuelve 404 y parece un problema de la regla.
$proxyActivo = (Get-WebConfigurationProperty -PSPath 'MACHINE/WEBROOT/APPHOST' `
        -Filter 'system.webServer/proxy' -Name 'enabled' -ErrorAction SilentlyContinue).Value
if ($proxyActivo) {
    Bien 'el proxy de ARR esta habilitado'
} else {
    Set-WebConfigurationProperty -PSPath 'MACHINE/WEBROOT/APPHOST' `
        -Filter 'system.webServer/proxy' -Name 'enabled' -Value $true
    Bien 'proxy de ARR habilitado'
}

# --- 3. Carpeta fisica + web.config -------------------------------------------------------------
Paso 3 "Carpeta $RutaFisica"
if (-not (Test-Path $RutaFisica)) {
    New-Item -ItemType Directory $RutaFisica -Force | Out-Null
    Bien 'creada'
} else {
    Ya 'existe'
}

$plantilla = Join-Path $PSScriptRoot 'web.config'
if (-not (Test-Path $plantilla)) {
    Mal "Falta $plantilla. Copiar la carpeta scripts\servidor\ completa al servidor."
    exit 1
}
# El puerto se reemplaza por si alguien corre esto con otro: el web.config del repo dice 8081.
(Get-Content $plantilla -Raw).Replace('localhost:8081', "localhost:$Puerto") |
    Set-Content (Join-Path $RutaFisica 'web.config') -Encoding UTF8
Bien "web.config escrito, apuntando a localhost:$Puerto"

# --- 4. Application pool propio -----------------------------------------------------------------
# Propio y no el del sitio: asi un reciclado o un cuelgue de la aplicacion del proxy no afecta al
# sitio padre ni a MotorFiscal. Sin codigo administrado adentro (el proxy es nativo).
Paso 4 'Application pool'
$pool = 'bonificaciones'
if (Get-ChildItem IIS:\AppPools | Where-Object { $_.Name -eq $pool }) {
    Ya "pool '$pool'"
} else {
    New-WebAppPool -Name $pool | Out-Null
    Set-ItemProperty "IIS:\AppPools\$pool" -Name managedRuntimeVersion -Value ''
    Bien "pool '$pool' creado (sin runtime administrado)"
}

# --- 5. La aplicacion anidada -------------------------------------------------------------------
Paso 5 "Aplicacion /$RutaVirtual"
$rutaIIS = "IIS:\Sites\$SitioExistente\$RutaVirtual"
if (Test-Path $rutaIIS) {
    Ya "/$RutaVirtual"
    Set-ItemProperty $rutaIIS -Name physicalPath -Value $RutaFisica
    Set-ItemProperty $rutaIIS -Name applicationPool -Value $pool
    Bien 'ruta fisica y pool actualizados'
} else {
    try {
        New-Item $rutaIIS -Type Application -PhysicalPath $RutaFisica -ApplicationPool $pool | Out-Null
        Bien "/$RutaVirtual creada"
    } catch {
        Mal "No se pudo crear la aplicacion: $($_.Exception.Message)"
        Ojo 'Hacelo por IIS Manager (ver el encabezado de este script) y copia el web.config a mano.'
        exit 1
    }
}

# --- Cierre -------------------------------------------------------------------------------------
Write-Host "`n--- IIS listo ---" -ForegroundColor White
Write-Host ''
Write-Host "⚠️  El jar tiene que estar compilado con esta MISMA ruta:" -ForegroundColor Yellow
Write-Host "      .\gradlew.bat build -PpanelBasePath=/$RutaVirtual/admin" -ForegroundColor Yellow
Write-Host '    Si no coincide, la API anda y el PANEL QUEDA EN BLANCO con 404 en la consola.' -ForegroundColor Yellow
Write-Host ''
Write-Host 'Verificar A TRAVES del proxy, nunca contra localhost directo' -ForegroundColor White
Write-Host '(contra localhost anda igual con el basePath mal, asi que esa prueba no detecta nada):' -ForegroundColor DarkGray
$host1 = ($sitio.Bindings.Collection | Select-Object -First 1).bindingInformation
Write-Host "  https://<el dominio del sitio>/$RutaVirtual/health" -ForegroundColor Green
Write-Host "  https://<el dominio del sitio>/$RutaVirtual/admin" -ForegroundColor Green
Write-Host "  (bindings del sitio: $host1)" -ForegroundColor DarkGray
