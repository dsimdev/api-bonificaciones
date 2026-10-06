# Changelog

All notable changes to this project. Technical detail, in English — the audience is future-you
and whoever runs the next 360 audit. Write **why**, not just what.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · Versioning:
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
