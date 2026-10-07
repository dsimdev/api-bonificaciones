# Levanta el gateway de bonificaciones en http://localhost:8080
#
#   .\arrancar.ps1          usa el jar ya compilado, o lo compila si no existe
#   .\arrancar.ps1 -Build   fuerza recompilar antes de levantar
#
# Las distribuidoras NO salen del .env: viven en la base y se dan de alta por el panel (/admin) o
# por /admin/v1/distribuidoras. Lo que sigue saliendo del .env son los secretos del entorno
# (CIFRADO_KEY, USUARIO_INICIAL, CLAVE_INICIAL) y, si se quiere levantar sin base,
# DISTRIBUIDORAS_EN_BASE=false con bonificaciones.distribuidoras.* -- eso es el modo de los tests.
#
# Sin CIFRADO_KEY la app levanta pero dar de alta falla explicito, a proposito: es preferible a
# guardar claves de produccion sin cifrar.

param([switch]$Build)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$version = (Get-Content .claude\VERSION).Trim()
$jar = "bonif-app\build\libs\bonif-app-$version.jar"

if (Test-Path .env) {
    Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
        $k, $v = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim())
    }
    Write-Host "Credenciales cargadas de .env"
}

if ($Build -or -not (Test-Path $jar)) {
    Write-Host "Compilando $jar ..."
    & .\gradlew.bat :bonif-app:bootJar -q --console=plain
}

if (-not $env:CIFRADO_KEY) {
    Write-Host "OJO: sin CIFRADO_KEY. Dar de alta una distribuidora va a fallar explicito." `
        -ForegroundColor Yellow
    Write-Host "     Generala con: openssl rand -hex 32   (y ponela en el .env)"
}

Write-Host "Levantando api-bonificaciones $version en http://localhost:8080  (Ctrl+C para cortar)"
Write-Host "  panel:   http://localhost:8080/admin"
Write-Host "  salud:   http://localhost:8080/health"
Write-Host "  swagger: http://localhost:8080/swagger-ui.html"
java -jar $jar
