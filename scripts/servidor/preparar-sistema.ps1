<#
.SYNOPSIS
  Prepara el servidor para correr api-bonificaciones: Java, la base, las variables de entorno de
  maquina y la carpeta del servicio con WinSW. NO toca nada de lo que ya este andando.

.DESCRIPTION
  Mismo enfoque que preparar-sistema.ps1 de MotorFiscal, y pensado para correr en el MISMO
  servidor donde MotorFiscal ya corre. Por eso cada paso es idempotente y verifica antes de
  escribir: si Java ya esta, no lo reinstala; si el login de SQL ya existe, no le cambia la clave;
  si la variable de entorno ya tiene valor, no la pisa.

  Se puede correr varias veces sin miedo. Lo que ya esta, lo informa y sigue.

  ⚠️ NO probado contra un servidor real. Cada paso dice que hace antes de hacerlo, y los que
  pueden fallar explican como terminarlos a mano.

.PARAMETER ClaveDeBase
  Clave para el login `bonificaciones` de SQL Server. Si no se pasa, se genera una y se imprime
  UNA vez -- hay que guardarla, porque despues solo queda en la variable de entorno.

.PARAMETER SinBase
  Saltea el paso de SQL Server. Para cuando la base la crea otra persona.

.EXAMPLE
  .\preparar-sistema.ps1
#>

param(
    [string]$ClaveDeBase = '',
    [switch]$SinBase
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

$carpeta = 'C:\bonificaciones'

# Sin caracteres ambiguos (l/I/1, O/0): esta clave se lee de una pantalla y se tipea.
function NuevaClave {
    $letras = 'abcdefghijkmnopqrstuvwxyz'
    $mayus = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
    $nums = '23456789'
    $simbolos = '!#$%&*+-=?@'
    $todo = ($letras + $mayus + $nums + $simbolos).ToCharArray()
    $clave = @(
        $letras[(Get-Random -Max $letras.Length)],
        $mayus[(Get-Random -Max $mayus.Length)],
        $nums[(Get-Random -Max $nums.Length)],
        $simbolos[(Get-Random -Max $simbolos.Length)]
    )
    $clave += (1..16 | ForEach-Object { $todo[(Get-Random -Max $todo.Length)] })
    -join ($clave | Sort-Object { Get-Random })
}

Write-Host 'Preparando el servidor para api-bonificaciones' -ForegroundColor White
Write-Host 'Nada de lo que ya este configurado se pisa.' -ForegroundColor DarkGray

# --- 1. Java ------------------------------------------------------------------------------------
Paso 1 'Java 21'
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    $v = (& java -version 2>&1 | Select-Object -First 1)
    Bien "$v"
    if ($v -notmatch '"(2[1-9]|[3-9][0-9])') {
        Ojo 'No parece 21 o superior. El jar se compila para 21 y no va a arrancar con menos.'
    }
} else {
    Mal 'No hay java en el PATH.'
    Ojo 'Si MotorFiscal ya corre en este servidor, Java esta instalado pero no en el PATH del'
    Ojo 'sistema. Verificar con: Get-ChildItem "C:\Program Files\*\jdk*" -Directory'
    Ojo 'Si falta, instalar Temurin 21 (https://adoptium.net) y volver a correr esto.'
    exit 1
}

# --- 2. SQL Server: login y base, sin tocar nada que ya exista ----------------------------------
if (-not $SinBase) {
    Paso 2 'SQL Server: login y base `bonificaciones`'
    $sqlcmd = Get-Command sqlcmd -ErrorAction SilentlyContinue
    if (-not $sqlcmd) {
        $posibles = Get-ChildItem 'C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\*\Tools\Binn\SQLCMD.EXE' -ErrorAction SilentlyContinue
        if ($posibles) { $sqlcmd = $posibles[0].FullName }
    } else { $sqlcmd = $sqlcmd.Source }

    if (-not $sqlcmd) {
        Mal 'No se encontro sqlcmd.'
        Ojo 'La base se puede crear a mano con scripts\crear-base.sql desde SSMS. Despues'
        Ojo 'volver a correr esto con -SinBase para el resto de los pasos.'
        exit 1
    }

    $existe = & $sqlcmd -S localhost -E -h -1 -W -Q `
        "SET NOCOUNT ON; SELECT COUNT(*) FROM sys.databases WHERE name = 'bonificaciones';" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Mal "No se pudo conectar a SQL Server: $existe"
        exit 1
    }

    if ("$existe".Trim() -eq '1') {
        Ya 'la base `bonificaciones` existe'
        if ($ClaveDeBase -eq '') {
            Ojo 'La base ya estaba, asi que NO se le cambia la clave al login.'
            Ojo 'Si no la tenes, pasa -ClaveDeBase para resetearla.'
        } else {
            & $sqlcmd -S localhost -E -Q "ALTER LOGIN bonificaciones WITH PASSWORD = '$ClaveDeBase';" | Out-Null
            Bien 'clave del login actualizada'
        }
    } else {
        if ($ClaveDeBase -eq '') {
            $ClaveDeBase = NuevaClave
            Write-Host ''
            Write-Host "    CLAVE GENERADA PARA EL LOGIN bonificaciones:" -ForegroundColor Yellow
            Write-Host "      $ClaveDeBase" -ForegroundColor Yellow
            Write-Host '    Guardala ahora. Despues solo queda en la variable de entorno.' -ForegroundColor Yellow
            Write-Host ''
        }
        # Este script vive en scripts\servidor\, y crear-base.sql en scripts\.
        $script = Join-Path (Split-Path $PSScriptRoot -Parent) 'crear-base.sql'
        if (-not (Test-Path $script)) {
            Mal "No se encontro $script. Copiar la carpeta scripts\ completa al servidor."
            exit 1
        }
        & $sqlcmd -S localhost -E -i $script | Out-Null
        & $sqlcmd -S localhost -E -Q "ALTER LOGIN bonificaciones WITH PASSWORD = '$ClaveDeBase', CHECK_POLICY = ON;" | Out-Null
        Bien 'base creada, con la politica de complejidad restaurada'
        Ojo 'La collation (Latin1_General_100_CS_AS, case-sensitive) se fija al crear la base y'
        Ojo 'cambiarla despues es costoso. Es a proposito: con la collation habitual, dyssa y'
        Ojo 'Dyssa serian el mismo valor en distribuidora.codigo, que es UNIQUE.'
    }
    Bien 'las TABLAS las crea Flyway al arrancar el servicio, no hay DDL a mano'
}

# --- 3. Variables de entorno de MAQUINA ---------------------------------------------------------
# De maquina y no del servicio: el .xml de WinSW se sube por FTP, asi que todo lo que tenga
# adentro viaja. Los secretos se quedan en el servidor.
Paso 3 'Variables de entorno de maquina (los secretos NO van en el .xml)'

function PonerVariable($nombre, $valor, $descripcion) {
    $actual = [Environment]::GetEnvironmentVariable($nombre, 'Machine')
    if ($actual) {
        Ya "$nombre"
        return $actual
    }
    [Environment]::SetEnvironmentVariable($nombre, $valor, 'Machine')
    Bien "$nombre definida ($descripcion)"
    return $valor
}

if (-not $SinBase -and $ClaveDeBase -ne '') {
    PonerVariable 'DB_PASSWORD' $ClaveDeBase 'clave del login de SQL' | Out-Null
} else {
    $yaEsta = [Environment]::GetEnvironmentVariable('DB_PASSWORD', 'Machine')
    if (-not $yaEsta) {
        Ojo 'Falta DB_PASSWORD. Definirla a mano:'
        Ojo '  [Environment]::SetEnvironmentVariable("DB_PASSWORD", "<la clave>", "Machine")'
    } else { Ya 'DB_PASSWORD' }
}

# CIFRADO_KEY cifra las claves de GESCOM de TODAS las distribuidoras. Si se pierde o cambia, hay
# que volver a cargarlas todas: por eso se genera una sola vez y nunca se pisa.
$cifrado = [Environment]::GetEnvironmentVariable('CIFRADO_KEY', 'Machine')
if ($cifrado) {
    Ya 'CIFRADO_KEY'
} else {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $cifrado = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    [Environment]::SetEnvironmentVariable('CIFRADO_KEY', $cifrado, 'Machine')
    Bien 'CIFRADO_KEY generada'
    Write-Host ''
    Write-Host '    ⚠️  CIFRADO_KEY NO SE PUEDE PERDER NI ROTAR A LA LIGERA.' -ForegroundColor Yellow
    Write-Host '    Cifra las claves de GESCOM de todas las distribuidoras: si cambia, ninguna' -ForegroundColor Yellow
    Write-Host '    se puede descifrar y hay que volver a cargarlas una por una. Copiala al lugar' -ForegroundColor Yellow
    Write-Host '    donde se guardan los secretos del servidor:' -ForegroundColor Yellow
    Write-Host "      $cifrado" -ForegroundColor Yellow
    Write-Host ''
}

# El primer usuario del panel. Solo se crea si la tabla de usuarios esta VACIA, asi que dejarlo
# definido no es una puerta de servicio permanente -- pero igual hay que sacarlo despues.
$usuarioInicial = [Environment]::GetEnvironmentVariable('USUARIO_INICIAL', 'Machine')
if ($usuarioInicial) {
    Ya "USUARIO_INICIAL ($usuarioInicial)"
} else {
    $claveInicial = NuevaClave
    [Environment]::SetEnvironmentVariable('USUARIO_INICIAL', 'admin', 'Machine')
    [Environment]::SetEnvironmentVariable('CLAVE_INICIAL', $claveInicial, 'Machine')
    Bien 'USUARIO_INICIAL = admin'
    Write-Host ''
    Write-Host '    PRIMER USUARIO DEL PANEL:' -ForegroundColor Yellow
    Write-Host '      usuario: admin' -ForegroundColor Yellow
    Write-Host "      clave:   $claveInicial" -ForegroundColor Yellow
    Write-Host '    Entra al panel, creale un usuario a cada persona, cambiale la clave a admin' -ForegroundColor Yellow
    Write-Host '    y DESPUES borra CLAVE_INICIAL del entorno:' -ForegroundColor Yellow
    Write-Host '      [Environment]::SetEnvironmentVariable("CLAVE_INICIAL", $null, "Machine")' -ForegroundColor Yellow
    Write-Host ''
}

# --- 4. Carpeta del servicio + WinSW ------------------------------------------------------------
Paso 4 "Carpeta $carpeta y WinSW"
if (-not (Test-Path $carpeta)) {
    New-Item -ItemType Directory $carpeta -Force | Out-Null
    Bien "$carpeta creada"
} else {
    Ya "$carpeta"
}
if (-not (Test-Path "$carpeta\logs")) {
    New-Item -ItemType Directory "$carpeta\logs" -Force | Out-Null
    Bien 'logs\ creada'
} else {
    Ya 'logs\'
}

$winsw = "$carpeta\bonificaciones.exe"
if (Test-Path $winsw) {
    Ya 'bonificaciones.exe (WinSW)'
} else {
    # El ejecutable de WinSW se llama igual que el .xml: es asi como encuentra su configuracion.
    Bien 'Descargando WinSW...'
    try {
        Invoke-WebRequest -Uri 'https://github.com/winsw/winsw/releases/latest/download/WinSW-x64.exe' `
            -OutFile $winsw -UseBasicParsing
        Bien 'WinSW descargado'
    } catch {
        Mal "No se pudo descargar WinSW: $($_.Exception.Message)"
        Ojo "Descargalo a mano de https://github.com/winsw/winsw/releases y guardalo como $winsw"
    }
}

$xml = Join-Path $PSScriptRoot 'bonificaciones.xml'
if (Test-Path $xml) {
    Copy-Item $xml "$carpeta\bonificaciones.xml" -Force
    Bien 'bonificaciones.xml copiado'
} else {
    Mal "Falta $xml. Copiar la carpeta scripts\servidor\ completa al servidor."
}

# --- Cierre -------------------------------------------------------------------------------------
Write-Host "`n--- Listo lo que se puede preparar sin el jar ---" -ForegroundColor White
if (-not (Test-Path "$carpeta\bonificaciones.jar")) {
    Write-Host 'Falta el jar. Tiene que ser el de PRODUCCION (termina en -prod.jar): el panel se' -ForegroundColor Yellow
    Write-Host 'compila con la ruta del proxy horneada adentro. Ver GUIA-PRIMER-DEPLOY.md.' -ForegroundColor Yellow
    Write-Host "  Copy-Item <el-jar-prod> '$carpeta\bonificaciones.jar' -Force" -ForegroundColor Yellow
    Write-Host "  cd $carpeta ; .\bonificaciones.exe install ; Start-Service bonificaciones" -ForegroundColor Yellow
} else {
    Write-Host 'El jar ya esta. Para instalar el servicio:' -ForegroundColor Green
    Write-Host "  cd $carpeta ; .\bonificaciones.exe install ; Start-Service bonificaciones" -ForegroundColor Green
    Write-Host 'Y despues: Invoke-RestMethod http://localhost:8081/health' -ForegroundColor Green
}
