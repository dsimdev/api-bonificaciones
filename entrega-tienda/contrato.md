# API Bonificaciones — contrato para la tienda

Este servicio dice **qué descuento le corresponde a un carrito, y por qué**. El número lo calcula el
ERP de la distribuidora (GESCOM); este servicio lo consulta, lo normaliza y explica de dónde salió.
La tienda pone el precio y aplica el porcentaje.

## Lo que te tienen que pasar

Tres datos, que llegan juntos de quien administra el servicio:

| Dato | Ejemplo | Dónde va |
|---|---|---|
| Código de la distribuidora (`tenant`) | `dyssa` | En la ruta |
| Clave de esa distribuidora | `bon_…` | En el header `x-api-key` |
| URL | `https://<dominio-de-la-tienda>/api/bonificaciones` | Para Postman o `curl`. El módulo `bonificaciones.js` no la necesita: llama a `/api/bonificaciones` en el mismo dominio de la tienda, como MotorFiscal en `/api/impuestos` |

Si la tienda trabaja con más de una distribuidora, cada una tiene su código y su clave.

## Las reglas

1. **Los códigos son los que ya usa la tienda**, que vienen del ERP de la distribuidora o de Axum:
   el del cliente y el de cada artículo. El servicio no traduce códigos.
2. **El precio lo ponés vos.** Mandá `items[].precioUnitario`: **por unidad** y **sin impuestos**.
   Con tu precio **no hace falta `listaPrecio`**: no cambia el descuento (verificado).
3. **Aplicá `lineas[].descuento` sobre tu neto de cada línea.** Está en **porcentaje** (`10` =
   10%). No sumes `bonificaciones[].descuento`: es el detalle de quién otorgó qué.
4. **Mandá el carrito entero**, en una llamada, con cada artículo una sola vez. Hay descuentos que
   dependen de toda la venta (cantidades mínimas, marcas, rubros).
5. **Relacioná las líneas por `codigo`, no por posición.** El orden de la respuesta no es el del
   carrito, y puede venir una línea de más: un regalo de una promo (`creadaPorPromo: true`).

---

## Valorizar un carrito

```
POST {URL}/v1/{tenant}/valorizaciones
x-api-key: {clave}
Content-Type: application/json
```

```json
{
  "cliente": "8380",
  "referencia": "carrito-991",
  "items": [
    { "codigo": "5000014792", "cantidad": 6, "precioUnitario": 1000 }
  ]
}
```

| Campo | ¿Obligatorio? | Qué es |
|---|---|---|
| `cliente` | **sí** | El código del cliente en el ERP de la distribuidora |
| `items[].codigo` | **sí** | El código del artículo en el ERP de la distribuidora, una vez por artículo |
| `items[].cantidad` | **sí** | En unidades, mayor que cero |
| `items[].precioUnitario` | recomendado | Tu precio **por unidad**, **sin impuestos**, mayor que cero |
| `listaPrecio` | no | Solo si algún artículo no tiene precio en la tienda: el ERP le pone el precio de esa lista. El código de lista lo define la distribuidora: preguntalo |
| `referencia` | no | Un identificador tuyo (el carrito). Vuelve tal cual; si no lo mandás, vuelve `null` |

Cada ítem se valoriza con su `precioUnitario` si lo trae, y si no con `listaPrecio`; se pueden
mezclar en el mismo carrito. En los dos casos **el descuento lo calcula el ERP**. Verificado: el
mismo artículo da el mismo 10% con `precioUnitario: 1000` (neto 6000, tu precio) que con la lista 2
(neto 58424,22, el precio del ERP).

---

## La respuesta

```json
{
  "fuente": "GESCOM",
  "tenant": "dyssa",
  "calculadoPor": "ERP",
  "consultadoEn": "2026-10-08T13:10:12.943071-03:00",
  "referencia": "carrito-991",
  "supuestos": [],
  "totales": { "neto": 6000, "descuento": 600, "netoConDescuento": 5400 },
  "lineas": [
    {
      "codigo": "5000014792",
      "cantidad": 6,
      "neto": 6000,
      "descuento": 10,
      "netoConDescuento": 5400,
      "creadaPorPromo": false,
      "bonificaciones": [
        {
          "id": "558",
          "nombre": "GANCIA CERO - 14792",
          "descuento": 10,
          "condiciones": [
            {
              "tipo": "CODIGO_ITEM",
              "descripcion": "La venta tiene uno o mas items",
              "valores": ["5000014792"],
              "invertida": false,
              "cantidadMinima": 1
            }
          ]
        }
      ]
    }
  ]
}
```

| Campo | Qué es |
|---|---|
| **`lineas[].descuento`** | **El porcentaje a aplicar** sobre tu neto de esa línea: `10` = 10%. `0` si no tiene descuento |
| `lineas[].codigo` | El código del artículo, siempre como texto |
| `lineas[].neto`, `lineas[].netoConDescuento` | El mismo cálculo hecho por el ERP (precio × cantidad, y menos el descuento). Con tu precio coincide con el tuyo salvo centavos de redondeo: sirve para verificar |
| `lineas[].creadaPorPromo` | `true` = **no la pidió el cliente**: la agregó una promo que regala unidades |
| `lineas[].bonificaciones` | Qué bonificación dio el descuento (`id`, `nombre`, `descuento` en %) y qué `condiciones` la dispararon. Vacía si no hay descuento |
| `totales` | La suma de las líneas. **Acá `descuento` está en pesos**, no en porcentaje |
| `supuestos` | Lista de `{ codigo, mensaje }`. Vacía es lo normal. Ver abajo |
| `calculadoPor` | `ERP`: el número lo dio el ERP de la distribuidora |
| `consultadoEn` | Cuándo se consultó al ERP (ISO 8601, con zona) |
| `fuente`, `tenant` | De dónde salió (`GESCOM`) y de qué distribuidora |

**Importes**: en pesos, sin impuestos (el servicio no suma ni saca nada) y sin redondear (el ERP
devuelve hasta seis decimales). El redondeo para mostrar es tuyo.

**Condiciones** (`bonificaciones[].condiciones[]`): `tipo` es uno de `CODIGO_CLIENTE`,
`TAG_CLIENTE`, `SUBRAMO_CLIENTE`, `CODIGO_ITEM`, `MARCA_ARTICULO`, `PROVEEDOR_ARTICULO`,
`LINEA_ARTICULO`, `RUBRO_ITEM`, `FAMILIA_ARTICULO`, `CALIBRE_ARTICULO`, `TAG_ITEM`,
`LISTA_PRECIO_VENTA`, o `DESCONOCIDA` si el ERP agrega un tipo nuevo. `valores` son los códigos a
los que aplica; `invertida: true` = se cumple cuando **no** está en esos valores; `cantidadMinima`
es `null` si no pide mínimo. `LISTA_PRECIO_VENTA` **no depende de la lista que mandes**
(verificado: aplica igual sin lista o con otra); todo indica que mira la lista que el cliente tiene
asignada en el ERP.

**Sobre mostrarle la traza al cliente**: los nombres y descripciones los carga la distribuidora en
su ERP y suelen ser internos (`"MATARAZZO + TERRABUSI - TRADE -"`, `"La venta tiene uno o mas
items"`). Sirven para rastrear un descuento; antes de mostrarlos al cliente, miralos con datos
reales.

### `supuestos`

| `codigo` | Cuándo | Qué hacer |
|---|---|---|
| `SIN_PRECIO_NI_LISTA` | Algún ítem no trajo precio y el pedido no trajo lista: el ERP usó la lista del cliente. El `mensaje` dice cuáles | Mandá el precio de esos ítems |
| `PRECIO_Y_LISTA_JUNTOS` | Mandaste lista, pero todos los ítems traen precio: la lista no se usó | Sacá `listaPrecio`, no hace falta |

### Líneas que vos no pediste (regalos)

Algunas promos **regalan unidades**. La respuesta trae entonces una línea de más, con
`creadaPorPromo: true`. Ejemplo real (`ejemplos/05`), carrito de dos artículos:

```
1331001095   cantidad 1   neto 10362.87   descuento 100%   creadaPorPromo true    ← el regalo
1331001095   cantidad 5   neto 500        descuento 0%     creadaPorPromo false   ← lo pedido
5000014792   cantidad 6   neto 6000       descuento 10%    creadaPorPromo false   ← lo pedido
```

- **El orden no es el del carrito** (se mandó `5000014792` primero).
- **La línea regalada trae el precio del ERP** (10362,87), no el tuyo: nunca le pusiste precio a algo
  que no se pidió. Por eso `totales.neto` y `totales.descuento` no sirven para un "te ahorraste $X":
  sumá el descuento de las líneas pedidas y mostrá los regalos aparte.
- **Qué hace la tienda con un regalo** (si se agrega al pedido, cómo se entrega, si va a MotorFiscal)
  lo define el negocio, no este servicio: preguntalo.

---

## Clientes que no están en el ERP

El ERP necesita el cliente para calcular. Si no lo tiene, la respuesta es **400
`CLIENTE_INEXISTENTE`**: el cliente compra sin descuento, con tu precio.

## Errores

Siempre con la misma forma, con un **código** además del status HTTP. Decidí por `codigo`, no por
el texto:

```json
{ "codigo": "CLIENTE_INEXISTENTE", "mensaje": "…", "crudo": "…" }
```

`conDescuentos` (en `bonificaciones.js`) **no corta la venta con ninguno**: devuelve el carrito con
0% y el motivo. La última columna dice si además hay algo para corregir.

| Código | HTTP | Qué significa | ¿Hay que corregir algo? |
|---|---|---|---|
| `NO_AUTORIZADO` | 401 | Falta `x-api-key`, es inválida, o es de otra distribuidora | **Sí**: la configuración de la clave |
| `PEDIDO_INVALIDO` | 400 | Falta un dato, o una cantidad o un precio es ≤ 0. No se consultó al ERP | **Sí**: lo que se manda |
| `CREDENCIALES_INVALIDAS` | 502 | Falló la conexión del servicio con el ERP | **Sí**, del lado del servicio: avisá |
| `CLIENTE_INEXISTENTE` | 400 | El cliente no está en el ERP | No: le toca 0% |
| `PEDIDO_RECHAZADO_POR_LA_FUENTE` | 400 | El ERP rechazó el pedido, p. ej. un artículo que no conoce. `mensaje` dice qué | No, pero conviene revisarlo si se repite |
| `DEMASIADOS_INTENTOS` | 429 | Tu clave es inválida y hubo muchos intentos fallidos para esa distribuidora en 5 minutos. Una clave válida nunca recibe esto | **Sí**: la configuración de la clave |
| `FUENTE_NO_DISPONIBLE` | 503 | El ERP no respondió (el servicio ya reintentó una vez) | No |
| `FUENTE_ERROR_DESCONOCIDO` | 502 | El ERP falló sin decir por qué. `crudo` trae su respuesta | No |
| `RESPUESTA_INCOHERENTE` | 502 | El ERP devolvió números que no cierran: no se pasa un precio que no cuadra | No, pero avisá si se repite |

Cualquier otro código, o un 5xx sin cuerpo: el servicio no está disponible.

## Autenticación

Header **`x-api-key`** con la clave de la distribuidora. La clave **solo sirve para valorizar** y
solo para su distribuidora: con la de `dyssa`, `/v1/senderolaser/...` da 401.

Se llama desde el navegador, así que la clave queda visible en el DevTools. Está asumido: por eso
solo puede valorizar. Igual no va escrita en el código: sale de la configuración de la tienda.
Si hace falta cambiarla, quien administra el servicio genera otra y la anterior deja de andar al
instante: pedí que te avisen antes.

## Lo que este servicio no hace

- **No crea ni confirma pedidos.** Es solo lectura: se puede consultar las veces que haga falta.
- **No da de alta clientes ni pone precios.**
- **No calcula impuestos.** Eso es MotorFiscal, con tu neto con descuento como base.

## Para probar

Con la colección de Postman de la entrega, o:

```bash
curl -X POST "https://<dominio-de-la-tienda>/api/bonificaciones/v1/dyssa/valorizaciones" \
  -H "x-api-key: <la clave>" -H "Content-Type: application/json" \
  -d '{"cliente":"8380","items":[{"codigo":"5000014792","cantidad":6,"precioUnitario":1000}]}'
```

`GET {URL}/health` no pide clave y dice si el servicio está arriba y qué versión corre.
