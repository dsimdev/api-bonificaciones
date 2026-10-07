# Plan de desarrollo

Cada fase cierra con un release y tiene un **criterio de salida verificable**. No se arranca la
siguiente hasta que la anterior lo cumple.

**Consumidor decidido el 2026-10-06: el entorno de Axum** (la tienda virtual o alguna app de la
suite). Y **lo más urgente a integrar es `eval-pedido`** (decidido el 2026-10-06): es la llamada
del checkout, y además no depende del catálogo, así que va primero y el catálogo baja a la Fase 2
como enriquecimiento.

Una consecuencia que no se posterga: la **autenticación** deja de ser "para más adelante". En
cuanto la tienda lo consuma por red, el gateway queda expuesto con credenciales de todas las
distribuidoras adentro. Si eso pasa antes de la Fase 5, el `x-api-key` se adelanta.

---

## Fase 0 — Andamio · v0.1.0 · **HECHA**

Gradle multi-módulo, Java 21, Spring Boot 3.4, `/health`, OpenAPI generado del código,
configuración por distribuidora con credenciales por variable de entorno, kit de método
instalado.

**Criterio de salida (cumplido)**: `gradlew build` verde, la app levanta, `/health` informa la
versión real embebida por el build y las distribuidoras configuradas.

---

## Fase 1 — Valorizar con `eval-pedido`, con catálogo · v0.2.0 · **✅ CERRADA para dyssa**

`POST /v1/{tenant}/valorizaciones` sobre GESCOM. Es la llamada del checkout y lo que paga el
proyecto.

`eval-pedido` ya devuelve `promoId` y `promoNombre`, así que el número no depende del catálogo —
pero el catálogo entró igual en esta fase (decisión del usuario) para poder devolver **las
condiciones que dispararon** cada descuento. Detalle de `get-promociones` en
[fuente-gescom-criterios.md](fuente-gescom-criterios.md).

1. `ServicioDeToken` — Keycloak grant `password` por distribuidora, cache Caffeine con refresco a
   los ~4 min. **Nunca loguea el token.**
2. `ConectorGescom` — `RestClient` sobre `https://<distri>.gescom.online/data/cmd/`, con timeouts
   explícitos y la clasificación de errores de `arquitectura.md`.
3. `POST /v1/{tenant}/valorizaciones` — contrato propio (cliente + ítems). Por dentro arma el
   `Pedido` envuelto con `CodigoItem` (**no** `CodigoArticulo`) y un `Identificador` GUID
   **generado por nosotros** — idempotencia, la lección del `operationGuid` de Axum.
4. Validación que falle **antes** de gastar la llamada: ítems vacíos, cantidad ≤ 0, tenant
   desconocido. Con códigos de dominio.
5. Respuesta normalizada por línea: neto, neto con descuento, descuento, y el detalle de qué
   promo lo otorgó. **Siempre dice que el número lo dio el ERP.**
6. `CatalogoGescom` — `get-promociones` normalizado y cacheado, para enriquecer cada descuento con
   las condiciones que lo dispararon. **Si falla, la valorización igual responde**: el catálogo
   explica el número, no lo produce.

**Criterio de salida — ✅ CUMPLIDO para dyssa el 2026-10-07**: `ValorizacionContraGescomRealIT`
reproduce el caso verificado (cliente 8380 / ítem 5000014792 / lista 2 → `58424.22` →
`52581.798`, 10%) **pasando por nuestro endpoint**, contra la API en vivo. Y
`CatalogoContraGescomRealIT` confirma que el parseo del catálogo real no pierde nada: ningún tipo
de condición ni de modificador sin mapear, ninguna condición conocida sin valores.

Falta `senderolaser` (se saltea sin credenciales): con una sola distribuidora no se prueba el
aislamiento entre tenants del cache de tokens.

46 tests verdes (1 salteado). Correr los que pegan al ERP: `gradlew build -PincludeErpTests` con
el `.env` cargado.

---


## Fase 2 — Explicar por qué · v0.3.0

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

## Fase 3 — Endurecer y deployar · v0.4.0

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

## Fase 4 — Chess, si entra · v1.0.0

La única segunda fuente que queda en el horizonte. Hoy **no sabemos qué es Chess** — ni una
mención en `C:\Dev\docs`. Arranca con el método de reversing (`metodo-reversing-gescom-api`), igual
que se hizo con GESCOM, y escribiendo la referencia en `C:\Dev\docs` **antes** del código.

**Criterio de salida**: el mismo request del consumidor, cambiando solo el tenant, devuelve la
misma forma de respuesta contra otra fuente. **Hasta que eso no pase, "contrato único" es una
hipótesis, no un hecho** — y por eso no se inventan abstracciones multi-fuente antes de tiempo.

> El gateway de Axum **no** entra acá: quedó fuera de alcance el 2026-10-07 porque la tienda lo
> consume directo. Ver `fuente-axum-bonificaciones.md`.

---

## Lo que no está en el plan (y por qué)

| Cosa | Por qué no |
|---|---|
| Crear/confirmar pedidos en el ERP | Otro alcance, con riesgo de escritura. Necesita decisión explícita. |
| Alta y edición de criterios | Se hace en GESCOM. Duplicarlo es pelearse con el ERP. |
| Calcular descuentos localmente | Ver `arquitectura.md`: solo en shadow mode y si aparece el caso que lo justifique. |
| Panel web propio | No se pidió. Si hace falta, se evalúa como repo aparte o estático embebido, como el de `api-impuestos`. |
| Base de datos | No hace falta todavía. Ver decisión abierta #4. |
