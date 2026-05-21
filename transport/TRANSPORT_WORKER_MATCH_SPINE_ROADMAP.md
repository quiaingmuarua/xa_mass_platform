# Transport Worker Match Spine Roadmap

Last updated: 2026-05-21

Status: next-phase convergence roadmap. This document describes target order
and known gaps after the AdapterNode / NodeGroupBinding registration baseline.
Use current code and verified runtime behavior as truth for implemented
behavior.

## Summary

The scheduling kernel is already group-first:

```text
Task(project,eventCode)
  -> WorkerCandidateIndex
  -> groupIds
  -> workerIds
  -> WorkerSchedulingView
  -> eligibility / rank / allocation / resource admission
```

The remaining transport worker work is not to invent another worker model. The
goal is to make external-worker registration, transport presence, dispatch
evidence, worker report feedback, and result convergence follow one spine:

```text
AdapterNode registered
  -> NodeGroupBinding registered
  -> WorkerGroup capability declared
  -> Worker registered with adapterNodeId + workerGroupId
  -> transport presence updates reachability evidence
  -> task eventCode matches WorkerGroup
  -> scheduling selects Worker
  -> dispatch carries group/node evidence
  -> transport routes by adapter/route evidence
  -> result converges through TaskResultRuntime
  -> owner-applied worker reports wake scheduling only through approved owners
```

One-line rule:

```text
capability first: eventCode -> WorkerGroup
runtime second: WorkerGroup -> Worker
transport last: Worker -> adapter route
```

Transport owns delivery and presence evidence. Engine owns capability and
scheduling truth.

## Current Gap Baseline

Current code already has several pieces:

- external worker registration accepts `adapterNodeId`, requires
  `workerGroupId`, and rejects worker-level capability fields
- external/control-plane code can declare WorkerGroup capability through the
  worker-group declaration surface before worker registration
- `WorkerRegistrySnapshot` indexes `groupId -> workerIds`,
  `adapterNodeId -> workerIds`, and `(adapterNodeId, groupId) -> workerIds`
- `WorkerCandidateIndex` is the candidate-source path for event/group matching
- `WorkerControlService` can wake runtime-ready polling on accepted capability
  report and `AVAILABLE` state report
- `WorkerManager` relationship mutations can wake runtime-ready polling after
  owner apply
- `WorkerDispatchAvailabilityOwner` uses source-scoped gates for worker state,
  worker command, and node-group binding availability
- external/SDK registration has explicit AdapterNode and NodeGroupBinding
  registration surfaces, and worker registration requires `adapterNodeId` when
  joining a WorkerGroup
- worker registration no longer auto-creates compatibility AdapterNode /
  NodeGroupBinding records from `adapterId`
- transport runtime owns adapter routing, delivery stores, result ingest, and
  presence stores

The spine is still not fully closed:

- the base Worker read model still carries legacy supported project/event
  projections for diagnostics; registration no longer accepts them as
  capability input
- bounded route-bucket acquisition is in the in-memory candidate-source path;
  Redis backing and bounded stale cleanup are still later slices

Do not treat this document as proof those gaps are already closed.

## Hard Rules

- Do not introduce an engine-side `WorkerSession`.
- Do not introduce `Device`, `DeviceSession`, `AccountSlot`, or implicit
  device/account locks in this roadmap.
- Do not make `AdapterNode`, transport adapter, transport route, or raw worker
  report a capability truth owner.
- Do not turn transport into a scheduler. Transport may route a selected
  worker; it must not choose workers for a task event.
- Do not let `NodeGroupBinding` carry `eventBindings`.
- Do not preserve superseded internal compatibility paths once the replacement
  path is explicit and tested.
- Do not change worker-facing transport JSON just to add diagnostics; prefer
  trace/diagnostic evidence first.
- Do not implement memory runtime as scan-first lists. Memory and Redis
  runtimes should share the same logical hash/index shape.
- Do not materialize all workers in a group on the scheduling hot path. Large
  relation sets are allowed for ownership and diagnostics, not as direct
  candidate lists for million-worker scheduling.
- Do not fan out adapter-node or node-group state changes into per-worker
  mutations. Use node/group gate overlays and bounded candidate validation.
- Tasks without an explicit `targetWorkerId` must enter scheduling through
  `EventKey -> WorkerGroup -> route bucket`. Direct worker lookup is only
  allowed for explicit fixed-worker constraints and must still pass group
  capability plus owner gates.

## Target Shape

### AdapterNode

AdapterNode is the worker registration endpoint and adapter runtime deployment
identity.

Owns:

- node identity and runtime metadata
- endpoint / callback scope
- node-level enabled / online evidence
- node diagnostics

Must not own:

- `WorkerGroup.eventBindings`
- matching, ranking, scheduling decision
- worker load or task lease
- task result finality

### WorkerGroup

WorkerGroup is the capability cohort and capability truth owner.

Owns:

- event bindings
- project capability
- group default attributes
- group default max concurrency
- group enabled state
- capability version

Must not own:

- adapter-node lifecycle
- transport route or connection
- active leases
- dispatch route ownership

### NodeGroupBinding

NodeGroupBinding says an AdapterNode hosts a WorkerGroup.

Owns:

- `(adapterNodeId, workerGroupId)` deployment relation
- node-local group enabled / draining
- plugin/deployment metadata
- deployment routing scope such as business, tenant, region, pool, or route
  bucket key when explicitly approved by routing policy

Must not own:

- event bindings
- effective capability truth
- worker active load
- task lease/result state

### Worker

Worker is the platform dispatchable execution identity.

Owns:

- worker identity
- `adapterNodeId`
- `workerGroupId`
- `adapterId`
- worker attributes
- declared max concurrent work
- worker-level enabled flag

Must not own:

- `supportedProjects` as capability truth
- `supportedEventCodes` as capability truth
- event bindings or project capability

Worker attributes such as `deviceId`, `accountId`, `region`, and `routingTags`
are statistics, trace, filter, and optional strategy inputs. They do not create
implicit owner models or locks.

Only explicitly approved routing attributes may become secondary indexes.
Unapproved attributes remain metadata and must not be auto-indexed.

## Routing Terms

This roadmap uses two different routing terms:

- transport `routeKey`: existing transport delivery address used with
  `adapterId + routeKey`
- scheduling `routeBucketKey`: engine-owned candidate bucket partition for
  business, tenant, region, pool, or other approved dispatch routing

`routeBucketKey` is a normalized string value produced by an engine-owned
`WorkerRoutingPolicy`. It is not a new capability owner and must not be
interpreted by transport delivery code.

Inputs:

- task project/event and approved task routing fields
- `WorkerGroup` capability identity
- approved `NodeGroupBinding` routing attributes
- approved worker routing attributes

Approved routing attributes:

- first slice approval is an explicit allow-list owned by
  `WorkerRoutingPolicy`
- default first-slice allow-list is empty, so all workers route to `default`
- later slices may add allow-listed keys such as `business`, `tenant`,
  `region`, or `pool`
- `WorkerRoutingPolicy` must not read arbitrary worker or binding attributes
  as routing inputs
- adding an approved key must update tests and diagnostics in the same change

Rules:

- one worker may belong to zero, one, or many `routeBucketKey` values
- multi-route membership is represented by set indexes, not a single worker
  field
- default first-slice policy returns one bucket, `default`, when no approved
  routing field is present
- changing routing policy may rebuild route bucket indexes, but it must not
  mutate `WorkerGroup` capability
- transport `routeKey` continues to be resolved later from selected worker and
  adapter evidence

### WorkerRouteBucketOwner Boundary

`WorkerRouteBucketOwner` owns route bucket state. It is not a listener bus.

Owns:

- `availableWorkersByGroupRouteBucket`
- `workerRouteBucketKeysById`
- bounded candidate acquisition
- stale candidate marking and bounded lazy cleanup

Callers:

- `WorkerManager` calls it after owner mutations such as worker add/update,
  node-group binding enable/disable/drain, adapter-node availability changes,
  and capability snapshot changes
- dispatch/admission calls it to acquire a bounded candidate batch and to mark
  stale candidates after admission rejection

Must not own:

- WorkerGroup event capability
- worker registration validation
- reachability truth
- dispatch gate truth
- load/admission truth

First slice wiring:

- `WorkerManager` remains the orchestrator that observes relation mutations and
  invokes `WorkerRouteBucketOwner`
- `WorkerRouteBucketOwner` holds the in-memory bucket indexes independently
  from `WorkerManager`
- `WorkerRoutingPolicy` is called by `WorkerRouteBucketOwner` when bucket
  membership is recomputed, and by candidate acquisition when resolving task
  route buckets
- no broad event bus is introduced

## Runtime Metadata And Index Shape

The target runtime shape is hash-first and index-first for both in-memory and
Redis runtimes. `Meta` below means the owner row payload for the existing main
type. It does not require new `*Meta` classes.

Type mapping:

- `WorkerMeta` maps to current `Worker` / future narrowed worker row
- `WorkerGroupMeta` maps to current `WorkerGroupRecord`
- `AdapterNodeMeta` maps to current `AdapterNodeRecord`
- `NodeGroupBindingMeta` maps to current `NodeGroupBindingRecord`

Owner rows:

- `workersById`: `workerId -> WorkerMeta`
- `workerAttributesById`: `workerId -> Map<String, String>`
- `workerStateProjectionById`: `workerId -> WorkerStateProjection`
- `workerGroupsById`: `groupId -> WorkerGroupMeta`
- `eventBindingsByGroupId`: `groupId -> Set<EventBinding>`
- `adapterNodesById`: `adapterNodeId -> AdapterNodeMeta`
- `nodeGroupBindingsByKey`: `(adapterNodeId, groupId) -> NodeGroupBindingMeta`

Worker indexes:

- `workerIdsByGroupId`: `groupId -> Set<workerId>`
- `workerIdsByAdapterNodeId`: `adapterNodeId -> Set<workerId>`
- `workerIdsByNodeGroup`: `(adapterNodeId, groupId) -> Set<workerId>`
- `workerRelationById`: `workerId -> (adapterNodeId, groupId)`
- `workerRouteBucketKeysById`: `workerId -> Set<routeBucketKey>`

Capability indexes:

- `groupIdsByEventKey`: `(projectCode, eventCode) -> Set<groupId>`
- `groupIdsByProjectCode`: `projectCode -> Set<groupId>`
- `groupIdsByAdapterNodeId`: `adapterNodeId -> Set<groupId>`
- `adapterNodeIdsByGroupId`: `groupId -> Set<adapterNodeId>`

Bounded routing indexes:

- `availableWorkersByGroupRouteBucket`: `(groupId, routeBucketKey) -> bounded
  SET/ZSET` of worker ids
- `availableNodeGroupsByGroupRouteBucket`: `(groupId, routeBucketKey) ->
  Set` of `(adapterNodeId, groupId)` pairs
- `routeBucketKeysByEventKey`: `(projectCode, eventCode) ->
  Set<routeBucketKey>` when a routing policy needs precomputed route
  partitions

Dynamic runtime views:

- `reachabilityByWorkerId`: transport-owned presence evidence as engine
  reachability view
- `dispatchGatesByWorkerId`: source-scoped dispatch gates
- `loadByWorkerId`: capacity, active lease count, and available slots
- `activeWorkersByTaskId`: active worker bindings for a task
- `tasksByWorkerId`: active task bindings for a worker

Scheduling hot path:

```text
Task(project,eventCode,businessRoute)
  -> groupIdsByEventKey[(project,eventCode)]
  -> WorkerRoutingPolicy resolves routeBucketKey candidates
  -> availableWorkersByGroupRouteBucket[(groupId,routeBucketKey)]
  -> bounded acquire / cursor / reserve
  -> workersById[workerId]
  -> workerRelationById[workerId]
  -> node-group gate / reachability / dispatch gate / load / lock / ranking
```

Fixed-worker path:

```text
Task(targetWorkerId,project,eventCode)
  -> workersById[targetWorkerId]
  -> workerRelationById[targetWorkerId]
  -> WorkerGroup capability gate
  -> node-group gate / reachability / dispatch gate / load / lock / ranking
  -> singleton candidate or empty
```

Rules:

- worker register/update must atomically maintain `workersById`,
  `workerRelationById`, worker relation indexes, and approved route buckets
- group capability update must diff old/new event bindings and update
  capability indexes
- node-group binding update must update relation indexes and dispatch gates,
  not group capability
- attributes do not get secondary indexes unless a routing/filter owner
  explicitly needs them
- relation indexes such as `workerIdsByGroupId` may be large; scheduling must
  not fetch or filter them as a full candidate list
- direct `workersById` lookup is not a general candidate source; it is only
  valid for explicit `targetWorkerId` fixed-worker dispatch
- adapter-node offline, node-group draining, or route disable must be a gate
  overlay and lazy candidate validation path, not a per-worker fan-out update
- `WorkerRegistrySnapshot` stays through this roadmap as the capability read
  view consumed by current `WorkerCandidateIndex` and
  `WorkerCapabilityAuthority`; after TW-1C, route-bucket candidate acquisition
  must not depend on full group worker enumeration from that snapshot

## Million-Scale Routing Constraint

This roadmap targets multi-business deployments where adapter nodes shard work
by business, tenant, region, pool, or other approved routing dimensions, and
the total worker count may reach millions.

Business routing must not become a second capability truth. The flow is:

```text
eventCode -> WorkerGroup capability
group + routeBucketKey -> bounded candidate bucket
candidate -> owner gate / resource admission
```

The flow must not become:

```text
eventCode -> all workers in group -> filter by business attributes
```

It also must not become:

```text
task attributes -> direct worker search
adapter node -> workers -> filter by event
```

Rules:

- `WorkerGroup` remains the only event capability owner
- `NodeGroupBinding` and worker attributes may provide approved routing scope
  but must not add event capability
- hot-path candidate acquisition must use bounded `groupId + routeBucketKey`
  buckets, not full group membership
- task-side worker targeting is a fixed-worker constraint only; it must never
  become business routing or attribute search
- candidate buckets may be backed by Redis `SET`, `ZSET`, or an in-memory
  equivalent, but the acquisition API must return a bounded batch
- ranking strategy is pluggable; the core mechanism only owns route partition,
  bounded acquisition, reservation, and owner validation
- node/group drain, offline, or disable changes must update gate state and
  route availability, not mutate every affected worker
- stale candidates are acceptable only if the admission path rejects them and
  schedules bounded cleanup through the candidate bucket owner
- observability may inspect large relation sets offline, but dispatch loops
  must not depend on full set scans

## Phase Plan

### TW-0A: Preserve adapter-node evidence through capability composition

Status: implemented.

Goal: capability reports must not break adapter-node/group indexes.

Scope:

- copy `adapterNodeId` when `WorkerCapabilityAuthority` creates effective
  workers
- add tests proving accepted capability reports preserve:
  - `workerIdsByAdapterNodeId(adapterNodeId)`
  - `workerIdsByAdapterNodeGroup(adapterNodeId, workerGroupId)`
  - `groupIdByWorkerId(workerId)`
  - the effective worker returned by the snapshot still has the registration
    row `adapterNodeId`

Acceptance:

- capability report accepted for an adapter-node/group worker does not remove
  that worker from adapter-node indexes
- effective worker returned by snapshot preserves `adapterNodeId` from the
  registration row after capability report is applied
- report-owned available event codes remain bounded by group/registration
  approved capability

### TW-0B: Source-scoped dispatch availability gates

Status: implemented.

Goal: worker state, worker command, and node-group draining must not overwrite
each other.

Scope:

- replace single worker-level availability bit with source-scoped gates
- minimum sources:
  - `WORKER_STATE`
  - `WORKER_COMMAND`
  - `NODE_GROUP_BINDING`
- aggregate availability is enabled only when no source disables dispatch
- update `DefaultWorkerDispatchAvailabilityPolicy` to write only
  `WORKER_STATE` and `WORKER_COMMAND`
- update `WorkerManager` node-group drain/enable handling to write only
  `NODE_GROUP_BINDING`
- update callers of `dispatchAvailabilityOwner.enable(...)` so relation
  recovery clears only the relevant source gate, for example
  `clearSource(NODE_GROUP_BINDING, workerId)`, instead of clearing all worker
  dispatch gates

Acceptance:

- node-group drain clear does not enable a worker still in state `DRAINING`
- worker state `AVAILABLE` does not enable a worker blocked by node-group drain
- command `DRAIN` does not get cleared by state or node-group events
- existing scheduling eligibility still reads one aggregated dispatch-enabled
  result

### TW-1A: WorkerGroup declaration surface

Status: implemented.

Goal: external/control-plane code can declare capability before worker
registration.

Scope:

- expose a narrow SDK/server operation for declaring/upserting WorkerGroup
  capability
- capability declaration owns `eventBindings`, default attributes, default max
  concurrency, enabled flag, and capability version
- move project/event capability ownership from worker-level
  `supportedProjects` / `supportedEventCodes` into
  `WorkerGroupRecord.projectCodes` / `WorkerGroupRecord.eventBindings`
- authorization for group declaration must use group event bindings instead of
  worker register event bindings

Acceptance:

- group capability can be declared without registering a worker
- eventCode-to-group index is produced from WorkerGroup capability
- project/event capability source is `WorkerGroupRecord`, not
  `Worker.supportedProjects` or `Worker.supportedEventCodes`
- AdapterNode and NodeGroupBinding still cannot carry event bindings

### TW-1B: External worker registration becomes identity-first

Status: implemented. Group-first capability is the mainline, the worker-level
capability projection into WorkerGroup truth is retired, and worker registration
no longer auto-creates AdapterNode / NodeGroupBinding from legacy `adapterId`.
NodeGroupBinding registration now requires both a registered AdapterNode and a
declared WorkerGroup, and adapter-node scoped worker registration is rejected
unless the explicit node/group binding exists.

Goal: worker register should bind execution identity to an existing
AdapterNode/WorkerGroup relation, not declare capability truth.

Scope:

- mainline register request uses `workerId`, `adapterNodeId`, `workerGroupId`,
  `maxConcurrentWork`, and attributes
- validate:
  - adapter node exists
  - worker group exists
  - node-group binding exists
- keep worker capability report as a report-owned slice bounded by approved
  group capability
- keep `WorkerCapabilityAuthority` composing candidate-source capability only
  from declared WorkerGroups
- keep worker registration capability-free: no `eventBindings`,
  `supportedProjects`, or `supportedEventCodes` on the registration contract
- remove compatibility auto-creation of AdapterNode/NodeGroupBinding from
  worker registration when the explicit path is covered by tests

Acceptance:

- missing node, group, or binding fails worker registration
- SDK/API registration requires explicit `adapterNodeId + workerGroupId` for
  group workers
- worker registration rejects or cannot express worker-level capability fields
- worker registration does not create new capability truth
- task eventCode still reaches workers through `WorkerCandidateIndex`
- legacy worker-level supported event fields are not used as mainline truth
- `WorkerCandidateIndex`, `WorkerSchedulingView`, and dispatch evidence read
  project/event capability from `WorkerGroupRecord`

### TW-1C: Bounded route bucket acquisition

Status: first in-memory slice implemented. The current implementation provides
an engine-owned route bucket owner, bounded candidate acquisition shape, and
fixed approved route attributes (`business`, `tenant`, `region`, `pool`).
Redis-backed buckets, advanced scoring, and background reconciliation remain
later slices.

Goal: million-worker deployments acquire candidates from route buckets instead
of materializing all workers in a group.

Scope:

- introduce an engine-owned candidate acquisition seam that accepts
  `groupId`, `routeBucketKey`, and a max candidate count
- maintain `availableWorkersByGroupRouteBucket` from worker relation,
  reachability, dispatch gate, and load/resource owner outcomes
- keep `workerIdsByGroupId` and node/group relation indexes for ownership and
  diagnostics, not direct scheduling enumeration
- `routeBucketKey` is resolved by `WorkerRoutingPolicy` from
  task/project/event and approved routing attributes
- node-group drain/offline gates must remove or hide an entire node/group
  route partition without per-worker fan-out
- introduce `WorkerRouteBucketOwner` as the owner of bounded bucket mutation,
  acquisition, stale candidate marking, and lazy cleanup
- wire first-slice bucket updates through explicit `WorkerManager` calls after
  owner mutations, not through a generic event bus

Acceptance:

- scheduling does not iterate all workers in a group for event tasks
- non-targeted tasks cannot use direct `workersById` lookup as candidate
  source
- targeted tasks use singleton direct lookup and still pass group capability,
  node-group gate, reachability, dispatch gate, load, and lock admission
- candidate acquisition returns a bounded batch for each group/route
- draining one adapter node group excludes its route candidates without
  rewriting every worker under that node
- stale bucket entries are rejected by admission and cleaned up through a
  bounded `WorkerRouteBucketOwner` path, not unbounded scheduler-side cleanup
- changing routing policy does not change WorkerGroup capability truth

First slice:

- implement one in-memory `WorkerRouteBucketOwner`
- use a default `WorkerRoutingPolicy` that resolves tasks without approved
  route attributes to one `routeBucketKey`: `default`
- allow a worker to belong to multiple route buckets in the index model, while
  the default runtime policy indexes `default` plus approved worker attribute
  buckets
- keep Redis `ZSET` backing, advanced scoring, and periodic full reconciliation
  out of scope for the first slice
- keep `WorkerRegistrySnapshot` for capability lookup; only replace the
  post-group worker enumeration with bounded bucket acquisition

Implemented route-attribute slice:

- read task route input from `Task.sharedConfig.routeAttributes`
- only approved fields `business`, `tenant`, `region`, and `pool` may become
  route bucket keys
- unapproved task or worker attributes do not affect bucket membership
- `TaskApiWorkerAttributeRoutingIntegrationTest` proves the approved route
  attribute bucket on the real server + websocket dispatch/result path without
  relying on a routing rule to reject the non-matching worker

Later slices:

- add Redis-backed bounded bucket operations
- add optional `WorkerRouteBucketReconciler` watchdog for bounded background
  cleanup when lazy eviction is insufficient
- add multi-bucket worker membership tests

### TW-2: Dispatch evidence spine

Status: implemented for internal binding, worker-match trace evidence, and
dispatched-attempt event-key evidence; worker-facing dispatch payload remains
unchanged.

Goal: dispatch/trace/diagnostics can prove the selected path without making
transport a scheduler.

Scope:

- carry dispatch evidence:
  - `workerGroupId`
  - `adapterNodeId`
  - `eventBindingKey`
  - `workerCandidateSource`
- feed this evidence from `WorkerSchedulingCandidate` / `WorkerSchedulingView`
  before the binder loses candidate context
- prefer additive optional evidence on dispatch diagnostics/carrier; do not
  change worker-facing transport payload in the first slice

Acceptance:

- trace can show `eventCode -> group -> worker -> adapter node`
- transport still resolves routes from selected worker/adapter evidence
- `TaskDispatchItem` worker-facing fields are unchanged in the first slice;
  evidence fields land only on internal binding, trace, or diagnostics
- polling, websocket, and socket remain peer transport consumers of the same
  engine-selected dispatch binding

### TW-3: Relationship-change scheduling wakeup

Status: implemented as a narrow `WorkerManager` callback wired by SDK assembly.

Goal: newly eligible resources wake runtime-ready polling fallback without
transport directly controlling the pump.

Scope:

- introduce an engine/SDK assembly seam for worker relation availability
  wakeups
- implement the first slice as a narrow `WorkerManager` wakeup callback wired
  by SDK assembly, not as a broad event bus
- call that seam from the owner mutation methods that can make workers newly
  eligible: `addWorker(...)`, `bindNodeGroup(...)`,
  `setNodeGroupBindingEnabled(...)`, `setNodeGroupBindingDraining(...)`, and
  AdapterNode online/enabled mutation methods when present
- trigger wakeup on:
  - worker registered
  - node-group binding enabled
  - node-group drain cleared
  - adapter node enabled/online when it may make workers eligible
  - accepted capability report that changes snapshot
  - worker state `AVAILABLE`
- wakeup must remain after owner apply and best-effort

Acceptance:

- ready backlog with no eligible worker enters idle admission
- registering or re-enabling a valid node/group/worker wakes dispatch before
  idle backoff expiry
- transport does not call `RuntimeReadyDispatchPump` directly

### TW-4: Transport black-box proof

Status: implemented for the group-first public contract and shared
polling/websocket/socket black-box registration shape. TW-1C remains the later
bounded-acquisition scaling slice, not a prerequisite for this proof.

Goal: prove the complete group-first external worker spine through public
surfaces.

Scope:

- extend the external-worker HTTP contract proof first
- validate polling as the first black-box protocol
- add websocket/socket proof only after they use the same registration and
  dispatch evidence contract

Acceptance:

- proof creates AdapterNode, WorkerGroup, NodeGroupBinding, Worker
- submitted task proves `eventCode -> group -> worker -> adapter node -> result`
- analyzer asserts no all-worker fallback
- analyzer asserts accepted match evidence includes `workerGroupId`,
  `adapterNodeId`, and non-fallback `workerCandidateSource`
- analyzer asserts dispatched attempt evidence includes `workerGroupId`,
  `adapterNodeId`, `eventBindingKey`, and non-fallback
  `workerCandidateSource`
- result convergence remains through TaskResultRuntime

## Testing Strategy

Engine tests:

- `WorkerCapabilityAuthority` preserves adapter-node evidence after report
- `WorkerRegistrySnapshot` indexes survive report composition
- candidate acquisition uses bounded route buckets and never materializes all
  workers in a group on the event scheduling path
- node-group drain/offline excludes a node/group route partition without
  fan-out worker mutation
- source-scoped dispatch gate interaction matrix:
  - state drain vs node drain
  - command drain vs state available
  - node drain clear vs command drain

SDK/server tests:

- WorkerGroup declaration validates event bindings
- external worker registration requires explicit node/group/binding after the
  mainline path is enabled
- worker registration without event bindings uses existing WorkerGroup
  capability

Transport/proof tests:

- dispatch evidence includes `workerGroupId`, `adapterNodeId`,
  `eventBindingKey`, and `workerCandidateSource` in trace/diagnostics
- transport route still uses selected worker/adapter route evidence
- multi-business proof covers at least two route bucket keys for the same
  WorkerGroup capability and proves no cross-route dispatch
- external-worker black-box proof covers registration, poll/dispatch, result,
  and report feedback

## Documentation Updates

When implementing this roadmap:

- update `transport/AGENTS.md` reading map
- update `doc/EXTERNAL_WORKER_QUICKSTART.md` when external worker contract
  changes
- update `doc/TRACE_CONTRACT.md` when evidence fields become authoritative
- update or retire stale wording that claims `supportedEventCodes` is worker
  capability truth
- do not document target state as current behavior before code and tests land

## Risks

### Risk: WorkerGroup declaration duplicates existing worker registration truth

Mitigation: TW-1A must establish WorkerGroup declaration as the owner before
TW-1B removes worker-level event binding requirements.

### Risk: Source-scoped gates become a policy framework

Mitigation: keep sources small and concrete. This is an owner-correctness fix,
not a general policy engine.

### Risk: Dispatch evidence mutates worker payload contracts

Mitigation: first slice adds trace/diagnostic/carrier evidence only. Worker
payload changes require a separate public contract decision.

### Risk: Transport becomes scheduling owner

Mitigation: transport consumes selected worker/route evidence only. Candidate
source and eligibility remain engine-owned.

## Final Target

The completed spine should read:

```text
External Worker / Adapter
  -> register AdapterNode
  -> declare WorkerGroup capability
  -> bind AdapterNode to WorkerGroup
  -> register Worker identity
  -> transport presence updates reachability evidence
  -> task eventCode matches WorkerGroup
  -> scheduling selects Worker
  -> dispatch goes through transport route
  -> result converges through TaskResultRuntime
  -> worker reports update owners
  -> owner outcomes wake dispatch / refresh candidate source
```

At that point the platform is no longer merely an engine that internally has
WorkerGroup. External worker onboarding itself is group-first,
adapter-node-aware, and transport-neutral.
