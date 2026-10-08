<#
.SYNOPSIS
  Cuelga api-bonificaciones en IIS como /api/bonificaciones, en el MISMO sitio donde ya esta
  /api/impuestos (MotorFiscal). No modifica el sitio, ni MotorFiscal, ni la configuracion global.

.DESCRIPTION
  Crea tres cosas, todas propias:
    - la carpeta C:\inetpub\bonificaciones, con un web.config que hace de proxy a localhost:8081
    - el application pool `bonificaciones`
    - la aplicacion /api/bonificaciones dentro del sitio

  Busca el sitio solo: es el que tiene la aplicacion /api/impuestos. Si no la encuentra, pide
  -SitioExistente con el nombre exacto.

  Necesita ARR y URL Rewrite con el proxy de ARR habilitado. MotorFiscal ya los usa, asi que tienen
  que estar: si falta algo, este script FRENA y avisa, no instala ni cambia nada global.

  Se puede correr varias veces. Correrlo en "Windows PowerShell" (no PowerShell 7) y como
  administrador.

  NO probado todavia contra el servidor real. Si `New-Item -Type Application` da un error raro,
  el camino manual por el Administrador de IIS esta al final del mensaje de error.

.PARAMETER SitioExistente
  Solo si el script no encuentra solo el sitio. El nombre exacto, tal cual lo muestra Get-Website.

.EXAMPLE
  .\preparar-iis.ps1
#>

param(
    [string]$SitioExistente = ''
)

$ErrorActionPreference = 'Stop'

function Paso($n, $texto) { Write-Host "`n[$n] $texto" -ForegroundColor Cyan }
function Bien($texto) { Write-Host "    $texto" -ForegroundColor Green }
function Ya($texto) { Write-Host "    $texto (ya estaba)" -ForegroundColor DarkGray }
function Mal($texto) { Write-Host "    $texto" -ForegroundColor Red }
function Ojo($texto) { Write-Host "    $texto" -ForegroundColor Yellow }
function Frenar($texto) { Mal $texto; Write-Host "`nNo se siguio. Nada de lo anterior a este paso se deshizo." -ForegroundColor Red; exit 1 }

# La ruta es fija: el jar de produccion trae el panel armado para /api/bonificaciones.
$rutaVirtual = 'api/bonificaciones'
$rutaFisica = 'C:\inetpub\bonificaciones'
$pool = 'bonificaciones'
$puerto = 8081

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
        ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Frenar 'Hace falta correr esto como administrador.'
}
if ($PSVersionTable.PSEdition -eq 'Core') {
    Frenar 'Esto es PowerShell 7. Abri "Windows PowerShell" (el azul, 5.1) como administrador y correlo ahi.'
}
try { Import-Module WebAdministration } catch {
    Frenar 'Falta el modulo de PowerShell de IIS ("Herramientas y scripts de administracion de IIS"). Es una caracteristica de Windows: se agrega desde "Agregar roles y caracteristicas" sin reiniciar IIS.'
}

# --- 1. El sitio ------------------------------------------------------------------------------
Paso 1 'El sitio de IIS'
$motorFiscal = @(Get-WebApplication | Where-Object { $_.path -eq '/api/impuestos' })
if (-not $SitioExistente) {
    if ($motorFiscal.Count -eq 1) {
        $SitioExistente = [regex]::Match($motorFiscal[0].ItemXPath, "@name='([^']+)'").Groups[1].Value
        Bien "'$SitioExistente' (es donde esta /api/impuestos de MotorFiscal)"
    } else {
        Ojo 'No encontre la aplicacion /api/impuestos. Los sitios que hay:'
        Get-Website | ForEach-Object { Ojo "  $($_.Name)" }
        Frenar 'Volve a correrlo con -SitioExistente "<el nombre exacto>" (el de la tienda).'
    }
}
$sitio = Get-Website -Name $SitioExistente -ErrorAction SilentlyContinue
if (-not $sitio) { Frenar "No existe un sitio llamado '$SitioExistente'." }
Bien "encontrado (estado: $($sitio.State))"

# --- 2. ARR y URL Rewrite: se verifican, no se instalan ni se cambian ---------------------------
Paso 2 'ARR y URL Rewrite'
$rewrite = Test-Path 'HKLM:\SOFTWARE\Microsoft\IIS Extensions\URL Rewrite'
$arr = Get-WebGlobalModule -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*ApplicationRequestRouting*' }
# El proxy se mira donde ya funciona: en /api/impuestos, que tiene el mismo web.config que el
# nuestro. Asi da igual si MotorFiscal lo habilito a nivel servidor o en su propio web.config.
$dondeMirar = 'MACHINE/WEBROOT/APPHOST'
if ($motorFiscal.Count -eq 1) { $dondeMirar = "MACHINE/WEBROOT/APPHOST/$SitioExistente/api/impuestos" }
$proxyActivo = (Get-WebConfigurationProperty -PSPath $dondeMirar `
        -Filter 'system.webServer/proxy' -Name 'enabled' -ErrorAction SilentlyContinue).Value
if (-not ($rewrite -and $arr -and $proxyActivo)) {
    if (-not $rewrite) { Mal 'falta URL Rewrite' }
    if (-not $arr) { Mal 'falta Application Request Routing (ARR)' }
    if ($arr -and -not $proxyActivo) { Mal 'ARR esta, pero su proxy esta deshabilitado' }
    Frenar 'MotorFiscal necesita esto mismo para funcionar, asi que en este servidor tendria que estar. Revisar que sea el servidor correcto antes de tocar nada global.'
}
Bien 'URL Rewrite, ARR y el proxy de ARR: OK'

# --- 3. Carpeta y web.config ------------------------------------------------------------------
Paso 3 "Carpeta $rutaFisica"
if (Test-Path $rutaFisica) { Ya 'existe' } else { New-Item -ItemType Directory $rutaFisica -Force | Out-Null; Bien 'creada' }
$plantilla = Join-Path $PSScriptRoot 'web.config'
if (-not (Test-Path $plantilla)) { Frenar "Falta $plantilla junto a este script." }
Copy-Item $plantilla (Join-Path $rutaFisica 'web.config') -Force
Bien "web.config copiado (proxy a localhost:$puerto)"

# --- 4. Application pool propio ---------------------------------------------------------------
Paso 4 "Application pool '$pool'"
if (Get-ChildItem IIS:\AppPools | Where-Object { $_.Name -eq $pool }) {
    Ya $pool
} else {
    New-WebAppPool -Name $pool | Out-Null
    Set-ItemProperty "IIS:\AppPools\$pool" -Name managedRuntimeVersion -Value ''
    Bien 'creado (sin .NET: el proxy es nativo de IIS)'
}

# --- 5. La aplicacion -------------------------------------------------------------------------
Paso 5 "Aplicacion /$rutaVirtual"
$rutaIIS = "IIS:\Sites\$SitioExistente\$rutaVirtual"
if (Test-Path $rutaIIS) {
    Set-ItemProperty $rutaIIS -Name physicalPath -Value $rutaFisica
    Set-ItemProperty $rutaIIS -Name applicationPool -Value $pool
    Ya "/$rutaVirtual (se le confirmo carpeta y pool)"
} else {
    try {
        New-Item $rutaIIS -Type Application -PhysicalPath $rutaFisica -ApplicationPool $pool | Out-Null
        Bien "/$rutaVirtual creada"
    } catch {
        Mal "No se pudo crear: $($_.Exception.Message)"
        Ojo 'A mano, en el Administrador de IIS:'
        Ojo "  Sitios -> $SitioExistente -> api -> click derecho -> Agregar aplicacion"
        Ojo "  Alias: bonificaciones   Grupo de aplicaciones: $pool   Ruta fisica: $rutaFisica"
        Ojo '  (igual que esta /api/impuestos)'
        exit 1
    }
}

# --- Cierre -----------------------------------------------------------------------------------
$binding = ($sitio.Bindings.Collection | Select-Object -First 1).bindingInformation
Write-Host "`n--- IIS listo ---" -ForegroundColor White
Write-Host 'Verificalo desde un navegador, con el dominio de la tienda (no con localhost):' -ForegroundColor White
Write-Host "  https://<dominio>/$rutaVirtual/health   -> tiene que decir la version y origen BASE" -ForegroundColor Green
Write-Host "  https://<dominio>/$rutaVirtual/admin    -> tiene que cargar el panel y pedir usuario" -ForegroundColor Green
Write-Host "  (primer binding del sitio: $binding)" -ForegroundColor DarkGray
