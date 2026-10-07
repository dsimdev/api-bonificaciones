# Instalación y deploy

> **Mismo enfoque que `api-impuestos`**, a propósito: quien opera el servidor no tiene que aprender
> dos formas distintas de instalar un servicio nuestro. Ver `api-impuestos/docs/proyecto/deploy-iis.md`
> para el detalle de IIS y del servicio de Windows, que acá se replica.

## 1. SQL Server

Igual que en local, con dos diferencias: la contraseña de `bonificaciones` **no** es
`bonificaciones`, y SQL Server escucha solo en `localhost` (la app corre en la misma máquina, así
que **no hay que abrir el 1433**).

```powershell
& 'C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\170\Tools\Binn\SQLCMD.EXE' -S localhost,1433 -E -i scripts\crear-base.sql
```

Después, cambiá la contraseña y **restaurá la política**, que el script deja apagada para
desarrollo (`CHECK_POLICY = OFF`):

```sql
ALTER LOGIN bonificaciones WITH PASSWORD = 'la-que-generaste', CHECK_POLICY = ON;
```

Las tablas las crea **Flyway** al arrancar. No hay que correr DDL a mano.

> La collation `Latin1_General_100_CS_AS` (case-sensitive) se fija **al crear la base**, igual que
> en motorfiscal. Con la collation típica (case-insensitive), `dyssa` y `Dyssa` entrarían como el
> mismo valor en `distribuidora.codigo`, que es UNIQUE. Cambiarla después es costoso.

## 2. Variables de entorno

| Variable | Para qué | Si falta |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | la base | no arranca |
| `CIFRADO_KEY` | cifra las claves de GESCOM (AES-256-GCM). `openssl rand -hex 32` | **dar de alta falla explícito**, a propósito: mejor eso que guardar mil claves de producción sin cifrar |
| `USUARIO_INICIAL`, `CLAVE_INICIAL` | el primer usuario del panel | si no hay usuarios, nadie puede administrar y avisa por log |

> ⚠️ **`CIFRADO_KEY` no se puede perder ni rotar a la ligera.** Si cambia, las claves guardadas no
> se pueden descifrar y hay que volver a cargar las credenciales de todas las distribuidoras.
> Guardala donde se guardan los secretos del servidor, no en el repo.

El usuario inicial se crea **solo si la tabla está vacía**. Después de usarlo: cambiarle la
contraseña y **sacar `CLAVE_INICIAL` del entorno**.

## 3. Verificación post-deploy

```
GET /health
```

Tiene que decir **`"origen": "BASE"`**. Si dice `CONFIGURACION`, el deploy quedó leyendo variables
de entorno y las altas que se hagan por el panel no se van a ver al reiniciar.

Y `total` tiene que coincidir con las distribuidoras que esperás.

## 4. Pendiente

- El **panel** (Fase 3c) todavía no existe; administrar se hace por `/admin/v1/**` desde Swagger.
- Cuando el panel exista, el checklist suma el paso del **proxy anidado**: compilarlo con el
  prefijo de ruta completo y probarlo **a través** del IIS, nunca contra `localhost:8080`. En
  api-impuestos ese bug llegó a producción tres veces.
