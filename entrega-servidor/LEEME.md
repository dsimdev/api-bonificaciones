# Instalar api-bonificaciones en el servidor

Para quien instala el servicio en el servidor de la tienda, **el mismo donde ya corre MotorFiscal**.
Se sigue de arriba a abajo; no hace falta leer nada más.

## Qué es

Un servicio Java que la tienda llama en el checkout para saber qué descuento le corresponde a un
carrito. Se instala igual que MotorFiscal: un servicio de Windows que escucha solo en `localhost`, y
una aplicación de IIS que lo publica en el mismo dominio de la tienda.

|  | MotorFiscal (ya está) | Este servicio |
|---|---|---|
| URL pública | `https://<dominio>/api/impuestos` | `https://<dominio>/api/bonificaciones` |
| Puerto local | 8080 | **8081** |
| Carpeta | `C:\motorfiscal` | `C:\bonificaciones` |
| Servicio de Windows | `motorfiscal` | `bonificaciones` |
| Base de SQL Server | `motorfiscal` | `bonificaciones`, con login propio, **en la misma instancia** |

**No modifica nada de MotorFiscal ni del sitio de la tienda.** Todo lo que crea es propio. De
MotorFiscal solo lee su configuración (para usar la misma instancia de SQL Server y el mismo sitio
de IIS). Si algo no está como esperan, los scripts **frenan y avisan**: no cambian configuración
global de IIS ni instalan componentes.

## Qué hay en esta carpeta

| Archivo | Para qué |
|---|---|
| `LEEME.md` | Esto |
| `bonif-app-0.7.0-prod.jar` | El servicio, ya compilado. No hay que compilar nada |
| `preparar-sistema.ps1` | Instala, actualiza o vuelve atrás el servicio |
| `preparar-iis.ps1` | Lo publica en IIS como `/api/bonificaciones` |
| `bonificaciones.xml`, `web.config` | Los usan los dos scripts. No hace falta tocarlos |

> No está probado todavía contra este servidor. Si algo falla, el script frena con un mensaje:
> mandale la salida completa a quien te pasó esta carpeta antes de improvisar.

## Antes de empezar

- Un usuario que sea **administrador de Windows** y **sysadmin de SQL Server** (ser administrador
  de Windows no alcanza para SQL Server). El script lo verifica.
- **Salida HTTPS (443)** del servidor a `*.gescom.online`: el servicio consulta ahí a las
  distribuidoras. El script avisa si no hay.
- **Windows PowerShell** (el azul, 5.1), no PowerShell 7.

---

## Paso 1 — Copiar la carpeta

Copiá esta carpeta entera al servidor. En **Windows PowerShell como administrador**, parado en la
carpeta:

```powershell
cd "<donde copiaste la carpeta>"
Get-ChildItem | Unblock-File
Set-ExecutionPolicy -Scope Process Bypass
```

`Unblock-File` porque Windows bloquea los scripts que llegan de otra máquina. El cambio de
política vale solo para esta ventana.

## Paso 2 — Instalar el servicio

```powershell
.\preparar-sistema.ps1
```

Verifica Java, SQL Server y la salida a GESCOM; crea la base y el login `bonificaciones`; copia el
jar a `C:\bonificaciones`; instala el servicio, lo arranca y comprueba que responda.

**Antes de arrancarlo muestra en amarillo tres cosas, una sola vez. Guardalas en el gestor de
contraseñas:**

- **usuario `admin` y su clave**: para entrar al panel la primera vez.
- **`CIFRADO_KEY`**: cifra las claves de GESCOM de todas las distribuidoras. **Es la importante**:
  sin ella, la base no sirve (hay que volver a cargar todas las distribuidoras).
- **la clave del login de SQL `bonificaciones`**.

Quedan guardadas en la configuración propia del servicio (en el registro, en
`HKLM\SYSTEM\CurrentControlSet\Services\bonificaciones`, valor `Environment`), no como variables de
máquina. MotorFiscal usa variables de máquina con nombres parecidos (`DB_PASSWORD`, `CIFRADO_KEY`);
este servicio usa las suyas (`BONIF_DB_PASSWORD`, `BONIF_CIFRADO_KEY`) y no lee ni pisa las de
MotorFiscal. Si en algún momento hace falta volver a verlas:

```powershell
(Get-ItemProperty HKLM:\SYSTEM\CurrentControlSet\Services\bonificaciones).Environment
```

> Si el servicio se desinstala, esa configuración se borra con él. Por eso la `CIFRADO_KEY` tiene
> que estar en el gestor de contraseñas: para reinstalar sobre la misma base, el script la pide
> (`-CifradoKey`) y no deja generar otra.

## Paso 3 — Publicarlo en IIS

```powershell
.\preparar-iis.ps1
```

Busca solo el sitio donde está `/api/impuestos` y crea al lado `/api/bonificaciones`, con su propio
application pool. Si no lo encuentra, te muestra los sitios y te pide el nombre:
`.\preparar-iis.ps1 -SitioExistente "<nombre>"`.

## Paso 4 — Verificar desde un navegador

Con el dominio real de la tienda, **no con `localhost`**:

| Abrí | Tiene que pasar |
|---|---|
| `https://<dominio>/api/bonificaciones/health` | Un JSON con `"version"` y `"origen": "BASE"` |
| `https://<dominio>/api/bonificaciones/admin` | Carga el panel y pide usuario y contraseña |

## Paso 5 — Dejarlo listo para la tienda

1. Entrá al panel con `admin` y la clave del Paso 2.
2. **Usuarios**: creá un usuario para cada persona que vaya a administrar (queda registrado quién
   dio de alta cada cosa) y cambiale la contraseña a `admin`.
3. Sacá la clave inicial de la configuración del servicio:
   ```powershell
   .\preparar-sistema.ps1 -OlvidarClaveInicial
   ```
4. **Distribuidoras → Nueva distribuidora**: cargá la distribuidora. El código y el usuario y la
   clave de GESCOM te los pasa quien te dio esta carpeta. Al guardar, el panel prueba la conexión
   con GESCOM: si dice *"trajo N criterios"*, anduvo de punta a punta.
5. El alta muestra **la clave de la tienda, una sola vez**. Pasásela al dev de la tienda junto con
   la URL `https://<dominio>/api/bonificaciones` y el código de la distribuidora.
   **Distribuidoras → Nueva clave** genera otra y anula la anterior al instante: avisale antes a la
   tienda, porque hasta que cambie su configuración el checkout no va a tener descuentos.

**Backups**: sumá la base `bonificaciones` al plan de backups del servidor. Base y `CIFRADO_KEY` van
juntas: una sin la otra no sirve.

---

## Actualizar a una versión nueva

Te va a llegar otro `bonif-app-X.Y.Z-prod.jar`, y quizás versiones nuevas de los scripts (si es así,
reemplazá los de esta carpeta). Como administrador:

```powershell
.\preparar-sistema.ps1 -Jar "<ruta al jar nuevo>"
```

Primero hace un **backup de la base** (en la carpeta de backups de SQL Server, con la versión en el
nombre), porque el servicio actualiza sus tablas al arrancar. Después frena el servicio, cambia el
jar, lo arranca y verifica que responda con la versión nueva. Los secretos no se tocan.

## Volver a la versión anterior

Cada jar instalado queda guardado en `C:\bonificaciones` como `bonificaciones-X.Y.Z.jar`:

```powershell
.\preparar-sistema.ps1 -Jar "C:\bonificaciones\bonificaciones-<version-anterior>.jar"
```

Si la versión nueva había cambiado tablas y la anterior no arranca, hay que restaurar también el
backup que se hizo antes de actualizar (`bonificaciones-antes-de-<version>-….bak`). Eso pedilo a
quien te pasó la carpeta antes de hacerlo.

## Si algo falla

| Síntoma | Qué mirar |
|---|---|
| El Paso 2 frena en "Java" | Que Java 21 esté instalado (MotorFiscal lo usa) y en el PATH |
| El Paso 2 frena en SQL Server | El mensaje dice qué falta (sysadmin, modo mixto). Usa la misma instancia que MotorFiscal |
| El Paso 2 frena pidiendo `-CifradoKey` | La base ya tiene distribuidoras: pasá la `CIFRADO_KEY` guardada |
| El Paso 2 dice "No respondió" | El último archivo de `C:\bonificaciones\logs`: el error de arranque está al final |
| El Paso 3 frena en ARR / URL Rewrite | No cambies nada global: es raro, porque MotorFiscal los necesita. Avisá |
| Error **500.19** en `/api/bonificaciones` | IIS rechaza algo del `web.config`. Es el mismo que usa `/api/impuestos`: compará los dos y avisá |
| `/health` anda pero el panel carga en blanco | La aplicación de IIS no quedó en `api/bonificaciones` exacto |
| 502 en el navegador | El servicio está parado: `Get-Service bonificaciones` |
| Todo anda y la tienda dice que no | Panel → **Distribuidoras**, tarjeta **Actividad**: si no hay llamadas de la tienda, no están llegando |

---

## Si sos un Claude ayudando con esta instalación

- La persona puede no ser devop: explicá cada paso, no solo el comando.
- No corras nada que no esté en esta guía, y nada que modifique lo que ya existía en el servidor
  (`Remove-*`, cambios globales de IIS, variables de máquina, la base o el login de MotorFiscal).
  Los scripts están hechos para no tocar MotorFiscal; improvisar es la forma de romperlo.
- Si un script frena, pedí la salida completa antes de proponer algo: casi siempre dice qué falta.
- Las claves del Paso 2 no se pegan en un chat común: van al gestor de contraseñas.
