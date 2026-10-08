# Instalación y deploy

> **Mismo enfoque que `api-impuestos`**, a propósito: quien opera el servidor no tiene que aprender
> dos formas distintas de instalar un servicio nuestro. Ver `api-impuestos/docs/proyecto/deploy-iis.md`
> para el detalle de IIS y del servicio de Windows, que acá se replica.
>
> **Los pasos concretos de instalación están en
> [`entrega-servidor/LEEME.md`](../../entrega-servidor/LEEME.md)** (lo que sigue quien instala), y
> cómo armar esa carpeta, en [`scripts/servidor/GUIA-PRIMER-DEPLOY.md`](../../scripts/servidor/GUIA-PRIMER-DEPLOY.md).
> Este documento es el *por qué*.

## 0. Dónde corre, y las dos cosas que no se pueden improvisar

**Va en el mismo servidor que MotorFiscal** (decidido 2026-10-08). Eso define dos cosas:

| | Valor | Por qué no se puede cambiar a la ligera |
|---|---|---|
| **Puerto** | **8081** | MotorFiscal usa el 8080. Dos servicios en el mismo puerto no arrancan los dos, y el segundo falla con un `Address already in use` que no dice de quién es el puerto |
| **Ruta de IIS** | `/api/bonificaciones` | Tiene que ser **la misma** con la que se compila el panel (`-PpanelBasePath=/api/bonificaciones/admin`). Si no coinciden, la API anda y el panel queda en blanco |

Lo bueno de compartir servidor: Java, IIS, ARR, URL Rewrite y el certificado **ya están** porque
MotorFiscal los usa. Por eso `preparar-iis.ps1` es mucho más corto que el de MotorFiscal — solo
cuelga una aplicación más, con su propio `web.config` y su propio application pool, y no toca el
sitio ni a MotorFiscal.

### Los scripts

| Script | Qué hace | Dónde se corre |
|---|---|---|
| `scripts/servidor/preparar-sistema.ps1` | Java, base y login propios, carpeta + WinSW, jar, secretos en el entorno del servicio, arranque y verificación. Idempotente; con `-Jar` también actualiza | en el servidor, como admin |
| `scripts/servidor/preparar-iis.ps1` | cuelga la aplicación de IIS en `/api/bonificaciones`, en el sitio donde está `/api/impuestos`. Verifica ARR, no lo cambia | en el servidor, como admin |
| `scripts/servidor/simular-proxy-anidado.ps1` | **reproduce la topología de producción en tu máquina, sin IIS** | en tu máquina |
| `scripts/deploy.ps1` | build + verifica el jar + FTP + reinicia + verifica a través del proxy | en tu máquina |
| `scripts/servidor/bonificaciones.xml` | el servicio de Windows (WinSW) | se copia al servidor |
| `scripts/servidor/web.config` | el reverse proxy de IIS | lo copia `preparar-iis.ps1` |

⚠️ **Ninguno se probó contra un servidor real todavía.** Lo que **sí** está verificado es
`simular-proxy-anidado.ps1`, y con él toda la superficie de la app en la topología anidada: health,
el panel con sus chunks, el login, la administración y Swagger.

## 1. SQL Server

En el servidor la base y el login los crea `preparar-sistema.ps1`, con una clave aleatoria y
`CHECK_POLICY = ON` desde el primer momento (`scripts/crear-base.sql` es solo para desarrollo
local: su clave es `bonificaciones`). Misma instancia que MotorFiscal, en `localhost`: **no hay que
abrir el 1433**. Necesita modo mixto (usuario y clave de SQL), igual que MotorFiscal; el script lo
verifica.

Las tablas las crea **Flyway** al arrancar. No hay que correr DDL a mano.

> La collation `Latin1_General_100_CS_AS` (case-sensitive) se fija **al crear la base**, igual que
> en motorfiscal. Con la collation típica (case-insensitive), `dyssa` y `Dyssa` entrarían como el
> mismo valor en `distribuidora.codigo`, que es UNIQUE. Cambiarla después es costoso.

## 2. Variables de entorno

**En el servidor no son variables de máquina.** MotorFiscal dejó `DB_PASSWORD` y `CIFRADO_KEY`
como variables de máquina con SUS valores; si este servicio usara esos nombres, heredaría la clave
de base de MotorFiscal (y no conectaría) o, peor, un script nuestro la pisaría y el que se rompe es
MotorFiscal. Por eso:

- Los secretos van en el **entorno propio del servicio** (registro:
  `HKLM\SYSTEM\CurrentControlSet\Services\bonificaciones`, valor `Environment`), que Windows aplica
  al arrancar el servicio — sin reiniciar el servidor y sin que lo vea otro proceso.
- Con nombres propios: la app lee `BONIF_DB_PASSWORD` y `BONIF_CIFRADO_KEY` **antes** que
  `DB_PASSWORD` y `CIFRADO_KEY`. En local se siguen usando los nombres cortos del `.env`.
- `DB_URL`, `DB_USER` y `PORT` van en `bonificaciones.xml` (no son secretos).

| Variable | Para qué | Si falta |
|---|---|---|
| `DB_URL`, `DB_USER`, `BONIF_DB_PASSWORD` (o `DB_PASSWORD`) | la base | no arranca |
| `BONIF_CIFRADO_KEY` (o `CIFRADO_KEY`) | cifra las claves de GESCOM (AES-256-GCM). `openssl rand -hex 32` | **las distribuidoras no se pueden leer ni dar de alta**: falla explícito |
| `CLAVE_INICIAL` | la contraseña del primer usuario, que se llama **`admin`** en todas las instalaciones (`USUARIO_INICIAL` lo pisa, pero no hace falta) | si no hay usuarios, nadie puede administrar y avisa por log |

> ⚠️ **`CIFRADO_KEY` no se puede perder ni rotar a la ligera.** Si cambia, las claves guardadas no
> se pueden descifrar y hay que volver a cargar las credenciales de todas las distribuidoras
> (desde el panel; desde la v0.6.1 eso funciona aunque la clave vieja ya no exista). Guardala en el
> gestor de contraseñas, no en el repo.

El usuario inicial se crea **solo si la tabla está vacía**. Después de usarlo: cambiarle la
contraseña y correr `preparar-sistema.ps1 -OlvidarClaveInicial`.

## 3. Verificación post-deploy

```
GET /health
```

Tiene que decir **`"origen": "BASE"`**. Si dice `CONFIGURACION`, el deploy quedó leyendo variables
de entorno y las altas que se hagan por el panel no se van a ver al reiniciar.

Y `total` tiene que coincidir con las distribuidoras que esperás.

## 4. El panel

Vive en `/admin`, **embebido en el jar** (export estático de Next). No se deploya aparte y no
necesita Node en el servidor: `gradlew build` lo compila y lo mete adentro. Node sí hace falta en
la máquina donde se **compila**; si no hay `node_modules`, el build no falla y el jar sale sin
panel (la API funciona igual).

### ⛔ El paso que no se puede saltear: con qué `panelBasePath` se compila

El `basePath` queda **horneado en el HTML y el JS** en tiempo de build, así que **el jar para un
deploy detrás de proxy anidado es un artefacto distinto** del que se prueba en local:

| Cómo entra el navegador | Cómo se compila | Jar que sale |
|---|---|---|
| `http://servidor:8081/admin` (directo a Spring) | `gradlew build` | `bonif-app-X.Y.Z.jar` |
| `https://dominio/api/bonificaciones/admin` (IIS lo cuelga anidado) | `gradlew build -PpanelBasePath=/api/bonificaciones/admin` | `bonif-app-X.Y.Z-prod.jar` |

El `-prod` del nombre no es cosmético: sin él los dos jars se llaman igual y un build local
posterior **pisa en silencio** el de producción ya verificado. En api-impuestos eso pasó dos veces.

Si se compila con el basePath equivocado, **el panel queda en blanco con 404 en la consola** y la
API no es el problema. En api-impuestos ese bug llegó a producción **tres veces**. El detalle de
por qué está en el comentario de `panel/next.config.mjs`.

**Probar el panel a través del proxy, nunca contra `localhost:8081` directo.** Contra localhost
anda igual con el basePath mal, así que esa prueba no detecta nada.

`deploy.ps1` abre el jar antes de subirlo y verifica que el panel de adentro pida sus archivos en
la ruta correcta. Es barato y es exactamente el error que no se nota hasta que alguien abre el
panel en producción.

### No es solo el panel: Swagger tiene el mismo problema

De las tres veces que esto llegó a producción en api-impuestos, **dos fueron Swagger**, no el
panel. Swagger UI también arma URLs absolutas para sí mismo (`/v3/api-docs` y, por separado,
`configUrl`), y detrás del proxy anidado las resuelve contra la raíz del dominio.

Acá eso está resuelto por configuración: el build pasa `externalBasePath` al `application.yml` y de
ahí salen `springdoc.swagger-ui.url`, `config-url` y el `servers` del spec (ver
`ConfiguracionOpenApi`). **Hay que setear los dos campos**: en MotorFiscal arreglar solo `url` no
alcanzó, porque springdoc arma `configUrl` con su propia auto-detección y la ignora.

Verificado con el simulador: sin el arreglo, `swagger-config` devolvía `"/v3/api-docs"` (raíz del
dominio, 404 detrás del proxy); con el arreglo devuelve `"/api/bonificaciones/v3/api-docs"`.

### Verificación del panel

1. Abrir `/admin` (sin barra final también tiene que andar: va por *forward*, no por redirect).
2. Entrar con el usuario inicial, dar de alta una distribuidora y ver que diga
   *"trajo N criterios"*. Eso prueba de punta a punta la base, el cifrado, Keycloak y GESCOM.
3. Guardar la clave de la tienda que muestra: **se ve una sola vez.**
