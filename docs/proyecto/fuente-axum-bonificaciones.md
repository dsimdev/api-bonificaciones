# Fuente Axum — bonificaciones

> Armado el 2026-10-06 con (a) una respuesta real del endpoint (~84 filas, `bonifId` 1, sucursal
> `0001`) y (b) la documentación funcional de Axum que pasó el usuario. **No está en
> `C:\Dev\docs\axum\integracion-axum.md`**, que es del 2026-08-19 y quedó vieja.
>
> Lo que viene de la doc de Axum está marcado 📘. Lo que es interpretación nuestra, ⚠️.

## Qué es

Una **política de bonificación por fila**. Cada fila dice: *"si se cumplen estos filtros, aplicá
esta operación"*. Entran al sistema por **`Bonificaciones.csv`** 📘 y bajan al celular del
vendedor junto con los clientes (`BajarClientesPic`) 📘.

**Son definiciones, no resultados.** No hay nada que valorice un pedido: **Axum no tiene
equivalente a `eval-pedido`**. Es el mismo patrón del `Hallazgo 1` de `integracion-axum.md`
(*"Axum transporta impuestos, no los calcula"*), que es lo que le dio lugar a MotorFiscal.

## Los filtros, por categoría 📘

| Categoría | Campos (doc / payload) | Qué hace |
|---|---|---|
| **Cliente** | `bonifId`, `empresa`, `listaP` / `listaDePrecios` | A qué clientes les aplica |
| **Vendedor** | `vendedor` / `codigoVendedor`, `sucursal` | Solo si coincide el vendedor |
| **Artículo** | `codArt` / `codigoArticulo`, `grupoArticulo`, `rubroArticulo`, `lineaArticulo`, `marca`, `codigoProveedor`, `canasta` | Sobre qué productos |
| **Cantidad** | `cantSuperior` / `cantidadSuperior`, `porBulto`, `esCantidadEnBultos` | Cuándo se activa |
| **Operación** | `desc` / `descuento`, `precio`, `sinCargo` / `cantidadSinCargo`, `topeDesc` / `topeDescuento` | Qué hace |

> ⚠️ **Los nombres de la doc y los del API no coinciden**: la doc describe las columnas del CSV
> (`desc`, `codArt`, `listaP`, `cantSuperior`, `topeDesc`, `sinCargo`) y el endpoint devuelve
> nombres largos (`descuento`, `codigoArticulo`, `listaDePrecios`, …). Al leer la doc de Axum hay
> que traducir. El conector trabaja con los del API.

### `bonifId` es el filtro por cliente, no un id de agrupación

📘 La doc lo lista como campo de **"Filtro Por Cliente"**. O sea: **el cliente lleva su `bonifId`
asignado** y las filas con ese `bonifId` son las que le aplican.

⚠️ Es exactamente el mismo diseño que el `Hallazgo 3` de `integracion-axum.md` para impuestos
(*"la percepción se asigna por cliente, no se resuelve por padrón"*: el cliente lleva `percepId`).
Axum repite el patrón. **Consecuencia práctica**: para saber qué bonificaciones le aplican a un
cliente hay que leer su `bonifId` desde `/clientes` y filtrar las filas. No hay entidad padre que
buscar — esto cierra una pregunta que teníamos abierta.

## Las tres operaciones posibles 📘

No es solo descuento:

| Operación | Campo | Nota |
|---|---|---|
| **Descuento %** | `descuento`, con tope en `topeDescuento` | |
| **Precio fijo** | `precio` | 📘 Cuando se fija precio, **el descuento queda en 0** y lo que cambia es el precio |
| **Unidades sin cargo** | `cantidadSinCargo`, `multiploSinCargo` | "lleva 10, paga 9" |

En la muestra que vimos solo se usa descuento; las otras dos están vacías. **Hacen falta ejemplos
reales de las otras dos** antes de modelarlas en firme.

## Jerarquía: quién gana cuando varias aplican 📘

De mayor a menor prioridad, *"según importancia y existencia"*:

1. **Canasta**
2. **Orden manual**
3. Código Artículo
4. Línea Artículo
5. Rubro Artículo
6. Grupo Artículo
7. Marca Artículo
8. Proveedor Artículo

📘 **"El valor más bajo de Orden Manual es el de mayor jerarquía"** cuando hay varias
bonificaciones aplicables con orden manual.

> ⚠️ **Corrección de una hipótesis previa.** Yo había observado que las filas vienen en pares con
> `ordenManual` 50 y 90 y exactamente 3 puntos de diferencia, y especulé que el 3% de la fila de
> proveedor estaba "incluido" en la variante de orden 50. **Eso no tiene respaldo en la doc y lo
> descarto.** La regla real es más simple: **gana el `ordenManual` más bajo**, o sea la fila de
> orden 50 (el descuento mayor). Por qué existe además la fila de 90 sigue sin explicarse — puede
> ser un fallback para cuando la de 50 no califica por otro filtro. No bloquea el motor: la regla
> de desempate ya la tenemos.

⚠️ Lectura de "importancia y existencia": se recorre la lista en orden y **gana la primera
bonificación aplicable cuyo filtro de ese nivel esté presente**. Una bonificación por canasta le
gana a una por marca aunque la de marca tenga mejor descuento.

## Canasta 📘

Una agrupación de artículos **totalmente libre**, sin las restricciones de rubro o línea, que
**siempre tiene máxima prioridad**.

Requiere que el archivo de artículos traiga la columna `canasta`, y que la bonificación la use
como filtro.

**El umbral de cantidad se evalúa sobre la suma de la canasta, no por ítem.** Es el ejemplo de la
doc: canasta "Bebidas" = Coca, Pepsi, Sprite; bonificación a partir de más de 9 unidades.

- 5 Coca + 5 Pepsi → **aplica** (10 unidades de la canasta)
- 5 Coca + 5 Fanta → **no aplica** (solo 5, Fanta no está en la canasta)

> ⚠️ **Esto es lo más fácil de implementar mal.** Si el motor evalúa `cantidadSuperior` por línea
> en vez de por el total del grupo, el primer caso no dispara y nadie se entera hasta el reclamo.
> **Pregunta abierta**: ¿la misma agregación vale para grupo, rubro, línea, marca y proveedor? El
> ejemplo de la doc solo cubre canasta.

Limitaciones que la propia doc reconoce 📘, y que son **una oportunidad para nuestro gateway**:
no se ve qué artículos están dentro de la canasta, no se ve en tiempo real si está aplicando, y
recién se ve en el resumen del pedido.

## Settings que cambian el resultado 📘

**El mismo payload puede dar descuentos distintos según cómo esté configurada la distribuidora.**
Esto no está en las filas y hay que conseguirlo aparte.

| Setting | ✔ Activado | ❌ Desactivado |
|---|---|---|
| `Bonificaciones.HabilitarOrdenManual` | se usa `ordenManual` | se usa la **prioridad automática** (la jerarquía de arriba sin el paso 2) |
| `Bonificaciones.HabilitarFiltroSucursalVendedor` | solo aplica si coincide la sucursal | se ignora el filtro de sucursal |
| **LP + cantidad de listas** | evalúa **todas** las listas disponibles y aplica **la de mayor beneficio**, ignorando el filtro de lista | aplica **solo** la lista del cliente |

El tercero es el más fuerte: convierte la resolución en un `max()` por beneficio sobre las
candidatas. ⚠️ Puede ser otra explicación de los pares 50/90, aunque en la muestra esas filas
tienen `listaDePrecios` vacío.

📘 Además, el cliente puede traer una columna `listasASeleccionar` que condiciona qué listas puede
elegir el vendedor; sin esa columna, puede elegir en orden ascendente desde la 1.

## Cómo se comporta en la app de Axum 📘

Sirve como especificación de referencia de lo que el motor tiene que reproducir:

1. El vendedor ingresa el código del artículo → se muestran las bonificaciones disponibles **como
   cards**.
2. Ingresa la cantidad → el sistema evalúa y **asigna el descuento automáticamente**.
3. **Se borra la card del descuento aplicado y quedan las que todavía se podrían aplicar.**

⚠️ El paso 3 sugiere que **aplica una bonificación por vez** y las demás siguen ofrecidas como
posibles si se agrega cantidad. Hay que confirmar si pueden **acumularse** sobre el mismo ítem o
si siempre gana una sola.

## Diferencias con GESCOM que resuelve el conector

| | GESCOM | Axum |
|---|---|---|
| **Descuento** | fracción (`0.1` = 10%) | **porcentaje (`46.57` = 46,57%)** |
| **Tipos** | JSON anidado como string (`configuracionJson`) | todo string plano, incluso números |
| **Booleanos** | reales | `"S"` / `"N"` |
| **Vacío** | ausente o lista vacía | `""` |
| **Condiciones** | tipadas y combinables (`All`/`Any`, `inverted`) | campos opcionales en AND + jerarquía de desempate |
| **Operaciones** | `DescuentoItem` | descuento, **precio fijo**, **unidades sin cargo** |
| **Motor** | **sí** (`eval-pedido`) | **no** |

> ⛔ **La convención del descuento es opuesta entre las dos fuentes.** Un `46.57` leído como
> fracción es 4657%. Se normaliza **en el conector** y la convención de salida se declara una sola
> vez en el contrato.

## Lo que sigue sin resolverse

| Qué | Por qué importa |
|---|---|
| **Cómo leemos los settings** (`HabilitarOrdenManual`, `HabilitarFiltroSucursalVendedor`, LP+listas) **por distribuidora** | Cambian el resultado y no vienen en el payload. ¿Hay endpoint? ¿Se configuran de nuestro lado? |
| **¿Hay vigencia?** | No aparece ni en el payload ni en la doc. Si no existe, no se puede reconstruir qué aplicaba en una fecha pasada — el `Hallazgo 2` de Axum repitiéndose. |
| **¿La agregación por grupo/rubro/línea/marca/proveedor funciona como la de canasta?** | Define si el umbral se suma o se mira por ítem. |
| **¿Se acumulan varias bonificaciones sobre un ítem?** | La jerarquía sugiere que gana una; el flujo de cards sugiere secuencial. |
| **Ruta exacta y método** del endpoint, y si **pagina** | Para el conector. |
| **Ejemplos reales de `precio` y de `cantidadSinCargo`** | Son dos de las tres operaciones y no las vimos en uso. |
| **Un caso donde `topeDescuento` difiera de `descuento`** | En la muestra son siempre iguales. |

## El archivo de ejemplo

Guardar la respuesta completa en `fixtures/axum-bonificaciones-<tenant>.json`: es el fixture de los
tests con WireMock. **Contiene la estructura de descuentos real de una distribuidora** — decidir
explícitamente si se commitea o se mantiene fuera del repo.
