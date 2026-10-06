# Plan de desarrollo

Cada fase cierra con un release y tiene un **criterio de salida verificable**. No se arranca la
siguiente hasta que la anterior lo cumple.

Las fases 1 y 2 no dependen de ninguna decisión abierta: se pueden empezar ya. De la 3 en
adelante, el orden depende de **quién consume el gateway** (ver `decisiones-abiertas.md` #1).

---

## Fase 0 — Andamio · v0.1.0 · **HECHA**

Gradle multi-módulo, Java 21, Spring Boot 3.4, `/health`, OpenAPI generado del código,
configuración por distribuidora con credenciales por variable de entorno, kit de método
instalado.

**Criterio de salida (cumplido)**: `gradlew build` verde, la app levanta, `/health` informa la
versión real embebida por el build y las distribuidoras configuradas.

---

## Fase 1 — Hablar con GESCOM · v0.2.0

Lo mínimo para que el gateway deje de ser un cascarón.

1. `ServicioDeToken` — Keycloak grant `password` por distribuidora, cache Caffeine con refresco a
   los ~4 min. Nunca loguea el token.
2. `ConectorGescom` — `RestClient` sobre `https://<distri>.gescom.online/data/cmd/`, con timeouts
   explícitos y la clasificación de errores de `arquitectura.md`.
3. `GET /v1/{distribuidora}/criterios` — trae `get-promociones` y lo normaliza al `Criterio` de
   `bonif-core`, incluido el parseo de `configuracionJson` (que viene como **string**, no como
   objeto). Lo no reconocido sale como `TipoCondicion.DESCONOCIDA` con su crudo.
4. Tests de conector con WireMock usando respuestas reales capturadas.

**Criterio de salida**: contra las dos distribuidoras reales (dyssa y senderolaser), el endpoint
devuelve todos los criterios y **el informe de parseo no pierde nada**: cantidad de criterios,
condiciones y modificadores leídos = los que trae el ERP, y la lista de tipos que cayeron en
`DESCONOCIDA` es explícita y está revisada.

**Necesita**: credenciales de las dos distribuidoras (ver `informacion-que-falta.md`).

---

## Fase 2 — Valorizar un pedido · v0.3.0

El endpoint que justifica el proyecto.

1. `POST /v1/{distribuidora}/valorizaciones` — contrato propio (cliente + ítems), por dentro
   `eval-pedido` con el `Pedido` envuelto, `Identificador` GUID generado por nosotros.
2. Respuesta normalizada: por línea, neto, neto con descuento, descuento (fracción) y
   **el detalle de qué criterio lo otorgó, con su nombre**, enriquecido desde el catálogo de la
   Fase 1 (el ERP devuelve `promoId` y `promoNombre`; el gateway puede devolver además las
   condiciones que lo dispararon).
3. Validación de entrada que falle antes de gastar una llamada al ERP (ítems vacíos, cantidad
   ≤ 0, distribuidora desconocida), con códigos de dominio.

**Criterio de salida**: un test etiquetado `erp` reproduce **exactamente** los dos casos ya
verificados — dyssa cliente 8380 / ítem 5000014792 / lista 2 → `58424.22` → `52581.80` (10%), y
senderolaser cliente 301 / ítem 610030 / lista 1 → `6201.06` → `5456.93` (12%) — pasando por
nuestro endpoint, no por curl.

---

## Fase 3 — Explicar por qué · v0.4.0

Lo que GESCOM **no** da y es la pregunta real del negocio: *"¿por qué este cliente no tiene esta
promo?"*.

1. Catálogos cacheados: `get-clientes` (ventas) y `get-articulos` (**inventario**, no ventas).
2. `GET /v1/{distribuidora}/clientes/{codigo}/criterios` — qué criterios podrían aplicarle a ese
   cliente, cruzando sus `tags` / `codigoSubramo` / `codigo` contra las condiciones.
3. Diagnóstico por criterio: qué condición se cumple y cuál no.

> Ojo: esto **sí** es evaluar criterios de nuestro lado, y contradice en parte la regla de
> delegar. La salida es que el resultado se marque explícitamente como **informativo**, nunca
> como precio. El número sigue saliendo solo de `eval-pedido`.

**Criterio de salida**: para un criterio real con condiciones combinadas (`All`/`Any`), el
diagnóstico coincide con lo que `eval-pedido` efectivamente aplica en un pedido de prueba.

---

## Fase 4 — Endurecer y deployar · v0.5.0

1. **Autenticación propia** del gateway (ver decisión abierta #2) — obligatoria antes de
   exponerlo fuera de la red local: guarda credenciales de varias distribuidoras.
2. Timeouts, reintentos acotados y qué devolver cuando el ERP está caído.
3. Métricas y logs útiles sin un solo secreto adentro.
4. Deploy: servicio de Windows, igual que `api-impuestos` (sin Docker en el entorno).
5. Colección Postman del gateway, para el integrador.

**Criterio de salida**: corriendo en el servidor real, con auth, y un consumidor externo haciendo
una valorización end-to-end.

---

## Fase 5 — Segundo ERP · v1.0.0

SIGMA / GEWINN detrás del mismo contrato, reusando el método de reversing
(`metodo-reversing-gescom-api`).

**Criterio de salida**: el mismo request del consumidor, cambiando solo la distribuidora, devuelve
la misma forma de respuesta contra otro ERP. **Hasta que esto no pase, "contrato único" es una
hipótesis, no un hecho** — y por eso no se inventan abstracciones multi-ERP antes de tiempo.

---

## Lo que no está en el plan (y por qué)

| Cosa | Por qué no |
|---|---|
| Crear/confirmar pedidos en el ERP | Otro alcance, con riesgo de escritura. Necesita decisión explícita. |
| Alta y edición de criterios | Se hace en GESCOM. Duplicarlo es pelearse con el ERP. |
| Calcular descuentos localmente | Ver `arquitectura.md`: solo en shadow mode y si aparece el caso que lo justifique. |
| Panel web propio | No se pidió. Si hace falta, se evalúa como repo aparte o estático embebido, como el de `api-impuestos`. |
| Base de datos | No hace falta todavía. Ver decisión abierta #4. |
