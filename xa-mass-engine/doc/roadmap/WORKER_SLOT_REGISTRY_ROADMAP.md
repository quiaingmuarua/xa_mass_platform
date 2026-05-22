# Worker Slot Registry Roadmap

Last updated: 2026-05-22

Status: proposed redesign. Verify current code before implementing each phase.

## Relationship To Other Roadmaps

This roadmap supersedes
`WORKER_RESOURCE_OCCUPANCY_CONVERGENCE_ROADMAP.md` (WRO) phases WRO-0 through
WRO-5. WRO-6 (Redis worker registry) is absorbed into WSR-6 here.

The WRO roadmap identified the right target (one occupancy owner, no CRUD lock,
capacity-first foreground). This roadmap pursues the same target through a
deeper structural change: collapse the three-copy worker data model into a
single per-worker `WorkerSlot` record with CAS-based occupancy mutations.

Do not execute both roadmaps simultaneously. If WRO has already landed WRO-0
through WRO-2, treat those commits as preparation for WSR-1.

## Summary

The current worker runtime has three overlapping copies of worker data:

```text
WorkerStorage.workersById
  CRUD-oriented storage, synchronized addWorker/updateWorker/deleteWorker,
  owns lockedWorkers set as a separate occupancy truth

WorkerManager.workerRegistryRows
  A second live copy maintained behind workerRegistryLock,
  rebuilt into WorkerRegistrySnapshot on every write

WorkerRegistrySnapshot
  Immutable read-cache rebuilt in full from all workers and groups on every
  worker or group change, used as the scheduling hot-path source
```

Occupancy state is split between `WorkerStorage.lockedWorkers` and
`InMemoryWorkerLoadView`, which uses a coarse `synchronized(this)` lock despite
holding `ConcurrentHashMap<String, AtomicInteger>` internally.

The result:

- every worker registration triggers a full snapshot rebuild
- every capacity check requires a global lock over an object that spans all
  workers
- exclusive lock state and capacity state have no shared synchronization
  boundary, so `tryReserve + tryLock` cannot be atomic
- Worker data lives in three places, and any Redis or persistent runtime
  implementation must replicate all three

This roadmap replaces those structures with:

```text
WorkerRegistry
  single per-worker AtomicReference<WorkerSlot> as occupancy + identity truth
  pre-computed secondary indexes as space-for-time read acceleration
  CAS-based reserve/release/confirm with no global lock
```

## Core Rules

1. One `WorkerSlot` per worker is the single occupancy + identity truth.
2. Occupancy mutations use per-worker CAS. No global lock over all workers.
3. `WorkerStorage` CRUD interface must not survive beyond WSR-4.
4. `lockedWorkers` must not survive beyond WSR-4.
5. Foreground exclusivity is expressed as `declaredCapacity=1` or equivalent
   resource policy. It is not a separate lock set.
6. `dispatchEnabled` in `WorkerSlot` replaces the gate semantics currently in
   `WorkerDispatchAvailabilityOwner`. Gate source tracking remains valid; it
   now applies to the slot.
7. Secondary indexes are maintained incrementally on slot writes, not rebuilt
   in full.
8. `WorkerRegistrySnapshot` becomes a thin read view over the registry, not a
   full copy of all worker data.
9. Memory and Redis implementations share the same `WorkerRegistry` contract
   and the same contract test suite.
10. `WorkerRouteBucketOwner` may continue deriving from registry state but must
    not hold its own stale copy of worker membership after WSR-3.

## Non-Goals

1. No rewrite of `TaskWorkRuntime` queue or lease semantics.
2. No device or account owner.
3. No WorkerSession model.
4. No transport-owned scheduling decisions.
5. No public API contract change for worker registration or capability report.
6. No change to `WorkerDispatchAvailabilityOwner` source-gate logic; only the
   storage target changes.
7. No premature Redis implementation before the contract is proven in memory.

## WorkerSlot Design

```text
WorkerSlot (immutable record, updated via CAS)

  identity
    workerId          String
    groupId           String          denormalized from Worker.workerGroupId
    adapterNodeId     String          denormalized from Worker.adapterNodeId
    worker            Worker          full attribute/metadata carrier

  capability
    declaredCapacity  int             from capability report; minimum 1
    eventKeys         Set<EventKey>   capability ceiling; empty = no ceiling

  occupancy (CAS-maintained)
    activeLeaseCount  int             confirmed active leases
    reservedCount     int             optimistic pre-claim reservations

  gate
    dispatchEnabled   boolean         false = DRAINING or WORKER_COMMAND gate
```

Derived invariants:

```text
canReserve = dispatchEnabled
             && activeLeaseCount + reservedCount < max(1, declaredCapacity)

isForegroundExclusive = declaredCapacity == 1
```

CAS operations on `WorkerSlot`:

```text
tryReserve(workerId)       -> boolean   CAS: +1 reservedCount if canReserve
confirmReservation(workerId)-> boolean  CAS: -1 reservedCount, +1 activeLeaseCount
releaseReservation(workerId)-> void     CAS: -1 reservedCount (min 0)
recordWorkFinal(workerId)   -> void     CAS: -1 activeLeaseCount (min 0)
setDispatchEnabled(workerId, boolean)   CAS: update gate
```

Each operation is a bounded retry loop on the per-worker `AtomicReference`.
Workers are independent so contention is bounded per worker, not global.

## WorkerRegistry Contract

```text
WorkerRegistry

  slot access
    upsertSlot(Worker, int declaredCapacity, Set<EventKey> eventKeys) -> void
    removeSlot(workerId) -> void
    slot(workerId) -> Optional<WorkerSlot>
    slots() -> Collection<WorkerSlot>

  occupancy mutations
    tryReserve(workerId, taskId) -> boolean
    confirmReservation(workerId, taskId) -> boolean
    releaseReservation(workerId, taskId) -> void
    recordWorkFinal(workerId, taskId) -> void

  gate mutations
    setDispatchEnabled(workerId, boolean, source) -> void

  candidate indexes (space-for-time reads)
    workerIdsByGroupId(groupId) -> Set<String>
    workerIdsByAdapterNodeId(adapterNodeId) -> Set<String>
    workerIdsByAdapterNodeGroup(adapterNodeId, groupId) -> Set<String>
    routeBucketKeysByWorkerId(workerId) -> Set<String>
```

`WorkerRegistry` replaces the combined surface of `WorkerStorage`,
`InMemoryWorkerLoadView`, and the lock methods on `WorkerManager`.

## Target Scheduling Path

Ordinary dispatch after this roadmap:

```text
task workerGroupSelector
  -> WorkerCandidateIndex / WorkerRouteBucketOwner (reads registry indexes)
  -> prefilter: registry.slot(workerId).dispatchEnabled + reachability
  -> rule/policy evaluation
  -> registry.tryReserve(workerId, taskId)         // CAS, no global lock
  -> TaskWorkRuntime.claimReady(...)
  -> registry.confirmReservation(workerId, taskId) // CAS
  -> transport dispatch
  -> result / expiry convergence
  -> registry.recordWorkFinal(workerId, taskId)    // CAS
```

No `WorkerStorage.tryLockWorker`. No `InMemoryWorkerLoadView.synchronized`.

## Phase Plan

### WSR-0: Inventory And Contract Definition

Goal: define `WorkerSlot` and `WorkerRegistry` as types only, with contract
tests proving CAS correctness under concurrency. No behavior change.

Scope:

1. Define `WorkerSlot` as an immutable record with the fields above.
2. Define `WorkerRegistry` as an interface with the contract above.
3. Write `WorkerRegistryContractTest` covering:
   - concurrent `tryReserve` respects `declaredCapacity`
   - `confirmReservation` atomically moves reserved → active
   - `releaseReservation` is idempotent (no negative counts)
   - `recordWorkFinal` is idempotent (no negative counts)
   - `setDispatchEnabled(false)` blocks `tryReserve` immediately
   - `upsertSlot` updates identity without disturbing occupancy counts
   - `removeSlot` clears all index memberships
4. Do not implement `InMemoryWorkerRegistry` yet.

Acceptance:

1. `WorkerSlot` and `WorkerRegistry` compile and have full javadoc contracts.
2. `WorkerRegistryContractTest` is abstract with a factory method for the
   implementation under test.
3. No existing test changes.

### WSR-1: InMemoryWorkerRegistry Implementation

Goal: implement `WorkerRegistry` for in-memory runtime. Prove correctness
through the shared contract test.

Scope:

1. Implement `InMemoryWorkerRegistry`:
   - `ConcurrentHashMap<String, AtomicReference<WorkerSlot>> slotsById`
   - secondary indexes as `ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>`
     for `workerIdsByGroupId`, `workerIdsByAdapterNodeId`, `workerIdsByAdapterNodeGroup`
   - index updates happen inside `upsertSlot` and `removeSlot` using
     compare-and-remove on the old slot's groupId/adapterNodeId before adding
     to the new slot's keys
2. Implement `routeBucketKeysByWorkerId` using `WorkerRoutingPolicy` on upsert.
3. `InMemoryWorkerRegistryTest` extends `WorkerRegistryContractTest`.
4. Do not wire into `WorkerManager` yet.

Acceptance:

1. All `WorkerRegistryContractTest` cases pass.
2. Concurrent upsert and remove do not corrupt index membership.
3. Route bucket keys are derived on upsert, not on each read.
4. No existing test changes.

### WSR-2: WorkerManager Convergence

Goal: make `WorkerManager` use `WorkerRegistry` as its primary worker data
source. Remove the `workerRegistryRows` duplicate copy.

Scope:

1. Replace `WorkerManager.workerRegistryRows: LinkedHashMap<String, Worker>`
   with `WorkerRegistry`.
2. Worker registration (`putRegistryRow`, `removeRegistryRow`) calls
   `registry.upsertSlot` / `registry.removeSlot`.
3. `WorkerCapabilityAuthority.applyReport` updates `declaredCapacity` and
   `eventKeys` on the slot via `registry.upsertSlot`.
4. `WorkerManager.findWorkerCandidates` continues through
   `WorkerCandidateIndex`, which now reads from registry indexes instead of
   `WorkerRegistrySnapshot.workerIdsByGroupId`.
5. Keep `WorkerRegistrySnapshot` publication for now; derive it from
   `registry.slots()` instead of from `workerRegistryRows`.
6. Keep `WorkerStorage` for persistence reads on startup only.

Acceptance:

1. `WorkerManager` no longer holds `workerRegistryRows`.
2. Worker registration and deregistration update the registry indexes.
3. `WorkerRegistrySnapshot` is derived from registry state, not from a
   separate live map.
4. Existing engine scheduling and manager tests pass.

### WSR-3: Occupancy Convergence

Goal: replace `InMemoryWorkerLoadView` with `WorkerRegistry` occupancy
operations. Remove the global `synchronized` lock.

Scope:

1. Wire `WorkerManager.tryReserveWorkerCapacity` → `registry.tryReserve`.
2. Wire `WorkerManager.releaseWorkerReservation` → `registry.releaseReservation`.
3. Wire load view confirm / work-final callbacks → `registry.confirmReservation`
   and `registry.recordWorkFinal`.
4. `WorkerDispatchAvailabilityOwner` gate mutations call
   `registry.setDispatchEnabled` instead of maintaining a separate
   `disabledSourcesByWorkerId` map. Gate source tracking can remain as a
   side-channel for diagnostics.
5. Remove `InMemoryWorkerLoadView` once no caller remains.

Acceptance:

1. `tryReserveCapacity` uses per-worker CAS with no global lock.
2. Concurrent reserve/release for different workers does not block each other.
3. `dispatchEnabled=false` blocks new reserves immediately.
4. Active lease count and reservation count are never negative.
5. Existing fault matrix chaos runners pass.

### WSR-4: Retire WorkerStorage Lock Interface And lockedWorkers

Goal: remove `lockedWorkers` and the lock methods from `WorkerStorage`.

Scope:

1. Remove `tryLockWorker`, `unlockWorker`, `isLocked`, `getLockedWorkers`
   from `WorkerStorage` interface.
2. Remove `lockedWorkers` field from `InMemoryWorkerStorage`.
3. Remove lock pass-through methods from `WorkerManager`.
4. Update `WorkerDispatchResourceReleaser` and
   `RuleBasedTaskWorkerMatchingStrategy` to use `registry.setDispatchEnabled`
   or capacity-only policy instead of `tryLockWorker`.
5. Update `EngineSchedulingCoreArchitectureGuardTest` to assert no
   `tryLockWorker` / `unlockWorker` / `isLocked` calls exist outside
   diagnostics.

Acceptance:

1. `WorkerStorage` interface has no lock methods.
2. `InMemoryWorkerStorage` has no `lockedWorkers` field.
3. Foreground exclusivity is enforced by `declaredCapacity=1` or resource
   policy, not by a separate lock.
4. Architecture guard fails on reintroduction of lock methods.
5. Existing foreground and background scheduling tests pass.

### WSR-5: WorkerRegistrySnapshot As Thin View

Goal: remove the full-rebuild snapshot pattern. `WorkerRegistrySnapshot`
becomes a read-only projection over registry state, not a standalone copy.

Scope:

1. Remove `withWorker`, `withoutWorker`, `withGroup`, `withoutGroup` mutation
   helpers from `WorkerRegistrySnapshot`.
2. Replace internal snapshot maps with direct reads from `WorkerRegistry`
   where possible, or keep a copy only for the immutable-read guarantee during
   a single scheduling pass.
3. `WorkerCandidateIndex` reads from `WorkerRegistry` indexes directly.
4. `WorkerRouteBucketOwner` is rebuilt from registry state on slot change
   rather than from a snapshot copy.
5. Remove `WorkerManager.publishWorkerRegistrySnapshot` if no longer needed
   as a full-copy publish mechanism.

Acceptance:

1. No full-copy snapshot rebuild on every worker registration.
2. Scheduling hot path reads from registry indexes without copying.
3. `WorkerRegistrySnapshot` is kept only if it provides a stable read
   boundary for a single scheduling pass; it holds no mutable worker state.
4. Existing scheduling correctness tests pass.

### WSR-6: Redis WorkerRegistry Foundation

Goal: prove the same `WorkerRegistry` contract works on a Redis-backed
implementation.

Scope:

1. Define Redis key structure:

   ```text
   worker:{workerId}:slot     hash   identity + capacity + eventKeys
   worker:{workerId}:occ      hash   activeLeaseCount, reservedCount, dispatchEnabled
   group:{groupId}:workers    set    workerId members
   node:{adapterNodeId}:workers set  workerId members
   node-group:{nodeId}:{groupId}:workers set  workerId members
   ```

2. Implement `RedisWorkerRegistry` using Lua scripts or atomic commands for
   CAS occupancy operations.
3. `RedisWorkerRegistryTest` extends `WorkerRegistryContractTest`.
4. Do not wire into production `WorkerManager` until contract tests pass and
   Redis runtime integration is verified.

Acceptance:

1. `RedisWorkerRegistryTest` passes the full contract suite.
2. Concurrent reserve/release with Redis atomic semantics does not allow
   over-reservation.
3. Key structure uses isolated namespace prefixes.
4. No memory-only assumptions leak into the Redis implementation.

## Testing Plan

### Contract

- `WorkerRegistryContractTest` (abstract, shared by memory and Redis)
  - concurrent reserve respects declared capacity
  - confirm/release idempotency
  - gate blocks reserve immediately
  - upsert preserves occupancy counts
  - remove clears all index memberships

### Engine Integration

- existing `WorkerManagerTest`, `WorkerCandidateIndexTest`,
  `WorkerRouteBucketOwnerTest` must pass at each phase
- foreground scheduling cannot assign same worker concurrently
- background scheduling up to declared capacity
- no-claim path releases reservation before any transport call
- dispatch failure compensates runtime claim and releases load

### Architecture Guards

After WSR-4:

- `WorkerStorage` interface has no lock methods
- `WorkerManager` has no direct `lockedWorkers` reference
- `InMemoryWorkerStorage` has no `lockedWorkers` field
- no call to `tryLockWorker` / `unlockWorker` / `isLocked` outside named
  diagnostics helper

## Risks

### Risk 1: CAS retry loops under high reservation contention

Mitigation:

CAS contention is per-worker. A worker with `declaredCapacity=1` under heavy
reassignment attempts will spin, but spins are bounded by the number of
concurrent scheduling threads, not by total fleet size. Profile before
optimizing.

### Risk 2: Index staleness between upsert and CAS occupancy update

Mitigation:

`upsertSlot` writes identity and indexes atomically from the registry's
perspective. Occupancy CAS operates on the existing slot reference. A slot
that is being replaced must have its index membership cleaned up before the
new slot's membership is added. Add an explicit `removeSlot` → `upsertSlot`
two-step in the contract test to verify no phantom index entries.

### Risk 3: WorkerDispatchAvailabilityOwner source tracking lost

Mitigation:

Source tracking (WORKER_STATE / WORKER_COMMAND / NODE_GROUP_BINDING gates) is
diagnostic and operational metadata, not occupancy truth. Keep the source map
as a side-channel for diagnostics; the slot's `dispatchEnabled` is the
admission truth. The two must be kept consistent: any source that disables
dispatch must also call `registry.setDispatchEnabled(false)`.

### Risk 4: Snapshot removal breaks stable-read guarantees during scheduling

Mitigation:

A single scheduling pass should read a consistent view. Keep a lightweight
`RegistryReadView` that captures a point-in-time copy of the slot references
(not the slot values) for a scheduling pass. This is a shallow copy and is
cheaper than the current full snapshot rebuild.

### Risk 5: Redis CAS requires Lua scripts, increasing operational complexity

Mitigation:

Define the Lua scripts as constants in `RedisWorkerRegistry` and test them
in isolation. The contract test verifies behavior, not implementation. Accept
the operational complexity in exchange for correct atomic occupancy on Redis.
