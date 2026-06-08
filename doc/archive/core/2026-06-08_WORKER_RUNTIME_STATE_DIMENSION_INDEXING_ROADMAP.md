# Worker Runtime State Dimension Indexing Roadmap

Status: current slice complete / mainline unblocked; archived on 2026-06-08
with residual readiness and physical-split work moved to
[WORKER_RUNTIME_STATE_READINESS_AND_PHYSICAL_SPLIT_ROADMAP.md](../../../roadmap/WORKER_RUNTIME_STATE_READINESS_AND_PHYSICAL_SPLIT_ROADMAP.md).

Current implementation progress:

- WRSI-0 inventory exists in
  [2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INVENTORY.md](2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INVENTORY.md).
- WRSI-1 dimension value contracts exist for reachability, readiness, and
  derived occupancy diagnostics. `UNKNOWN` reachability is retained as a
  non-reachable observation gap.
- WRSI-2A / WRSI-3 heartbeat split is implemented for Redis worker registry:
  heartbeat deadlines are group-local under
  `worker:group:{groupId}:heartbeat:0`, and stale cleanup uses the bounded
  group set instead of a global encoded heartbeat member.
- WRSI-2B candidate-bucket owner/keyspace convergence is implemented:
  `WorkerCandidateBucketPolicy` is the shared task-request, worker-membership,
  and source-guard policy protocol; Redis uses `:bucket:` physical keys.
- WRSI-2C is closed with the conservative single-writer decision:
  `group:{groupId}:slots` remains the canonical Redis worker aggregate for
  this roadmap. Writable `worker:meta:{workerId}` and
  `worker:occupancy:{workerId}` keys are named residue, not current mainline
  work, because moving them to canonical truth requires a full Redis worker
  mutation rewrite rather than a safe incremental slice.
- WRSI-4 and WRSI-5 are limited to logical dimension contracts, diagnostics,
  and runtime outcome proof over the existing slot aggregate. They must not add
  physical split keys unless a successor roadmap first replaces the slot
  mutation boundary.
- WRSI residual work that is not complete in this archive includes state-report
  readiness outcome proof, `DRAINING` scheduling-view alignment, target-only
  readiness values, occupancy diagnostic/admission wording, and any future
  physical split of `worker:meta`, `worker:occupancy`, or `available` keys.

## Purpose

Worker runtime state must not collapse independent scheduling facts into one
status value such as:

```text
worker.status = ONLINE / READY / BUSY / OFFLINE
```

Those words mix different owners:

- transport reachability,
- execution readiness,
- work-specific eligibility,
- occupancy and capacity.

This roadmap splits worker runtime state into explicit dimensions and aligns
the Redis worker registry keyspace with those dimensions.

Target scheduling predicate:

```text
task-side admission
  -> resolved worker universe
  -> worker reachability
  -> worker readiness / eligibility
  -> worker occupancy / availability
  -> reserve / lock / bind
```

Equivalent decision shape:

```text
taskAdmitted
  && inResolvedWorkerUniverse
  && reachable
  && ready
  && eligibleFor(dispatchIntent, eventCode)
  && available
```

## Current Baseline

Current owner facts:

- `ResolvedWorkerSchedulingPolicy` owns static worker-universe inputs and does
  not own live runtime evidence.
- `WorkerTaskSelector` is the worker-runtime candidate input derived from
  `ResolvedWorkerSchedulingPolicy`.
- `xa-mass-worker-runtime` owns higher-level worker lifecycle, declaration,
  evidence, candidate source, admission, warm hints, and command lifecycle.
- `mass-runtime-api` owns the low-level `WorkerRegistry` / `WorkerSlot`
  contract.
- `mass-runtime-redis` owns the Redis worker registry implementation and
  keyspace baseline.

Current Redis worker registry implementation uses:

- group-partitioned slot hashes,
- group and node candidate buckets,
- heartbeat deadline index,
- exclusive lease index,
- task-worker active count indexes,
- `WATCH` / `MULTI` / `EXEC` over the group-local slot hash for reservation.

This roadmap does not claim that metadata/occupancy split keys already exist.
For the current implementation, the group-local slot hash remains canonical for
worker metadata, readiness/dispatch-gate inputs, and occupancy counters.

## External Call Surface vs. Internal Storage

Worker selection callers should express scheduling intent, not Redis key layout.

Target layering:

```text
ResolvedWorkerSchedulingPolicy
  -> WorkerTaskSelector
  -> WorkerCandidateRuntime / WorkerCandidateIndex
  -> WorkerRegistry
  -> Redis candidate indexes
```

The external engine-facing surface should remain the resolved worker policy,
candidate selector shape, and worker-runtime candidate source: worker groups,
optional target worker, optional adapter node, and logical candidate
constraints. `WorkerRegistry` is a low-level worker-runtime contract, not an
engine-facing scheduling surface. Redis key names, physical bucket names, shard
layout, and cleanup membership keys are implementation details of the runtime
registry.

Implemented boundary:

- `WorkerRegistry#acquireCandidates(...)` exposes `candidateBucketKey`.
- `WorkerTaskSelector` exposes `candidateBucketKeys`.
- `RedisWorkerRegistryKeyspace` names candidate buckets as
  `:bucket:{candidateBucketKey}:workers`.
- task-side bucket requests, worker-side bucket membership, and source-guard
  validation use the shared `WorkerCandidateBucketPolicy` protocol and the
  platform-approved `WorkerCandidateBucketPolicies` implementation.

Route attributes remain one possible strategy input for candidate-bucket
derivation. They are not Redis keyspace vocabulary and not public worker policy
configuration.

This roadmap does not add a public SDK/server bucket-configuration surface.
Bucket strategy is a runtime indexing concern until a later policy-product
decision proves caller, cost, owner, and runtime consumer.

## Target State Model

### Reachability

Question:

```text
Can the platform currently contact this worker or its transport node?
```

Examples:

- heartbeat is fresh,
- polling session active,
- WebSocket connected,
- adapter node reachable,
- route owner still valid.

Recommended logical values:

```text
reachabilityState = ONLINE / STALE / OFFLINE / UNKNOWN
```

`UNKNOWN` is retained as an observation gap, not a reachable state. Runtime
scheduling may only treat `ONLINE` as reachable. Tests must prove `UNKNOWN` is
rejected the same way as non-reachable evidence for dispatch.

### Readiness / Schedulability

Question:

```text
Can this worker participate in dispatch in principle?
```

Examples:

- environment initialized,
- worker version compatible,
- account/session ready,
- dependency health acceptable,
- capability report loaded,
- not draining,
- not in maintenance,
- dispatch not disabled by command/control state.

Recommended logical values:

```text
readinessState =
  READY
  DRAINING
  INIT_REQUIRED
  VERSION_MISMATCH
  ACCOUNT_UNAVAILABLE
  HEALTH_UNAVAILABLE
  MAINTENANCE
```

`eligibleFor(...)` is separate from readiness. It means the worker can run this
specific work kind inside the already resolved worker universe. It may use
WorkerGroup capability and `eventCode` handler identity; it must not turn item
payload into worker-selection policy.

### Occupancy / Availability

Question:

```text
Does this worker currently have resource space for this assignment?
```

Examples:

- declared capacity,
- reserved count,
- active lease count,
- active count by task,
- exclusive worker lock,
- occupied-until / cooldown,
- next available time.

Recommended derived values:

```text
occupancyState = FREE / RESERVED / OCCUPIED / CAPACITY_FULL
```

The canonical truth should remain the underlying counts, locks, and timestamps.
`occupancyState` is a diagnostic or query classification unless a later owner
decision proves it should be stored.

## Current Physical Storage Decision

WRSI-2C closes with this implementation decision:

```text
group:{groupId}:slots remains canonical.
worker:meta:{workerId} is deferred residue.
worker:occupancy:{workerId} is deferred residue.
```

Reason:

- Redis worker reservation, reservation confirmation, release, claimed/final
  work accounting, exclusive lease, dispatch-disable, and removing-slot updates
  all mutate an encoded `WorkerSlot` inside one `WATCH` / `MULTI` / `EXEC`
  boundary over `group:{groupId}:slots`.
- Adding writable `worker:meta` or `worker:occupancy` beside the slot payload
  would create two writable Redis truth tracks for the same worker fact.
- Replacing `slots` with split canonical records requires redesigning the Redis
  worker mutation boundary around multiple watched keys or Lua scripts. That is
  a successor storage rewrite, not a safe continuation of this roadmap.

This decision does not weaken the logical model. It means WRSI-4 and WRSI-5
prove dimensions through the current canonical aggregate and record the
physical split as named residue.

Named residue:

| Residue | Current owner | Removal condition |
| --- | --- | --- |
| `worker:meta:{workerId}` target key | none; target only | successor Redis worker storage rewrite makes metadata canonical and removes/demotes slot metadata in same slice |
| `worker:occupancy:{workerId}` target key | none; target only | successor Redis worker storage rewrite makes occupancy canonical and removes/demotes slot occupancy in same slice |
| `worker:group:{groupId}:available:{shard}` target hint | none; target only | successor rewrite proves cost need and updates available hint atomically with canonical occupancy |
| readiness values beyond current dispatch gate (`INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, `HEALTH_UNAVAILABLE`) | target only | a worker state/report owner maps these values to dispatch gate and diagnostics with runtime outcome proof |

## Target Redis Shape

This is the successor physical direction, not current implementation truth.
Only heartbeat/candidate-bucket parts that are already implemented may be
described as current behavior. Metadata, occupancy, and available split keys are
target-only residue while `group:{groupId}:slots` remains canonical.

Namespace:

```text
{runtimePrefix}:worker
```

### Heartbeat / Liveness

```text
worker:group:{groupId}:heartbeat:{shard}
  ZSET
  member = workerId
  score  = heartbeatDeadlineAt
```

Use cases:

- recent live candidate discovery,
- bounded stale worker discovery,
- bounded cleanup of route and availability bucket members.

The score is `heartbeatDeadlineAt`, not `lastHeartbeatAt`, so stale workers can
be found with:

```text
ZRANGEBYSCORE heartbeat -inf now
```

`lastHeartbeatAt` remains diagnostic metadata.

### Metadata / Readiness

Successor target shape if a later Redis worker storage rewrite selects
`worker:meta:{workerId}` as canonical:

```text
worker:meta:{workerId}
  HASH
    groupId
    adapterNodeId
    reachabilityState
    readinessState
    readinessReason
    envVersion
    accountState
    capabilityVersion
    lastCapabilityReportAt
    lastStateReportAt
    lastHeartbeatAt
    heartbeatDeadlineAt
    dispatchDisabledSources
    draining
```

Do not add this as a parallel writable record while
`group:{groupId}:slots` remains canonical. Do not add a ready index in the
first metadata slice unless a measured candidate-filter cost requires it.

Possible later index:

```text
worker:group:{groupId}:ready:{shard}
  SET or ZSET
```

This later index is only a candidate hint. Readiness truth remains the metadata
record plus the owning worker-runtime state transitions.

### Occupancy / Availability

Successor target shape if a later Redis worker storage rewrite selects
`worker:occupancy:{workerId}` as canonical:

```text
worker:occupancy:{workerId}
  HASH
    declaredCapacity
    reservedCount
    activeLeaseCount
    activeLeaseCountByTask
    exclusiveLeaseHeld
    nextAvailableAt       # optional derived hint unless a cooldown owner exists
    occupiedUntil         # optional derived hint unless a cooldown owner exists
```

Candidate hint:

```text
worker:group:{groupId}:available:{shard}
  ZSET
  member = workerId
  score  = nextAvailableAt
```

Do not add this as a parallel writable record while
`group:{groupId}:slots` remains canonical. `available` is a hint only. It
answers "who may be able to accept work now?" Final truth is the
reserve/admission mutation path.

### Candidate Buckets

Candidate buckets are runtime index partitions. They may be derived from route
attributes, worker id hash, group-local defaults, adapter-node locality,
capability version, or another bounded partition strategy.

Target physical direction:

```text
worker:group:{groupId}:bucket:{bucketKey}:workers
worker:group:{groupId}:node:{adapterNodeId}:bucket:{bucketKey}:workers
worker:group:{groupId}:buckets
worker:group:{groupId}:node-buckets
```

Default logical strategy:

```text
bucketKey = default
```

within each worker group. A later implementation may choose hash buckets such
as `hash:{workerId % shardCount}` or domain buckets such as `region:us`, but
that is an index strategy, not worker policy truth by itself.

Current implementation uses group and node candidate bucket names. Those keys
are candidate hints, not readiness, capacity, or policy truth.

### Bucket Strategy

The bucket strategy answers:

```text
Which bounded candidate partitions should this worker appear in?
Which bounded candidate partitions should this task search?
```

The first implementation can keep the existing default behavior while renaming
the concept:

- group-local default bucket,
- optional node-local narrowing,
- optional route-derived candidate buckets when route attributes are present.

Future strategies may include:

- `hash(workerId) % N`,
- route/region buckets,
- adapter-node buckets,
- capability-version buckets,
- warm-pool buckets.

All bucket membership remains a hint. Reservation/admission must revalidate
reachability, readiness, eligibility, occupancy, and dispatch gates.

### Candidate Bucket Owner

Candidate buckets are not just Redis key names. They are the consistency
protocol between:

- task-side requested candidate partitions,
- worker-side indexed candidate partitions,
- source-guard validation for a worker returned from a partition.

Current code converges that protocol through `WorkerCandidateBucketPolicy`,
`WorkerCandidateBucketPolicies`, `WorkerTaskSelector#candidateBucketKeys`,
`WorkerCandidateIndex#sourceGuard(...)`, and
`WorkerRegistry#acquireCandidates(...)`.

Task route attributes are only inputs to the approved-attribute bucket strategy.
They do not create a second task-side bucket owner.

## Hard Rules

1. Do not introduce or preserve a composite production `worker.status` that
   mixes reachability, readiness, and occupancy.
2. `ResolvedWorkerSchedulingPolicy` must not carry live heartbeat, readiness,
   occupancy, admission, load, or lock evidence.
3. Redis candidate indexes are hints. Reserve/admission remains the final truth
   gate for stale candidates.
4. Heartbeat/liveness, readiness metadata, and occupancy records must be
   updated by their owning lifecycle paths, not by matching rules.
5. `eventCode` remains handler/capability identity. Item payload must not become
   worker-selection policy.
6. WorkerGroup capability remains external group-level capability truth.
   Worker-level supported project/event fields are compatibility/read hints
   unless a separate owner decision changes that boundary.
7. Runtime state belongs in Redis or memory runtime implementations. Control-
   plane storage must not absorb heartbeat, reservation, active lease, dispatch
   gate, or occupancy truth.
8. External candidate APIs must not expose Redis key names, physical shard
   layout, cleanup membership keys, or route-specific storage names.
9. Bucket terminology means candidate partition. It must not be route-only
   unless a specific route-derived strategy is being described.
10. Candidate-bucket task requests, worker membership, and source-guard
    validation must be derived from one policy owner.
11. A Redis fact may have only one writable owner in a slice. `group:{groupId}:slots`,
    `worker:meta:{workerId}`, and `worker:occupancy:{workerId}` must not become
    parallel writable truth.
12. `nextAvailableAt` / `occupiedUntil` must not become writable availability
    truth until a real cooldown or delayed-availability owner exists.
13. Worker occupancy may read worker-side active lease counters as occupancy
    evidence, but `TaskWorkRuntime` remains the task work lease lifecycle owner
    for claim, expiry, retry, and finality.
14. Redis worker keyspace physical-shape changes use clean-runtime recreation in
    the current pre-release stage. Do not implement live rolling migration or
    dual writers unless this roadmap is explicitly re-scoped.
15. No `SCAN` fallback is allowed on hot candidate, reserve, heartbeat cleanup,
   or availability paths.
16. Do not add compatibility aliases that keep two worker runtime keyspaces live
    indefinitely.

## Non-Goals

- No public worker policy product.
- No persisted `SchedulingPolicyCatalog` or `ProjectSchedulingBinding`.
- No SDK/server worker policy configuration surface.
- No public bucket-strategy configuration surface in the first slices.
- No item-payload matching feature.
- No all-worker scan fallback.
- No change to task runtime queue, lease, result, or terminal truth.
- No change to `TaskWorkRuntime` claim, expiry, retry, or finality semantics.
- No change to transport protocol payload ownership.
- No requirement to make Redis worker registry the default server runtime in
  the first slice.
- No ready-worker index in the first slice unless measurement proves it is
  needed.

## Do Not Start With

Do not start by renaming `WorkerSlot` or adding a global `WorkerStatus` enum.

The first useful work is classification:

```text
which current fields are reachability?
which current fields are readiness?
which current fields are occupancy?
which indexes are truth?
which indexes are candidate hints?
```

## Phase Plan

### WRSI-0: Current Worker Runtime State Inventory

Goal: classify current worker runtime fields and Redis keys before changing
behavior.

Scope:

1. Inventory `WorkerSlot`, `WorkerMeta`, `WorkerMeta#diagnosticStatus`,
   `WorkerRuntimeStateRecord`,
   `WorkerResourceRecord`, `WorkerSchedulingView`, and Redis worker registry
   fields.
2. Classify every field as:
   - reachability,
   - readiness,
   - eligibility/capability,
   - occupancy,
   - route/candidate hint,
   - diagnostic/read evidence,
   - residue.
3. Inventory current Redis worker registry keys from
   `RedisWorkerRegistryKeyspace` and classify each as canonical truth or hint.
4. Inventory current candidate-bucket vocabulary and classify:
   - `WorkerCandidateBucketPolicies`,
   - `WorkerCandidateBucketPolicy`,
   - `WorkerRegistry#acquireCandidates(... candidateBucketKey ...)`,
   - `WorkerTaskSelector#candidateBucketKeys`,
   - `WorkerCandidateIndex#sourceGuard(...)`,
   - Redis `bucket:{candidateBucketKey}:workers` key names,
   - worker bucket-membership cleanup keys.
5. Confirm which fields are owned by worker-runtime report/capability paths,
   worker registry paths, transport reachability paths, and engine selection
   paths.
6. No behavior change.

Acceptance:

1. Inventory exists beside this roadmap or in the owning Redis/worker-runtime
   baseline.
2. No field remains classified as generic "status" without a dimension.
   `WorkerMeta#diagnosticStatus` is classified as diagnostic/read evidence only,
   not scheduling truth.
3. Existing candidate bucket keys are explicitly classified as candidate hints and
   storage/index vocabulary residue, not policy truth.
4. Existing `group:{groupId}:slots` payload is classified as current physical
   aggregate, not the long-term logical model.
5. Current external caller exposure of `candidateBucketKey` is recorded as either:
   - a logical selector input to keep for now, or
   - vocabulary residue to converge in WRSI-2/WRSI-7.
6. No production behavior change.

Suggested checks:

```bash
rg -n "WorkerSlot|WorkerMeta|WorkerRuntimeStateRecord|WorkerResourceRecord|WorkerSchedulingView|RedisWorkerRegistryKeyspace|RedisWorkerRegistry|candidateBucketKey|bucket-membership" \
  xa-mass-worker-runtime platform_infra/mass-runtime-api platform_infra/mass-runtime-redis xa-mass-engine --glob '!**/target/**'
```

### WRSI-1: Dimension Value Contracts

Goal: introduce explicit dimension vocabulary without changing Redis keyspace.

Scope:

1. Add or refine worker-runtime value contracts for:
   - `WorkerReachabilityState`,
   - `WorkerReadinessState`,
   - derived `WorkerOccupancyState` if needed for diagnostics.
2. Reconcile the existing `WorkerReachabilityState.UNKNOWN` value:
   - keep it as observation-gap evidence, or
   - remove/fold it with explicit caller and test updates.
3. Keep canonical occupancy truth as counts, locks, and timestamps.
4. Keep `WorkerSchedulingView` rule context free of live evidence; full
   diagnostics may include the dimension values.
5. Do not add a public API surface unless an existing server/operator read view
   already exposes equivalent evidence.
6. Inventory external read-model fallout for:
   - `WorkerResourceRecord#statusName`,
   - `WorkerRuntimeStateRecord#statusName`,
   - `WorkerResourceOwner` status projection,
   - server worker/catalog read APIs,
   - frontend worker/runtime status badges.

Acceptance:

1. Code no longer needs a composite worker status to explain scheduling state.
2. Existing scheduling behavior is unchanged.
3. Readiness and reachability are distinguishable in diagnostics/tests.
   `UNKNOWN` is either removed or explicitly classified as non-reachable
   observation-gap evidence.
4. Occupancy classification is derived from resource facts, not manually
   written as a second truth.
5. Existing operator/server/frontend read surfaces have a named compatibility
   plan: either keep legacy `statusName/status` as display-only projection, or
   replace it with dimension fields in a coordinated API/UI slice.

Suggested proof:

```bash
mvn -pl xa-mass-worker-runtime,xa-mass-engine -am \
  "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,WorkerSchedulingCandidateEnumeratorTest,WorkerMatchContextTest" test
```

Architecture guards are residue sanity only for this phase.

### WRSI-2: Redis Keyspace Split Gates

Goal: produce bounded implementation gates for Redis worker state changes
without turning the roadmap into an open-ended design debate.

WRSI-2 is not a single blocking mega-phase. It is split into three gates. Later
phases may start once their required gate is satisfied; they must not wait for
unrelated gates.

Each gate must close as one bounded implementation slice. If a gate cannot
produce a single owner decision plus executable acceptance in that slice, stop
and re-scope the roadmap instead of carrying an open-ended design debate into
later phases.

The accepted output of a gate is not prose alone. It must either name the
current implementation path with verification, or explicitly defer the physical
change as named residue with the owner and removal condition recorded.

Cutover strategy:

```text
Redis worker keyspace physical-shape changes use clean-runtime recreation.
```

This is the chosen pre-release strategy, not merely an allowed fallback. The
implementation must not write old and new Redis worker truth in parallel for
compatibility. If a live rolling cutover becomes required, this roadmap must be
re-scoped before implementation.

#### WRSI-2A: Heartbeat / Reachability Gate

Unblocks: WRSI-3.

Scope:

1. Decide the heartbeat key owner and target key shape.
2. Decide whether heartbeat deadline remains global or becomes group/shard
   partitioned in the first executable slice.
3. Define how `UNKNOWN`, stale deadline, and missing heartbeat map to
   reachability diagnostics and reservation rejection.
4. Define cleanup behavior for stale heartbeat members without scanning worker
   keys.

Acceptance:

1. WRSI-3 can be implemented without changing candidate-bucket owner or
   occupancy storage.
2. `ONLINE`, `STALE`, `OFFLINE`, and `UNKNOWN` behavior is explicit.
3. Clean-runtime recreation is the only physical cutover path for any heartbeat
   key shape change in this roadmap.
4. No new Redis worker heartbeat truth is writable in parallel with the old
   heartbeat truth.

#### WRSI-2B: Candidate Bucket Gate

Unblocks: candidate-bucket/keyspace rename or split work.

Scope:

1. Update or create a Redis worker registry inventory describing:
   - current keys,
   - current candidate bucket keys,
   - target candidate bucket keys.
2. Confirm `candidateBucketKey` / `candidateBucketKeys` are generic logical
   candidate selector fields, not route-specific storage names.
3. Decide the candidate-bucket policy owner:
   - one owner must derive task-side requested buckets,
   - the same owner must derive worker-side bucket membership,
   - source-guard validation must use that owner,
   - direct `WorkerRegistry#acquireCandidates(...)` calls stay inside worker-
     runtime candidate ownership.
4. Define the caller/storage boundary:
   - engine-facing selectors must not depend on Redis physical key names,
   - worker-runtime APIs may expose logical candidate constraints,
   - Redis registry internals own physical bucket/shard/member key layout.

Acceptance:

1. Candidate hint indexes are explicitly non-authoritative.
2. External worker-selection callers are documented against logical selector
   fields, not Redis route/bucket keyspace names.
3. Any remaining route-attribute vocabulary is recorded as strategy input, not
   storage/keyspace vocabulary.
4. Candidate-bucket policy has one named owner across task request, worker
   membership, and source guard.
5. Clean-runtime recreation is the only physical cutover path for candidate
   bucket key shape changes in this roadmap.

#### WRSI-2C: Metadata / Occupancy Single-Writer Gate

Unblocks: WRSI-4 and WRSI-5.

Decision:

```text
group:{groupId}:slots remains canonical.
worker:meta:{workerId} is not writable production truth.
worker:occupancy:{workerId} is not writable production truth.
```

WRSI-4 and WRSI-5 must not start physical Redis split work in this roadmap.
They may only add logical readiness/occupancy diagnostics and runtime outcome
proof over the current canonical slot aggregate.

Scope:

1. Update or create a Redis worker registry inventory describing:
   - current slot payload facts,
   - target metadata keys,
   - target occupancy keys,
   - target available hint keys.
2. Record the chosen transition for `group:{groupId}:slots`:
   - current roadmap choice: retained as canonical production truth,
   - successor-only alternatives: removed in the same implementation slice,
     retained as a derived read aggregate, or retained only as test/support
     residue.
3. Define single-writer transition rules:
   - if `group:{groupId}:slots` remains canonical, `worker:meta` and
     `worker:occupancy` are not writable production truth in that slice,
   - if `worker:meta` / `worker:occupancy` become canonical, `slots` is removed
     or demoted to a derived read projection in the same slice,
   - no implementation slice may require two writable Redis truth tracks for
     the same worker fact.
4. Define atomic mutation boundaries:
   - heartbeat refresh,
   - readiness report update,
   - reserve,
   - confirm reservation,
   - release reservation,
   - record claimed/final work,
   - exclusive lock acquire/release,
   - dispatch disabled source update.
5. Define cleanup behavior for metadata, ready, occupancy, and available hint
   members.
6. Define lease boundary:
   - worker occupancy reads worker-side active lease counters,
   - `TaskWorkRuntime` remains the owner of task work lease claim, expiry,
     retry, and finality.

Acceptance:

1. There is one canonical owner for each Redis fact.
2. No migration path keeps old and new Redis truth live indefinitely.
3. No `SCAN` fallback is introduced.
4. `group:{groupId}:slots`, `worker:meta:{workerId}`, and
   `worker:occupancy:{workerId}` have an explicit single-writer transition plan.
5. Lease counts are read as worker occupancy evidence only; task lease lifecycle
   is not changed.
6. Clean-runtime recreation is the only physical cutover path for metadata or
   occupancy key shape changes in this roadmap.
7. This roadmap chooses the first transition option: keep
   `group:{groupId}:slots` canonical and defer WRSI-4/WRSI-5 physical split
   with named residue.
8. WRSI-4 and WRSI-5 may proceed after this decision because their scope is
   limited to logical dimensions and runtime outcome proof over `slots`; they
   must not wait for, or silently start, the successor physical split.

### WRSI-3: Heartbeat / Reachability Index

Goal: split heartbeat freshness from readiness and occupancy.

Prerequisite: WRSI-2A only.

Scope:

1. Implement or retarget heartbeat storage to:

```text
worker:group:{groupId}:heartbeat:{shard}
score = heartbeatDeadlineAt
```

2. Keep last heartbeat timestamp in metadata/read evidence.
3. Candidate acquisition may use heartbeat index to narrow reachable
   candidates, but reservation must still re-check heartbeat freshness.
4. Add bounded stale heartbeat cleanup.

Acceptance:

1. Reachable candidate discovery is bounded by group/shard index.
2. Stale workers are discoverable by deadline without scanning worker keys.
3. Reservation rejects stale heartbeat even if route/available buckets still
   contain the worker.
4. Existing worker reachability tests still pass or are renamed around
   reachability dimensions.

Suggested proof:

```bash
mvn -pl platform_infra/mass-runtime-redis,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-api -am \
  "-Dtest=RedisWorkerRegistryTest,WorkerRegistryContractTest,InMemoryWorkerRegistryTest" test
```

### WRSI-4: Readiness Metadata And Filtering

Goal: make readiness explicit without over-indexing it too early.

Prerequisite: WRSI-2C. WRSI-2B is not required unless the implementation also
changes candidate-bucket vocabulary.

Scope:

1. Store readiness fields in the canonical worker metadata owner selected by
   WRSI-2C. If WRSI-2C keeps `group:{groupId}:slots` canonical, WRSI-4 may
   only add logical readiness diagnostics/proof and must record the physical
   `worker:meta:{workerId}` split as named residue.

Target split-key shape:

```text
worker:meta:{workerId}
```

2. Model readiness causes such as:
   - init required,
   - version mismatch,
   - account unavailable,
   - health unavailable,
   - draining,
   - maintenance.
3. Candidate filtering reads metadata after universe/reachability/availability
   narrowing.
4. Do not add `worker:group:{groupId}:ready:{shard}` until measurement proves
   metadata filtering is too expensive.

Acceptance:

1. Current implemented readiness dimensions are explicit:
   - `READY` from dispatch-enabled, non-removing worker evidence,
   - `DRAINING` from removing/drain evidence,
   - `MAINTENANCE` from dispatch-disabled evidence.
2. `ONLINE + DRAINING + FREE` is representable and does not dispatch.
3. `STALE + READY + OCCUPIED` is representable and does not dispatch.
4. Readiness rejection reason is observable in assignment diagnostics or trace.
5. No ready index is added without a cost note.
6. `INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, and
   `HEALTH_UNAVAILABLE` remain target-only readiness values until a worker
   state/report owner maps them to dispatch-gate behavior and diagnostics.
7. Any remaining `WorkerResourceRecord#statusName`,
   `WorkerRuntimeStateRecord#statusName`, server `status`, or frontend status
   badge is display/read-model compatibility only. Replacing it with dimension
   fields is a coordinated API/UI slice, not an implicit WRSI-4 side effect.

Suggested proof:

```bash
mvn -pl xa-mass-engine,xa-mass-worker-runtime -am \
  "-Dtest=TaskSchedulingGateAndTargetingTest,TaskWorkerEligibilityTest,WorkerAdmissionOwnerTest" test
```

### WRSI-5: Occupancy / Availability Split

Goal: separate occupancy truth from available candidate hints.

Prerequisite: WRSI-2C.

Scope:

1. Store occupancy facts in the canonical worker occupancy owner selected by
   WRSI-2C. If WRSI-2C keeps `group:{groupId}:slots` canonical, WRSI-5 may
   only add logical occupancy diagnostics/proof and must record the physical
   `worker:occupancy:{workerId}` split as named residue.

Target split-key shape:

```text
worker:occupancy:{workerId}
```

2. Use available hint index:

```text
worker:group:{groupId}:available:{shard}
score = nextAvailableAt
```

3. Reserve/admission must validate:
   - slot exists,
   - heartbeat is fresh,
   - readiness is ready,
   - dispatch gate allows,
   - exclusive lock is not held when needed,
   - reserved + active capacity allows,
   - `now >= nextAvailableAt`,
   - task-local worker constraints allow.
4. Available index updates happen in the same mutation family that changes
   occupancy.
5. If there is no real cooldown or delayed-availability owner, do not introduce
   writable `nextAvailableAt`; derive the available hint from capacity,
   reservation, active lease, exclusive lock, and dispatch-gate facts.
6. Do not change `TaskWorkRuntime` lease claim, expiry, retry, or finality
   semantics. Worker occupancy consumes worker-side active lease counters as a
   read dependency only; it does not take ownership of lease lifecycle.

Acceptance:

1. `ONLINE + READY + FREE` dispatches when policy allows.
2. `ONLINE + READY + CAPACITY_FULL` does not dispatch.
3. `ONLINE + READY + OCCUPIED` rejects or waits according to capacity/lock
   truth.
4. A stale available-index member cannot bind work if occupancy truth rejects.
5. Failed reserve/lock/bind paths release every acquired runtime resource.
6. `nextAvailableAt` / `occupiedUntil` is either derived or has a named lifecycle
   owner and runtime outcome proof.
7. Task lease lifecycle tests remain behavior-neutral; any lease lifecycle
   change stops this roadmap and requires a separate owner roadmap.
8. Physical `worker:occupancy:{workerId}` and `available:{shard}` keys remain
   named residue while `group:{groupId}:slots` is canonical.
9. Any occupancy proof that reads active lease counters must demonstrate worker
   scheduling outcome only, not alter task lease ownership or expiry semantics.

Suggested proof:

```bash
mvn -pl xa-mass-engine,xa-mass-worker-runtime,platform_infra/mass-runtime-redis -am \
  "-Dtest=TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingBindingEntryBypassTest,WorkerAdmissionOwnerTest,RedisWorkerRegistryTest" test
```

### WRSI-6: Runtime Selection Integration Proof

Goal: prove the split dimensions preserve worker scheduling behavior.

Scope:

1. Add or retarget integration tests for:
   - reachable but init-required worker rejected,
   - reachable ready occupied worker rejected or delayed,
   - stale ready occupied worker rejected,
   - draining free worker rejected,
   - ready capacity-full worker rejected,
   - candidate bucket member stale but reserve rejects,
   - available hint stale but reserve rejects.
2. Reuse existing scheduling proof surfaces when possible:
   - `TaskWorkerEligibilityTest`,
   - `TaskSchedulingGateAndTargetingTest`,
   - `TaskSchedulingContentionTest`,
   - `TaskSchedulingBindingEntryBypassTest`.
3. Add Redis-specific contract tests only for keyspace/index behavior that
   memory runtime cannot prove.

Acceptance:

1. Tests prove runtime outcomes, not only object fields.
2. Rejection reasons distinguish reachability, readiness, eligibility, and
   occupancy.
3. Worker selection still flows through the approved runtime selection order.
4. Redis and memory worker registry behavior remain contract-compatible where
   they share `WorkerRegistry` semantics.

### WRSI-7: Docs, Guards, And Residue Removal

Goal: make the new dimensions durable and remove old status vocabulary.

Scope:

1. Update:
   - `xa-mass-worker-runtime/README.md`,
   - `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md`,
   - `doc/AGENT_BASELINE.md` if global vocabulary changes,
   - `doc/PROOF_REGISTRY.md` if proof ownership changes,
   - server/frontend contract docs if worker read APIs expose dimension fields.
2. Add architecture/source guards that reject:
   - new composite worker status driving scheduling,
   - Redis `SCAN` fallback in worker hot paths,
   - `ResolvedWorkerSchedulingPolicy` importing live runtime owners,
   - matching rules consuming live reachability/readiness/occupancy evidence,
   - new external callers depending on Redis physical bucket key names.
3. Remove stale docs/tests that imply `ONLINE/READY/BUSY/OFFLINE` is one axis.
4. Retire or explicitly mark display-only:
   - `WorkerResourceRecord#statusName`,
   - `WorkerRuntimeStateRecord#statusName`,
   - server worker/catalog `status` responses,
   - frontend worker/runtime status badges.
5. Archive this roadmap after residue scan and proof registry updates.

Acceptance:

1. Active docs describe reachability, readiness, eligibility, and occupancy as
   separate dimensions.
2. Redis baseline describes canonical facts and candidate hints separately.
3. No active source path uses a composite worker status as scheduling truth.
4. Any remaining `statusName` / `status` worker read field is documented as
   display-only compatibility, not scheduling truth.
5. Residue scans pass by either returning no hits for forbidden vocabulary or
   by having every non-zero hit classified in
   [2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INVENTORY.md](2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INVENTORY.md)
   as display/read-model compatibility, target-only residue, or support-test
   fixture. Unclassified hits fail WRSI-7.

Suggested residue scans:

```bash
rg -n "worker\\.status|WorkerStatus|workerStatus|statusName|ONLINE.*READY.*BUSY|READY.*BUSY.*OFFLINE" \
  xa-mass-engine xa-mass-worker-runtime platform_infra doc roadmap --glob '!**/target/**' --glob '!doc/archive/**'
rg -n "\\bSCAN\\b|scanIterator|keys\\(" \
  platform_infra/mass-runtime-redis/src/main/java --glob '!**/target/**'
rg -n "ResolvedWorkerSchedulingPolicy.*(Reachability|Admission|Registry|Lease|Load)|WorkerSchedulingPolicy.*Redis" \
  xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "candidateBucketKey|groupCandidateBucket|nodeCandidateBucket|route:.*:workers" \
  xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java platform_infra/mass-runtime-api/src/main/java platform_infra/mass-runtime-redis/src/main/java --glob '!**/target/**'
```

## Proof Bar

A change counts as worker state dimension proof only if it observes at least one
runtime-visible outcome:

- candidate excluded before binding,
- reservation rejected,
- exclusive lock not acquired,
- capacity counter unchanged,
- dispatch binding absent,
- rejection record / trace reason emitted,
- active lease count unchanged,
- previously blocked worker dispatches after readiness or occupancy changes.

The following are support only:

- enum value assertions,
- Redis key-name assertions,
- object field-copy tests,
- source scans without runtime outcome proof.

## Verification Bundle

Minimum focused verification after implementation slices:

```bash
mvn -pl xa-mass-worker-runtime,xa-mass-engine -am \
  "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,TaskCandidateWarmPoolTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskSchedulingBindingEntryBypassTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am \
  "-Dtest=WorkerRegistryContractTest,InMemoryWorkerRegistryTest,RedisWorkerRegistryTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check
```

Architecture guards and residue scans are support verification only. They must
not be cited as proof of runtime state correctness without the runtime outcome
tests above.

If server/runtime profile wiring changes, add Boot/context proof for the
affected profile. Unit tests against constructors are not enough for Spring
profile wiring.

## Exit Criteria

1. `WorkerSchedulingPolicy` / `ResolvedWorkerSchedulingPolicy` remains static
   worker-universe input only.
2. Worker runtime state has explicit reachability, readiness/eligibility, and
   occupancy/availability dimensions.
3. Redis worker registry has separate heartbeat ownership and explicit
   metadata/occupancy/availability residue while `group:{groupId}:slots`
   remains canonical.
4. Candidate hints cannot bind stale or ineligible workers because reservation
   revalidates truth.
5. External worker-selection call surfaces are separated from Redis physical
   bucket keyspace; any remaining candidate-bucket vocabulary is named residue, not
   treated as durable public API.
6. Default group-local bucket strategy can later change to hash/custom
   partitioning without changing public worker policy configuration.
7. Candidate-bucket policy has one owner across task-side request, worker-side
   membership, and source-guard validation.
8. Redis worker facts have no parallel writable truth across `slots`, `meta`,
   and `occupancy` keys.
9. No writable `nextAvailableAt` / `occupiedUntil` exists without a named
   lifecycle owner and outcome proof.
10. No hot path uses Redis scans as fallback.
11. No active docs describe a composite worker status as scheduling truth.
12. Runtime outcome tests prove the implemented important combinations:
   - `UNKNOWN/STALE + READY + FREE/OCCUPIED` does not bind,
   - `ONLINE + READY + OCCUPIED` does not double-assign,
   - `ONLINE + DRAINING/MAINTENANCE + FREE` does not dispatch,
   - `ONLINE + READY + CAPACITY_FULL` does not dispatch.
13. Redis baseline and worker-runtime README carry the durable facts.
14. Roadmap is archived after residue scan.
