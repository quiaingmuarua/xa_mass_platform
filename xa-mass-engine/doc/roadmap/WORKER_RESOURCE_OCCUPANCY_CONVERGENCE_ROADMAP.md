# Worker Resource Occupancy Convergence Roadmap

Last updated: 2026-05-22

Status: superseded for phases WRO-0 through WRO-5 by
[WORKER_SLOT_REGISTRY_ROADMAP.md](WORKER_SLOT_REGISTRY_ROADMAP.md).
WRO-6 (Redis worker registry) is absorbed into WSR-6 of that roadmap.

Do not execute this roadmap and WORKER_SLOT_REGISTRY_ROADMAP.md simultaneously.
Retain this file as a record of the original occupancy convergence intent.

## Summary

The current scheduling path has three overlapping resource-occupancy layers:

```text
WorkerStorage.lockedWorkers
  foreground exclusive worker lock

WorkerLoadView
  capacity reservation + active lease counters

TaskWorkRuntime
  ready queue + active work lease + result/expiry convergence
```

This works, but it is harder than necessary to reason about at scale. For a
task scheduling platform, every lock must have a clear owner, lifetime, and
release path. A lock that duplicates lease/capacity truth becomes a hidden
policy and complicates Redis/runtime convergence.

This roadmap converges worker resource occupancy toward:

```text
TaskWorkRuntime lease = final work ownership truth
WorkerLoadView capacity = dispatch admission truth
WorkerRegistry / WorkerSlot.disabledSources = new-dispatch gate truth
WorkerStorage = worker registry/index truth, not general lock owner
```

The target is not "no locks anywhere". The target is:

1. no duplicated lock/capacity truth in the ordinary dispatch path
2. no DB worker lock path
3. no permanent worker lock state without runtime lease evidence
4. foreground exclusivity expressed as capacity policy where possible
5. Redis/runtime backend can share the same occupancy contract as memory

## Current Runtime Structures

### WorkerStorage

Current role:

- worker identity lookup
- worker group index
- worker lock set

Current memory shape:

```text
workersById: workerId -> Worker
groupIdByWorkerId: workerId -> groupId
workerIdsByGroupId: groupId -> Set<workerId>
lockedWorkers: Set<workerId>
```

Concern:

`lockedWorkers` is a separate occupancy truth from `WorkerLoadView` and
`TaskWorkRuntime` active leases.

### WorkerManager

Current role:

- worker/group/adapter-node relationship owner
- publishes `WorkerRegistrySnapshot`
- owns dispatch availability and load view access

Current relationship indexes:

```text
workerRegistryRows: workerId -> Worker
workerGroupsById: groupId -> WorkerGroupRecord
adapterNodesById: adapterNodeId -> AdapterNodeRecord
nodeGroupBindingsByKey: (adapterNodeId, groupId) -> NodeGroupBindingRecord
groupIdsByAdapterNodeId: adapterNodeId -> Set<groupId>
adapterNodeIdsByGroupId: groupId -> Set<adapterNodeId>
```

Concern:

WorkerManager still exposes lock methods directly, so strategy code can treat
worker lock as a first-class scheduling primitive instead of a resource policy
detail.

### WorkerRegistrySnapshot / WorkerRouteBucketOwner

Current role:

- candidate-source read cache
- route-bucket bounded sampling

Current candidate indexes:

```text
workerIdsByGroupId: groupId -> Set<workerId>
workerIdsByAdapterNodeId: adapterNodeId -> Set<workerId>
workerIdsByAdapterNodeGroup: (adapterNodeId, groupId) -> Set<workerId>
route buckets: (groupId, routeBucketKey) -> List<workerId>
node route buckets: (groupId, adapterNodeId, routeBucketKey) -> List<workerId>
```

Concern:

No direct concern. These are candidate indexes, not occupancy owners.

### Registry-Backed Dispatch Gates

Current role:

- source-scoped dispatch gate
- disables new dispatch for worker state, command, or node-group drain

Current shape:

```text
disabledSourcesByWorkerId: workerId -> EnumSet<source>
```

Concern:

No direct concern. This is a gate, not a lock. It should remain separate from
capacity/lease truth.

### WorkerLoadView

Current role:

- declared capacity
- reservation before runtime claim
- active lease counters after runtime claim
- active workers per task

Current memory shape:

```text
declaredCapacities: workerId -> maxConcurrentWork
reservedCounts: workerId -> count
activeLeaseCounts: workerId -> count
activeWorkerCountsByTask: taskId -> workerId -> count
```

Concern:

This is the natural dispatch admission owner, but it is currently JVM-local and
partly duplicated by `WorkerStorage.lockedWorkers`.

### TaskWorkRuntime

Current role:

- ready work truth
- active lease truth
- result/expiry convergence truth

Current public lease surfaces:

```text
claimReady(taskId, workerTargets, options)
activeLeases(taskId)
getActiveLease(taskId, messageId)
hasActiveLeaseForWorker(taskId, workerId)
pollExpiredLeases(limit, now)
applyResult(...)
```

Concern:

This is already the strongest occupancy truth. Anything that says "worker is
busy" but cannot be reconciled with a runtime lease should be treated as
best-effort admission state, not final truth.

## Core Rules

1. `TaskWorkRuntime` owns active work leases.
2. `WorkerLoadView` owns admission counters and capacity.
3. `WorkerRegistry` owns source-scoped new-dispatch gates.
4. `WorkerStorage` must not become a durable or DB-backed worker lock owner.
5. Foreground exclusivity is a resource policy, not a separate lock model.
6. A worker is not "busy" unless occupancy can be tied to reservation or active
   lease evidence.
7. Reservation must be short-lived and released on all no-claim / no-dispatch
   paths.
8. Active lease release must converge from result, expiry, terminal closure, or
   compensation paths.
9. Redis and memory runtime must share the same occupancy contract and tests.
10. Query/diagnostics may expose lock-like evidence, but must label the owner:
    reservation, active lease, dispatch gate, or legacy exclusive lock.

## Non-Goals

This roadmap does not do:

1. No rewrite of `TaskWorkRuntime` queue semantics.
2. No DB worker lock table.
3. No device/account owner.
4. No WorkerSession model.
5. No transport-owned scheduling decisions.
6. No attempt to remove all synchronization primitives inside memory
   implementations.
7. No public API compatibility layer for legacy lock semantics.

## Desired End State

Ordinary dispatch should read like:

```text
Task workerGroupSelector
  -> WorkerCandidateIndex / route bucket
  -> prefilter dispatch gate + reachability
  -> rule/policy evaluation
  -> WorkerLoadView reserve capacity
  -> TaskWorkRuntime claimReady
  -> WorkerLoadView confirm active lease
  -> transport dispatch
  -> TaskWorkRuntime result/expiry convergence
  -> WorkerLoadView release active lease
```

Foreground exclusivity should become:

```text
resource policy sets effective capacity = 1 for this dispatch mode
```

not:

```text
capacity reservation + separate WorkerStorage exclusive lock
```

## Phase Plan

### WRO-0: Inventory And Guard

Goal: make the current occupancy owners explicit without behavior change.

Scope:

1. Add an owner inventory test or architecture guard that lists the only
   allowed worker occupancy owners:
   - `TaskWorkRuntime`
   - `WorkerLoadView`
   - registry-backed source-scoped dispatch gates
   - temporary `WorkerStorage` exclusive lock seam
2. Add a source scan guard that blocks JDBC worker lock/table reintroduction.
3. Add a source scan note that scheduling strategies must not call
   `WorkerStorage` lock methods directly after WRO-2.
4. Document current release paths:
   - no claimed work
   - dispatch submit failure
   - result accepted
   - lease expired
   - task terminal

Acceptance:

1. No behavior change.
2. Current tests pass.
3. The guard names every temporary lock seam explicitly.

### WRO-1: Occupancy Diagnostics Split

Goal: stop exposing "locked" as a single ambiguous concept.

Scope:

1. Add internal diagnostic view fields:
   - `dispatchEnabled`
   - `reservedCount`
   - `activeLeaseCount`
   - `declaredCapacity`
   - `legacyExclusiveLocked`
2. Update trace/debug context where currently useful.
3. Keep existing public `locked` field only if needed by current API tests, but
   define it as legacy exclusive lock evidence.

Acceptance:

1. Worker assignment diagnostics can distinguish gate, reservation, active
   lease, and legacy lock.
2. Existing API tests that expect `locked` still pass or are intentionally
   updated to the clearer field.

### WRO-2: Hide Lock Behind Resource Policy

Goal: prevent ordinary matching code from treating worker lock as a separate
mainline primitive.

Scope:

1. Introduce a narrow engine-internal admission/release method that applies
   `WorkerDispatchResourcePolicy`.
2. Move `tryReserveWorkerCapacity + optional tryLockWorker` behind that method.
3. Move `releaseReservation + optional unlockWorker` behind the matching
   releaser.
4. Keep behavior equivalent in this phase.
5. Add guard: strategy code must not call `tryLockWorker`, `unlockWorker`, or
   `isLocked` directly except for diagnostics/enumerator snapshots.

Acceptance:

1. Foreground still obtains exclusive legacy lock.
2. Background still uses capacity only.
3. No no-claim path leaks reservation or lock.
4. Existing WorkerManager/strategy tests pass.

### WRO-3: Capacity-First Foreground Policy

Goal: prove foreground exclusivity can be represented by capacity admission.

Scope:

1. Add policy option for foreground effective capacity:
   - default current behavior remains legacy lock + reservation
   - test-only/new mode uses capacity-only with effective per-worker capacity 1
2. Make capacity-only foreground path use the same `WorkerLoadView` reservation
   and runtime lease confirmation path as background.
3. Do not remove legacy lock yet.

Acceptance:

1. Capacity-only foreground test prevents two concurrent foreground claims for
   the same worker when effective capacity is 1.
2. Existing legacy foreground tests still pass.
3. Active lease release returns worker to eligible state.

### WRO-4: Lease-Reconciled Load View

Goal: make load counters recoverable from runtime lease truth.

Scope:

1. Add a reconciliation method that rebuilds or corrects active lease counters
   from `TaskWorkRuntime.activeLeases(taskId)` for bounded task scopes.
2. Use it in restart/recovery-oriented tests or maintenance paths, not as a
   hot-path scan.
3. Ensure result/expiry/terminal release paths are idempotent.

Acceptance:

1. Duplicate release does not create negative counters.
2. Runtime active lease evidence can repair stale active count for a task.
3. No global all-task scan is added to the hot path.

### WRO-5: Retire Legacy Exclusive Lock From Ordinary Dispatch

Goal: remove `WorkerStorage.lockedWorkers` from normal scheduling admission.

Prerequisite:

- WRO-3 capacity-only foreground proof is stable.
- WRO-4 lease reconciliation proof is stable.

Scope:

1. Switch foreground default to capacity-only effective capacity 1.
2. Remove ordinary matching dependency on `WorkerStorage.tryLockWorker`.
3. Remove lock release dependency from result/terminal path.
4. Keep or remove query-facing `locked` only after tests/docs are updated.

Acceptance:

1. Ordinary scheduling does not call `tryLockWorker`.
2. Worker exclusivity is enforced by capacity/lease proof.
3. Result, expiry, and terminal paths release capacity consistently.
4. No stale legacy lock can block a worker.

### WRO-6: WorkerStorage Contract Narrowing

Goal: make worker storage a registry/index contract, not an occupancy lock
contract.

Scope:

1. Remove lock methods from `WorkerStorage` if no remaining production caller
   requires them.
2. Rename or split contract only if it creates a real owner boundary:
   - `WorkerRegistryStore` for identity/index
   - no fake adapter/wrapper just to preserve old names
3. Update memory/redis worker registry contracts together when Redis worker
   registry exists.

Acceptance:

1. `WorkerStorage` or its replacement has no lock methods.
2. Memory and Redis worker registry tests share the same contract.
3. DB/JDBC still has no worker registry or lock table.

## Test Plan

### Unit / Contract

- WorkerLoadView reservation and release idempotency.
- Resource policy foreground effective capacity.
- WorkerStorage no longer owns lock after WRO-6.
- Architecture guard for no JDBC worker lock/table.

### Integration

- foreground task: same worker cannot receive concurrent work beyond effective
  capacity.
- background task: worker can receive concurrent work up to declared capacity.
- no-claim path releases reservation.
- dispatch submit failure compensates runtime claim and releases load.
- result accepted releases active count.
- lease expiry releases active count and makes task retryable.

### Proof / Trace

- trace shows owner-specific occupancy evidence:
  - dispatch gate
  - reservation
  - active lease
  - capacity
- no proof should assert a generic "locked" state without naming owner.

## Risks

Risk 1: capacity-only foreground allows duplicate dispatch under race.

Mitigation:

Use `WorkerLoadView.tryReserveCapacity` as the only admission mutation and
confirm through `TaskWorkRuntime.claimReady`. Redis version must use atomic
increment/check semantics.

Risk 2: load counters drift from runtime leases.

Mitigation:

Make release idempotent and add bounded reconciliation from active lease truth.

Risk 3: removing `locked` breaks operator expectations.

Mitigation:

First split diagnostics into owner-specific fields, then retire legacy `locked`
only after callers/tests are updated.

Risk 4: Redis runtime gets a different occupancy model from memory.

Mitigation:

Define shared contract tests before Redis worker registry/load implementation.

## Recommended First Slice

Do WRO-0 + WRO-1 first.

Why:

1. They reduce ambiguity without changing behavior.
2. They expose where lock evidence is actually used.
3. They make later removal measurable instead of speculative.

Do not start by deleting `lockedWorkers`. That would be faster but unsafe
because current foreground semantics and some diagnostics still depend on it.
