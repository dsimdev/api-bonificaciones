# Changelog

All notable changes to this project. Technical detail, in English — the audience is future-you
and whoever runs the next 360 audit. Write **why**, not just what.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · Versioning:
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.0] - 2026-10-09

### Added
- **Pre-resolved articles per criterion.** `GET /v1/{tenant}/criterios` now includes an `articulos`
  field on each criterion: the item codes that match the criterion's article conditions (brand,
  category, supplier, etc.), resolved against GESCOM's article catalogue via an inverted index. The
  store no longer needs to resolve "brand pepsico-11" to individual item codes — the gateway does it.
- **Client filter on criteria endpoint.** `?cliente=8380` filters the response to only the criteria
  that apply to that client, checking tag, subramo, and explicit client code conditions. Without it,
  all criteria are returned.
- **Article cache (`ArticulosGescom`)** — lazy per-tenant Caffeine cache of GESCOM's `get-articulos`
  (inventario service). Builds an inverted index on load: attribute value → set of item codes. Same
  TTL config as the criteria cache. Fallback to stale data on refresh failure.
- **Client cache (`ClientesGescom`)** — lazy per-tenant Caffeine cache of GESCOM's `get-clientes`
  (ventas service). Stores code, subramo, and tags per client. Same TTL and fallback pattern.
- **`ResolvedorDeArticulos`** — walks the condition tree (TODAS = intersection, ALGUNA = union) with
  memoization to handle GESCOM's DAG structure (nodes with multiple parents). Client conditions are
  skipped for article resolution and evaluated separately for the client filter.
- Tests for article resolution (CODIGO_ITEM, MARCA_ARTICULO via inverted index), client filtering
  (tag match, tag mismatch), and no-filter baseline. Fixtures for `get-articulos` and `get-clientes`.
- Criteria endpoint documented in `entrega-tienda/contrato.md`.

## [0.7.0] - 2026-10-09

### Added
- **Lazy in-memory cache for the criteria catalogue, per distributor.** Each tenant's criteria are
  fetched from GESCOM on first access and refreshed after a configurable TTL (default 60 min,
  `GESCOM_CACHE_CRITERIOS_MINUTOS`). Unused tenants are evicted after 4 hours of no access
  (`GESCOM_CACHE_CRITERIOS_EVICT_HORAS`). With ~1000 possible distributors only the active ones
  occupy memory. Uses Caffeine `expireAfterAccess`, not `expireAfterWrite`, so the eviction clock
  resets on every query.
- **Fallback to stale data when GESCOM is down.** If a refresh fails and the cache still holds the
  previous entry, it serves the stale data and logs a warning instead of failing the request. Only
  the first load (no cache at all) propagates the error. This prevents losing the catalogue when
  GESCOM is temporarily unavailable.
- **`actualizadoEn` in the criteria response.** ISO 8601 timestamp of when the cached data was last
  successfully fetched from GESCOM. The consumer knows how fresh the data is.
- `CatalogoGescom.olvidar(tenant)` to invalidate a single tenant's cache entry (used by tests and
  available for future admin endpoints).
- Three cache-specific tests: second call does not hit GESCOM, GESCOM failure with cache serves
  stale, `actualizadoEn` is set after load.

### Changed
- **`VALORIZACION` scope can now read `/v1/{tenant}/criterios`.** The restriction was added because
  the checkout key lived in a browser and the catalogue is the full commercial structure. Since
  v0.6.3 the call goes server-side, so the key is no longer exposed in DevTools. The store needs
  the catalogue to build its bonifications page, and the data is not more sensitive than what
  the valorisation response already returns per line (same bonifications, same conditions — just
  all of them instead of the ones that matched a specific order).
- **Panel: criterion detail expands inline.** "Ver por qué" now opens as an expandable row directly
  below the criterion in the table, instead of as a separate card at the bottom of the page. Allows
  reviewing the list without losing scroll position.
- `CriteriosController` and `CatalogoAdminController` now inject `CatalogoGescom` directly instead
  of the `CatalogoDeCriterios` port, because `actualizadoEn` is a cache concern that does not
  belong in the domain port.

## [0.6.3] - 2026-10-08

### Security
- **Admin login now rate-limited.** 5 failed attempts per username in 15 minutes, then
  `DEMASIADOS_INTENTOS` (429). A successful login resets the counter.
- **Password change requires the current password.** `claveActual` is now mandatory in `ClaveRequest`.
  A stolen session token can no longer change passwords without knowing the current one. The panel
  prompts for it before the new one.
- **Catch-all exception handler.** Unhandled exceptions (missing CIFRADO_KEY, database errors,
  NPEs) now return `ERROR_INTERNO` (500) with a generic message. The stack trace goes to the server
  log, never to the client. Spring's 404 for unknown routes is preserved.
- **`Gescom` record masks the password in `toString()`.** The auto-generated `toString()` would
  have printed the cleartext GESCOM password if the record were ever logged. Now prints `clave=***`.
- **Corrupted `configuracionJson` on a `DescuentoItem` no longer silently yields 0%.** If the
  `descuento` field is missing or not a number, the modifier is now marked `noReconocido()` with
  its raw JSON, instead of passing as a recognized modifier with 0% — which would have made a
  promo disappear without anyone noticing.

### Changed
- Delivery docs (`contrato.md`, `LEEME.md`, `bonificaciones.js`, `guia-de-integracion.md`): the
  API call goes server-side (Node 18+), not from the browser. The `x-api-key` and the discount
  percentage must not be exposed in DevTools. `bonificaciones.js` now accepts a `url` parameter in
  `configurarBonificaciones()` to point at the service from the store's server.

## [0.6.2] - 2026-10-08

### Changed
- Docs only. The contract now states what the store does with a promo's free line
  (`creadaPorPromo: true`): it is shown as a gift, **not charged and not sent to MotorFiscal**; its
  `neto` is informational and summed into nothing. Decided by the user — the contract specifies and
  the store complies, rather than leaving it as an open integration question. Written into
  `docs/guia-de-integracion.md` (and its copy in `entrega-tienda/`), the store `LEEME.md` and
  `bonificaciones.js`. No code change.

## [0.6.1] - 2026-10-08

### Fixed
- **Credentials could not be reloaded after the `CIFRADO_KEY` changed or was lost.** Updating a
  distributor's GESCOM credentials first decrypted the old password, only to read the host and
  realm, so with a new key it failed before saving anything. That made the deploy guide's recovery
  procedure ("reload every distributor's credentials") impossible. It now reads host and realm
  without touching the stored password (`RepositorioDeDistribuidoras.destino`). Found when the local
  key turned out to live only in the memory of a running process. Covered by
  `CredencialesConCifradoNuevoIT` (tag `db`), which fails against the previous code.
- **The installer would have collided with MotorFiscal on the shared server.** MotorFiscal's
  install left `DB_PASSWORD` and `CIFRADO_KEY` as *machine* environment variables;
  `preparar-sistema.ps1` used the same names and "did not overwrite existing ones", so this service
  would have started with MotorFiscal's database password (and failed), and overwriting them would
  have broken MotorFiscal on its next restart. Now the app reads `BONIF_DB_PASSWORD` /
  `BONIF_CIFRADO_KEY` first, and the installer stores them in the **service's own environment**
  (registry `Services\bonificaciones\Environment`), not machine-wide. Verified locally by starting
  the jar with wrong `DB_PASSWORD`/`CIFRADO_KEY` and correct `BONIF_*`: it reads the database and
  decrypts the GESCOM credentials.
- The `PRECIO_Y_LISTA_JUNTOS` notice and the docs said the price list "is still sent because it can
  condition which criterion applies". Verified live that it does not: with an own price, criterion
  610 (conditioned on price list 2) applies the same 12% with no list, list 2 and list 3. The rule
  is now "with your own price, don't send the list".
- `PRECIO_Y_LISTA_JUNTOS` fired on **mixed carts**, where the list *is* used (it prices the items
  without `precioUnitario`), and the docs told the store to remove the list — which would have left
  those items unpriced. It now fires only when every item has a price, i.e. the list was unused.
  Covered by `carritoMixtoConListaNoAvisaNada`.
- **Denial of service through the attempt limiter.** `LimitadorDeIntentos` blocked the whole
  tenant *before* looking at the key: ten requests with made-up keys in five minutes, from anywhere,
  left the store's checkout without discounts even with its valid key. Now the key is validated
  first and the limiter is only consulted for keys that fail: past the limit those get 429 instead
  of 401, and a valid key always passes. A success no longer resets the count (normal store
  traffic would erase an attacker's failures). Per tenant and not per IP on purpose: behind IIS
  every request comes from 127.0.0.1. Covered by `losIntentosAjenosNoBloqueanLaClaveBuena`, which
  fails against the previous code.

### Changed
- **Deploy kit rewritten for the person who installs, who has no repo.** `preparar-sistema.ps1`
  now does the whole service install: checks Windows PowerShell 5.1, Java (full path written into
  the WinSW config, so the service does not depend on PATH), port 8081, SQL Server mixed mode;
  creates the login with a random password and `CHECK_POLICY = ON` from the start (never the weak
  dev password of `crear-base.sql`), checks every `sqlcmd` exit code; copies WinSW from MotorFiscal
  when there is no internet (or downloads a pinned version); installs the jar keeping a versioned
  copy for rollback; starts the service and checks `/health`. Secrets come from a CSPRNG. Re-run
  with `-Jar` to upgrade or roll back; `-OlvidarClaveInicial` removes the initial admin password.
  Also: uses MotorFiscal's SQL Server instance (read from its WinSW config); checks sysadmin and
  outbound HTTPS to GESCOM; prints the secrets **before** starting the service, so a failed first
  start does not lose them; refuses to generate a new `CIFRADO_KEY` over a database that already
  has distributors (`-CifradoKey` to reinstall); backs up the database before every upgrade,
  because the service migrates its schema on start; caps the heap at 768 MB (measured use: ~33 MB)
  since it shares the machine with MotorFiscal. The read-only SQL checks were run in Windows
  PowerShell 5.1 against a local instance.
- `preparar-iis.ps1` checks ARR's proxy **where MotorFiscal's `/api/impuestos` runs** (effective
  value), so it works whether MotorFiscal enabled it server-wide or in its own `web.config`.
  `web.config` stays identical to MotorFiscal's, which already works on that site.
- `preparar-iis.ps1` finds the IIS site by locating MotorFiscal's `/api/impuestos` application, and
  **no longer enables ARR's proxy globally**: if it is off it stops and says so. The virtual path is
  fixed to `api/bonificaciones` (the panel is built for it).
- `entrega-servidor/` is flat and contains only what runs on the server, with a single install
  guide. `deploy.ps1`, the proxy simulator and the build steps stay in the repo.
- `entrega-tienda/`: `bonificaciones.js` calls `/api/bonificaciones` on the store's own domain
  (no dev host, no CORS — same as MotorFiscal), keeps one key per distributor, times out after
  10 s including the body, and `conDescuentos` **never blocks the sale**: it returns 0% with
  `motivoSinDescuento: {codigo, mensaje, hayQueCorregir}`, and logs the errors that will not fix
  themselves (bad key, malformed order, service credentials). The fallback has the same shape as a
  real response. New `lineaDelItem` (compares codes as text): response lines are **not** in cart
  order (verified). New `usarRespuestasDePrueba` to develop against the examples. The store applies
  `lineas[].descuento` to its own line net (the decision recorded on 2026-10-08); the response's
  `netoConDescuento` is documented as a cross-check. The contract documents `supuestos`, condition types,
  nullability, taxes and currency; the store's Postman collection has no admin requests; examples
  01, 04 and 05 were regenerated live.

## [0.6.0] - 2026-10-08

First release that actually reaches the remote. 0.3.0 to 0.5.0 were planned as separate releases
but every push failed until 2026-10-08, so their changes are folded in here. The local `v0.2.0` tag
sits on the commit that introduced the database without bumping the version, so that work is listed
here too and is not part of 0.2.0.

### Breaking
No deployed consumer exists yet, so nothing actually breaks — listed because the contract changed.
- **`/v1/**` now requires `x-api-key`.** It was open: anyone with the URL could read any
  distributor's prices and discounts.
- The `LISTA_PRECIO_NO_ENVIADA` assumption is replaced by `SIN_PRECIO_NI_LISTA` (names the items
  left without a price) and `PRECIO_Y_LISTA_JUNTOS`.
- Default port is **8081**. It shares the server with MotorFiscal, which listens on 8080, and the
  second service to start would die with an `Address already in use` that does not say whose port it is.

### Added
- **Distributors live in SQL Server + Flyway**, not in env vars: ~1000 tenants, and onboarding one
  cannot mean restarting the service. GESCOM passwords are third-party secrets we must read back to
  mint tokens, so they are **encrypted** (AES-256-GCM, `CIFRADO_KEY`), not hashed; without the key
  onboarding fails explicitly. `/health` reports where distributors are read from (`BASE` or
  `CONFIGURACION`) so a deploy that lost its database shows up there.
- **Named panel users** (BCrypt, revocable session token), not a shared password: a shared one
  cannot tell who misconfigured a distributor nor be revoked for one person. The first user is
  created only on an empty table and defaults to `admin`; the name is a convention, not a role.
- **Onboarding tests end to end before saving**: mints a token and pulls the catalogue; if that
  fails nothing is stored. Errors are 400 in plain Spanish, because a mis-pasted password is the
  operator's mistake, not the source's.
- **API keys bound to the route's tenant, with scopes.** dyssa's key on `/v1/senderolaser` is 401,
  not 200 with someone else's data. The checkout key lives in a browser, so `VALORIZACION` can only
  valorize; the full catalogue needs `ADMIN` (403 `ALCANCE_INSUFICIENTE` otherwise). Keys are stored
  as SHA-256, compared in constant time, shown in clear once. Per-tenant `DEMASIADOS_INTENTOS` (429)
  so a 32-hex key cannot be brute-forced silently.
- **`items[].precioUnitario`** (optional, per unit). The store sets its own price and wants our
  discount trace. Verified live that GESCOM's `PrecioUnitario` works and the ERP still applies the
  same criteria, which is why `calculadoPor` stays `ERP` — had it not worked we would have had to
  compute the number ourselves. Mixed carts (some items priced, some not) are valid on purpose;
  unpriced items omit the field (`NON_NULL`) so the ERP uses the list for them.
- **Admin panel at `/admin`**, a Next static export inside the jar (same origin: no CORS, one
  artifact). Screens: onboarding, status, valorisation diagnostic, criteria, users.
- **Valorisation diagnostic** (`POST /admin/v1/distribuidoras/{codigo}/diagnostico/valorizacion`).
  When a store disputes a discount there are three suspects indistinguishable from outside: the ERP,
  our normalisation, the store. It returns all three layers — the request in GESCOM's field names
  (pastes into Postman), the ERP's raw response, ours, and a line-by-line comparison. Unlike the
  public endpoint it does **not** reject incoherent lines: that is the case one needs to look at.
- **Criteria screen** via `GET /admin/v1/distribuidoras/{codigo}/criterios` with the panel session,
  so the panel never stores an `ADMIN` key per distributor in a browser. Shows non-current criteria
  behind a filter ("it worked last week" is usually an expired promo) and unrecognised modifiers raw.
- **Resilience** at the single choke point for GESCOM calls (token minting included): one retry, not
  three — safe because `eval-pedido` is a dry run, but someone is waiting at a checkout. Only
  `FUENTE_NO_DISPONIBLE` is retried; a rejected order neither retries nor counts toward the breaker,
  or one store's integration bug would mark its distributor down for everyone. **Per-tenant circuit
  breaker**, skipped for unsaved credentials. Timeouts are configuration.
- **Per-tenant metrics** (`GET /admin/v1/metricas`) and one log line per call, with no secrets and
  no request body. The domain error code travels from the error handler to the interceptor as a
  request attribute, otherwise the panel would show `HTTP_502` instead of `FUENTE_NO_DISPONIBLE`.
- **Deploy kit** (`scripts/`): system and IIS preparation, WinSW service, `web.config`, `deploy.ps1`
  (builds the production jar, opens it to verify the panel's baked base path, uploads versioned,
  verifies through the proxy) and `simular-proxy-anidado.ps1`, which reproduces the nested-proxy
  topology locally. Secrets stay in machine env vars, never in files that travel by FTP.
- The production jar is a separate artifact (`-PpanelBasePath`, `-prod` classifier): the panel's
  base path is baked at build time, and without the classifier a later local build silently
  overwrote the verified one.
- Postman collection and two self-contained handoff folders: `entrega-tienda/` (checkout
  integration: rules, ES module, contract, eight live examples) and `entrega-servidor/` (first
  deploy; the jar itself is gitignored).

### Fixed
- **Swagger behind the nested proxy** built its URLs against the domain root and would have shown
  "Failed to load remote configuration". Found by the simulator before any deploy. Both
  `swagger-ui.url` **and** `config-url` must be set: springdoc builds `configUrl` with its own
  auto-detection and ignores `url`. `externalBasePath` is a declared `processResources` input, or
  Gradle would not invalidate it when the base path changes.
- The session interceptor covered `/admin/**`, which includes the panel's own files: the login page
  itself would have been 401. Now `/admin/v1/**`. `/admin` forwards (never redirects — a redirect
  builds `Location` from the path this process sees and lands at the domain root behind the proxy).
- The call-recording interceptor ran after the API-key one, and Spring only calls `afterCompletion`
  on interceptors that already passed: **no auth-rejected call was recorded** — exactly the case
  that explains most "it doesn't work" reports. Found live; it now runs first.
- The breaker counts **attempts**, not orders, so with the retry it opened after half the failed
  valorisations its name suggested. Renamed to `intentos-fallidos-para-abrir`, maths in the javadoc.
- `AutenticacionConBaseIT` emptied `distribuidora` and `credencial` using real codes, wiping
  manually onboarded distributors twice and leaving active-looking ones pointing at a closed port.
  Now uses `zzz-test-` codes and deletes only those.
- `bonificaciones.xml` and `web.config` were invalid XML (`--` inside comments). WinSW and IIS would
  have rejected both.

### Notes
- **A free line created by a promo carries the ERP's price in `neto`**, even when the store sent
  `precioUnitario`. `netoConDescuento` is still right (0 on that line), but `totales.neto` mixes both
  prices and a savings figure computed from it is inflated. The handoff module's `ahorro()` splits
  savings into an exact discount amount plus a list of gifts with no amount, since the gateway never
  saw the store's price for an item nobody ordered.
- The item's price list changes the price but **not** which criterion applies (verified with lists
  2 and 3).
- Unverified: `precioUnitario` with `unidadFactor` ≠ 1 (the ERP validates the unit against the
  item), and how two discounts on one line compose.
- A valorisation takes 1.7–2.0 s with everything cached, almost all of it `eval-pedido`.
- The deploy scripts pass the PowerShell parser and the XML validates, but **none has run against
  the real server yet**. Per-credential quotas are deferred until there is real traffic to measure.

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
