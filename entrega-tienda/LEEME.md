# Descuentos en el checkout — entrega para la tienda

Para quien integra los descuentos de las distribuidoras en el checkout de la tienda.

## Qué es

Un servicio que, dado un carrito, dice **qué descuento le corresponde a cada línea y por qué**. El
número lo calcula el ERP de la distribuidora; la tienda pone el precio y aplica el porcentaje. Va
antes de MotorFiscal:

```
carrito  →  bonificaciones  →  tu neto con descuento  →  MotorFiscal  →  IVA y percepciones
```

## Qué hay en esta carpeta

| Archivo | Qué es |
|---|---|
| `LEEME.md` | Esto: por dónde empezar |
| `contrato.md` | **La referencia**: reglas, pedido, respuesta, errores. Empezá por "Las reglas" |
| `bonificaciones.js` | Módulo listo para copiar a `js/`. Hace la llamada y maneja los errores |
| `ApiBonificaciones.postman_collection.json` | Los pedidos, para probar a mano |
| `ejemplos/` | Pedidos y respuestas **reales** del servicio |

## Lo que te van a pasar

El servicio **todavía no está instalado** en el servidor. Cuando lo esté, quien te pasó esta carpeta
te manda el **código de la distribuidora** (por ejemplo `dyssa`), su **clave** y la **URL** para
probar con Postman.

**La llamada va server-side**, no desde el browser. Si se hiciera client-side, la clave y el
descuento quedarían en DevTools y el cliente podría manipularlos. Desde el servidor de la tienda, la
clave no se expone y al browser solo le llega el precio final. El módulo `bonificaciones.js` funciona
igual en Node 18+ (usa `fetch` estándar); en el servidor, la `BASE` se configura con la URL interna
del servicio en vez de una ruta relativa.

## Cómo integrarlo

**La llamada se hace desde el server de la tienda** (Node 18+), no desde el browser. El módulo usa
`fetch` estándar y funciona igual en Node.

1. Copiá `bonificaciones.js` al server de la tienda.
2. Configurá la `BASE` con la URL interna del servicio (por ejemplo
   `http://localhost:8081` o la que te pasen) y registrá la clave de cada distribuidora:
   ```js
   import { configurarBonificaciones } from './bonificaciones.js';
   configurarBonificaciones({ tenant: 'dyssa', clave: process.env.CLAVE_BONIFICACIONES });
   ```
3. En el checkout, con el carrito **entero**, los códigos que ya usa la tienda y tu precio **por
   unidad y sin impuestos**:
   ```js
   import { conDescuentos, lineaDelItem, aplicarDescuento, regalos } from './bonificaciones.js';

   const r = await conDescuentos('dyssa', cliente.codigoErp,
     carrito.map((i) => ({ codigo: i.codigo, cantidad: i.cantidad, precioUnitario: i.precio })));

   for (const item of carrito) {
     const linea = lineaDelItem(r, item.codigo);          // por código: el orden no es el del carrito
     item.descuento = linea ? linea.descuento : 0;        // porcentaje, para mostrar
     item.netoConDescuento = aplicarDescuento(item.precio * item.cantidad, linea); // base para MotorFiscal
   }
   const regalados = regalos(r);   // unidades que regaló una promo: se muestran, no se cobran
                                   // y no van a MotorFiscal
   ```
4. Devolvé al browser **solo el precio final**, no el porcentaje ni la traza.
5. `conDescuentos` **nunca corta la venta**. Si no se puede saber el descuento (el servicio no
   responde, el cliente no está en el ERP, etc.), devuelve 0% y el motivo en
   `r.motivoSinDescuento`. Si `r.motivoSinDescuento.hayQueCorregir` es `true` (por ejemplo una
   clave inválida), además lo registra en la consola: no se arregla solo.

### Mientras el servicio no está instalado

En local, `/api/bonificaciones` no existe. Para desarrollar, usá las respuestas reales de
`ejemplos/`:

```js
import { usarRespuestasDePrueba } from './bonificaciones.js';
// el JSON de ejemplo copiado donde tu servidor local lo sirva
const respuesta = await (await fetch('/ejemplos/05-con-linea-regalada-respuesta.json')).json();
usarRespuestasDePrueba(() => respuesta);   // y usarRespuestasDePrueba(null) para volver al real
```

## Los ejemplos

Capturados del servicio contra el ERP real de `dyssa`, con el cliente `8380` (salvo el 07).

| Archivo | Qué muestra |
|---|---|
| `01-con-precio-propio` | El caso normal: precio 1000 × 6 → neto 6000, 10% |
| `02-con-lista` | Un ítem sin precio, valorizado con la lista 2 del ERP |
| `03-sin-precio-ni-lista` | Sin precio ni lista: funciona, y avisa `SIN_PRECIO_NI_LISTA` |
| `04-precio-y-lista-juntos` | Lista que no se usó: avisa `PRECIO_Y_LISTA_JUNTOS` |
| `05-con-linea-regalada` | **Mirá este**: un regalo (`creadaPorPromo: true`) y las líneas en otro orden que el carrito |
| `06-carrito-mixto` | Un ítem con precio y otro sin, con lista para el que no tiene: sin avisos |
| `07-cliente-inexistente` | 400 `CLIENTE_INEXISTENTE`: va 0% |
| `08-pedido-invalido` | 400 `PEDIDO_INVALIDO` (cantidad 0) |

## Si algo no está en el contrato

Los códigos de cliente y de artículo son los que ya usa la tienda (vienen del ERP o de Axum). Lo
demás está en `contrato.md`. Si algo no está ahí, preguntáselo a quien te pasó esta carpeta antes
de suponerlo. El servicio **no** crea pedidos, **no** da de alta clientes y **no** calcula
impuestos: si algo de la integración parece necesitarlo, es una pregunta, no algo a construir.
