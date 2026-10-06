# api-bonificaciones

Gateway de **bonificaciones / criterios de venta**. Consume las APIs de los ERP de las
distribuidoras (hoy **GESCOM**) y las devuelve detrás de un contrato único, propio y estable.

**Versión actual: 0.1.0** — andamio. Levanta, informa salud y publica su OpenAPI; todavía no
consulta ningún ERP.

## Por qué existe

La API de GESCOM es un gateway de comandos sin Swagger, con token de 5 minutos, los endpoints
repartidos entre servicios (`ventas` / `inventario`) y errores que casi siempre dicen
`"Error desconocido"`. Cada consumidor que quiera saber "qué bonificación le cae a este pedido"
tiene que resolver todo eso de nuevo. Este servicio lo resuelve una vez.

## Arrancar

Requisitos: **JDK 21** (no hace falta instalar Gradle, está el wrapper).

```powershell
.\arrancar.ps1 -Build
```

- salud: <http://localhost:8080/health>
- swagger: <http://localhost:8080/swagger-ui.html>

Para pegarle a un ERP real hacen falta credenciales por distribuidora: copiar `.env.example` a
`.env` y completarlo (está en `.gitignore`, no se commitea). Detalle en
[docs/proyecto/entorno-local.md](docs/proyecto/entorno-local.md).

## Build y tests

```powershell
.\gradlew.bat build                      # compila y corre los tests
.\gradlew.bat build -PincludeErpTests    # suma los tests que pegan contra un ERP real
```

## Estructura

| Módulo | Qué hay adentro |
|---|---|
| `bonif-core` | Modelo normalizado de criterios y los puertos hacia los ERP. Sin Spring: solo JDK. |
| `bonif-app` | API REST, configuración y los conectores por ERP. |

## Documentación

- [docs/proyecto/](docs/proyecto/) — arquitectura, plan de fases y decisiones abiertas.
- [docs/metodo/](docs/metodo/) — cómo se trabaja: deploy, testing, auditoría, memoria.
- `C:\Dev\docs\gescom\eval-pedido.md` — la referencia de la API de GESCOM (cross-project).
- `CLAUDE.md` — directivas de trabajo y reglas duras del gateway.
