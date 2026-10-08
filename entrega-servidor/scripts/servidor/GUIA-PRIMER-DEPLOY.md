# Primer deploy de api-bonificaciones

Para quien instala esto en el servidor. **Va en el mismo servidor que MotorFiscal**, así que buena
parte ya está hecha: Java, IIS, ARR, URL Rewrite y el certificado los puso MotorFiscal. Lo que
falta es una base, un servicio y una aplicación más de IIS.

> ⚠️ **Nada de esto se probó contra un servidor real todavía.** Cada paso dice qué hace y, si
> falla, cómo terminarlo a mano. Si algo no coincide con lo que ves, pará y preguntá: es más
> barato que desarmar algo que ya funcionaba.

**Lo que no se puede improvisar:** el puerto (8081, porque MotorFiscal usa el 8080) y la ruta de
IIS, que tiene que ser **la misma** con la que se compila el panel. Esas dos cosas están en el
paso 2 y el paso 4, y equivocarlas es el 90% de lo que puede salir mal acá.

---

## 0. Antes de empezar

Copiá al servidor la carpeta `scripts\` completa (incluye `servidor\` y `crear-base.sql`).

Y averiguá **el nombre exacto del sitio de IIS** del que va a colgar. En el servidor:

```powershell
Get-Website
```

Anotá el nombre tal cual aparece. Es lo que va en el paso 4.

---

## 1. Preparar el sistema

Como **administrador**:

```powershell
cd scripts\servidor
.\preparar-sistema.ps1
```

Es idempotente: lo que ya está, lo informa y sigue. Hace Java (verifica), la base, las variables
de entorno de máquina y la carpeta `C:\bonificaciones` con WinSW.

**Te va a imprimir tres cosas, una sola vez. Guardalas antes de cerrar la ventana:**

| Qué | Para qué | Si se pierde |
|---|---|---|
| Clave del login `bonificaciones` de SQL | la base | se resetea con `-ClaveDeBase` |
| **`CIFRADO_KEY`** | cifra las claves de GESCOM de **todas** las distribuidoras | **hay que volver a cargar las credenciales de todas, una por una** |
| Usuario y clave iniciales del panel | entrar la primera vez | se regenera borrando la fila de `usuario` |

> ⚠️ **`CIFRADO_KEY` es la que importa.** Copiala donde se guardan los secretos del servidor. Si
> cambia, las claves de GESCOM guardadas no se pueden descifrar y ninguna distribuidora funciona
> hasta que se recarguen todas.

Los secretos quedan como **variables de entorno de máquina**, no en el `.xml` del servicio: ese
archivo se sube por FTP y todo lo que tenga adentro viaja.

---

## 2. Compilar el jar de producción

**En tu máquina, no en el servidor.** Y acá está el punto que no se puede saltear:

```powershell
.\gradlew.bat build -PpanelBasePath=/api/bonificaciones/admin
```

El resultado es **`bonif-app-X.Y.Z-prod.jar`** (con `-prod`, distinto del jar local).

**¿Por qué dos jars?** El panel es un export estático: el `basePath` queda **horneado en el HTML y
el JS** en tiempo de build. El navegador entra por `https://dominio/api/bonificaciones/admin`, así
que el HTML tiene que pedir sus archivos en esa ruta completa. Compilado sin eso, los pide en la
raíz del dominio y **el panel queda en blanco con 404 en la consola** — la API andando perfecta y
el panel roto. En `api-impuestos` esa clase de bug llegó a producción **tres veces**.

El `-prod` del nombre tampoco es cosmético: sin él los dos jars se llaman igual y un build local
posterior pisa en silencio el que ya habías verificado.

### Probalo antes de subirlo

Se puede reproducir la topología del servidor en tu máquina, sin IIS:

```powershell
# Terminal 1
java -jar bonif-app\build\libs\bonif-app-X.Y.Z-prod.jar
# Terminal 2
.\scripts\servidor\simular-proxy-anidado.ps1
```

Y abrir **http://localhost:9000/api/bonificaciones/admin**. Si el panel carga y se puede entrar,
el jar está bien. **Probar contra `localhost:8081` directo no sirve**: ahí anda igual con el
basePath mal, así que esa prueba no detecta nada.

---

## 3. Subir el jar y arrancar el servicio

Si hay FTP y WinRM, desde tu máquina:

```powershell
.\scripts\deploy.ps1 -Servidor <host> -Usuario <usuario-ftp>
```

Hace el checklist (build, versión, tag), **verifica que el panel de adentro del jar sea el de esta
ruta**, sube con nombre versionado, reinicia el servicio y verifica a través del proxy.

Si no hay FTP, a mano: copiá el jar al servidor y ahí:

```powershell
Copy-Item <el-jar-prod> 'C:\bonificaciones\bonificaciones.jar' -Force
cd C:\bonificaciones
.\bonificaciones.exe install
Start-Service bonificaciones
Invoke-RestMethod http://localhost:8081/health
```

`/health` tiene que decir **`"origen": "BASE"`**. Si dice `CONFIGURACION`, no encontró la base y
las altas que se hagan por el panel se van a perder al reiniciar: revisar `DB_URL`, `DB_USER` y
`DB_PASSWORD`.

Las **tablas las crea Flyway** al arrancar. No hay DDL a mano.

---

## 4. Colgarlo de IIS

Como administrador, en el servidor:

```powershell
cd scripts\servidor
.\preparar-iis.ps1 -SitioExistente '<el nombre exacto que anotaste en el paso 0>'
```

Crea una **aplicación** de IIS en `/api/bonificaciones`, con su propio `web.config` y su propio
application pool. IIS no mezcla la configuración de una aplicación con la del sitio padre ni con la
de sus hermanas, así que **esto no toca MotorFiscal ni el sitio de la tienda**.

> ⚠️ Si usás otra ruta (`-RutaVirtual`), el jar hay que **recompilarlo** con esa misma ruta en
> `-PpanelBasePath`. Tienen que coincidir.

---

## 5. Verificar, a través del proxy

En un navegador, con el dominio real:

| URL | Qué tiene que pasar |
|---|---|
| `https://<dominio>/api/bonificaciones/health` | `"origen": "BASE"` y la versión que subiste |
| `https://<dominio>/api/bonificaciones/admin` | el panel carga y pide la contraseña |
| `https://<dominio>/api/bonificaciones/swagger-ui.html` | la UI carga el spec (si dice *"Failed to load remote configuration"*, el jar no tiene el prefijo correcto) |

**Siempre a través del dominio, nunca contra `localhost:8081`.** Las tres cosas de arriba andan
contra localhost incluso con el basePath mal.

---

## 6. Dejarlo usable

1. Entrá al panel con **`admin`** y la contraseña que imprimió el paso 1.
2. **Pestaña Usuarios**: creale un usuario a cada persona que vaya a dar de alta distribuidoras.
   Son usuarios con nombre y no una clave compartida porque cada distribuidora guarda **quién la
   dio de alta**.
3. Cambiale la contraseña a `admin` y después **borrá `CLAVE_INICIAL` del entorno**:
   ```powershell
   [Environment]::SetEnvironmentVariable("CLAVE_INICIAL", $null, "Machine")
   ```
4. **Pestaña Distribuidoras**: cargá la primera. Los campos se llaman como en la colección de
   Postman (`DISTRIBUIDORA`, `USERNAME`, `PASSWORD`). Al guardar prueba la credencial contra
   GESCOM: si responde *"trajo N criterios"*, anduvo de punta a punta — base, cifrado, Keycloak y
   GESCOM.
5. **Guardá la clave de la tienda que te muestra**: se ve **una sola vez**. Es la que va en el
   header `x-api-key` del checkout.

---

## Si algo falla

| Síntoma | Causa más probable |
|---|---|
| El servicio no arranca | Puerto 8081 ocupado, o falta `DB_PASSWORD`. Ver `C:\bonificaciones\logs` |
| `/health` dice `"origen": "CONFIGURACION"` | No llegó a la base. Revisar `DB_URL`, `DB_USER`, `DB_PASSWORD` |
| El panel carga **en blanco**, 404 en la consola | El jar se compiló con otro `-PpanelBasePath` que la ruta de IIS |
| Swagger: *"Failed to load remote configuration"* | Lo mismo de arriba |
| El alta dice que GESCOM rechazó las credenciales | Clave mal copiada, o la colección de Postman de otra distribuidora. Los usuarios de dos distribuidoras se parecen peligrosamente |
| 502 desde IIS | El servicio está abajo, o ARR no tiene el proxy habilitado |
| Todo anda y la tienda dice que no | **Pestaña Distribuidoras → Actividad.** Si no aparece ninguna llamada de esa tienda, no llegó: el problema está del lado de la tienda |

**Rollback**: las versiones anteriores quedan en `C:\bonificaciones` con su número. Volver atrás es
copiar un archivo:

```powershell
Stop-Service bonificaciones
Copy-Item C:\bonificaciones\bonificaciones-<version-anterior>.jar C:\bonificaciones\bonificaciones.jar -Force
Start-Service bonificaciones
```
