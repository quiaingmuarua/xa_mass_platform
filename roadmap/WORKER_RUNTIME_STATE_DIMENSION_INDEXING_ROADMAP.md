# Worker Runtime State Dimension Indexing Roadmap

Status: proposed convergence roadmap.

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
- group and node route buckets,
- heartbeat deadline index,
- exclusive lease index,
- task-worker active count indexes,
- `WATCH` / `MULTI` / `EXEC` over the group-local slot hash for reservation.

This roadmap does not claim that the split keyspace already exists.

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

Current gap:

- `WorkerRegistry#acquireCandidates(...)` exposes `routeBucketKey`.
- `WorkerTaskSelector` exposes `routeBucketKeys`.
- `RedisWorkerRegistryKeyspace` names candidate buckets as
  `:route:{routeBucketKey}:workers`.

That means the internal storage partition vocabulary is still visible above the
Redis implementation boundary. This roadmap should converge that vocabulary to
a generic candidate-bucket concept, or explicitly record why `routeBucketKey`
remains a logical selector input rather than Redis storage shape. The preferred
direction is to keep engine callers on `WorkerCandidateRuntime` /
`WorkerTaskSelector` and keep direct `WorkerRegistry#acquireCandidates(...)`
usage inside worker-runtime candidate ownership.

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
reachabilityState = ONLINE / STALE / OFFLINE
```

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

## Target Redis Shape

This is the target physical direction, not current implementation truth.

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

First slice:

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

Do not add a ready index in the first slice unless a measured candidate-filter
cost requires it.

Possible later index:

```text
worker:group:{groupId}:ready:{shard}
  SET or ZSET
```

This later index is only a candidate hint. Readiness truth remains the metadata
record plus the owning worker-runtime state transitions.

### Occupancy / Availability

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

`available` is a hint only. It answers "who may be able to accept work now?"
Final truth is the reserve/admission mutation path.

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

Current implementation still uses route and node-route bucket names. Those
current keys must be classified as candidate hints, not readiness, capacity,
or policy truth.

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

Current code splits that protocol across `WorkerRoutingPolicy`,
`WorkerRouteBucketPolicy`, `WorkerTaskSelector#routeBucketKeys`,
`WorkerCandidateIndex#sourceGuard(...)`, and `WorkerRegistry#acquireCandidates`.

This roadmap must converge that protocol to one owner before changing physical
keys. The likely owner is a renamed/generalized candidate-bucket policy such as
`WorkerCandidateBucketPolicy`, or an explicitly retained `WorkerRouteBucketPolicy`
classified as a route-derived candidate-bucket policy. A pure rename is not
enough unless both task-side bucket requests and worker-side bucket membership
continue to use the same owner.

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
13. No `SCAN` fallback is allowed on hot candidate, reserve, heartbeat cleanup,
   or availability paths.
14. If the Redis physical shape changes, clean-runtime recreation is acceptable
   in the current pre-release stage; do not add compatibility aliases that keep
   two worker runtime keyspaces live indefinitely.

## Non-Goals

- No public worker policy product.
- No persisted `SchedulingPolicyCatalog` or `ProjectSchedulingBinding`.
- No SDK/server worker policy configuration surface.
- No public bucket-strategy configuration surface in the first slices.
- No item-payload matching feature.
- No all-worker scan fallback.
- No change to task runtime queue, lease, result, or terminal truth.
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
   - `WorkerRoutingPolicy`,
   - `WorkerRouteBucketPolicy`,
   - `WorkerRegistry#acquireCandidates(... routeBucketKey ...)`,
   - `WorkerTaskSelector#routeBucketKeys`,
   - `WorkerCandidateIndex#sourceGuard(...)`,
   - Redis `route:{routeBucketKey}:workers` key names,
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
3. Existing route bucket keys are explicitly classified as candidate hints and
   storage/index vocabulary residue, not policy truth.
4. Existing `group:{groupId}:slots` payload is classified as current physical
   aggregate, not the long-term logical model.
5. Current external caller exposure of `routeBucketKey` is recorded as either:
   - a logical selector input to keep for now, or
   - vocabulary residue to converge in WRSI-2/WRSI-7.
6. No production behavior change.

Suggested checks:

```bash
rg -n "WorkerSlot|WorkerMeta|WorkerRuntimeStateRecord|WorkerResourceRecord|WorkerSchedulingView|RedisWorkerRegistryKeyspace|RedisWorkerRegistry|routeBucketKey|bucket-membership" \
  xa-mass-worker-runtime platform_infra/mass-runtime-api platform_infra/mass-runtime-redis xa-mass-engine --glob '!**/target/**'
```

### WRSI-1: Dimension Value Contracts

Goal: introduce explicit dimension vocabulary without changing Redis keyspace.

Scope:

1. Add or refine worker-runtime value contracts for:
   - `WorkerReachabilityState`,
   - `WorkerReadinessState`,
   - derived `WorkerOccupancyState` if needed for diagnostics.
2. Keep canonical occupancy truth as counts, locks, and timestamps.
3. Keep `WorkerSchedulingView` rule context free of live evidence; full
   diagnostics may include the dimension values.
4. Do not add a public API surface unless an existing server/operator read view
   already exposes equivalent evidence.

Acceptance:

1. Code no longer needs a composite worker status to explain scheduling state.
2. Existing scheduling behavior is unchanged.
3. Readiness and reachability are distinguishable in diagnostics/tests.
4. Occupancy classification is derived from resource facts, not manually
   written as a second truth.

Suggested proof:

```bash
mvn -pl xa-mass-worker-runtime,xa-mass-engine -am \
  "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,WorkerSchedulingCandidateEnumeratorTest,WorkerMatchContextTest,EngineSchedulingCoreArchitectureGuardTest" test
```

### WRSI-2: Redis Keyspace Split Design

Goal: decide the physical Redis transition before implementation.

Scope:

1. Update or create a Redis worker registry inventory describing:
   - current keys,
   - target heartbeat keys,
   - target metadata keys,
   - target occupancy keys,
   - target available hint keys,
   - current route bucket keys,
   - target candidate bucket keys.
2. Decide whether `group:{groupId}:slots` is:
   - removed in the same implementation slice,
   - retained as a derived read aggregate,
   - retained only as test/support residue until WRSI-6.
3. Decide whether `routeBucketKey` remains a logical candidate selector field
   or is renamed to generic `candidateBucketKey` / `candidateBucketKeys`.
4. Decide the candidate-bucket policy owner:
   - one owner must derive task-side requested buckets,
   - the same owner must derive worker-side bucket membership,
   - source-guard validation must use that owner,
   - direct `WorkerRegistry#acquireCandidates(...)` calls stay inside worker-
     runtime candidate ownership.
5. Define the caller/storage boundary:
   - engine-facing selectors must not depend on Redis physical key names,
   - worker-runtime APIs may expose logical candidate constraints,
   - Redis registry internals own physical bucket/shard/member key layout.
6. Define single-writer transition rules:
   - if `group:{groupId}:slots` remains canonical, `worker:meta` and
     `worker:occupancy` are not writable production truth in that slice,
   - if `worker:meta` / `worker:occupancy` become canonical, `slots` is removed
     or demoted to a derived read projection in the same slice,
   - no implementation slice may require two writable Redis truth tracks for
     the same worker fact.
7. Define atomic mutation boundaries:
   - heartbeat refresh,
   - readiness report update,
   - reserve,
   - confirm reservation,
   - release reservation,
   - record claimed/final work,
   - exclusive lock acquire/release,
   - dispatch disabled source update.
8. Define cleanup behavior for stale heartbeat, candidate bucket, ready, and
   available hint members.

Acceptance:

1. There is one canonical owner for each Redis fact.
2. Candidate hint indexes are explicitly non-authoritative.
3. No migration path keeps old and new Redis truth live indefinitely.
4. No `SCAN` fallback is introduced.
5. External worker-selection callers are documented against logical selector
   fields, not Redis route/bucket keyspace names.
6. If route-bucket vocabulary remains after this phase, it is recorded as named
   residue with owner, reason, and removal condition.
7. `group:{groupId}:slots`, `worker:meta:{workerId}`, and
   `worker:occupancy:{workerId}` have an explicit single-writer transition plan.
8. Candidate-bucket policy has one named owner across task request, worker
   membership, and source guard.

### WRSI-3: Heartbeat / Reachability Index

Goal: split heartbeat freshness from readiness and occupancy.

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

Scope:

1. Store readiness fields in:

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

1. `ONLINE + INIT_REQUIRED + FREE` is representable.
2. `ONLINE + DRAINING + FREE` is representable and does not dispatch.
3. `STALE + READY + OCCUPIED` is representable and does not dispatch.
4. Readiness rejection reason is observable in assignment diagnostics or trace.
5. No ready index is added without a cost note.

Suggested proof:

```bash
mvn -pl xa-mass-engine,xa-mass-worker-runtime -am \
  "-Dtest=TaskSchedulingGateAndTargetingTest,TaskWorkerEligibilityTest,WorkerAdmissionOwnerTest" test
```

### WRSI-5: Occupancy / Availability Split

Goal: separate occupancy truth from available candidate hints.

Scope:

1. Store occupancy facts in:

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

Acceptance:

1. `ONLINE + READY + FREE` dispatches when policy allows.
2. `ONLINE + READY + CAPACITY_FULL` does not dispatch.
3. `ONLINE + READY + OCCUPIED` rejects or waits according to capacity/lock
   truth.
4. A stale available-index member cannot bind work if occupancy truth rejects.
5. Failed reserve/lock/bind paths release every acquired runtime resource.
6. `nextAvailableAt` / `occupiedUntil` is either derived or has a named lifecycle
   owner and runtime outcome proof.

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
   - `doc/PROOF_REGISTRY.md` if proof ownership changes.
2. Add architecture/source guards that reject:
   - new composite worker status driving scheduling,
   - Redis `SCAN` fallback in worker hot paths,
   - `ResolvedWorkerSchedulingPolicy` importing live runtime owners,
   - matching rules consuming live reachability/readiness/occupancy evidence,
   - new external callers depending on Redis physical bucket key names.
3. Remove stale docs/tests that imply `ONLINE/READY/BUSY/OFFLINE` is one axis.
4. Archive this roadmap after residue scan and proof registry updates.

Acceptance:

1. Active docs describe reachability, readiness, eligibility, and occupancy as
   separate dimensions.
2. Redis baseline describes canonical facts and candidate hints separately.
3. No active source path uses a composite worker status as scheduling truth.
4. Residue scans pass.

Suggested residue scans:

```bash
rg -n "worker\\.status|WorkerStatus|ONLINE.*READY.*BUSY|READY.*BUSY.*OFFLINE" \
  xa-mass-engine xa-mass-worker-runtime platform_infra doc roadmap --glob '!**/target/**'
rg -n "\\bSCAN\\b|scanIterator|keys\\(" \
  platform_infra/mass-runtime-redis/src/main/java --glob '!**/target/**'
rg -n "ResolvedWorkerSchedulingPolicy.*(Reachability|Admission|Registry|Lease|Load)|WorkerSchedulingPolicy.*Redis" \
  xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "routeBucketKey|groupRouteBucket|nodeRouteBucket|route:.*:workers" \
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
  "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,TaskCandidateWarmPoolTest,TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskSchedulingBindingEntryBypassTest" test
mvn -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am \
  "-Dtest=WorkerRegistryContractTest,InMemoryWorkerRegistryTest,RedisWorkerRegistryTest,RedisWorkerRegistryKeyspaceTest" test
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
3. Redis worker registry has separate heartbeat, metadata, occupancy, and
   availability hint ownership, or a recorded reason why a slice is deferred.
4. Candidate hints cannot bind stale or ineligible workers because reservation
   revalidates truth.
5. External worker-selection call surfaces are separated from Redis physical
   bucket keyspace; any remaining route-bucket vocabulary is named residue, not
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
12. Runtime outcome tests prove the important combinations:
   - `ONLINE + INIT_REQUIRED + FREE`,
   - `ONLINE + READY + OCCUPIED`,
   - `STALE + READY + OCCUPIED`,
   - `ONLINE + DRAINING + FREE`,
   - `ONLINE + READY + CAPACITY_FULL`.
13. Redis baseline and worker-runtime README carry the durable facts.
14. Roadmap is archived after residue scan.
