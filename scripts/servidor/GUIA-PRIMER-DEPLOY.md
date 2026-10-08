# Deploy: armar lo que se le pasa a quien instala

Para quien tiene este repo. **Los pasos de instalación en el servidor NO están acá**: están en
[`entrega-servidor/LEEME.md`](../../entrega-servidor/LEEME.md), que es lo que sigue quien instala.
Una sola guía de instalación, para que no haya dos que se contradigan.

## 1. Compilar el jar de producción

```powershell
.\gradlew.bat build -PpanelBasePath=/api/bonificaciones/admin
```

Desde **PowerShell**, no desde Git Bash: Git Bash convierte `/api/...` en una ruta de Windows y el
build del panel falla. Sale `bonif-app\build\libs\bonif-app-X.Y.Z-prod.jar`.

El panel es un export estático con la ruta horneada: compilado sin `-PpanelBasePath`, detrás de IIS
carga en blanco con 404. Por eso el de producción es otro artefacto (`-prod`). Detalle en
[docs/proyecto/deploy.md](../../docs/proyecto/deploy.md).

## 2. Probarlo como si estuviera detrás de IIS

```powershell
# Terminal 1 (con las variables del .env cargadas; PORT para no pisar el 8081 local)
$env:PORT = '8091'; java -jar bonif-app\build\libs\bonif-app-X.Y.Z-prod.jar
# Terminal 2
.\scripts\servidor\simular-proxy-anidado.ps1 -PuertoGateway 8091
```

Abrir `http://localhost:9000/api/bonificaciones/health`, `/admin` y `/swagger-ui.html`. Contra el
puerto directo andan igual con la ruta mal, así que esa prueba no sirve.

## 3. Armar `entrega-servidor/`

```powershell
Remove-Item entrega-servidor\*.jar
Copy-Item bonif-app\build\libs\bonif-app-X.Y.Z-prod.jar entrega-servidor\
Copy-Item scripts\servidor\preparar-sistema.ps1, scripts\servidor\preparar-iis.ps1, `
          scripts\servidor\bonificaciones.xml, scripts\servidor\web.config entrega-servidor\
```

Los scripts de `scripts/servidor/` son la fuente: se editan ahí y se copian. El jar no va a git
(está en `.gitignore`), así que la carpeta se pasa desde el disco.

## Re-deploys

Quien instala corre `.\preparar-sistema.ps1 -Jar <jar nuevo>` en el servidor (ver el LEEME). Si hay
FTP y WinRM hacia el servidor, `scripts\deploy.ps1` hace lo mismo desde acá.
