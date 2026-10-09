# Novedades

Qué cambió en cada versión, contado para quien integra contra esta API. En español, sin
tecnicismos.

## 0.8.1 — 2026-10-09

- **Nuevo campo `aplicaATodo` en cada criterio.** `true` si el criterio no tiene condiciones de
  artículo (aplica a todo el catálogo), `false` si tiene (y `articulos` lista los códigos). Antes,
  `articulos` vacío podía significar las dos cosas.

## 0.8.0 — 2026-10-09

- **Cada criterio ahora trae los artículos a los que aplica.** El campo `articulos` en
  `GET /v1/{tenant}/criterios` lista los códigos de artículo que matchean las condiciones del
  criterio (marca, rubro, proveedor, etc.), resueltos contra el catálogo de GESCOM. La tienda ya no
  necesita hacer ese cruce.
- **Filtro por cliente.** Con `?cliente=8380` se devuelven solo los criterios que aplican a ese
  cliente (por tag, subramo o código). Sin el parámetro, se devuelven todos.
- **Los artículos y los clientes se cachean** igual que los criterios: se traen de GESCOM una vez y
  se refrescan cada 60 minutos. Si GESCOM no responde al refrescar, se sirven los datos anteriores.

## 0.7.0 — 2026-10-09

- **Los criterios de venta se cachean en memoria.** En vez de consultar a GESCOM en cada pedido de
  criterios, se guardan por distribuidora y se refrescan cada 60 minutos (configurable). Si GESCOM
  no responde al refrescar, se sirven los datos que ya había en vez de fallar. Esto mejora la
  velocidad del catálogo y permite que la tienda consulte los criterios sin preocuparse por el
  tráfico a GESCOM.
- **La respuesta de criterios ahora incluye `actualizadoEn`**: la fecha y hora en que se trajo el
  catálogo de GESCOM. Sirve para saber qué tan frescos son los datos.
- **La clave de la tienda (`VALORIZACION`) ahora puede leer el catálogo de criterios**
  (`GET /v1/{tenant}/criterios`). Antes necesitaba una clave `ADMIN`. No hace falta generar una
  clave nueva: la que ya tiene la tienda funciona.
- **En el panel, el detalle de un criterio se abre en la misma fila** en vez de en una tarjeta
  separada abajo de todo. Más cómodo para revisar la lista.

## 0.6.3 — 2026-10-08

- **Seguridad: el login del panel ahora tiene límite de intentos** (5 en 15 minutos).
- **Cambiar la contraseña requiere la contraseña actual.** Una sesión robada ya no alcanza.
- **Las excepciones inesperadas no filtran detalles internos** al que llama.
- **La llamada al servicio va desde el servidor de la tienda**, no desde el navegador. La clave y
  el descuento ya no quedan expuestos en DevTools. El módulo `bonificaciones.js` acepta la URL del
  servicio en `configurarBonificaciones({ url })`.

## 0.6.2 — 2026-10-08

- El contrato ahora deja claro qué hace la tienda con una unidad regalada por una promo
  (`creadaPorPromo: true`): se muestra como regalo, **no se cobra y no va a MotorFiscal**.

## 0.6.1 — 2026-10-08

- **Una clave válida nunca queda bloqueada.** Antes, muchos intentos con claves inválidas para una
  distribuidora la bloqueaban 5 minutos para todos. Ahora `DEMASIADOS_INTENTOS` (429) solo le llega
  a quien usa una clave inválida.
- **Si mandás tu precio, no mandes `listaPrecio`**: verificado que no cambia el descuento. Antes
  la guía decía lo contrario.
- **Las líneas de la respuesta no vienen en el orden del carrito**: relacionalas por `codigo` (y
  `creadaPorPromo` para los regalos).
- En un carrito mixto (ítems con precio y sin precio) con `listaPrecio`, ya no aparece el aviso
  `PRECIO_Y_LISTA_JUNTOS`: ahí la lista sí se usa. Sale solo si todos los ítems traen precio.
- El módulo `bonificaciones.js` llama al servicio en el mismo dominio de la tienda
  (`/api/bonificaciones`), acepta una clave por distribuidora y corta a los 10 segundos.
  `conDescuentos` nunca corta la venta, y marca con `hayQueCorregir` los errores que no se
  arreglan solos. Nuevo `usarRespuestasDePrueba` para desarrollar con los ejemplos.
- La guía de integración ya no sugiere dar de alta clientes en el ERP: este servicio no da de alta
  clientes. Un cliente que no está en el ERP sigue igual: `CLIENTE_INEXISTENTE`, 0% y tu precio.

## 0.6.0 — 2026-10-08

- **Ahora hace falta una clave.** Cada llamada a `/v1/{tenant}/…` lleva el header `x-api-key` con
  la clave de esa distribuidora. La clave de una distribuidora no sirve para otra (da 401).
- La clave del checkout **solo sirve para valorizar**. El catálogo completo de bonificaciones
  (`GET /v1/{tenant}/criterios`) pide una clave de administración y con la del checkout da 403.
- **Podés mandar tu propio precio** por ítem, en `items[].precioUnitario` (por unidad, no el total
  de la línea). El descuento lo sigue calculando el ERP, con tu precio. Si un ítem no trae precio,
  se valoriza con `listaPrecio`. Se pueden mezclar ítems con y sin precio en el mismo carrito.
- **El número a aplicar es `lineas[].descuento`.** No sumes `bonificaciones[].descuento`: es el
  detalle de qué bonificación otorgó qué, y una línea puede tener más de una.
- Avisos nuevos en `supuestos`: `SIN_PRECIO_NI_LISTA` (dice qué ítems quedaron sin precio) y
  `PRECIO_Y_LISTA_JUNTOS` (mandaste los dos; gana el precio). Reemplazan a `LISTA_PRECIO_NO_ENVIADA`.
- Errores nuevos: `NO_AUTORIZADO` (401), `ALCANCE_INSUFICIENTE` (403) y `DEMASIADOS_INTENTOS` (429).
- Si el ERP no responde, el servicio **reintenta una vez solo** antes de devolver
  `FUENTE_NO_DISPONIBLE`. Ese sí conviene reintentarlo de tu lado; los demás errores, no.
- Ojo con las **líneas regaladas** (`creadaPorPromo: true`): traen el precio del ERP en `neto`,
  no el tuyo. Para cobrar usá `totales.netoConDescuento`, que siempre está bien.
- En producción va a vivir en `/api/bonificaciones`, al lado de MotorFiscal. Se confirma con el
  primer deploy.

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
