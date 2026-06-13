# Worker Runtime External Eligibility Surface Roadmap

Status: mainline implemented. WES-0 inventory, WES-1 vocabulary decision,
WES-2 server/catalog alignment, WES-3 SDK classification verification, WES-4
frontend alignment, WRP-0 decision, WES-5 docs/guards, and successor handoff
are landed for the current WES scope.

Successor to:

- `roadmap/WORKER_RUNTIME_COMPOSITE_ELIGIBILITY_SET_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`
- `roadmap/TRANSPORT_SELECTED_WORKER_DELIVERY_AND_REACHABILITY_BOUNDARY_ROADMAP.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `roadmap/SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md`
- `frontend/WORKER_SOURCE_AWARE_PRESENTATION_FOLLOWUP.md`

Execution artifacts:

- `roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_INVENTORY.md`
- `roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_DECISION.md`

Successor handoff:

- `roadmap/SERVER_CATALOG_WORKER_CAPABILITY_API_CATEGORY_ROADMAP.md`

## Purpose

Converge worker eligibility and lifecycle evidence on external and diagnostic
surfaces after the engine / worker-runtime scheduling mainline has landed.

The completed CES mainline established the production scheduling mechanism:

```text
WorkerGroup selector
  -> bounded candidate source
  -> registry-owned slot lifecycle acquisition/source guard
  -> worker-runtime reachability evidence
  -> engine rank/rule/reserve
  -> dispatch binding
```

This roadmap does not reopen that hot path. It owns the next problem: server
worker inspection routes, catalog capability routes, SDK-facing worker views,
frontend worker/catalog pages, and docs must not keep presenting old worker
status fields, route-owner evidence, or Redis/internal index details as if they
were scheduling truth.

Reachability projection optimization is included only as a decision lane. Any
new writer, storage, batch-read contract, or transport integration must split
into a dedicated reachability-projection implementation roadmap before coding.

## Deferred Problem Handoff

This roadmap preserves the engine-external issues that CES intentionally left
outside the hot-path mainline. Do not remove a surface from this roadmap only
because the current name or owner is wrong; classify it, choose the new owner
language, and either converge it here or hand it to a named successor roadmap.

Current deferred problems carried here:

- server worker inspection and catalog capability routes still expose old
  online/eligible/reachable vocabulary,
- public Java SDK, embedded SDK, and public-contract surfaces need caller-level
  classification before worker lifecycle wording changes,
- frontend worker and catalog pages consume backend fields that can be mistaken
  for scheduler truth,
- `/api/v1/catalog/worker-capabilities` still has a diagnostic category with
  SDK credential bypass in the route inventory; WES can source-label its fields,
  but caller/auth/category cleanup belongs to
  `roadmap/SERVER_CATALOG_WORKER_CAPABILITY_API_CATEGORY_ROADMAP.md` unless
  this roadmap is explicitly expanded,
- optional reachability projection optimization has a WRP-0 decision in
  `roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_DECISION.md`; WES keeps
  the live `WorkerReachabilityView` and does not implement a projection writer.

Only one kind of issue should be deleted from this document without a successor:
a statement that is false against current code. For example, the old CES claim
that Stage-2 still rereads dispatch gate was removed because current engine code
no longer does that. The dispatch-gate boundary itself remains documented as
Stage-1 source guard plus reserve revalidation.

## Current Code Observations

- `WorkerRuntimeStateRecord` is the worker-runtime current-state view assembled
  from registry, reachability, heartbeat freshness, dispatch gate, and admission
  evidence. It is explicitly not declaration-store truth.
- `/api/v1/runtime/workers`, `/api/v1/runtime/workers/{workerId}/state`, and
  `/api/v1/runtime/workers/states` are classified as console diagnostics in
  `xa-mass-server/doc/API_SURFACE_INVENTORY.md`.
- `/api/v1/catalog/event-capabilities`,
  `/api/v1/catalog/worker-capabilities`, and
  `/api/v1/catalog/worker-group-capabilities` also expose worker lifecycle or
  eligibility vocabulary. Some of these routes are public SDK read surfaces, so
  catalog cannot be treated as a frontend-only display cleanup.
- Frontend real worker consumers already use `/api/v1/runtime/workers`; the
  frontend-local follow-up says backend shape is not deferred there and only
  page presentation polish remains.
- WES-0 found frontend catalog consumers using `onlineWorkerIds`,
  `hasOnlineWorkerCoverage`, `transportOnlineCounts`, and
  `dispatchEligibleCount` in runtime discovery and project resource pages. The
  WES target replaces those with source-labeled backend fields rather than
  frontend-owned eligibility inference.
- Transport boundary docs state SDK/operator worker inspection must not read
  route-owner worker-id projections. Route-owner evidence is transport delivery
  truth, not worker lifecycle or scheduling eligibility truth.
- The API contract health lane owns broad route/auth/DTO/SDK/frontend parity.
  This roadmap owns the narrower worker eligibility vocabulary and source
  classification used by those surfaces.
- CES preserved the current `WorkerReachabilityView` read for scheduling and
  explicitly did not make Redis registry own reachability.

## Owner Review

Worker runtime owns:

- worker declaration, WorkerGroup, AdapterNode, and NodeGroupBinding facts,
- worker runtime state records and evidence contracts,
- reachability/readiness/occupancy evidence exposed to engine and diagnostics,
- admission, dispatch gate, heartbeat freshness, removing state, and worker
  command lifecycle truth.

Server owns:

- HTTP routes, auth, permissions, DTO mapping, API docs, and bounded diagnostic
  read behavior for worker inspection and catalog capability surfaces.

SDK owns:

- external task/worker typed clients and embedded assembly convenience methods.
  SDK may expose worker views only through worker-runtime/server contracts; it
  must not reinterpret route-owner evidence as worker online/offline truth.

Frontend owns:

- presentation, adapters, and local type consumption. It must not define worker
  eligibility, catalog capability truth, route aliases, permission names, or
  inferred lifecycle truth.

Transport owns:

- route-owner leases, transport node evidence, final-hop delivery, and delivery
  failure. It does not own worker schedulability or worker inspection truth.

Engine owns:

- scheduling decisions, ranking, rule evaluation, reserve/admission calls, and
  dispatch binding. This roadmap should not change engine scheduling behavior
  unless a later reachability-projection decision proves a better evidence read
  surface with equivalent correctness.

## Boundary Decision

External and diagnostic worker surfaces should expose separate source-labeled
dimensions, not a single overloaded worker status.

Allowed dimensions:

- worker identity and WorkerGroup/node membership,
- declaration/static attributes,
- WorkerGroup capability references,
- reachability evidence and source,
- readiness evidence derived from dispatch gate/removing state,
- heartbeat freshness and observed-at timestamp,
- occupancy/admission counters where bounded and source-labeled,
- command/drain state when presented as worker-command/runtime state, not as
  capability truth.
- catalog capability coverage only when source-labeled and not presented as the
  scheduler's composite eligibility predicate.

Forbidden shortcuts:

- treating Redis candidate keys, bucket membership, or lifecycle deadline ZSETs
  as public API/SDK/frontend fields,
- treating route-owner presence as worker reachability truth,
- exposing one `scheduleEligible` boolean as public SDK truth without separate
  source dimensions and diagnostic reasons,
- letting frontend compute eligibility by combining fields locally,
- preserving legacy `statusName` / `Worker.status` as scheduling truth.

## Merge / Split Decision

This roadmap coordinates two related but independently executable tracks:

- **WES**: external worker eligibility surface convergence for server, SDK,
  frontend, docs, and guards.
- **WRP**: optional reachability projection optimization decision only.

Do not merge WRP implementation into this roadmap. If WRP needs a new
projection writer, batch-read contract, Redis shape, event stream, or transport
integration, create a separate reachability-projection roadmap and keep this
roadmap focused on external surfaces.

## Non-Goals

- No engine scheduling hot-path rewrite.
- No Redis keyspace change for worker registry or transport presence.
- No new public API route unless WES-1 proves a current route cannot express the
  selected source-labeled view.
- No broad API contract health lane replacement.
- No frontend-only model or route alias.
- No transport route-owner ownership change.
- No bucket-rule, security-policy, or policy-catalog expansion.

## Do Not Start With

Do not start by adding a new `eligible` / `available` / `online` boolean to a
DTO. First inventory current fields and callers, then decide which source owns
each dimension and whether the surface is public SDK, operator diagnostic, or
frontend presentation.

## WES-0: External Surface Inventory

Goal: classify every worker eligibility or lifecycle field exposed outside the
engine scheduling mainline.

Scope:

- Inventory server routes and DTOs for:
  - `/api/v1/runtime/workers`,
  - `/api/v1/runtime/workers/{workerId}/state`,
  - `/api/v1/runtime/workers/states`,
  - `/api/v1/catalog/event-capabilities`,
  - `/api/v1/catalog/worker-capabilities`,
  - `/api/v1/catalog/worker-group-capabilities`,
  - worker command diagnostic routes only where they expose readiness/drain
    vocabulary.
- Inventory public Java SDK, embedded SDK, and public-contract methods that
  expose worker snapshots, catalog capability views, reachability, resource
  state, or registration/session state.
- Inventory frontend worker/catalog adapters, worker/catalog types,
  dashboard/runtime-discovery/resource/project pages, and
  `frontend/WORKER_SOURCE_AWARE_PRESENTATION_FOLLOWUP.md`.
- Inventory docs that describe worker online/offline, readiness, dispatch
  eligibility, or reachability.
- Classify fields as:
  - declaration/static,
  - WorkerGroup capability,
  - registry slot lifecycle,
  - reachability evidence,
  - admission/occupancy,
  - command/drain state,
  - transport delivery evidence,
  - legacy compatibility/display only.

Acceptance:

- A sibling inventory exists with field-by-field owner classification and
  current caller list.
- Inventory distinguishes public SDK, worker API, operator diagnostic, frontend
  presentation, and test-only callers.
- Inventory identifies any current surface that still reads or implies transport
  route-owner reachability.
- Inventory classifies catalog capability fields separately from scheduler
  eligibility and names any public SDK read exposure.
- No behavior changes in this slice.

Suggested inventory file:

```text
roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_INVENTORY.md
```

## WES-1: Surface Vocabulary Decision

Goal: select the external vocabulary before changing DTOs or frontend types.

Scope:

- Decide the canonical names for separate dimensions:
  - reachability,
  - readiness,
  - dispatch eligibility,
  - heartbeat freshness,
  - occupancy/admission,
  - diagnostic status/display label.
- Decide whether `statusName` remains on any external route, and if so mark it
  as display-only compatibility with a source field.
- Decide whether server worker routes should expose a diagnostic reasons list
  instead of a composite `eligible` boolean.
- Decide catalog capability names for reachable/online worker coverage,
  transport online counts, and dispatch-capable counts. These names must not
  imply they are the CES composite scheduling predicate unless they are backed
  by the same source contract.
- Decide whether SDK public worker snapshots should include runtime diagnostic
  state or keep worker registration/resource state separate from operator
  diagnostics.
- Classify SDK data-plane `online`, `heartbeat`, and `offline` as worker
  presence/session APIs when retained. They are not worker inspection
  eligibility APIs.

Acceptance:

- Chosen names and source ownership are documented in this roadmap or the
  owning module docs.
- Any public DTO change has a caller plan for server tests, public contract/SDK
  types, frontend adapters, and docs.
- The decision does not require engine scheduling changes.

## WES-2: Server Worker And Catalog Surface Alignment

Goal: align server worker inspection and catalog capability routes with
worker-runtime evidence without exposing Redis or transport internals.

Scope:

- Update `WorkerApiController` DTO mapping if WES-1 selects new source-labeled
  fields.
- Update `CatalogController` DTO/map output for event, worker, and WorkerGroup
  capability routes if WES-1 renames or splits catalog eligibility vocabulary.
- Keep worker routes classified as console diagnostics unless API contract
  health explicitly promotes a shape to public SDK read.
- Keep catalog routes classified according to `API_SURFACE_INVENTORY.md`; public
  SDK read routes require public-contract/SDK caller review, not just console
  presentation updates.
- Preserve bounded list limits and avoid scan-heavy polling routes.
- Update `xa-mass-server/doc/API_SURFACE_INVENTORY.md` and
  `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`.
- Add or update server tests for worker/catalog route response shape, auth
  family, and removed duplicate worker report/ack routes if touched.

Acceptance:

- Server DTOs do not expose Redis key names, candidate bucket keys, slot
  lifecycle ZSET names, or transport route-owner IDs as worker lifecycle truth.
- Server worker and catalog route docs label fields by owner/source.
- Route/auth tests still pass for worker diagnostics, catalog capability reads,
  and worker API ingress.
- If Spring wiring changes, a startup/context proof is included.

## WES-3: SDK And Embedded Surface Alignment

Goal: prevent SDK-facing worker views from presenting diagnostic or transport
delivery evidence as worker lifecycle truth.

Scope:

- Inventory and then retarget SDK worker snapshot/resource methods according to
  WES-1.
- Keep external worker registration/session APIs separate from operator
  diagnostic worker runtime state.
- Keep public Java SDK worker data-plane `online`/`heartbeat`/`offline` and
  managed session behavior legal as presence/session calls when retained; do
  not describe them as worker inspection eligibility or scheduler truth.
- If a worker reachability helper exists, ensure it consumes worker-runtime
  reachability/state evidence, not route-owner lookup.
- Update SDK README/quickstart only when public caller behavior changes.

Acceptance:

- SDK does not expose route-owner presence as worker online/offline truth.
- SDK public methods do not expose Redis key/index shapes or internal lifecycle
  projections.
- SDK tests cover any changed worker snapshot/resource behavior.
- Public Java SDK README/quickstart scans and worker client/session tests are
  included when presence wording or worker/capability views are touched.

## WES-4: Frontend Worker Presentation Alignment

Goal: make frontend worker views consume the selected server fields without
creating frontend-owned eligibility logic.

Scope:

- Update `frontend/src/api/workers.real.ts` and `frontend/src/types/workers.ts`
  only after WES-1/WES-2 define the backend shape.
- Update `frontend/src/api/catalog.real.ts`, `frontend/src/types/catalog.ts`,
  runtime discovery pages, and project resource pages if catalog capability
  vocabulary changes.
- Update worker list/detail/dashboard/project presentation to show source-aware
  dimensions where useful.
- Retire or update `frontend/WORKER_SOURCE_AWARE_PRESENTATION_FOLLOWUP.md` if
  the page presentation work lands.
- Keep frontend tests focused on adapter/type/presentation behavior.

Acceptance:

- Frontend does not infer scheduling eligibility from local combinations of
  reachability, dispatch, route owner, or capacity fields.
- Frontend catalog pages do not treat `onlineWorkerIds`,
  `transportOnlineCounts`, or `dispatchEligibleCount` as hidden scheduler
  truth without backend-owned source labeling.
- Frontend has no hardcoded replacement route, permission, or DTO truth.
- Frontend tests/typecheck/build pass for touched surfaces.

## WRP-0: Reachability Projection Decision

Goal: decide whether the current `WorkerReachabilityView` read needs an
optimization, and name the owner before any implementation.

Scope:

- Inventory current reachability writers and readers:
  - engine scheduling,
  - worker-runtime state records,
  - SDK/embedded views,
  - server diagnostics,
  - transport route-owner delivery evidence.
- Record the measured or expected problem that justifies optimization:
  per-candidate read cost, batch read cost, consistency lag, or observability
  gaps.
- Choose one:
  - preserve current live `WorkerReachabilityView`,
  - propose a bounded batch reachability read surface in worker-runtime as a
    successor roadmap,
  - propose a worker-runtime-owned projection writer from a named event/evidence
    source as a successor roadmap,
  - defer because current reachability read is not the bottleneck.
- Explicitly reject Redis registry ownership and direct transport route-owner
  reads unless a new owner decision supersedes current transport baseline.

Acceptance:

- Decision names writer, reader, staleness tolerance, failure mode, and proof.
- Any implementation decision points to a new roadmap; WES does not carry the
  implementation slice.
- Non-ONLINE reachability remains non-dispatchable.

## WES-5: Guards, Docs, And Residue

Goal: prevent old external vocabulary from becoming a second scheduling model.

Scope:

- Add guards for:
  - server/SDK/frontend mainline must not expose Redis worker key shapes,
  - SDK/server worker inspection must not call transport route-owner lookup for
    worker online/offline state,
  - catalog capability routes must not expose old online/eligible vocabulary
    without source ownership,
  - frontend must not hardcode worker eligibility inference or route aliases,
  - worker-runtime external records must label legacy `statusName` as display
    or diagnostic only if retained.
- Update owner docs touched by implementation:
  - `xa-mass-worker-runtime/README.md`,
  - `xa-mass-worker-runtime/CONTRACTS.md`,
  - `xa-mass-server/doc/API_SURFACE_INVENTORY.md`,
  - `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`,
  - `sdk/xa-mass-java-sdk/README.md` and
    `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md` when public Java SDK
    wording changes,
  - `doc/FRONTEND_BACKEND_CONTRACT.md` when backend/frontend contract changes.
- Run residue scans for old vocabulary.

Acceptance:

- Guards fail on route-owner reachability leakage into worker inspection.
- Guards fail on Redis key/index leakage into external worker surfaces.
- Active docs do not describe target state as already implemented.
- Completed frontend-local follow-ups are archived or updated.

## Verification Candidates

Inventory and shape scans:

```powershell
rg -n "/api/v1/runtime/workers|/api/v1/catalog/event-capabilities|/api/v1/catalog/worker-capabilities|/api/v1/catalog/worker-group-capabilities|WorkerRuntimeStateRecord|WorkerResourceRecord|WorkerListItem|workers.real|catalog.real|types/catalog|RuntimeDiscoveryPage|isWorkerReachable|getWorkerRouteOwnerView|activeOwnerForSelectedWorker|WorkerDispatchRouteOwnerView" xa-mass-server sdk frontend xa-mass-worker-runtime transport -g "*.java" -g "*.ts" -g "*.vue" -g "*.md"
rg -n "RedisWorkerRegistry|RedisWorkerRegistryKeyspace|bucket-membership|slot-lifecycle-deadlines|candidateBucketLifecycleDeadlinesZset|worker:group|group:\\{groupId\\}:slots" xa-mass-server sdk frontend xa-mass-worker-runtime xa-mass-engine -g "*.java" -g "*.ts" -g "*.vue" -g "*.md"
rg -n "transportReachability|transportOnline|onlineWorkerIds|hasOnlineWorkerCoverage|transportOnlineCounts|dispatchEligibleCount|modelStatusCounts|worker\.status\s*==|Online workers only" xa-mass-server/src/main/java xa-mass-server/src/test/java/com/xa/mass/api/internal frontend/src sdk -g "*.java" -g "*.ts" -g "*.vue" -g "*.md"
rg -n "ready=true|transportReachability|transportOnline|onlineWorkerIds|hasOnlineWorkerCoverage|transportOnlineCounts|dispatchEligibleCount" xa-mass-server/doc doc/FRONTEND_BACKEND_CONTRACT.md frontend/README.md frontend/WORKER_SOURCE_AWARE_PRESENTATION_FOLLOWUP.md -g "*.md"
```

Focused backend and SDK tests:

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dtest=WorkerApiControllerTest,CatalogControllerTest,ApiAuthInterceptorTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl sdk/xa-mass-java-sdk -am "-Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am "-Dtest=WorkerRuntimeSelectionIntegrationTest,WorkerRuntimePresenceIngressTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl xa-mass-engine -am "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,RuleBasedTaskWorkerMatchingStrategyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Frontend verification when frontend is touched:

```powershell
cd frontend
corepack pnpm test:run -- workers catalog RuntimeDiscoveryPage ProjectsPage ProjectDetailPage DashboardPage WorkerDebugPanel RulesPage
corepack pnpm typecheck
corepack pnpm build
```

Startup proof required if server configuration or Spring wiring changes:

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dtest=*Application*Test,*Context*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## Verification Record

Current WES mainline verification:

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dtest=WorkerApiControllerTest,CatalogControllerTest,RuleApiControllerTest,ApiAuthInterceptorTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
cd frontend
corepack pnpm test:run -- workers catalog RuntimeDiscoveryPage ProjectsPage ProjectDetailPage DashboardPage WorkerDebugPanel RulesPage
corepack pnpm typecheck
corepack pnpm build
cd ..
.\mvnw.cmd -pl sdk/xa-mass-java-sdk -am "-Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am "-Dtest=WorkerRuntimeSelectionIntegrationTest,WorkerRuntimePresenceIngressTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Results:

- Server focused tests: passed, 77 tests.
- Frontend targeted tests: passed, 40 files / 101 tests.
- Frontend typecheck: passed.
- Frontend build: passed with existing Vite chunk-size warning.
- Public Java SDK focused tests: passed, 26 tests.
- Embedded SDK focused tests: passed, 4 tests.

Engine scheduling tests remain candidate proof for engine changes; WES did not
change engine scheduling hot-path code.

## Suggested Implementation Order

1. WES-0 inventory.
2. WES-1 vocabulary decision.
3. WES-2 server worker and catalog surface alignment.
4. WES-3 SDK and embedded surface alignment.
5. WES-4 frontend presentation alignment.
6. WRP-0 reachability projection decision.
7. WES-5 guards, docs, and residue cleanup.

WES-2/WES-3/WES-4 may be split by caller if WES-1 concludes no shared DTO
change is needed.

## Completion Criteria

This roadmap is complete when:

1. Worker external surfaces expose separate source-labeled worker lifecycle
   dimensions instead of relying on one overloaded status.
2. Server worker diagnostic routes are bounded, documented, and guarded against
   Redis/transport key leakage.
3. Catalog capability routes are classified by public/diagnostic caller and do
   not expose old online/eligible vocabulary without source ownership.
4. SDK worker views do not read transport route-owner evidence as worker
   reachability and do not expose internal Redis/runtime projection shapes.
5. Frontend worker and catalog pages consume backend-owned fields and do not
   infer scheduling eligibility locally.
6. Reachability projection is either explicitly deferred with evidence, kept as
   current live `WorkerReachabilityView`, or handed off to its own
   implementation roadmap with writer/reader/staleness proof.
7. API health lane and frontend/source-aware follow-up docs are updated so they
   no longer carry stale worker eligibility wording.
8. Guards and focused tests cover the selected surfaces.
