# Worker Runtime Minimal Interface Convergence Roadmap

Status: active roadmap; WMI mainline convergence implemented and verified in
the current workspace slice. Legacy/base/diagnostic residue remains tracked
below and should not be treated as a second live mainline.

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

## Current Mainline State

These are implementation facts after the WMI mainline slice in this workspace:

- `WorkerDeclarationRecord` is the declaration mutation shape and contains
  only worker-owned declaration fields: `workerId`, `workerGroupId`,
  `transportHint`, `agentVersion`, `maxConcurrentWork`, and `attributes`.
- `WorkerResourceRecord` is a minimal lookup read model with the same
  worker-owned identity/declaration facts. It no longer carries runtime status,
  heartbeat time, worker-level capability hints, adapter topology ids, or
  create/update timestamps.
- `WorkerResourceDeclarationRuntime#addWorker` and `#updateWorker` accept
  `WorkerDeclarationRecord`, not `WorkerResourceRecord`.
- The broad `WorkerResourceRuntime` aggregate has been removed. Engine, SDK,
  starter, and server assembly use narrow ports such as declaration mutation,
  resource query, node binding, heartbeat refresh, scheduling evidence, and
  control/runtime views.
- Worker candidate acquisition is WorkerGroup-scoped. `WorkerTaskSelector`,
  `WorkerCandidateSamplingContext`, `WorkerRegistry#acquireCandidates(...)`,
  memory registry, and Redis registry no longer accept `adapterNodeId` as a
  candidate-source dimension.
- Engine scheduling intent and resolved worker scheduling policy no longer
  carry `adapterNodeId`. `TaskDispatchBinding` and ordinary assignment evidence
  no longer expose adapter-node worker-selection evidence.
- `WorkerMeta` no longer serializes or exposes `adapterNodeId` or `adapterId`.
- NodeGroupBinding is topology/admin diagnostics for this roadmap. It remains
  available as adapter-node to WorkerGroup metadata but no longer drives worker
  dispatch eligibility.
- Embedded SDK worker registration, Java SDK worker registration/session
  helpers, server external worker registration, and frontend worker default
  views no longer require or return `adapterNodeId` / `adapterId`.
- Public `WorkerSnapshot` exposes `transportHint` and no longer has the
  compatibility `getOnlineStrategy()` alias. Default worker list/snapshot
  views do not expose raw heartbeat/create/update timestamps.
- WorkerGroup capability remains the capability truth used to compose public
  worker/catalog views; worker rows are not the canonical project/event source.

Known residual surfaces:

- The legacy base `com.xa.mass.base.model.Worker` still has historical fields
  such as `onlineStrategy`, `adapterNodeId`, `adapterId`, timestamps, and
  worker-level capability hints. Current WMI mainline treats this as legacy
  model residue, not the target worker-runtime contract.
- Engine monkey/diagnostic snapshot code still has historical
  `onlineStrategy` naming. It is not a worker default-view or hot-path
  scheduling contract and should be handled by a later diagnostic cleanup
  slice.

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

Do not treat `adapterNodeId` removal from scheduling as complete while
`TaskDispatchBinding`, `WorkerSchedulingView`, assignment evidence, trace
events, runtime-api candidate sampling, or Redis candidate buckets still carry
adapter-node worker-selection evidence.

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

Inventory artifact:
[WORKER_RUNTIME_MINIMAL_INTERFACE_CONVERGENCE_INVENTORY.md](WORKER_RUNTIME_MINIMAL_INTERFACE_CONVERGENCE_INVENTORY.md).

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
  - `WorkerSchedulingView#adapterNodeId`
  - `TaskDispatchBinding#adapterNodeId`
  - `SimpleTaskDispatchBinder` dispatch evidence `adapterNodeId`
  - `TraceEventLogger` worker-selection `adapterNodeId`
  - trace sink node context `adapterNodeId`
  - `WorkerCandidateSamplingContext#adapterNodeId`
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
- For every production `WorkerResourceRuntime` caller, record the target
  narrow port:
  - declaration mutation
  - identity/group lookup
  - runtime evidence
  - presence ingress
  - topology admin
  - diagnostic snapshot
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
- Inventory contains a caller mapping for every production
  `WorkerResourceRuntime`, `WorkerResourceQueryRuntime`,
  `WorkerResourceDeclarationRuntime`, and `WorkerNodeBindingRuntime` caller.
- Inventory classifies `TaskDispatchBinding`, `WorkerSchedulingView`,
  `SimpleTaskDispatchBinder`, `TraceEventLogger`,
  `WorkerCandidateSamplingContext`, and Redis node candidate bucket usages.
- No code behavior changes in this slice.

Suggested verification:

```powershell
rg -n "WorkerResourceRecord|WorkerDeclarationRecord|WorkerResourceRuntime|WorkerSnapshot|WorkerSpec|PollingWorkerSession|WebSocketWorkerSession|ExternalWorkerRegisterApiRequest|WorkerListItem|TaskDispatchBinding|WorkerSchedulingView|WorkerCandidateSamplingContext|TraceEventLogger|adapterNodeId|adapterId|lastHeartbeat|observedAt|createTime|updateTime|registeredAt|lastSeenAt|expiresAt|deadline|leaseExpireAt" `
  xa-mass-worker-runtime/src/main/java `
  xa-mass-engine/src/main/java `
  xa-mass-base/src/main/java `
  platform_infra/mass-runtime-api/src/main/java `
  platform_infra/mass-runtime-memory/src/main/java `
  platform_infra/mass-runtime-redis/src/main/java `
  platform_infra/mass-trace-sink/src/main/java `
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
- Use the WMI-0 caller mapping to move each production caller to its target
  narrow port. Do not introduce a generic `minimal` facade that still forwards
  all behavior through `WorkerResourceRuntime`.
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
- Each previous `WorkerResourceRuntime` production caller has an explicit
  target port in code and in the inventory.
- No new facade/wrapper becomes a second broad worker-runtime aggregate.
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

## WMI-1A - NodeGroupBinding Dispatch Gate Decision

Goal: record the topology gate decision before any slice removes
`adapterNodeId` from worker declaration or candidate selection.

Decision: `NodeGroupBinding` is topology/admin diagnostics only. It remains
the owner for adapter-node to WorkerGroup relation metadata, including
enabled/draining fields, but it no longer drives worker dispatch eligibility.

Rationale:

- Scheduling is WorkerGroup and worker runtime evidence based.
- Adapter-node topology must not become a worker-selection selector.
- Keeping NodeGroupBinding operational would require a second internal
  membership truth for `(adapterNodeId, workerGroupId)`, preserving the same
  topology/lifecycle coupling this roadmap removes.
- Worker state report and dispatch gate mechanisms already own worker-level
  drain/disable semantics.

Scope:

- Remove NodeGroupBinding from scheduling proof expectations.
- Identify tests/docs that currently treat it as a dispatch gate.
- Keep adapter node and node-group binding APIs as topology/admin surfaces.
- Record the decision in the WMI inventory.

Acceptance:

- The roadmap no longer presents NodeGroupBinding gate semantics as an open
  option inside WMI-2.x implementation.
- No later slice depends on NodeGroupBinding to prove worker dispatch
  exclusion.
- Adapter node and node-group binding topology/admin APIs remain in scope and
  are not removed by this decision.
- Verification commands for diagnostics-only behavior are listed before
  WMI-2.x implementation starts.

Suggested verification:

```powershell
rg -n "applyNodeGroupBindingDispatchGate|disableDispatchForAdapterNodeGroup|clearDispatchDisableForAdapterNodeGroup|workerIdsByAdapterNodeGroup" `
  xa-mass-worker-runtime/src/main/java `
  platform_infra/mass-runtime-api/src/main/java `
  platform_infra/mass-runtime-memory/src/main/java `
  platform_infra/mass-runtime-redis/src/main/java `
  xa-mass-worker-runtime/src/test/java `
  xa-mass-engine/src/test/java `
  --glob '!**/target/**'
```

## WMI-2.0 - Implement NodeGroupBinding Gate Decision

Goal: land the WMI-1A decision without changing public worker registration or
engine scheduling selector shape in the same slice.

Scope:

- For diagnostics-only:
  - remove the NodeGroupBinding dispatch-gate mutation from worker lifecycle
    writes
  - remove or retarget tests that assert binding disable/drain blocks worker
    scheduling
  - keep topology/admin read and mutation APIs intact
- For operational gate:
  - add or retarget the internal topology/session/endpoint membership source
  - make `WorkerRelationshipOwner` use that source instead of worker
    declaration `adapterNodeId`
  - keep registry gate methods only if they consume the new internal
    membership source
- Do not remove `adapterNodeId` from public registration in this slice.
- Do not remove task scheduling selectors in this slice.

Acceptance:

- The chosen NodeGroupBinding behavior is implemented and tested.
- Worker declaration `adapterNodeId` is no longer the required evidence source
  for NodeGroupBinding dispatch gating.
- The repo compiles after this slice without relying on a later registration
  or scheduling cleanup.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest" test

.\mvnw.cmd -pl xa-mass-engine `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest" test
```

## WMI-2.1 - Remove Adapter Node From Scheduling Intent And Selector

Goal: remove transport topology ids from the engine worker-selection request
path while leaving registration and public SDK/API cleanup to later slices.

Scope:

- Remove `adapterNodeId` from `TaskDispatchIntent`.
- Remove `adapterNodeId` from `ResolvedWorkerSchedulingPolicy`.
- Remove `adapterNodeId` from `WorkerTaskSelector`.
- Stop `WorkerTaskSelectorFactory` from reading adapter-node evidence.
- Remove task shared config `adapterNodeId` as an engine mainline scheduling
  consumer, or classify it as stale config residue with no runtime consumer.
- For any real product constraint, use worker attributes or an explicit worker
  scheduling policy dimension instead of transport topology ids.

Acceptance:

- Engine scheduling intent and resolved worker scheduling policy contain no
  `adapterNodeId`.
- Worker-runtime selector contracts contain no `adapterNodeId`.
- Engine matching/ranking still selects workers by WorkerGroup, target worker,
  attributes, capability, and runtime evidence.
- The repo compiles after this slice while public registration may still carry
  adapter-node residue.

Suggested verification:

```powershell
rg -n "adapterNodeId" `
  xa-mass-engine/src/main/java/com/xa/mass/engine/runtime/scheduling `
  xa-mass-engine/src/main/java/com/xa/mass/engine/strategy/WorkerTaskSelectorFactory.java `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/candidate/WorkerTaskSelector.java `
  xa-mass-base/src/main/java/com/xa/mass/base/model/TaskSharedConfig.java `
  --glob '!**/target/**'

.\mvnw.cmd -pl xa-mass-engine `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest" test
```

## WMI-2.2 - Retarget Candidate Runtime And Registry Source

Goal: remove adapter-node scoped candidate acquisition from worker-runtime and
runtime registry contracts after the engine selector no longer supplies that
dimension.

Scope:

- Remove `adapterNodeId` from `WorkerRegistry#acquireCandidates(...)`.
- Remove `adapterNodeId` from `WorkerCandidateSamplingContext`.
- Remove adapter-node candidate guard logic from `WorkerCandidateIndex`.
- Remove or classify Redis/memory node candidate bucket keys as residue with
  no hot-path runtime consumer.
- Keep NodeGroupBinding operational membership, if chosen, on its separate
  internal source. Do not reuse candidate acquisition as the membership owner.

Acceptance:

- Worker candidate acquisition is group-scoped and attribute/bucket-scoped,
  not adapter-node scoped.
- `WorkerCandidateIndex` does not acquire or reject scheduling candidates by
  adapter-node selector.
- Runtime-api, memory registry, and Redis registry compile with the new
  candidate acquisition contract.
- Any remaining adapter-node registry methods are topology gate/admin residue
  called out by the WMI-1A decision, not scheduling source APIs.

Suggested verification:

```powershell
rg -n "acquireCandidates\\(|WorkerCandidateSamplingContext|nodeCandidateBucket|adapterNodeId" `
  platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker `
  platform_infra/mass-runtime-memory/src/main/java/com/xa/mass/runtime/memory `
  platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCandidateIndex.java `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/candidate `
  --glob '!**/target/**'

.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis,xa-mass-worker-runtime -DskipTests compile

.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerCandidateIndexTest,WorkerManagerTest" test
```

Remaining `adapterNodeId` hits in this slice must be WMI-1A
topology-gate/admin allowlist entries, not candidate source reads.

## WMI-2.3 - Retarget Worker Registration And Session Public Surface

Goal: remove transport topology ids from worker declaration and normal worker
session startup after scheduling and candidate acquisition no longer depend on
adapter-node evidence.

Scope:

- Define how a worker becomes a schedulable WorkerGroup member using
  `workerId`, `workerGroupId`, declaration fields, and optional coarse
  `transportHint`.
- Retarget SDK embedded registration mapping away from `WorkerResourceRecord`
  and away from `adapterNodeId`.
- Retarget Java SDK `WorkerSpec` and `WorkerRegistrationResult` away from
  `adapterNodeId`.
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
- Tests prove worker registration still creates a schedulable worker in the
  selected WorkerGroup.

Suggested verification:

```powershell
rg -n "adapterNodeId|adapterId" `
  sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/model/WorkerRegistration.java `
  sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk `
  sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerSpec.java `
  sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerRegistrationResult.java `
  sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session `
  xa-mass-server/src/main/java/com/xa/mass/api/model/worker/ExternalWorkerRegisterApiRequest.java `
  --glob '!**/target/**'

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk,sdk/xa-mass-java-sdk,xa-mass-server -DskipTests compile
```

## WMI-2.4 - Remove Dispatch Binding And Trace Adapter-Node Residue

Goal: remove adapter-node worker-selection residue that can survive after the
selector and registration paths are cleaned.

Scope:

- Remove `adapterNodeId` from `WorkerSchedulingView` unless it is moved to an
  explicitly named topology/diagnostic snapshot.
- Remove `adapterNodeId` from `TaskDispatchBinding`.
- Stop `SimpleTaskDispatchBinder` from writing adapter-node evidence into
  task dispatch binding or ordinary assignment evidence.
- Stop `TraceEventLogger` from writing adapter-node worker-selection evidence.
- Review trace sink `ExecutionEvent.NodeContext`: keep adapter node only for
  explicit transport/topology events, not engine worker-selection or task
  dispatch proof.
- Update assignment snapshot and proof docs if they currently treat
  adapter-node evidence as scheduling truth.

Acceptance:

- Engine/starter/transport handoff sees `workerGroupId`, selected `workerId`,
  task/work ids, delivery bucket, and worker attributes/evidence as needed,
  but not transport topology ids.
- `TaskDispatchBinding`, `WorkerSchedulingView`, assignment evidence, and
  worker-selection trace attrs do not expose `adapterNodeId`.
- Any remaining trace `adapterNodeId` is explicit transport/topology
  diagnostics and is not sourced from worker scheduling view.

Suggested verification:

```powershell
rg -n "adapterNodeId" `
  xa-mass-base/src/main/java/com/xa/mass/base/runtime/dispatch/TaskDispatchBinding.java `
  xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerSchedulingView.java `
  xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java `
  xa-mass-engine/src/main/java/com/xa/mass/engine/TraceEventLogger.java `
  platform_infra/mass-trace-sink/src/main/java/com/xa/mass/trace/sink `
  --glob '!**/target/**'

.\mvnw.cmd -pl xa-mass-base,xa-mass-engine,platform_infra/mass-trace-sink -DskipTests compile
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
- Assigned task delivery continues to use engine-selected worker identity, but
  delivery integration now resolves `selectedWorkerId -> adapterMailboxKey`
  through worker-runtime delivery target evidence before transport handoff.
  Transport handoff must not restore `deliveryBucketId + selectedWorkerId` or
  per-worker consumer evidence as queue ownership.
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
- Add guards that fail if engine worker-selection or task dispatch contracts
  expose `adapterNodeId`, including `TaskDispatchIntent`,
  `ResolvedWorkerSchedulingPolicy`, `WorkerTaskSelector`,
  `WorkerSchedulingView`, `TaskDispatchBinding`, ordinary assignment evidence,
  and worker-selection trace attrs.
- Add guards that fail if runtime candidate acquisition reintroduces
  adapter-node scoped source reads through `WorkerCandidateSamplingContext`,
  `WorkerRegistry#acquireCandidates`, memory registry, or Redis registry.
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
- Guards fail if engine scheduling selector, worker scheduling view, task
  dispatch binding, or ordinary worker-selection trace evidence contains
  `adapterNodeId`.
- Guards fail if runtime candidate acquisition accepts or indexes by
  `adapterNodeId` as a scheduling source dimension.
- Guards allow timestamps only in command/evidence result packages and
  explicitly named diagnostic/audit snapshot packages.
- Guards allow `adapterNodeId` only in explicit topology/admin/transport
  diagnostic packages and in tests for those explicit surfaces.
- Owning docs state the implemented contract, not target state.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerDeclarationBoundaryGuardTest,WorkerManagerTest" test

.\mvnw.cmd -pl transport/transport_runtime `
  "-Dtest=TransportConvergenceArchitectureGuardTest" test

.\mvnw.cmd -pl xa-mass-server `
  "-Dtest=*ArchitectureGuardTest,*Contract*Test" test

rg -n "adapterNodeId" `
  xa-mass-engine/src/main/java/com/xa/mass/engine/runtime/scheduling `
  xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerSchedulingView.java `
  xa-mass-base/src/main/java/com/xa/mass/base/runtime/dispatch/TaskDispatchBinding.java `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/candidate `
  platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker/WorkerCandidateSamplingContext.java `
  --glob '!**/target/**'
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
- The WMI inventory records the target narrow port for every former production
  `WorkerResourceRuntime`/resource-query/declaration/node-binding caller.
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
- Engine scheduling intent, resolved worker scheduling policy, worker task
  selector, worker scheduling view, task dispatch binding, assignment
  evidence, and worker-selection trace evidence do not expose `adapterNodeId`.
- Runtime candidate acquisition no longer accepts or indexes by
  `adapterNodeId`; Redis/memory node candidate bucket residue is removed or
  explicitly classified as non-hot-path topology/admin residue.
- Worker capability truth comes from WorkerGroup capability contracts, not
  worker-level supported project/event fields.
- Runtime scheduling evidence comes from worker-runtime evidence/admission
  contracts, not `WorkerResourceRecord#statusName` or `lastHeartbeat`.
- Default worker-facing views do not return raw timestamps. Timestamp evidence
  remains internal, command/deadline-driving, or explicitly diagnostic/audit.
- All in-repo callers are retargeted; no compatibility wrapper preserves the
  old fat model as a second live path.
- Architecture guards cover default public worker read/list/catalog DTO/model
  packages, worker-runtime mutation/query interfaces, engine
  worker-selection/dispatch contracts, runtime candidate acquisition
  contracts, and worker-selection trace evidence.
- Owning docs are updated and stale roadmap residue is archived after a
  residue scan.

## Current Verification Record

Verified on 2026-06-16 in the current workspace slice:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime -am `
  "-Dtest=WorkerDeclarationBoundaryGuardTest,WorkerCandidateIndexTest,WorkerManagerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 73 tests, 0 failures

.\mvnw.cmd -pl xa-mass-engine -am `
  "-Dtest=DefaultSchedulingPlaneResolverTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,WorkerSchedulingCandidateEnumeratorTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 23 tests, 0 failures

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api -am `
  "-Dtest=WorkerModelShapeGuardTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 2 tests, 0 failures

.\mvnw.cmd -pl sdk/xa-mass-java-sdk -am `
  "-Dtest=WorkerClientTest,PollingWorkerSessionTest,WebSocketWorkerSessionTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 22 tests, 0 failures

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am `
  "-Dtest=MassSdkTest,MassEngineStartRecoveryTest,WorkerRuntimeSelectionIntegrationTest,WorkerRuntimePresenceIngressTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 114 tests, 0 failures

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am `
  "-Dtest=MassSdkTest,EmbeddedPullWorkerSessionTest,RuntimeTaskResultIngestChannelTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 118 tests, 0 failures

.\mvnw.cmd -pl xa-mass-server -am `
  "-Dtest=ExternalWorkerModelShapeGuardTest,WorkerApiControllerTest,CatalogControllerTest,ExternalWorkerApiControllerTest,MockRuntimeDataLoaderTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 50 tests, 0 failures

.\mvnw.cmd -pl transport/socket-adapter -am `
  "-Dtest=SocketTransportServerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 5 tests, 0 failures

.\mvnw.cmd -pl integrations/xa-mass-worker-pack -am `
  "-Dtest=WebSocketClientStarterTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
# PASS: 5 tests, 0 failures

.\mvnw.cmd -pl xa-mass-worker-runtime,sdk/xa-mass-embedded-sdk,sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-java-sdk,integrations/xa-mass-worker-pack,xa-mass-server,xa-mass-testing -am -DskipTests test-compile
# PASS

corepack pnpm -C frontend typecheck
# PASS

corepack pnpm -C frontend exec vitest run `
  src/api/workers.real.test.ts `
  src/api/catalog.test.ts `
  src/pages/resources/workers/WorkersPage.test.ts `
  src/pages/runtime/RuntimeDiscoveryPage.test.ts `
  src/pages/dashboard/DashboardPage.test.ts `
  src/components/WorkerDebugPanel.test.ts `
  --maxWorkers=1
# PASS: 6 files, 12 tests
```

Verification notes:

- Direct `pnpm` was not on local PATH; frontend commands were run through
  `corepack pnpm`.
- Residue scan result: hot path scheduling/candidate/dispatch contracts and
  default worker-facing SDK/server/frontend models have no `adapterNodeId`,
  `adapterId`, `onlineStrategy`, or raw heartbeat/create/update timestamp
  fields. Remaining `adapterNodeId` in catalog/frontend is limited to explicit
  `AdapterNodeCapability` / `NodeGroupBindingCapability` topology/admin
  models.
- Default worker view residue scan found only the allowed topology/admin
  `CatalogController` `adapterNodeId` references used to compose adapter-node
  and node-group binding capability views.
- Java SDK session helper residue scan found no hidden topology fields in
  normal session builders; remaining hits are explicit topology/admin README
  examples, the quickstart note that transport runtime ids are internal, and
  tests asserting registration requests do not contain `adapterId`.
- Old transport result-ingress residue scan found only
  `RuntimeTaskResultIngestChannelTest` and
  `TransportConvergenceArchitectureGuardTest` references that exercise the
  current starter handler and guard removed legacy transport result types.
- `WorkerResourceRuntime` appears only in architecture guards that prevent the
  retired aggregate from returning.

## Verification Candidates

Focused compile:

```powershell
.\mvnw.cmd -pl xa-mass-base -DskipTests compile
.\mvnw.cmd -pl xa-mass-worker-runtime -DskipTests compile
.\mvnw.cmd -pl platform_infra/mass-runtime-api -DskipTests compile
.\mvnw.cmd -pl platform_infra/mass-runtime-memory -DskipTests compile
.\mvnw.cmd -pl platform_infra/mass-runtime-redis -DskipTests compile
.\mvnw.cmd -pl platform_infra/mass-trace-sink -DskipTests compile
.\mvnw.cmd -pl xa-mass-engine -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-java-sdk -DskipTests compile
.\mvnw.cmd -pl xa-mass-server -DskipTests compile
```

Focused tests:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerManagerTest,WorkerDeclarationBoundaryGuardTest,WorkerCandidateIndexTest" test

.\mvnw.cmd -pl xa-mass-engine `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest" test

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk `
  "-Dtest=MassSdkTest,EmbeddedPullWorkerSessionTest,MassApplicationDistributedTransportTest" test

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

rg -n "adapterNodeId" `
  xa-mass-engine/src/main/java/com/xa/mass/engine/runtime/scheduling `
  xa-mass-engine/src/main/java/com/xa/mass/engine/strategy `
  xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerSchedulingView.java `
  xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java `
  xa-mass-engine/src/main/java/com/xa/mass/engine/TraceEventLogger.java `
  xa-mass-base/src/main/java/com/xa/mass/base/runtime/dispatch/TaskDispatchBinding.java `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/candidate `
  xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCandidateIndex.java `
  platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker/WorkerCandidateSamplingContext.java `
  platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker/WorkerRegistry.java `
  platform_infra/mass-runtime-memory/src/main/java/com/xa/mass/runtime/memory `
  platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis `
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
