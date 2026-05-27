# Worker Runtime Module Extraction Inventory

Status: active WRX inventory.

This inventory supports
[`WORKER_RUNTIME_MODULE_EXTRACTION_ROADMAP.md`](./WORKER_RUNTIME_MODULE_EXTRACTION_ROADMAP.md).
It records current caller and owner reality before module movement. It is not a
target-state baseline.

## Current Surface Summary

`WorkerManager` is still the active assembly surface for worker resource
declaration, worker runtime projection, candidate source, report projection,
admission delegation, and diagnostics.

Current first-slice contract split:

| Contract | Current implementer | Purpose |
| --- | --- | --- |
| `WorkerResourceRuntime` | `WorkerManager` | Runtime-api contract for Worker, WorkerGroup, AdapterNode, and NodeGroupBinding resource writes |
| `WorkerCandidateRuntime` | `WorkerManager` | Runtime-api candidate acquisition returning worker candidate rows |
| `WorkerSchedulingViewRuntime` | `WorkerManager` | Read evidence used to build engine scheduling candidates |
| `WorkerAdmissionRuntime` | `WorkerManager` | Runtime-api contract for reserve, confirm, release, final occupancy, exclusive lease, and load reads |
| `WorkerAvailabilityWakeupRuntime` | `WorkerManager` | Runtime-api lifecycle hook for worker availability evidence that can wake assignment retry / ready-scan paths |
| `WorkerDispatchGateRuntime` | `WorkerManager` | Runtime-api contract for source-scoped dispatch gate reads and mutations |
| `WorkerReportRuntime` | `WorkerManager` | Runtime-api report contract for capability report mutation currently owned by `WorkerCapabilityAuthority` via `WorkerManager` |
| `WorkerWarmHintRuntime` | `WorkerManager` | Runtime-api contract for task-local warm candidate hint mutation |

`WorkerCandidateRuntime` now accepts runtime-neutral `WorkerTaskSelector`, not
`Task`. The engine adapts `Task.sharedConfig` to selector evidence through
`WorkerTaskSelectorFactory` before crossing the candidate-source contract.
Candidate acquisition and task-local warm hints are now package-owned by
`WorkerCandidateSourceOwner`; `WorkerManager` delegates to it while the module
boundary is still inside engine.

`InMemoryWorkerRegistry` now lives in `platform_infra/mass-runtime-memory`.
SDK/server assembly injects it as the default embedded `WorkerRegistry`; engine
main consumes only the `WorkerRegistry` contract and no longer instantiates the
memory implementation.
Memory and Redis worker registries both consume runtime-api route-bucket policy
helpers; Redis tests no longer depend on engine `WorkerRoutingPolicy`.

`xa-mass-worker-runtime` now exists as the higher-level worker runtime owner
module. It owns WorkerGroup declaration state, AdapterNode / NodeGroupBinding
relationship state, Worker registration row to runtime slot projection, and
bounded worker state report projection. It also owns worker admission,
occupancy, exclusive lease operations, worker capability report application,
registry snapshot composition, Stage-1 candidate indexing/source orchestration,
and task-local warm candidate hints over `WorkerRegistry`. Engine still
assembles these owners through `WorkerManager` / `WorkerControlService`, but
those owner implementations are no longer engine-local source.

Task selector, candidate batch shape, candidate rows, Worker resource row,
WorkerGroup capability read view, scheduling-view contract, admission contract, worker load, resource
declaration records, and transport reachability evidence now use
runtime-neutral `mass-runtime-api` types: `WorkerTaskSelector`,
`WorkerCandidateBatch<T>`, `WorkerCandidateRow`, `WorkerResourceRecord`,
`WorkerCandidateRuntime`, `WorkerGroupCapabilityView`,
`WorkerSchedulingViewRuntime`, `WorkerAdmissionRuntime`, `WorkerResourceRuntime`,
`WorkerDispatchGateRuntime`, `WorkerAvailabilityWakeupRuntime`,
`WorkerWarmHintRuntime`, `WorkerLoadSnapshot`, `WorkerReachabilityState`,
`WorkerCapabilityReport`, `WorkerReportRuntime`,
`WorkerCapabilityReportStatus`, and `WorkerCapabilityReportResult`, plus worker state report/projection DTOs
(`WorkerStateReport`, `WorkerStateProjection`, `WorkerStateProjectionResult`,
`WorkerStateProjectionStatus`) and resource declaration records
(`AdapterNodeRecord`, `NodeGroupBindingRecord`, `WorkerGroupRecord`,
`EventBinding`). Engine-owned scheduling DTOs still adapt those runtime values
into `WorkerSchedulingView` and `WorkerMatchContext` locally.
Capability report application keeps `WorkerRegistrySnapshot` package-local so
external/report DTOs do not expose engine candidate snapshot truth.

Worker row mutation and registry slot projection are owned by
`xa-mass-worker-runtime` `WorkerResourceOwner`; WorkerGroup declaration state is
owned by `WorkerGroupOwner`; AdapterNode and WorkerGroup binding relationships
are owned by `WorkerRelationshipOwner`; bounded worker state report projection
is owned by `WorkerStateProjectionOwner`; worker runtime admission, exclusive
lease, and occupancy reads are owned by `WorkerAdmissionOwner`;
worker-originated capability report projection is owned by `WorkerReportOwner`.
`WorkerManager` still implements the external runtime contracts and delegates
to these owners while callers converge. `WorkerControlService` now consumes
`WorkerReportRuntime`, `WorkerResourceRuntime`, and
`WorkerDispatchGateRuntime` instead of accepting the full `WorkerManager`
assembly surface.
The SDK process-local runtime event bridge now depends on
`WorkerResourceRuntime` for legacy heartbeat refresh and no longer receives a
full `WorkerManager`. SDK worker shell reads and updates also use
`WorkerResourceRuntime` instead of direct `WorkerStorage` access.
Dispatch binding and task resource release now consume `WorkerAdmissionRuntime`
for reservation confirmation, fallback load claim, final-load accounting,
reservation release, and exclusive-lease release. These paths no longer require
the full `WorkerManager` surface; task runtime claim, dispatch handoff,
terminal handling, and refill decisions remain engine-owned.
`TaskWorkerAssignListener` now consumes `WorkerAdmissionRuntime` for active
occupancy reads and `WorkerWarmHintRuntime` for useful-candidate hint writes.
It no longer needs the full `WorkerManager`; assignment allocation, status
transition, dispatch binding, and refill timing stay in engine.
`MassEngine` now wires resource-side worker availability wakeups through
`WorkerAvailabilityWakeupRuntime` instead of directly importing
`WorkerManager`; startup/shutdown lifecycle wiring remains SDK assembly.
Kernel worker report/state/command event registration now uses worker-control
entry naming instead of WorkerManager owner naming; `TargetScope.WORKER_MANAGER`
remains the historical event-routing target, not the mutation owner.
Transport runtime dispatch routing now consumes `WorkerResourceRuntime` and
runtime-neutral `WorkerResourceRecord` instead of `WorkerLookupStore` or
mutable base `Worker` rows.
Perf runner deterministic matching support still uses `WorkerManager` for
local scenario assembly, but matching loops now consume
`WorkerResourceRuntime`, `WorkerAdmissionRuntime`, and
`WorkerSchedulingViewRuntime`. They no longer use the old model-shaped
`getAllWorkers()`, boolean reserve shortcut, or `Worker`-shaped dispatch gate
read paths.

## Public Method Inventory

| Method group | Methods | Current callers | Target owner | Truth layer | Notes |
| --- | --- | --- | --- | --- | --- |
| Worker row mutation | `addWorker`, `updateWorker`, `deleteWorker` | SDK registration, SDK worker shell update, tests, server bootstrap through SDK | `WorkerResourceOwner` through `WorkerResourceRuntime` | control-plane storage plus derived runtime projection | Cross-module calls use runtime-neutral `WorkerResourceRecord`; engine-local compatibility overloads still accept `Worker` |
| Worker row lookup | `worker`, `workers`, `getWorker`, `getAllWorkers` | SDK worker shell reads, SDK diagnostics, tests | `WorkerResourceOwner` through `WorkerResourceRuntime` | control-plane storage read | Runtime API exposes `worker` / `workers`; SDK, transport, and perf matching loops no longer read `WorkerStorage` or model-shaped `WorkerManager` lookup helpers for worker shell/candidate lookup; the separate `WorkerLookupStore` seam has been deleted |
| WorkerGroup mutation | `upsertWorkerGroup`, `deleteWorkerGroup` | SDK declaration, bootstrap, tests | `WorkerGroupOwner` through resource runtime | control-plane storage plus runtime candidate projection | `WorkerGroupRecord` / `EventBinding` are runtime-api declaration values; WorkerGroup remains capability declaration truth, not match strategy state |
| WorkerGroup read | `workerGroup`, `workerGroupReadView`, `workerGroups` | SDK read APIs, candidate enumeration, tests | `WorkerGroupOwner` / scheduling-view runtime | control-plane read plus runtime read model | `workerGroupReadView` is used by scheduling candidate enumeration |
| AdapterNode mutation | `registerAdapterNode`, `deleteAdapterNode` | SDK declaration, tests | `WorkerRelationshipOwner` through resource runtime | control-plane storage plus runtime projection | `AdapterNodeRecord` is a runtime-api declaration value; AdapterNode is endpoint/runtime-node declaration truth |
| AdapterNode read | `adapterNode`, `adapterNodes` | SDK read APIs, tests | `WorkerRelationshipOwner` through resource runtime | control-plane read | Not scheduling policy |
| NodeGroupBinding mutation | `bindNodeGroup`, `unbindNodeGroup`, `setNodeGroupBindingEnabled`, `setNodeGroupBindingDraining` | SDK declaration/control, tests | `WorkerRelationshipOwner` through resource runtime | control-plane declaration plus runtime dispatch gate | `NodeGroupBindingRecord` is a runtime-api declaration value; enabled/draining effects mutate source-scoped dispatch gates in `WorkerRegistry` |
| NodeGroupBinding read | `nodeGroupBinding`, `nodeGroupBindings`, `groupIdsByAdapterNodeId`, `adapterNodeIdsByGroupId` | SDK read APIs, tests, routing diagnostics | `WorkerRelationshipOwner` through resource runtime | control-plane read / runtime read model | Candidate source may consume relation evidence but not own declaration truth |
| Candidate source | `findWorkerCandidateBatch` | `RuleBasedTaskWorkerMatchingStrategy`, tests | `WorkerCandidateRuntime` / `WorkerCandidateIndex` in `xa-mass-worker-runtime` | runtime state | Uses runtime-neutral `WorkerCandidateBatch<WorkerCandidateRow>` and `WorkerTaskSelector`; Task-shaped convenience acquisition has been deleted; must start from resolved WorkerGroup selector; no all-worker candidate scan |
| Candidate diagnostics | `findWorkerCandidateBatch` metadata | tests and diagnostics | `WorkerCandidateSourceOwner` in `xa-mass-worker-runtime` plus engine diagnostic exposure | runtime read model residue | List-only `findWorkerCandidates` has been deleted; batch metadata is the strategy-facing contract |
| Candidate index diagnostics | `getWorkerCandidateIndex` | tests and indexed source diagnostics | `WorkerCandidateIndex` in `xa-mass-worker-runtime` plus engine-local diagnostic exposure | runtime read model residue | Kept off `WorkerCandidateRuntime` so the candidate contract does not expose Stage-1 implementation types |
| Warm hints | `recordWarmCandidate` | `TaskWorkerAssignListener`, strategy tests | `TaskCandidateWarmPool` in `xa-mass-worker-runtime` plus engine-side write timing | runtime state | Kept off `WorkerCandidateRuntime`; public entrypoint uses `WorkerTaskSelector` and `WorkerCandidateRow`; engine triggers after useful assignment evidence, runtime owns bounded hint storage |
| Snapshot maintenance | `refreshWorkerRegistrySnapshot`, `getWorkerRegistrySnapshot` | tests, diagnostics, capability report path | `WorkerRegistrySnapshot` in `xa-mass-worker-runtime` plus engine publication residue | runtime read model residue | Delete or narrow public snapshot access after runtime DTOs replace snapshot callers |
| Capability report | `applyWorkerCapabilityReport` | `WorkerControlService`, event handlers, SDK | `WorkerReportOwner` / `WorkerCapabilityAuthority` in `xa-mass-worker-runtime` | resource mutation plus runtime projection | Runtime-api report/result/status contract; engine still publishes the returned snapshot through `WorkerManager` |
| State report projection | `applyWorkerStateReport` / `WorkerStateProjectionOwner.applyReport` | `WorkerControlService`, event handlers, tests | `WorkerStateProjectionOwner` in `xa-mass-worker-runtime` | bounded runtime diagnostic projection | Runtime-api report/projection/result DTOs; engine callers consume the moved owner through `WorkerManager` assembly |
| Online model status | `updateOnlineStatus`, `isWorkerOnline` | legacy event bridge, tests | compatibility/resource status path | control-plane row plus transport reachability residue | Transport presence remains reachability owner |
| Reachability read | `getWorkerReachability` | scheduling candidate enumeration, tests | `WorkerSchedulingViewRuntime` | transport evidence consumed as runtime read evidence | Returns runtime-neutral `WorkerReachabilityState`; must not turn transport session into scheduling truth |
| Dispatch gate read | `isWorkerDispatchEnabled` | scheduling candidate enumeration, tests | `WorkerSchedulingViewRuntime` / `WorkerDispatchGateRuntime` | runtime state | Derived from source-scoped gates; scheduling consumes read evidence, control policies consume gate contract; the old `Worker`-shaped overload has been deleted |
| Dispatch gate mutation | `disableWorkerDispatch`, `clearWorkerDispatchDisable` | worker state report policy, node-group binding policy, tests | `WorkerDispatchGateRuntime` | runtime state | Source-scoped gate mutation; state/command policies no longer need the full `WorkerManager` surface |
| Wakeup callback | `setDispatchWakeupCallback` | SDK engine assembly through `WorkerAvailabilityWakeupRuntime` | assembly residue | lifecycle wiring | Narrow runtime-api hook for availability evidence; not candidate truth or dispatch-gate truth |
| Admission / occupancy | `reserveWorkerCapacity`, `confirmWorkerReservation`, `releaseWorkerReservation`, `recordWorkClaimed`, `recordWorkFinal` | match strategy, dispatch binder, resource releaser, result/resource listeners, perf runner matching support | `WorkerAdmissionOwner` through `WorkerAdmissionRuntime` | runtime state | Structured reserve is the only admission path; the old boolean reserve helper has been deleted |
| Exclusive lease | `tryAcquireWorkerExclusiveLease`, `releaseWorkerExclusiveLease`, `hasWorkerExclusiveLease`, `getExclusiveLeaseWorkerIds` | match strategy, resource releaser, diagnostics, tests | `WorkerAdmissionOwner` through `WorkerAdmissionRuntime` | runtime state | Rename away from lock vocabulary only when owner move requires it |
| Load / occupancy read | `getWorkerLoad`, `getActiveWorkerCountForTask` | match strategy, allocation policy caller, tests | `WorkerAdmissionOwner` / scheduling view runtime | runtime state | `WorkerLoadSnapshot` lives in `mass-runtime-api`; runtime load evidence, not control-plane truth |

## Current Main Caller Graph

```text
SDK/server shell
  -> MassSdkApplication
  -> EngineConfig worker runtime accessors
  -> WorkerResourceRuntime / WorkerAdmissionRuntime / WorkerAvailabilityWakeupRuntime methods

transport runtime tests
  -> WorkerManager for routing setup and read-model proof

engine assignment
  -> TaskWorkerAssignListener
  -> RuleBasedTaskWorkerMatchingStrategy
     -> WorkerCandidateRuntime from `mass-runtime-api`
     -> WorkerSchedulingViewRuntime from `mass-runtime-api` through WorkerSchedulingCandidateEnumerator
     -> WorkerAdmissionRuntime
  -> SimpleTaskDispatchBinder / WorkerDispatchResourceReleaser
     -> WorkerAdmissionRuntime
     -> WorkerAdmissionOwner through the configured worker admission runtime
  -> TaskResourceReleaseListener
     -> WorkerAdmissionRuntime for final-load and exclusive-lease release
  -> TaskWorkerAssignListener
     -> WorkerAdmissionRuntime for active occupancy reads
     -> WorkerWarmHintRuntime for task-local warm hint writes

worker control
  -> WorkerControlService
  -> WorkerReportRuntime.applyWorkerCapabilityReport
  -> WorkerReportOwner
  -> WorkerStateProjectionOwner
  -> WorkerDispatchAvailabilityPolicy
  -> WorkerDispatchGateRuntime dispatch-gate methods
  -> WorkerResourceRuntime.worker(...) for worker resource presence checks

worker relationship resources
  -> WorkerManager resource-compatible methods
  -> xa-mass-worker-runtime WorkerResourceOwner for Worker rows and slot projection
  -> xa-mass-worker-runtime WorkerGroupOwner for WorkerGroup declarations
  -> xa-mass-worker-runtime WorkerRelationshipOwner
  -> WorkerRegistry source-scoped dispatch gates for binding availability

perf runner deterministic matching support
  -> WorkerResourceRuntime.workers()
  -> WorkerAdmissionRuntime.reserveWorkerCapacity(...)
  -> WorkerSchedulingViewRuntime for reachability, dispatch gate, lease, load, and group read evidence
```

`RuleBasedTaskWorkerMatchingStrategy` now receives candidate acquisition,
admission, and scheduling-view runtimes as explicit constructor dependencies.
Production assembly passes explicit runtime contracts from `EngineConfig`; the
strategy no longer has a `WorkerManager` constructor path and candidate
acquisition no longer implicitly requires the same object to also implement
scheduling-view reads.

## Disposition Notes

### `WorkerLookupStore`

Current disposition: deleted.

Reason: transport runtime now consumes `WorkerResourceRuntime.worker(...)` and
`WorkerResourceRecord` for worker registration identity. `WorkerStorage` remains
the control-plane storage contract, while cross-module runtime callers use the
runtime-api resource read contract.

### `WorkerCandidateRuntime` and Candidate Rows

Current disposition: runtime-api contract plus runtime-neutral row value.

Reason: the strategy-facing Stage-1 contract no longer carries the mutable
`base.model.Worker` entity. `WorkerCandidateRuntime` lives in
`mass-runtime-api` and returns `WorkerCandidateBatch<WorkerCandidateRow>`.
Engine-owned code still materializes `WorkerSchedulingView` and
`WorkerMatchContext`, but the candidate-source protocol is no longer tied to
the control-plane worker model.

### `WorkerCandidateBatch`

Current disposition: generic runtime-api value type.

Reason: it was formerly `WorkerManager.WorkerCandidateBatch`, which forced
callers to depend on the god object type. It now lives in `mass-runtime-api` as
`WorkerCandidateBatch<T>` so the batch metadata is runtime-neutral. The active
candidate-source batch type is `WorkerCandidateBatch<WorkerCandidateRow>`.

### Admission Signature

Current facade methods are worker-id-only:

```text
reserveWorkerCapacity(workerId, taskId) -> ReserveResult
confirmWorkerReservation(workerId, taskId)
releaseWorkerReservation(workerId, taskId)
recordWorkFinal(workerId, taskId)
```

`reserveWorkerCapacity(workerId, taskId)` is the main strategy-facing path and
returns the structured `ReserveResult` from the underlying `WorkerRegistry`.
The former `tryReserveWorkerCapacity(workerId, taskId)` boolean helper has been
deleted. Callers must inspect the structured `ReserveResult` so admission
rejection ownership and reason are not collapsed.

The admission owner internally resolves `groupId` from the current worker slot,
uses one permit for the current scheduling path, and passes the current clock to
`WorkerRegistry.tryReserve(groupId, workerId, taskId, permits, nowMillis)`.
The extracted `WorkerAdmissionRuntime` contract currently keeps that as a
documented runtime-owned lookup. The implementation detail lives in
`WorkerAdmissionOwner`, not in the `WorkerManager` assembly surface.

### Task Selector Boundary

Current candidate-source contract input:

```text
WorkerTaskSelector
  taskId
  workerGroupIds
  adapterNodeId
  targetWorkerId
  routeBucketKeys
```

`WorkerTaskSelector` lives in `mass-runtime-api`. `Task.sharedConfig` parsing
remains on the engine side through `WorkerTaskSelectorFactory`. Worker runtime
contracts should keep consuming this selector shape and must not grow a
dependency on `Task`, `WorkerMatchContext`, or rule-evaluation DTOs.

### Warm Hint Boundary

Current put path:

```text
TaskWorkerAssignListener
  -> recordWarmCandidatesForBoundWorkers(...)
  -> WorkerWarmHintRuntime.recordWarmCandidate(selector, workerCandidateRow)
  -> TaskCandidateWarmPool in xa-mass-worker-runtime
```

Target path should be engine-triggered but runtime-owned:

```text
engine assignment success evidence
  -> worker runtime warm hint put(taskId, selector/source evidence, worker candidate row)
```

Do not make worker runtime depend on `Task`.

## Current Blockers Before Module Movement

- `WorkerSchedulingView` and `WorkerMatchContext` are engine strategy DTOs; they
  must not move into runtime contracts.
- `WorkerAdmissionRuntime`, `WorkerCandidateBatch<T>`, `WorkerTaskSelector`,
  `WorkerLoadSnapshot`, `WorkerReachabilityState`, and `WorkerReachabilityView`
  already live in `mass-runtime-api`.
- `AdapterNodeRecord`, `NodeGroupBindingRecord`, `WorkerGroupRecord`, and
  `EventBinding` now live in `mass-runtime-api`; their owners have moved to
  `xa-mass-worker-runtime`.
- `WorkerManager` still assembles worker-runtime owners and publishes registry
  snapshots, but no longer owns the resource maps, candidate source, warm pool,
  admission state, or report projection implementations directly.
- In-memory `WorkerRegistry` implementation is no longer a blocker for M2; the
  remaining M2 work is shared memory/Redis contract proof and engine import
  cleanup around slot/index internals.
- Command-gate effects remain engine-owned because the default policy consumes
  engine command lifecycle records. Do not move that policy until the command
  owner boundary is extracted or represented through a runtime-neutral command
  contract.
- Redis/memory WorkerRegistry implementations already share `mass-runtime-api`
  contracts; do not create another registry contract in a new module.
