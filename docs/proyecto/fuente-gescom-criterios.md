# Fuente GESCOM — `get-promociones` (criterios de venta)

> Verificado el 2026-10-07 contra una respuesta **real** de dyssa. Antes de esto el conector estaba
> escrito contra un fixture reconstruido de la doc, y tenía un bug grueso (ver abajo).
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
| `All` / `Any` | `conditionCodes` | combinan otras condiciones, no tienen valores propios |

Los códigos de agrupador vienen **prefijados por origen**: `pepsico-11`, `dyssa-122`, `dyssa-02`.
Conviven los dos prefijos en el mismo catálogo.

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

## Detalles que ahorran tiempo

- **`id` y `promoId` llegan como NÚMERO**, no como string. Es el mismo valor en los dos endpoints,
  así que es por donde se cruzan `get-promociones` y `eval-pedido`.
- **El descuento es fracción** (`0.1`, `0.1812`, `0.1943`). El contrato expone porcentaje, así que
  el conector multiplica por 100 y hay que preservar decimales: `0.1812` → `18.12`.
- **El único `modificador.tipo` observado es `DescuentoItem`.** No hay precio fijo ni unidades sin
  cargo en el catálogo de dyssa.
- **Los `marcadores` no aportan nada hoy**: todos son `ItemQMarker` con `configuracionJson: null`.
- ⚠️ **La `descripcion` puede mentir sobre el número.** El criterio 205 se llama *"FRIGOR
  CADENAS"* y su descripción dice *"DESCUENTO 20% EN CADENAS"*, pero el modificador aplica
  **0.15**. La descripción es texto libre que escribió una persona: sirve para explicar, **no**
  como fuente del número.

## Lo que sigue sin verificar

| Qué | Por qué importa |
|---|---|
| `greedy`, `evaluateAll`, `criterioOrden`, `cantidadMaxima` | No sabemos qué hacen. Hoy no nos afecta porque **delegamos la evaluación en `eval-pedido`**; sí importaría si algún día calculáramos acá |
| Un `All` con `evaluateAll:false` | Se lee como una contradicción. Preguntar |
| Si `get-promociones` **pagina** | El catálogo de dyssa ya supera los 560 criterios. Si pagina y lo ignoramos, nos faltan criterios en silencio |
| Qué pasa con `clientes[]` cuando no está vacío | En dyssa está vacío en todos |
| Si existen otros `modificador.tipo` en otras distribuidoras | Un tipo nuevo hoy cae como `DESCONOCIDA`… pero el **modificador** no tiene ese escape: hay que revisarlo cuando aparezca |
