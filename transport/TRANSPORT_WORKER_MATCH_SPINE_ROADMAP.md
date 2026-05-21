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

- external worker registration accepts `adapterNodeId` and requires
  `workerGroupId`
- `WorkerRegistrySnapshot` indexes `groupId -> workerIds`,
  `adapterNodeId -> workerIds`, and `(adapterNodeId, groupId) -> workerIds`
- `WorkerCandidateIndex` is the candidate-source path for event/group matching
- `WorkerControlService` can wake runtime-ready polling on accepted capability
  report and `AVAILABLE` state report
- transport runtime owns adapter routing, delivery stores, result ingest, and
  presence stores

The spine is still not fully closed:

- external-worker registration still requires worker-level `eventBindings` and
  derives WorkerGroup capability through compatibility projection
- there is no first-class external/control surface for declaring WorkerGroup
  capability before worker registration
- `WorkerCapabilityAuthority.copyWorker()` does not currently preserve
  `adapterNodeId`, so accepted capability reports can drop adapter-node/group
  evidence from the effective snapshot
- `WorkerDispatchAvailabilityOwner` is a single worker-level gate; node-group
  drain clear can accidentally clear worker-state or command-driven drain
- `TaskDispatchBinding` does not carry `workerGroupId`, `adapterNodeId`,
  `eventBindingKey`, or `workerCandidateSource`
- relationship changes such as worker registered, binding enabled, drain
  cleared, or adapter node online do not yet have one explicit scheduling
  wakeup seam

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
- worker attributes
- declared max concurrent work
- worker-level enabled flag

Worker attributes such as `deviceId`, `accountId`, `region`, and `routingTags`
are statistics, trace, filter, and optional strategy inputs. They do not create
implicit owner models or locks.

## Phase Plan

### TW-0A: Preserve adapter-node evidence through capability composition

Goal: capability reports must not break adapter-node/group indexes.

Scope:

- copy `adapterNodeId` when `WorkerCapabilityAuthority` creates effective
  workers
- add tests proving accepted capability reports preserve:
  - `workerIdsByAdapterNodeId(adapterNodeId)`
  - `workerIdsByAdapterNodeGroup(adapterNodeId, workerGroupId)`
  - `groupIdByWorkerId(workerId)`

Acceptance:

- capability report accepted for an adapter-node/group worker does not remove
  that worker from adapter-node indexes
- report-owned available event codes remain bounded by group/registration
  approved capability

### TW-0B: Source-scoped dispatch availability gates

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

Acceptance:

- node-group drain clear does not enable a worker still in state `DRAINING`
- worker state `AVAILABLE` does not enable a worker blocked by node-group drain
- command `DRAIN` does not get cleared by state or node-group events
- existing scheduling eligibility still reads one aggregated dispatch-enabled
  result

### TW-1A: WorkerGroup declaration surface

Goal: external/control-plane code can declare capability before worker
registration.

Scope:

- expose a narrow SDK/server operation for declaring/upserting WorkerGroup
  capability
- capability declaration owns `eventBindings`, default attributes, default max
  concurrency, enabled flag, and capability version
- authorization for group declaration must use group event bindings instead of
  worker register event bindings

Acceptance:

- group capability can be declared without registering a worker
- eventCode-to-group index is produced from WorkerGroup capability
- AdapterNode and NodeGroupBinding still cannot carry event bindings

### TW-1B: External worker registration becomes identity-first

Goal: worker register should bind execution identity to an existing
AdapterNode/WorkerGroup relation, not declare capability truth.

Scope:

- mainline register request uses `workerId`, `adapterNodeId`, `workerGroupId`,
  `maxConcurrentWork`, and attributes
- validate:
  - adapter node exists
  - worker group exists
  - node-group binding exists
- stop requiring worker-level `eventBindings` on the mainline registration
  path after TW-1A exists
- keep worker capability report as a report-owned slice bounded by approved
  group capability
- remove or explicitly demote compatibility auto-creation of
  AdapterNode/NodeGroupBinding from worker registration when the explicit path
  is covered by tests

Acceptance:

- missing node, group, or binding fails worker registration
- worker registration does not create new capability truth
- task eventCode still reaches workers through `WorkerCandidateIndex`
- legacy worker-level supported event fields are not used as mainline truth

### TW-2: Dispatch evidence spine

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
- polling, websocket, and socket remain peer transport consumers of the same
  engine-selected dispatch binding

### TW-3: Relationship-change scheduling wakeup

Goal: newly eligible resources wake runtime-ready polling fallback without
transport directly controlling the pump.

Scope:

- introduce an engine/SDK assembly seam for worker relation availability
  wakeups
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
- analyzer asserts group/node evidence appears in trace/diagnostics
- result convergence remains through TaskResultRuntime

## Testing Strategy

Engine tests:

- `WorkerCapabilityAuthority` preserves adapter-node evidence after report
- `WorkerRegistrySnapshot` indexes survive report composition
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

- dispatch evidence is present in trace/diagnostics
- transport route still uses selected worker/adapter route evidence
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
