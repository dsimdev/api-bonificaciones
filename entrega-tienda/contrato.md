# API Bonificaciones — guía de integración

> **Esta es una copia** de `api-bonificaciones/docs/guia-de-integracion.md`,
> tomada el 2026-10-08 para la entrega. El original es la version viva: si algo no coincide,
> gana el original.

Para quien implementa el checkout de la tienda.

Este servicio dice **qué descuento le corresponde a un pedido**, y **por qué**. El número lo da el
ERP de la distribuidora, no nosotros: somos el intermediario que resuelve la autenticación,
normaliza la respuesta y explica de dónde salió cada descuento.

En el checkout se llama **antes** que MotorFiscal: el neto con descuento comercial es la base sobre
la que MotorFiscal calcula los impuestos.

```
carrito  →  api-bonificaciones  →  neto con descuento  →  MotorFiscal  →  IVA y percepciones
```

## Las cinco reglas, en media página

1. **El precio lo ponés vos.** Mandá `items[].precioUnitario` (por unidad) o `listaPrecio`, y el
   ERP valoriza con eso. Si mandás precio, el ERP lo usa y **sigue aplicando los mismos
   descuentos**.
2. **El número a aplicar es `lineas[].descuento`**, en **porcentaje** (`10` = 10%). **No sumes**
   `bonificaciones[].descuento`: eso es el detalle de quién otorgó qué, el total de la línea es el
   que vale.
3. **La traza es `lineas[].bonificaciones`**: qué bonificación lo otorgó y qué condiciones la
   dispararon. Es lo que le mostrás al cliente ("20% por llevar 3 o más").
4. **Mandá el carrito entero**, en una llamada. Los criterios se evalúan sobre toda la venta.
5. **Podés recibir más líneas que las que mandaste.** Las promos que regalan unidades agregan una
   línea con `creadaPorPromo: true`.

Y una más que no es una regla sino un aviso: **si `supuestos` no viene vacío, leelo.** Es lo que
resolvimos nosotros porque el pedido no lo traía.

---

## Valorizar un pedido

```
POST /v1/{tenant}/valorizaciones
x-api-key: bon_...        ← la clave de TU distribuidora
Content-Type: application/json
```

`{tenant}` es el código de la distribuidora (`dyssa`, `senderolaser`, …), igual que en el gateway
de Axum y en MotorFiscal.

### Lo que mandás

```json
{
  "cliente": "8380",
  "referencia": "carrito-991",
  "items": [
    { "codigo": "5000014792", "cantidad": 6, "precioUnitario": 9737.37 }
  ]
}
```

| Campo | ¿Obligatorio? | Qué es |
|---|---|---|
| `cliente` | **sí** | El código de cliente **del ERP**, el mismo que usa GESCOM. No hay traducción. |
| `items[].codigo` | **sí** | Código del artículo. |
| `items[].cantidad` | **sí** | Mayor que cero. |
| `items[].precioUnitario` | ver abajo | **Tu** precio, **por unidad** (no el total de la línea). Si lo mandás, el ERP valoriza con él. |
| `listaPrecio` | ver abajo | La lista del ERP con la que valorizar los ítems que no traen `precioUnitario`. |
| `referencia` | no | Un identificador tuyo (el carrito, el pedido). Te lo devolvemos tal cual, para poder rastrear después qué te respondimos. |
| `items[].unidad` | no | Por defecto `"Unidad"`. |
| `items[].unidadFactor` | no | Por defecto `1`. Es la relación unidad/bulto. |

> ### Precio **o** lista: mandá uno de los dos
>
> Cada ítem se valoriza con su `precioUnitario` si lo trae, y si no con `listaPrecio`. Las dos
> formas andan y en las dos **el descuento lo calcula el ERP** (`calculadoPor` sigue diciendo
> `ERP`).
>
> Verificado contra el ERP el 2026-10-08, mismo artículo y cantidad 6:
>
> | Lo que mandás | Neto que sale | Descuento |
> |---|---|---|
> | `precioUnitario: 1000` | **6.000** (= 1000 × 6, tu precio) | 10% |
> | `listaPrecio: "2"` | 58.424,22 (precio del ERP) | 10% |
> | los dos juntos | **6.000** — gana el precio | 10% |
> | ninguno | 58.424,22, con la lista del cliente | 10% |
>
> **Un carrito mixto es válido**: algunos ítems con precio y otros sin. El día que tengas un
> artículo sin precio cargado no queremos rechazarte el carrito entero.
>
> **Si no mandás ninguno de los dos**, el ERP usa la lista que tiene asignada el cliente. No falla,
> pero el neto puede no coincidir con el que mostraste, así que la respuesta trae
> `SIN_PRECIO_NI_LISTA` en `supuestos` **nombrando los ítems** que quedaron así.
>
> **Si mandás los dos**, gana el precio para el importe y te avisamos con `PRECIO_Y_LISTA_JUNTOS`.
> La lista igual se envía, porque hay criterios condicionados a la lista de precio.

> ### Mandá el carrito **entero**, no un ítem por llamada
>
> Los criterios se evalúan sobre **toda la venta**: hay bonificaciones que piden una cantidad
> mínima, y otras que miran si el pedido tiene artículos de cierta marca o rubro. Preguntar ítem
> por ítem **da descuentos distintos**. No es una optimización, es corrección.

### Lo que te devolvemos

```json
{
  "fuente": "GESCOM",
  "tenant": "dyssa",
  "calculadoPor": "ERP",
  "consultadoEn": "2026-10-07T12:34:57.242-03:00",
  "referencia": "carrito-991",
  "supuestos": [],
  "totales": {
    "neto": 58424.22,
    "descuento": 5842.422,
    "netoConDescuento": 52581.798
  },
  "lineas": [
    {
      "codigo": "5000014792",
      "cantidad": 6,
      "neto": 58424.22,
      "descuento": 10,
      "netoConDescuento": 52581.798,
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
| **`lineas[].descuento`** | **El número que aplicás.** PORCENTAJE: `10` significa 10%. (Ojo: Axum usa la convención opuesta en percepciones.) |
| **`lineas[].bonificaciones`** | **La traza.** Qué bonificación otorgó el descuento, con **las condiciones que la dispararon**. Es lo que le mostrás al cliente para explicarle por qué ganó el descuento. |
| `calculadoPor` | `ERP` = el número lo dio el ERP de la distribuidora. Hoy siempre es `ERP`, **también cuando mandás tu propio precio**. Si algún día dice `GATEWAY`, el número lo calculamos nosotros y **no vale lo mismo frente a un reclamo**. |
| `supuestos` | Lo que resolvimos nosotros porque no vino en el pedido. **Vacío es lo normal.** Si trae algo, leelo. |
| `neto` / `netoConDescuento` | Lo que calculó el ERP con el precio que se usó. Si mandaste `precioUnitario`, `neto` es tu precio × cantidad: sirve para **verificar con qué precio valorizamos**. |
| `totales` | La suma de las líneas, ya hecha. `descuento` está **en pesos**, no en porcentaje. |
| `lineas[].creadaPorPromo` | `true` = **esta línea no la pidió el cliente, se la regaló una promo**. Ver abajo. |

### ⚠️ No sumes los descuentos de `bonificaciones`

El número a aplicar es **`lineas[].descuento`**, el total de la línea que calculó el ERP.
`bonificaciones[]` es el **detalle de quién otorgó qué**, y una línea puede tener más de una.

No sumes ese detalle para obtener el total: si dos bonificaciones se componen, el ERP ya resolvió
cómo, y no tenemos verificado que la composición sea una suma simple. El total de la línea es el
único número con autoridad.

### Los importes vienen sin redondear

El ERP devuelve hasta seis decimales (`52581.798`). Te los pasamos tal cual: **el redondeo para
mostrar es decisión tuya**, y así no perdemos precisión en el camino. `totales` es la suma de las
líneas, también sin redondear.

Si aplicás el porcentaje sobre tu propio neto, tu total va a diferir del nuestro por centavos. Es
esperable y no es un error: el número definitivo lo fija el ERP cuando el pedido se confirma.

### Líneas que vos no pediste

Algunas bonificaciones **regalan unidades** ("lleva 5, paga 4", combos). Cuando eso pasa, la
respuesta trae **una línea de más** con `creadaPorPromo: true`:

```
1331001095   cantidad 1   descuento 100%   creadaPorPromo true    ← la regalada
1331001095   cantidad 5   descuento 0%     creadaPorPromo false   ← la que pediste
5000014792   cantidad 6   descuento 10%    creadaPorPromo false
```

**Tenés que contemplarlo**: la cantidad de líneas que devolvemos puede ser mayor que la que
mandaste. Mostralas como regalo, no como un ítem más del carrito.

---

## Clientes que no están en el ERP

`eval-pedido` exige el cliente y rechaza el pedido entero si no existe, así que **para un cliente
que no está en GESCOM no podemos decir qué descuento le toca**.

**Pero no te bloquea**, y eso quedó resuelto el 2026-10-08: como el precio lo pones vos, un cliente
que no está en el ERP se maneja del todo de tu lado. Recibís **400 `CLIENTE_INEXISTENTE`**, aplicás
0% de descuento y mostrás tu precio de lista. No hay nada que construir de ninguno de los dos
lados.

---

## Errores

Siempre con la misma forma, y **con un código de dominio además del status HTTP**, para que puedas
decidir sin leer el texto:

```json
{
  "codigo": "CLIENTE_INEXISTENTE",
  "mensaje": "El campo CodigoCliente tiene un codigo invalido",
  "crudo": "{\"errorCode\":\"0\",\"message\":\"...\"}"
}
```

| Código | HTTP | Qué significa | ¿Reintentar? |
|---|---|---|---|
| `NO_AUTORIZADO` | 401 | Falta `x-api-key`, es inválida, o **no es de esa distribuidora** | No |
| `ALCANCE_INSUFICIENTE` | 403 | Tu clave no alcanza para eso (p. ej. `/criterios` con la del checkout) | No |
| `DEMASIADOS_INTENTOS` | 429 | Demasiados intentos fallidos de autenticación para esa distribuidora | Sí, en unos minutos |
| `PEDIDO_INVALIDO` | 400 | El pedido no pasa nuestras validaciones (sin ítems, cantidad ≤ 0, `precioUnitario` ≤ 0). **No se consulta al ERP.** | No, corregilo |
| `CLIENTE_INEXISTENTE` | 400 | Ese código de cliente no existe en el ERP | No |
| `PEDIDO_RECHAZADO_POR_LA_FUENTE` | 400 | El ERP rechazó el pedido: falta un dato o uno es inválido (p. ej. un artículo que no existe). `mensaje` trae lo que dijo el ERP | No |
| `TENANT_DESCONOCIDO` | 400 | Esa distribuidora no está configurada acá | No |
| `FUENTE_NO_DISPONIBLE` | 503 | El ERP no responde o tardó demasiado | **Sí** |
| `CREDENCIALES_INVALIDAS` | 502 | Nuestras credenciales contra el ERP fallaron. Problema nuestro, avisanos | No |
| `FUENTE_ERROR_DESCONOCIDO` | 502 | El ERP falló y no dijo qué. `crudo` trae su respuesta | Una vez, con cuidado |
| `RESPUESTA_INCOHERENTE` | 502 | El ERP devolvió números que no cierran entre sí. **No te pasamos un precio que no cuadra.** Avisanos | No |

Un apunte sobre `FUENTE_ERROR_DESCONOCIDO`: el ERP tapa casi todos sus errores de runtime con un
mensaje genérico. Cuando podemos distinguir qué pasó, te damos un código preciso; cuando no, te
damos el crudo en vez de inventar.

---

## Autenticación

Va un header **`x-api-key`**, una clave por distribuidora. **Vas a recibir la tuya** cuando demos
de alta tu distribuidora; se muestra una sola vez y si se pierde se regenera (y la anterior queda
revocada al instante).

```
x-api-key: bon_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**La clave está atada a tu distribuidora**: con la de `dyssa`, pedir `/v1/senderolaser/...` da 401.
No es una convención, está verificado con un test.

**Tu clave solo puede valorizar.** `GET /criterios` —el catálogo completo de bonificaciones— pide
una clave de alcance `ADMIN` y devuelve **403** con la del checkout. Es a propósito, y el motivo es
el de abajo.

Dos cosas a tener en cuenta:

- Si llamás **desde el navegador**, esa clave queda a la vista de cualquiera que abra el DevTools.
  Lo asumimos y limitamos el daño: por eso la clave del checkout **solo valoriza** y no puede leer
  el catálogo.
- Para cerrarlo del todo haría falta que **tu backend emita un token corto** que diga "este
  navegador es el cliente 8380". Si lo podés hacer, hablemos: es la diferencia entre que alguien
  pueda consultar los precios de **cualquier** cliente de la distribuidora o solo los suyos.

### CORS

Hoy no hace falta configurarlo porque el servicio queda **en el mismo origen** que la tienda, igual
que MotorFiscal. **Si en algún momento se monta en otro dominio, avisanos**: ahí sí hay que
habilitar CORS explícitamente y es un cambio nuestro.

---

## Lo que este servicio **no** hace

- **No crea ni confirma pedidos.** Es solo lectura, un *dry-run*: consultás las veces que quieras,
  no pasa nada del otro lado.
- **No es el precio final.** El número definitivo lo fija el ERP cuando el pedido se confirma de
  verdad. Nosotros te decimos qué descuento corresponde hoy, con el catálogo de hoy.
- **No calcula impuestos.** Eso es MotorFiscal, con nuestro neto como base.

---

## Para probar

```bash
curl -X POST http://localhost:8081/v1/dyssa/valorizaciones \
  -H "x-api-key: bon_tuClaveAca" \
  -H "Content-Type: application/json" \
  -d '{"cliente":"8380","items":[{"codigo":"5000014792","cantidad":6,"precioUnitario":9737.37}]}'
```

La documentación interactiva está en `/swagger-ui.html`, y `/health` dice qué versión corre y qué
distribuidoras están configuradas.
