# Plan de desarrollo

Cada fase cierra con un release y tiene un **criterio de salida verificable**. No se arranca la
siguiente hasta que la anterior lo cumple.

**Consumidor decidido el 2026-10-06: el entorno de Axum** (la tienda virtual o alguna app de la
suite). Eso ordena el plan: la Fase 2 es la que paga el proyecto, porque es la llamada del
checkout; y la autenticación deja de ser "para más adelante" — en cuanto la tienda lo consuma por
red, el gateway queda expuesto con credenciales de todas las distribuidoras adentro.

---

## Fase 0 — Andamio · v0.1.0 · **HECHA**

Gradle multi-módulo, Java 21, Spring Boot 3.4, `/health`, OpenAPI generado del código,
configuración por distribuidora con credenciales por variable de entorno, kit de método
instalado.

**Criterio de salida (cumplido)**: `gradlew build` verde, la app levanta, `/health` informa la
versión real embebida por el build y las distribuidoras configuradas.

---

## Fase 1 — Leer el catálogo de las dos fuentes · v0.2.0

`GET /v1/{tenant}/criterios`, alimentado por las dos fuentes y normalizado al mismo `Criterio`.

**GESCOM**

1. `ServicioDeToken` — Keycloak grant `password` por distribuidora, cache Caffeine con refresco a
   los ~4 min. Nunca loguea el token.
2. `ConectorGescom` — `RestClient` sobre `https://<distri>.gescom.online/data/cmd/`, con timeouts
   explícitos y la clasificación de errores de `arquitectura.md`.
3. Normalizar `get-promociones`, incluido el parseo de `configuracionJson` (viene como **string**,
   no como objeto). Lo no reconocido sale como `TipoCondicion.DESCONOCIDA` con su crudo.

**Axum**

4. `ConectorAxum` — `x-api-key`, tenant en la ruta. Normalizar las filas de bonificación: cada
   fila = N condiciones en AND (los campos no vacíos) + 1 modificador. **Convertir el porcentaje
   a la convención de salida acá**, nunca después (`46.57` de Axum ≠ `0.1` de GESCOM).
5. Mapear `"S"`/`"N"` a booleanos y `""` a ausente, en el borde.

**Las dos**: tests de conector con WireMock usando las respuestas reales capturadas.

**Criterio de salida**: para una distribuidora real de cada fuente, el endpoint devuelve todos los
criterios y **el informe de parseo no pierde nada**: lo leído = lo que trae la fuente, y la lista
de lo que cayó en `DESCONOCIDA` es explícita y está revisada. Más un test que falla si un
porcentaje de Axum sale sin convertir.

**Necesita**: credenciales de una distribuidora GESCOM, y la ruta + api-key de Axum
(ver `informacion-que-falta.md`).

---

## Fase 2 — Valorizar un pedido · v0.3.0

El endpoint que justifica el proyecto. `POST /v1/{tenant}/valorizaciones`, contrato propio
(cliente + ítems), con validación que falle **antes** de gastar una llamada a la fuente (ítems
vacíos, cantidad ≤ 0, tenant desconocido), con códigos de dominio.

**Dos caminos distintos por dentro, uno solo hacia afuera** (ver `arquitectura.md` → "La regla de
delegar, corregida"):

| Fuente | Cómo |
|---|---|
| **GESCOM** | **Delega** en `eval-pedido` (`Pedido` envuelto, `Identificador` GUID nuestro). El número lo da el ERP. |
| **Axum** | **Evalúa acá**: no hay motor del otro lado. Filtros en AND, umbral por `cantidadSuperior`, bultos vs unidades, y la resolución de `ordenManual` (decisión abierta #5). |

La respuesta **dice cuál de los dos fue**, y en los dos casos trae el detalle de qué criterio
otorgó el descuento, con su nombre.

**Criterio de salida**, dos partes:

- **GESCOM**: un test etiquetado `erp` reproduce **exactamente** los dos casos ya verificados —
  dyssa cliente 8380 / ítem 5000014792 / lista 2 → `58424.22` → `52581.80` (10%), y senderolaser
  cliente 301 / ítem 610030 / lista 1 → `6201.06` → `5456.93` (12%) — pasando por nuestro
  endpoint, no por curl.
- **Axum**: un set de casos **acordados con negocio**, no inventados por nosotros. Acá no hay
  contra qué contrastar: si nuestra interpretación de las filas no es la que la distribuidora
  tiene en la cabeza, nadie lo detecta hasta la factura.

---

## Fase 3 — Explicar por qué · v0.4.0

Lo que GESCOM **no** da y es la pregunta real del negocio: *"¿por qué este cliente no tiene esta
promo?"*.

1. Catálogos cacheados: `get-clientes` (ventas) y `get-articulos` (**inventario**, no ventas).
2. `GET /v1/{tenant}/clientes/{codigo}/criterios` — qué criterios podrían aplicarle a ese
   cliente, cruzando sus `tags` / `codigoSubramo` / `codigo` contra las condiciones.
3. Diagnóstico por criterio: qué condición se cumple y cuál no.

> Ojo: esto **sí** es evaluar criterios de nuestro lado, y contradice en parte la regla de
> delegar. La salida es que el resultado se marque explícitamente como **informativo**, nunca
> como precio. El número sigue saliendo solo de `eval-pedido`.

**Criterio de salida**: para un criterio real con condiciones combinadas (`All`/`Any`), el
diagnóstico coincide con lo que `eval-pedido` efectivamente aplica en un pedido de prueba.

---

## Fase 4 — Endurecer y deployar · v0.5.0

> **La autenticación (`x-api-key`) se adelanta a la Fase 2** si la tienda va a consumirlo por red
> antes de que exista esta fase. El gateway guarda credenciales de todas las distribuidoras:
> exponerlo sin auth es regalar sus datos comerciales. Son pocas horas de trabajo, no justifica
> el riesgo de dejarlo para después.

1. **Autenticación propia** del gateway por `x-api-key`, misma convención que el gateway de Axum.
2. Timeouts, reintentos acotados y qué devolver cuando el ERP está caído.
3. Métricas y logs útiles sin un solo secreto adentro.
4. Deploy: servicio de Windows, igual que `api-impuestos` (sin Docker en el entorno).
5. Colección Postman del gateway, para el integrador.

**Criterio de salida**: corriendo en el servidor real, con auth, y un consumidor externo haciendo
una valorización end-to-end.

---

## Fase 5 — Las otras fuentes · v1.0.0

Dos cosas distintas, en este orden:

1. **Gateway de Axum**. No aporta criterios (no los tiene): aporta atributos de cliente/artículo
   y listas. Entra cuando esté decidido **para qué lo queremos** — ver
   `informacion-que-falta.md`. Puede adelantarse a la Fase 3 si resulta que conviene resolver los
   atributos por Axum en vez de por GESCOM.
2. **Chess**. Hoy no sabemos qué es. Arranca con el método de reversing
   (`metodo-reversing-gescom-api`), igual que se hizo con GESCOM.

**Criterio de salida**: el mismo request del consumidor, cambiando solo el tenant, devuelve la
misma forma de respuesta contra otra fuente. **Hasta que eso no pase, "contrato único" es una
hipótesis, no un hecho** — y por eso no se inventan abstracciones multi-fuente antes de tiempo.

---

## Lo que no está en el plan (y por qué)

| Cosa | Por qué no |
|---|---|
| Crear/confirmar pedidos en el ERP | Otro alcance, con riesgo de escritura. Necesita decisión explícita. |
| Alta y edición de criterios | Se hace en GESCOM. Duplicarlo es pelearse con el ERP. |
| Calcular descuentos localmente | Ver `arquitectura.md`: solo en shadow mode y si aparece el caso que lo justifique. |
| Panel web propio | No se pidió. Si hace falta, se evalúa como repo aparte o estático embebido, como el de `api-impuestos`. |
| Base de datos | No hace falta todavía. Ver decisión abierta #4. |
