# Changelog

All notable changes to this project. Technical detail, in English — the audience is future-you
and whoever runs the next 360 audit. Write **why**, not just what.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · Versioning:
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-10-07

### Added
- `POST /v1/{tenant}/valorizaciones` — the endpoint the project exists for. Own contract in, own
  contract out; nothing from GESCOM leaks through. Delegates the number to `eval-pedido` because
  GESCOM has an engine: reimplementing criteria evaluation is the fastest way to quote a price the
  ERP does not recognise.
- `ServicioDeToken` — Keycloak `password` grant, cached per tenant for 4 minutes against a 5-minute
  token, minted in the same process that uses it. Cache key is the tenant: handing distributor B
  distributor A's token would serve one distributor's commercial data to another.
- `ClienteGescom` — translates GESCOM's failure modes into domain codes. `InvalidRegistrationException`
  means *we* asked for a command that does not exist (500, our bug), not that the distributor's
  data is wrong; the generic `{"errorCode":"0"}` comes back as `FUENTE_ERROR_DESCONOCIDO` with the
  raw body attached rather than swallowed.
- `CatalogoGescom` — `get-promociones` normalized into `Criterio`, cached 5 minutes, used to
  enrich each discount with the conditions that triggered it. **If it fails the valorisation still
  answers**: the catalogue explains the number, it does not produce it, and dropping a checkout
  over a missing explanation trades the sale for a nicety.
- `LineaValorizada.cierra()` — invariant that `neto − descuento == netoConDescuento` per line,
  within a cent (the ERP rounds to two decimals and we do not reproduce its rounding). A response
  that does not add up is rejected with `RESPUESTA_INCOHERENTE` instead of being passed on.
- `Operacion` sealed interface: discount, fixed price, free units — the three operations Axum
  documents. Sealed so a fourth breaks compilation instead of being silently dropped.
- `ValorizacionContraGescomRealIT`, tagged `erp`: reproduces the two live-verified cases (dyssa
  558 → 52581.80, senderolaser 159 → 5456.93) through our own endpoint. Skips itself when no
  credentials are present.

### Changed
- **Discount is now exposed as a percentage (`10` = 10%), not a fraction.** Decided for
  consistency with the rest of the Axum environment. GESCOM gives `0.1` and the connector
  multiplies by 100; Axum already gives `46.57` and passes through. The conversion lives in the
  connector and nowhere else, and `LineaValorizadaTest` fails if a fraction escapes unconverted.
- `RestClient` is pinned to **HTTP/1.1**. The JDK client negotiates HTTP/2 by default and the
  connection died with `EOF reached while reading` instead of a usable error — found against the
  stub, and the real counterpart is a years-old .NET backend, so this is not hypothetical.
- `Clock` is injected rather than calling `OffsetDateTime.now()` inline: `consultadoEn` is part of
  the contract and has to be fixable in a test.

### Fixed (after seeing a real `get-promociones` payload from dyssa)
The connector had been written against a fixture reconstructed from the reference docs. The real
payload broke three assumptions:
- **A modifier's `descuento`, `dataConditionCodes` and `allowOverlap` live *inside*
  `configuracionJson`**, not as fields of their own. Reading them as top-level fields left them
  null, so **every criterion came out with a 0% discount**. A hand-made fixture validates the code
  against itself — this is the cost of that.
- **`condiciones` is a tree with dead branches, not a list.** Evaluation starts at
  `codigoCondicionPrincipal` and descends through `conditionCodes`; the real catalogue contains
  **orphan conditions** that no combinator references (criteria 294 and 30). Returning those as
  "why it applied" would be a lie, so `Criterio.condicionesEnJuego()` walks from the root and
  `condicionesHoja()` also drops the `All`/`Any` nodes. The tree can contain **cycles** (criterion
  2: the `Any` 104 references condition 101, which also hangs off the root `All`), so the walk
  tracks visited codes.
- **The key holding a condition's values differs per type** — `codigos`, `tags`, `marcas`,
  `proveedores`, `lineas`, `rubros`, `familias`, `calibres`, `subRamoCodigos`. Now mapped
  explicitly per type, with the previous first-scalar-array heuristic kept only for unknown types.

Also: `id` and `promoId` arrive as **numbers**, not strings; dates are ISO 8601 with offset;
discounts keep four decimals (`0.1812` → `18.12`). Criterion descriptions can contradict the actual
discount (criterion 205 says "20%" and applies 15%), so the description is for humans only.

### Changed
- An unrecognised `modificador.tipo` no longer becomes a silent 0% discount. `Modificador` now
  carries the ERP's `tipo` and raw `configuracionJson`, `operacion` is null, and
  `noReconocido()` flags it — the hard rule is that what we do not understand stays visible.
- `Criterio` gained `descripcion`, `codigoCondicionPrincipal` and `orden`; `Condicion` and
  `Modificador` gained the ERP's own `descripcion`, which is better at explaining a discount than
  anything we would write. It is exposed in `CondicionResponse.descripcion`.
- Enrichment now matches the modifier **by the discount `eval-pedido` actually applied** and
  returns only that modifier's conditions. A criterion can carry several modifiers pointing at
  different conditions — that is how dyssa's criterion 2 expresses "10% global and 5% on Pehuamar".

### Removed
- **Axum as a source.** The store consumes Axum's bonificaciones directly, so this gateway is
  GESCOM-only (decided 2026-10-07). `Fuente` has a single value, the `Axum` config record is gone,
  and `Operacion` lost `PrecioFijo` and `UnidadesSinCargo` — those were Axum's model.
  `docs/proyecto/fuente-axum-bonificaciones.md` is kept as reference, clearly marked out of scope.

### Added (after running against the live dyssa API)
Walking the real catalogue — 70 criteria, all active, in a single response — surfaced three things
the reference docs never mentioned. They surfaced **because an unrecognised type stays flagged
instead of passing as 0%**; that rule paid for itself on its first outing.
- **`TablaDescuentoItem`** — quantity-tiered discount. `"tabla":[[3,0.05],[45,0.12]]` means 5% from
  3 units, 12% from 45. Modelled as `Operacion.EscalaDeDescuento`.
- **`AgregaGratis`** — free units of a specific item (`codigoItem`, `cantidad`); eight criteria in
  dyssa, the "5+1 sin cargo" deals and combos. Modelled as `Operacion.ItemSinCargo`.
  **This one was a live bug waiting to happen**: it makes `eval-pedido` return lines the customer
  never ordered, flagged `creadoPorPromo`. A free line can carry a normal net and a zero final
  price with no discount to explain it, so the coherence invariant would have rejected a perfectly
  good valorisation and killed the checkout. Those lines are now exempt from the invariant and the
  contract exposes `creadaPorPromo` per line.
- **`ListaPrecioVenta`** — a condition type ("applies to a list of price lists") that was not in
  the reference either.
- `CatalogoContraGescomRealIT` (tagged `erp`) turns the parse report into a standing test: it fails
  if GESCOM introduces a condition or modifier type we do not map, or if a value key changes. Run
  it with each new distributor's credentials.
- `Condicion` now also carries the ERP's raw `tipo` string — without it, a `DESCONOCIDA` condition
  is unidentifiable, which is exactly how `ListaPrecioVenta` stayed invisible.

### Verified live against both distributors
- `ValorizacionContraGescomRealIT` reproduces both documented cases end to end through our own
  endpoint: dyssa 558 (10%) and senderolaser 159 (12%).
- `CatalogoContraGescomRealIT` is parameterized over both tenants, and
  `cadaDistribuidoraRecibeSuPropioCatalogoYNoElDeLaOtra` covers **tenant isolation of the token
  cache** — the one bug a single distributor can never catch, and the worst this service could
  have: one distributor being served another is commercial data.
- The client code is the same one GESCOM uses, confirmed by the user: no translation layer needed.
- Grouper codes are formatted differently per distributor — prefixed in dyssa (`pepsico-11`,
  `dyssa-122`), bare numbers in senderolaser (`"100"`). They are opaque identifiers; do not parse
  the prefix.
- A criterion name can contradict its own discount in both catalogues: senderolaser 163 is called
  "ALM/REF/INS 22%" and applies 24%.

### Notes
- Amounts come back with **six decimals** (`58424.220000` → `52581.7980000`). The reference doc
  rounds them to two, which is where the first live test's wrong expectation came from. The shared
  doc in `C:\Dev\docs` has been corrected.
- No evidence of pagination: 70 criteria in one response. What looked like a 560-criteria catalogue
  was Postman's copy limit.
- Keycloak `client_id` confirmed live: `gcw-web-api`.
- Still unverified in `configuracionJson`: `greedy`, `evaluateAll` (including `All` +
  `evaluateAll:false`, which reads as a contradiction), `criterioOrden`, `cantidadMaxima`,
  `descuentoPorPromocion`. None affect us while evaluation is delegated to `eval-pedido`.

## [0.1.0] - 2026-10-06

### Added
- Initial scaffold: Gradle multi-module (Kotlin DSL), Java 21, Spring Boot 3.4.1.
  `bonif-core` holds the normalized model and the ERP ports with no framework dependency;
  `bonif-app` holds the REST layer and (later) one connector per ERP. Same split as
  `api-impuestos`, for the same reason: the domain must be testable without booting Spring.
- `bonif-core` draft model reconstructed from the verified GESCOM notes in
  `C:\Dev\docs\gescom\eval-pedido.md`: `Criterio`, `Condicion`, `Modificador`, `TipoCondicion`,
  `Vigencia`, `Erp`, plus the `CatalogoDeCriterios` port. `TipoCondicion.DESCONOCIDA` exists on
  purpose — an unmapped condition type from the ERP must stay visible instead of vanishing.
- `GET /health` reporting the running version and the configured distribuidoras. The second half
  is the cheap deploy smoke test: a missing env var shows up there instead of on the first real
  query. Locked in by `SaludControllerTest`.
- OpenAPI/Swagger generated from the code (springdoc). Lesson carried over from `api-impuestos`:
  a hand-maintained API document goes stale without anyone noticing.
- `processResources` replaces `@version@` in `application.yml` at packaging time, with
  `inputs.property("version", ...)` so a version bump actually invalidates the task — in
  `api-impuestos` the missing input line shipped a stale version for a whole release.
- Per-distribuidora configuration (`ConfiguracionDeDistribuidoras`) with credentials sourced from
  environment variables only; `.env` is gitignored and `.env.example` documents the shape.
- Test setup: JUnit 5 everywhere, WireMock available in `bonif-app` so ERP connectors get tested
  against a real HTTP server. Tests tagged `erp` (which need live distributor credentials) are
  excluded unless `-PincludeErpTests` is passed, so `gradlew build` stays green on any machine.
- CLAUDIO kit installed: `CLAUDE.md`, `.claude/settings.json` hooks, `docs/metodo/`,
  `docs/proyecto/`, both changelogs.

### Notes
- No database, no auth on our own endpoints yet — both are open decisions, see
  `docs/proyecto/decisiones-abiertas.md`.
