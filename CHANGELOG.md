# Changelog

All notable changes to this project. Technical detail, in English — the audience is future-you
and whoever runs the next 360 audit. Write **why**, not just what.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · Versioning:
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-10-06

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

### Notes
- The `get-promociones` fixtures are **reconstructed from the reference docs, not captured from
  the live API** — we have never seen a real response. In particular the key holding a condition's
  values is unknown, so the mapper takes the first scalar array that is not `conditionCodes`. This
  needs validating against a real payload before it is trusted.

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
