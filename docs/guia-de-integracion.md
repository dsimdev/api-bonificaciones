# API Bonificaciones — guía de integración

Para quien implementa el checkout de la tienda.

Este servicio dice **qué descuento le corresponde a un pedido**. El número lo da el ERP de la
distribuidora, no nosotros: somos el intermediario que resuelve la autenticación, normaliza la
respuesta y explica de dónde salió cada descuento.

En el checkout se llama **antes** que MotorFiscal: nosotros devolvemos el neto con descuento
comercial, y ese neto es la base sobre la que MotorFiscal calcula los impuestos.

```
carrito  →  api-bonificaciones  →  neto con descuento  →  MotorFiscal  →  IVA y percepciones
```

---

## Valorizar un pedido

```
POST /v1/{tenant}/valorizaciones
Content-Type: application/json
```

`{tenant}` es el código de la distribuidora (`dyssa`, `senderolaser`, …), igual que en el gateway
de Axum y en MotorFiscal.

### Lo que mandás

```json
{
  "cliente": "8380",
  "listaPrecio": "2",
  "referencia": "carrito-991",
  "items": [
    { "codigo": "5000014792", "cantidad": 6, "unidad": "Unidad", "unidadFactor": 1 }
  ]
}
```

| Campo | ¿Obligatorio? | Qué es |
|---|---|---|
| `cliente` | **sí** | El código de cliente **del ERP**, el mismo que usa GESCOM. No hay traducción. **Tiene que existir en el ERP** — ver "Clientes que no están en el ERP". |
| `listaPrecio` | no, pero **mandala** | Ver la advertencia de abajo: **cambia el precio**. |
| `referencia` | no | Un identificador tuyo (el carrito, el pedido). Te lo devolvemos tal cual, para poder rastrear después qué te respondimos. |
| `items[].codigo` | **sí** | Código del artículo. |
| `items[].cantidad` | **sí** | Mayor que cero. |
| `items[].unidad` | no | Por defecto `"Unidad"`. |
| `items[].unidadFactor` | no | Por defecto `1`. Es la relación unidad/bulto. |

> ### ⚠️ Mandá `listaPrecio`, y mandá la misma que usaste para mostrar el precio
>
> **La lista cambia el precio.** Verificado contra el ERP: el mismo artículo, la misma cantidad y
> el mismo cliente dan neto **63.175** en la lista 2 y **59.976** en la lista 3.
>
> Si no la mandás, el ERP usa la que tiene asignada el cliente. No falla, pero **el neto que te
> devolvemos puede no coincidir con el que le mostraste al cliente en el carrito**. Y el
> **porcentaje de descuento sale igual en los dos casos**, así que el error no se ve mirando el
> descuento — solo el importe.
>
> Cuando no la mandás, la respuesta trae un aviso en `supuestos`. No lo ignores.

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
| `calculadoPor` | `ERP` = el número lo dio el ERP de la distribuidora. Hoy siempre es `ERP`. Si algún día dice `GATEWAY`, el número lo calculamos nosotros y **no vale lo mismo frente a un reclamo**. |
| `supuestos` | Lo que resolvimos nosotros porque no vino en el pedido. **Vacío es lo normal.** Si trae algo, leelo. |
| `totales` | La suma de las líneas, ya hecha, para que no diverjas por redondeo. `descuento` está **en pesos**, no en porcentaje. |
| `lineas[].descuento` | **PORCENTAJE**: `10` significa 10%. (Ojo: Axum usa la convención opuesta en percepciones.) |
| `lineas[].creadaPorPromo` | `true` = **esta línea no la pidió el cliente, se la regaló una promo**. Ver abajo. |
| `lineas[].bonificaciones` | Qué bonificación otorgó el descuento, con **las condiciones que la dispararon**. Sirve para mostrarle al cliente por qué ganó el descuento. |

### Los importes vienen sin redondear

El ERP devuelve hasta seis decimales (`52581.798`). Te los pasamos tal cual: **el redondeo para
mostrar es decisión tuya**, y así no perdemos precisión en el camino. `totales` es la suma de las
líneas, también sin redondear.

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
que no está en GESCOM no podemos devolver nada** — ni descuento ni precio.

Si en tu flujo puede haber clientes que no vengan del ERP, **hablemos antes de que lo implementes**.
La salida depende de algo que solo vos sabés: **¿usás nuestro `neto` como precio del carrito, o ya
tenés el precio y solo nos pedís el descuento?**

- Si ya tenés el precio: un cliente nuevo es trivial. Recibís `CLIENTE_INEXISTENTE`, aplicás 0% de
  descuento y mostrás precio de lista. No hay nada que construir de ninguno de los dos lados.
- Si usás nuestro `neto`: ahí sí hay que resolverlo, y hay opciones (que el alta pase primero por
  el ERP, o un cliente genérico por distribuidora). Ninguna es gratis.

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
| `PEDIDO_INVALIDO` | 400 | El pedido no pasa nuestras validaciones (sin ítems, cantidad ≤ 0). **No se consulta al ERP.** | No, corregilo |
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

**Todavía no está implementada.** Va a ser un header `x-api-key`, una clave por distribuidora, y
vas a recibir la tuya. La clave va a estar atada al tenant: con la de `dyssa` no vas a poder pedir
`/v1/senderolaser/...`.

Dos cosas a tener en cuenta cuando llegue:

- Si llamás **desde el navegador**, esa clave queda a la vista de cualquiera que abra el DevTools.
  Lo asumimos y limitamos el daño: la clave del checkout **solo va a poder valorizar**.
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
curl -X POST http://localhost:8080/v1/dyssa/valorizaciones \
  -H 'Content-Type: application/json' \
  -d '{"cliente":"8380","listaPrecio":"2","items":[{"codigo":"5000014792","cantidad":6}]}'
```

La documentación interactiva está en `/swagger-ui.html`, y `/health` dice qué versión corre y qué
distribuidoras están configuradas.
