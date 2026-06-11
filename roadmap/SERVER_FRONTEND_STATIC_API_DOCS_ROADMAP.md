# Server Frontend Static API Docs Roadmap

Status: proposed direction document.

## Current Code Observations

- `xa-mass-server` owns the active OpenAPI surface through Springdoc/Knife4j:
  `/v3/api-docs` exports JSON and `/doc.html` serves the browser UI.
- `xa-mass-server/src/main/java/com/xa/mass/api/config/WebMvcConfig.java`
  currently applies `ApiAuthInterceptor` only to `/api/v1/**` and
  `/internal/v1/**`; `/doc.html` and `/v3/api-docs` are not part of that
  interceptor path.
- `frontend/src/pages/system/api-reference/ApiReferencePage.vue` currently
  embeds `VITE_API_DOCS_URL` with an iframe and suppresses the default local
  `/doc.html#/home` iframe only in mock preview mode.
- Vercel is a frontend-only review surface. It does not run the Java server,
  SQLite control-plane storage, Redis runtime, worker registration, or live
  Knife4j endpoint.
- `doc/FRONTEND_BACKEND_CONTRACT.md` already records the main boundary:
  frontend consumes backend contracts and must not become a second API,
  auth, permission, DTO, task, worker, or catalog owner.

## Owner Review

The backend owns HTTP route behavior, authorization, OpenAPI schema generation,
and route/DTO classification.

The frontend owns presentation of a static API documentation snapshot for
review/demo use. It may display or link to generated docs, but it must not
define the API dictionary, hand-write DTO truth, or infer permissions from
frontend convenience.

Vercel owns only static frontend preview. Any API docs visible on Vercel are a
generated snapshot, not a live server contract proof.

## Boundary Decision

Replace the iframe-backed `System -> API Reference` page with a static docs
snapshot flow:

- server/OpenAPI remains the source of truth
- docs generation is explicit, not implicit on every Java compile
- generated frontend docs are static preview assets under frontend ownership
- backend live docs exposure is secured or intentionally classified before the
  static snapshot is used as a public review surface

## Target Shape

- A documented command generates a sanitized static API docs snapshot from the
  backend OpenAPI export.
- The generated snapshot is written to a frontend static asset location such as
  `frontend/public/api-docs/`.
- `System -> API Reference` no longer embeds a backend iframe. It links to or
  routes into the local static docs page.
- Vercel preview can show the static docs snapshot without a live backend.
- Backend-hosted local console can still open live `/doc.html` when explicitly
  useful, but live docs are not the frontend preview dependency.
- Backend `/doc.html` and `/v3/api-docs` exposure is classified and tested:
  public demo, operator-only, or profile-gated.

## Hard Rules

- Do not hand-write API route dictionaries, DTO field catalogs, auth rules, or
  permission semantics in frontend source.
- Do not use iframe embedding as the durable API docs strategy.
- Do not make Java compilation start Spring Boot or rewrite frontend files by
  default.
- Do not expose internal/debug routes in a public/static docs snapshot without
  an explicit owner decision.
- Do not add frontend-only permission names to protect docs.
- Do not add scan-heavy backend routes for UI documentation convenience.
- Backend route/shape/auth changes must still update server tests, public
  contract/SDK surfaces when relevant, and frontend adapters when they consume
  the route.
- Generated static docs are review/demo artifacts. They are not proof that the
  live backend currently serves the same contract unless the generation command
  or CI guard has run.

## Non-Goals

- Do not generate a broad OpenAPI client for the Java SDK or frontend.
- Do not publish a commercial-ready public API portal in this roadmap.
- Do not move backend API ownership into frontend.
- Do not solve all OpenAPI annotation gaps in one slice.
- Do not make Vercel run the Java backend or connect to production storage.

## Do Not Start With

Do not start by polishing the iframe page. The first risk is ownership and
exposure: decide which backend routes can appear in a static public/review
snapshot and how live `/doc.html` / `/v3/api-docs` are protected.

## SAD-0 Inventory And Exposure Decision

Scope:

- Review the archived first inventory at
  `doc/archive/xa-mass-server/2026-06-11_SERVER_FRONTEND_STATIC_API_DOCS_INVENTORY.md`.
  If this roadmap becomes active implementation work again, create a fresh
  current-code inventory from verified source rather than moving the archived
  inventory back unchanged.
- Inventory current docs surfaces:
  `/doc.html`, `/v3/api-docs`, `frontend System -> API Reference`,
  `VITE_API_DOCS_URL`, Vercel preview behavior, and existing Markdown API
  references.
- Classify route groups in the OpenAPI export:
  public SDK ingress, public SDK read, operator command, console diagnostic,
  internal debug, worker API, health/docs tooling, and remove/merge.
- Decide live docs exposure:
  public demo, operator-only, profile-gated, or local-only.
- Decide static snapshot exposure:
  public/review-safe, authenticated-console-only, or split public/internal
  snapshots.

Acceptance:

- Inventory records every current docs surface and its owner.
- Inventory records whether `/doc.html` and `/v3/api-docs` require backend auth
  or remain intentionally public/local.
- Inventory records which route categories may appear in the Vercel static
  snapshot.
- Inventory records whether Markdown `INTERNAL_API_REFERENCE.md` remains a
  narrative/index contract or is demoted behind generated OpenAPI for field
  details.
- No code behavior changes are required in this slice.

Verification:

```powershell
rg -n "doc.html|v3/api-docs|api-reference|VITE_API_DOCS_URL|FRONTEND_BACKEND_CONTRACT" xa-mass-server frontend doc roadmap -g "*.md" -g "*.java" -g "*.ts" -g "*.vue" -g "*.yml"
```

## SAD-1 Backend Live Docs Auth And Classification

Scope:

- Implement the SAD-0 decision for `/doc.html` and `/v3/api-docs`.
- If docs are operator-only, protect both endpoints in backend routing/security
  and prove anonymous access fails.
- If docs remain public/local-only, encode that as an explicit server config
  or profile decision and test it.
- Update server docs so live docs exposure is not described as an accidental
  demo surface.

Acceptance:

- Backend live docs exposure matches the SAD-0 decision.
- A server test proves anonymous and authenticated behavior for `/doc.html`
  and `/v3/api-docs`.
- `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` no longer has ambiguous
  `Demo` wording for docs exposure.
- Frontend route permissions do not invent a new permission string; they either
  remain authenticated-only by decision or reuse an existing backend permission.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=*Api*Auth*Test,*OpenApi*Test,*WebMvc*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## SAD-2 Static Snapshot Generator

Scope:

- Add an explicit generation command that reads backend OpenAPI JSON and writes
  frontend static docs assets.
- Prefer input from a running backend URL or a checked-in OpenAPI JSON export;
  do not make ordinary Java compile start Spring Boot.
- Write generated assets under a frontend-owned static location such as
  `frontend/public/api-docs/`.
- Generate at least:
  - `openapi.json`
  - `index.html` or equivalent static renderer
  - a small metadata file with source URL/version/generated time
- Keep the generator dependency footprint small and agent-friendly.

Acceptance:

- The generator is reproducible from a documented command.
- Generated docs can be served by Vite/Vercel without a backend.
- The generated snapshot does not include route categories excluded by SAD-0.
- The frontend build does not require a live backend.
- The generated output location is documented in `frontend/README.md` and
  `doc/FRONTEND_BACKEND_CONTRACT.md`.

Verification:

```powershell
cd frontend
corepack pnpm build
corepack pnpm test:run
```

## SAD-3 Frontend API Reference Page Convergence

Scope:

- Replace iframe embedding with a static docs snapshot entry.
- `System -> API Reference` should route to or link to
  `/api-docs/index.html` when static docs are present.
- Keep a clear unavailable state when the static snapshot has not been
  generated.
- Remove or narrow `VITE_API_DOCS_URL` if it is no longer the main strategy;
  if retained, classify it as an external fallback link only.
- Add frontend tests for static docs availability and fallback behavior.

Acceptance:

- `ApiReferencePage.vue` does not render an iframe for backend docs.
- Vercel/mock preview displays or links to the static snapshot without a
  backend.
- Backend-hosted console can open static docs from the same frontend asset
  path.
- The page text makes clear the snapshot is generated from server OpenAPI and
  is not frontend-owned API truth.

Verification:

```powershell
cd frontend
corepack pnpm typecheck
corepack pnpm test:run
corepack pnpm build
```

## SAD-4 Drift Guard And CI Proof

Scope:

- Add a residue/drift check so backend route/schema changes do not silently
  leave the static docs stale.
- Guard against frontend source becoming an API dictionary.
- Guard against iframe docs reappearing as the main API Reference strategy.
- Add a review command for Vercel preview static docs behavior.

Acceptance:

- CI or a local verification command detects stale generated OpenAPI snapshot
  after backend route/schema changes.
- A scan fails or reports if frontend pages/adapters contain inline `fetch`
  route dictionaries outside `frontend/src/api/*`.
- A scan fails or reports if `ApiReferencePage.vue` reintroduces a backend docs
  iframe.
- `doc/FRONTEND_BACKEND_CONTRACT.md`, `frontend/AGENTS.md`, and
  `frontend/README.md` agree on the static snapshot flow.

Verification:

```powershell
rg -n "<iframe|VITE_API_DOCS_URL|/doc.html#/home" frontend/src/pages/system/api-reference frontend/src/app frontend/README.md
rg -n "fetch\\(|/api/v1/|/worker-api/v1/|/internal/v1/" frontend/src -g "*.vue" -g "*.ts"
git diff --check
```

## Suggested Implementation Order

1. SAD-0: inventory and exposure decision.
2. SAD-1: backend live docs auth/classification.
3. SAD-2: explicit static docs generator.
4. SAD-3: frontend page convergence away from iframe.
5. SAD-4: stale snapshot and boundary guards.

## Roadmap Completion Criteria

- Backend live docs exposure is intentional and tested.
- Static frontend API docs are generated from server OpenAPI, not hand-written.
- Vercel can show API docs without a live Java backend.
- Frontend API Reference no longer depends on iframe embedding.
- Boundary docs and owner READMEs route future agents to the static snapshot
  generation flow.
- Residue scans do not find frontend-owned API dictionaries, backend docs
  iframe dependency, or stale active docs claims.
