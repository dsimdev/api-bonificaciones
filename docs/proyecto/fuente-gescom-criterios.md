# Fuente GESCOM — `get-promociones` (criterios de venta)

> Verificado el 2026-10-07 **contra la API en vivo de dyssa**, con credenciales reales. Antes de
> esto el conector estaba escrito contra un fixture reconstruido de la doc, y tenía un bug grueso
> (ver abajo).
>
> **El catálogo de dyssa son 70 criterios, todos activos.** Los ids llegan a 670+ pero
> `get-promociones` devuelve 70 en una sola respuesta: **no hay evidencia de paginación**, y lo que
> parecía un catálogo gigante era el límite de copiado de Postman.
>
> Un subconjunto representativo del payload real está en
> `bonif-app/src/test/resources/fixtures/get-promociones-dyssa.json`: cubre los 13 tipos de
> condición y todos los casos raros. Es el fixture de `CatalogoGescomTest`.

## Estructura de un criterio

```
criterio
├── id, nombre, descripcion, dominio, activo, orden, global
├── validoDesde / validoHasta   ISO 8601 con offset: "2023-10-30T00:00:00-03:00"
├── codigoCondicionPrincipal    ← por acá ARRANCA la evaluación
├── condiciones[]               cuándo aplica (árbol, no lista)
├── modificadores[]             qué hace
├── marcadores[]                siempre ItemQMarker con configuracionJson null
└── clientes[]                  vacío en todo el catálogo de dyssa
```

Cada condición y cada modificador tiene **dos identificadores distintos**:

- `id` — la fila global en GESCOM. No se usa para nada nuestro.
- **`codigo`** — el id **dentro** del criterio. Es lo que referencian `codigoCondicionPrincipal`,
  `conditionCodes` y `dataConditionCodes`. **Es el que importa.**

## El bug que encontró el payload real

En los **modificadores**, `descuento`, `dataConditionCodes` y `allowOverlap` **no son campos de
ese nivel**: viajan dentro de `configuracionJson`, que es un **string con JSON adentro**.

```json
{
  "codigo": 100,
  "tipo": "DescuentoItem",
  "configuracionJson": "{\"criterioOrden\":0,\"cantidadMaxima\":0,\"descuento\":0.1,\"dataConditionCodes\":[102]}"
}
```

Modelarlos como campos sueltos los dejaba en `null`, y **el descuento salía 0 para todos los
criterios del catálogo**. El fixture reconstruido no lo detectaba porque lo había inventado con la
forma equivocada. Lo fija `CatalogoGescomTest.elDescuentoSaleDelConfiguracionJsonDelModificador`.

> Es exactamente el riesgo que estaba anotado al escribir el mapeador a ciegas. La lección no es
> "el fixture estaba mal": es que **un fixture inventado valida el código contra uno mismo**.

## Las condiciones son un árbol, y hay ramas muertas

La evaluación arranca en la condición cuyo `codigo` == `codigoCondicionPrincipal` (siempre `100`
en dyssa), que es un combinador `All`/`Any`, y baja por sus `conditionCodes`.

**No todas las condiciones de la lista participan.** Hay **condiciones huérfanas**: existen en
`condiciones[]` pero ningún combinador las referencia.

| Criterio | Qué pasa |
|---|---|
| 294 NUEVO PREMIUM - BIC | define una condición de línea de artículo (código 102); el `All` solo referencia la 101 |
| 30 GRUPO 8 | define un `CodigoItem` con `inverted:true` (código 103); el `All` solo referencia 101 y 102 |

**Devolverlas como "por qué aplicó" sería mentir.** Por eso `Criterio.condicionesEnJuego()` camina
desde la raíz y `condicionesHoja()` descarta además los combinadores.

### Y el árbol tiene ciclos

En el criterio 2 (GRUPO 10), el `All` 100 referencia la 101 y la 104; y el `Any` 104 vuelve a
referenciar la 101. Un recorrido sin corte de visitados no termina. Está cubierto por
`unCicloEnElGrafoDeCondicionesNoCuelga`.

## La clave de los valores cambia según el tipo

No hay una clave genérica: cada tipo guarda sus valores con otro nombre.

| `tipo` | Clave | Ejemplo |
|---|---|---|
| `CodigoCliente` | `codigos` | `{"codigos":["1369","9965"],"inverted":false}` |
| `CodigoItem` | `codigos` | `{"greedy":true,"codigos":["5000014792"],"requiredQuantity":1}` |
| `TagCliente` | `tags` | `{"tags":["GRUPO 10"]}` |
| `TagItem` | `tags` | `{"greedy":true,"tags":["RESTO SECO"],"requiredQuantity":1}` |
| `SubRamoCliente` | `subRamoCodigos` | `{"subRamoCodigos":["pepsico-5001"]}` |
| `MarcaArticulo` | `marcas` | `{"greedy":true,"marcas":["pepsico-11"],...}` |
| `ProveedorArticulo` | `proveedores` | `{"greedy":true,"proveedores":["8531"],...}` |
| `LineaArticulo` | `lineas` | `{"greedy":true,"lineas":["dyssa-122"],...}` |
| `RubroItem` | `rubros` | `{"greedy":true,"rubros":["dyssa-02"],...}` |
| `FamiliaArticulo` | `familias` | `{"greedy":true,"familias":["dyssa-215"],...}` |
| `CalibreArticulo` | `calibres` | `{"greedy":true,"calibres":["dyssa-07"],...}` |
| `ListaPrecioVenta` | `codigos` | `{"codigos":["2"]}` — "aplica a una lista de listas de precio" |
| `All` / `Any` | `conditionCodes` | combinan otras condiciones, no tienen valores propios |

Frecuencia en los 70 criterios de dyssa: `CodigoItem` 45, `CodigoCliente` 26, `TagItem` 10,
`MarcaArticulo` 8, `ProveedorArticulo` 8, `SubRamoCliente` 4, `TagCliente` 4, `LineaArticulo` 2,
`ListaPrecioVenta` 2, `FamiliaArticulo` 1, `RubroItem` 1, `CalibreArticulo` 1. Más 70 `All` y 2
`Any` como combinadores.

⚠️ **El formato de los códigos de agrupador cambia entre distribuidoras.** En dyssa vienen
prefijados por origen (`pepsico-11`, `dyssa-122`, `dyssa-02`, y conviven dos prefijos en el mismo
catálogo); en senderolaser son números pelados (`subRamoCodigos: ["100","105","108"]`). **No
asumir un formato ni parsear el prefijo**: son identificadores opacos que solo se comparan.

### Otras claves del `configuracionJson`

| Clave | Dónde | Qué sabemos |
|---|---|---|
| `requiredQuantity` | condiciones de ítem | cantidad mínima para que dispare. Siempre `1` en dyssa |
| `inverted` | condiciones de lista | "los que **no** están en la lista". `true` en el criterio 30 |
| `greedy` | condiciones de ítem | **sin verificar**. Probablemente "tomar todos los ítems que matcheen". Se conserva en el crudo |
| `evaluateAll` | `All` / `Any` | **sin verificar**. Aparece `true`, `false`, y a veces no aparece — y hay criterios con `All` + `evaluateAll:false` (el 293 y el 295), que a primera vista se contradice |
| `criterioOrden`, `cantidadMaxima` | modificadores | **sin verificar**. Siempre `0` en dyssa |

## Varios modificadores = varios descuentos en un criterio

Así se arma el "10% en global y 5% en Pehuamar" del criterio 2: dos modificadores
`DescuentoItem`, cada uno con su `dataConditionCodes` apuntando a una condición de marca distinta.

`dataConditionCodes` vacío = el descuento cae sobre todo lo que califique.

Como `eval-pedido` nos dice **qué porcentaje** aplicó, el enriquecimiento busca el modificador que
produjo ese porcentaje y devuelve solo **sus** condiciones. Lo fija
`cadaModificadorApuntaASusPropiasCondiciones`.

## Hay TRES tipos de modificador, no uno

La doc de referencia solo mencionaba `DescuentoItem`. Recorrer el catálogo real mostró dos más — y
aparecieron **porque un tipo no reconocido queda marcado en vez de pasar como 0%**. Esa regla se
pagó sola.

### `DescuentoItem` — descuento plano

```json
{"criterioOrden":0,"cantidadMaxima":0,"descuento":0.1,"dataConditionCodes":[102]}
```

### `TablaDescuentoItem` — descuento escalonado por cantidad

```json
{"criterioOrden":0,"cantidadMaxima":0,"tabla":[[3,0.05],[45,0.12]],
 "descuentoPorPromocion":false,"dataConditionCodes":[],"descuentoPorCantidad":true}
```

`tabla` es una lista de pares `[cantidadDesde, descuentoEnFracción]`: desde 3 unidades 5%, desde 45
unidades 12%. Lo usan los criterios 610 y 611 ("MATARAZZO + TERRABUSI"), los dos con
`descuentoPorCantidad: true`. **Sin verificar** qué hace cuando está en `false`, ni qué significa
`descuentoPorPromocion`.

### `AgregaGratis` — unidades sin cargo

```json
{"criterioOrden":0,"cantidadMaxima":0,"codigoItem":"1331001095","dataConditionCodes":[],"cantidad":1}
```

Agrega N unidades de **un ítem puntual**. Son los "5+1 sin cargo" y los combos: ocho criterios en
dyssa (NOEL POTE 1KG, FRIGOR 1KG, CHOMP, los combos de BIC…).

> ⚠️ **Esto hace que `eval-pedido` devuelva líneas que el cliente no pidió**, marcadas con
> `creadoPorPromo: true`. Una línea regalada puede traer neto normal y precio final cero sin que el
> `descuentoTotal` lo explique, así que **el invariante de coherencia no se le aplica**: si se le
> aplicara, rechazaríamos una valorización perfectamente correcta y voltearíamos el checkout. El
> contrato expone `creadaPorPromo` por línea para que el consumidor distinga lo pedido de lo
> regalado.

## La lista de precios: qué hace y qué no

Verificado en vivo contra dyssa el 2026-10-07, y **corrige una suposición nuestra anterior**.

### Lo que sí hace: cambia el precio

El `CodigoListaPrecio` que mandamos **por ítem** determina el precio. Mismo ítem, misma cantidad,
cliente 8380:

| Lista enviada | Neto (50 un. del ítem `1000031861`) |
|---|---|
| `2` | `63175.000000` |
| `3` | `59976.000000` |
| sin mandar nada | `63175.000000` (igual que la 2) |

Sin lista, GESCOM **no falla**: usa la del cliente. Para el 8380 eso resulta la lista 2.

### Lo que NO hace: decidir qué criterio aplica

Esto era lo que habíamos supuesto mal. Dyssa tiene dos criterios gemelos:

| Criterio | Condición de lista | Ítems | Escala |
|---|---|---|---|
| 610 "MATARAZZO + TERRABUSI - **TRADE**" | `ListaPrecioVenta: [2]` | los mismos 40 | 5% desde 3 un., **12% desde 45** |
| 611 "MATARAZZO + TERRABUSI - **AASS**" | `ListaPrecioVenta: [3]` | los mismos 40 | 5% desde 3 un., **12% desde 150** |

Pidiendo **50 unidades con lista 3**, debería aplicar el 611 → 5% (50 < 150). Pero aplica el
**610 → 12%**. O sea: **la condición `ListaPrecioVenta` no mira la lista que mandamos.** Lo más
probable es que mire la que tiene asignada el cliente en `get-clientes` (el 8380 está en la 2).
Eso explica también por qué omitir la lista da el mismo resultado que mandar la 2.

> ⚠️ **No está probado al 100%** que sea la lista del cliente: haría falta un cliente asignado a
> la lista 3 para confirmarlo. Lo que sí está probado es que **la lista enviada no cambia el
> criterio**. Lo fija `laListaCambiaElPrecioPeroNoElCriterioQueAplica`.

### Por qué importa igual

Porque **mandar la lista equivocada da el descuento correcto sobre el precio equivocado**. El
porcentaje engaña: sale 12% en los dos casos, y parece que está todo bien. El importe no: 55594
contra 52778. Nadie lo nota mirando el descuento.

Entonces la pregunta para la tienda **no** es "¿mandás la lista para que aplique la promo
correcta?" sino: **¿de dónde sale el precio que ve el cliente en el carrito?** Si la tienda muestra
precios de una lista, tiene que mandarnos esa misma, o el neto que devolvemos no va a coincidir
con el suyo.

Y una consecuencia de negocio que conviene mirar: la lista no es solo un precio, **es el segmento
comercial**. TRADE y AASS (autoservicios) pagan distinto y además llegan al 12% con distinta
cantidad: 45 unidades contra 150.

## Detalles que ahorran tiempo

- **`id` y `promoId` llegan como NÚMERO**, no como string. Es el mismo valor en los dos endpoints,
  así que es por donde se cruzan `get-promociones` y `eval-pedido`.
- **El descuento es fracción** (`0.1`, `0.1812`, `0.1943`). El contrato expone porcentaje, así que
  el conector multiplica por 100 y hay que preservar decimales: `0.1812` → `18.12`.
- **Los importes vienen con seis decimales**: `58424.220000` → `52581.7980000`. La doc de
  referencia los muestra redondeados a dos (`52581.80`) y de ahí salió una expectativa equivocada
  en el primer test contra el ERP real. **Esa doc es para leer, no para fijar expectativas.**
- **Los `marcadores` no aportan nada hoy**: todos son `ItemQMarker` con `configuracionJson: null`.
- ⚠️ **El nombre y la descripción pueden mentir sobre el número, y pasa en las dos
  distribuidoras.** En dyssa, el criterio 205 *"FRIGOR CADENAS"* dice *"DESCUENTO 20%"* y aplica
  **0.15**. En senderolaser, el criterio 163 se llama literalmente *"ALM/REF/INS 22%"* y aplica
  **0.24**. Son texto libre que escribió una persona: sirven para explicar, **nunca** como fuente
  del número. Si alguien audita promos leyendo nombres, va a encontrar varias así.

## Lo que sigue sin verificar

| Qué | Por qué importa |
|---|---|
| `greedy`, `evaluateAll`, `criterioOrden`, `cantidadMaxima`, `descuentoPorPromocion` | No sabemos qué hacen. Hoy no nos afecta porque **delegamos la evaluación en `eval-pedido`**; sí importaría si algún día calculáramos acá |
| Un `All` con `evaluateAll:false` | Se lee como una contradicción. Preguntar |
| `TablaDescuentoItem` con `descuentoPorCantidad:false` | En dyssa siempre está en `true` |
| Qué pasa con `clientes[]` cuando no está vacío | En los 70 criterios de dyssa está vacío |
| Si otras distribuidoras traen tipos que dyssa no tiene | **Cubierto por un test**: `CatalogoContraGescomRealIT` falla si aparece un tipo de condición o de modificador que no mapeamos. Correrlo con las credenciales de cada distribuidora nueva |

## Lo que ya no está abierto

- ~~¿Pagina?~~ No hay evidencia: 70 criterios en una respuesta.
- ~~¿Hay otros tipos de modificador?~~ Sí, tres en total, los tres mapeados.
- ~~¿Cuál es el `client_id` de Keycloak?~~ `gcw-web-api`, confirmado contra la API en vivo.
