# Worker Runtime Minimal Interface Convergence Roadmap

Status: proposed direction document.

## Problem

Worker-runtime and worker-facing SDK/server/frontend surfaces currently expose
large composite models. The most visible example is `WorkerResourceRecord`,
which carries declaration fields, runtime state, compatibility capability
hints, transport topology ids, and timestamps in one object. That shape then
crosses mutation, query, SDK snapshot, server API, frontend, and test/perf
callers.

This weakens owner boundaries:

- Callers pass a whole row when they only own one field or one operation.
- Multiple models repeat the same attributes with unclear authority.
- Worker declaration, runtime evidence, capability truth, and transport
  topology facts are bundled together.
- Agents can preserve the old problem by adding wrappers around the same fat
  DTO instead of narrowing the contracts.

This roadmap converges worker-runtime and worker-facing surfaces around a
minimum-parameter rule.

## Current Code Observations

These are current implementation facts, not target state:

- `WorkerResourceRecord` includes worker identity, `statusName`,
  `lastHeartbeat`, worker-level supported project/event hints,
  `workerGroupId`, `adapterNodeId`, `adapterId`, `onlineStrategy`,
  concurrency, attributes, and timestamps.
- `WorkerResourceDeclarationRuntime#addWorker` and `#updateWorker` accept
  `WorkerResourceRecord`, so mutation callers can supply runtime and
  compatibility fields they do not own.
- `WorkerResourceQueryRuntime#worker` and `#workers` return
  `WorkerResourceRecord`, so lookup callers receive transport topology and
  runtime evidence even when they only need identity/group evidence.
- `WorkerManager` converts between `WorkerResourceRecord` and the legacy base
  `Worker` model, including `statusName`, `lastHeartbeat`, worker-level
  capability hints, `adapterNodeId`, and `adapterId`.
- The current `WorkerDeclarationRecord` is not a minimal target. It still
  contains `adapterNodeId`, `adapterId`, `onlineStrategy`, and persistence
  timestamps.
- `WorkerResourceRuntime` is itself a broad residual surface: it aggregates
  query, declaration mutation, node-group binding mutation, and heartbeat
  refresh. Engine/starter/SDK callers still obtain that broad surface through
  runtime config.
- `MassSdkApplication` assembles public `WorkerSnapshot` objects from
  `WorkerResourceRecord`, and update paths rebuild full records to change a
  narrow concern.
- The public Java SDK `WorkerSpec` still carries `adapterNodeId`, and worker
  session helpers use it while registering workers.
- Public Java SDK `PollingWorkerSession` and `WebSocketWorkerSession` require
  `adapterNodeId` and combine topology bootstrap, node-group binding, worker
  registration, presence, capability report, and state report in one helper.
- Server external worker routes receive and return `adapterNodeId` for worker
  registration responses.
- Frontend worker list types and worker pages still include `adapterNodeId`
  and connection `adapterId`.
- Transport docs already state that `adapterId` is concrete transport runtime
  truth, but some current wording still says external worker registration or
  session APIs declare `adapterNodeId`.
- Engine scheduling still carries `adapterNodeId` through
  `TaskDispatchIntent`, `ResolvedWorkerSchedulingPolicy`, and
  `WorkerTaskSelector`, and `WorkerCandidateIndex` can acquire and guard
  candidates by adapter node. This is current code behavior, but it is not the
  target scheduling boundary.
- NodeGroupBinding dispatch gate currently depends on worker/registry
  adapter-node membership. If worker declaration stops carrying
  `adapterNodeId`, that gate needs an explicit replacement source or a
  deliberate downgrade to topology/admin diagnostics only.
- Several current records and snapshots expose raw timestamps by default,
  including worker heartbeat, create/update time, adapter-node registration
  time, node-group binding update time, and runtime observation time. Their
  existence in current interfaces is not proof that default views should return
  them.

## Owner Review

Worker declaration belongs to worker-runtime.

Worker runtime may store and expose worker identity, WorkerGroup membership,
static worker attributes, declared capacity/concurrency, and worker-owned
metadata through narrow declaration contracts.

The existing `WorkerDeclarationRecord` and `WorkerResourceRuntime` names are
not owner-proof. `WorkerDeclarationRecord` must be rewritten or replaced before
it can be the minimal declaration contract. `WorkerResourceRuntime` must be
retired or reduced to internal assembly only; production callers should depend
on narrow ports for declaration, identity/group lookup, topology admin,
runtime evidence, or presence refresh.

Worker reachability, heartbeat freshness, dispatch gates, admission,
reservation, lease, and load belong to worker-runtime runtime evidence
contracts, not to worker declaration records.

WorkerGroup owns capability truth. Worker rows must not carry project/event
capability as canonical truth. Worker-level supported project/event fields are
compatibility residue only until removed from public worker read models.

Transport owns adapter/runtime/session/endpoint topology. `adapterId`,
`adapterNodeId`, `routeKey`, `connectionId`, `transportNodeId`,
`deliveryQueueKey`, and endpoint lease evidence must not be exposed through
worker resource records, worker registration results, worker list items, or
worker inspection snapshots. They may remain in transport topology,
transport diagnostics, adapter node management, node-group binding management,
and internal assembly contracts.

NodeGroupBinding is a topology/admin relation, not a scheduling selector. If
its `enabled` or `draining` state continues to affect dispatch eligibility,
the membership evidence must be owned by topology or transport session/endpoint
evidence and must not re-enter worker declaration, task dispatch intent, or
worker candidate selection as `adapterNodeId`.

Runtime worker selection owns concrete worker choice inside an already selected
WorkerGroup universe. `adapterNodeId` must not be a task scheduling selector.
If product behavior needs locality, deployment domain, region, pool, device
family, or similar worker-universe constraints, those facts should be modeled
as worker attributes and consumed through an explicit worker scheduling policy
or candidate bucket policy. They must not be expressed by exposing transport
owner identifiers such as `adapterNodeId`.

Time evidence belongs to the owner that needs it to drive behavior. Raw
timestamps are not neutral view fields. A claim, refresh, lease, deadline, or
heartbeat-ingress command may return `observedAt`, `deadline`, or `expiresAt`
when the caller must use that value to renew, expire, compare freshness, or
drive a bounded runtime decision. Default resource views, worker list views,
catalog views, and identity lookups must return state labels or owner-specific
facts instead of raw timestamps. Operator diagnostics or audit views may expose
timestamps only through an explicitly named diagnostic/audit snapshot.

Server, SDK, and frontend own product/API surfaces. They may compose
worker-facing snapshots from owner-specific contracts, but must not make a
fat worker model the public compatibility anchor.

## Boundary Decision

Apply a minimum-parameter rule:

1. A method accepts only the identifiers and fields owned by that operation.
2. A method returns only the evidence its caller needs for its owner boundary.
3. A composite object is allowed only when its name and package make it an
   explicit inspection or diagnostic snapshot.
4. No hot-path or mutation contract may accept a composite inspection snapshot.
5. No worker-facing public DTO may expose transport topology internals.
6. No field may appear in nested objects as a second authority for the same
   fact unless one side is explicitly marked as diagnostic copy.
7. `workerGroupId` must be passed directly on hot paths when known. Do not
   hide group-scoped operations behind worker-id reverse lookup.
8. `adapterNodeId` must not appear in task dispatch intent, resolved worker
   scheduling policy, or worker candidate selector contracts.
9. Default view/query/list contracts must not return raw timestamps. Return
   timestamps only from command/evidence results that need them to drive
   behavior, or from explicitly named diagnostic/audit snapshots.
10. Broad aggregate interfaces are not acceptable targets. `WorkerResourceRuntime`
   must not remain the default port exposed to engine, SDK, starter, or server
   callers.
11. Public Java SDK worker session helpers are worker-facing public surface.
   They must not require topology ids or silently combine topology bootstrap
   with ordinary worker session startup.
12. Timestamp guards must be scoped to default worker list/snapshot/catalog
   contracts. Command requests, deadline evidence, API-key expiry, task audit,
   and explicitly named diagnostic/audit surfaces are not governed by the
   worker default-view timestamp rule.
13. In-repo callers are not compatibility constraints. Replace them instead of
   adding pass-through wrappers or aliases.

## Target Shape

### Worker Declaration

Worker declaration input should be narrow:

```text
workerId
workerGroupId
transportHint
maxConcurrentWork
agentVersion
attributes
create/update timestamps only where persistence owner needs them
```

It must not include:

```text
statusName
lastHeartbeat
reachability
dispatch gate state
reserved/active lease/load state
supportedProjects
supportedEventCodes
adapterId
adapterNodeId
routeKey
connectionId
transportNodeId
deliveryQueueKey
endpoint lease evidence
```

`transportHint` remains a coarse worker-facing family hint. It is not a
concrete adapter identity and must not be enough to reconstruct transport
runtime topology.

The current `WorkerDeclarationRecord` must not be treated as this target
shape. It should either be rewritten in place or replaced by a new declaration
command/record whose name does not preserve the old topology fields by
accident. `onlineStrategy` must be explicitly resolved: if it means coarse
worker-facing transport family, converge it to `transportHint`; if it means
adapter/runtime strategy, remove it from worker declaration.

### Port Split

The target worker-runtime ports are narrow:

- worker declaration mutation: declare, update, and delete worker-owned
  declaration fields only
- worker identity/group lookup: `workerId`, `workerGroupId`, and declared
  worker attributes needed by callers that do not need runtime evidence
- worker runtime evidence: reachability, readiness, occupancy, dispatch gate,
  capacity, reservation, and lease observations
- worker presence refresh: session/heartbeat ingress that refreshes
  worker-runtime evidence
- topology admin: adapter node and node-group binding administration
- diagnostics/audit: explicitly named snapshots that can carry wider evidence

`WorkerResourceRuntime` is not one of these target ports. Keeping it as the
primary externally injected surface would preserve the same broad owner leak
even if the DTOs are renamed.

### Time Evidence

Default read models should avoid raw timestamps. They should expose derived
state when the caller needs a decision:

```text
reachable / reachabilityState
readinessState
occupancyState
dispatchEnabled
removing
capacity / reservation counts where explicitly needed
```

They should not expose fields such as:

```text
lastHeartbeat
observedAt
createTime
updateTime
registeredAt
lastSeenAt
expiresAt
deadline
leaseExpireAt
```

unless the contract is one of:

- a command or claim result whose caller must act on the timestamp
- a refresh result whose caller must renew or compare freshness
- a lease/deadline evidence result whose caller must expire or retry
- an explicitly named diagnostic or audit snapshot

Presence, endpoint lease, and registry deadline implementations may keep
timestamps internally. They must not turn those timestamps into default worker
resource, catalog, or SDK list fields.

### Worker Scheduling Constraints

Worker scheduling constraints should be owner-named instead of transport-named:

- WorkerGroup selects the worker universe.
- Target worker selects one concrete worker when explicitly requested.
- Worker attributes may express business or deployment evidence such as
  `region`, `pool`, `deviceFamily`, `deploymentDomain`, or `capabilityTier`.
- Candidate bucket policy may index those attributes when a bounded source
  read needs an index.

`adapterNodeId` is not a scheduling constraint. It may be used by topology or
admin operations to drain one adapter node's hosting relation, but it must not
enter:

- `TaskDispatchIntent`
- `ResolvedWorkerSchedulingPolicy`
- `WorkerTaskSelector`
- task shared config
- public task create/update API
- worker candidate source API

If a current use case appears to require adapter-node scheduling, the roadmap
must first name the real product constraint and model it as a worker attribute
or explicit worker scheduling policy field.

### Topology Gate

`NodeGroupBinding.enabled` and `NodeGroupBinding.draining` need a separate
decision before worker registration removes `adapterNodeId`.

Allowed target choices:

- keep NodeGroupBinding as topology/admin diagnostics only; it no longer
  drives worker dispatch gates
- keep NodeGroupBinding as an operational dispatch gate, but feed it from an
  internal topology/session/endpoint membership source that is not worker
  declaration and not task scheduling input

Not allowed:

- retaining `adapterNodeId` on worker declaration solely to make the current
  gate implementation keep working
- using `adapterNodeId` as task shared config, scheduling policy, or candidate
  selector
- reintroducing adapter-node scoped candidate acquisition as an optimization
  before a real worker attribute or policy dimension owns the constraint

### Worker Lookup

Worker lookup contracts should have explicit shapes:

- `WorkerIdentityRecord`: `workerId`, `workerGroupId`, static attributes,
  declared capacity/concurrency, optional `transportHint`.
- `WorkerGroupMembershipRecord`: `workerId`, `workerGroupId` for places that
  only need reverse group lookup.
- `WorkerRuntimeStateRecord`: reachability, heartbeat freshness, readiness,
  dispatch gate, load, reservation, lease observations.
- `WorkerInspectionSnapshot`: SDK/server/operator composite view assembled
  from declaration, runtime state, group capability, and diagnostics.

`WorkerResourceRecord` should stop being the default mutation and query shape.
If it remains during convergence, it must be explicitly classified as
compatibility residue or renamed/contained as an inspection snapshot.

### Transport Topology

Transport topology fields remain available only through transport/topology
surfaces:

- `AdapterNodeRecord`
- `NodeGroupBindingRecord`
- endpoint lease diagnostics
- transport runtime diagnostics
- adapter/session internal records

Worker public registration and worker public inspection do not expose
`adapterNodeId` or `adapterId`.

## Non-Goals

- Do not redesign worker candidate bucket rules.
- Do not change Redis key families unless a slice explicitly touches a
  runtime contract.
- Do not change endpoint lease algorithms.
- Do not introduce a new policy catalog or worker matching DSL.
- Do not remove adapter node or node-group binding admin/topology surfaces.
- Do not redesign frontend pages beyond removing or relocating fields needed
  by this roadmap.
- Do not add compatibility wrappers that preserve the same fat DTO under a new
  name.

## Do Not Start With

Do not start by globally deleting `adapterNodeId`, `adapterId`, or
`WorkerResourceRecord`.

Do not remove public worker registration `adapterNodeId` before the same
executable slice has a topology-neutral worker registration path, an
adapter-node-free scheduling selector path, and an explicit decision for the
current NodeGroupBinding dispatch gate. Removing only the registration field
would create either a hidden reverse lookup dependency or a broken gate.

First classify every usage as one of:

- worker declaration
- worker lookup
- worker runtime evidence
- worker inspection snapshot
- transport topology/admin
- transport diagnostics
- SDK/server public contract
- frontend display
- test fixture
- stale documentation

Only then move each caller to the narrow owner contract. `AdapterNode` and
`NodeGroupBinding` surfaces are valid topology surfaces; the bug is letting
their identities leak through worker resource and worker registration models.

## WMI-0 - Inventory And Contract Allowlist

Goal: produce a code-grounded inventory and allowlist before changing behavior.

Scope:

- Inventory all production and test usages of:
  - `WorkerResourceRecord`
  - `WorkerDeclarationRecord`
  - `WorkerResourceRuntime`
  - `WorkerResourceQueryRuntime#worker/workers`
  - `WorkerResourceDeclarationRuntime#addWorker/updateWorker`
  - `TaskDispatchIntent#adapterNodeId`
  - `ResolvedWorkerSchedulingPolicy#adapterNodeId`
  - `WorkerTaskSelector#adapterNodeId`
  - `WorkerRegistry#acquireCandidates(groupId, adapterNodeId, ...)`
  - `WorkerRegistry#disableDispatchForAdapterNodeGroup`
  - `WorkerRelationshipOwner#applyNodeGroupBindingDispatchGate`
  - `WorkerMeta#adapterNodeId`
  - `MassSdkApplication#normalizeWorkerRegistration`
  - Java SDK `PollingWorkerSession` and `WebSocketWorkerSession`
  - raw timestamp fields in default view/query/list contracts, including
    `lastHeartbeat`, `observedAt`, `createTime`, `updateTime`, `registeredAt`,
    `lastSeenAt`, `expiresAt`, `deadline`, and `leaseExpireAt`
  - `WorkerSnapshot`
  - `WorkerSpec`
  - `ExternalWorkerRegisterApiRequest`
  - frontend `WorkerListItem`
  - `adapterNodeId` and `adapterId` in worker-facing surfaces
- Classify each usage by owner category.
- Separate legal topology/admin surfaces from illegal worker-facing model
  surfaces.
- Separate legal topology/admin drain gates from illegal task scheduling
  selectors.
- Record whether NodeGroupBinding remains an operational dispatch gate or
  becomes topology/admin diagnostics only. If it remains operational, name the
  internal membership source that replaces worker declaration `adapterNodeId`.
- Separate normal worker session startup from topology bootstrap in the Java
  SDK session helpers.
- Separate legal command/deadline/audit timestamps from illegal default view
  timestamps.
- Define an allowlist for where `adapterNodeId` and `adapterId` may remain.
  Define an allowlist for where raw timestamps may remain.

Acceptance:

- A sibling inventory file records current usages, owner category, and target.
- The roadmap links to the inventory.
- Inventory distinguishes main-source usage from tests/fixtures/docs.
- No code behavior changes in this slice.

Suggested verification:

```powershell
rg -n "WorkerResourceRecord|WorkerDeclarationRecord|WorkerResourceRuntime|WorkerSnapshot|WorkerSpec|PollingWorkerSession|WebSocketWorkerSession|ExternalWorkerRegisterApiRequest|WorkerListItem|adapterNodeId|adapterId|lastHeartbeat|observedAt|createTime|updateTime|registeredAt|lastSeenAt|expiresAt|deadline|leaseExpireAt" `
  xa-mass-worker-runtime/src/main/java `
  xa-mass-engine/src/main/java `
  sdk/xa-mass-embedded-sdk-api/src/main/java `
  sdk/xa-mass-embedded-sdk/src/main/java `
  sdk/xa-mass-java-sdk/src/main/java `
  xa-mass-server/src/main/java `
  frontend/src `
  --glob '!**/target/**'
```

## WMI-1 - Define Minimal Worker Contracts

Goal: introduce or retarget worker-runtime contracts around operation-owned
models.

Scope:

- Rewrite or replace the current `WorkerDeclarationRecord`; do not use it as
  the target minimal declaration shape while it still contains
  `adapterNodeId`, `adapterId`, or adapter/runtime strategy fields.
- Replace declaration mutation input with a declaration-only command/record.
- Add lightweight lookup records for worker identity and group membership.
- Keep runtime evidence reads on `WorkerSchedulingViewRuntime` and
  `WorkerRuntimeStateRecord`.
- Define an explicit inspection snapshot contract for SDK/server/operator
  surfaces if a composite view is still needed.
- Split `WorkerResourceRuntime` into narrow ports or reduce it to internal
  assembly only. Production callers should depend on operation-owned ports,
  not the broad aggregate.
- Do not add a same-module wrapper that only forwards to the current fat
  `WorkerResourceRecord`.

Acceptance:

- `WorkerDeclarationRecord` is replaced or rewritten so declaration contracts
  do not preserve transport topology fields by name inertia.
- `WorkerResourceDeclarationRuntime` no longer accepts
  `WorkerResourceRecord` for worker add/update.
- Query callers that only need identity/group membership no longer receive
  `WorkerResourceRecord`.
- `WorkerResourceRecord` is either unused by mutation/query contracts or
  explicitly scoped to a compatibility snapshot path.
- `WorkerResourceRuntime` is no longer the default injected port for engine,
  SDK, starter, or server production callers.
- New contracts do not contain `adapterId` or `adapterNodeId`.
- New contracts do not contain worker-level supported project/event
  capability hints.
- Default query/list contracts do not contain raw timestamps. Timestamp fields
  are confined to command/evidence results or explicitly named
  diagnostic/audit snapshots.

Suggested verification:

```powershell
rg -n "addWorker\\(WorkerResourceRecord|updateWorker\\(WorkerResourceRecord|Optional<WorkerResourceRecord>|List<WorkerResourceRecord>" `
  xa-mass-worker-runtime/src/main/java `
  sdk/xa-mass-embedded-sdk/src/main/java `
  xa-mass-engine/src/main/java `
  xa-mass-server/src/main/java `
  --glob '!**/target/**'
```

## WMI-2 - Converge Registration, Scheduling Constraints, And Topology Gate

Goal: remove `adapterNodeId` from worker declaration and runtime worker
selection without creating a broken NodeGroupBinding gate or a hidden reverse
lookup path.

This is one executable slice. Do not land worker registration cleanup while
the scheduling selector and NodeGroupBinding dispatch-gate decision still
depend on worker declaration carrying `adapterNodeId`.

Scope:

- Define how a worker becomes a schedulable WorkerGroup member using
  `workerId`, `workerGroupId`, declaration fields, and optional coarse
  `transportHint`; do not require public `adapterNodeId`.
- Retarget SDK embedded registration mapping away from `WorkerResourceRecord`
  and away from `adapterNodeId`.
- Retarget Java SDK worker registration request generation and normal worker
  session helpers away from `adapterNodeId`.
- Split Java SDK `PollingWorkerSession` and `WebSocketWorkerSession` behavior:
  normal worker session startup registers/updates worker declaration and
  presence only; topology bootstrap, if still needed, is an explicit topology
  admin API and not a required worker session parameter.
- Retarget server external worker registration away from `adapterNodeId`
  request and response fields.
- Remove full-row update patterns where a caller reads a worker record only to
  rebuild it for one field.
- Decide whether worker-level `updateWorkerSupportedProjects` is removed,
  redirected to WorkerGroup capability, or kept only as explicitly deprecated
  compatibility residue with no scheduling authority.
- Remove `adapterNodeId` from `TaskDispatchIntent`.
- Remove `adapterNodeId` from `ResolvedWorkerSchedulingPolicy`.
- Remove `adapterNodeId` from `WorkerTaskSelector`.
- Remove adapter-node scoped candidate acquisition from the engine-to-worker
  runtime mainline.
- Remove task shared config `adapterNodeId` as a scheduling selector, or
  reclassify it as deprecated/stale config residue with no runtime consumer.
- For any legitimate current use case, introduce or reuse worker attributes
  such as `region`, `pool`, or `deploymentDomain`, and route them through the
  worker scheduling policy/candidate bucket policy owner instead of transport
  topology ids.
- Resolve NodeGroupBinding dispatch-gate ownership in the same slice:
  - either keep NodeGroupBinding as topology/admin diagnostics only, with no
    worker dispatch-gate effect
  - or keep it as an operational gate, but feed membership from an internal
    topology/session/endpoint source that is not worker declaration and not
    task scheduling input

Acceptance:

- Worker registration input is minimal and worker-owned.
- Worker registration result does not return `adapterNodeId` or `adapterId`.
- No public worker registration path or normal Java SDK worker session helper
  requires transport topology ids.
- Declaration update callers cannot supply `statusName`, `lastHeartbeat`,
  capability hints, or transport topology fields.
- Declaration writers cannot supply raw runtime timestamps except persistence
  timestamps owned by the declaration store adapter. Public declaration APIs
  should not accept those persistence timestamps by default.
- Engine scheduling intent, resolved worker scheduling policy, and
  worker-runtime selector contracts contain no `adapterNodeId`.
- `WorkerCandidateIndex` does not acquire or reject scheduling candidates by
  adapter-node selector.
- Task shared config no longer provides adapter-node scheduling input to the
  engine mainline.
- NodeGroupBinding gate behavior is explicitly implemented according to the
  chosen target. If operational, it is driven by topology/session/endpoint
  membership evidence; if diagnostic-only, tests and docs stop treating it as
  scheduling proof.
- Any remaining `adapterNodeId` usage in worker-runtime is topology/admin,
  diagnostics, or explicitly documented migration residue called out in the
  inventory.
- Tests prove worker registration still creates a schedulable worker in the
  selected WorkerGroup, WorkerGroup-scoped scheduling still works, and the
  chosen NodeGroupBinding behavior is enforced.

Suggested verification:

```powershell
rg -n "adapterNodeId" `
  sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk `
  sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker `
  xa-mass-server/src/main/java/com/xa/mass/api/internal `
  xa-mass-engine/src/main/java/com/xa/mass/engine/runtime/scheduling `
  xa-mass-engine/src/main/java/com/xa/mass/engine/strategy `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/candidate `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCandidateIndex.java `
  xa-mass-base/src/main/java/com/xa/mass/base/model/TaskSharedConfig.java `
  --glob '!**/target/**'

.\mvnw.cmd -pl xa-mass-worker-runtime -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-java-sdk -DskipTests compile
.\mvnw.cmd -pl xa-mass-server -DskipTests compile

.\mvnw.cmd -pl xa-mass-engine `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest" test

.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerCandidateIndexTest,WorkerManagerTest" test
```

## WMI-3 - Retarget Worker Reads And Inspection

Goal: stop returning transport topology and runtime evidence through worker
resource reads.

Scope:

- Retarget SDK worker snapshot assembly to compose from:
  - declaration/identity lookup
  - WorkerGroup capability view
  - runtime evidence view
  - optional diagnostics
- Remove `adapterNodeId` and `adapterId` from worker snapshot/list DTOs.
- Remove worker-level capability hint fallback from public worker snapshots
  after WorkerGroup capability is available.
- Keep topology information on adapter/node topology endpoints only.
- Ensure reachability/status wording comes from worker-runtime evidence, not
  legacy `statusName`.
- Remove raw timestamps from default worker list/catalog/snapshot views. If an
  operator needs times, expose them only through an explicit diagnostic or
  audit snapshot.

Acceptance:

- `WorkerSnapshot` and server worker-list response contain no `adapterNodeId`
  or `adapterId`.
- Frontend `WorkerListItem` contains no `adapterNodeId`, no connection
  `adapterId`, and no endpoint/session internals.
- Worker inspection can still show `workerId`, `workerGroupId`,
  `transportHint`, capability from WorkerGroup, reachability, readiness,
  and occupancy/load facts appropriate to the view.
- Default worker inspection/list/catalog views contain no `lastHeartbeat`,
  `observedAt`, `createTime`, `updateTime`, `registeredAt`, `lastSeenAt`,
  `expiresAt`, `deadline`, or `leaseExpireAt`.
- Explicit diagnostic/audit snapshots, if kept, name their diagnostic/audit
  role and are not reused as mutation, scheduling, or public default list
  contracts.
- Catalog worker capability routes do not rebuild capability from worker row
  compatibility fields when WorkerGroup capability is available.

Suggested verification:

```powershell
rg -n "adapterNodeId|adapterId|routeKey|connectionId|transportNodeId|deliveryQueueKey|lastHeartbeat|observedAt|createTime|updateTime|registeredAt|lastSeenAt|expiresAt|deadline|leaseExpireAt" `
  sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/model/WorkerSnapshot.java `
  xa-mass-server/src/main/java/com/xa/mass/api/internal/WorkerApiController.java `
  xa-mass-server/src/main/java/com/xa/mass/api/internal/CatalogController.java `
  xa-mass-server/src/main/java/com/xa/mass/api/internal/WorkerCapabilityViewSupport.java `
  frontend/src/types/workers.ts `
  frontend/src/types/catalog.ts `
  frontend/src/pages/resources/workers `
  frontend/src/pages/runtime/RuntimeDiscoveryPage.vue `
  --glob '!**/target/**'
```

The command should only report allowlisted topology/admin/diagnostic surfaces.
Do not turn legal command, deadline, API-key expiry, task audit, or state
report timestamp fields into worker default-view failures.

## WMI-4 - Retarget Transport And Starter Assembly

Goal: remove worker resource records as the source of transport runtime
identity.

Scope:

- Retarget starter delivery and raw side-channel assembly that reads
  `worker.adapterId()` or `worker.adapterNodeId()` from worker resource
  records.
- Use transport runtime registry, endpoint lease/session evidence, or
  topology/admin records where transport identity is actually needed.
- Keep `transportHint` as worker-facing family input only.
- Keep `adapterId` inside transport runtime and adapter-local dispatch.
- Keep `adapterNodeId` inside adapter node and node-group binding topology.

Acceptance:

- Starter/transport assembly does not read `adapterId` or `adapterNodeId` from
  worker declaration or worker resource snapshot.
- Assigned task delivery continues to use `deliveryBucketId + selectedWorkerId`
  and handoff-private selected-worker consumer evidence.
- Endpoint/session topology remains transport-owned and is not projected into
  worker declaration.
- Raw/debug side-channel paths, if retained, obtain adapter identity from
  transport-owned channels or diagnostics, not worker resource records.

Suggested verification:

```powershell
rg -n "worker\\.adapterId\\(|worker\\.adapterNodeId\\(|getAdapterId\\(\\)|getAdapterNodeId\\(\\)" `
  sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter `
  transport `
  --glob '!**/target/**'
```

## WMI-5 - Server, SDK, And Frontend Contract Cleanup

Goal: align external product/API surfaces with the minimal worker model.

Scope:

- Update Java SDK worker API models and tests.
- Update Java SDK `PollingWorkerSession` and `WebSocketWorkerSession` so the
  normal worker session builder does not require `adapterNodeId`; topology
  bootstrap, if retained, moves to an explicit topology/admin API or option.
- Update embedded SDK quickstart and README.
- Update server internal API reference.
- Update frontend worker/catalog types, mock data, tests, and pages.
- Keep adapter-node and node-group binding admin APIs available as topology
  APIs, not worker registration/read fields.
- Add explicit migration notes because this is a public SDK/API contract break
  inside the current pre-release stage.

Acceptance:

- Public worker registration examples do not mention `adapterNodeId` or
  `adapterId`.
- Public Java SDK worker session examples do not require `adapterNodeId` for
  ordinary worker startup.
- Worker presence APIs return only worker identity/action/coarse
  `transportHint` when needed.
- Worker list and catalog responses do not expose transport internals.
- Worker list and catalog responses do not expose raw timestamps by default.
- Adapter node and node-group binding surfaces remain reachable through their
  own endpoints/SDK models.
- Frontend still has a way to inspect topology if a topology/admin page
  exists; worker list no longer displays it as worker state.

Suggested verification:

```powershell
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-java-sdk -DskipTests compile
.\mvnw.cmd -pl xa-mass-server -DskipTests compile
rg -n "adapterNodeId|adapterId" `
  sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/model `
  sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker `
  sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md `
  xa-mass-server/doc `
  frontend/src/types `
  frontend/src/api `
  frontend/src/pages/resources/workers `
  frontend/src/pages/runtime/RuntimeDiscoveryPage.vue `
  --glob '!**/target/**'
```

Any remaining hits must be topology/admin, diagnostics, tests for those
surfaces, or documented migration notes.

## WMI-6 - Guards And Residue Removal

Goal: prevent fat worker models and transport identity leakage from returning.

Scope:

- Add architecture guards for default public worker read/list/catalog
  DTO/model packages.
- Add guards for worker-runtime mutation/query contract shapes.
- Add source scans that fail on `adapterId`/`adapterNodeId` in worker-facing
  SDK/server/frontend models while allowlisting topology/admin packages.
- Scope raw timestamp guards to default worker list/snapshot/catalog contracts.
  Do not scan the entire server API model package with bare timestamp field
  names because command requests, state reports, deadlines, API keys, task
  audit, and trace/audit DTOs are allowed to carry time evidence.
- Remove stale documentation that presents `WorkerResourceRecord` as the
  preferred API shape.
- Archive or update any roadmap/inventory that is superseded by the final
  contract.

Acceptance:

- Guards fail if default worker read/list/catalog DTOs contain:
  - `adapterId`
  - `adapterNodeId`
  - `routeKey`
  - `connectionId`
  - `transportNodeId`
  - `deliveryQueueKey`
  - default raw timestamp fields such as `lastHeartbeat`, `observedAt`,
    `createTime`, `updateTime`, `registeredAt`, `lastSeenAt`, `expiresAt`,
    `deadline`, or `leaseExpireAt`
- Guards fail if declaration mutation accepts inspection/composite snapshots.
- Guards fail if worker snapshots include worker-level capability hints as
  capability truth.
- Guards allow timestamps only in command/evidence result packages and
  explicitly named diagnostic/audit snapshot packages.
- Owning docs state the implemented contract, not target state.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerDeclarationBoundaryGuardTest,WorkerManagerTest" test

.\mvnw.cmd -pl transport/transport_runtime `
  "-Dtest=TransportConvergenceArchitectureGuardTest" test

.\mvnw.cmd -pl xa-mass-server `
  "-Dtest=*ArchitectureGuardTest,*Contract*Test" test
```

Exact guard class names may change during implementation; update this section
when the guard tests are added.

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

- Worker declaration mutation contracts no longer accept fat composite worker
  records.
- The current `WorkerDeclarationRecord` has been rewritten or replaced; no
  minimal declaration contract keeps `adapterId` or `adapterNodeId`.
- `WorkerResourceRuntime` is retired as a production caller surface or reduced
  to internal assembly only. Engine, SDK, starter, and server callers use
  narrow owner ports.
- Worker lookup contracts return minimal records, not composite inspection
  snapshots.
- Worker public SDK/server/frontend surfaces do not expose transport topology
  internals.
- Public Java SDK normal worker session helpers no longer require
  `adapterNodeId`; topology bootstrap, if present, is explicit topology/admin
  behavior.
- Transport topology/admin surfaces still expose adapter node and binding
  information where that is their explicit owner boundary.
- NodeGroupBinding dispatch behavior is explicitly decided and implemented:
  either diagnostics-only, or operational with internal topology/session/
  endpoint membership evidence rather than worker declaration `adapterNodeId`.
- Worker capability truth comes from WorkerGroup capability contracts, not
  worker-level supported project/event fields.
- Runtime scheduling evidence comes from worker-runtime evidence/admission
  contracts, not `WorkerResourceRecord#statusName` or `lastHeartbeat`.
- Default worker-facing views do not return raw timestamps. Timestamp evidence
  remains internal, command/deadline-driving, or explicitly diagnostic/audit.
- All in-repo callers are retargeted; no compatibility wrapper preserves the
  old fat model as a second live path.
- Architecture guards cover default public worker read/list/catalog DTO/model
  packages and worker-runtime mutation/query interfaces.
- Owning docs are updated and stale roadmap residue is archived after a
  residue scan.

## Verification Candidates

Focused compile:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-java-sdk -DskipTests compile
.\mvnw.cmd -pl xa-mass-server -DskipTests compile
```

Focused tests:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerManagerTest,WorkerDeclarationBoundaryGuardTest,WorkerCandidateIndexTest" test

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk `
  "-Dtest=MassSdkTest,PullWorkerSessionTest,MassApplicationDistributedTransportTest" test

.\mvnw.cmd -pl sdk/xa-mass-java-sdk test

.\mvnw.cmd -pl xa-mass-server `
  "-Dtest=Worker*Test,Catalog*Test,*ArchitectureGuardTest" test
```

Frontend contract verification:

```powershell
cd frontend
npm test -- --run
```

If local frontend tooling uses a different command, correct this during
WMI-5 instead of treating the command as proof.

Residue scans:

```powershell
rg -n "WorkerResourceRecord" `
  sdk xa-mass-server frontend xa-mass-engine transport `
  --glob '!**/target/**'

rg -n "adapterNodeId|adapterId|routeKey|connectionId|transportNodeId|deliveryQueueKey|lastHeartbeat|observedAt|createTime|updateTime|registeredAt|lastSeenAt|expiresAt|deadline|leaseExpireAt" `
  sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/model/WorkerSnapshot.java `
  xa-mass-server/src/main/java/com/xa/mass/api/internal/WorkerApiController.java `
  xa-mass-server/src/main/java/com/xa/mass/api/internal/CatalogController.java `
  xa-mass-server/src/main/java/com/xa/mass/api/internal/WorkerCapabilityViewSupport.java `
  frontend/src/types/workers.ts `
  frontend/src/types/catalog.ts `
  frontend/src/pages/resources/workers `
  frontend/src/pages/runtime/RuntimeDiscoveryPage.vue `
  --glob '!**/target/**'
```

Remaining hits must be allowlisted topology/admin, transport diagnostics,
internal transport runtime, or tests for those explicit surfaces.
