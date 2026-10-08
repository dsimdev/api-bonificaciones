<#
.SYNOPSIS
  Instala, actualiza o vuelve atras api-bonificaciones como servicio de Windows: base de datos,
  carpeta, WinSW, el jar, los secretos y el arranque. Al final verifica que responda.

.DESCRIPTION
  Pensado para el MISMO servidor donde ya corre MotorFiscal, y escrito para no tocar nada suyo:

  - Los secretos (BONIF_DB_PASSWORD, BONIF_CIFRADO_KEY, CLAVE_INICIAL) se guardan en el entorno
    PROPIO del servicio (registro: HKLM\SYSTEM\CurrentControlSet\Services\bonificaciones, valor
    Environment), NO como variables de maquina. MotorFiscal dejo DB_PASSWORD y CIFRADO_KEY como
    variables de maquina: por eso aca los nombres llevan BONIF_ y el servicio los usa en vez de
    esos, asi nunca toma los de MotorFiscal ni los pisa. Windows aplica ese entorno cada vez que
    arranca el servicio, asi que no hace falta reiniciar el servidor.
  - Usa su propio login y su propia base de SQL Server (`bonificaciones`), en la MISMA instancia
    que MotorFiscal (la lee de C:\motorfiscal\motorfiscal.xml), su propia carpeta
    (C:\bonificaciones) y su propio puerto (8081). De MotorFiscal solo LEE esa configuracion, y
    copia su WinSW si no hay internet para descargarlo.

  Se puede correr varias veces. Lo que ya esta, lo informa y sigue; los secretos ya generados no
  se regeneran. Si el servicio ya estaba instalado, antes de cambiar el jar hace un backup de la
  base (el servicio actualiza sus tablas al arrancar).

  Correrlo en "Windows PowerShell" (no PowerShell 7), como administrador de Windows y con un
  usuario que sea sysadmin de SQL Server.

  OJO: NO probado todavia contra el servidor real. Si un paso falla, frena con el mensaje y no sigue.

.PARAMETER Jar
  El jar a instalar. Si no se pasa, usa el *-prod.jar que este en la misma carpeta que este
  script. Para volver atras, el bonificaciones-X.Y.Z.jar que quedo guardado en C:\bonificaciones.

.PARAMETER CifradoKey
  Solo para reinstalar un servicio cuya configuracion se perdio, sobre una base que YA tiene
  distribuidoras: la CIFRADO_KEY con la que se cargaron (la del gestor de contrasenias).

.PARAMETER OlvidarClaveInicial
  Saca CLAVE_INICIAL del entorno del servicio y lo reinicia. Correrlo despues de entrar al panel
  la primera vez y cambiar la clave de admin.

.EXAMPLE
  .\preparar-sistema.ps1
  .\preparar-sistema.ps1 -Jar "D:\descargas\bonif-app-0.6.2-prod.jar"
  .\preparar-sistema.ps1 -Jar "C:\bonificaciones\bonificaciones-0.6.1.jar"
  .\preparar-sistema.ps1 -OlvidarClaveInicial
#>

param(
    [string]$Jar = '',
    [string]$CifradoKey = '',
    [switch]$OlvidarClaveInicial
)

$ErrorActionPreference = 'Stop'

function Paso($n, $texto) { Write-Host "`n[$n] $texto" -ForegroundColor Cyan }
function Bien($texto) { Write-Host "    $texto" -ForegroundColor Green }
function Ya($texto) { Write-Host "    $texto (ya estaba)" -ForegroundColor DarkGray }
function Mal($texto) { Write-Host "    $texto" -ForegroundColor Red }
function Ojo($texto) { Write-Host "    $texto" -ForegroundColor Yellow }
function Frenar($texto) { Mal $texto; Write-Host "`nNo se siguio. Nada de lo anterior a este paso se deshizo." -ForegroundColor Red; exit 1 }

$servicio = 'bonificaciones'
$carpeta = 'C:\bonificaciones'
$puerto = 8081
$claveRegistro = "HKLM:\SYSTEM\CurrentControlSet\Services\$servicio"
$winswVersion = 'v2.12.0'

# --- El entorno propio del servicio, en el registro ------------------------------------------
function LeerEntorno {
    $h = @{}
    $v = (Get-ItemProperty $claveRegistro -Name Environment -ErrorAction SilentlyContinue).Environment
    foreach ($linea in @($v)) {
        if ($linea -and $linea.Contains('=')) {
            $k, $val = $linea -split '=', 2
            $h[$k] = $val
        }
    }
    return $h
}
function GuardarEntorno($h) {
    $lineas = @($h.Keys | Sort-Object | ForEach-Object { "$_=$($h[$_])" })
    New-ItemProperty -Path $claveRegistro -Name Environment -PropertyType MultiString `
        -Value $lineas -Force | Out-Null
}

# Criptograficamente aleatoria y sin caracteres ambiguos (l/I/1, O/0): se lee de una pantalla.
# Sin comillas, parentesis ni $: va adentro de un string de T-SQL que pasa por sqlcmd.
function NuevaClave {
    $alfabeto = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!#%*+-=?@'.ToCharArray()
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $cuerpo = -join ($bytes | ForEach-Object { $alfabeto[$_ % $alfabeto.Length] })
    # Garantiza las cuatro clases que pide la politica de complejidad de SQL Server.
    return 'aA7!' + $cuerpo
}
function NuevaCifradoKey {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

function EsperarSalud {
    Write-Host '    esperando que responda (hasta 2 minutos)...' -ForegroundColor DarkGray
    for ($i = 0; $i -lt 60; $i++) {
        try {
            return Invoke-RestMethod "http://localhost:$puerto/health" -TimeoutSec 3
        } catch { Start-Sleep -Seconds 2 }
    }
    return $null
}

# --- 0. Precondiciones ------------------------------------------------------------------------
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
        ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Frenar 'Hace falta correr esto como administrador (click derecho en Windows PowerShell -> Ejecutar como administrador).'
}
if ($PSVersionTable.PSEdition -eq 'Core') {
    Frenar 'Esto es PowerShell 7. Abri "Windows PowerShell" (el azul, 5.1) como administrador y correlo ahi.'
}

# --- Atajo: solo sacar la clave inicial -------------------------------------------------------
if ($OlvidarClaveInicial) {
    if (-not (Test-Path $claveRegistro)) { Frenar "El servicio $servicio no esta instalado." }
    $entorno = LeerEntorno
    if ($entorno.ContainsKey('CLAVE_INICIAL')) {
        $entorno.Remove('CLAVE_INICIAL')
        GuardarEntorno $entorno
        Restart-Service $servicio
        Bien 'CLAVE_INICIAL sacada del entorno del servicio, y servicio reiniciado.'
    } else {
        Ya 'CLAVE_INICIAL no estaba'
    }
    exit 0
}

$instalado = Test-Path $claveRegistro
$entorno = if ($instalado) { LeerEntorno } else { @{} }
$generados = @()

Write-Host "api-bonificaciones: $(if ($instalado) { 'actualizacion' } else { 'instalacion' })" -ForegroundColor White
Write-Host 'No modifica nada de MotorFiscal.' -ForegroundColor DarkGray

# --- 1. Java ----------------------------------------------------------------------------------
Paso 1 'Java 21'
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Ojo 'Si MotorFiscal corre en este servidor, Java esta instalado. Buscalo con:'
    Ojo '  Get-ChildItem "C:\Program Files\*\jdk*\bin\java.exe"'
    Frenar 'No hay java en el PATH de esta consola.'
}
$javaExe = $javaCmd.Source
# Por cmd y no directo: `java -version` escribe en stderr y Windows PowerShell lo toma como error.
$version = (cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1)
if ("$version" -notmatch '"(2[1-9]|[3-9][0-9])') {
    Frenar "Java encontrado ($version) pero hace falta 21 o superior."
}
Bien "$version"
Bien "se usa $javaExe (ruta completa: el servicio no depende del PATH)"

# --- 2. El jar --------------------------------------------------------------------------------
Paso 2 'El jar'
if (-not $Jar) {
    $candidatos = @(Get-ChildItem $PSScriptRoot -Filter '*-prod.jar' -ErrorAction SilentlyContinue)
    if ($candidatos.Count -ne 1) {
        Frenar "No encontre UN jar *-prod.jar en $PSScriptRoot (encontre $($candidatos.Count)). Pasalo con -Jar <ruta>."
    }
    $Jar = $candidatos[0].FullName
}
if (-not (Test-Path $Jar)) { Frenar "No existe $Jar" }
$nombreJar = Split-Path $Jar -Leaf
# El de produccion trae el panel armado para /api/bonificaciones. Las copias que este script deja
# en C:\bonificaciones (bonificaciones-X.Y.Z.jar) son ese mismo jar, para volver atras.
if ($nombreJar -notmatch '-prod\.jar$' -and $nombreJar -notmatch '^bonificaciones-\d+\.\d+\.\d+\.jar$') {
    Frenar "$nombreJar no es un jar de produccion (*-prod.jar). Otro jar deja el panel en blanco detras de IIS."
}
$versionJar = [regex]::Match($nombreJar, '(\d+\.\d+\.\d+)').Groups[1].Value
# Se copia antes de tocar nada: si el jar esta en C:\bonificaciones, lo vamos a pisar.
$jarTemporal = Join-Path $env:TEMP "bonificaciones-instalar-$versionJar.jar"
Copy-Item $Jar $jarTemporal -Force
Bien "$nombreJar (version $versionJar)"

# --- 3. Puerto y salida a GESCOM --------------------------------------------------------------
Paso 3 "Puerto $puerto y salida a GESCOM"
$ocupado = Get-NetTCPConnection -LocalPort $puerto -State Listen -ErrorAction SilentlyContinue
if ($ocupado) {
    $dueno = (Get-Process -Id $ocupado[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
    $nuestro = $instalado -and ((Get-Service $servicio -ErrorAction SilentlyContinue).Status -eq 'Running')
    if (-not $nuestro) { Frenar "El puerto $puerto lo usa otro proceso ($dueno). MotorFiscal usa el 8080, no deberia ser el." }
    Ya 'lo usa este mismo servicio'
} else {
    Bien 'libre'
}
if ((Test-NetConnection auth.gescom.online -Port 443 -WarningAction SilentlyContinue).TcpTestSucceeded) {
    Bien 'hay salida HTTPS a GESCOM (auth.gescom.online)'
} else {
    Ojo 'NO hay salida HTTPS a auth.gescom.online:443. El servicio va a arrancar, pero no va a poder'
    Ojo 'consultar a las distribuidoras hasta que el firewall deje salir a *.gescom.online por 443.'
}

# --- 4. SQL Server ----------------------------------------------------------------------------
Paso 4 'SQL Server: login y base `bonificaciones` (propios, en la instancia de MotorFiscal)'
$sqlcmd = (Get-Command sqlcmd -ErrorAction SilentlyContinue)
if ($sqlcmd) { $sqlcmd = $sqlcmd.Source } else {
    $posibles = @(Get-ChildItem 'C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\*\Tools\Binn\SQLCMD.EXE' -ErrorAction SilentlyContinue)
    if ($posibles.Count -gt 0) { $sqlcmd = $posibles[0].FullName }
}
if (-not $sqlcmd) { Frenar 'No se encontro sqlcmd (viene con SQL Server).' }

# La misma instancia que MotorFiscal: si el se conecta a otro lado, nosotros tambien.
$servidorSql = 'localhost:1433'
$xmlMotorFiscal = 'C:\motorfiscal\motorfiscal.xml'
if (Test-Path $xmlMotorFiscal) {
    $m = [regex]::Match((Get-Content $xmlMotorFiscal -Raw), 'jdbc:sqlserver://([^;"]+)')
    if ($m.Success) { $servidorSql = $m.Groups[1].Value }
    Bien "instancia de MotorFiscal: $servidorSql"
} else {
    Ojo "no encontre $xmlMotorFiscal; se usa $servidorSql"
}
$servidorSqlcmd = $servidorSql.Replace(':', ',')

function Sql($consulta) {
    # sqlcmd escribe sus errores en stderr: con 'Stop', Windows PowerShell cortaria con un error
    # crudo en vez de pasar por Frenar.
    $previo = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $salida = & $sqlcmd -S $servidorSqlcmd -E -b -h -1 -W -Q "SET NOCOUNT ON; $consulta" 2>&1
    $codigo = $LASTEXITCODE
    $ErrorActionPreference = $previo
    if ($codigo -ne 0) { Frenar "SQL Server respondio con error: $salida" }
    return ("$salida").Trim()
}

if ((Sql "SELECT IS_SRVROLEMEMBER('sysadmin');") -ne '1') {
    Frenar "Tu usuario de Windows no es sysadmin de SQL Server, y hace falta para crear el login y la base. Correlo con un usuario que lo sea (el que instalo SQL Server, o pedile a quien lo administra)."
}
if ((Sql "SELECT CAST(SERVERPROPERTY('IsIntegratedSecurityOnly') AS int);") -eq '1') {
    Frenar 'SQL Server acepta solo autenticacion de Windows. El servicio entra con usuario y clave de SQL (igual que MotorFiscal): hay que habilitar el modo mixto.'
}
Bien 'sysadmin y modo mixto: OK'

$baseExiste = (Sql "SELECT COUNT(*) FROM sys.databases WHERE name = 'bonificaciones';") -eq '1'
$hayDistribuidoras = $false
if ($baseExiste) {
    $hayDistribuidoras = (Sql "IF OBJECT_ID('bonificaciones.dbo.distribuidora') IS NULL SELECT 0 ELSE SELECT COUNT(*) FROM bonificaciones.dbo.distribuidora;") -ne '0'
}

# La CIFRADO_KEY se resuelve ANTES de tocar nada: generar otra sobre una base con distribuidoras
# las dejaria sin poder descifrarse.
if ($CifradoKey) {
    if ($CifradoKey -notmatch '^[0-9a-fA-F]{64}$') { Frenar 'La -CifradoKey tiene que ser de 64 caracteres hexadecimales.' }
    $entorno['BONIF_CIFRADO_KEY'] = $CifradoKey.ToLower()
} elseif (-not $entorno['BONIF_CIFRADO_KEY']) {
    if ($hayDistribuidoras) {
        Frenar 'La base ya tiene distribuidoras, pero este servicio no tiene su CIFRADO_KEY. Volve a correrlo con -CifradoKey <la que guardaste>. Generar una nueva dejaria todas sin poder usarse.'
    }
    $entorno['BONIF_CIFRADO_KEY'] = NuevaCifradoKey
    $generados += 'CIFRADO_KEY'
}

if ($instalado -and $baseExiste) {
    # El servicio actualiza sus tablas al arrancar con un jar nuevo: primero, un backup. Va al
    # directorio de backups por defecto de SQL Server (donde SQL Server ya puede escribir).
    $archivo = "bonificaciones-antes-de-$versionJar-$(Get-Date -Format yyyyMMdd-HHmmss).bak"
    Sql "BACKUP DATABASE bonificaciones TO DISK = N'$archivo' WITH COPY_ONLY;" | Out-Null
    Bien "backup de la base: $archivo (en la carpeta de backups de SQL Server)"
}

$loginExiste = (Sql "SELECT COUNT(*) FROM sys.server_principals WHERE name = 'bonificaciones';") -eq '1'
$claveBase = $entorno['BONIF_DB_PASSWORD']
if (-not $loginExiste) {
    $claveBase = NuevaClave
    Sql "CREATE LOGIN bonificaciones WITH PASSWORD = '$claveBase', CHECK_POLICY = ON;" | Out-Null
    $generados += 'clave de la base'
    Bien 'login `bonificaciones` creado'
} elseif (-not $claveBase) {
    # El login existe pero este servicio no tiene su clave: se le pone una nueva. Es el login
    # PROPIO de este servicio, nadie mas lo usa.
    $claveBase = NuevaClave
    Sql "ALTER LOGIN bonificaciones WITH PASSWORD = '$claveBase', CHECK_POLICY = ON;" | Out-Null
    $generados += 'clave de la base'
    Bien 'login `bonificaciones` existia sin clave conocida: se le puso una nueva'
} else {
    Ya 'login `bonificaciones`'
}
$entorno['BONIF_DB_PASSWORD'] = $claveBase

if ($baseExiste) {
    Ya 'base `bonificaciones`'
} else {
    # Case-sensitive a proposito: con la collation habitual, 'dyssa' y 'Dyssa' serian el mismo
    # codigo de distribuidora. SIMPLE: es una base chica de configuracion, sin backups de log.
    Sql "CREATE DATABASE bonificaciones COLLATE Latin1_General_100_CS_AS; ALTER DATABASE bonificaciones SET RECOVERY SIMPLE;" | Out-Null
    Bien 'base `bonificaciones` creada'
}
Sql "USE bonificaciones; IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'bonificaciones') CREATE USER bonificaciones FOR LOGIN bonificaciones; ALTER ROLE db_owner ADD MEMBER bonificaciones;" | Out-Null
Bien 'el login es duenio de su base (las tablas las crea el servicio al arrancar)'

# --- 5. Carpeta y WinSW -----------------------------------------------------------------------
Paso 5 "Carpeta $carpeta y WinSW"
foreach ($c in @($carpeta, "$carpeta\logs")) {
    if (Test-Path $c) { Ya $c } else { New-Item -ItemType Directory $c -Force | Out-Null; Bien "$c creada" }
}
$winsw = "$carpeta\$servicio.exe"
if (Test-Path $winsw) {
    Ya "$servicio.exe (WinSW)"
} elseif (Test-Path 'C:\motorfiscal\motorfiscal.exe') {
    # Es el mismo programa (WinSW) con otro nombre: no hace falta internet.
    Copy-Item 'C:\motorfiscal\motorfiscal.exe' $winsw
    Bien 'WinSW copiado del de MotorFiscal (el de MotorFiscal no se toca)'
} else {
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -OutFile $winsw `
            -Uri "https://github.com/winsw/winsw/releases/download/$winswVersion/WinSW-x64.exe"
        Bien "WinSW $winswVersion descargado"
    } catch {
        Frenar "No se pudo descargar WinSW ($($_.Exception.Message)). Bajalo a mano de https://github.com/winsw/winsw/releases/tag/$winswVersion (WinSW-x64.exe), guardalo como $winsw y volve a correr esto."
    }
}

# La configuracion: ruta completa de java y la instancia de SQL de MotorFiscal.
$xmlOrigen = Join-Path $PSScriptRoot "$servicio.xml"
if (-not (Test-Path $xmlOrigen)) { Frenar "Falta $xmlOrigen junto a este script." }
$xmlTexto = Get-Content $xmlOrigen -Raw
$xmlTexto = $xmlTexto.Replace('<executable>java</executable>', "<executable>$javaExe</executable>")
$xmlTexto = $xmlTexto.Replace('jdbc:sqlserver://localhost:1433', "jdbc:sqlserver://$servidorSql")
Set-Content "$carpeta\$servicio.xml" -Value $xmlTexto -Encoding UTF8
Bien "$servicio.xml copiado (java: $javaExe, SQL: $servidorSql)"

# --- 6. Servicio, jar y secretos --------------------------------------------------------------
Paso 6 'Servicio, jar y secretos'
if ($instalado -and (Get-Service $servicio).Status -ne 'Stopped') {
    Stop-Service $servicio
    Bien 'servicio detenido para cambiar el jar'
}
# Queda una copia con la version: volver atras es correr esto mismo con esa copia.
Copy-Item $jarTemporal "$carpeta\bonificaciones-$versionJar.jar" -Force
Copy-Item $jarTemporal "$carpeta\bonificaciones.jar" -Force
Remove-Item $jarTemporal
Bien "jar instalado (y guardado como bonificaciones-$versionJar.jar)"

if ($instalado) {
    # Aplica los cambios del .xml que son del servicio (arranque, reintentos).
    Push-Location $carpeta
    try { & $winsw refresh | Out-Null } finally { Pop-Location }
} else {
    Push-Location $carpeta
    try { & $winsw install | Out-Null } finally { Pop-Location }
    if (-not (Test-Path $claveRegistro)) { Frenar 'WinSW no pudo instalar el servicio.' }
    Bien "servicio '$servicio' instalado (arranque automatico)"
}

# La clave del primer usuario del panel (`admin`). El servicio la usa solo si no hay NINGUN
# usuario, asi que despues del primer ingreso se saca con -OlvidarClaveInicial.
$claveAdmin = $null
if (-not $instalado -and -not $hayDistribuidoras) {
    $claveAdmin = NuevaClave
    $entorno['CLAVE_INICIAL'] = $claveAdmin
    $generados += 'clave de admin'
}

GuardarEntorno $entorno
Bien 'secretos guardados en el entorno propio del servicio (no son variables de maquina)'

# Se muestran ANTES de arrancar: si el arranque falla, igual quedan anotados.
if ($generados.Count -gt 0) {
    Write-Host ''
    Write-Host '  GUARDA ESTO AHORA en el gestor de contrasenias. No se vuelve a mostrar.' -ForegroundColor Yellow
    if ($claveAdmin) {
        Write-Host '    Panel, primer ingreso:  usuario admin' -ForegroundColor Yellow
        Write-Host "                            clave   $claveAdmin" -ForegroundColor Yellow
    }
    if ($generados -contains 'CIFRADO_KEY') {
        Write-Host "    CIFRADO_KEY:            $($entorno['BONIF_CIFRADO_KEY'])" -ForegroundColor Yellow
        Write-Host '      (cifra las claves de GESCOM de todas las distribuidoras: sin ella, hay que' -ForegroundColor Yellow
        Write-Host '       volver a cargarlas todas)' -ForegroundColor Yellow
    }
    if ($generados -contains 'clave de la base') {
        Write-Host "    Clave del login SQL 'bonificaciones': $claveBase" -ForegroundColor Yellow
    }
    Write-Host ''
}

Start-Service $servicio
Bien 'servicio arrancado'

# --- 7. Verificacion --------------------------------------------------------------------------
Paso 7 "Verificacion en http://localhost:$puerto/health"
$salud = EsperarSalud
if (-not $salud) {
    Frenar "No respondio. Mirar el ultimo archivo de $carpeta\logs (el error de arranque esta al final)."
}
if ($salud.version -ne $versionJar) {
    Frenar "Responde la version $($salud.version), no la $versionJar que se instalo."
}
Bien "version $($salud.version)"
if ($salud.origen -ne 'BASE') {
    Frenar "origen = $($salud.origen): no esta leyendo de la base. Mirar el log de arranque."
}
Bien 'lee de la base'

Write-Host "`n--- Servicio $(if ($instalado) { 'actualizado' } else { 'instalado' }) y respondiendo ---" -ForegroundColor White
if (-not $instalado) {
    Write-Host 'Siguiente: .\preparar-iis.ps1' -ForegroundColor Green
}
