# api-bonificaciones — Directivas para Claude Code

**Gateway de bonificaciones.** Consume las APIs de los ERP de las distribuidoras (hoy GESCOM;
SIGMA/GEWINN después) y devuelve, detrás de **un contrato único**, qué criterios de venta existen
y qué descuento le cae a un pedido. Lo que más condiciona el diseño: **la fuente de verdad del
número es el ERP, no nosotros**, y el token de GESCOM dura 5 minutos.

> Método portado del kit CLAUDIO de `api-impuestos`. Las secciones 1–4, 8 y 9 son el método
> común; 5–7 y 10 son de este proyecto.

---

## 1. DEPLOY CHECKLIST — obligatorio antes de todo push a main

Antes de `git push origin main`, completar en este orden **sin excepción**:

1. `./gradlew build` pasa sin errores (build + tests verdes)
2. **Bump versión** en `.claude/VERSION` **y** en el `version` del `build.gradle.kts` raíz —
   semver: patch=fix/tweak, minor=feature nueva, major=rediseño/breaking
3. **`CHANGELOG.md`** — entrada técnica en inglés con todos los cambios del release
4. **`CHANGELOG_USER.md`** — entrada en español para el integrador (solo si el cambio se ve
   desde afuera; si es 100% interno, "arreglos menores")
5. **`README.md`** — actualizar "Versión actual" y cualquier dato que haya cambiado
6. `git tag vX.Y.Z && git push origin vX.Y.Z`
7. **Confirmación explícita del usuario** para ESTE push puntual

**Un "sí" anterior NO autoriza el próximo push. Cada deploy necesita su propia confirmación.**

> La versión canónica vive en `.claude/VERSION` porque el hook de auditoría no lee
> `build.gradle.kts` de forma confiable. Los dos se bumpean juntos.

**Este servicio lo consumen otros repos.** Vale la misma regla que con un frontend: **la API
nunca rompe al cliente viejo.** Campos nuevos opcionales, endpoints nuevos en vez de cambiar la
firma de los existentes, y el borrado de un campo va en un release posterior.

## 2. Flujo de trabajo

- Desarrollar en rama (`fix/…`, `feat/…`) — NUNCA commitear directo a `main`
- Merge directo a `main` + push (NO crear PRs)
- Push a `main` = producción
- Rollback: `git reset --hard vX.Y.Z` + push, o redeploy del jar anterior
- Si en algún momento hay base de datos, los cambios de esquema van con **migración Flyway
  versionada**, nunca a mano

## 3. Antes de implementar — marco obligatorio para cada feature/fix

Para cada cosa que se pida, responder con este marco ANTES de codear:

1. **Verdad sobre lo que se solicita** — ¿sirve para el negocio? ¿lo entiendo bien? Cuestionar
   el pedido, no asumir. ¿Es un problema del gateway o del ERP? (se confunden seguido)
2. **Opciones / alternativas** — al menos dos
3. **Complejidad y alcance** de cada opción
4. **Mejoras** — si es viable
5. **El entorno** — servidor propio on-premise, Java 21, sin Docker. El gateway depende de APIs
   de terceros que no controlamos y que fallan de formas tontas: pensar timeouts, reintentos y
   qué pasa cuando el ERP está caído
6. **Resumen y opinión** — resumen, plan, opinión

Esperar elección del usuario; recién entonces implementar. **Esto se cumple sobre todo cuando el
pedido parece obvio**: ahí es donde más seguido se construye lo que no era.

## 4. Regla de mínimo cambio

Cuando el pedido es simple ("agregá X", "cambiá Y"), hacer el **mínimo cambio posible**. No
interpretar como oportunidad de rediseñar el gateway. No agregar endpoints, abstracciones ni
capas que no se pidieron explícitamente.

## 5. Reglas duras del gateway

Estas no se negocian: son el motivo de existir del servicio.

- **El gateway no inventa números.** Si la respuesta dice 10%, es porque el ERP lo dijo. Mientras
  no exista una decisión explícita de calcular acá, **todo descuento se delega a `eval-pedido`**:
  reimplementar la evaluación de criterios parece fácil y es la forma más rápida de devolver un
  precio que el ERP no reconoce.
- **Toda respuesta dice de dónde salió**: qué ERP, qué distribuidora, qué criterio (id y nombre) y
  cuándo se consultó. Un descuento que no se puede explicar frente al preventista es un problema
  de soporte, no un resultado. La traza es parte del contrato, no un extra de debug.
- **Plata y descuentos en `BigDecimal`, nunca `double`/`float`.** El descuento se expone como
  **fracción** (0.1 = 10%), igual que GESCOM, para que el número sea comparable uno a uno con la
  respuesta del ERP sin conversiones en el medio.
- **Lo que no entendemos del ERP se expone, no se descarta.** Un `condicion.tipo` nuevo llega
  como `DESCONOCIDA` con su JSON crudo. Tragarse en silencio lo que no mapeamos hace que una
  promo desaparezca sin que nadie se entere.
- **Las credenciales nunca se commitean ni se loguean.** Van por variable de entorno (`.env` está
  en `.gitignore`). Ningún log imprime token, usuario ni clave — ni en DEBUG.
- **El token de GESCOM dura 5 minutos.** Se cachea por distribuidora con margen (refrescar ~4
  min) y se mintea en el mismo proceso que lo usa. Un token relayado de afuera llega vencido.
- **El gateway es de solo lectura.** `eval-pedido` es dry-run y no persiste nada. Confirmar un
  pedido contra el ERP es otro alcance y necesita una decisión explícita, no se agrega de taquito.
- **Si falta un dato para resolver, se falla explícito.** Nunca un default silencioso (ni lista de
  precios por defecto, ni "sin descuento") sin dejarlo marcado en la respuesta.

## 6. Diseño de la API

- REST/JSON. Contrato estable y versionado en la ruta: **`/v1/{tenant}/…`**, con `tenant` = la
  distribuidora. **No se inventan convenciones**: el consumidor es el entorno de Axum, así que se
  copia lo que Axum y MotorFiscal ya hacen — tenant en la ruta, auth por header **`x-api-key`**, y
  los nombres de campo de la respuesta elegidos para mapear 1:1 con lo que el consumidor ya usa.
- **Fechas en ISO 8601 con zona, un solo formato en toda la API** (Axum tiene dos y duele).
  Enums definidos una vez y validados en el borde. Listas de valores como objetos con código y
  etiqueta separados, nunca un string con el código metido en el texto.
- **La vigencia la filtra el endpoint por defecto**: un criterio vencido no viaja en el payload
  para que el cliente lo descarte.
- **Endpoint batch obligatorio** donde haya catálogo: el consumidor pide N ítems en una llamada,
  no N llamadas.
- Errores con **código de dominio**, no solo HTTP: el integrador tiene que poder distinguir
  "cliente inexistente en el ERP" de "el ERP está caído" de "distribuidora no configurada".
  El ERP devuelve casi todo como `{"errorCode":"0","message":"Error desconocido"}` — traducir eso
  a algo accionable es buena parte del valor del gateway.
- Los endpoints de consulta son **idempotentes y sin efectos**: mismo input, mismo output.

## 7. Código

- **Java 21.** Records para los DTO, sealed interfaces donde haya variantes cerradas.
- **El código va en español**, igual que el de `api-impuestos` (que lo hace aunque su CLAUDE.md
  diga lo contrario — acá se escribe la regla que de verdad se cumple). El dominio es
  íntegramente español y traducirlo rompe la conversación con el negocio: `criterio`,
  `bonificacion`, `promocion`, `descuento`, `distribuidora`, `cliente`, `articulo`, `item`,
  `preventista`, `listaPrecio`, `rubro`, `marca`, `subramo`.
- **Los nombres de campo del ERP se respetan tal cual al mapear** (`CodigoItem`, no
  `CodigoArticulo`) y se aíslan en el conector: el modelo normalizado no arrastra las rarezas de
  GESCOM al contrato público.
- Sin comentarios en el código salvo que el WHY sea no obvio. **La excepción**: todo lo que sea
  comportamiento reverse-engineereado del ERP lleva comentario con dónde se verificó — ese WHY no
  es obvio dentro de seis meses y no hay Swagger al que volver.

## 8. Commits y mensajes

- NO incluir "Generated with Claude Code" ni ninguna mención a Claude/Anthropic en commits,
  PR bodies ni descripciones
- Formato de commit: `tipo(scope): descripción breve`
- Sin emojis salvo pedido explícito

## 9. Respuestas

- Respuestas cortas y directas
- Cuando algo no se verificó, decirlo — no adivinar
- **Nada de la API de GESCOM se afirma como verdad si no se probó contra la API en vivo.** Está
  reverse-engineereada, no documentada: lo no verificado se marca como hipótesis.

## 10. Contexto del proyecto

**Arquitectura**: Java 21 + Spring Boot 3.4, Gradle multi-módulo (Kotlin DSL). `bonif-core` =
modelo normalizado y puertos, sin framework. `bonif-app` = REST + **un conector por fuente**. Sin
base de datos por ahora (ver `docs/proyecto/decisiones-abiertas.md`). Detalle en
`docs/proyecto/arquitectura.md`.

**Las fuentes no son intercambiables.** Se consumen GESCOM y el gateway de Axum, y más adelante
Chess — pero no son tres implementaciones de lo mismo: **solo GESCOM tiene criterios y el motor
que los aplica**. El gateway de Axum no expone promociones ni descuentos (verificado contra los
shapes reales de `/clientes` y `/articulos`), aporta atributos y listas. De Chess no sabemos
nada todavía. Por eso el modelo tiene `Fuente` y no un enum `Erp`, y el puerto
`CatalogoDeCriterios` lo implementa solo quien realmente tenga criterios. **No inventar
abstracciones multi-fuente antes de conocer el segundo caso.**

**La restricción dominante es que dependemos de APIs que no controlamos**: sin Swagger, con
errores genéricos, token de 5 minutos y un backend .NET+Unity que responde
`InvalidRegistrationException` a lo que no existe. Todo lo que sabemos salió de probar en vivo.
Eso define el diseño: conector aislado por ERP, todo lo crudo conservado, y tests de conector
contra un servidor HTTP stub (WireMock), no contra mocks en proceso.

**Quién lo consume** (decidido 2026-10-06): el **entorno de Axum** — la tienda virtual o alguna
app de la suite. Encaja justo en el hueco que MotorFiscal declara fuera de su alcance ("el pricing
comercial es de la tienda; el motor recibe una base ya neteada"): en un checkout, la tienda llama
primero acá para obtener el neto con descuento, y después a MotorFiscal para los tributos sobre
esa base. Los dos servicios se componen y **ninguno llama al otro**.

**Fuera de alcance** (hasta decisión explícita): crear o confirmar pedidos en el ERP, calcular
descuentos por nuestra cuenta, y la administración/alta de criterios (eso se hace en GESCOM).

---

## Documentación de referencia (fuente de verdad, cross-project)

- `C:\Dev\docs\gescom\eval-pedido.md` (y `.html`) — endpoint `eval-pedido`: auth, campos,
  respuesta, y cómo se conecta con `get-promociones`. **Leer esto primero.**
- `C:\Dev\docs\axum\integracion-axum.md` — API pública de Axum (gateway, percepciones, IVA).
- `C:\Dev\docs\axum\axum-referencias.md` — decisiones de diseño de Axum a imitar / evitar.
- `C:\Dev\docs\README.md` — índice.
- `docs/metodo/` — el método (deploy, memoria, auditoría 360, testing, lecciones).
- `docs/proyecto/` — lo específico: arquitectura, plan de fases, decisiones abiertas.

## Esenciales de la API GESCOM

- **Gateway de comandos**: `https://<distri>.gescom.online/data/cmd/<servicio>/api/v1/<comando>`.
  Servicios: `ventas`, `inventario`, `ctacte`, `distribucion`. Ruta no registrada ⇒ error de Unity.
- **Auth**: Keycloak, realm `gcw-<distri>`, client `gcw-web-api`, grant `password`. Token dura **5 min**.
- **Bonificaciones = `GET ventas/api/v1/get-promociones`** (en GESCOM "criterios de venta"). No existen
  endpoints separados de bonificaciones/descuentos/acciones. Estructura: condiciones (cuándo) +
  modificadores (`DescuentoItem`, descuento 0.1=10%) + marcadores.
- **`eval-pedido`** (`POST ventas/api/v1/eval-pedido`) = motor que aplica los criterios: le mandás
  cliente + ítems y devuelve líneas valorizadas con el descuento. Dry-run, solo lectura.
- Criterios cruzan atributos de **cliente** (`get-clientes`: tags, codigoSubramo, codigo) y de
  **artículo** (`get-articulos`, servicio **`inventario`**: codigoMarca, codigoProveedor, codigoLinea,
  codigoRubro, codigoFamilia, tags).
- Item usa `CodigoItem` (NO `CodigoArticulo`). `get-articulos` vive en `inventario`, no `ventas`.

## Método para seguir destripando la API (SIGMA/GEWINN, nuevos endpoints)

Ver `C:\Dev\docs` y la memoria `metodo-reversing-gescom-api`: mintear token local (mismo script,
por los 5 min), probar nombres de comando (Unity = no existe), leer los mensajes de validación de
modelo para reconstruir los DTO, cruzar con payloads reales. Los errores de runtime caen en un
genérico `{"errorCode":"0","message":"Error desconocido"}`; solo la validación de modelo informa.
