# Worker Slot Registry Roadmap

Last updated: 2026-05-24

Status: in progress. WSR-0 through WSR-6 have established the JVM
`WorkerRegistry` foundation, moved production occupancy mutations to it,
retired storage-owned worker lock truth, removed `WorkerLoadView` production
wiring, renamed the `WorkerManager` exclusive-lease facade away from
lock-owned terminology, and moved the shared `WorkerRegistry` contract/value
types to `platform_infra/mass-runtime-api`. WSR-7 now has a first-slice
Redis-backed `WorkerRegistry` foundation under `mass-runtime-redis`; WSR-8
runtime switching and distributed proof remain open. Verify current code before
implementing each remaining phase.

## Summary

This roadmap redesigns worker runtime storage around the scheduling hot path,
not around DB-style rows.

This is a full convergence line for worker match scheduling and worker connect
runtime data structures. It must follow the project refactor rhythm:

```text
converge current truth
  make owners, indexes, and callers explicit without changing behavior

modify runtime structure
  introduce WorkerRegistry and move match / connect runtime reads to it

remove residue
  delete old snapshot/load/lock paths after the new owner is proven
```

Do not implement this as parallel old/new tracks. Each phase must either keep
behavior unchanged while shrinking ambiguity, or move one owner boundary with a
verifiable exit.

The current worker runtime still has multiple worker data copies:

```text
WorkerStorage
  workersById
  workerIdsByGroupId

WorkerManager
  workerRegistryRows
  workerGroupsById
  adapterNodesById
  nodeGroupBindingsByKey

Derived read path
  WorkerRegistrySnapshot
  WorkerRouteBucketOwner

Dynamic runtime state
  WorkerRegistry / WorkerSlot
  WorkerDispatchAvailabilityOwner
  WorkerReachabilityView
```

That shape is workable for current JVM memory runtime, but it is not the target
runtime structure for Redis or multi-JVM scheduling. It creates row-oriented
storage, full snapshot rebuilds, duplicate identity truth, and separate load /
lock / gate truth.

## Current Runtime Truth Inventory

Current implementation truth during this phase:

```text
Worker identity / control-plane row
  WorkerStorage
  InMemoryWorkerStorage.workersById
  InMemoryWorkerStorage.workerIdsByGroupId

Current engine registration row copy
  WorkerManager.workerRegistryRows
  protected by WorkerManager.workerRegistryLock

Current candidate read cache
  WorkerRegistrySnapshot
  WorkerCandidateIndex
  WorkerRouteBucketOwner

Current stage-2 dynamic admission state
  WorkerRegistry / WorkerSlot occupancy counters
  WorkerDispatchAvailabilityOwner
  WorkerReachabilityView

Current exclusivity truth
  WorkerRegistry / WorkerSlot.exclusiveLeaseHeld
  WorkerManager tryAcquireWorkerExclusiveLease /
    releaseWorkerExclusiveLease / hasWorkerExclusiveLease facade
```

Current caller classes to converge before replacement:

```text
candidate-source reads
  WorkerManager.findWorkerCandidates
  WorkerCandidateIndex
  WorkerSchedulingCandidateEnumerator

Stage-2 admission reads/writes
  RuleBasedTaskWorkerMatchingStrategy
  WorkerManager.tryReserveWorkerCapacity
  WorkerManager.confirmWorkerReservation
  WorkerManager.releaseWorkerReservation
  WorkerManager.recordWorkFinal

release/final convergence
  WorkerDispatchResourceReleaser
  SimpleTaskDispatchBinder
  TaskResourceReleaseListener

connect / presence evidence
  WorkerReachabilityView
  WorkerManager.updateOnlineStatus
```

Current policy seams:

```text
route key policy
  WorkerRoutingPolicy

bounded bucket selection
  WorkerRouteBucketSelectionPolicy
  RandomWorkerRouteBucketSelectionPolicy
  future WorkerCandidateSamplingPolicy should absorb this role

post-admission ranking
  WorkerCandidateRanker
  DefaultWorkerCandidateRanker

polling cleanup/backoff pacing
  PollingIdleBackoffPolicy
```

Do not extend these current residues as long-term owners. They are listed so
later phases can replace or delete them intentionally.

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
13. Compensation is a crash-recovery fallback, not the normal consistency
    mechanism. Normal request paths should avoid creating inconsistency through
    narrow atomic writes, idempotent mutation methods, and Stage-2 validation.
14. Mechanism and policy must stay separated. `WorkerRegistry` owns runtime
    facts and atomic mutation; route selection, candidate sampling, ranking,
    cleanup cadence, and admission heuristics are policy seams.

## Non-Goals

1. No rewrite of `TaskWorkRuntime` queue or lease semantics.
2. No WorkerSession model.
3. No Device / AccountSlot owner.
4. No transport-owned scheduling decision.
5. No worker runtime DB CRUD backend.
6. No public worker API contract change in the first slice.
7. No global distributed lock for candidate acquisition.
8. No requirement that indexes are immediately cleaned after worker disconnect.
9. No standalone compensation service in the normal scheduling path.
10. No hard-coded production ranking / penalty / routing strategy in
    `WorkerRegistry` or `WorkerManager`.

## Mechanism And Policy

This roadmap must keep the production model realistic without forcing mature
policy too early.

Mechanism belongs to the mainline owner:

```text
WorkerRegistry
  owns slot metadata, group-local indexes, heartbeat evidence, dispatch gates,
  active/reserved counters, tombstone state, and atomic mutation methods

TaskWorkRuntime / result owners
  own work lease, result finality, and terminal convergence
```

Policy must be pluggable and replaceable:

```text
WorkerRoutingPolicy
  task/group/attribute -> routeBucketKey

WorkerCandidateSamplingPolicy
  choose bounded worker ids from a large group/route bucket

WorkerAdmissionPolicy
  interpret slot evidence, reachability, gate, capacity, and route attributes

WorkerCleanupPolicy
  cleanup cadence, batch size, stale tolerance, and watchdog pacing

WorkerRankingPolicy
  optional ranking after bounded sampling; first slice may be random/no-op
```

First-slice policies may be intentionally simple:

- route bucket key can be the default route plus approved routing attributes
- candidate sampling can be random bounded sampling
- ranking can be no-op
- cleanup can be lazy plus bounded watchdog
- capacity policy can be declared `maxConcurrentWork` only

These simple policies are acceptable only because they sit behind explicit
seams. Do not bake random sampling, route-key construction, cleanup intervals,
or future penalty logic into the registry data structure.

`WorkerRegistry.acquireCandidates(...)` uses the registry-bound
`WorkerCandidateSamplingPolicy`. The policy is injected when the registry is
constructed, not passed on every hot-path call. This keeps the call surface
small while preventing sampling strategy from being hard-coded into the
registry data structure.

Admission policy must not become a second admission owner. It may interpret a
pre-read slot view and explain why a worker is likely eligible, but the atomic
decision is always made by `WorkerRegistry.tryReserve(...)`. `tryReserve(...)`
must re-read/re-validate the current slot at CAS/Lua time before incrementing
reserved permits.

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
- `activeLeaseCountByTask` is not an independently mutated truth. It is updated
  only by `WorkerRegistry` occupancy mutation methods, together with the
  task-level active worker index.

## Redis Runtime Shape

The Redis target is group-partitioned, not one huge global worker table and not
one DB-row key per worker.

First Redis slice target:

```text
{prefix}:worker:group
  HASH workerId -> groupId

{prefix}:worker:group:{groupId}:slots
  HASH workerId -> WorkerSlotPayload

{prefix}:worker:heartbeat:deadlines
  ZSET encoded(groupId,workerId) -> lastHeartbeatMillis + freshnessTtlMillis

{prefix}:worker:group:{groupId}:route:{routeBucketKey}:workers
  SET/ZSET workerId

{prefix}:worker:group:{groupId}:node:{adapterNodeId}:route:{routeBucketKey}:workers
  SET/ZSET workerId

{prefix}:worker:task:{taskId}:active-workers
  SET workerId

{prefix}:worker:task:{taskId}:worker-active-count
  HASH workerId -> activeLeaseCountForTask
```

The first implementation stores one immutable `WorkerSlotPayload` per worker in
the group-local slot hash. Splitting meta/capacity/active/reserved/gate into
separate hashes is an allowed later optimization, not required for the first
verified Redis registry slice.

`worker:group` is required. The recommended `{groupId}-{randomId}` worker
id prefix is only a routing convenience and diagnostic aid. It is not enough for
correct lookup because external or historical worker ids may not follow the
prefix convention.

Redis 7.4 hash field TTL can later optimize presence or payload cleanup:

```text
{prefix}:worker:group:{groupId}:slots
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

Reachability is independent admission evidence. `WorkerReachabilityView` or its
registry equivalent should be read during Stage-2 together with heartbeat
freshness. It must not be folded into `disabledSources`; dispatch gates are
operator/runtime controls such as worker state, worker command, and node-group
drain.

Do not create two liveness truths:

```text
lastHeartbeatMillis
  freshness evidence from worker heartbeat/report

heartbeatDeadline
  derived Redis cleanup/admission deadline, not a second liveness truth

WorkerReachabilityView
  transport presence evidence
```

Both may be consulted during Stage-2 admission, but neither is a candidate
source. Candidate discovery still starts from worker group / route buckets and
current slot validation rejects stale or unreachable workers.

## WorkerRegistry Contract

```text
WorkerRegistry

  identity
    upsertSlot(WorkerMeta meta, declaredCapacity, eventBindingCeiling) -> void
    markSlotRemoving(groupId, workerId, reason) -> boolean
    cleanupRemovedSlots(groupId, limit) -> CleanupSummary
    slot(groupId, workerId) -> Optional<WorkerSlot>
    slotByWorkerId(workerId) -> Optional<WorkerSlot>

  candidate acquisition
    acquireCandidates(groupId, routeBucketKey, max) -> List<workerId>
    acquireCandidates(groupId, adapterNodeId, routeBucketKey, max) -> List<workerId>

  admission
    tryReserve(groupId, workerId, taskId, permits, nowMillis) -> ReserveResult
    confirmReservation(groupId, workerId, taskId, permits) -> boolean
    releaseReservation(groupId, workerId, taskId, permits) -> void
    recordWorkClaimed(groupId, workerId, taskId, permits) -> void
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
must first consult the explicit `workerId -> groupId` reverse index when one is
available and must validate the resolved group against stored metadata.

Occupancy mutation ownership:

```text
tryReserve
  increments reservedCount only
  carries taskId for trace / cleanup correlation only
  does not guarantee task-worker idempotency

confirmReservation
  decrements reservedCount
  increments activeLeaseCount
  increments task active worker indexes
  returns false for a removing slot

releaseReservation
  decrements reservedCount only
  used when reserved capacity is not dispatched or not claimed

recordWorkClaimed
  increments activeLeaseCount
  increments task active worker indexes
  used only when runtime work was claimed without a confirmed reservation
  must not be the normal reservation path

recordWorkFinal
  decrements activeLeaseCount
  decrements task active worker indexes
  used when result / expiry / terminal convergence closes active work
```

`WorkerRegistry` mutation methods are the only writers for worker active /
reserved counters and task active worker indexes. Redis may store worker-level
and task-level counters in separate keys. First Redis slice treats task-level
active worker indexes as rebuildable visibility indexes; admission must not
depend on them. Worker-level active/reserved counters are the occupancy truth
and must be updated atomically by reserve/confirm/release/final.

Canonical occupancy split:

```text
WorkerSlot.activeLeaseCount / reservedCount
  per-worker admission truth

WorkerSlot.activeLeaseCountByTask
  worker-local canonical slice for task visibility

{prefix}:task:{taskId}:active-workers
{prefix}:task:{taskId}:worker-active-count
  reverse lookup / diagnostics projection
  rebuildable from worker slots
  not admission truth
```

`taskId` on `tryReserve(...)` is not a uniqueness key. The registry does not
prevent multiple reservations for the same task-worker pair by task id alone.
Scheduling must de-duplicate candidates within a pass before reserve. If future
dispatch needs idempotent reserve retry, add an explicit `reservationId` rather
than overloading `taskId`.

Slot removal is tombstone-first:

```text
markSlotRemoving
  removes candidate bucket membership
  disables new reserve
  keeps active/reserved counters visible

cleanupRemovedSlots
  physically removes slot only when activeLeaseCount + reservedCount == 0
```

Do not physically delete a slot with active or reserved occupancy. Active work
must converge through result / expiry / terminal finality first.

`confirmReservation(...)` against a removing slot returns false. The caller must
follow the same foreground cleanup path and call idempotent
`releaseReservation(...)`. A removing slot may still be visible so existing
reserved/active counters can drain, but it must not accept new reserve or
promote reserved work to active.

`confirmReservation(...) == false` means the reservation was not confirmed. The
caller should immediately call idempotent `releaseReservation(...)` from the
same foreground path when it knows a reserve was not claimed or dispatched. This
is local cleanup, not a separate compensation service.

Background compensation exists only for abnormal interruption:

```text
process crash after reserve
server restart before release/final
Redis/client timeout after an ambiguous write
transport disconnect during dispatch handoff
```

The background path must be bounded and best-effort. Correctness still depends
on Stage-2 validation and idempotent reserve/release/final methods, not on a
large reconciliation service running perfectly.

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

Foreground mutation should leave as little work as possible for cleanup. Cleanup
is for residual state after crash / restart / ambiguous network failure, not for
routine scheduling progress. The cleanup executor can be an existing runtime
maintenance/watchdog loop; it should not become a new always-required service
that owns scheduling correctness.

## Phase Plan

### WSR-0: Current Runtime Inventory

Goal: document the actual current worker runtime data holders before changing
behavior.

Scope:

1. Inventory `WorkerStorage`, `WorkerManager.workerRegistryRows`,
   `WorkerRegistrySnapshot`, `WorkerRouteBucketOwner`, historical `WorkerLoadView`,
   `WorkerDispatchAvailabilityOwner`, and `WorkerReachabilityView`.
2. List every place that still treats `WorkerStorage` as scheduling truth.
3. List every place that still depends on `tryLockWorker`, `unlockWorker`, or
   `isLocked`.
4. Classify `WorkerReachabilityView` as independent admission evidence, not a
   dispatch gate source.
5. Add architecture notes that runtime worker data is not DB CRUD data.

Acceptance:

1. No behavior change.
2. Current truth split is visible in one doc section.
3. Risky migration points are named before implementation.

### WSR-1: Current Caller Convergence

Goal: converge naming and caller boundaries before introducing the new runtime
structure.

Scope:

1. Classify current worker match scheduling callers:
   - candidate-source reads
   - Stage-2 admission reads
   - reserve/confirm/release/final writes
   - connect runtime / reachability writes
   - diagnostics/read-cache reads
2. Mark `WorkerRegistrySnapshot` as current read cache only, not the target
   admission owner.
3. Mark `WorkerRouteBucketOwner` as current bucket owner that must either be
   retired or reduced to selection-only after registry migration.
4. Mark historical `WorkerLoadView` and `WorkerStorage.lockedWorkers` as
   occupancy/exclusivity residues that must not remain live production wiring
   after registry occupancy convergence.
5. Classify current strategy-like code as mechanism or policy:
   - route key construction
   - route bucket sampling
   - candidate ranking / ordering
   - capacity admission
   - cleanup pacing
6. Add source guards or explicit TODO guards for:
   - no new hot-path caller of `WorkerStorage.getAllWorkers()`
   - no new scheduling read through event/project indexes
   - no new worker lock caller outside the existing cleanup path
   - no new hard-coded routing/ranking/cleanup strategy inside registry owners

Acceptance:

1. No behavior change.
2. New tests do not add coverage against legacy worker-storage CRUD as runtime
   truth.
3. Current match / connect / occupancy callers are listed before replacement.
4. Residue owners are named so later phases delete them instead of extending
   them.
5. Strategy-like code has an explicit owner or policy seam before registry
   implementation begins.

### WSR-2: Contract First

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
5. Define policy seams without implementing mature strategy:
   - `WorkerRoutingPolicy`
   - `WorkerCandidateSamplingPolicy`, injected into `WorkerRegistry` at
     construction time and used by `acquireCandidates(...)`
   - `WorkerAdmissionPolicy`
   - `WorkerCleanupPolicy`
   - optional `WorkerRankingPolicy`
6. Create abstract `WorkerRegistryContractTest`.

Contract tests:

- bounded candidate acquisition can return stale ids, but Stage-2 reserve
  rejects missing/current-invalid slot
- duplicate candidates are harmless after de-duplication
- concurrent reserve cannot exceed capacity
- `tryReserve(taskId)` does not prevent duplicate task-worker reservations by
  itself; pass-level de-duplication remains the caller responsibility
- confirm moves reserved to active
- confirm returns false when the slot disappears between reserve and confirm,
  and the caller foreground cleanup path can safely call `releaseReservation`
- release decrements reserved count only and is idempotent
- final decrements active count only and is idempotent
- confirm/final update worker-level counters and task-level active worker
  indexes through the same registry mutation owner
- clearing one gate source does not clear another
- stale heartbeat blocks reserve
- active worker count by task converges to zero after final
- upsert changes future indexes but preserves active occupancy
- lowering capacity below current occupancy blocks new reserve but preserves
  current active/reserved counts
- mark-removing blocks new reserve but preserves active/reserved occupancy
- physical cleanup removes a removed slot only after active/reserved reaches zero

Acceptance:

1. Types and abstract contract tests compile.
2. No production behavior change.
3. Contract explicitly allows stale candidate indexes.
4. Registry contract contains no hard-coded route/ranking/cleanup strategy.

Implementation status:

- completed for the current JVM slice; the shared `WorkerRegistry` contract,
  slot/meta/value types, sampling/admission/cleanup policy seams, and abstract
  `WorkerRegistryContractTest` now live in `platform_infra/mass-runtime-api`
  so Redis can implement the same contract without depending on engine main
  sources.

### WSR-3: In-Memory Group-Partitioned Registry

Goal: implement the contract in memory using the same logical structure as
Redis.

Scope:

1. Implement grouped structures:
   - `slotsByGroupId`
   - `workerIdToGroupId`
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
5. `slotByWorkerId` works for non-prefix worker ids through `workerIdToGroupId`
   and validates stored metadata.

Implementation status:

- completed for the current JVM slice; `InMemoryWorkerRegistry` remains the
  engine-owned memory implementation while its contract is shared from
  `mass-runtime-api`.

### WSR-4: WorkerManager Identity / Index Convergence

Goal: make `WorkerManager` use `WorkerRegistry` for worker identity and
candidate indexes while leaving existing occupancy owners in place.

Scope:

1. Replace `workerRegistryRows` with registry-backed slot upsert/remove.
2. Migrate scheduling candidate-source callers away from
   `WorkerRegistrySnapshot`:
   - `WorkerCandidateIndex`
   - `WorkerRouteBucketOwner`
   - `WorkerSchedulingCandidateEnumerator`
3. Retain `WorkerRegistrySnapshot` only if it is still needed as a diagnostic /
   stable read cache. It must not be admission truth, candidate-source truth, or
   a full-scan rebuild of worker identity.
4. Move `WorkerRouteBucketOwner` membership to registry-owned buckets. After
   migration it must not own membership. Either retire it or rename it to a
   selection-only role such as `WorkerRouteBucketSelectionPolicy`.
5. Keep `WorkerLoadView` as the only admission truth in this phase. The
   registry synchronizes identity and bucket membership only; reservation and
   active counters must not be used by scheduling yet.
6. Keep `WorkerDispatchAvailabilityOwner` temporarily as the dispatch gate
   mutation owner.
7. Keep `WorkerStorage` only as a compatibility bootstrap/query layer until it
   is removed in a later phase.
8. Evaluate and remove GFS-superseded indexes from `WorkerRegistrySnapshot`:
   `groupIdsByEventKey` and `groupIdsByProjectCode`. With group-selector-first
   scheduling, task candidate lookup starts from `workerGroupId(s)` in
   `TaskSharedConfig`. Remove these indexes when no hot-path caller depends on
   event-code-to-group resolution.

Acceptance:

1. Worker registration updates registry group/route/node buckets.
2. Candidate acquisition is registry-backed and bounded.
3. `WorkerManager` no longer owns a second mutable worker row map.
4. No code path performs dual reserve/write against both `WorkerLoadView` and
   `WorkerRegistry`.
5. Scheduling/admission candidate source no longer depends on
   `WorkerRegistrySnapshot`.
6. If retained, `WorkerRegistrySnapshot` is documented and tested as
   diagnostics/read-cache only.
7. `groupIdsByEventKey` and `groupIdsByProjectCode` are either removed or
   confirmed as non-hot-path indexes with a documented caller inventory.
8. No scheduling read path enters `workerRegistryLock`.
9. Existing scheduling tests pass.

### WSR-5: Occupancy And Gate Convergence

Goal: move capacity reservation, active counters, and dispatch disabled sources
into `WorkerRegistry`.

Scope:

1. Wire `tryReserveWorkerCapacity` to `WorkerRegistry.tryReserve`.
2. Wire `confirmWorkerReservation`, `releaseWorkerReservation`, and
   `recordWorkClaimed` / `recordWorkFinal` to registry occupancy operations.
3. Migrate release/final callers that currently reach `InMemoryWorkerLoadView`
   through `WorkerManager`, including:
   - `WorkerDispatchResourceReleaser.releaseReservations`
   - `WorkerDispatchResourceReleaser.releaseReservationsAndLocks`
   - `WorkerDispatchResourceReleaser.releaseReservationAndLock`
   - `SimpleTaskDispatchBinder` confirm / fallback-claimed / final paths
   - `TaskResourceReleaseListener` result / expiry / terminal paths
4. Store source-scoped gates in `WorkerSlot`.
5. Remove `InMemoryWorkerLoadView` when no caller remains.
6. Keep TaskWorkRuntime as work lease/finality owner.

Acceptance:

1. No over-reservation under concurrent scheduling.
2. No negative active/reserved counters.
3. Active worker count by task is correct after result and expiry.
4. Node-group drain clear does not clear worker-state or command drain gates.
5. No production caller writes occupancy through `InMemoryWorkerLoadView`.
6. Source guard blocks `InMemoryWorkerLoadView` from being used as production
   occupancy truth after this phase.
7. Existing lifecycle, retry, fault, and trace-observed tests pass.

### WSR-6: Retire WorkerStorage Lock Model

Goal: remove worker lock as a separate occupancy truth.

Scope:

1. Remove `tryLockWorker`, `unlockWorker`, `isLocked`, and `getLockedWorkers`
   from `WorkerStorage`.
2. Keep `WorkerManager` exclusive-lease methods as the current scheduler
   facade; internally they must delegate to `WorkerRegistry` exclusive lease
   state on `WorkerSlot`.
3. Treat the `WorkerSlot` exclusive lease as a foreground execution-lane
   mechanism, not a second storage lock set.
4. Keep the later option to collapse foreground exclusivity into permit policy,
   but do not mix that policy redesign into this owner-convergence slice.
5. Route diagnostics through `WorkerManager`, not `WorkerStorage`.
6. Add architecture guard against reintroducing storage-owned worker lock
   methods or `lockedWorkers`.
7. Remove `lockedWorkers` from memory storage.

Out of scope:

1. Change foreground/background scheduling policy.

Acceptance:

1. No separate `WorkerStorage.lockedWorkers` truth remains.
2. `WorkerStorage` no longer exposes worker lock methods.
3. `WorkerManager` exclusive-lease facade reads/writes `WorkerRegistry`
   exclusive lease.
4. Add architecture guard against reintroducing storage lock methods.
5. Foreground/background scheduling tests pass.
6. Architecture guard fails if storage lock methods or `lockedWorkers` return.

### WSR-7: Redis WorkerRegistry Foundation

Goal: prove Redis runtime can implement the same contract with group-partitioned
hashes/indexes.

Prerequisite status:

- completed: Redis can now depend on `mass-runtime-api` for `WorkerRegistry`,
  `WorkerSlot`, `WorkerMeta`, reserve outcomes, dispatch-gate sources, and the
  shared contract test package instead of depending on `xa-mass-engine`.
- completed first slice: `RedisWorkerRegistry` now implements the shared
  contract with group-local slot hashes, worker-id-to-group lookup,
  group/node route buckets, heartbeat deadline index, task occupancy indexes,
  and Redis `WATCH` / `MULTI` / `EXEC` over the group-local slot hash.

Scope:

1. Implement Redis key prefix as configuration.
2. Implement group-local worker slot payload hashes.
3. Implement group-local route/node route buckets with bounded sampling.
4. Implement heartbeat deadline ZSET and bounded cleanup.
5. Implement atomic reserve/confirm/release/final with Lua or Redis atomic
   primitives.
   - Current first slice uses Redis `WATCH` on the group-local slot hash, so it
     proves correctness but has group-key conflict granularity.
   - If same-group write contention becomes material, the next refinement is
     Lua mutation over the specific hash field, not a separate worker-row key
     design.
6. Keep Redis 7.4 hash field TTL optional. Do not require it for correctness.
7. `RedisWorkerRegistryTest` extends the same contract suite.

Acceptance:

1. Redis contract tests pass.
2. Missing/stale bucket members are rejected at Stage-2.
3. Over-reservation is impossible under concurrent Redis clients.
4. Prefix isolation prevents test pollution.
5. Redis implementation does not use one global worker hash.

Current verification:

- `RedisWorkerRegistryTest` passes the shared `WorkerRegistryContractTest`.
- Redis prefix isolation and group-partitioned hash shape are covered by module
  tests.
- Concurrent reserve across independent Redis clients is covered and verifies
  capacity is not exceeded.

### WSR-8: Runtime Switch And Proof

Goal: make memory and Redis runtime selectable and prove both through the same
test lanes.

Current status:

- started: SDK/starter engine assembly can now accept an injected
  `WorkerRegistry`, and server `mass.runtime.mode=redis` wires
  `RedisWorkerRegistry` beside Redis task work/result runtime.
- completed at registry level: Redis stale heartbeat reject, stale route bucket
  cleanup, reconnect heartbeat refresh, prefix isolation, and concurrent reserve
  are covered by `RedisWorkerRegistryTest`.
- completed at server E2E level: ready-task late-worker registration on Redis
  runtime is covered by
  `TaskApiDelayedWorkerAvailabilityRedisRuntimeIntegrationTest`.

Scope:

1. Add runtime config:
   - `memory`
   - `redis`
   - Current server switch reuses `mass.runtime.mode`; `redis` wires task
     work/result runtime plus worker registry runtime.
2. Run shared contract tests against memory and Redis.
3. Run scheduling integration tests against memory.
4. Run selected Redis integration/proof tests:
   - worker registers late while task is ready: covered at server E2E level
   - worker heartbeat expires and stale candidate is skipped: covered at
     registry level
   - worker reconnects and becomes eligible again: covered at registry level
   - route bucket stale member cleanup: covered at registry level
   - concurrent reserve against same worker: covered at registry level
5. Update docs to describe runtime truth vs historical projection truth.

Acceptance:

1. Memory and Redis share test semantics.
2. Redis stale candidate proof passes.
3. Runtime switch does not alter public worker API.
4. Trace remains the path for historical projection, not direct runtime DB
   writes.

### WSR-9: Residue Removal

Goal: delete old paths once the new registry is proven.

Current status:

- completed for route membership: production `WorkerManager` no longer owns or
  publishes a route bucket membership copy, `WorkerCandidateIndex` acquires
  candidates from `WorkerRegistry`, and the snapshot-backed
  `WorkerRouteBucketOwner` residue has been removed.

Scope:

1. Remove or demote `WorkerRegistrySnapshot` to diagnostics-only if still
   required.
2. Remove `WorkerRouteBucketOwner` membership ownership; keep only selection
   policy if it still has a role.
3. Remove `WorkerLoadView` production wiring.
4. Rename or remove remaining lock-named diagnostics and trace terminology
   after confirming they are not part of an external operator contract.
5. Remove tests that only prove old CRUD/lock/snapshot behavior and are covered
   by registry contract or proof tests.
6. Update architecture guards so old paths cannot be reintroduced.

Acceptance:

1. No old and new worker occupancy truth run in parallel.
2. No old and new route bucket membership truth run in parallel.
3. No production scheduling path depends on worker-storage row scans.
4. Worker runtime docs describe only the converged registry owner model.

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
- source guards for worker hot-path lock/ownership:
  - scheduling reads do not enter `workerRegistryLock`
  - production occupancy no longer writes through `InMemoryWorkerLoadView`
  - route bucket membership is not owned outside `WorkerRegistry`

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

### Risk 3: Compensation service becomes a second runtime owner

Mitigation: do not add a broad reconciliation owner in the main path. Keep
foreground cleanup local and idempotent; keep background cleanup bounded to
crash/restart/ambiguous-write residue.

### Risk 4: Worker id prefix becomes hidden truth

Mitigation: prefix is a routing convenience. Stored `WorkerMeta.groupId` remains
explicit and must be validated.

### Risk 5: Hash field TTL creates version lock-in

Mitigation: use heartbeat ZSET + Stage-2 heartbeat validation as correctness
baseline. Redis 7.4 TTL is optional optimization.

### Risk 6: Memory and Redis diverge

Mitigation: memory implementation mirrors group-partitioned logical structure
and both implementations run the same contract tests.

## Follow-Ups

These are useful but not required to prove the worker registry mainline:

- Evaluate migrating `WorkerCapabilityAuthority` from `synchronized +
  LinkedHashMap` to `ConcurrentHashMap` after registry-backed candidate source
  is stable.
- Evaluate splitting `WorkerMatchContext` into per-pass task context and
  per-candidate extension after occupancy convergence.

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
