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
5. Foreground exclusivity is expressed by resource-policy permit consumption,
   not by a separate lock set. A foreground dispatch may consume all effective
   permits for that worker without changing the worker's declared capacity.
6. Source-scoped dispatch gates remain source-scoped. The slot may expose an
   aggregate `dispatchEnabled` read, but `WORKER_STATE`, `WORKER_COMMAND`, and
   `NODE_GROUP_BINDING` must not overwrite each other.
7. Secondary indexes are maintained incrementally on slot writes, not rebuilt
   in full.
8. `WorkerRegistrySnapshot` becomes a thin read view over the registry, not a
   full copy of all worker data.
9. Memory and Redis implementations share the same `WorkerRegistry` contract
   and the same contract test suite.
10. `WorkerRouteBucketOwner` may continue deriving from registry state until
    WSR-5, but any bucket read must either read registry-owned indexes directly
    or validate worker membership before materializing candidates.

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
    worker            Worker          defensive immutable snapshot of metadata

  capability
    declaredCapacity  int             from registration/report; minimum 1
    eventKeys         Set<EventKey>   capability ceiling; empty = no ceiling

  occupancy (CAS-maintained)
    activeLeaseCount  int             confirmed active leases
    reservedCount     int             optimistic pre-claim reservations

  gate
    disabledSources   Set<DispatchAvailabilitySource>
    dispatchEnabled   boolean         derived: disabledSources.isEmpty()
```

`WorkerSlot` must not expose a mutable `Worker` instance as live state.
Implementations must use one of these forms:

```text
preferred: WorkerSlotMetadata immutable record copied from Worker
allowed:   defensive-copy Worker on slot write and slot read
forbidden: storing and returning the caller's mutable Worker reference
```

Derived invariants:

```text
canReserve(permits) = dispatchEnabled
                      && permits > 0
                      && activeLeaseCount + reservedCount + permits
                         <= max(1, declaredCapacity)

foregroundExclusive = resource policy consumes all available permits
```

CAS operations on `WorkerSlot`:

```text
tryReserve(workerId, permits)        -> boolean
  CAS: +permits reservedCount if canReserve(permits)

confirmReservation(workerId, permits)-> boolean
  CAS: -permits reservedCount, +permits activeLeaseCount

releaseReservation(workerId, permits)-> void
  CAS: -permits reservedCount (min 0)

recordWorkFinal(workerId, permits)   -> void
  CAS: -permits activeLeaseCount (min 0)

disableDispatch(workerId, source)    -> boolean
  CAS: add source to disabledSources

clearDispatchDisable(workerId, source)-> boolean
  CAS: remove source from disabledSources
```

Each operation is a bounded retry loop on the per-worker `AtomicReference`.
Workers are independent so contention is bounded per worker, not global.

Permit semantics:

- one permit represents one runtime work lease capacity unit
- per-worker batch claim must reserve the same number of permits that may be
  confirmed into active leases
- if a dispatch slot reserves N permits but runtime claims M work items, where
  M < N, the unused `N - M` permits must be released before transport dispatch
- foreground exclusive policy should reserve all currently available permits
  or use an explicit policy-calculated permit cost; it must not mutate
  `declaredCapacity`

## WorkerRegistry Contract

```text
WorkerRegistry

  slot access
    upsertSlot(Worker, int declaredCapacity, Set<EventKey> eventKeys) -> void
    removeSlot(workerId) -> void
    slot(workerId) -> Optional<WorkerSlot>
    slots() -> Collection<WorkerSlot>

  occupancy mutations
    tryReserve(workerId, taskId, permits) -> boolean
    confirmReservation(workerId, taskId, permits) -> boolean
    releaseReservation(workerId, taskId, permits) -> void
    recordWorkFinal(workerId, taskId, permits) -> void

  gate mutations
    disableDispatch(workerId, source) -> boolean
    clearDispatchDisable(workerId, source) -> boolean

  candidate indexes (space-for-time reads)
    workerIdsByGroupId(groupId) -> Set<String>
    workerIdsByAdapterNodeId(adapterNodeId) -> Set<String>
    workerIdsByAdapterNodeGroup(adapterNodeId, groupId) -> Set<String>
    workerIdsByGroupRoute(groupId, routeBucketKey) -> Set<String>
    workerIdsByNodeGroupRoute(adapterNodeId, groupId, routeBucketKey) -> Set<String>
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
  -> registry.tryReserve(workerId, taskId, permits) // CAS, no global lock
  -> TaskWorkRuntime.claimReady(...)
  -> registry.confirmReservation(workerId, taskId, claimedPermits) // CAS
  -> registry.releaseReservation(workerId, taskId, unusedPermits)  // CAS
  -> transport dispatch
  -> result / expiry convergence
  -> registry.recordWorkFinal(workerId, taskId, finalPermits)      // CAS
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
   - multi-permit `tryReserve` respects `declaredCapacity`
   - `confirmReservation` atomically moves reserved → active by permit count
   - `releaseReservation` is idempotent (no negative counts)
   - `recordWorkFinal` is idempotent (no negative counts)
   - `disableDispatch(source)` blocks `tryReserve` immediately
   - clearing one dispatch source does not enable a slot disabled by another
     source
   - `upsertSlot` updates identity without disturbing occupancy counts
   - `upsertSlot` defensively snapshots worker metadata or exposes immutable
     metadata
   - `removeSlot` clears all index memberships
   - route bucket index membership changes when approved routing attributes
     change
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
     for group, adapter-node, node-group, and route-bucket membership
   - same-worker identity/index writes are serialized with
     `slotsById.compute(workerId, ...)` or an equivalent per-worker mutation
     boundary
   - index reads must validate the current slot before materializing a worker,
     so stale index entries are harmless and can be lazily cleaned up
2. Implement route bucket indexes using `WorkerRoutingPolicy` on upsert:
   - `workerIdsByGroupRoute(groupId, routeBucketKey)`
   - `workerIdsByNodeGroupRoute(adapterNodeId, groupId, routeBucketKey)`
   - `routeBucketKeysByWorkerId(workerId)`
3. `InMemoryWorkerRegistryTest` extends `WorkerRegistryContractTest`.
4. Do not wire into `WorkerManager` yet.

Acceptance:

1. All `WorkerRegistryContractTest` cases pass.
2. Concurrent upsert and remove do not corrupt index membership.
3. Route bucket keys are derived on upsert, not on each read.
4. Stale index entries, if produced by a race, are rejected by slot validation
   before candidate materialization.
5. No existing test changes.

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
5. `WorkerRouteBucketOwner` either reads registry route-bucket indexes directly
   or becomes a thin policy/selection wrapper over registry-owned route
   buckets.
6. Keep `WorkerRegistrySnapshot` publication for now; derive it from
   `registry.slots()` instead of from `workerRegistryRows`.
7. Keep `WorkerStorage` for startup bootstrap/query compatibility only; it must
   not be the scheduling source.

Acceptance:

1. `WorkerManager` no longer holds `workerRegistryRows`.
2. Worker registration and deregistration update the registry indexes.
3. `WorkerRegistrySnapshot` is derived from registry state, not from a
   separate live map.
4. Route-bucket candidate acquisition is registry-backed or slot-validated.
5. Existing engine scheduling and manager tests pass.

### WSR-3: Occupancy Convergence

Goal: replace `InMemoryWorkerLoadView` with `WorkerRegistry` occupancy
operations. Remove the global `synchronized` lock.

Scope:

1. Wire `WorkerManager.tryReserveWorkerCapacity` → `registry.tryReserve(..., permits)`.
2. Wire `WorkerManager.releaseWorkerReservation` → `registry.releaseReservation(..., permits)`.
3. Wire load view confirm / work-final callbacks →
   `registry.confirmReservation(..., permits)` and
   `registry.recordWorkFinal(..., permits)`.
4. `WorkerDispatchAvailabilityOwner` gate mutations call
   `registry.disableDispatch(source)` / `registry.clearDispatchDisable(source)`.
   Gate source tracking can remain in `WorkerDispatchAvailabilityOwner`, but
   the slot's disabled source set is admission truth.
5. Remove `InMemoryWorkerLoadView` once no caller remains.

Acceptance:

1. `tryReserveCapacity` uses per-worker CAS with no global lock.
2. Concurrent reserve/release for different workers does not block each other.
3. Any disabled dispatch source blocks new reserves immediately.
4. Clearing one disabled source does not clear other disabled sources.
5. Multi-permit reserve/confirm/release counts match runtime claimed work.
6. Active lease count and reservation count are never negative.
7. Existing fault matrix chaos runners pass.

### WSR-4: Retire WorkerStorage Lock Interface And lockedWorkers

Goal: remove `lockedWorkers` and the lock methods from `WorkerStorage`.

Scope:

1. Remove `tryLockWorker`, `unlockWorker`, `isLocked`, `getLockedWorkers`
   from `WorkerStorage` interface.
2. Remove `lockedWorkers` field from `InMemoryWorkerStorage`.
3. Remove lock pass-through methods from `WorkerManager`.
4. Update `WorkerDispatchResourceReleaser` and
   `RuleBasedTaskWorkerMatchingStrategy` to use registry permits and
   source-scoped gate operations instead of `tryLockWorker`.
5. Update `EngineSchedulingCoreArchitectureGuardTest` to assert no
   `tryLockWorker` / `unlockWorker` / `isLocked` calls exist outside
   diagnostics.

Acceptance:

1. `WorkerStorage` interface has no lock methods.
2. `InMemoryWorkerStorage` has no `lockedWorkers` field.
3. Foreground exclusivity is enforced by resource-policy permit consumption,
   not by a separate lock and not by mutating declared worker capacity.
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
4. `WorkerRouteBucketOwner` no longer owns membership. It may own only
   selection policy over registry-provided bucket members.
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
   worker:{workerId}:slot                         hash identity + capacity + eventKeys
   worker:{workerId}:occ                          hash activeLeaseCount, reservedCount
   worker:{workerId}:gate                         set  disabled source names
   worker:{workerId}:routes                       set  routeBucketKey members
   group:{groupId}:workers                        set  workerId members
   node:{adapterNodeId}:workers                   set  workerId members
   node-group:{nodeId}:{groupId}:workers          set  workerId members
   group-route:{groupId}:{routeKey}:workers       set  workerId members
   node-group-route:{nodeId}:{groupId}:{routeKey}:workers set workerId members
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
4. Route-bucket acquisition is bounded in Redis and does not require reading an
   entire large group set.
5. No memory-only assumptions leak into the Redis implementation.

## Testing Plan

### Contract

- `WorkerRegistryContractTest` (abstract, shared by memory and Redis)
  - concurrent reserve respects declared capacity
  - multi-permit reserve/confirm/release count correctness
  - confirm/release idempotency
  - source-scoped gate blocks reserve immediately and clears independently
  - upsert preserves occupancy counts
  - remove clears all index memberships
  - mutable Worker input cannot mutate slot metadata or indexes after upsert

### Engine Integration

- existing `WorkerManagerTest`, `WorkerCandidateIndexTest`,
  `WorkerRouteBucketOwnerTest` must pass at each phase
- foreground scheduling cannot assign same worker concurrently
- foreground policy consumes permits without mutating declared capacity
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

CAS contention is per-worker. A worker under heavy reassignment attempts will
spin, but spins are bounded by the number of concurrent scheduling threads
targeting that worker, not by total fleet size. Permit-based foreground
exclusivity may increase contention on hot workers; profile before optimizing.

### Risk 2: Index staleness between upsert and CAS occupancy update

Mitigation:

Use a per-worker mutation boundary for slot identity/index changes. Reads from
secondary indexes must validate the current slot before materialization, so
stale entries are correctness-neutral and can be lazily cleaned up. Add an
explicit `removeSlot` → `upsertSlot` two-step and a concurrent upsert/remove
contract test to verify no phantom worker is materialized.

### Risk 3: WorkerDispatchAvailabilityOwner source tracking lost

Mitigation:

Source tracking (WORKER_STATE / WORKER_COMMAND / NODE_GROUP_BINDING gates) is
admission-relevant. Store source-scoped disabled gates in the slot, or make the
slot read from the same source set. A boolean-only `dispatchEnabled` is only a
derived projection and must not be the mutation API.

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
