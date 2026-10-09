# api-bonificaciones

Gateway de **bonificaciones / criterios de venta**. Consume la API de **GESCOM** y la devuelve
detrás de un contrato propio y estable.

**Versión actual: 0.8.1** — valoriza pedidos contra GESCOM con el precio de la tienda o una lista
del ERP, con clave por distribuidora y panel de administración. Los criterios se cachean por
distribuidora (60 min, configurable) con fallback a datos vencidos si GESCOM no responde. El
catálogo de criterios incluye los artículos ya resueltos y soporta filtro por cliente. **Solo
GESCOM**: Axum quedó fuera de alcance (la tienda lo consume directo).

```
POST /v1/{tenant}/valorizaciones            x-api-key: <clave de la distribuidora>
{ "cliente": "8380",
  "items": [ { "codigo": "5000014792", "cantidad": 6, "precioUnitario": 1000 } ] }
```

Cada ítem lleva el precio de la tienda (`precioUnitario`, por unidad) o se valoriza con
`listaPrecio`. El descuento lo calcula siempre el ERP.

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
y `CLAVE_INICIAL`, la contraseña del primer usuario del panel (se llama **`admin`**). **Las credenciales de las
distribuidoras ya no van en el `.env`**: se cargan desde el panel. Detalle en
[docs/proyecto/deploy.md](docs/proyecto/deploy.md).

## Build y tests

```powershell
.\gradlew.bat build                                 # compila (panel incluido) y corre los tests
.\gradlew.bat cleanTest build -PincludeDbTests      # suma los que necesitan SQL Server
.\gradlew.bat cleanTest build -PincludeErpTests     # suma los que pegan contra un ERP real
```

`cleanTest` no es opcional en los dos últimos: sin él Gradle los da por *up to date* y no los
corre. Los de `-PincludeDbTests` usan distribuidoras con prefijo `zzz-test-` y borran solo esas:
lo que tengas cargado a mano en la base local no se toca.

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

- **[entrega-tienda/](entrega-tienda/LEEME.md) — la carpeta para quien integra el checkout.** Se
  pasa entera: reglas, módulo JS, contrato, Postman y ejemplos reales.
- **[entrega-servidor/](entrega-servidor/LEEME.md) — la carpeta para quien instala en el
  servidor.** Lleva el jar de producción (que no está en git: se compila con
  `-PpanelBasePath=/api/bonificaciones/admin`) y los scripts.

- [docs/guia-de-integracion.md](docs/guia-de-integracion.md) — el contrato (fuente de verdad del que
  está en `entrega-tienda/`).
- [scripts/servidor/GUIA-PRIMER-DEPLOY.md](scripts/servidor/GUIA-PRIMER-DEPLOY.md) — cómo compilar,
  probar y armar `entrega-servidor/` (la instalación en sí está en su LEEME).

- [docs/proyecto/deploy.md](docs/proyecto/deploy.md) — el *por qué* del deploy: el puerto, la ruta
  de IIS y por qué el jar de producción es otro artefacto.
- [docs/proyecto/](docs/proyecto/) — arquitectura, plan de fases y decisiones abiertas.
- [docs/metodo/](docs/metodo/) — cómo se trabaja: deploy, testing, auditoría, memoria.
- `C:\Dev\docs\gescom\eval-pedido.md` — la referencia de la API de GESCOM (cross-project).
- `CLAUDE.md` — directivas de trabajo y reglas duras del gateway.
