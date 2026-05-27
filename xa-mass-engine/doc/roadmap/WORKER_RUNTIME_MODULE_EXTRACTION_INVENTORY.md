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
| `WorkerResourceRuntime` | `WorkerManager` | Worker, WorkerGroup, AdapterNode, and NodeGroupBinding resource writes |
| `WorkerCandidateRuntime` | `WorkerManager` | Runtime-api candidate acquisition returning worker candidate rows |
| `WorkerSchedulingViewRuntime` | `WorkerManager` | Read evidence used to build engine scheduling candidates |
| `WorkerAdmissionRuntime` | `WorkerManager` | Runtime-api contract for reserve, confirm, release, final occupancy, exclusive lease, and load reads |
| `WorkerReportRuntime` | `WorkerManager` | Capability report mutation currently owned by `WorkerCapabilityAuthority` via `WorkerManager` |
| `WorkerLookupStore` | `WorkerManager` | Storage-edge single-worker lookup seam; disposition still open |

`WorkerCandidateRuntime` now accepts runtime-neutral `WorkerTaskSelector`, not
`Task`. The engine adapts `Task.sharedConfig` to selector evidence through
`WorkerTaskSelectorFactory` before crossing the candidate-source contract.
Candidate acquisition and task-local warm hints are now package-owned by
`WorkerCandidateSourceOwner`; `WorkerManager` delegates to it while the module
boundary is still inside engine.

Task selector, candidate batch shape, candidate rows, WorkerGroup capability
read view, scheduling-view contract, admission contract, worker load, and
transport reachability evidence now use runtime-neutral `mass-runtime-api`
types: `WorkerTaskSelector`,
`WorkerCandidateBatch<T>`, `WorkerCandidateRow`,
`WorkerCandidateRuntime`, `WorkerGroupCapabilityView`,
`WorkerSchedulingViewRuntime`, `WorkerAdmissionRuntime`, `WorkerLoadSnapshot`,
and `WorkerReachabilityState`. Engine-owned scheduling DTOs still adapt those
runtime values into `WorkerSchedulingView` and `WorkerMatchContext` locally.

Worker row mutation and registry slot projection are now package-owned by
`WorkerResourceOwner`; WorkerGroup declaration state is package-owned by
`WorkerGroupOwner`; worker-originated capability report projection is
package-owned by `WorkerReportOwner`; worker runtime admission, exclusive lease,
and occupancy reads are package-owned by `WorkerAdmissionOwner`; AdapterNode and
WorkerGroup binding relationships are package-owned by `WorkerRelationshipOwner`.
`WorkerManager` still implements the external contracts and delegates to these
owners while callers converge.

## Public Method Inventory

| Method group | Methods | Current callers | Target owner | Truth layer | Notes |
| --- | --- | --- | --- | --- | --- |
| Worker row mutation | `addWorker`, `updateWorker`, `deleteWorker` | SDK registration, tests, server bootstrap through SDK | `WorkerResourceOwner` through resource runtime | control-plane storage plus derived runtime projection | Registration row is stable resource truth; `WorkerMeta` slot projection is runtime truth |
| Worker row lookup | `getWorker`, `findWorker`, `getAllWorkers` | SDK diagnostics, storage-edge lookup, tests | `WorkerResourceOwner` / replacement read contract | control-plane storage read | `findWorker` exists because `WorkerLookupStore` is still active |
| WorkerGroup mutation | `upsertWorkerGroup`, `deleteWorkerGroup` | SDK declaration, bootstrap, tests | `WorkerGroupOwner` through resource runtime | control-plane storage plus runtime candidate projection | WorkerGroup is capability declaration truth, not match strategy state |
| WorkerGroup read | `workerGroup`, `workerGroupReadView`, `workerGroups` | SDK read APIs, candidate enumeration, tests | `WorkerGroupOwner` / scheduling-view runtime | control-plane read plus runtime read model | `workerGroupReadView` is used by scheduling candidate enumeration |
| AdapterNode mutation | `registerAdapterNode`, `deleteAdapterNode` | SDK declaration, tests | `WorkerRelationshipOwner` through resource runtime | control-plane storage plus runtime projection | AdapterNode is endpoint/runtime-node declaration truth |
| AdapterNode read | `adapterNode`, `adapterNodes` | SDK read APIs, tests | `WorkerRelationshipOwner` through resource runtime | control-plane read | Not scheduling policy |
| NodeGroupBinding mutation | `bindNodeGroup`, `unbindNodeGroup`, `setNodeGroupBindingEnabled`, `setNodeGroupBindingDraining` | SDK declaration/control, tests | `WorkerRelationshipOwner` through resource runtime | control-plane declaration plus runtime dispatch gate | Enabled/draining effects mutate source-scoped dispatch gates in `WorkerRegistry` |
| NodeGroupBinding read | `nodeGroupBinding`, `nodeGroupBindings`, `groupIdsByAdapterNodeId`, `adapterNodeIdsByGroupId` | SDK read APIs, tests, routing diagnostics | `WorkerRelationshipOwner` through resource runtime | control-plane read / runtime read model | Candidate source may consume relation evidence but not own declaration truth |
| Candidate source | `findWorkerCandidateBatch` | `RuleBasedTaskWorkerMatchingStrategy`, tests | `WorkerCandidateRuntime` | runtime state | Uses runtime-neutral `WorkerCandidateBatch<WorkerCandidateRow>`; must start from resolved WorkerGroup selector; no all-worker candidate scan |
| Candidate diagnostics | `findWorkerCandidateBatch` metadata | tests and diagnostics | `WorkerCandidateSourceOwner` / diagnostics residue | runtime read model residue | List-only `findWorkerCandidates` has been deleted; batch metadata is the strategy-facing contract |
| Candidate index diagnostics | `getWorkerCandidateIndex` | tests and indexed source diagnostics | `WorkerCandidateSourceOwner` / diagnostics residue | runtime read model residue | Kept off `WorkerCandidateRuntime` so the candidate contract does not expose Stage-1 implementation types |
| Warm hints | `recordWarmCandidate` | `TaskWorkerAssignListener`, strategy tests | `WorkerCandidateSourceOwner` / future warm hint write contract | runtime state | Kept off `WorkerCandidateRuntime`; engine triggers after useful assignment evidence, runtime owns hint storage/revalidation |
| Snapshot maintenance | `refreshWorkerRegistrySnapshot`, `getWorkerRegistrySnapshot` | tests, diagnostics, capability report path | candidate/resource read model residue | runtime read model residue | Delete or narrow after runtime DTOs replace snapshot callers |
| Capability report | `applyWorkerCapabilityReport` | `WorkerControlService`, event handlers, SDK | `WorkerReportOwner` through `WorkerReportRuntime` | resource mutation plus runtime projection | Capability truth materializes into WorkerGroup/snapshot evidence |
| Online model status | `updateOnlineStatus`, `isWorkerOnline` | legacy event bridge, tests | compatibility/resource status path | control-plane row plus transport reachability residue | Transport presence remains reachability owner |
| Reachability read | `getWorkerReachability` | scheduling candidate enumeration, tests | `WorkerSchedulingViewRuntime` | transport evidence consumed as runtime read evidence | Returns runtime-neutral `WorkerReachabilityState`; must not turn transport session into scheduling truth |
| Dispatch gate read | `isWorkerDispatchEnabled` | scheduling candidate enumeration, tests | `WorkerSchedulingViewRuntime` | runtime state | Derived from source-scoped gates |
| Dispatch gate mutation | `disableWorkerDispatch`, `clearWorkerDispatchDisable` | worker state report policy, node-group binding policy, tests | worker runtime dispatch gate owner | runtime state | Source-scoped gate mutation |
| Wakeup callback | `setDispatchWakeupCallback` | SDK engine assembly | assembly residue | lifecycle wiring | Keep as assembly wiring until owner split decides final home |
| Admission / occupancy | `reserveWorkerCapacity`, `confirmWorkerReservation`, `releaseWorkerReservation`, `recordWorkClaimed`, `recordWorkFinal` | match strategy, dispatch binder, resource releaser, result/resource listeners | `WorkerAdmissionOwner` through `WorkerAdmissionRuntime` | runtime state | Structured reserve is the strategy path; boolean reserve remains a `WorkerManager`-only internal helper |
| Exclusive lease | `tryAcquireWorkerExclusiveLease`, `releaseWorkerExclusiveLease`, `hasWorkerExclusiveLease`, `getExclusiveLeaseWorkerIds` | match strategy, resource releaser, diagnostics, tests | `WorkerAdmissionOwner` through `WorkerAdmissionRuntime` | runtime state | Rename away from lock vocabulary only when owner move requires it |
| Load / occupancy read | `getWorkerLoad`, `getActiveWorkerCountForTask` | match strategy, allocation policy caller, tests | `WorkerAdmissionOwner` / scheduling view runtime | runtime state | `WorkerLoadSnapshot` lives in `mass-runtime-api`; runtime load evidence, not control-plane truth |

## Current Main Caller Graph

```text
SDK/server shell
  -> MassSdkApplication
  -> EngineConfig.getWorkerManager()
  -> WorkerManager resource/report methods

transport runtime tests
  -> WorkerManager for routing setup and read-model proof

engine assignment
  -> TaskWorkerAssignListener
  -> RuleBasedTaskWorkerMatchingStrategy
     -> WorkerCandidateRuntime from `mass-runtime-api`
     -> WorkerSchedulingViewRuntime from `mass-runtime-api` through WorkerSchedulingCandidateEnumerator
     -> WorkerAdmissionRuntime
  -> SimpleTaskDispatchBinder / WorkerDispatchResourceReleaser
     -> WorkerAdmissionRuntime-compatible methods through WorkerManager
     -> WorkerAdmissionOwner

worker control
  -> WorkerControlService
  -> WorkerManager.applyWorkerCapabilityReport
  -> WorkerReportOwner
  -> WorkerStateProjectionOwner
  -> WorkerDispatchAvailabilityPolicy
  -> WorkerManager dispatch-gate methods

worker relationship resources
  -> WorkerManager resource-compatible methods
  -> WorkerResourceOwner for Worker rows and slot projection
  -> WorkerGroupOwner for WorkerGroup declarations
  -> WorkerRelationshipOwner
  -> WorkerRegistry source-scoped dispatch gates for binding availability
```

`RuleBasedTaskWorkerMatchingStrategy` now receives candidate acquisition,
admission, and scheduling-view runtimes as explicit constructor dependencies.
Production assembly still passes `WorkerManager` for all three while the
implementation remains inside engine, but candidate acquisition no longer
implicitly requires the same object to also implement scheduling-view reads.

## Disposition Notes

### `WorkerLookupStore`

Current disposition: keep temporarily.

Reason: `WorkerManager` still implements storage-edge `WorkerLookupStore` for
single-worker lookup. It overlaps with future resource read/scheduling view
contracts, but deleting it now would be rename churn before owner split. WRX-C1
must keep the overlap visible; WRX-D1 should either delete it or replace it with
a worker-runtime/resource read contract.

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
tryReserveWorkerCapacity(workerId, taskId)
confirmWorkerReservation(workerId, taskId)
releaseWorkerReservation(workerId, taskId)
recordWorkFinal(workerId, taskId)
```

`reserveWorkerCapacity(workerId, taskId)` is the main strategy-facing path and
returns the structured `ReserveResult` from the underlying `WorkerRegistry`.
`tryReserveWorkerCapacity(workerId, taskId)` remains a `WorkerManager` boolean
helper for older internal call sites and tests, but is not part of the extracted
`WorkerAdmissionRuntime` contract.

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
  -> WorkerManager.recordWarmCandidate(selector, workerCandidateRow)
  -> TaskCandidateWarmPool
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
  `WorkerLoadSnapshot`, and `WorkerReachabilityState` already live in
  `mass-runtime-api`.
- `WorkerManager` still owns both resource maps and runtime slot synchronization.
- `WorkerStateProjectionOwner` and command-gate effects need a resource/report
  owner split before moving.
- Redis/memory WorkerRegistry implementations already share `mass-runtime-api`
  contracts; do not create another registry contract in a new module.
