# Worker Slot Registry Roadmap

Last updated: 2026-05-24

Status: proposed redesign. Verify current code before implementing each phase.

## Summary

This roadmap redesigns worker runtime storage around the scheduling hot path,
not around DB-style rows.

The current worker runtime still has multiple worker data copies:

```text
WorkerStorage
  workersById
  workerIdsByGroupId
  lockedWorkers

WorkerManager
  workerRegistryRows
  workerGroupsById
  adapterNodesById
  nodeGroupBindingsByKey

Derived read path
  WorkerRegistrySnapshot
  WorkerRouteBucketOwner

Dynamic runtime state
  WorkerLoadView
  WorkerDispatchAvailabilityOwner
  WorkerReachabilityView
```

That shape is workable for current JVM memory runtime, but it is not the target
runtime structure for Redis or multi-JVM scheduling. It creates row-oriented
storage, full snapshot rebuilds, duplicate identity truth, and separate load /
lock / gate truth.

The target is a `WorkerRegistry` that is:

- group-partitioned
- hash/index based
- stale-tolerant for candidate indexes
- narrow-atomic only for admission and finality-sensitive counters
- shared by memory and Redis through the same contract tests

The system does not need every instant to be strongly consistent. It needs to
avoid task confusion. Stale candidates, duplicate candidates, delayed cleanup,
and slightly stale presence evidence are acceptable if Stage-2 admission
validates the current slot before reserve and all reserve/final operations are
idempotent and per-worker atomic.

## Core Judgment

Large scheduling systems are usually not globally strongly consistent on
candidate indexes. Candidate discovery is allowed to be approximate because the
dispatch path has a second admission step.

This roadmap follows that model:

```text
Stage-1 candidate source
  lock-free / stale-tolerant / bounded sample from group/route buckets

Stage-2 admission
  validate current slot, heartbeat, gate, capacity, and route attributes
  perform per-worker atomic reserve

Dispatch/result
  TaskWorkRuntime owns work lease/finality
  WorkerRegistry owns worker occupancy counters

Cleanup
  lazy cleanup + watchdog cleanup
  correctness must not depend on immediate cleanup
```

This is not "loose correctness". It is choosing the correct consistency level
for each layer.

## Core Rules

1. WorkerGroup is the first runtime partition for worker registry data.
2. Task candidate lookup must start from resolved `workerGroupId(s)`, then
   route / adapter-node / attributes may narrow candidates.
3. Runtime worker storage must not be modeled as DB CRUD rows.
4. Durable history, analytics, and audit projections come from trace/event
   streams, not direct DB writes from worker runtime structures.
5. Candidate indexes may be briefly stale and may contain duplicate or dead
   worker ids.
6. Stage-2 admission must validate the current worker slot before reserve.
7. No global worker-registry lock is allowed on the scheduling hot path.
8. Per-worker reserve/confirm/release/final operations must be atomic.
9. Source-scoped dispatch gates remain source-scoped; a boolean
   `dispatchEnabled` is derived only.
10. Worker reachability is evidence, not candidate truth. Stale heartbeat
    evidence is rejected at Stage-2.
11. Memory and Redis implementations must share one `WorkerRegistry` contract
    and one test suite.
12. Redis 7.4 hash field TTL may be used as an optimization, but correctness
    must not require it in the first Redis slice.

## Non-Goals

1. No rewrite of `TaskWorkRuntime` queue or lease semantics.
2. No WorkerSession model.
3. No Device / AccountSlot owner.
4. No transport-owned scheduling decision.
5. No worker runtime DB CRUD backend.
6. No public worker API contract change in the first slice.
7. No global distributed lock for candidate acquisition.
8. No requirement that indexes are immediately cleaned after worker disconnect.

## Worker Identity

Recommended worker id shape:

```text
{groupId}-{randomId}
```

Examples:

```text
telegram-send-v1-8f3a92
crawler-fetch-v2-19aa07
```

This is useful because Redis runtime can infer the natural group partition from
`workerId` during diagnostics and cleanup. It must not be the only source of
truth. `WorkerMeta.groupId` remains explicit and registration must validate:

```text
workerId prefix group == WorkerMeta.groupId
```

Changing a worker's group is treated as a new worker identity. Do not do
in-place group migration for an active worker.

## WorkerSlot Shape

`WorkerSlot` is the current per-worker runtime view used by Stage-2 admission.
It must be immutable in memory and represented as group-local hashes/indexes in
Redis.

```text
WorkerSlot
  identity
    workerId
    groupId
    adapterNodeId
    adapterId
    transportHint

  metadata
    attributes
    agentVersion
    runtimeVersion
    lastHeartbeatMillis
    diagnosticStatus

  capability evidence
    declaredCapacity
    eventBindingCeiling

  occupancy
    activeLeaseCount
    reservedCount
    activeLeaseCountByTask

  gates
    disabledSources
```

Important rules:

- `WorkerSlot` must not hold a mutable `Worker`.
- `eventBindingCeiling` is report/diagnostic evidence only. It is not a
  candidate-source key.
- `dispatchEnabled = disabledSources.isEmpty()` is derived, not written as an
  independent truth.
- `lastHeartbeatMillis` may be stale. Stage-2 validates freshness before
  reserve.

## Redis Runtime Shape

The Redis target is group-partitioned, not one huge global worker table and not
one DB-row key per worker.

First Redis slice:

```text
{prefix}:worker:{groupId}:meta
  HASH workerId -> WorkerMetaPayload

{prefix}:worker:{groupId}:capacity
  HASH workerId -> declaredCapacity

{prefix}:worker:{groupId}:active
  HASH workerId -> activeLeaseCount

{prefix}:worker:{groupId}:reserved
  HASH workerId -> reservedCount

{prefix}:worker:{groupId}:gates
  HASH workerId -> disabledSourceBitmask

{prefix}:worker:{groupId}:heartbeatDeadline
  ZSET workerId -> expireAtMillis

{prefix}:worker:{groupId}:route:{routeBucketKey}:{shard}
  SET/ZSET workerId

{prefix}:worker:{groupId}:node:{adapterNodeId}:route:{routeBucketKey}:{shard}
  SET/ZSET workerId

{prefix}:task:{taskId}:active-workers
  SET workerId

{prefix}:task:{taskId}:worker-active-count
  HASH workerId -> activeLeaseCountForTask
```

Redis 7.4 hash field TTL can later optimize presence or payload cleanup:

```text
{prefix}:worker:{groupId}:meta
  HEXPIRE workerId ...
```

But first-slice correctness should use:

```text
lastHeartbeatMillis in payload
heartbeatDeadline ZSET
Stage-2 freshness validation
lazy cleanup / watchdog cleanup
```

## Candidate Flow

Scheduling after convergence:

```text
Task.sharedConfig.workerGroupId(s)
  -> for each group:
       routeBucketKey = WorkerRoutingPolicy(task)
       sample bounded workerIds from group route bucket
  -> de-duplicate candidates for this scheduling pass
  -> for each workerId:
       read current slot/meta/load/gate
       reject missing slot
       reject mismatched group or adapterNode
       reject stale heartbeat
       reject disabled gate
       reject capacity unavailable
       atomic reserve permits
  -> TaskWorkRuntime.claimReady(...)
  -> confirm reservation for claimed permits
  -> release unused reserved permits
  -> dispatch
```

No correctness claim relies on the bucket being clean. A bucket may contain a
dead worker id. Stage-2 rejects it and cleanup removes it eventually.

## Consistency Model

Strong enough:

- per-worker reserve must not overbook declared capacity
- confirm/release/final must not produce negative counts
- gate source clear must not clear other gate sources
- result finality remains owned by TaskWorkRuntime / result owners
- task active worker count must converge after result/expiry

Eventually consistent:

- route bucket membership
- node/group bucket membership
- heartbeat/presence cleanup
- meta attribute propagation into candidate buckets
- route bucket cleanup after worker remove

Stale is acceptable only when it is harmless:

- stale candidate id sampled from a bucket
- stale route key membership
- stale node-group membership
- stale heartbeat deadline entry

Stale is not acceptable after Stage-2 admission. A worker that fails current
slot validation must not receive a new lease.

## WorkerRegistry Contract

```text
WorkerRegistry

  identity
    upsertSlot(WorkerMeta meta, declaredCapacity, eventBindingCeiling) -> void
    removeSlot(groupId, workerId) -> void
    slot(groupId, workerId) -> Optional<WorkerSlot>
    slotByWorkerId(workerId) -> Optional<WorkerSlot>

  candidate acquisition
    acquireCandidates(groupId, routeBucketKey, max) -> List<workerId>
    acquireCandidates(groupId, adapterNodeId, routeBucketKey, max) -> List<workerId>

  admission
    tryReserve(groupId, workerId, taskId, permits, nowMillis) -> ReserveResult
    confirmReservation(groupId, workerId, taskId, permits) -> boolean
    releaseReservation(groupId, workerId, taskId, permits) -> void
    recordWorkFinal(groupId, workerId, taskId, permits) -> void

  gates
    disableDispatch(groupId, workerId, source) -> boolean
    clearDispatchDisable(groupId, workerId, source) -> boolean

  task occupancy
    activeWorkerIdsByTask(taskId) -> Set<workerId>
    activeWorkerCountForTask(taskId) -> int
    activeLeaseCountByTaskWorker(taskId, workerId) -> int

  cleanup
    markCandidateStale(groupId, workerId, reason) -> void
    cleanupExpiredHeartbeats(nowMillis, limit) -> CleanupSummary
    cleanupStaleBucketMembers(groupId, limit) -> CleanupSummary
```

`slotByWorkerId(workerId)` may derive `groupId` from the worker id prefix, but
must validate it against stored metadata.

## Atomicity Boundary

No global lock:

```text
do not lock all workers
do not lock all groups
do not lock all route buckets
do not lock WorkerManager for scheduling reads
```

Allowed narrow atomic boundaries:

```text
single worker slot admission
single task-worker occupancy counter
single result/finality owner mutation
```

Memory implementation:

```text
grouped maps + AtomicReference<WorkerSlot>
CAS retry per worker
stale bucket validation on read
```

Redis implementation:

```text
Lua / atomic commands for tryReserve and confirm/release/final
bounded candidate sample from group-local bucket
lazy cleanup when stale members are observed
watchdog cleanup from heartbeatDeadline ZSET
```

## Phase Plan

### WSR-0: Current Runtime Inventory

Goal: document the actual current worker runtime data holders before changing
behavior.

Scope:

1. Inventory `WorkerStorage`, `WorkerManager.workerRegistryRows`,
   `WorkerRegistrySnapshot`, `WorkerRouteBucketOwner`, `WorkerLoadView`,
   `WorkerDispatchAvailabilityOwner`, and `WorkerReachabilityView`.
2. List every place that still treats `WorkerStorage` as scheduling truth.
3. List every place that still depends on `tryLockWorker`, `unlockWorker`, or
   `isLocked`.
4. Add architecture notes that runtime worker data is not DB CRUD data.

Acceptance:

1. No behavior change.
2. Current truth split is visible in one doc section.
3. Risky migration points are named before implementation.

### WSR-1: Contract First

Goal: define `WorkerMeta`, `WorkerSlot`, and `WorkerRegistry` contract with
stale-tolerant semantics.

Scope:

1. Define immutable `WorkerMeta`.
2. Define immutable `WorkerSlot`.
3. Define `WorkerRegistry` interface.
4. Define `ReserveResult` with explicit outcomes:
   - accepted
   - missing slot
   - stale heartbeat
   - dispatch disabled
   - capacity unavailable
   - group mismatch
   - adapter-node mismatch
5. Create abstract `WorkerRegistryContractTest`.

Contract tests:

- bounded candidate acquisition can return stale ids, but Stage-2 reserve
  rejects missing/current-invalid slot
- duplicate candidates are harmless after de-duplication
- concurrent reserve cannot exceed capacity
- confirm moves reserved to active
- release is idempotent
- final is idempotent
- clearing one gate source does not clear another
- stale heartbeat blocks reserve
- active worker count by task converges to zero after final
- upsert changes future indexes but preserves active occupancy
- lowering capacity below current occupancy blocks new reserve but preserves
  current active/reserved counts

Acceptance:

1. Types and abstract contract tests compile.
2. No production behavior change.
3. Contract explicitly allows stale candidate indexes.

### WSR-2: In-Memory Group-Partitioned Registry

Goal: implement the contract in memory using the same logical structure as
Redis.

Scope:

1. Implement grouped structures:
   - `slotsByGroupId`
   - `routeBucketsByGroupId`
   - `nodeRouteBucketsByGroupId`
   - `heartbeatDeadlinesByGroupId`
   - task active worker indexes
2. Use per-worker `AtomicReference<WorkerSlot>` or equivalent CAS boundary.
3. Candidate bucket reads return bounded samples.
4. Candidate materialization validates current slot.
5. Stale members are removed lazily and through bounded cleanup APIs.

Acceptance:

1. `InMemoryWorkerRegistryTest` extends and passes
   `WorkerRegistryContractTest`.
2. No global lock is used for scheduling reads.
3. Concurrent reserve on different workers does not block on a shared registry
   monitor.
4. Stale bucket entries are correctness-neutral.

### WSR-3: WorkerManager Identity / Index Convergence

Goal: make `WorkerManager` use `WorkerRegistry` for worker identity and
candidate indexes while leaving existing occupancy owners in place.

Scope:

1. Replace `workerRegistryRows` with registry-backed slot upsert/remove.
2. Derive `WorkerRegistrySnapshot` from registry state only if a snapshot is
   still required for stable read views.
3. Move `WorkerRouteBucketOwner` membership to registry-owned buckets. It may
   remain as a selection policy wrapper.
4. Keep `WorkerLoadView` and `WorkerDispatchAvailabilityOwner` temporarily.
5. Keep `WorkerStorage` only as a compatibility bootstrap/query layer until it
   is removed in a later phase.

Acceptance:

1. Worker registration updates registry group/route/node buckets.
2. Candidate acquisition is registry-backed and bounded.
3. `WorkerManager` no longer owns a second mutable worker row map.
4. Existing scheduling tests pass.

### WSR-4: Occupancy And Gate Convergence

Goal: move capacity reservation, active counters, and dispatch disabled sources
into `WorkerRegistry`.

Scope:

1. Wire `tryReserveWorkerCapacity` to `WorkerRegistry.tryReserve`.
2. Wire confirm/release/final callbacks to registry occupancy operations.
3. Store source-scoped gates in `WorkerSlot`.
4. Remove `InMemoryWorkerLoadView` when no caller remains.
5. Keep TaskWorkRuntime as work lease/finality owner.

Acceptance:

1. No over-reservation under concurrent scheduling.
2. No negative active/reserved counters.
3. Active worker count by task is correct after result and expiry.
4. Node-group drain clear does not clear worker-state or command drain gates.
5. Existing lifecycle, retry, fault, and trace-observed tests pass.

### WSR-5: Retire WorkerStorage Lock Model

Goal: remove worker lock as a separate occupancy truth.

Scope:

1. Remove `tryLockWorker`, `unlockWorker`, `isLocked`, and `getLockedWorkers`
   from worker storage/runtime hot path.
2. Express foreground exclusivity through permit policy, not a lock set.
3. Add architecture guard against reintroducing worker lock methods.
4. Remove `lockedWorkers` from memory storage.

Acceptance:

1. No separate `lockedWorkers` truth remains.
2. Foreground/background scheduling tests pass.
3. Architecture guard fails if lock methods return.

### WSR-6: Redis WorkerRegistry Foundation

Goal: prove Redis runtime can implement the same contract with group-partitioned
hashes/indexes.

Scope:

1. Implement Redis key prefix as configuration.
2. Implement group-local meta/capacity/active/reserved/gate hashes.
3. Implement group-local route/node route buckets with bounded sampling.
4. Implement heartbeat deadline ZSET and bounded cleanup.
5. Implement atomic reserve/confirm/release/final with Lua or Redis atomic
   primitives.
6. Keep Redis 7.4 hash field TTL optional. Do not require it for correctness.
7. `RedisWorkerRegistryTest` extends the same contract suite.

Acceptance:

1. Redis contract tests pass.
2. Missing/stale bucket members are rejected at Stage-2.
3. Over-reservation is impossible under concurrent Redis clients.
4. Prefix isolation prevents test pollution.
5. Redis implementation does not use one global worker hash.

### WSR-7: Runtime Switch And Proof

Goal: make memory and Redis runtime selectable and prove both through the same
test lanes.

Scope:

1. Add runtime config:
   - `memory`
   - `redis`
2. Run shared contract tests against memory and Redis.
3. Run scheduling integration tests against memory.
4. Run selected Redis integration/proof tests:
   - worker registers late while task is ready
   - worker heartbeat expires and stale candidate is skipped
   - worker reconnects and becomes eligible again
   - route bucket stale member cleanup
   - concurrent reserve against same worker
5. Update docs to describe runtime truth vs historical projection truth.

Acceptance:

1. Memory and Redis share test semantics.
2. Redis stale candidate proof passes.
3. Runtime switch does not alter public worker API.
4. Trace remains the path for historical projection, not direct runtime DB
   writes.

## Testing Strategy

Contract tests are the primary proof. E2E tests should prove cross-boundary
behavior, not every internal counter.

Required lanes:

- `WorkerRegistryContractTest`
- `InMemoryWorkerRegistryTest`
- `RedisWorkerRegistryTest`
- `WorkerManager` convergence tests
- route bucket stale candidate tests
- node-group drain / worker-state gate source tests
- lifecycle result/final release tests
- Redis prefix isolation tests

Avoid:

- tests that assert exact instant index cleanliness
- tests that require immediate cleanup after disconnect
- duplicate happy-path tests that only prove worker registration once
- DB-style CRUD tests for runtime registry behavior

## Risks

### Risk 1: Stale candidates cause wasted scheduling work

Mitigation: bounded sample size, per-pass de-duplication, Stage-2 rejection,
lazy cleanup, and watchdog cleanup.

### Risk 2: Redis atomic scripts become complex

Mitigation: keep scripts narrow. Only reserve/confirm/release/final require
atomicity. Bucket cleanup and candidate reads do not.

### Risk 3: Worker id prefix becomes hidden truth

Mitigation: prefix is a routing convenience. Stored `WorkerMeta.groupId` remains
explicit and must be validated.

### Risk 4: Hash field TTL creates version lock-in

Mitigation: use heartbeat ZSET + Stage-2 heartbeat validation as correctness
baseline. Redis 7.4 TTL is optional optimization.

### Risk 5: Memory and Redis diverge

Mitigation: memory implementation mirrors group-partitioned logical structure
and both implementations run the same contract tests.

## Final Target

```text
Task(workerGroupId/workerGroupIds)
  -> group-local route/node bucket bounded sample
  -> Stage-2 current slot validation
  -> per-worker atomic reserve
  -> TaskWorkRuntime claim
  -> confirm/release permits
  -> dispatch
  -> result/expiry
  -> final permit release
  -> async trace projection to history/query stores
```

The runtime registry is not a database. It is a scheduling data structure.
