# Worker Runtime Admission SPI And Redis Shape Convergence Roadmap

Status: archived after implemented current-scope convergence. Candidate
acquisition and node-group worker lookup boundedness are deferred to
`roadmap/WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`.

Related documents:

- `platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker/WorkerRegistry.java`
- `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md`
- `platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/RedisWorkerRegistryKeyspace.java`
- `xa-mass-worker-runtime/CONTRACTS.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md` (boundary reference only)
- `doc/PROOF_REGISTRY.md`
- `roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`

## Objective

This roadmap does only two things:

1. **Worker Runtime Admission SPI Convergence**
   Kernel and worker-runtime callers must not know Redis physical key shape.
   Current `WorkerRegistry` admission remains the implemented final admission
   guard, but upper callers must reach it through semantic runtime APIs.
2. **Worker Runtime Redis Shape Reduction**
   After the SPI boundary is clean, reduce redundant worker-runtime Redis key
   families. Keep only key families with a strong runtime reason, bounded read
   shape, and single owner.

This roadmap does not implement worker-side final admission, pending-offer
leases, public worker offer APIs, SDK worker-session semantics, or transport
presence projection cleanup. Those are deferred successor topics, not active
files in this roadmap directory.

## Current Truth

Current implemented admission truth:

- `WorkerAdmissionRuntime` and `WorkerRegistry` reserve/confirm/release are the
  current platform-side final admission guard.
- `WorkerOccupancyState` is diagnostic. `OCCUPIED` can still be schedulable
  when capacity remains.
- `TaskWorkRuntime` owns task work leases, retry visibility, result validity,
  and runtime work lifecycle. This roadmap does not move lease truth into
  worker runtime.
- Transport presence owns route reachability and delivery owner state. Worker
  runtime must not copy transport presence projections into worker admission
  truth.

Current Redis worker-runtime key families:

| Key family | Type | Current role | Initial direction |
| --- | --- | --- | --- |
| `...:worker:group` | `HASH` | `workerId -> groupId` lookup for worker-id semantic APIs | Retained because worker-id semantic APIs need bounded lookup without decoding worker id prefixes |
| `...:groups` | `SET` | Group discovery / cleanup | Retained as group-local cleanup discovery; sharding deferred until group cardinality is proven large |
| `...:exclusive-leases` | `SET` | Global exclusive-lease lookup | Retained because `exclusiveLeaseWorkerIds()` is a current diagnostic/support API and scanning all slots would be worse |
| `...:group:{groupId}:slots` | `HASH` | Encoded `WorkerSlot` by worker id; current admission aggregate | Current truth; this roadmap may optimize physical read/write cost only. It must not delete or downgrade `reservedCount`, `activeLeaseCount`, `activeLeaseCountByTask`, or `exclusiveLeaseHeld`. |
| `...:group:{groupId}:heartbeat:0` | `ZSET` | Registry-local heartbeat deadline | Retained; expiry cleanup reads at most the remaining cleanup limit per group zset. Group-index sharding is deferred until group cardinality is a proven deployment issue. |
| `...:group:{groupId}:bucket:{bucket}:workers` | `SET` | Candidate source | Retained under current complete-set acquisition semantics; bounded candidate acquisition is deferred to `WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md` |
| `...:group:{groupId}:buckets` | `SET` | Candidate bucket cleanup discovery | Keep only with bounded cleanup reason |
| `...:group:{groupId}:node:{nodeId}:bucket:{bucket}:workers` | `SET` | Node-scoped candidate source and node-group maintenance source | Retained under current complete-set lookup semantics; paged node-group maintenance is deferred to `WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md` |
| `...:group:{groupId}:node-buckets` | `SET` | Node bucket cleanup discovery | Keep only with bounded cleanup reason |
| `...:group:{groupId}:bucket-membership` | `HASH` | Group-local reverse bucket membership cleanup; field is `workerId`, value is encoded bucket-key list | Reshaped from retired per-worker key family; retain as Redis implementation cleanup projection, not scheduling truth |
| `...:task:{taskId}:worker-active-count` | `HASH` | Active lease count by worker for a task; also backs active worker ids/count | Retained as the single task-worker occupancy projection; retired parallel `active-workers` set |

Current known cost issues:

- `RedisWorkerRegistry` has many `synchronized` methods.
- `updateSlot(...)` uses `WATCH` / `MULTI` on the whole group slots hash.
- `acquireCandidates(...)` materializes a full bucket with `SMEMBERS` before
  sampling. This is current complete-set policy semantics and is deferred to
  `WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`.
- `workerIdsByAdapterNodeGroup(...)` materializes node bucket members. This is
  current complete-set maintenance SPI semantics and is deferred to
  `WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`.

## Target Invariants

- Engine and `xa-mass-worker-runtime` production code depend on
  `platform_infra/mass-runtime-api`, not Redis key names, keyspace classes,
  codecs, or `mass-runtime-redis` implementation classes.
- `WorkerRegistry` exposes semantic operations for admission, metadata,
  candidate acquisition, dispatch gates, and cleanup. Redis physical shape stays
  behind the implementation.
- Memory and Redis runtime implementations remain substitutable through shared
  contract tests.
- Every retained Redis key family has a documented owner, lifecycle,
  cardinality formula, bounded-read story, and proof reason.
- Redundant key families are removed rather than kept as compatibility aliases.
- Current platform-side admission remains current truth unless a successor
  worker-offer proof roadmap replaces it.

## Hard Non-Goals

- Do not add worker-side `ACCEPTED` / `BUSY` / `CAPACITY_FULL` offer protocol.
- Do not add pending-offer lease state.
- Do not change public worker API, Java SDK session semantics, embedded SDK
  session semantics, worker-pack protocols, or Node samples.
- Do not delete `reservedCount`, `activeLeaseCount`,
  `activeLeaseCountByTask`, or `exclusiveLeaseHeld` based on target theory.
- Do not introduce writable `worker:meta:{workerId}`,
  `worker:occupancy:{workerId}`, or `worker:available:{shard}` beside active
  `group:{groupId}:slots` as a second truth track.
- Do not preserve old and new Redis shapes with dual writes or fallback readers.
  This repo is pre-release; use clean runtime recreation for internal runtime
  key-shape replacement unless a later decision explicitly requires migration.
- Do not use Redis Cluster/hash-tag work as part of this roadmap. If a selected
  implementation would constrain future cluster deployment, stop and record a
  deferred deployment decision.
- Do not optimize key count by creating one large global worker hash. Key count
  reduction must not create hot keys or unbounded reads.

## Stop Gates

Stop and request owner review before implementation if a slice requires any of
the following:

- A product or runtime semantic decision about final admission ownership.
- A public API, SDK, worker-pack, or sample protocol change.
- A new task-work lease state or change to result-finality validity.
- A dual-write compatibility bridge.
- A Redis physical shape that leaks above `mass-runtime-redis`.
- A retained key family whose only reason is convenience.
- A proof claim based only on field-copy tests, getter tests, key-name tests, or
  local key count.

## Track A: Worker Runtime Admission SPI Convergence

Goal:

Make the current platform-side admission path storage-independent from the
kernel perspective.

Current status:

This track is a checkpoint. The source scans are expected to stay green; the
required output is inventory/classification, not a new wrapper, facade, or
bridge. Do not create code just to "implement" Track A.

Scope:

- Inventory production imports and references in `xa-mass-engine` and
  `xa-mass-worker-runtime`.
- Confirm upper callers do not import:
  - `com.xa.mass.runtime.redis`,
  - `RedisWorkerRegistry`,
  - `RedisWorkerRegistryKeyspace`,
  - Redis key suffixes such as `worker:group`, `group:{groupId}:slots`,
    `bucket-membership`, or `worker-active-count`.
- Confirm upper callers do not directly call implementation/slot-shaped methods
  such as:
  - `slotByWorkerId(...)`,
  - `markSlotRemoving(...)`,
  - `workerIdsByGroupId(...)`,
  - `workerIdsByAdapterNodeGroup(...)`.
- Classify `WorkerRegistry` methods as:
  - semantic runtime SPI,
  - implementation/maintenance surface,
  - contract-test surface,
  - remove/retarget candidate.
- If an upper caller needs a new access pattern, add or propose a
  storage-independent `mass-runtime-api` semantic method before touching Redis.
- Keep current admission behavior unchanged.

Acceptance:

- Engine and `xa-mass-worker-runtime` production source has no direct Redis
  implementation imports, keyspace references, or Redis key-name literals.
- Engine and `xa-mass-worker-runtime` production source reaches admission,
  worker metadata, dispatch gates, and candidate acquisition through
  `platform_infra/mass-runtime-api` or worker-runtime semantic owners.
- Current admission proof remains platform-side:
  `WorkerAdmissionRuntime` / `WorkerRegistry` reserve result owns the binding
  decision.
- `doc/PROOF_REGISTRY.md` continues to state that worker-offer outcome proof is
  successor work, not current proof.
- No public API, SDK, transport, or task-work lifecycle behavior changes in this
  track.
- Shared memory and Redis contract tests still pass.

Verification:

```powershell
rg -n "com\\.xa\\.mass\\.runtime\\.redis|RedisWorkerRegistry|RedisWorkerRegistryKeyspace|worker:group|group:\\{groupId\\}:slots|bucket-membership|worker-active-count" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "\\.slotByWorkerId\\(|\\.markSlotRemoving\\(|\\.workerIdsByGroupId\\(|\\.workerIdsByAdapterNodeGroup\\(" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
.\mvnw.cmd -pl xa-mass-engine "-Dtest=EngineSchedulingCoreArchitectureGuardTest#upperRuntimeCallersUseWorkerRegistrySemanticOperations" test
.\mvnw.cmd -pl platform_infra/mass-runtime-memory "-Dtest=InMemoryWorkerRegistryTest" test
.\mvnw.cmd -pl platform_infra/mass-runtime-redis "-Dtest=RedisWorkerRegistryTest" test
```

Completion output:

- A short inventory table of upper-layer callers and the semantic API each uses.
- A `WorkerRegistry` method classification table.
- Any required SPI deltas, or an explicit statement that no SPI delta is needed
  for Track B.

Track A checkpoint output:

| Upper-layer caller family | Current access | Classification |
| --- | --- | --- |
| Engine matching, dispatch binding, resource release, wakeup wiring | `WorkerCandidateRuntime`, `WorkerAdmissionRuntime`, `WorkerWarmHintRuntime`, `WorkerAvailabilityWakeupRuntime` | semantic worker-runtime APIs; no Redis implementation or key-shape access |
| `xa-mass-worker-runtime` resource, candidate, admission, relationship owners | `WorkerRegistry` from `mass-runtime-api` | worker-runtime owner implementation surface; physical Redis shape remains behind memory/Redis adapters |
| `xa-mass-worker-runtime` report/snapshot paths | `WorkerRegistrySnapshot` and resource query records | diagnostic/control-plane read model, not Redis key-shape truth |

| `WorkerRegistry` surface | Classification | Notes |
| --- | --- | --- |
| `tryReserve`, `confirmReservation`, `releaseReservation`, `recordWorkClaimed`, `recordWorkFinal`, dispatch-gate operations, exclusive-lease operations | semantic runtime admission SPI | Current platform-side admission proof remains here. |
| `acquireCandidates`, `markCandidateStale`, `cleanupStaleBucketMembers` | semantic candidate/cleanup SPI | Candidate indexes may be stale; reserve re-validates current slot. |
| `workerMeta`, `workerAdmissionSnapshot`, worker-id default methods | semantic worker-id lookup SPI | Backed by implementation-specific bounded lookup projection. |
| `upsertSlot`, `markSlotRemoving`, `cleanupRemovedSlots`, `cleanupExpiredHeartbeats` | worker-runtime owner / maintenance surface | Used by worker-resource lifecycle and registry cleanup, not by engine scheduling shape logic. |
| `slot`, `slotByWorkerId`, `workerIdsByGroupId`, `workerIdsByAdapterNodeGroup`, task active-count reads | contract-test / diagnostic / maintenance support | Retained behind `mass-runtime-api`; upper engine production should keep using worker-runtime semantic owners. |

No Track A SPI delta is needed for the first Track B slice.

## Track B: Worker Runtime Redis Shape Reduction

Goal:

After Track A is green, remove or reshape redundant Redis worker-runtime key
families while preserving current platform-side admission behavior.

Ordering:

1. Inventory key families and bounded-read costs.
2. Pick one key family.
3. Replace it with one single-writer shape or remove it.
4. Delete old keyspace methods, tests, docs, and vocabulary in the same slice.
5. Rerun memory/Redis contract proof.

First execution slice:

- Start with `bucket-membership` per-worker keys.
- Do not start Track B with `group:{groupId}:slots`, `active-workers`,
  `worker-active-count`, `exclusive-leases`, or `worker:group`.
- The first slice should reduce per-worker key cardinality without changing
  admission, task occupancy, exclusive lease, or worker-id lookup semantics.

Candidate reductions:

| Candidate | Current issue | Disposition / allowed direction |
| --- | --- | --- |
| `bucket-membership` per-worker keys | One key per worker for reverse cleanup | Reshaped to `...:group:{groupId}:bucket-membership` group-local hash; no old per-worker key fallback |
| `task:{taskId}:active-workers` | Duplicates `worker-active-count` for active worker count | Removed; `activeWorkerIdsByTask` and `activeWorkerCountForTask` derive from `worker-active-count` hash fields |
| Candidate bucket reads | Mainline `acquireCandidates(...)` still uses full `SMEMBERS` before policy sampling | Deferred to `WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`; current complete-set policy semantics are retained |
| `heartbeat:0` | Future shard suffix but currently one zset per group | Retained with bounded per-group expired read; shard threshold deferred until group cardinality is a demonstrated deployment issue |
| `exclusive-leases` | Global diagnostic index duplicating slot flag | Retained for current `exclusiveLeaseWorkerIds()` diagnostic/support API; removing it would require replacing that API or scanning all slots |
| `worker:group` | Global worker-id lookup projection | Retained for worker-id semantic APIs such as `workerMeta(workerId)`, worker-id reserve/release defaults, and dispatch-gate defaults |

First slice status:

- `RedisWorkerRegistryKeyspace#workerBucketMembershipSet(...)` is removed.
- `RedisWorkerRegistry` stores reverse bucket membership in
  `groupBucketMembershipHash(groupId)` and removes the worker field on slot
  removal or stale-bucket cleanup.
- The old per-worker key shape is not read as a fallback and is not dual-written.
- `RedisWorkerRegistryTest#bucketMembershipUsesGroupLocalHashInsteadOfPerWorkerKeys`
  guards the physical-shape regression; registry contract tests remain the
  behavior proof.

Additional completed reduction:

- `RedisWorkerRegistryKeyspace#taskActiveWorkersSet(...)` is removed.
- `RedisWorkerRegistry` derives `activeWorkerIdsByTask(taskId)` and
  `activeWorkerCountForTask(taskId)` from `taskWorkerActiveCountsHash(taskId)`.
- The old `task:{taskId}:active-workers` set is not written or read as a
  fallback.
- `RedisWorkerRegistryTest#taskWorkerActiveCountHashOwnsActiveWorkerProjectionWithoutActiveWorkerSet`
  guards the physical-shape regression; shared registry contract tests remain
  the behavior proof.

Retained with reviewed reason:

- `group:{groupId}:heartbeat:0` remains the registry-local heartbeat deadline
  index. `cleanupExpiredHeartbeats(...)` now pushes the remaining cleanup limit
  into each Redis zset read. The `groups` set is still the group discovery owner;
  sharding it is deferred until group cardinality is an explicit deployment
  problem.
- `cleanupStaleBucketMembers(...)` now samples at most the remaining cleanup
  limit from each group bucket. This bounds cleanup reads without changing
  scheduling candidate acquisition semantics.
- `cleanupRemovedSlots(...)` now scans group slot fields through bounded
  Redis `HSCAN` before reclaiming removable fields.
- `worker:group` remains the bounded lookup projection for worker-id semantic
  APIs. Removing it would require a different worker-id lookup owner.
- `exclusive-leases` remains the support index for
  `exclusiveLeaseWorkerIds()`. Removing it would require deleting/replacing that
  diagnostic API or scanning all group slot hashes.

Deferred successor decision:

- Mainline `acquireCandidates(...)` still materializes a bucket before applying
  `WorkerCandidateSamplingPolicy`. Replacing that with Redis-side sampling would
  change candidate selection behavior. The successor roadmap must decide whether
  policy sees complete candidate sets or bounded subsets before implementation.
- `workerIdsByAdapterNodeGroup(...)` still returns the complete current worker
  set for a node/group by materializing node bucket members. Replacing it with a
  bounded cursor, page token, or node-membership index changes the
  `WorkerRegistry` maintenance SPI. The successor roadmap owns that contract
  decision.

Scope:

- Update `RedisWorkerRegistryKeyspace` and `RedisWorkerRegistry` only through
  `WorkerRegistry` semantics.
- Preserve current reserve/confirm/release behavior.
- Preserve candidate correctness: candidate indexes may be stale, but selection
  must re-read current worker metadata/admission state before binding.
- Replace full-key reads on mainline or cleanup paths with Redis-side bounded
  reads where the slice claims bounded behavior.
- Treat `synchronized`, shared connection, and `WATCH` / `MULTI` cost as part of
  the physical shape review. A lower key count with the same global JVM monitor
  may still be unacceptable.
- Update `REDIS_RUNTIME_BASELINE.md` when a key family changes.

Acceptance:

- Every changed key family has a documented owner, lifecycle, cardinality
  formula, bounded-read shape, and proof reason.
- Removed key families have no keyspace aliases, fallback readers, dual writes,
  or test-only vocabulary preserving the old shape.
- No upper runtime caller changes because of Redis key layout unless the
  `WorkerRegistry` SPI was intentionally revised in Track A.
- Memory and Redis `WorkerRegistry` behavior remains aligned through shared
  contract tests.
- Current platform-side admission proof remains valid.
- No transport presence key is moved into worker-runtime ownership.
- `doc/PROOF_REGISTRY.md` and `REDIS_RUNTIME_BASELINE.md` match the implemented
  current shape after each slice.

Verification:

```powershell
.\mvnw.cmd -pl platform_infra/mass-runtime-memory "-Dtest=InMemoryWorkerRegistryTest" test
.\mvnw.cmd -pl platform_infra/mass-runtime-redis "-Dtest=RedisWorkerRegistryTest" test
git diff --check
```

Each Track B slice must also define its own residue scan with explicit expected
status for each searched pattern:

```text
pattern -> expected absent | expected retained | expected moved | expected deferred
```

Do not treat a broad `rg` hit as either automatic failure or automatic proof.
The slice owner must explain every retained hit.

Completion output:

- Updated `REDIS_RUNTIME_BASELINE.md` classification for changed key families.
- Residue scan for removed key names and old keyspace methods.
- Contract proof showing memory and Redis remain behaviorally aligned.
- Candidate disposition table covering every candidate in this roadmap as one
  of: `removed`, `reshaped`, `retained-with-reviewed-reason`, or
  `deferred-to-named-successor`.

## Deferred Successor Topics

These topics are intentionally outside this roadmap:

- **Worker Offer Admission Proof**
  Prove worker-side `ACCEPTED` / `BUSY` / `CAPACITY_FULL` / timeout behavior,
  pending-offer or transitional release semantics, stale-result rejection, and
  external worker path behavior before deleting platform occupancy.
- **Transport Presence Projection Cleanup**
  Remove or demote `worker-route:{workerId}` and `workers` under the transport
  owner. Worker runtime may record the dependency but must not own transport
  key cleanup.
- **Redis Runtime Key Proof Operator**
  Codify scenario-level Redis key proof only after worker-runtime and transport
  key ownership are stable.
- **Redis Cluster Deployment Shape**
  Decide hash tags and multi-key transaction boundaries when distributed Redis
  deployment becomes an explicit target.

## Completion Criteria

This roadmap is complete when:

1. Track A proves engine and `xa-mass-worker-runtime` do not depend on Redis
   physical shape.
2. Current platform-side admission remains explicit and documented as current
   truth.
3. Track B records a disposition for every candidate key family named in this
   roadmap: `removed`, `reshaped`, `retained-with-reviewed-reason`, or
   `deferred-to-named-successor`.
4. Every retained worker-runtime Redis key family has a reviewed reason and
   either a bounded-read story or an explicit complete-set semantics deferral to
   a named successor roadmap.
5. `REDIS_RUNTIME_BASELINE.md`, `xa-mass-worker-runtime/CONTRACTS.md`, and
   `doc/PROOF_REGISTRY.md` agree with current code.
6. Memory and Redis `WorkerRegistry` contract tests pass.
7. No active roadmap wording claims worker-side final admission, pending-offer,
   or public offer protocol has landed.
8. Residue scans show removed key names, keyspace methods, and old tests/docs
   are gone.
