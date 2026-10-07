# Novedades

Qué cambió en cada versión, contado para quien integra contra esta API. En español, sin
tecnicismos.

## 0.2.0 — 2026-10-07

- Ya se puede **valorizar un pedido**: `POST /v1/{tenant}/valorizaciones`. Se le manda el cliente
  y los ítems, y devuelve cada línea con su neto, el descuento que le corresponde y **qué
  bonificación se lo otorgó**, con las condiciones que la dispararon.
- El descuento viaja en **porcentaje**: `10` significa 10%.
- Cada respuesta dice de dónde salió el número: `calculadoPor: "ERP"` es el ERP de la
  distribuidora; `"GATEWAY"` sería un cálculo nuestro. Por ahora siempre es el ERP.
- Los errores traen un **código** además del status HTTP, para poder distinguir "el pedido está
  mal" de "el ERP no responde" sin leer el texto.
- Un pedido inválido (sin ítems, con cantidad cero, o de una distribuidora no configurada) se
  rechaza **sin consultar al ERP**.

## 0.1.0 — 2026-10-06

- Primera versión: el servicio levanta, informa su estado en `/health` y publica su documentación
  en `/swagger-ui.html`.
- Todavía **no** consulta bonificaciones: es el andamio sobre el que se construye. El primer
  endpoint útil llega en 0.2.0.
