# Levanta el gateway de bonificaciones en http://localhost:8080
#
#   .\arrancar.ps1          usa el jar ya compilado, o lo compila si no existe
#   .\arrancar.ps1 -Build   fuerza recompilar antes de levantar
#
# Las credenciales de las distribuidoras salen del .env (ver .env.example). Sin ellas la app
# igual levanta: /health muestra las distribuidoras configuradas y las consultas al ERP fallan
# con un error explicito, no en silencio.

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

Write-Host "Levantando api-bonificaciones $version en http://localhost:8080  (Ctrl+C para cortar)"
Write-Host "  salud:   http://localhost:8080/health"
Write-Host "  swagger: http://localhost:8080/swagger-ui.html"
java -jar $jar
