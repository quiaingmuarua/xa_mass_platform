# Worker Runtime External Eligibility Surface Inventory

Status: WES-0 inventory. Code-grounded current-state record; no behavior
change.

Parent roadmap:

- `roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_ROADMAP.md`

## Purpose

Inventory every worker lifecycle / eligibility field exposed outside the engine
scheduling mainline before changing DTOs, SDK contracts, frontend types, or
docs.

This inventory intentionally treats similarly named fields as separate facts:
worker data-plane presence, worker-runtime reachability, transport/session
delivery evidence, registry slot lifecycle, capability coverage, occupancy,
command/drain state, and display-only status are not interchangeable.

## Source Classes

| Class | Owner | Meaning |
| --- | --- | --- |
| `declaration` | worker-runtime declaration | Worker identity, WorkerGroup/node membership, adapter hints, static attributes, declared max concurrency |
| `workerGroupCapability` | worker-runtime WorkerGroup resource | Project/event bindings and group defaults; worker rows are not capability truth |
| `runtimeStatusDisplay` | worker-runtime compatibility/read model | Legacy `Worker.status` / `statusName`; display or current-state projection only |
| `workerRuntimeReachability` | worker-runtime/embedded reachability view | `WorkerInspectionOperations#listReachableWorkerIds()` and `isWorkerReachable(...)`; current implementation derives from worker-runtime lifecycle status |
| `registrySlotLifecycle` | runtime registry/admission | heartbeat deadline, dispatch gate, removing state, group membership, capacity/reserve mutation checks; not external DTO truth |
| `occupancyAdmission` | runtime diagnostics/admission | locked/exclusive lease, capacity/reservation/load observations |
| `transportSessionEvidence` | transport/session diagnostics | session connection rows, endpoint, routeKey, adapter connection evidence; delivery feasibility, not worker schedulability |
| `commandDrainState` | worker command/runtime control | operator command and state-report projection, drain command lifecycle |
| `compatibilityProjection` | display/read compatibility | Flat `supportedProjects` / `supportedEventCodes` where derived from WorkerGroup capability or legacy worker fields |

## Server Routes

| Surface | Caller class | Auth | Fields with eligibility vocabulary | Current source | Risk / action |
| --- | --- | --- | --- | --- | --- |
| `GET /api/v1/runtime/workers` | console diagnostics | `WORKER_VIEW` | `status`, `transportReachability`, `transportOnline`, `lastHeartbeat`, `locked`, `connections`, `hasActiveEndpoint`, `fieldSources` | `WorkerQueryOperations#getAllWorkers`, `WorkerQueryOperations#listReachableWorkerIds`, `RuntimeDiagnosticsOperations#listSessions`, `RuntimeDiagnosticsOperations#listLockedWorkerIds` | Keep diagnostic. Rename/source-label `transportReachability` and `transportOnline` unless WES-1 explicitly keeps them as legacy display. Do not expose route-owner IDs as worker lifecycle truth. |
| `GET /api/v1/runtime/workers/{workerId}/state` | console diagnostics | `WORKER_VIEW` | state projection record | `WorkerControlOperations#getWorkerStateProjection` | Keep diagnostic command/state surface. Must not be scheduler predicate. |
| `GET /api/v1/runtime/workers/states` | console diagnostics | `WORKER_VIEW`, limit default 200 max 500 | state projection list | `WorkerControlOperations#listWorkerStateProjections` | Keep bounded diagnostic. |
| `POST /api/v1/runtime/workers/{workerId}/commands` | operator command | `WORKER_EDIT` | command target/command status | `WorkerControlOperations#requestWorkerCommand` | Keep operator command. Command drain state can affect dispatch gate only through worker-runtime policy, not frontend inference. |
| `GET /api/v1/runtime/workers/{workerId}/commands` | console diagnostics | `WORKER_VIEW`, limit default 200 max 500 | command status/history | `WorkerControlOperations#listWorkerCommandsForWorker` | Keep bounded diagnostic. |
| `GET /api/v1/runtime/workers/commands/{commandId}` | console diagnostics | `WORKER_VIEW` | command status | `WorkerControlOperations#getWorkerCommand` | Keep bounded diagnostic. |
| `GET /api/v1/catalog/event-capabilities` | public SDK read + frontend | SDK credential bypass | `workerIds`, `reachableWorkerIds`, `hasOnlineWorkerCoverage`, `ready` | catalog event definitions + WorkerGroup capability + `WorkerQueryOperations#listReachableWorkerIds` | Public surface currently exposes readiness-like vocabulary. WES-1 must rename/split so `ready` and online coverage do not imply scheduler composite eligibility. |
| `GET /api/v1/catalog/worker-capabilities` | console diagnostics, currently SDK bypass by route family | SDK credential bypass | `status`, `online`, `connections`, `hasActiveEndpoint`, `locked`, `fieldSources` | WorkerGroup capability + worker declarations + reachability list + runtime diagnostics | Current `online` naming is ambiguous. Route is inventory-classed diagnostic but auth allows SDK bypass; WES-2 must either document this intentionally or narrow route classification/auth in API-surface follow-up. |
| `GET /api/v1/catalog/worker-group-capabilities` | public SDK read + frontend | SDK credential bypass | `transportOnlineCounts`, `modelStatusCounts`, `lockedCount`, `dispatchEligibleCount` | WorkerGroup capability + worker declarations + reachability list + runtime diagnostics + adapter/node binding flags | Public surface currently exposes `dispatchEligibleCount`, but its formula is not CES composite eligibility. WES-1 must rename/source-label or split diagnostic counts from public capability view. |

Removed duplicate server routes are already documented in
`xa-mass-server/doc/API_SURFACE_INVENTORY.md`:

- `POST /api/v1/runtime/workers/{workerId}/capability-reports`
- `POST /api/v1/runtime/workers/{workerId}/state-reports`
- `POST /api/v1/runtime/workers/{workerId}/commands/{commandId}/ack`

Worker self-report and command ack belong to `/worker-api/v1/**`.

## Server Field Details

### Runtime Worker List

Current output fields from `WorkerApiController#listWorkers(...)`:

| Field | Class | Notes |
| --- | --- | --- |
| `workerId`, `workerGroupId`, `adapterNodeId`, `agentVersion`, `adapterId`, `transportHint`, `attributes`, `maxConcurrentWork`, `updateTime` | `declaration` | Static/declaration or declaration-derived view. |
| `supportedProjects`, `supportedEventCodes`, `eventBindings` | `compatibilityProjection` / `workerGroupCapability` | Must not become worker-row capability truth. |
| `status`, `lastHeartbeat` | `runtimeStatusDisplay` | Display/current-state evidence only. |
| `transportReachability`, `transportOnline` | `workerRuntimeReachability` by current call path, but currently named as transport | Needs WES-1 naming decision. |
| `locked` | `occupancyAdmission` | Diagnostic exclusive-lease/lock observation. |
| `connections`, `hasActiveEndpoint` | `transportSessionEvidence` | May include `routeKey`; diagnostics only. |
| `fieldSources` | diagnostic metadata | Good pattern, but current source names need WES-1 review. |

### Catalog Event Capabilities

Current `EventCapabilityView` fields:

| Field | Class | Notes |
| --- | --- | --- |
| `eventCode`, `eventName`, `enabled`, `priorityClass`, `responseMode`, `deliveryAcknowledgementMode`, `convergenceMode`, `targetScope`, `invocationModel`, `projectCodes` | catalog metadata | Not worker scheduling truth. |
| `workerIds` | `workerGroupCapability` + declaration | Declared workers in capable groups. |
| `reachableWorkerIds` | `workerRuntimeReachability` | Current name is better than `online`, but public callers still need source semantics. |
| `hasOnlineWorkerCoverage` | `workerRuntimeReachability` summary | Rename/source-label candidate. |
| `ready` | mixed direct runtime + reachable coverage | High-risk name; not CES composite eligibility. |

### Catalog WorkerGroup Capabilities

Current fields with WES risk:

| Field | Class | Notes |
| --- | --- | --- |
| `transportCounts`, `transportOnlineCounts` | declaration + `workerRuntimeReachability` | Name says transport; formula uses worker reachable ids grouped by transport hint. |
| `modelStatusCounts` | `runtimeStatusDisplay` | Display-only status summary. |
| `lockedCount` | `occupancyAdmission` | Diagnostic count. |
| `dispatchEligibleCount` | mixed reachability + lock + node binding availability | Not registry slot lifecycle + reserve + reachability composite; must not remain public as scheduler eligibility wording. |

## SDK Surfaces

| Surface | Caller class | Fields / methods | Current source | Risk / action |
| --- | --- | --- | --- | --- |
| `sdk/xa-mass-java-sdk` `WorkerClient` | external worker data-plane | `online`, `heartbeat`, `offline`, `poll`, `submitResult`, `pollCommands`, `ackCommand`, `reportCapability`, `reportState` | `/worker-api/v1/**` | Keep. Presence/session API is legal worker data-plane vocabulary, not worker inspection eligibility. |
| `sdk/xa-mass-java-sdk` managed polling session | external worker session | calls `online`, periodic `heartbeat`, best-effort `offline` | worker data-plane | Keep; docs/tests should say session presence, not scheduler eligibility. |
| `sdk/xa-mass-java-sdk` managed WebSocket session | external worker session | transport connection owns presence; does not call polling `online/heartbeat/offline` | transport connection path | Keep split. |
| `sdk/xa-mass-embedded-sdk-api` `WorkerSnapshot` | embedded read model | `status`, `lastHeartbeat`, `supportedProjects`, `supportedEventCodes`, `eventBindings`, `onlineStrategy` | `WorkerResourceRecord` projection | Composite read model; WES-3 should keep source wording clear and avoid public SDK browser semantics. |
| `sdk/xa-mass-embedded-sdk` `WorkerInspectionOperations` | embedded inspection | `getWorker`, `getAllWorkers`, `listReachableWorkerIds`, `isWorkerReachable` | `WorkerResourceRuntime` / `WorkerResourceRecord.statusName` | Current javadoc says runtime lifecycle state and rejects transport route-owner leases. Keep or rename only with a stronger owner decision. |
| `sdk/xa-mass-embedded-sdk` `RuntimeDiagnosticsOperations` | operator diagnostics | sessions, queues, worker locked ids | runtime/transport diagnostics | Must stay diagnostic and bounded. |
| `sdk/xa-mass-public-contract` | public HTTP DTO constants | task shared config worker group/target attributes only | task contract | No current worker lifecycle/eligibility DTO surface found. Do not add one unless WES-1 selects a public contract route shape. |

## Frontend Surfaces

| Surface | Backend route | Fields consumed | Current behavior | Risk / action |
| --- | --- | --- | --- | --- |
| `frontend/src/types/workers.ts` and `frontend/src/api/workers.real.ts` | `/api/v1/runtime/workers` | `status`, `transportReachability`, `transportOnline`, `locked`, `connections`, `fieldSources` | Console worker list type/adaptor | Must follow server source labels; no frontend recomputation of eligibility. |
| `frontend/src/pages/resources/workers/WorkersPage.vue` | `/api/v1/runtime/workers` | `status`, `transportOnline`, `transportReachability`, `locked`, capability fields | Shows `Model status` and `Transport online`; computes online metric from `transportOnline` | Good split visually, but names must follow server WES-1 vocabulary. |
| `frontend/src/pages/resources/workers/WorkerDetailPage.vue` | worker list data | `status` | Detail/debug display | Diagnostic only. |
| `frontend/src/types/catalog.ts` and `frontend/src/api/catalog.real.ts` | catalog routes | `onlineWorkerIds`, `hasOnlineWorkerCoverage`, `transportOnlineCounts`, `dispatchEligibleCount` | Catalog capability type/adaptor | High-risk names because catalog is partly public SDK read. |
| `frontend/src/pages/runtime/RuntimeDiscoveryPage.vue` | catalog + worker list | `onlineWorkerIds`, `ready`, `transportOnline`, worker `supportedEventCodes` fallback | Computes coverage and fallback event rows when capability data missing | Must stop treating fallback worker rows as hidden capability truth if server contract changes. |
| `frontend/src/pages/resources/projects/ProjectsPage.vue` | worker-group capabilities | `dispatchEligibleCount`, `workerCount` | Displays "Online capacity" as dispatchEligible / workerCount | Rename needed; current label does not match field name and both can imply scheduler eligibility. |
| `frontend/src/pages/resources/projects/ProjectDetailPage.vue` | worker-group capabilities + worker list | `dispatchEligibleCount`, `transportOnlineCounts`, worker `status`, `transportReachability` | Shows WorkerGroup dispatch eligible, transport online, model status | Needs source-aware labels after WES-1. |
| `frontend/src/api/*.mock.ts` | mock preview | same names | Test/mock support | Must be kept in lockstep with real adapter shape. |

## Documentation Surfaces

| File | Current WES relevance | Action |
| --- | --- | --- |
| `xa-mass-server/doc/API_SURFACE_INVENTORY.md` | route classifications and auth families | Update when WES-2 changes server/catalog classification or DTO names. |
| `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` | currently says catalog `ready=true`, worker `online`, runtime worker `transportReachability` | Update with source-labeled wording after WES-2. |
| `sdk/xa-mass-java-sdk/README.md` | describes data-plane online/heartbeat/offline and session behavior | Keep presence/session wording; avoid inspection eligibility wording. |
| `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md` | explicitly separates registration and online presence | Keep and possibly strengthen as WES-3. |
| `sdk/xa-mass-embedded-sdk/README.md` | states `isWorkerReachable(...)` reads worker runtime lifecycle, not route-owner evidence | Keep; align if embedded API names change. |
| `doc/FRONTEND_BACKEND_CONTRACT.md` | frontend must not define worker/catalog truth | Update only if server/frontend contract changes. |
| `frontend/WORKER_SOURCE_AWARE_PRESENTATION_FOLLOWUP.md` | frontend-local follow-up referenced by WES | Retire/update when WES-4 lands. |

## Current Route-Owner / Redis Exposure Check

- Server and frontend external worker surfaces do not currently expose Redis
  registry key names or candidate bucket keys.
- Worker/catalog diagnostics can expose transport/session `routeKey` inside
  `connections`. This is transport session evidence and must stay diagnostic;
  it must not be promoted into worker lifecycle or scheduling eligibility.
- Current external worker surfaces do not call `activeOwnerForSelectedWorker`
  or route-owner lookup for online/offline inspection in mainline server
  controllers. Route-owner references found under SDK/transport are assembly,
  delivery, or tests.

## WES-1 Decision Inputs

Recommended vocabulary direction:

| Current external term | Decision input |
| --- | --- |
| `status` / `statusName` | retain only as `runtimeStatusDisplay` or replace with source-labeled display field; never scheduling truth |
| `online` on catalog worker rows | replace or label as reachability/session coverage; not generic worker online truth |
| `transportReachability` / `transportOnline` on runtime worker rows | either keep as legacy transport-display fields or rename to `reachability` / `reachable` with source metadata because the current call path is worker-runtime reachability |
| `reachableWorkerIds` | acceptable if documented as worker-runtime reachability evidence |
| `onlineWorkerIds` / `hasOnlineWorkerCoverage` | rename or alias behind source-labeled catalog coverage |
| `ready` on event capability | split into `hasDirectRuntimeHandler` and reachable worker coverage; avoid a single public readiness boolean unless reasons/source are included |
| `dispatchEligibleCount` | replace with a source-labeled diagnostic count such as `dispatchCoverageCount` or `currentlyReachableUnlockedBindingCount`; do not present as CES composite eligibility |

## Acceptance Status

- WES-0 server routes and DTOs are inventoried.
- WES-0 public Java SDK, embedded SDK, and public-contract surfaces are
  inventoried.
- WES-0 frontend worker/catalog adapters, types, and pages are inventoried.
- WES-0 route-owner and Redis leakage checks are recorded.
- No behavior changes were made by this inventory.
