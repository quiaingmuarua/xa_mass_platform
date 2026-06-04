# Server API Surface Convergence Roadmap

Status: proposed direction document.

Scope: `xa-mass-server` HTTP endpoint design, public/internal/console boundary,
runtime snapshot exposure, and route-contract guards.

Out of scope:

- control-plane store implementation or H2/SQLite/Redis switching
- task/worker history DB materialization
- trace storage or trace-to-DB ingestion
- broad auth/IAM feature expansion, except where endpoint exposure requires a
  minimal route-boundary decision

Related roadmap:

- [`SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md`](./SERVER_CONTROL_PLANE_STORE_INFRA_CONVERGENCE_ROADMAP.md)
  is separate. Store backing is an infra concern; this roadmap decides whether
  the HTTP route should exist and which surface may consume it.

## Current Code Observations

Current route groups in `xa-mass-server/src/main/java/com/xa/mass/api/internal`:

| Surface | Current routes | Current shape |
| --- | --- | --- |
| Task public API | `/api/v1/tasks`, `/api/v1/tasks/{taskId}`, `/api/v1/tasks/{taskId}/items`, `/api/v1/tasks/{taskId}/items:sync`, `/api/v1/tasks/{taskId}/results`, archive, stage evidence | mixed task shell, runtime append, result read, and review/export reads |
| External worker data-plane | `/worker-api/v1/**` | external worker registration, polling, result submit, capability/state report, command ack |
| Runtime worker API | `/api/v1/runtime/workers/**` | composite worker snapshots, state projections, capability/state report, command request/list/get/ack |
| Runtime diagnostics | `/api/v1/runtime/sessions`, `/api/v1/runtime/sessions:stats`, `/api/v1/runtime/queues`, `/api/v1/runtime/queues/metrics`, `/api/v1/runtime/config/projects` | runtime/session/queue/config snapshots and metrics |
| Catalog/project API | `/api/v1/catalog/**`, `/api/v1/projects/**` | control-plane metadata and capability read views |
| Identity/API-key API | `/api/v1/users`, `/api/v1/roles`, `/api/v1/permissions`, `/api/v1/api-keys`, `/api/v1/api-key-applications`, `/api/v1/submitter-sessions` | server-owned operator and API-key lifecycle |
| Internal review/debug | `/internal/v1/review/tasks/**`, `/internal/v1/debug/task-invocations:sync` | console/review export and debug sync invocation |

Specific current gaps already observed:

- `POST /api/v1/tasks/{taskId}/items:sync` resolves `eventCode` from request or
  `item.eventCode`, but append/review still pass `requestBody.getEventCode()`.
- `/internal/v1/debug/task-invocations:sync` is documented as internal debug,
  but route authorization allows submitter SDK credentials and the controller
  creates, seals, approves, and waits for a task.
- `/api/v1/runtime/workers/**` exposes heavy runtime snapshot and CRUD-shaped
  operations that overlap with `/worker-api/v1/**`.
- `/api/v1/runtime/sessions` and `/api/v1/runtime/queues` expose runtime
  snapshot/detail views before a durable history/read-model owner exists.
- Frontend real API callers already depend on runtime routes such as
  `/api/v1/runtime/workers` and `/api/v1/runtime/config/projects`; `rules.real`
  currently calls `/api/v1/runtime/rules` while the server-owned rule route is
  `/api/v1/admin/rules`. Treat this as caller drift to classify in API-0, not
  as proof that the runtime rules route exists.
- `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` has API dictionary drift and
  needs to be updated together with route changes.

## Owner Review

`xa-mass-server` owns a product HTTP shell and validation host. It may expose
SDK-first task/worker ingress, operator command intent, bounded diagnostics,
and server-owned identity/API-key surfaces.

Runtime truth belongs to engine/runtime/transport owners. Server HTTP must not
turn live runtime state into a broad public CRUD or snapshot contract. Runtime
snapshots are expensive, volatile, and hard to remove once frontend, SDK, or
scripts depend on them.

DB-backed history/review materialization may later justify historical query
endpoints. That is a different owner and proof set. Until history is durable
and query-owned, runtime snapshot endpoints should be console/internal
diagnostics only, and preferably bounded.

Trace/audit remains separate. Trace may explain lifecycle and later feed
materialization, but trace data must not reverse-drive runtime endpoint design.

Server API design should bias toward a Telegram-like style: small, direct
methods over stable concepts, a simple envelope, and plain request/response
objects with obvious field meaning. It should not multiply DTO/viewer/snapshot
layers for the same object just to look safer. Extra wrapping is justified only
when it changes caller contract, ownership, cost boundary, or security shape.

`Snapshot` and `viewer` names are warning signs in server API work. They are
often historical debt from console convenience or runtime debugging. Before
keeping or adding one, prove the endpoint has product value, bounded cost, and
a clear owner distinct from live runtime truth.

## Boundary Decision

Classify every server route into one of these categories before changing route
behavior:

| Category | Meaning | Target rule |
| --- | --- | --- |
| `public-sdk-ingress` | external task/worker caller action required to run work | keep public; route must express intent, not CRUD |
| `public-sdk-read` | bounded owner-scoped result or metadata read | keep only when value and cost are explicit |
| `operator-command` | operator intent to control a task/worker | keep if it maps to a command owner, not direct runtime mutation |
| `console-diagnostics` | current runtime or console view | move behind console/internal route and bound cost |
| `internal-debug` | test/debug helper | internal/operator-only; not SDK contract |
| `remove-or-merge` | duplicate, stale, or value-weak endpoint | remove after callers are retargeted |

Initial target classification proposal:

| Route family | Proposed target |
| --- | --- |
| `POST /api/v1/tasks` | `public-sdk-ingress` |
| `POST /api/v1/tasks/{taskId}/items` | `public-sdk-ingress` |
| `POST /api/v1/tasks/{taskId}/items:sync` | `public-sdk-ingress`; fix resolved-event contract |
| `GET /api/v1/tasks/{taskId}/results` and archive routes | `public-sdk-read` only if backed by bounded result runtime/archive owner |
| `GET /api/v1/tasks` | classify in API-0; likely console/review read rather than broad public runtime list |
| `GET /api/v1/tasks/{taskId}` | classify in API-0; task shell read may remain, but avoid item/runtime snapshots |
| `PATCH /api/v1/tasks/{taskId}` | `operator-command` or pre-dispatch definition patch; not generic CRUD |
| `POST /api/v1/tasks/{taskId}/commands` | `operator-command` |
| `/worker-api/v1/**` | `public-sdk-ingress` for external workers |
| `/api/v1/runtime/workers/{workerId}/capability-reports` and `state-reports` | `remove-or-merge`; worker self-report belongs to `/worker-api/v1/**` |
| `/api/v1/runtime/workers/{workerId}/commands` | `operator-command` if kept |
| `/api/v1/runtime/workers/{workerId}/commands/{commandId}/ack` | `remove-or-merge`; worker ack belongs to `/worker-api/v1/**` |
| `/api/v1/runtime/workers`, `/workers/states`, command list/get | `console-diagnostics`; bound or internalize |
| `/api/v1/runtime/sessions`, `/runtime/queues` | `console-diagnostics`; prefer stats/metrics over full detail |
| `/internal/v1/debug/task-invocations:sync` | `internal-debug`; operator-only |
| `/internal/v1/review/tasks/**` | `console-diagnostics` or review/export read model, not runtime truth |

## Hard Rules

1. Do not expose live runtime snapshots as stable public API unless the endpoint
   has an explicit caller value and bounded cost.
2. Do not add CRUD-shaped runtime task or worker endpoints. Runtime mutation must
   be expressed as task/worker command intent or external worker data-plane
   ingress.
3. Do not treat current runtime views as historical data APIs. Historical query
   routes wait for DB-backed materialization or explicit review/export owner.
4. Do not let console convenience become SDK/public contract by default.
5. Do not preserve removed internal routes through aliases after all in-repo
   callers are retargeted.
6. Do not solve endpoint-value problems by adding permissions first. Route value
   and owner must be decided before auth expansion.
7. Do not let trace or audit endpoints redefine runtime ownership.
8. Do not duplicate model layers by default. Avoid separate `View`, `Viewer`,
   `Snapshot`, `Dto`, and wrapper variants for the same concept unless the
   contract boundary is materially different.
9. Prefer flat, explicit API objects. Avoid deep nesting and pass-through
   wrappers that hide the real task, worker, command, result, or diagnostic
   fields.
10. Every endpoint review must include performance cost: cardinality, scan
    behavior, live runtime reads, fan-out calls, payload size, and whether the
    endpoint can be bounded without changing its value.

## Do Not Start With

Do not start by deleting `/api/v1/runtime/**` routes or moving paths blindly.
First inventory in-repo callers, classify each endpoint, and decide whether it
is public SDK ingress, operator command, console diagnostics, internal debug, or
remove/merge.

Do not start with store mode, Redis, SQLite, or route auth table expansion. Those
are follow-up concerns after the endpoint surface is narrowed.

## API-0: Route Inventory And Value Classification

Goal: produce an executable route inventory from current code.

Scope:

- enumerate all `@RequestMapping`, `@GetMapping`, `@PostMapping`,
  `@PatchMapping`, and `@DeleteMapping` routes under `xa-mass-server`
- separate production routes from test fixtures
- classify every route using the categories in this roadmap
- record the current authorization mode for every route:
  SDK credential bypass, operator-only, named permission, SDK-or-operator, or
  no current catalog entry
- explicitly flag any `/internal/v1/**` or `/api/v1/runtime/**` route that is
  currently reachable with SDK submitter credentials
- score every route for product value and performance risk before proposing
  permission or infra changes
- record current known callers: Java SDK, scenario launcher, frontend console
  (`frontend/src/api/**`), server E2E tests, direct scripts, and worker-pack
  samples
- record active caller drift, including frontend calls to routes that do not
  currently exist or routes whose owner changed, before retargeting any caller
- flag unbounded runtime snapshot and duplicate mutation paths
- flag duplicate model shapes, especially `View`, `Viewer`, `Snapshot`, and
  controller-local response objects that restate an existing public contract

Acceptance:

- a route inventory table exists beside this roadmap or inside the roadmap
- each route has exactly one target category
- each route has a current auth mode and target auth owner recorded
- each route has a value/performance classification: keep, bound, internalize,
  merge, or remove
- every frontend real API route under `frontend/src/api/**` is mapped to a
  current server route, a retarget route, or an explicit stale-caller finding
- every `remove-or-merge` route names a retarget path or states that no current
  caller should exist
- duplicated model shapes are listed with an owner decision: keep as contract,
  flatten, merge, or delete
- no code behavior changes in this slice

Verification candidates:

```powershell
rg -n "@RequestMapping|@(GetMapping|PostMapping|PatchMapping|DeleteMapping)" xa-mass-server/src/main/java -g "*.java"
rg -n "/api/v1/runtime|/worker-api/v1|/internal/v1|items:sync|task-invocations:sync" xa-mass-server integrations sdk frontend -g "*.java" -g "*.md" -g "*.mjs" -g "*.ts" -g "*.tsx"
rg -n "SDK_CREDENTIAL_BYPASS|OPERATOR_AUTH_ONLY|SDK_OR_OPERATOR_ROUTE|ApiPermissionNames" xa-mass-server/src/main/java/com/xa/mass/api/auth/ApiRouteAuthorizationCatalog.java
rg -n "requestApiData<|/api/v1/" frontend/src/api -g "*.ts" -g "*.tsx"
rg -n "class .*View|class .*Viewer|class .*Snapshot|record .*View|record .*Snapshot|Dto|DTO" xa-mass-server/src/main/java sdk integrations -g "*.java"
```

## API-1: Route Boundary Guard

Goal: make route additions fail code review when they bypass classification.

Scope:

- add or extend a server architecture guard that scans controller route
  annotations
- require each server route family to appear in an allowlist/category map
- make the API-0 inventory the route-category owner truth; any hard-coded
  guard table must be mechanically cross-checkable against the inventory route
  list, so the guard does not become a second drifting source of truth
- fail if new `/api/v1/runtime/**` routes are added without an explicit
  `console-diagnostics` or `operator-command` category
- fail if new `/internal/v1/**` routes allow SDK submitter credentials without
  an explicit exception
- fail if `/worker-api/v1/**` routes are duplicated under `/api/v1/runtime/**`
  as worker self-report or worker result callback APIs

Acceptance:

- guard covers current controller main sources
- guard error message points to this roadmap or the active API inventory
- guard fails if a controller route is missing from the API-0 inventory or if an
  inventory route has no matching controller route except documented removed or
  stale-caller rows
- existing routes pass only because they are categorized, not because the guard
  ignores runtime routes

Verification candidates:

```powershell
mvn -pl xa-mass-server -Dtest=ServerMainSourceArchitectureGuardTest test
```

## API-2: Fix Current Contract Bugs Before Surface Moves

Goal: remove high-risk contract mismatches before route movement creates extra
noise.

Scope:

- fix `items:sync` so append and review use the resolved single `eventCode`
- add a successful test for `items:sync` with only `item.eventCode`
- make `/internal/v1/debug/task-invocations:sync` operator-only or record a
  deliberate owner decision if it is promoted out of internal debug
- remove or explicitly reject the current SDK credential bypass branch for this
  internal debug route unless API-0 records a deliberate non-debug public route
  decision
- update `INTERNAL_API_REFERENCE.md` for these current facts

Acceptance:

- `items:sync` validates and appends the same resolved event identity
- internal debug route no longer behaves like an undocumented public SDK invoke
  route
- internal debug controller behavior is tested, not only the auth interceptor
  route table
- no new invoke-style public route is added in this slice

Verification candidates:

```powershell
mvn -pl xa-mass-server -Dtest=TaskApiControllerTest,InternalDebugTaskInvocationControllerTest,ApiAuthInterceptorTest test
```

## API-3: Downgrade Runtime Snapshot Routes To Console Diagnostics

Goal: prevent live runtime views from becoming stable public API.

Scope:

- retarget frontend callers of heavy runtime views to an explicit
  console/internal diagnostics surface, or keep the existing route only if the
  inventory proves it is already the accepted console route
- fix or classify frontend caller drift discovered in API-0, including
  frontend calls to `/api/v1/runtime/rules` when the current server rule route
  is `/api/v1/admin/rules`
- review `/api/v1/runtime/workers`, `/workers/states`, command list/get,
  `/sessions`, and `/queues`
- prefer aggregate stats/metrics over full list/detail where product value is
  unclear
- add bounds, filters, or explicit internal-only placement for any remaining
  full snapshot endpoints
- remove or flatten viewer/snapshot response wrappers where the only purpose is
  to repackage another live runtime object

Acceptance:

- runtime snapshot routes are no longer described as public SDK/API contract
- any remaining snapshot endpoint has a documented operator/console caller,
  bounded cost rule, and no SDK usage
- Java SDK docs and quickstarts do not advertise runtime snapshot routes
- remaining diagnostics response models are intentionally named and do not
  pretend to be durable history or public domain objects

Verification candidates:

```powershell
rg -n "/api/v1/runtime" sdk integrations xa-mass-server/src/main/resources xa-mass-server/doc -g "*.md" -g "*.java" -g "*.mjs"
mvn -pl xa-mass-server -Dtest=ApiAuthInterceptorTest,ServerMainSourceArchitectureGuardTest test
```

## API-4: Merge Duplicate Worker Runtime Mutation Paths

Goal: keep worker data-plane ingress and operator command intent separate.

Scope:

- keep external worker self-report, poll, result submit, and command ack under
  `/worker-api/v1/**`
- keep operator command request only if it expresses owner-backed command
  intent
- remove or retarget `/api/v1/runtime/workers/{workerId}/capability-reports`,
  `/state-reports`, and `/commands/{commandId}/ack` if API-0 confirms they are
  duplicate worker self-report paths
- update tests and docs to use the surviving owner path

Acceptance:

- worker self-report and callback routes have one owner path
- operator command routes do not directly mutate worker runtime state
- no compatibility aliases remain inside the repo after callers move

Verification candidates:

```powershell
mvn -pl xa-mass-server -Dtest=ExternalWorkerApiControllerTest,ApiAuthInterceptorTest test
mvn -pl xa-mass-server -Dtest=ExternalWorkerPollingApiIntegrationTest test
```

## API-5: Public Task Read Surface Narrowing

Goal: keep task reads useful without turning runtime state into broad list
snapshots.

Scope:

- classify `GET /api/v1/tasks` and `GET /api/v1/tasks/{taskId}` separately
- keep task shell/detail reads only if they remain shell-oriented and bounded
- avoid returning item payload snapshots by default
- keep result reads/archive behind result-runtime or archive owner, not review
  rows unless explicitly documented as review/export materialization
- decide whether list tasks belongs to console/review read model rather than
  public SDK API

Acceptance:

- public task read routes document their owner: shell, result runtime, or
  review/export materialization
- list routes are bounded or moved to console/review surface
- SDK examples do not imply broad runtime task browsing as the normal caller
  path

Verification candidates:

```powershell
mvn -pl xa-mass-server -Dtest=TaskApiControllerTest test
rg -n "GET /api/v1/tasks|/api/v1/tasks" sdk integrations xa-mass-server/doc -g "*.md" -g "*.java"
```

## API-6: Documentation And Residue Cleanup

Goal: make docs, tests, and guards match the narrowed API surface.

Scope:

- update `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`
- update `xa-mass-server/README.md`, SDK quickstarts, and integration docs only
  where route behavior changed
- remove stale route references such as removed worker-context paths if still
  present
- add route vocabulary rules: public SDK ingress, operator command, console
  diagnostics, internal debug
- add model vocabulary rules: avoid duplicate viewer/snapshot layers, prefer
  flat Telegram-like request/response shapes, and justify wrappers by real
  contract boundaries
- run a residue scan for stale runtime CRUD and view-snapshot vocabulary

Acceptance:

- no active docs advertise removed or internal-only routes as public API
- route guard and docs agree on route categories
- no test fixture preserves a removed route as a hidden compatibility path

Verification candidates:

```powershell
rg -n "workers/.*/contexts|task-invocations:sync|/api/v1/runtime/workers/.*/capability-reports|/api/v1/runtime/workers/.*/state-reports|/api/v1/runtime/workers/.*/commands/.*/ack" . -g "*.md" -g "*.java" -g "*.mjs"
mvn -pl xa-mass-server test
```

## Suggested Implementation Order

1. API-0 route inventory and classification.
2. API-1 route boundary guard.
3. API-2 current contract fixes.
4. API-3 runtime snapshot downgrade.
5. API-4 duplicate worker mutation path merge.
6. API-5 task read narrowing.
7. API-6 docs and residue cleanup.

## Open Decisions

1. Whether `GET /api/v1/tasks` is a public SDK read or console/review read.
2. Whether `GET /api/v1/tasks/{taskId}` is acceptable as bounded shell detail
   for SDK submitters, or should be split into shell-only public and richer
   console detail.
3. Whether console diagnostics should stay under `/api/v1/runtime/**` with
   operator-only route category, or move under `/internal/v1/diagnostics/**`.
4. Whether worker command list/get is a real operator workflow now or should
   wait for durable command/history materialization.

## Execution Readiness

This roadmap is executable after API-0 completes. API-0 is intentionally the
first slice because the endpoint surface is broad and current docs/tests may
encode stale or convenience-only route assumptions.
