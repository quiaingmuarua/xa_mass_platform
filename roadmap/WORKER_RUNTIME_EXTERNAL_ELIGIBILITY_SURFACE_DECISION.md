# Worker Runtime External Eligibility Surface Decision

Status: WES-1 decision. Target vocabulary for server, SDK, frontend, and docs.

Parent roadmap:

- `roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_ROADMAP.md`

Inventory input:

- `roadmap/WORKER_RUNTIME_EXTERNAL_ELIGIBILITY_SURFACE_INVENTORY.md`

## Decision

External and diagnostic worker surfaces must expose source-labeled lifecycle
dimensions. They must not expose a single public `eligible`, `available`,
`online`, or `ready` field as if it were the engine scheduler predicate.

The current engine scheduling mainline remains unchanged:

```text
WorkerGroup selector
  -> bounded candidate source
  -> registry-owned slot lifecycle acquisition/source guard
  -> worker-runtime reachability evidence
  -> engine rank/rule/reserve
  -> dispatch binding
```

External surfaces describe observations about that world. They do not own or
recompute it.

## Canonical External Dimensions

| Dimension | External name | Owner/source | Rule |
| --- | --- | --- | --- |
| Worker declaration | `workerId`, `workerGroupId`, `transportHint`, `attributes`, `maxConcurrentWork` | worker-runtime declaration/resource | Stable identity and declared facts. |
| WorkerGroup capability | `eventBindings`, `projectCodes`, `declaredWorkerIds` | WorkerGroup capability | Capability truth is group-level, not worker-row supported lists. |
| Runtime status display | `runtimeStatus` | worker-runtime read model | Display-only current-state label. Not scheduling truth. |
| Reachability | `reachability`, `reachable`, `reachableWorkerIds`, `hasReachableWorkerCoverage` | worker-runtime reachability/inspection | Current observed reachability. Not route-owner proof and not reserve success. |
| Registry slot lifecycle | `readiness` only when source/reasons are included | registry slot lifecycle / worker-runtime state | Do not expose raw Redis keys or lifecycle zsets. |
| Occupancy/admission | `locked`, `lockedCount`, future `capacity*` fields only when source-labeled | runtime diagnostics/admission | Diagnostic; reserve remains final mutation authority. |
| Transport/session evidence | `connections`, `hasActiveEndpoint`, optional `routeKey` inside diagnostic connection rows | transport/session diagnostics | Delivery evidence only; not worker reachability truth. |
| Invocation coverage | `hasInvocationCoverage` with component fields present | catalog/direct runtime + reachable worker coverage | Usability summary. Not scheduler eligibility. |
| Worker data-plane presence | `online`, `heartbeat`, `offline` method names | `/worker-api/v1/**` data-plane session API | Legal for worker sessions. Not worker inspection eligibility. |

## Target Field Changes

### Runtime Worker Diagnostic Route

Route:

- `GET /api/v1/runtime/workers`

Target shape changes:

| Current | Target | Reason |
| --- | --- | --- |
| `status` | `runtimeStatus` | Avoid overloading status as scheduling truth. |
| `transportReachability` | `reachability` | Current source is worker-runtime reachability via `WorkerQueryOperations`, not route-owner transport proof. |
| `transportOnline` | `reachable` | Boolean reachability summary. |
| `fieldSources.status=runtime` | `fieldSources.runtimeStatus=runtimeStatusDisplay` | Source-labeled display only. |
| `fieldSources.transportReachability=transport` | `fieldSources.reachability=workerRuntimeReachability` | Match the actual reader. |
| `fieldSources.transportOnline=transport` | `fieldSources.reachable=workerRuntimeReachability` | Match the actual reader. |
| `connections[*].routeKey` | keep diagnostic | Transport/session evidence remains allowed inside bounded diagnostic rows. |

No public SDK method should be added for this diagnostic route in WES.

### Catalog Event Capability Route

Route:

- `GET /api/v1/catalog/event-capabilities`

Target shape changes:

| Current | Target | Reason |
| --- | --- | --- |
| `workerIds` | `declaredWorkerIds` | Declared workers in capable groups; not currently eligible workers. |
| `reachableWorkerIds` | keep | Source-labeled enough when documented as worker-runtime reachability. |
| `hasOnlineWorkerCoverage` | `hasReachableWorkerCoverage` | Avoid generic online vocabulary. |
| `ready` | `hasInvocationCoverage` | Usability summary only; component fields remain present. |

`hasInvocationCoverage` is true when either a direct runtime handler exists or
reachable worker coverage exists. It must be documented as catalog invocation
coverage, not dispatch eligibility.

### Catalog Worker Capability Route

Route:

- `GET /api/v1/catalog/worker-capabilities`

Target shape changes:

| Current | Target | Reason |
| --- | --- | --- |
| `status` | `runtimeStatus` | Display-only current worker runtime label. |
| `online` | `reachable` | Avoid generic online vocabulary on a catalog diagnostic row. |
| missing reachability enum | `reachability` | Source-labeled enum/string for diagnostics. |
| `fieldSources.status=runtime` | `fieldSources.runtimeStatus=runtimeStatusDisplay` | Align with source classes. |
| `fieldSources.online=transport` | `fieldSources.reachable=workerRuntimeReachability` | Match actual reader. |
| `connections` | keep diagnostic | Transport/session evidence remains diagnostic. |

This route remains diagnostic unless a later API contract decision promotes a
separate public SDK catalog worker view.

### Catalog WorkerGroup Capability Route

Route:

- `GET /api/v1/catalog/worker-group-capabilities`

Target shape changes:

| Current | Target | Reason |
| --- | --- | --- |
| `workerIds` | `declaredWorkerIds` | Group declaration membership, not eligibility. |
| `transportOnlineCounts` | `reachableWorkerCountsByTransport` | Current formula is reachability grouped by transport hint. |
| `modelStatusCounts` | `runtimeStatusCounts` | Display/runtime status summary. |
| `dispatchEligibleCount` | `reachableUnlockedWorkerCount` | Current formula is reachable + not locked worker coverage; it is not CES composite eligibility. |

`reachableUnlockedWorkerCount` is still only a catalog coverage/diagnostic
count. It excludes reserve mutation outcome, capacity reservation races, rule
evaluation, target-worker policy, and any future policy-owned attribute index.

## SDK Decision

### Public Java SDK

Keep current worker data-plane method names:

- `WorkerClient#online(...)`
- `WorkerClient#heartbeat(...)`
- `WorkerClient#offline(...)`
- managed polling session online/heartbeat/offline behavior
- managed WebSocket session transport-owned connection behavior

These names are session/presence operations against `/worker-api/v1/**`.
Documentation and tests may continue to use them, but must not describe them as
worker inspection eligibility or scheduler eligibility.

Do not add Java SDK runtime worker browse or catalog eligibility DTOs in WES
unless a separate public-contract decision proves caller, auth, cost, and DTO
owner.

### Embedded SDK

Keep `WorkerInspectionOperations#listReachableWorkerIds()` and
`isWorkerReachable(...)` as embedded inspection APIs for now. Their existing
javadocs already state that transport route-owner leases are not read.

`WorkerSnapshot#status` remains a composite read-model field until a larger
embedded SDK model split is approved. WES docs must classify it as
`runtimeStatusDisplay`, not scheduling truth.

## Frontend Decision

Frontend must consume the target server names directly:

- workers: `runtimeStatus`, `reachability`, `reachable`
- event capability: `declaredWorkerIds`, `reachableWorkerIds`,
  `hasReachableWorkerCoverage`, `hasInvocationCoverage`
- worker group capability: `declaredWorkerIds`,
  `reachableWorkerCountsByTransport`, `runtimeStatusCounts`,
  `reachableUnlockedWorkerCount`

Frontend pages may display source-aware summaries, but must not compute a local
replacement for scheduler eligibility by combining reachability, lock,
capacity, route owner, or capability fields.

## WRP-0 Reachability Projection Decision

WES does not implement a new reachability projection.

Current decision:

- preserve the existing live `WorkerReachabilityView` read for engine
  scheduling, worker-runtime state records, embedded inspection, and server
  diagnostics;
- do not make Redis `WorkerRegistry` the reachability owner;
- do not read transport route-owner leases as worker lifecycle truth;
- do not add a batch projection writer, Redis key family, event listener, or
  transport integration in WES.

Reason:

- CES already moved dispatch gate, removing state, heartbeat deadline, and
  reserve revalidation into the registry-owned slot lifecycle path;
- remaining reachability is a distinct observation source and still has no
  measured hot-path bottleneck in this roadmap;
- a stored projection would need a named writer, reader contract, staleness
  tolerance, failure mode, and proof surface before it can be production
  owner truth.

Successor trigger:

- only open a reachability-projection implementation roadmap if profiling or
  integration proof shows the live reachability read is the scheduling
  bottleneck, or if a concrete external diagnostic caller requires bounded
  batch reachability with explicit staleness semantics.

## Out Of Scope

- No engine scheduling hot-path change.
- No Redis registry key/shape change.
- No reachability projection writer implementation.
- No Java SDK runtime worker inspection feature.
- No compatibility alias for renamed in-repo server/frontend DTO fields.

## Verification Targets

The first implementation slice should update:

- `CatalogController` and `EventCapabilityView`
- `WorkerApiController` and `WorkerCapabilityViewSupport`
- `CatalogControllerTest`, `WorkerApiControllerTest`, and auth/architecture
  guards where field names are asserted
- `frontend/src/types/catalog.ts`
- `frontend/src/types/workers.ts`
- frontend real/mock adapters and pages/tests that consume renamed fields
- `xa-mass-server/doc/API_SURFACE_INVENTORY.md`
- `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`
- Java SDK README/quickstart wording only if the presence/session wording is
  touched

Focused verification:

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dtest=WorkerApiControllerTest,CatalogControllerTest,ApiAuthInterceptorTest,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -pl sdk/xa-mass-java-sdk -am "-Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
cd frontend
corepack pnpm test:run -- workers catalog RuntimeDiscoveryPage ProjectsPage ProjectDetailPage
corepack pnpm typecheck
```
