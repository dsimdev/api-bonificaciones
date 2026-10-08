# Entorno local

## Requisitos

- **JDK 21** (verificado: Temurin/Oracle 21.0.11). No hace falta instalar Gradle: está el wrapper.
- **SQL Server** (Express alcanza). Las distribuidoras y sus credenciales viven en la base desde la
  v0.3.0: son ~1000 y un alta no puede implicar reiniciar el servicio.
- **Node 22** solo si se va a tocar el panel. Si no está, `gradlew build` no falla: el jar sale sin
  panel y la API funciona igual.
- Sin Docker.

## Crear la base

```powershell
& 'C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\170\Tools\Binn\SQLCMD.EXE' -S localhost -E -i scripts\crear-base.sql
```

Las tablas las crea **Flyway** al arrancar; el script solo crea la base y el login. La collation
`Latin1_General_100_CS_AS` (case-sensitive) se fija ahí y **no se cambia después**: con la
collation habitual, `dyssa` y `Dyssa` serían el mismo valor en `distribuidora.codigo`, que es
UNIQUE.

## Levantar

```powershell
.\arrancar.ps1 -Build     # compila y levanta
.\arrancar.ps1            # usa el jar ya compilado
```

- **panel: <http://localhost:8081/admin>**
- salud: <http://localhost:8081/health>
- swagger: <http://localhost:8081/swagger-ui.html>
- spec OpenAPI: <http://localhost:8081/v3/api-docs>

`/health` devuelve la versión que está corriendo de verdad (la embebe el build), **de dónde está
leyendo las distribuidoras** (`"origen": "BASE"` o `CONFIGURACION`) y cuáles son. Un deploy que
quedó sin base se ve ahí, no cuando alguien da de alta algo y al reiniciar no está.

## Secretos del entorno

```powershell
Copy-Item .env.example .env
```

| Variable | Para qué |
|---|---|
| `CIFRADO_KEY` | cifra las claves de GESCOM (AES-256-GCM). `openssl rand -hex 32` |
| `USUARIO_INICIAL`, `CLAVE_INICIAL` | el primer usuario del panel. Solo se crea si la tabla está **vacía** |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | la base, si no son los valores por defecto locales |

`arrancar.ps1` carga el `.env` solo. **`.env` está en `.gitignore`: no se commitea nunca.**

**Las credenciales de las distribuidoras ya no van acá**: se cargan desde el panel, que las prueba
contra GESCOM antes de guardarlas y las guarda cifradas. Sin `CIFRADO_KEY` el alta falla explícito
— es preferible a guardar claves de producción sin cifrar.

## El panel

Se compila solo con `gradlew build` y queda embebido en el jar, en `/admin`. Para trabajar en él
con recarga en vivo:

```powershell
cd panel
npm install        # la primera vez
npm run dev        # http://localhost:3001
npm run typecheck
```

En `npm run dev` el panel corre en otro puerto que la API, así que los `fetch` a `/admin/v1/...`
caen en el 3001 y fallan. Para probar de verdad va `gradlew build` y `/admin` servido por Spring,
que es como corre en producción.

> ⛔ Para un deploy detrás de un IIS que lo cuelgue como aplicación anidada hay que compilarlo con
> `-PpanelBasePath=/api/bonificaciones/admin` o **queda en blanco con 404**. Ver
> [deploy.md](deploy.md) y el comentario de `panel/next.config.mjs`.

## Build y tests

```powershell
.\gradlew.bat build                                # compila todo (panel incluido) y corre los tests
.\gradlew.bat test --tests '*Vigencia*'            # un test puntual
.\gradlew.bat cleanTest build -PincludeDbTests     # suma los que necesitan SQL Server
.\gradlew.bat cleanTest build -PincludeErpTests    # suma los que pegan contra el ERP real
```

Los tests etiquetados `erp` y `db` están **excluidos por defecto**: necesitan red con credenciales
reales, o una base. Así `gradlew build` sigue verde en cualquier máquina y sin secretos.

**`cleanTest` no es opcional** en los dos últimos: sin él Gradle los da por *up to date* y no los
vuelve a correr, con lo cual parece que pasaron.

> ⚠️ **`-PincludeDbTests` vacía `distribuidora` y `credencial` de la base local.** Si tenías
> distribuidoras cargadas a mano para probar el panel, después del build ya no están — y quedan las
> del test, que parecen válidas pero apuntan a un puerto cerrado. Hay que volver a darlas de alta.
> La tabla `usuario` no se toca.
## Pegarle a GESCOM a mano

El token de Keycloak dura **5 minutos**, así que hay que mintearlo y usarlo en el mismo script —
un token pegado a mano un rato después ya está vencido.

```bash
HOST=https://dyssa.gescom.online
REALM=gcw-dyssa

TOKEN=$(curl -s -X POST "https://auth.gescom.online/realms/$REALM/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "client_id=gcw-web-api&grant_type=password&username=$USUARIO&password=$CLAVE" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

curl -s "$HOST/data/cmd/ventas/api/v1/get-promociones" -H "Authorization: Bearer $TOKEN" \
  > fixtures/get-promociones-dyssa.json
```

Recordar: `get-articulos` vive en el servicio **`inventario`**, no en `ventas`. Una ruta que no
existe responde `Unity.Exceptions.InvalidRegistrationException` — es la forma de distinguir
"comando inexistente" de "comando que falló".

Detalle completo y campos verificados: `C:\Dev\docs\gescom\eval-pedido.md`.

## Git

- Se desarrolla en rama `feat/…` o `fix/…`. **Hay un hook que bloquea editar y commitear en
  `main`** (`.claude/settings.json`). Es a propósito: `main` es producción.
- Los hooks recién pegados **no se cargan hasta reiniciar la sesión de Claude Code**. Es el
  gotcha que más tiempo cuesta.
