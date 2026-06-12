# Server Catalog Worker Capability API Category Roadmap

Status: successor roadmap, not part of WES mainline implementation.

Parent:

- `roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_ROADMAP.md`

## Purpose

Resolve the remaining caller/auth/category mismatch around
`GET /api/v1/catalog/worker-capabilities`.

WES source-labels worker lifecycle fields on catalog and runtime worker
surfaces. It does not decide whether `worker-capabilities` should stay a
diagnostic route that happens to allow SDK credential bypass, become a public
SDK read contract, or split into separate public catalog and operator
diagnostic routes.

## Current Problem

`xa-mass-server/doc/API_SURFACE_INVENTORY.md` currently classifies:

- `GET /api/v1/catalog/event-capabilities` as `public-sdk-read`,
- `GET /api/v1/catalog/worker-group-capabilities` as `public-sdk-read`,
- `GET /api/v1/catalog/worker-capabilities` as `console-diagnostics` with
  SDK credential bypass.

That last row is source-labeled after WES, but the caller/category decision is
still unresolved. Keeping it unresolved inside WES would mix vocabulary cleanup
with broader API contract policy.

## Scope

- Inventory real callers of `/api/v1/catalog/worker-capabilities`:
  frontend pages, SDK users, integration tests, API-key viewer flows, and
  operator diagnostics.
- Decide one target:
  - keep route as operator console diagnostic and remove SDK credential bypass,
  - promote a bounded public SDK read DTO with public-contract ownership,
  - split public WorkerGroup capability catalog from operator worker instance
    diagnostics.
- If promoted or split, define auth, permission, response bound, DTO owner,
  SDK/public-contract impact, and frontend adapter migration.
- Keep worker-runtime scheduling and CES candidate/admission mechanics out of
  scope.

## Acceptance

- `API_SURFACE_INVENTORY.md` route category and auth mode no longer disagree.
- `INTERNAL_API_REFERENCE.md` documents the chosen caller and source labels.
- Frontend adapters consume the chosen route(s) without aliases.
- Public Java SDK is updated only if a real public SDK read contract is chosen.
- Tests cover route auth/category and touched DTO/frontend adapters.

## Suggested Verification

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dtest=CatalogControllerTest,ApiAuthInterceptorTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
cd frontend
corepack pnpm test:run -- catalog RuntimeDiscoveryPage ProjectsPage ProjectDetailPage
corepack pnpm typecheck
```
