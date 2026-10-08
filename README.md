# api-bonificaciones

Gateway de **bonificaciones / criterios de venta**. Consume la API de **GESCOM** y la devuelve
detrás de un contrato propio y estable.

**Versión actual: 0.2.0** — valoriza pedidos contra GESCOM. **Solo GESCOM**: Axum quedó fuera de
alcance (la tienda lo consume directo).

```
POST /v1/{tenant}/valorizaciones
{ "cliente": "8380", "listaPrecio": "2",
  "items": [ { "codigo": "5000014792", "cantidad": 6, "unidad": "Unidad" } ] }
```

Devuelve cada línea con su neto, su descuento y **qué bonificación lo otorgó, con las condiciones
que la dispararon**. El descuento va en **porcentaje** (`10` = 10%). `calculadoPor` dice si el
número lo dio el ERP o lo calculó el gateway.

## Por qué existe

La API de GESCOM es un gateway de comandos sin Swagger, con token de 5 minutos, los endpoints
repartidos entre servicios (`ventas` / `inventario`) y errores que casi siempre dicen
`"Error desconocido"`. Cada consumidor que quiera saber "qué bonificación le cae a este pedido"
tiene que resolver todo eso de nuevo. Este servicio lo resuelve una vez.

## El panel

En **<http://localhost:8081/admin>**, embebido en el mismo jar. Desde ahí se da de alta una
distribuidora **probando la credencial contra GESCOM antes de guardar** (si no anda, no se guarda
nada), se vuelve a probar una credencial ya cargada, y se **prueba una valorización mostrando las
tres capas**: lo que le pedimos al ERP, lo que el ERP contestó crudo y lo que devolvemos nosotros.
Eso último es para soporte: sirve para decidir si un descuento mal está mal en el ERP, en nuestra
normalización o en la tienda.

Entra con **usuario y contraseña** (no una clave compartida): cada distribuidora guarda quién la
dio de alta.

## Arrancar

Requisitos: **JDK 21** y **SQL Server** (las distribuidoras viven en la base). Gradle no hace
falta instalarlo, está el wrapper. **Node** solo si se va a tocar el panel.

```powershell
& 'C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\170\Tools\Binn\SQLCMD.EXE' -S localhost -E -i scripts\crear-base.sql
.\arrancar.ps1 -Build
```

- panel: <http://localhost:8081/admin>
- salud: <http://localhost:8081/health> — dice si está leyendo de la base (`"origen": "BASE"`)
- swagger: <http://localhost:8081/swagger-ui.html>

En el `.env` (gitignored) van los secretos del entorno: `CIFRADO_KEY` (cifra las claves de GESCOM),
y `USUARIO_INICIAL` / `CLAVE_INICIAL` para el primer usuario del panel. **Las credenciales de las
distribuidoras ya no van en el `.env`**: se cargan desde el panel. Detalle en
[docs/proyecto/deploy.md](docs/proyecto/deploy.md).

## Build y tests

```powershell
.\gradlew.bat build                                 # compila (panel incluido) y corre los tests
.\gradlew.bat cleanTest build -PincludeDbTests      # suma los que necesitan SQL Server
.\gradlew.bat cleanTest build -PincludeErpTests     # suma los que pegan contra un ERP real
```

`cleanTest` no es opcional en los dos últimos: sin él Gradle los da por *up to date* y no los
corre. **Los que llevan `-PincludeDbTests` vacían `distribuidora` y `credencial` de la base
local** — después hay que volver a dar de alta lo que tuvieras cargado a mano.

Para un deploy detrás de un proxy anidado, el panel se compila distinto:
`-PpanelBasePath=/api/bonificaciones/admin`. Ver [docs/proyecto/deploy.md](docs/proyecto/deploy.md),
que explica por qué saltearlo deja el panel en blanco.

## Estructura

| Módulo | Qué hay adentro |
|---|---|
| `bonif-core` | Modelo normalizado de criterios y los puertos hacia las fuentes. Sin Spring: solo JDK. |
| `bonif-app` | API REST, administración, seguridad y el conector de GESCOM. |
| `panel` | El panel (Next, export estático). `gradlew build` lo compila y lo mete en el jar, en `/admin`. |

## Documentación

- **[docs/guia-de-integracion.md](docs/guia-de-integracion.md) — el contrato, para quien integra la
  tienda.** Es lo que hay que pasarle al dev del checkout.

- [docs/proyecto/](docs/proyecto/) — arquitectura, plan de fases y decisiones abiertas.
- [docs/metodo/](docs/metodo/) — cómo se trabaja: deploy, testing, auditoría, memoria.
- `C:\Dev\docs\gescom\eval-pedido.md` — la referencia de la API de GESCOM (cross-project).
- `CLAUDE.md` — directivas de trabajo y reglas duras del gateway.
