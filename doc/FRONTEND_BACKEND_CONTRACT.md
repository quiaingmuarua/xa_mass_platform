# Frontend Backend Contract

Status: current cross-owner handoff for server/frontend integration.

This document is the shared maintenance contract between `xa-mass-server` and
`frontend`. It records ownership and change rules. It is not an API dictionary;
route-level details still belong in `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`
current code, and the live server docs exposed at `/doc.html#/home`.

## Baseline

The project remains server + SDK first.

- kernel/core owns correctness, lifecycle semantics, scheduling correctness,
  and runtime truth boundaries
- server owns the reference host, product/API host, auth/session/CSRF, API
  boundary, server-local control-plane resources, API docs exposure, and
  backend-hosted console assembly
- SDK owns the main external edit, integration, and automation surface for
  task producers, worker registration/session, and typed platform access
- frontend owns observation, validation, presentation, and a lightweight
  operator console over server-owned contracts
- backend owns HTTP behavior, authorization, control-plane storage semantics,
  worker/task/resource truth, and public contract DTO shape
- SDK and public-contract modules own stable external integration surfaces
- frontend owns control-console presentation, route/menu UX, mock preview
  adapters, and browser-side auth state
- frontend consumes backend contracts; it must not invent replacement kernel,
  permission, worker, task, catalog, or auth truth

Frontend is not the main editing surface. Its primary optimization target is:

- clear information architecture
- accurate task, worker, runtime, auth, and product/API host state presentation
- explicit mock/backend mode behavior
- closed auth/session/CSRF UX loops
- meaningful dashboard metrics
- task and worker detail pages that help operators debug current behavior
- audit, API-key, user, and role pages that demonstrate server product/API
  maturity
- professional loading, empty, error, retry, and unavailable states
- API documentation/static snapshot presentation as a contract review surface,
  not as a frontend-maintained API dictionary

Current primary local integration URL:

- `http://localhost:8088/` is the backend-hosted console served from
  `frontend/dist`
- `http://localhost:8088/system/api-reference` is the console entry for the
  live server-generated API docs
- `http://localhost:5174` is Vite dev only and may run with mock API/auth

## Owner Boundaries

| Area | Owner | Rule |
| --- | --- | --- |
| Task lifecycle, worker selection, scheduling, result convergence | backend/core | frontend displays read models and command outcomes only |
| HTTP route behavior and authorization | `xa-mass-server` | frontend must call documented server routes through `src/api/*` |
| Public wire DTO/constants shared with SDKs | `sdk/xa-mass-public-contract` | do not duplicate Controller-exposed shapes in frontend as independent truth |
| External task/worker automation | `sdk/xa-mass-java-sdk` and integrations | frontend must not become the worker/task SDK substitute |
| Console pages, shell, route meta, menu visibility | `frontend` | backend must not drive menu schema or page DSL |
| Frontend mock data | `frontend/src/api/*.mock.ts` | mock data is preview/test support, not server seed truth |
| Auth enforcement | backend | frontend permission checks are UX only |

## API Change Rules

When backend changes a route, response shape, auth behavior, or permission:

1. update the server controller/service tests that own the behavior
2. update `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` when the route contract
   changes
3. update `sdk/xa-mass-public-contract` when the changed shape is Controller
   exposed and reused by external SDKs
4. update the matching frontend `src/api/*.real.ts` adapter and type file
5. update frontend tests for adapter behavior or page behavior affected by the
   change

When frontend needs data or an action that does not exist:

1. do not add inline `fetch`, route aliases, mock-only production logic, or
   frontend-only permission strings
2. write the backend contract requirement first: route owner, caller, auth,
   cost/cardinality, DTO shape, and proof surface
3. implement the backend/server contract before wiring the frontend real adapter
4. keep frontend mock support aligned with the real adapter shape

## Auth Contract

Browser auth mode discovery is:

- `GET /api/v1/auth/config`

Frontend backend auth code lives under:

- `frontend/src/auth/backend-auth.ts`
- `frontend/src/auth/provider.backend.ts`

Rules:

- operator auth mode is backend-owned
- `dev-header` is local/test convenience only
- `session` uses HttpOnly session cookie plus CSRF token for mutating operator
  routes
- frontend must not persist operator passwords, API-key raw secrets, CSRF tokens
  outside the existing auth runtime boundary, or internal API-key viewer
  credentials as user-facing concepts
- API-key viewer credentials use API-key credential APIs and must not
  attach operator session assumptions

## Frontend API Adapter Rules

- all backend calls go through `frontend/src/api/*`
- pages do not call `fetch` directly
- runtime config comes from `frontend/src/app/config.ts`
- mock/real split is `*.mock.ts`, `*.real.ts`, and a thin selecting module
- frontend route permissions use backend permission strings in `domain:action`
  form; do not create frontend-only aliases
- project/event catalog discovery goes through `src/api/catalog.ts`
- `Worker.eventBindings` is the capability truth exposed to the UI;
  `supportedEventCodes` and `supportedProjects` are derived display/filter
  helpers only
- worker lifecycle and catalog coverage DTOs must use backend-owned,
  source-labeled fields. Frontend may display `runtimeStatus`, `reachability`,
  `reachable`, `declaredWorkerIds`, `reachableWorkerIds`,
  `hasReachableWorkerCoverage`, `hasInvocationCoverage`,
  `reachableWorkerCountsByTransport`, `runtimeStatusCounts`, and
  `reachableUnlockedWorkerCount`, but it must not combine them into a local
  scheduler eligibility predicate.
- task detail result previews must read public result rows from
  `/api/v1/tasks/{taskId}/results`; server-local review rows may populate seed
  preview and export support, but they are not the runtime result read source.

## Backend Route Guardrails For Console Needs

Console convenience is not enough reason to add broad runtime endpoints.

Before adding a console-facing route, classify:

- route owner: public SDK ingress, public SDK read, operator command, console
  diagnostic, internal debug, or remove/merge
- expected caller: frontend operator, API-key viewer, SDK, integration test,
  or internal support
- auth and permission
- cardinality and scan cost
- payload size and pagination/filter needs
- whether the route exposes runtime truth, control-plane truth, or server-local
  review materialization

Do not add:

- CRUD-shaped runtime worker/task mutation endpoints
- scan-heavy worker/runtime reconciliation routes for page convenience
- compatibility aliases for stale frontend callers
- backend-driven menu/page schemas

## Verification

Backend contract changes that affect frontend should include at least one of:

- server controller/service test for the route behavior
- frontend adapter test for the real API shape
- page test when UX behavior changes
- Boot-shell or E2E proof for startup/auth/profile-sensitive behavior

Recommended frontend verification after cross-boundary changes:

```powershell
cd frontend
corepack pnpm test:run
corepack pnpm typecheck
corepack pnpm build
```

Recommended server verification depends on the touched owner. For HTTP route
changes, include the relevant server controller/integration test and avoid
claiming frontend success from mock-only tests.
