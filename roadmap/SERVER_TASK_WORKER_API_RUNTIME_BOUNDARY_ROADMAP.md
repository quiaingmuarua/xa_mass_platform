# Server Task Worker API Runtime Boundary Roadmap

Status: proposed convergence roadmap.

This roadmap converges the server-facing task and worker HTTP/read-model
surfaces so callers can distinguish:

- control-plane / DB-shaped declaration and shell facts
- Redis/runtime current truth
- result-runtime truth
- server-local review materialization
- transport/session evidence
- catalog/WorkerGroup capability truth
- intentionally composite console diagnostics

It also records the first worker registration DB direction: worker registration
DB rows are analysis/audit/control-plane observation output only. They must not
restore runtime state, scheduling candidates, online status, or transport
presence on server startup.

Read with:

- [TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_ROADMAP.md](./TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_ROADMAP.md)
- [TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_INVENTORY.md](./TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_INVENTORY.md)
- [SERVER_TASK_WORKER_API_RUNTIME_BOUNDARY_INVENTORY.md](./SERVER_TASK_WORKER_API_RUNTIME_BOUNDARY_INVENTORY.md)
- [../doc/INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [../doc/FRONTEND_BACKEND_CONTRACT.md](../doc/FRONTEND_BACKEND_CONTRACT.md)
- [../xa-mass-server/doc/API_SURFACE_INVENTORY.md](../xa-mass-server/doc/API_SURFACE_INVENTORY.md)
- [../xa-mass-server/doc/INTERNAL_API_REFERENCE.md](../xa-mass-server/doc/INTERNAL_API_REFERENCE.md)
- [../xa-mass-server/README.md](../xa-mass-server/README.md)
- [../frontend/AGENTS.md](../frontend/AGENTS.md)

## Current Code Observations

- `TaskApiController` exposes `/api/v1/tasks` list/detail/create/update/item
  ingress/result routes. List and detail return `ApiTask`, a single response
  shape that currently mixes shell fields, lifecycle/current aggregate fields,
  counters, execution aliases, timestamps, and compatibility aliases.
- `TaskApiController` already exposes result-runtime reads separately through
  `/api/v1/tasks/{taskId}/results` and archive routes.
- `InternalTaskReviewController` already exposes server-local review/export
  materialization separately through `/internal/v1/review/tasks/**`.
- `TaskApiController.listTasks(...)` has bounded list parameters, but
  `status` queries still call `getTaskSummariesByStatus(status)` and then
  filter/sort in the controller. The problem is not only paging; the returned
  model does not identify which fields are shell, runtime, result, or review
  facts.
- `WorkerApiController` exposes `/api/v1/runtime/workers` as a composite
  console diagnostic row. It joins worker declaration snapshots, WorkerGroup
  capability-derived event bindings, transport connection evidence, runtime
  lock state, and reachability facts into one map.
- `WorkerApiController` already labels `fieldSources`, but the route and
  frontend adapter still treat the response as the main worker list.
- `ExternalWorkerApiController` is the public worker data-plane ingress for
  WorkerGroup, AdapterNode, NodeGroupBinding, worker registration, presence,
  polling, result submit, capability report, state report, command poll, and
  command ack.
- `WorkerDeclarationStore` exists as a worker declaration/control-plane
  abstraction, but current production storage is still memory-shaped; no JDBC
  worker declaration or registration ledger implementation is present in the
  current inventory.
- Frontend callers use `/api/v1/tasks`, `/api/v1/runtime/workers`, and
  `/internal/v1/review/tasks/**` through `frontend/src/api/*`. That is the
  correct caller location, but the API shapes still blur owner boundaries.

## Owner Review

Task ownership:

- Task shell/control-plane facts belong to task shell/storage and server task
  APIs.
- Task runtime current truth belongs to runtime/engine owners such as
  `TaskWorkRuntime` and current lifecycle state owners.
- Task result rows and result-side barriers belong to `TaskResultRuntime` and
  result query operations.
- Task review/export rows are server-local materialized read models. They are
  not runtime truth and must remain opt-in where materialization is controlled
  by task config.

Worker ownership:

- Worker registration ingress belongs to `/worker-api/v1/**` and SDK worker
  session/registration flows.
- Worker declaration facts belong to worker-runtime declaration owner surfaces.
- Worker availability, online/offline, heartbeat, locks, current load,
  candidate availability, dispatch gates, and transport sessions are runtime
  truth, not DB truth.
- WorkerGroup owns project/event capability binding. Worker rows must not
  become a second capability owner.
- Server console worker views may compose declaration, capability, runtime, and
  transport evidence, but those views must be labeled as composite diagnostics.

Registration DB owner decision:

```text
worker registration DB rows are observation / audit / future analysis output.
They are not runtime restore source.
They do not drive matching, scheduling, candidate selection, presence, or
transport routing.
```

If Redis/runtime state is cleared, workers must reconnect/re-register through
public APIs. DB registration rows must not resurrect runtime state on startup.

## Boundary Decision

Use four task API/read surfaces:

```text
Task shell / control-plane
  task identity, project, user, contract, sourceRef, sharedConfig, shell
  timestamps, command intent, item ingest metadata

Task runtime current state
  status, intake window, current aggregate counters, scheduling/admission
  current state, runtime progress facts

Task result runtime
  ordered result windows, result archive manifest/content, result-side
  idempotency and final rows

Task review materialization
  seed/result preview, export support, console review rows, bounded and
  opt-in; not result truth
```

Use four worker API/read surfaces:

```text
Worker registration ingress
  public worker data-plane API and SDK registration/session flows

Worker registration DB observation
  registration ledger/current rows for future analysis and audit; no runtime
  restore and no scheduling input

Worker runtime current state
  Redis/runtime/transport presence, locks, heartbeat, load, current commands,
  dispatch gates, route buckets, candidate availability

Worker capability/catalog
  WorkerGroup project/event bindings and catalog capability views
```

Composite console routes are allowed only when the response labels each field's
owner and does not become a public SDK or runtime decision contract.

## Hard Rules

1. Do not solve this as a paging-only cleanup. Pagination is required where
   scans exist, but field owner separation is the main invariant.
2. Do not expand `ApiTask` into a larger all-purpose object. Split or label
   owner-specific views before adding more task fields.
3. Do not make `/api/v1/runtime/workers` the public worker entity contract. It
   is a composite console diagnostic unless a later decision creates a
   declaration-only worker read contract.
4. Do not let frontend convenience create CRUD-shaped runtime endpoints,
   compatibility aliases, or frontend-only permission/model names.
5. Do not put online/offline, heartbeat, active connections, locks, current
   load, leases, in-flight dispatch, route buckets, command delivery transient
   state, or scheduling candidate truth into worker registration DB rows.
6. Do not load worker registration DB rows on startup into Redis/runtime,
   `WorkerRegistry`, transport sessions, or scheduling candidate indexes.
7. Registration DB writes, if implemented, happen after successful public
   registration ingress and are observation/audit writes. They must not change
   the success semantics of runtime state unless the slice explicitly chooses
   required-write behavior for prod.
8. Result-runtime reads and review materialization reads must remain separate.
   Review rows must not source `/results`, and `/results` must not depend on
   review materialization.
9. WorkerGroup remains capability truth. Worker declaration or registration DB
   rows may refer to group ids but must not become a second project/event
   binding owner.
10. Any server/frontend API boundary change must update
    `doc/FRONTEND_BACKEND_CONTRACT.md`, frontend `src/api/*`, and the relevant
    frontend adapter/page tests.
11. Runtime current-state UI refresh should converge toward SSE-backed bounded
    streams in a later roadmap. Do not freeze task/worker polling/list routes
    as the long-term realtime mechanism while doing this API split.

## Non-Goals

- Do not implement worker history analytics in this roadmap.
- Do not implement trace archive materialization in this roadmap.
- Do not restore worker runtime state from DB on startup.
- Do not migrate all task APIs in one slice.
- Do not remove existing frontend task/worker pages before replacement adapter
  contracts exist.
- Do not freeze internal engine/base task or worker models as public contracts.
- Do not introduce backend-driven frontend menu/page schemas.
- Do not implement SSE in this roadmap.

## Deferred Runtime Realtime Direction

Future task/worker realtime console refresh should use SSE-backed bounded
current-state streams rather than repeated broad list scans.

SSE is the preferred later direction for console-visible runtime current state:

- task status, intake, counters, and terminal transitions
- worker online/offline, heartbeat freshness, draining, lock/load summaries,
  and command/current-state summaries
- queue/runtime aggregate metric updates
- bounded assignment/progress updates

SSE must not become:

- a full task DB list transport
- a worker registration DB transport
- a result payload stream for large outputs
- a review/export content transport
- trace/history replay storage
- a raw heartbeat flood channel

This roadmap only preserves the boundary for that future direction. It does
not implement SSE, define SSE event schemas, or require frontend realtime work.
Current task/worker list routes remain valid read surfaces during this
convergence, but they should not be documented as the final realtime mechanism.

## Do Not Start With

Do not start by renaming routes, deleting `ApiTask`, replacing frontend pages,
or adding worker DB restore. Start by classifying current route fields and
callers, then define the target response surfaces and proof order.

## TWA-0 Inventory And Field Classification

Artifact:
[SERVER_TASK_WORKER_API_RUNTIME_BOUNDARY_INVENTORY.md](./SERVER_TASK_WORKER_API_RUNTIME_BOUNDARY_INVENTORY.md)

Goal: make the current mixed task/worker route and model ownership visible
before changing code.

Scope:

- Inventory task routes:
  - `/api/v1/tasks`
  - `/api/v1/tasks/{taskId}`
  - `/api/v1/tasks/{taskId}/items`
  - `/api/v1/tasks/{taskId}/items:sync`
  - `/api/v1/tasks/{taskId}/commands`
  - `/api/v1/tasks/{taskId}/results`
  - `/api/v1/tasks/{taskId}/results/archive`
  - `/internal/v1/review/tasks/**`
- Inventory worker routes:
  - `/worker-api/v1/**`
  - `/api/v1/runtime/workers`
  - `/api/v1/runtime/workers/{workerId}/state`
  - `/api/v1/runtime/workers/states`
  - `/api/v1/runtime/workers/{workerId}/commands`
  - `/api/v1/runtime/workers/commands/{commandId}`
  - catalog worker capability routes
- Classify every response field used by frontend and SDK-facing server APIs as:
  - `control-plane storage`
  - `runtime current truth`
  - `result-runtime`
  - `review-materialization`
  - `transport/session evidence`
  - `catalog/WorkerGroup capability`
  - `registration observation`
  - `compatibility alias`
  - `composite diagnostic`
- Inventory frontend callers in `frontend/src/api/*` and pages that depend on
  task/worker mixed shapes.
- Identify whether each route is `public-sdk-read`, `public-sdk-ingress`,
  `operator-command`, `console-diagnostics`, or `internal-debug`.

Acceptance:

- Inventory lists the first-pass field classifications for `ApiTask`,
  `ApiTaskResultWindow`, review task responses, worker runtime rows, and
  worker capability rows.
- Inventory explicitly marks `/api/v1/runtime/workers` as composite diagnostic,
  not declaration truth.
- Inventory explicitly marks worker registration DB rows as observation/audit
  output and states they do not restore runtime state.
- Inventory identifies which frontend adapters must change in later slices.
- No behavior change is required in TWA-0.

Verification:

```powershell
rg -n "ApiTask|ApiTaskResultWindow|/api/v1/runtime/workers|/internal/v1/review/tasks|/worker-api/v1" xa-mass-server frontend/src
git diff --check
```

## TWA-1 Target API Contract Decision

Goal: record the target route/DTO split before implementation.

Scope:

- Decide the target names and route ownership for:
  - task shell list/detail
  - task runtime current state/detail
  - task result window/archive
  - task review/export
  - worker declaration/read surface, if any
  - worker runtime diagnostic surface
  - worker registration observation DB surface, if any
- Decide whether current `/api/v1/tasks` remains shell-oriented public read or
  is split into public shell plus console runtime detail.
- Decide whether `ApiTask` is retained only as compatibility output,
  superseded by narrower response records, or split immediately.
- Decide whether worker registration observation uses:
  - current-only table
  - append-only ledger table
  - both current and ledger tables
- Decide DB write semantics:
  - dev/test best-effort versus required
  - prod required versus best-effort
  - what error response is returned if the observation write fails

Acceptance:

- `xa-mass-server/doc/API_SURFACE_INVENTORY.md` is updated with the chosen
  target categories and no longer implies mixed task/worker rows are base
  entity truth.
- `doc/FRONTEND_BACKEND_CONTRACT.md` records that API docs expose current
  routes but route models must label owner boundaries where composite.
- `frontend/AGENTS.md` or `frontend/README.md` points frontend API changes to
  the chosen split instead of raw route discovery.
- No new route is introduced before the target split is recorded.

Verification:

```powershell
rg -n "composite diagnostic|registration observation|Task shell|Task runtime|Worker declaration" xa-mass-server/doc doc frontend
git diff --check
```

## TWA-2 Task Read Surface Split

Goal: separate task shell/control-plane reads from runtime/result/review reads
without breaking current SDK/frontend callers.

Scope:

- Introduce narrower task response records or explicitly source-labeled task
  response sections.
- Keep `/api/v1/tasks` bounded and shell-oriented unless TWA-1 chooses a new
  route.
- Keep result windows on `/api/v1/tasks/{taskId}/results`.
- Keep review/export on `/internal/v1/review/tasks/**`.
- Update frontend task adapters and pages to consume the correct surface for:
  - task list shell fields
  - runtime/current progress fields
  - result preview/export fields
  - review materialization fields
- Update API docs and tests to prove review/result separation.

Acceptance:

- Task list/detail no longer present unlabeled runtime/review/result fields as
  generic task shell fields.
- Frontend task list/detail still works through `frontend/src/api/tasks*`.
- Result window tests prove `/results` does not depend on review
  materialization.
- Review tests prove `/internal/v1/review/tasks/**` stays server-local and
  opt-in.
- Public SDK-facing task reads do not consume frontend-only shapes.

Verification candidates:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=TaskApiControllerTest,TaskApiListControllerTest,InternalTaskReviewControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
cd frontend
corepack pnpm test:run -- tasks
corepack pnpm typecheck
```

## TWA-3 Worker Read Surface Split

Goal: make worker declaration/capability/runtime/transport composition explicit
and stop treating `/api/v1/runtime/workers` as a base worker entity contract.

Scope:

- Keep `/worker-api/v1/**` as worker data-plane registration and runtime
  ingress.
- Keep `/api/v1/runtime/workers` as console diagnostic or rename/classify it
  according to TWA-1.
- Add or update response source labels for worker rows:
  - declaration
  - runtime
  - transport
  - WorkerGroup capability
  - compatibility alias
  - composite diagnostic
- Update frontend worker adapters/pages to display source-aware worker facts
  instead of treating all fields as a single entity model.
- Do not add worker history analytics.

Acceptance:

- Worker list/detail UI can still show registered/current workers, but the API
  adapter preserves source distinctions.
- `Worker.eventBindings` / WorkerGroup capability remains capability truth.
- `supportedEventCodes` and `supportedProjects` stay derived display/filter
  fields only.
- No SDK/public worker read contract depends on console composite rows unless
  TWA-1 explicitly accepts it.

Verification candidates:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=WorkerApiControllerTest,CatalogControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
cd frontend
corepack pnpm test:run -- workers
corepack pnpm typecheck
```

## TWA-4 Worker Registration DB Observation

Goal: persist worker registration observations for future analysis without
turning DB into runtime source of truth.

Scope:

- Add server/control-plane schema for worker registration observation rows.
  Candidate tables:
  - `xa_worker_registration_ledger`
  - optionally `xa_worker_registration_current`
- Record successful public registration ingress for:
  - WorkerGroup declaration
  - AdapterNode declaration
  - NodeGroupBinding declaration
  - Worker registration
- Store only registration/audit facts such as:
  - resource type
  - resource id
  - action
  - principal id/type
  - request hash
  - bounded payload JSON or selected payload fields
  - first/last/occurred timestamps
- Do not store runtime online/offline, heartbeat, active connection, lock,
  load, lease, route bucket, dispatch, result, or command delivery transient
  truth.
- Do not load rows into runtime on startup.
- Decide and test write failure behavior according to TWA-1.

Acceptance:

- Successful registration writes observation rows.
- Server startup does not read worker registration observation rows into
  runtime, `WorkerRegistry`, transport sessions, or scheduling candidate
  indexes.
- Clearing Redis/runtime still requires workers to reconnect/re-register;
  DB rows do not resurrect runtime state.
- Schema SQL lives in the correct owner directory based on the final decision:
  server-owned observation schema under
  `xa-mass-server/src/main/resources/db/schema/server-control-plane` and
  `xa-mass-server/src/main/resources/db/migration/server-control-plane`;
  generic platform storage schema must not absorb server API/audit concepts.

Verification candidates:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=ExternalWorker*IntegrationTest,*WorkerRegistration*Test,*ArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "worker registration.*restore|load.*worker.*registration|xa_worker_registration" xa-mass-server platform_infra -g "*.java" -g "*.sql" -g "*.md"
```

## TWA-5 Guards And Residue Scan

Goal: prevent future sessions from re-mixing DB/runtime/review/transport truth
after the split lands.

Scope:

- Add or update architecture guards so:
  - worker registration observation stores are not injected into runtime
    startup restore or scheduling candidate paths
  - `/api/v1/runtime/workers` remains classified as console diagnostic if it
    stays
  - frontend production code does not call task/worker routes outside
    `src/api/*`
  - task review rows are not used as result-runtime source
  - worker declaration DB rows do not carry online/heartbeat/lock/load fields
- Run residue scans for old mixed vocabulary if TWA-2/TWA-3 rename response
  records.
- Update `INTERNAL_API_REFERENCE.md`, `API_SURFACE_INVENTORY.md`,
  `FRONTEND_BACKEND_CONTRACT.md`, frontend README/AGENTS if behavior changed.

Acceptance:

- Guards fail on new runtime restore from worker registration DB.
- Guards fail on frontend inline task/worker `fetch` calls outside API modules.
- Docs describe current behavior, not target state.
- Roadmap remains active unless all completion criteria below are satisfied.

Verification candidates:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=*ArchitectureGuardTest,*Api*Test,*Worker*Test,*Task*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
cd frontend
corepack pnpm test:run
corepack pnpm typecheck
corepack pnpm build
git diff --check
```

## Suggested Implementation Order

1. TWA-0 inventory and field classification.
2. TWA-1 target route/DTO/DB observation decision.
3. TWA-2 task read surface split.
4. TWA-3 worker read surface split.
5. TWA-4 worker registration DB observation.
6. TWA-5 guards, residue scan, and owner-doc sync.

TWA-4 can start after TWA-1 if task/worker API split work is delayed, but it
must still obey the hard rule that DB registration rows do not restore runtime
state.

## Completion Criteria

This roadmap can be marked complete only when:

- task shell/runtime/result/review fields are split or source-labeled in API
  contracts and frontend adapters
- worker declaration/runtime/transport/capability/composite fields are split or
  source-labeled in API contracts and frontend adapters
- worker registration observation DB writes, if implemented, are proven not to
  restore runtime state or drive scheduling
- server route inventory and API reference reflect current behavior
- frontend adapters and tests consume the chosen route contracts
- architecture guards prevent the main boundary regressions
- residue scan finds no active docs claiming mixed composite routes are base
  entity truth
