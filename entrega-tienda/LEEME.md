# Entrega para el lado de la tienda — api-bonificaciones

**Esta carpeta es autocontenida: se pasa completa y alcanza para implementar el checkout.** Está
escrita para quien (o lo que) trabaja en `pwa-tienda`, no para quien hizo el gateway.

```
LEEME.md          ← esto. Empezá acá.
contrato.md       ← la referencia completa del contrato
bonificaciones.js ← módulo listo para pegar en js/, en el estilo de js/api.js
ApiBonificaciones.postman_collection.json
ejemplos/         ← pedidos y respuestas REALES, capturados contra GESCOM
```

---

## En una frase

Le mandás el carrito y te dice **qué descuento le corresponde a cada línea y por qué**. El número
lo calcula el ERP de la distribuidora (GESCOM), no este servicio ni la tienda.

En el checkout va **antes** de MotorFiscal:

```
carrito  →  bonificaciones  →  neto con descuento  →  MotorFiscal  →  IVA y percepciones
```

**El precio lo pone la tienda.** Eso se decidió el 2026-10-08: mandás tu precio (o una lista del
ERP) y el gateway devuelve el descuento y la traza. No reemplaza tu catálogo ni tu precio.

---

## Las cinco reglas

1. **El precio es tuyo.** Mandá `items[].precioUnitario` (**por unidad**, no total de línea) o
   `listaPrecio`. Si mandás precio, el ERP valoriza con él y **sigue aplicando los mismos
   descuentos**.
2. **El número a aplicar es `lineas[].descuento`**, en **porcentaje** (`10` = 10%). **No sumes**
   `bonificaciones[].descuento` — eso es el detalle de quién otorgó qué, y una línea puede tener
   más de una.
3. **La traza es `lineas[].bonificaciones`**: qué bonificación lo otorgó y qué condiciones la
   dispararon. Es lo que le mostrás al cliente.
4. **Mandá el carrito entero**, en una llamada. Los criterios se evalúan sobre toda la venta: hay
   bonificaciones que piden cantidad mínima y otras que miran marca o rubro. Ítem por ítem **da
   descuentos distintos**. No es optimización, es corrección.
5. **Podés recibir más líneas que las que mandaste.** Las promos que regalan unidades agregan una
   línea con `creadaPorPromo: true`. Ver `ejemplos/05-con-linea-regalada-respuesta.json` y la
   advertencia de abajo, que es la única trampa de esta entrega.

Y un aviso: **si `supuestos` no viene vacío, leelo.** Es lo que el gateway resolvió porque el
pedido no lo traía — casi siempre un dato que la tienda debería haber mandado.

### ⚠️ La trampa de la línea regalada (verificado, mirá el ejemplo 05)

Cuando una promo regala unidades, la línea regalada viene con **el precio del ERP en su `neto`,
NO tu precio** — aunque hayas mandado `precioUnitario`. Es coherente: vos no le pusiste precio a
algo que no pediste. Lo verificamos con `precioUnitario: 100`:

```
cant 1   neto 10362.87   descuento 100%   ← regalada: el 10362.87 es del ERP, NO tu precio
cant 5   neto 500        descuento 0%     ← la que pediste: 5 × tu precio de 100
```

Dos consecuencias para la tienda:

- **`netoConDescuento` siempre cierra en 0 en la línea regalada**, así que `totales.netoConDescuento`
  —lo que cobrás— sale bien. No tenés que hacer nada para eso.
- **Pero `totales.neto` mezcla tu precio con el del ERP** (acá `10862.87` = tus 500 + los 10362.87
  del ERP). Si mostrás "te ahorraste $X" calculándolo desde ese `neto`, el número sale inflado.

El gateway **no puede** calcular el ahorro de un regalo: nunca vio tu precio para una unidad que
el cliente no pidió. Lo que sí puede, y lo hace el helper `ahorro(respuesta)` de
`bonificaciones.js`, es partirlo:

- `porDescuento`: la plata que descontaron las bonificaciones sobre lo que pediste. Exacto.
- `regalos`: los artículos regalados, con cantidad y **sin importe**. Si querés sumarlos al cartel,
  ponés vos tu precio × cantidad.

Regla práctica: para **cobrar** usá `totales.netoConDescuento`. Para el **cartel de ahorro** usá
`ahorro(respuesta)` y no el `neto` de una línea con `creadaPorPromo: true`.

---

## Lo primero que te va a faltar

| Qué | Cómo se consigue |
|---|---|
| **La api-key de la distribuidora** | Se genera desde el panel del gateway y **se muestra una sola vez**. Pedila. No está en esta carpeta a propósito. |
| **La URL de producción** | Planificada: `https://tienda.axumweb.com/api/bonificaciones`, al lado de `/api/impuestos`. **Confirmala antes de hardcodearla**: todavía no está deployado. |

Para desarrollar mientras tanto: el gateway corre en **`http://localhost:8081`** en la máquina de
quien lo levanta (`.\arrancar.ps1` en `C:\Dev\api-bonificaciones`). Ahí ya están cargadas dyssa y
senderolaser con credenciales reales, así que las respuestas son de GESCOM de verdad.

---

## Cómo arrancar

1. Leé las cinco reglas de arriba y mirá `ejemplos/01-con-precio-propio-*.json`. Son dos archivos:
   lo que se manda y lo que vuelve, reales.
2. Pegá `bonificaciones.js` en `js/`. Usa el mismo patrón `IS_PROD` que `js/api.js`, así que en
   producción la llamada es **same-origin** y no hay CORS que configurar.
3. En el checkout, usá `conDescuentos(...)` y no `valorizar(...)` directo. La diferencia está
   explicada en el archivo: `conDescuentos` **no voltea la compra** si el gateway o el ERP no
   responden, devuelve el carrito sin descuento y te avisa en `huboError`.

   **Esa decisión es tuya, no mía.** Yo elegí "vender sin descuento antes que no vender", porque
   el número definitivo lo fija el ERP cuando el pedido se confirma. Si para el negocio es
   inaceptable, cambialo por un error visible — lo que no conviene es quedarse a mitad de camino.
4. Importá la colección de Postman si querés probar a mano. Las variables a completar son
   `baseUrl`, `tenant` y `apiKey`.

---

## Los ejemplos

Todos capturados en vivo contra `dyssa.gescom.online` el 2026-10-08, pasando por el gateway.

| Archivo | Qué muestra |
|---|---|
| `01-con-precio-propio` | El caso normal: mandás tu precio, el ERP devuelve 10% |
| `02-con-lista` | Lo mismo pero valorizando con la lista 2 del ERP |
| `03-sin-precio-ni-lista` | Funciona, pero trae `SIN_PRECIO_NI_LISTA` en `supuestos` nombrando los ítems |
| `04-precio-y-lista-juntos` | Gana el precio. Trae `PRECIO_Y_LISTA_JUNTOS` |
| `05-con-linea-regalada` | **Mirá este.** Dos líneas del mismo artículo: una al 100% con `creadaPorPromo: true` |
| `06-carrito-mixto` | Un ítem con precio y otro sin. Es válido |
| `07-cliente-inexistente` | 400 `CLIENTE_INEXISTENTE`. Ver abajo |
| `08-pedido-invalido` | 400 `PEDIDO_INVALIDO`, sin llegar a consultar al ERP |

### Clientes que no están en el ERP

`eval-pedido` exige el cliente, así que para uno que no está en GESCOM **no podemos decir qué
descuento le toca**. Pero no te bloquea: como el precio lo pones vos, recibís
`CLIENTE_INEXISTENTE`, aplicás 0% y mostrás tu precio de lista. Nada que construir de ninguno de
los dos lados.

---

## Errores: ramificá por `codigo`, no por el texto

Todos vienen como `{codigo, mensaje, crudo}`. El `mensaje` es para humanos y puede cambiar.

| Código | HTTP | ¿Reintentar? |
|---|---|---|
| `NO_AUTORIZADO` | 401 | No — falta la clave, es inválida, o no es de esa distribuidora |
| `PEDIDO_INVALIDO` | 400 | No, corregilo. No se consultó al ERP |
| `CLIENTE_INEXISTENTE` | 400 | No — manejalo como "sin descuento" |
| `PEDIDO_RECHAZADO_POR_LA_FUENTE` | 400 | No. `mensaje` trae lo que dijo el ERP (p. ej. un artículo que no existe) |
| `FUENTE_NO_DISPONIBLE` | 503 | **Sí**, el ERP no respondió. El gateway ya reintentó una vez por su cuenta |
| `RESPUESTA_INCOHERENTE` | 502 | No. El ERP devolvió números que no cierran y **preferimos no pasarte un precio que no cuadra** |

La tabla completa está en `contrato.md`.

---

## Cosas que conviene saber antes de que te sorprendan

- **Una valorización tarda 1,7 a 2,0 segundos**, medido con todo cacheado. Es casi todo el ERP, no
  el gateway. Tenelo en cuenta en el checkout: conviene un indicador de carga y no bloquear la
  pantalla.
- **Los importes vienen sin redondear** (el ERP da hasta 6 decimales). El redondeo para mostrar es
  decisión tuya.
- **Si aplicás el porcentaje sobre tu neto, tu total va a diferir del nuestro por centavos.** Es
  esperable: el definitivo lo fija el ERP al confirmar el pedido.
- **La lista de precio no cambia el porcentaje**, solo el precio del ERP. Verificado: el mismo
  artículo con lista 2 y lista 3 da el mismo descuento y distinto neto. Así que si mandás tu
  precio, la lista no te cambia el descuento.

---

## Lo que NO está verificado

Dicho explícitamente para que no se tome como garantía:

- **`precioUnitario` con `unidadFactor` ≠ 1** (bultos): no se sabe si el precio es por unidad de
  venta o se multiplica por el factor. No se pudo probar porque el ERP valida la unidad contra el
  artículo. **Si la tienda va a mandar bultos, avisá antes de implementarlo.**
- **Cómo se componen dos bonificaciones en la misma línea** (¿suma o compuesto?). Por eso la regla
  2: usá el total de la línea y no sumes el detalle.
- **Nada está deployado todavía.** El gateway corre en una máquina de desarrollo. La URL de
  producción es un plan, no un hecho.

---

## Si necesitás algo que no está acá

Lo que falte se pide del lado del gateway (`C:\Dev\api-bonificaciones`). Dos cosas que ya sabemos
que podrían hacer falta y **no están**, para no inventarlas:

- Un campo que diga **de qué promo** salió cada línea regalada (hoy sabés que es un regalo y qué
  artículo, pero no qué bonificación lo generó).
- Un endpoint para **simular sin cliente** (precio de lista público, sin cuenta).

Si aparece alguna, se agrega como **campo opcional nuevo**: la regla del gateway es que la API
nunca rompe al cliente viejo.
