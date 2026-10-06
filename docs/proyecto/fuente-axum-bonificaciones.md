# Fuente Axum — endpoint de bonificaciones

> Reconstruido el 2026-10-06 a partir de una respuesta real que pasó el usuario (~84 filas
> visibles, `bonifId` 1, sucursal `0001`). **No está en `C:\Dev\docs\axum\integracion-axum.md`**:
> esa doc es del 2026-08-19 y quedó vieja. Esto es análisis del payload, no contrato oficial.
>
> ⚠️ Falta la ruta exacta, el método, y la **entidad padre** (`bonifId`). Ver "Lo que falta".

## Qué devuelve

Una **lista plana de filas de bonificación**, todas colgando de un `bonifId`. Cada fila es una
regla: *"si se cumplen estos filtros, aplicá este descuento"*.

**Son definiciones, no resultados.** No hay nada en la respuesta que valorice un pedido. Esto
**confirma la hipótesis** que veníamos manejando y que es el mismo patrón del `Hallazgo 1` de
`integracion-axum.md` (*"Axum transporta impuestos, no los calcula"*): Axum transporta también las
bonificaciones, y **no existe del lado de Axum un equivalente a `eval-pedido`**.

## Campos

| Campo | Qué es | Observado |
|---|---|---|
| `id` | id de la fila | único entero real del payload |
| `bonifId` | **la bonificación a la que pertenece la fila** | `"1"` en todas |
| `codigo` | ¿código de la bonificación? | vacío en todas |
| `codigoArticulo` | filtro: artículo puntual | `"2020"`, `"78"`, o vacío |
| `codigoGrupoArticulo` / `grupoArticulo` | filtro: agrupación comercial, con descripción | `"111"` / `"SKIP CONC DP 800"` |
| `codigoRubroArticulo` / `rubroArticulo` | filtro: rubro | vacíos |
| `lineaArticulo`, `marca` | filtros | vacíos |
| `canasta` / `canastaDescripcion` | filtro: canasta de productos | `"PAPEL"` |
| `codigoProveedor` | filtro: proveedor | `"00009"` en una fila |
| `codigoVendedor` | filtro: vendedor | vacío |
| `empresa`, `sucursal` | alcance | `sucursal: "0001"` |
| `listaDePrecios` | filtro: lista | `"5"` en una fila |
| `cantidadSuperior` | **umbral de cantidad** para que dispare | `"0"`, `"1"`, `"2"`, `"3"`, `"4"` |
| `esCantidadEnBultos` | si el umbral se cuenta en bultos o unidades | `"S"` / `"N"` |
| `porBulto` | si el descuento se aplica por bulto | `"S"` / `"N"` |
| `descuento` | **porcentaje** | `"3"`, `"5.03"`, `"46.57"` |
| `topeDescuento` | tope del descuento | **idéntico a `descuento` en todas las filas** |
| `precio` | precio fijo en vez de descuento | vacío en todas |
| `cantidadSinCargo` / `multiploSinCargo` | **bonificación en producto** (lleva N paga M) | `"0"` / vacío |
| `ordenManual` | prioridad / orden de resolución | `"1"`, `"50"`, `"90"`, `"100"` |

### Los filtros son un AND implícito

No hay condiciones tipadas ni combinadores como en GESCOM. **Un campo lleno = un filtro activo;
vacío = no restringe.** Una fila con `codigoGrupoArticulo: "111"` y todo lo demás vacío aplica a
todo el grupo 111.

Eso hace el modelo de Axum **más simple pero menos expresivo** que el de GESCOM: no hay `Any`, no
hay `inverted`, no hay condiciones compartidas entre modificadores. A cambio, cada fila es
autocontenida y se mapea derecho a nuestro `Criterio` (N condiciones en AND + 1 modificador).

## Diferencias con GESCOM que hay que resolver en el conector

| | GESCOM | Axum |
|---|---|---|
| **Descuento** | fracción (`0.1` = 10%) | **porcentaje (`46.57` = 46,57%)** |
| **Tipos** | todo string JSON anidado (`configuracionJson`) | todo string plano, incluso los números |
| **Booleanos** | booleanos reales | `"S"` / `"N"` |
| **Vacío** | ausente o lista vacía | `""` |
| **Condiciones** | tipadas, combinables (`All`/`Any`, `inverted`) | campos opcionales en AND |
| **Motor** | **sí** (`eval-pedido`) | **no** |

> ⛔ **La convención del descuento es opuesta entre las dos fuentes.** Un `46.57` de Axum leído
> como fracción es 4657%. Esto se normaliza **en el conector**, y la convención de salida se
> declara una sola vez en el contrato. Es la clase de bug que no se detecta en code review y sí
> en una factura.

## Lo que el payload NO tiene

Y que hace falta para poder usarlo:

1. **Nada de cliente.** Ni código, ni tag, ni subramo, ni lista asignada. Si la bonificación se
   segmenta por cliente, eso vive en la entidad padre (`bonifId`) que no vimos.
2. **Nada de vigencia.** Ni `desde` ni `hasta`. Es exactamente el `Hallazgo 2` de
   `integracion-axum.md` (*"ni iva ni percepciones tienen vigencia"*) repitiéndose: **no se puede
   reconstruir qué bonificación estaba vigente en una fecha pasada.**
3. **`codigo` vacío en todas las filas** — no sabemos para qué está.

## Dos hallazgos del payload que hay que confirmar

### 1. Las filas vienen en pares, y la diferencia es siempre 3 puntos

Decenas de pares comparten **todos** los campos salvo `descuento` y `ordenManual`:

| grupo | `ordenManual` 50 | `ordenManual` 90 | delta |
|---|---|---|---|
| 111 SKIP CONC DP 800 | 18 | 15 | 3 |
| 113 KNORR PASTA-ARROZ | 11 | 8 | 3 |
| 208 GRANBY DIL 500 | 26 | 23 | 3 |
| 805 REXONA ANTIB LIQ 220 | 28 | 25 | 3 |
| 931 COMF 500 | 23 | 20 | 3 |

**En todos los pares observados la diferencia es exactamente 3.** Y la fila `id: 1` —la única con
`listaDePrecios: "5"` y `codigoProveedor: "00009"`, con `ordenManual: "100"`— tiene
`descuento: "3"`.

**Hipótesis**: el 3% de proveedor/lista está **incluido** en la variante de orden 50 y **no** en
la de orden 90, y `ordenManual` decide cuál gana según alguna dimensión que no está en estas
filas (probablemente la lista de precios del cliente, o el padre `bonifId`).

**No se puede resolver con este payload.** Y es crítico: elegir mal entre 18% y 15% es plata.

### 2. `topeDescuento` es igual a `descuento` en todas las filas

O es redundante en este dataset, o solo difiere cuando varios descuentos se acumulan y el tope
los limita. Si es lo segundo, **confirma que los descuentos se apilan** — y entonces el orden de
aplicación importa todavía más.

## Lo que falta

| Qué | Para qué |
|---|---|
| **Ruta exacta y método** del endpoint | Configurar el conector. |
| **La entidad padre `bonifId`** (su endpoint y su shape) | Ahí tienen que estar el cliente, la vigencia y el nombre. Sin eso no se puede saber a quién le aplica ni desde cuándo. |
| **Qué distingue `ordenManual` 50 de 90** | Es la diferencia entre cobrar 18% o 15%. Ver hallazgo 1. |
| **Cuándo `topeDescuento` difiere de `descuento`** | Define si los descuentos se acumulan. |
| **Qué significa `codigo`** (vacío en todas) | |
| **Si pagina** | Con una distribuidora real pueden ser miles de filas. |
| **Una respuesta con `precio` y con `cantidadSinCargo` cargados** | Son otros dos tipos de bonificación (precio fijo, y producto gratis) que acá no se ven usados. |

## El archivo de ejemplo

Guardar la respuesta completa en `fixtures/axum-bonificaciones-<tenant>.json`. Es el fixture de
los tests con WireMock. **Ojo**: contiene la estructura de descuentos real de una distribuidora —
decidir explícitamente si eso se commitea o se mantiene fuera del repo.
