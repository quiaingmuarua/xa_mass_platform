# Worker Runtime Redis Key Shape Convergence Roadmap

Status: proposed convergence roadmap.

Related documents:

- `platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker/WorkerRegistry.java`
- `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md`
- `platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/RedisWorkerRegistryKeyspace.java`
- `roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md`

## Purpose

The current worker-runtime Redis keyspace is controllable and currently has one
heavy worker slot aggregate:

```text
xa:mass:runtime:v1:worker:group:{groupId}:slots
```

However, this aggregate may be too heavy for the long-term worker lifecycle
model. It currently mixes worker metadata, candidate attributes, heartbeat
evidence, dispatch gates, reservation counts, active lease counts, exclusive
lease state, and removing tombstone into one encoded `WorkerSlot`.

This roadmap therefore sets a default direction: delete the heavy platform-side
`WorkerSlot` admission aggregate and demote the platform to candidate selection,
metadata/hard-gate filtering, route reachability, and dispatch offer handling.
Workers should perform final accept/reject admission unless a later proof shows
that a specific platform-side admission policy is required.

Only after that demotion path is defined should the Redis key shape be
optimized. Otherwise we risk spending effort sharding and tuning a heavyweight
snapshot that the mainline should delete.

The rest of the current keyspace also contains several derived indexes and
per-worker key families whose long-term cost is not yet proven. This roadmap
converges the worker runtime Redis shape for a million-worker design.

The goal is not to minimize Redis key count blindly. A single global worker hash
would also be wrong because it would create large hot keys, poor cleanup
boundaries, and cross-group contention. The goal is stricter:

- every key family must have a strong runtime reason,
- canonical truth must stay single-owner,
- indexes must be rebuildable or explicitly justified,
- hot-path reads must be bounded,
- mutation contention must be bounded by worker group and shard, not by the
  whole worker runtime,
- heavyweight platform-side worker resource snapshots are deletion candidates by
  default and must be justified before they are retained.

This project is not yet production-launched. When a new worker-runtime key
shape becomes the mainline, superseded internal key families, API methods,
tests, and docs should be removed in the same convergence track. Keeping old
shapes as compatibility residue makes future agents treat residue as truth.

## Current Code Observations

Current keyspace owner:

```text
platform_infra/mass-runtime-redis
  com.xa.mass.runtime.redis.RedisWorkerRegistry
  com.xa.mass.runtime.redis.RedisWorkerRegistryKeyspace
```

Current Redis worker key families:

| Key family | Type | Current purpose | Initial classification |
| --- | --- | --- | --- |
| `...:worker:group` | `HASH` | `workerId -> groupId` lookup for `slotByWorkerId(workerId)` | Bounded lookup projection; must be justified by worker-id-only callers |
| `...:groups` | `SET` | group discovery for cleanup | Group index; likely valid |
| `...:exclusive-leases` | `SET` | list/check exclusive lease workers | Diagnostic/global lookup candidate; duplicate of slot flag unless justified |
| `...:group:{groupId}:slots` | `HASH` | encoded `WorkerSlot` by worker id | Current platform-side worker admission/resource aggregate; default delete target |
| `...:group:{groupId}:heartbeat:0` | `ZSET` | heartbeat deadline by worker id | Bounded stale-worker index; valid but currently unsharded |
| `...:group:{groupId}:bucket:{candidateBucketKey}:workers` | `SET` | candidate source for group bucket | Candidate index; valid reason, but acquisition currently reads whole set |
| `...:group:{groupId}:buckets` | `SET` | bucket discovery for cleanup | Cleanup index; likely valid if bucket count is bounded |
| `...:group:{groupId}:node:{adapterNodeId}:bucket:{candidateBucketKey}:workers` | `SET` | node-scoped candidate source | Candidate index; valid reason, but acquisition cost must be bounded |
| `...:group:{groupId}:node-buckets` | `SET` | node bucket discovery for cleanup | Cleanup index; likely valid if bucket count is bounded |
| `...:group:{groupId}:worker:{workerId}:bucket-membership` | `SET` | reverse index from worker to bucket keys for cleanup | Suspect per-worker key family; strong replacement candidate |
| `...:task:{taskId}:active-workers` | `SET` | active worker ids per task | Suspect duplicate of `worker-active-count` hash fields |
| `...:task:{taskId}:worker-active-count` | `HASH` | active lease count by worker for a task | Conditional projection; keep only if WorkerRegistry task-worker occupancy survives WKRK-2.5/WKRK-3 |

Current runtime cost observations:

- Most `RedisWorkerRegistry` public methods are currently `synchronized`; key
  shape cleanup alone will not remove single-JVM serialization.
- `updateSlot(...)` uses `WATCH` / `MULTI` on the whole group slots hash, so a
  single shared connection and group-level key watch can create contention even
  when workers are unrelated.
- `cleanupRemovedSlots(groupId, limit)` currently reads all fields from the
  group slots hash before applying the limit in process.
- `cleanupExpiredHeartbeats(now, limit)` currently scans all groups and can
  read all expired workers for a group before applying the limit in process.
- `workerIdsByAdapterNodeGroup(adapterNodeId, groupId)` currently scans
  node-bucket discovery and materializes all matching node bucket members.

Current upper-layer boundary status:

- `WorkerRegistry` now exposes worker-id semantic operations such as
  `workerMeta(workerId)`, `workerAdmissionSnapshot(workerId)`,
  `tryReserve(workerId, ...)`, `disableDispatch(workerId, source)`,
  `markWorkersRemovingByGroup(...)`, and
  `disableDispatchForAdapterNodeGroup(...)`.
- `xa-mass-worker-runtime/src/main` and `xa-mass-engine/src/main` no longer call
  `slotByWorkerId(...)`, `markSlotRemoving(...)`, `workerIdsByGroupId(...)`, or
  `workerIdsByAdapterNodeGroup(...)` directly.
- `EngineSchedulingCoreArchitectureGuardTest` guards this boundary so future
  production callers consume semantic operations instead of physical slot/index
  operations.
- The old slot/group APIs still exist inside the shared runtime SPI and
  implementations. They are current implementation/contract surfaces, not a
  target upper-layer API.

Local Redis snapshot on 2026-06-10 showed the same shape:

```text
102 group:{groupId}:worker:{workerId}:bucket-membership keys
  4 group:{groupId}:bucket:{bucketKey}:workers keys
  4 group:{groupId}:node:{nodeId}:bucket:{bucketKey}:workers keys
  3 group:{groupId}:slots keys
  3 group:{groupId}:heartbeat:0 keys
  1 worker:group key
  1 groups key
```

This snapshot is not production proof. It is useful because it shows the first
obvious scaling pressure: the reverse bucket membership shape creates one Redis
key per worker, while the canonical slot and heartbeat shapes create one key per
group.

## Owner Review

Worker runtime state belongs to the `WorkerRegistry` SPI in
`platform_infra/mass-runtime-api`.

`platform_infra/mass-runtime-memory` and `platform_infra/mass-runtime-redis` are
implementations of that SPI. Memory and Redis must remain behaviorally aligned
through the API contract and contract tests; neither implementation should force
its physical storage shape into upper-layer callers.

Infra runtime physical data structures and upper-layer runtime semantics should
be separated wherever practical:

- Upper layers should request semantic operations such as candidate acquisition,
  worker metadata lookup, hard-gate update, and offer/reject handling.
- Upper layers should not care whether Redis stores those facts in group hashes,
  sharded worker hashes, sets, zsets, streams, or Lua-maintained aggregates.
- Redis implementation details can change slice by slice as long as the
  `WorkerRegistry` / worker-runtime contract remains stable.

`xa-mass-worker-runtime` may consume worker runtime facts through the
`WorkerRegistry` API, but it must not define Redis key structure or depend on
Redis-only projections.

Engine scheduling may consume worker records and reserve/admission outcomes
through worker-runtime contracts, but it must not read Redis worker keys or
transport presence keys directly.

Transport presence is adjacent runtime state, not worker slot truth. The
canonical transport lookup is route-owner by canonical route key:

```text
workerGroupId + workerId
  -> CanonicalWorkerRouteKeyCodec.encode(...)
  -> xa:mass:transport:presence:v1:owner:{shard}
```

Worker-id-only transport projections such as
`xa:mass:transport:presence:v1:worker-route:{workerId}` and
`xa:mass:transport:presence:v1:workers` must not be copied into this roadmap as
retained runtime primitives. They are deletion candidates, not sharding
candidates, because engine/worker-runtime already owns `workerGroupId +
workerId` before reachability lookup.

## Cross-Layer Worker Lifecycle Key Ownership

The complete worker lifecycle crosses three runtime owners:

```text
Worker declaration / metadata
  -> WorkerRegistry metadata and candidate buckets
  -> transport presence route owner
  -> WorkerReachabilityView
  -> engine selection / dispatch offer binding
  -> transport route selection / delivery
  -> worker accept/reject
  -> result / expiry release
```

Key ownership:

| Lifecycle fact | Owner | Redis key family | Scheduling role |
| --- | --- | --- | --- |
| Worker declaration, group, attributes, capacity hint, hard gates | WorkerRegistry | target `worker:meta:{shard}` or equivalent semantic store | Lightweight metadata/filter truth |
| Platform-side worker occupancy/reservation | Default none | current `...:slots` fields | Delete unless a specific policy proves it is required |
| Candidate source | WorkerRegistry | `...:bucket:*:workers`, `...:node:*:bucket:*:workers` | Derived bounded candidate index |
| Worker-registry heartbeat/stale evidence | WorkerRegistry | `...:heartbeat:{shard}` | Registry-local stale/diagnostic evidence |
| Network reachability / route owner | Transport presence | `xa:mass:transport:presence:v1:owner:{shard}` | Canonical delivery reachability truth |
| Worker-id route projection | None in mainline | `...:worker-route:{workerId}` | Delete; do not use for scheduling |
| Online worker list projection | None in mainline | `...:workers` | Delete or operator-only replacement outside hot path |
| Task active worker occupancy | Review target | `...:task:{taskId}:worker-active-count` | Keep only if a proven policy needs task-worker occupancy outside TaskWorkRuntime |

Important distinction:

- Transport presence answers "can the selected worker currently be reached and
  where should transport deliver?"
- WorkerRegistry answers "what current worker metadata, hard gates, and
  candidate indexes are known before dispatch offer?"
- Worker-side accept/reject answers "can this worker actually accept this work
  now?"
- `WorkerSlot.meta.diagnosticStatus` is display/diagnostic evidence only. Local
  samples showed fresh slot heartbeat with `diagnosticStatus=OFFLINE`, so it
  must not be used as scheduling truth.

## WorkerSlot Deletion Decision

Before optimizing the `slots` hash, this roadmap assumes the heavy slot
aggregate should be deleted. The only reason to retain it is a later proof that
platform-side worker admission/reservation is required for a named policy.

Current heavy slot fields:

| Field group | Current owner | Review direction |
| --- | --- | --- |
| worker id, group id, adapter node, adapter id | WorkerRegistry declaration/runtime meta | Keep as lightweight worker meta |
| worker attributes and route attributes | WorkerRegistry candidate/filter meta | Keep, but update path should not rewrite occupancy facts |
| declared capacity | Worker declaration / worker report | Keep as hint or hard cap only if platform admission remains |
| event binding ceiling | Worker capability report validation | Keep only if still needed for validation/diagnostics |
| dispatch disabled sources | WorkerRegistry hard gate | Keep as optional platform hard gate |
| removing tombstone | WorkerRegistry cleanup/lifecycle | Keep, but consider dedicated cleanup index |
| last heartbeat millis | WorkerRegistry projection | Review; transport presence owns reachability |
| diagnostic status | Diagnostic/read model | Demote to display-only or remove from hot aggregate |
| reserved count | Platform-side admission | Delete/demote if worker-side accept/reject owns final admission |
| active lease count | Platform-side occupancy | Delete/demote if TaskWorkRuntime plus worker ack/reject owns execution lifecycle |
| active lease count by task | Platform-side occupancy projection | Delete/demote unless a proven policy needs it |
| exclusive lease flag | Platform-side strict resource mode | Delete/demote unless explicit policy keeps platform exclusivity |

Default target model:

Model B: worker-side accept/reject owns final admission.

```text
candidate worker
  -> read lightweight worker meta / hard gates
  -> route owner reachable
  -> dispatch offer
  -> worker ACCEPTED / BUSY / CAPACITY_FULL / DEVICE_NOT_READY / DRAINING
  -> engine retries or records active execution after accept
```

This model accepts short-lived stale candidates and possible extra dispatch
round trips, but it removes heavyweight platform-side worker occupancy truth and
matches the eventual-consistency direction.

Exception model:

Model A: platform-side admission remains for a named policy.

```text
candidate worker
  -> read WorkerSlot
  -> platform reserve/admit
  -> dispatch
  -> result/expiry release
```

This exception reduces invalid dispatches but keeps `WorkerSlot` as a mutable
resource aggregate and forces Redis/memory runtimes to own reservation
correctness. It must name the policy that requires this tradeoff.

The target Redis shape should move toward a semantic worker metadata store such
as:

```text
xa:mass:runtime:v1:worker:meta:{shard}
  HASH
  field = workerId
  value = lightweight WorkerRuntimeMeta
```

Stage-2 can then do:

```text
workerId -> shard(workerId) -> HGET worker:meta:{shard} workerId
  -> verify meta.groupId is in selected group universe
  -> apply attribute/hard-gate filters
  -> check transport currentOwner(routeKey)
  -> dispatch offer
```

The first implementation slice may temporarily keep the existing `slots` key
name while demoting fields, but active docs and tests must stop describing it as
long-term canonical admission truth.

## Boundary Decision

Treat `group:{groupId}:slots` as the current platform-side worker aggregate,
not as a target shape. The target is deletion/demotion to lightweight worker
metadata unless WKRK-0 records a specific policy that requires platform-side
admission/reservation.

Derived worker-runtime key families are allowed only when they satisfy one of
these reasons:

1. bounded candidate acquisition without scanning slots,
2. bounded stale-worker discovery,
3. atomic mutation or release compensation that cannot be derived safely,
4. bounded worker-id-only lookup required by an active owner API,
5. bounded cleanup of a larger index,
6. bounded diagnostic query that cannot be implemented from canonical state
   without scanning an unbounded namespace.

If a key family does not satisfy one of those reasons, it should be removed or
folded into a lower-cardinality structure.

API shape is part of the boundary decision. If an upper-layer caller needs a
different access pattern, first decide whether the `WorkerRegistry` SPI should
expose it. Do not add Redis-only helper methods and then let upper layers grow
around the implementation detail.

## Hard Rules

1. Do not replace all worker runtime state with one global worker hash.
2. Do not introduce writable `worker:meta:{workerId}`,
   `worker:occupancy:{workerId}`, or `worker:available:{shard}` beside a still
   active `group:{groupId}:slots` admission aggregate. A lightweight worker meta
   shape may replace the slot aggregate only after WKRK-0 selects worker-side
   accept/reject or another explicit demotion model.
3. Do not keep a per-worker Redis key family unless it has a named hot-path or
   cleanup reason that cannot be satisfied by a bounded worker-metadata shape
   or a group-scoped index.
4. Do not use key count alone as proof. A low key count with unbounded
   `SMEMBERS`, `HGETALL`, `HKEYS`, or group-wide `WATCH` contention is not
   scalable.
5. Do not add compatibility dual writes for old and new Redis shapes. This
   repo is pre-release; use clean runtime recreation for key-shape replacement
   unless a later decision explicitly requires live migration.
6. Once a new key family becomes mainline, remove the superseded internal key
   family, keyspace method, tests, docs, and roadmap wording in the same track.
   Do not leave aliases, fallback readers, or "temporary" compatibility paths
   inside the repo.
7. Redis physical shape must not leak above `platform_infra/mass-runtime-redis`.
   Upper layers talk to `platform_infra/mass-runtime-api`; memory and Redis
   implementations prove parity through the shared contract.
8. Candidate buckets may be stale. Correctness must come from Stage-2
   metadata/hard-gate validation plus worker-side accept/reject. Platform-side
   reserve/admission is allowed only for an accepted WKRK-0 exception.
9. A projection is not truth. Any projection that can accept admission, reserve,
   release, or capacity decisions without re-reading current worker metadata or
   going through worker-side accept/reject becomes a duplicate truth bug.
10. Transport presence must remain separate from worker runtime. Transport
   route ownership answers "where can this matched worker be delivered";
   WorkerRegistry answers "what worker metadata and hard gates are known before
   dispatch offer".
11. Redis field TTL is an optional cleanup optimization, not a boundary
   decision.
12. Every slice must leave runtime behavior compiling and must not require a
    later slice to restore scheduling correctness.
13. Redis key-shape convergence must be reviewed together with Redis
    connection, transaction, and locking boundaries. A lower key count with one
    JVM-wide monitor or one shared connection transaction bottleneck is not a
    production-ready shape.
14. Bounded cleanup means Redis-side bounded reads, not reading a full key and
    enforcing `limit` only inside Java.
15. If Redis Cluster is in scope for a key-shape change, hash-tag placement must
    be decided before multi-key Lua or transaction boundaries land. If Redis
    Cluster is out of scope for this roadmap, state that explicitly.
16. Do not preserve transport `worker-route:{workerId}` as a sharded hash or
    replacement projection. The mainline already has `workerGroupId + workerId`
    and must compute canonical route key directly.
17. `WorkerPresenceStore#getPresence(workerId)`, `isWorkerOnline(workerId)`,
    `findOwners(workerId)`, and `listActivePresences()` must not be production
    scheduling or dispatch-route inputs. Retarget callers to route-key-first
    lookup or demote the APIs to bounded operator/support surfaces before
    deleting their Redis projections.
18. Worker-registry heartbeat and transport presence are not interchangeable.
    If both gates remain, their names and tests must prove distinct semantics:
    registry stale/admission evidence versus transport reachability.
19. Do not optimize `WorkerSlot` sharding, WATCH contention, or occupancy
    indexes before deciding whether platform-side worker admission remains.
20. If worker-side accept/reject becomes final admission, platform
    `reservedCount`, `activeLeaseCount`, `activeLeaseCountByTask`, and
    `exclusiveLeaseHeld` must be demoted or deleted rather than reimplemented in
    a new key family.
21. Do not expose Redis physical key shapes above the infra runtime boundary.
    Upper layers should consume semantic operations, not key families.

## Do Not Start With

Do not start by deleting `worker:group`, `bucket-membership`, or
`active-workers` keys.

Do not start by changing only key names. The first implementation slice must
know whether the target operation is limited by key cardinality, full-key reads,
group-level `WATCH` contention, shared Redis connection state, or JVM-level
serialization. Otherwise the roadmap can reduce visible Redis keys while
leaving the real hot-path bottleneck unchanged.

Do not start by sharding `group:{groupId}:slots` before reviewing whether the
slot aggregate should survive as a heavy admission object. Sharding a model that
will be deleted is wasted complexity.

Start by proving caller needs, key cardinality, and hot-path cost. Then replace
one key family at a time with a single-writer shape. Deleting first would create
hidden full scans or force compatibility bridges back into the implementation.
After a replacement is accepted, delete the old residue rather than preserving
both tracks.

## Target Key Budget

Target cardinality is expressed in Redis keys, not entries:

| Category | Preferred key cardinality | Notes |
| --- | --- | --- |
| current worker slots | `0` target unless platform admission exception is proven | Delete/demote heavy `WorkerSlot` aggregate |
| lightweight worker metadata | `O(metaShard)` or `O(group * metaShard)` | Target semantic meta store; physical shape hidden behind WorkerRegistry |
| heartbeat deadlines | `O(group * heartbeatShard)` | Entries are `O(workers)`; supports bounded expiry scans |
| candidate buckets | `O(group * bucket * shard)` plus optional node dimension | Must support bounded sampling; no full bucket reads on large sets |
| reverse bucket membership | `O(group * membershipShard)` | Prefer hash fields by worker id over one key per worker |
| worker id to group lookup | `O(1)` global hash or `O(shard)` hashes | Keep only if worker-id-only owner APIs remain |
| removing-slot cleanup | `O(group * removingShard)` if needed | Needed if cleanup cannot scan group slots hash |
| task active worker counts | `0` target unless a policy proves WorkerRegistry occupancy is required; otherwise `O(activeTask)` one-hash projection | TaskWorkRuntime/offer-accept evidence should own lifecycle first |
| transport route owner | `O(routeShard)` | Canonical transport reachability by routeKey |
| transport worker-id route projection | `0` target mainline keys | Delete; do not replace with sharded hash |
| diagnostics | explicitly bounded | Diagnostic indexes cannot become admission truth |

The target allows more than one key per group or bucket when it prevents hot
keys or full scans. It rejects one key per worker unless the key family has a
stronger reason than convenience.

## WKRK-0: WorkerSlot Deletion And Admission Ownership Review

Goal:

Converge the default target away from strong platform-side
reservation/admission truth and toward worker-side accept/reject.

Scope:

- Inventory every `WorkerSlot` field and classify it as:
  - declaration/meta,
  - candidate/filter input,
  - platform hard gate,
  - platform admission/occupancy,
  - task-runtime-derived projection,
  - transport reachability duplicate,
  - diagnostic-only.
- Inventory every production call path that mutates or reads:
  - `reservedCount`,
  - `activeLeaseCount`,
  - `activeLeaseCountByTask`,
  - `exclusiveLeaseHeld`,
  - `lastHeartbeatMillis`,
  - `diagnosticStatus`.
- Define the worker-side offer protocol needed to delete platform occupancy:
  - `ACCEPTED`,
  - `BUSY`,
  - `CAPACITY_FULL`,
  - `DEVICE_NOT_READY`,
  - `ATTRIBUTE_MISMATCH`,
  - `DRAINING`,
  - timeout/no-ack.
- Define how rejected offers return task work to ready/delayed state and how
  retry/backoff avoids hot-loop dispatch to busy workers.
- Define which existing Stage-2 gates remain platform hard gates:
  - group membership,
  - adapter node/group binding,
  - explicit dispatch disable,
  - removing tombstone,
  - transport reachability.
- Define the exception bar for keeping platform-side admission:
  - named policy,
  - why worker-side reject is insufficient,
  - expected dispatch rejection cost,
  - required Redis/memory proof.

Acceptance:

- The roadmap records worker-side accept/reject as the default final-admission
  target before slot sharding or occupancy key-shape work begins.
- Any platform-side admission exception names the policy that requires it and
  why worker-side reject/backoff is insufficient.
- `WorkerSlot` occupancy fields become delete/demotion candidates and
  subsequent WKRK slices retarget to lightweight worker meta unless an exception
  is accepted.
- No implementation change is made in this slice.

Suggested checks:

```powershell
rg -n "reservedCount|activeLeaseCount|activeLeaseCountByTask|exclusiveLeaseHeld|tryReserve|confirmReservation|releaseReservation|recordWorkFinal|recordWorkClaimed" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
rg -n "WorkerSlot|WorkerMeta|diagnosticStatus|lastHeartbeatMillis" platform_infra xa-mass-worker-runtime xa-mass-engine sdk --glob '!**/target/**'
```

## WKRK-1: Key Family Inventory And Budget

Goal:

Build a code-grounded inventory before changing key shape.

Scope:

- Inventory every `RedisWorkerRegistryKeyspace` method.
- Inventory matching `WorkerRegistry` SPI methods and distinguish API contract
  from Redis-only physical shape.
- Inventory adjacent transport presence keys that participate in worker
  lifecycle:
  - `owner:{shard}`,
  - `deadline:{shard}`,
  - `owner-shards`,
  - `worker-route:{workerId}`,
  - `workers`.
- For each key family, record:
  - owner method that writes it,
  - production methods that read it,
  - Redis type,
  - cardinality formula,
  - whether it is canonical truth or derived index,
  - whether it can be rebuilt from lightweight worker meta, or from current
    `WorkerSlot` only during transition,
  - whether it is used in scheduling/admission or diagnostics only.
- Include current live Redis scan as evidence, but do not treat local key count
  as production proof.
- Record current hot-path costs:
  - `acquireCandidates(...)` reads full candidate buckets with `SMEMBERS` and
    sorts the whole list before sampling.
  - `workerIdsByAdapterNodeGroup(...)` scans node-bucket discovery and
    materializes node bucket sets.
  - `cleanupRemovedSlots(...)` reads all worker ids from `group:{groupId}:slots`
    before applying the cleanup limit.
  - `cleanupExpiredHeartbeats(...)` can read all expired heartbeat members for a
    group before applying the cleanup limit.
  - `updateSlot(...)` watches the whole group `slots` hash, so unrelated worker
    mutations in the same group can conflict.
- Record current serialization costs:
  - public synchronized methods,
  - synchronized `updateSlot(...)`,
  - single shared Redis connection transaction state,
  - group-key `WATCH` retry behavior.
- Decide whether Redis Cluster is out of scope. If it is in scope, record the
  hash-tag strategy before any key rename or Lua boundary is implemented.

Acceptance:

- A sibling inventory, `WORKER_RUNTIME_REDIS_KEY_SHAPE_INVENTORY.md`, exists
  or this roadmap has been updated with an equivalent table.
- Every key family has a classification:
  `canonical`, `bounded-index`, `cleanup-index`, `diagnostic-index`,
  `duplicate-candidate`, or `remove-candidate`.
- Every `WorkerRegistry` API method used by upper layers is mapped to either a
  storage-independent contract or a retarget/removal decision.
- The inventory explicitly names whether a key family is allowed to be
  `O(worker keys)`.
- Every full-key read on a mainline or cleanup path is classified as retained,
  replaced, or bounded by a follow-up slice.
- Every JVM monitor / Redis connection / transaction bottleneck is named with
  either a removal plan or a retained reason.
- Redis Cluster is explicitly in or out of scope for this roadmap.
- Transport `worker-route:{workerId}` and `workers` are classified as delete
  candidates unless a bounded operator-only replacement is explicitly approved.
- The inventory includes a sample proving `diagnosticStatus` is not scheduling
  truth and cannot override transport reachability or WorkerRegistry gates.
- No implementation change is made in this slice.

Suggested checks:

```powershell
rg -n "RedisWorkerRegistryKeyspace|workerBucketMembershipSet|taskActiveWorkersSet|groupSlotsHash|groupHeartbeatDeadlinesZset|groupCandidateBucket|nodeCandidateBucket" platform_infra/mass-runtime-redis/src/main/java
rg -n "interface WorkerRegistry|workerMeta|workerAdmissionSnapshot|markWorkersRemovingByGroup|disableDispatchForAdapterNodeGroup|slotByWorkerId|workerIdsByGroupId|activeWorkerIdsByTask|activeWorkerCountForTask|exclusiveLeaseWorkerIds|acquireCandidates" platform_infra/mass-runtime-api platform_infra/mass-runtime-memory platform_infra/mass-runtime-redis
rg -n "slotByWorkerId|markSlotRemoving|workerIdsByGroupId|workerIdsByAdapterNodeGroup" xa-mass-worker-runtime/src/main xa-mass-engine/src/main --glob '!**/target/**'
rg -n "synchronized|watch\\(|smembers\\(|hkeys\\(|zrangebyscore\\(" platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/RedisWorkerRegistry.java
rg -n "worker-route|workersKey\\(|getPresence\\(|isWorkerOnline\\(|findOwners\\(|listActivePresences\\(|currentOwner\\(" transport sdk xa-mass-engine xa-mass-worker-runtime --glob '!**/target/**'
```

## WKRK-1.5: Redis Concurrency And Bounded Read Budget

Goal:

Separate key-shape cleanup from concurrency and bounded-read correctness, so a
smaller keyspace does not hide a shared lock, shared connection transaction, or
full-key read bottleneck.

Scope:

- Classify every `synchronized` method in `RedisWorkerRegistry`:
  - connection-safety guard,
  - mutation atomicity guard,
  - convenience serialization,
  - removable after Lua/per-operation connection change.
- Decide first-slice Redis mutation discipline:
  - keep `synchronized` temporarily and document it as a known bottleneck,
  - use a per-operation connection for `WATCH` / `MULTI`,
  - replace small multi-key slot mutations with Lua,
  - or another explicit approach.
- Classify bounded-read requirements for:
  - candidate acquisition,
  - node-group worker lookup,
  - heartbeat expiry cleanup,
  - removing-slot cleanup,
  - stale bucket cleanup.
- Forbid "read all, then limit in Java" on paths that claim to be bounded.
- Define which proof must use independent Redis clients to avoid hiding
  connection-scoped transaction problems.

Acceptance:

- The roadmap has a table of runtime operations with:
  - Redis commands used,
  - bounded-read status,
  - mutation atomicity boundary,
  - current JVM lock involvement,
  - target lock/connection model.
- Any retained `synchronized` method has a named reason and a removal trigger.
- Shared Redis connection `WATCH` / `MULTI` is either explicitly retained as a
  short-term bottleneck or replaced by a safer first-slice discipline.
- Contract/concurrency tests identify which cases require independent Redis
  clients.

Suggested checks:

```powershell
rg -n "public synchronized|private synchronized|watch\\(|multi\\(|exec\\(" platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/RedisWorkerRegistry.java
rg -n "independent|RedisClient|StatefulRedisConnection|concurrent" platform_infra/mass-runtime-redis/src/test platform_infra/mass-runtime-api/src/test
```

## WKRK-2: Worker-Id Lookup Contract

Goal:

Keep worker-id lookup as a semantic runtime operation while deciding whether the
underlying `worker:group` projection remains justified.

Scope:

- Treat the current semantic boundary as the baseline:
  - upper runtime callers use `workerMeta(workerId)`,
    `workerAdmissionSnapshot(workerId)`, worker-id admission methods, and
    node/group bulk gate methods,
  - upper runtime callers do not call `slotByWorkerId(...)`,
    `markSlotRemoving(...)`, `workerIdsByGroupId(...)`, or
    `workerIdsByAdapterNodeGroup(...)`.
- Audit remaining `slotByWorkerId(workerId)` callers inside
  `platform_infra/mass-runtime-api`, `mass-runtime-memory`, and
  `mass-runtime-redis`.
- Classify each remaining caller as:
  - implementation lookup,
  - contract test,
  - cleanup/diagnostic,
  - platform-admission exception,
  - remove target.
- If `worker:group` remains, document it as a WorkerRegistry-owned lookup
  projection and prove that dispatch still re-reads current worker metadata and
  reaches worker-side accept/reject, or an accepted platform-admission exception.
- Record write frequency for `worker:group`. If frequent worker heartbeat,
  state, capability, or metadata updates flow through `upsertSlot(...)`, this
  global hash can become a write hotspot even when read use is justified.
- Define the old-group source needed by group-change cleanup. WKRK-4 cannot
  remove or replace reverse bucket membership safely unless the old group can be
  resolved without a hidden namespace scan.

Acceptance:

- `worker:group` has either a retained reason or a replacement plan.
- No path accepts scheduling or capacity decisions from `worker:group` alone.
- Engine and worker-runtime production callers remain on semantic
  `WorkerRegistry` methods; the architecture guard stays green.
- Worker-id lookup rejects group mismatch by reading current worker metadata.
  During a platform-admission exception, `tryReserve(groupId, workerId, ...)`
  must also reject group mismatch by reading the target group slot/meta entry.
- Any retained worker-id-only API is documented as lookup convenience, not
  canonical worker membership truth.
- Any retained global `worker:group` write path has a bounded write-frequency
  reason, or the roadmap defines a sharded lookup projection.
- Group-change cleanup has a concrete old-group source before any dependent
  bucket-membership key family is removed.

Suggested checks:

```powershell
rg -n "\\.slotByWorkerId\\(|workerGroupHash\\(|worker:group" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
.\mvnw.cmd -pl xa-mass-engine -am -Dtest=EngineSchedulingCoreArchitectureGuardTest test
```

## WKRK-2.5: Worker-Side Offer Accept/Reject Runtime

Goal:

Land the replacement correctness loop before deleting platform-side occupancy.

Why this exists:

This roadmap defaults to worker-side final admission. That cannot stay as a
documentation-only decision. Before `reservedCount`, `activeLeaseCount`,
`activeLeaseCountByTask`, or `exclusiveLeaseHeld` are removed from the worker
runtime, the dispatch path must have an explicit offer outcome and recovery
model.

Scope:

- Define a worker offer outcome contract owned by transport/worker runtime, not
  Redis key shape:
  - `ACCEPTED`,
  - `BUSY`,
  - `CAPACITY_FULL`,
  - `DEVICE_NOT_READY`,
  - `ATTRIBUTE_MISMATCH`,
  - `DRAINING`,
  - timeout/no-ack.
- Decide where `ACCEPTED` becomes active execution evidence:
  - `TaskWorkRuntime` lease/claim evidence,
  - transport dispatch binding,
  - trace event,
  - or a small worker-runtime semantic projection if a proven policy needs it.
- Define rejection handling:
  - release or avoid platform reservation if the transitional path still uses it,
  - return work to ready/delayed state,
  - bounded backoff so busy workers do not hot-loop,
  - stale candidate marking when rejection implies stale metadata.
- Define timeout handling for no-ack dispatch offer.
- Define how worker-side reject interacts with retry, pause, resume, terminal,
  and result finality.
- Define black-box proof with an external worker that deliberately returns
  `BUSY` / `CAPACITY_FULL` before later accepting.
- Keep policy separate:
  - retry delay/backoff is policy,
  - offer outcome recording and work return are mechanism.

Acceptance:

- A task can be dispatched to a candidate worker, receive worker-side `BUSY` or
  `CAPACITY_FULL`, return work to competition, and later complete on another or
  the same worker without duplicate final result.
- `ACCEPTED` is recorded before active execution is counted by any retained
  worker-runtime occupancy projection.
- No rejected offer leaves a stuck active lease or invisible in-flight item.
- Timeout/no-ack follows the same recovery path as explicit rejection.
- Trace or proof output distinguishes:
  - candidate selected,
  - offer sent,
  - worker accepted/rejected/timed out,
  - work requeued or finalized.
- Only after this phase is proven may WKRK-3 delete platform-side occupancy
  fields.

Suggested verification:

```powershell
rg -n "ACCEPTED|CAPACITY_FULL|DEVICE_NOT_READY|ATTRIBUTE_MISMATCH|DRAINING|offer" xa-mass-engine xa-mass-worker-runtime transport sdk --glob '!**/target/**'
.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime,transport/transport_runtime -am test
```

## WKRK-3: Per-Task Occupancy Projection Convergence

Goal:

Decide whether task-worker occupancy remains in WorkerRegistry at all. If a
platform-side occupancy exception is retained, remove redundant per-task
projection so only one task-worker active view remains.

Current issue:

`task:{taskId}:active-workers` and `task:{taskId}:worker-active-count` both
encode the active worker set. The hash already has worker ids as fields and
counts as values. But if WKRK-2.5 makes worker-side accept/reject the final
admission path and TaskWorkRuntime owns active execution evidence, both
WorkerRegistry task-worker occupancy projections may become delete candidates.

Conditional target A, only if WorkerRegistry task-worker occupancy remains:

```text
xa:mass:runtime:v1:worker:task:{taskId}:worker-active-count
  HASH
  field = workerId
  value = active lease count
```

Scope:

- Depend on WKRK-2.5.
- First decide whether any named policy still needs WorkerRegistry task-worker
  active counts outside TaskWorkRuntime and trace.
- If no policy needs it, remove both:
  - `task:{taskId}:active-workers`,
  - `task:{taskId}:worker-active-count`.
- If a policy needs it, keep exactly one projection and document:
  - policy owner,
  - update owner,
  - failure/cleanup behavior,
  - why TaskWorkRuntime/trace is insufficient.
- Replace `activeWorkerIdsByTask(taskId)` with hash field lookup if acceptable.
- Replace `activeWorkerCountForTask(taskId)` with hash length if acceptable.
- Remove writes to `task:{taskId}:active-workers`.
- Remove the keyspace method, tests, docs, and residue scans after all callers
  are moved. Do not leave an unused compatibility method.
- If retained, keep task-worker active counts as a WorkerRegistry projection, not
  task lifecycle truth.

Acceptance:

- The phase records one of two decisions:
  - delete WorkerRegistry task-worker occupancy after WKRK-2.5 proves worker-side
    accept/reject and TaskWorkRuntime active evidence are sufficient,
  - or retain one explicitly justified projection for a named policy.
- Clean runtime no longer creates `...:task:{taskId}:active-workers`.
- If `worker-active-count` remains, `WorkerRegistryContractTest` still proves
  active worker ids and counts.
- If `worker-active-count` is deleted, contract tests and production callers are
  moved to the replacement semantic view.
- No second active-worker structure exists for the same task-worker fact.

Suggested verification:

```powershell
.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am -Dtest=WorkerRegistryContractTest,RedisWorkerRegistryTest test
rg -n "taskActiveWorkersSet|active-workers" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
```

## WKRK-4: Reverse Bucket Membership Shape Convergence

Goal:

Remove or replace the per-worker reverse bucket membership key family.

Current issue:

```text
group:{groupId}:worker:{workerId}:bucket-membership
```

creates one Redis key per worker. It exists so `removeFromBuckets(groupId,
workerId)` can remove a worker from all candidate buckets without scanning all
bucket sets. That is a valid cleanup need, but the physical shape is expensive
at million-worker scale.

Target candidates:

Option A:

```text
group:{groupId}:bucket-membership:{membershipShard}
  HASH
  field = workerId
  value = encoded bucket keys
```

Option B, only if WKRK-0 keeps the slot aggregate:

```text
group:{groupId}:slots:{slotShard}
  HASH
  field = workerId
  value = WorkerSlot plus derived bucket membership
```

Option A is the safer first target because it keeps candidate-index cleanup
separate from worker meta/slot payload while reducing key count from
`O(workers)` to `O(group * membershipShard)`. If WKRK-0 selects worker-side
accept/reject, do not fold membership into `WorkerSlot`; retarget cleanup around
lightweight worker meta instead.

Scope:

- Depend on WKRK-2's old-group lookup decision. Do not remove
  `worker:group` or the old reverse-membership shape until group-move cleanup
  has an explicit old-group source.
- Decide membership shard count and encoding.
- Retarget `addToBuckets` and `removeFromBuckets`.
- Preserve bounded removal on:
  - worker group move,
  - `markSlotRemoving`,
  - heartbeat expiry cleanup,
  - stale candidate removal,
  - clean removed-slot cleanup.
- Use clean runtime recreation. Do not dual-write old per-worker keys and new
  membership hashes.
- Remove the old per-worker keyspace method and tests after the new membership
  shape is accepted.

Acceptance:

- Clean runtime no longer creates
  `group:{groupId}:worker:{workerId}:bucket-membership`.
- Worker removal or group move removes old group and node bucket membership
  without scanning every bucket in the group.
- Candidate acquisition still returns the same worker universe after upsert.
- Stale bucket cleanup remains bounded.
- Tests include at least one worker with multiple candidate bucket keys and one
  node-scoped bucket.
- Group-change cleanup removes membership from the previous group without a
  Redis namespace scan.

Suggested verification:

```powershell
.\mvnw.cmd -pl platform_infra/mass-runtime-redis -am -Dtest=RedisWorkerRegistryTest test
.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am -Dtest=WorkerRegistryContractTest test
rg -n "workerBucketMembershipSet|bucket-membership" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
```

## WKRK-5: Candidate Acquisition Must Be Bounded

Goal:

Make candidate buckets scalable beyond small local sample sizes.

Current issue:

`RedisWorkerRegistry.acquireCandidates(...)` currently reads the whole candidate
bucket with `SMEMBERS`, sorts all worker ids, and only then samples. This is not
million-worker-ready even if key count is low.

Node-group bulk gate operations currently delegate to registry-side worker-id
fanout. Even though upper runtime callers now use semantic operations such as
`disableDispatchForAdapterNodeGroup(...)`, the Redis implementation must still
treat the underlying node-group fanout as a mainline mutation cost until it is
bounded, async, or explicitly classified as support-only.

Target:

- Candidate acquisition must not load the full bucket when only
  `maxCandidateCount` candidates are needed.
- The sampling algorithm must remain deterministic enough for tests or expose
  a test seam that does not change production owner boundaries.

Possible approaches:

- `SRANDMEMBER key count` for unordered random sampling,
- `SSCAN` bounded page sampling with cursor state if fairness requires it,
- sharded candidate sets plus bounded shard sampling,
- sorted sets only if ordering has a real runtime purpose.

Scope:

- Decide candidate-bucket physical type and sampling rule.
- Define the relationship between Redis-side bounded acquisition and
  `WorkerCandidateSamplingPolicy`:
  - either the policy is injected into the registry and controls Redis
    acquisition shape,
  - or Redis returns a bounded raw sample and upper-layer ranking handles
    ordering after Stage-2 validation.
- Keep Stage-2 metadata/hard-gate validation and worker-side accept/reject as
  correctness gates. Redis-side bounded acquisition is only candidate sourcing.
- Preserve node-scoped candidate buckets.
- Decide whether node-group bulk gate operations are:
  - bounded synchronous mainline operations,
  - async/repair-style semantic operations,
  - or support-only operations that cannot sit on hot mutation paths.
- Decide whether the lower-level `workerIdsByAdapterNodeGroup(...)` method
  remains only as implementation/contract surface or is removed after semantic
  bulk methods are fully implemented.
- Record why the chosen approach is compatible with fairness, warm hints, and
  runtime selection ranking.

Acceptance:

- `acquireCandidates(groupId, bucketKey, max)` performs bounded Redis reads.
- `acquireCandidates(groupId, adapterNodeId, bucketKey, max)` performs bounded
  Redis reads.
- No production path uses `SMEMBERS` on a candidate bucket as the normal
  acquisition path.
- Tests prove that a large synthetic bucket does not require materializing all
  workers in process memory.
- Node-scoped acquisition and semantic node-group bulk operations do not
  materialize all workers under an adapter node / group pair unless the operation
  is explicitly classified as support-only.
- Memory and Redis implementations expose the same registry contract:
  - bounded candidate universe,
  - source guard correctness,
  - deterministic test seam when needed.
  They do not need identical candidate ordering when Redis samples before
  upper-layer ranking.

Suggested checks:

```powershell
rg -n "smembers\\(bucketKey\\)|groupCandidateBucket\\(|nodeCandidateBucket\\(" platform_infra/mass-runtime-redis/src/main/java
```

## WKRK-6: Worker Meta And Heartbeat Sharding Decision

Goal:

Decide how lightweight worker meta and `heartbeat:0` keys should be sharded for
million-worker groups after the heavy slot aggregate is deleted/demoted.

Current issue:

`group:{groupId}:slots` is group-local, which is better than a global hash.
However, if platform-side admission remains, `updateSlot(...)` watches the
whole group `slots` hash. In Redis, `WATCH` is key-level, so two unrelated
worker updates in the same large group can conflict.

The sharding decision should target metadata write/read patterns rather than
platform occupancy mutation. Slot sharding is only allowed if WKRK-0 accepts a
platform-side admission exception.

Current heartbeat shape also uses a fixed `heartbeat:0` suffix, which implies a
future shard dimension but currently creates one zset per group.

Removed-slot cleanup currently has no dedicated index and can read every worker
id from the group slots hash before enforcing its limit.

Scope:

- Depend on WKRK-0 admission deletion/exception decision.
- Measure or model expected max workers per group.
- Decide whether to introduce:
  - `worker:meta:{metaShard}` or `group:{groupId}:workers:{metaShard}`,
  - `group:{groupId}:slots:{slotShard}` only for an accepted platform-admission
    exception,
  - `group:{groupId}:heartbeat:{heartbeatShard}`,
  - `group:{groupId}:removing:{removingShard}`,
  - matching cleanup iteration over group shard metadata.
- Decide whether `cleanupExpiredHeartbeats(...)` should use Redis-side bounded
  reads such as `ZRANGEBYSCORE ... LIMIT`, `ZPOPMIN`, or a small Lua boundary.
- Decide whether `cleanupRemovedSlots(...)` needs a removing-slot cleanup index
  instead of `HKEYS` over group slots.
- If sharding is deferred, record the threshold that will reopen the decision.
- Do not introduce writable `worker:occupancy` beside lightweight worker meta in
  this slice. If the slot aggregate survives by WKRK-0 exception, do not add a
  second occupancy truth.

Acceptance:

- A decision exists for worker meta shard and heartbeat shard count.
- This phase targets lightweight worker meta sharding and does not optimize
  obsolete occupancy fields unless WKRK-0 accepted a platform-admission
  exception.
- If sharding lands, every worker meta read/write path computes the same shard
  from worker id.
- Cross-shard operations are avoided on the hot path.
- Cleanup can iterate shards without scanning the entire Redis namespace.
- Cleanup does not read all fields from a large group slots hash before applying
  `limit`.
- Heartbeat expiry cleanup does not read all expired members for a large group
  before applying `limit`.
- WorkerRegistry contract tests pass for memory and Redis implementations.

Suggested checks:

```powershell
rg -n "watch\\(slotsKey\\)|groupSlotsHash|groupHeartbeatDeadlinesZset|heartbeat:0" platform_infra/mass-runtime-redis/src/main/java
```

## WKRK-7: Diagnostic Index Review

Goal:

Keep diagnostic indexes only when they justify their cost.

Scope:

- Review `exclusive-leases`.
- Review `groups`.
- Review bucket discovery sets: `buckets` and `node-buckets`.
- Review transport presence projection keys:
  - `xa:mass:transport:presence:v1:worker-route:{workerId}`,
  - `xa:mass:transport:presence:v1:workers`.
- Review whether `hasExclusiveLease(workerId)` and
  `exclusiveLeaseWorkerIds()` belong in the core `WorkerRegistry` contract or
  should be demoted to diagnostic/support surfaces before the physical index is
  changed.
- Review whether `WorkerPresenceStore#getPresence(workerId)`,
  `isWorkerOnline(workerId)`, `findOwners(workerId)`, and
  `listActivePresences()` are still needed as public/operator surfaces. If they
  remain, they must not require per-worker Redis keys and must not be used in
  scheduling.
- For each diagnostic or cleanup index, record:
  - production caller,
  - max cardinality,
  - whether it can be rebuilt from canonical state,
  - whether it can be wrong briefly,
  - cleanup behavior when canonical state disappears.

Acceptance:

- `exclusive-leases` is either:
  - retained with a clear global diagnostic/cleanup reason, or
  - removed and replaced by worker-id semantic lease view, bounded metadata
    lookup, or an accepted platform-admission exception where appropriate.
- If `exclusiveLeaseWorkerIds()` remains in the core SPI, the backing index has
  a reviewed bounded reason; if it is diagnostic-only, the API contract says so.
- `groups`, `buckets`, and `node-buckets` have bounded cleanup reasons.
- `worker-route:{workerId}` is removed from the mainline transport Redis
  keyspace; scheduling and dispatch route selection use canonical route key
  derived from `workerGroupId + workerId`.
- `workers` is removed or replaced by an explicitly bounded operator-only
  listing path that does not participate in scheduling.
- Tests and production callers that currently use worker-id presence projection
  are retargeted to `currentOwner(routeKey)` when they need route reachability.
- No diagnostic index participates in reserve/admission without re-reading
  current worker metadata and reaching worker-side accept/reject, or without an
  accepted platform-admission exception.

Suggested checks:

```powershell
rg -n "exclusiveLeasesSet|exclusiveLeaseWorkerIds|isExclusiveLeaseHeld|groupCandidateBucketsSet|groupNodeCandidateBucketsSet|workerGroupsSet" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
rg -n "worker-route|workersKey\\(|getPresence\\(|isWorkerOnline\\(|findOwners\\(|listActivePresences\\(" transport sdk xa-mass-engine xa-mass-worker-runtime --glob '!**/target/**'
```

## WKRK-8: Runtime Key Proof Handoff

Goal:

Make the converged key shape ready for proof-runner codification.

Scope:

- Update `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md` with the
  final key families and their classification.
- Update `roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md` if its deferred
  assumptions are now stale.
- Update `platform_infra/mass-runtime-api` contract docs or tests when an API
  method is retargeted or removed.
- Add source-level residue scans for removed key families.
- Add a replayable Python or Node local Redis snapshot script only if a concrete
  scenario needs it. Do not create a broad Redis CLI in this roadmap.

Acceptance:

- Active docs classify every worker-runtime Redis key as canonical, derived
  index, cleanup index, or diagnostic index.
- Active docs classify transport presence route-owner keys as the only
  transport reachability truth used by worker lifecycle.
- Removed key families are absent from main source.
- Removed key families are not preserved through keyspace aliases, fallback
  readers, or test-only helper vocabulary.
- Memory and Redis implementations still satisfy the same `WorkerRegistry`
  contract tests.
- RRKP remains deferred until both key shape and scenario proof needs are
  stable.
- The roadmap can be archived only after current facts are moved to the owning
  Redis runtime baseline and residue scans pass.

Suggested verification:

```powershell
git diff --check
rg -n "workerBucketMembershipSet|taskActiveWorkersSet|active-workers|worker:occupancy|available:\\{shard\\}" platform_infra xa-mass-worker-runtime xa-mass-engine roadmap doc --glob '!**/target/**'
rg -n "worker-route|workersKey\\(|WorkerPresenceStore#getPresence|isWorkerOnline\\(|findOwners\\(|listActivePresences\\(" transport sdk xa-mass-engine xa-mass-worker-runtime roadmap doc --glob '!**/target/**'
.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am test
```

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

1. Every `xa:mass:runtime:v1:worker*` key family has a documented owner,
   lifecycle, cardinality formula, and proof reason.
2. Admission ownership is explicit: worker-side accept/reject owns final
   admission by default, and any platform-side reserve/confirm/release exception
   is justified by a named policy.
3. No per-worker Redis key family remains unless it has a named, reviewed,
   non-replaceable runtime reason.
4. WorkerRegistry task-worker occupancy is either removed entirely after
   worker-side accept/reject proof, or exactly one retained projection is
   justified by a named policy. If retained, `task:{taskId}:active-workers` is
   removed unless a measured query proves `worker-active-count` cannot satisfy
   it.
5. Candidate acquisition is bounded and does not materialize entire large
   bucket sets.
6. Node-group worker lookup is either bounded or classified as diagnostic and
   removed from hot-path mutation logic.
7. Worker meta mutation contention is either sharded,
   Lua/per-operation-connection bounded, or explicitly accepted with a
   documented threshold for reopening the decision. Slot mutation contention is
   optimized only for an accepted platform-admission exception.
8. Worker-id-only lookup via `worker:group` is either removed or documented as a
   lookup projection with guardrails.
9. Diagnostic indexes cannot drive reserve/admission without reading current
   worker metadata and reaching worker-side accept/reject, or without an
   accepted platform-admission exception.
10. Transport presence `owner:{shard}` is the only route reachability truth used
    by scheduling reachability and dispatch route selection.
11. Transport `worker-route:{workerId}` and `workers` are removed from mainline
    Redis keyspace or demoted to bounded operator-only surfaces with no
    scheduling usage.
12. `WorkerSlot.meta.diagnosticStatus` is documented and guarded as
    diagnostic-only.
13. Redis runtime baseline and active roadmaps agree with current code.
14. Clean-runtime recreation is sufficient for the new shape; no old/new Redis
    compatibility bridge remains.
15. `platform_infra/mass-runtime-api` remains the upper-layer contract, and
    Redis-specific physical shape is not visible to `xa-mass-worker-runtime` or
    engine scheduling.
16. Superseded key families, methods, tests, docs, and roadmap wording are
    removed rather than retained as legacy explanations.
17. Redis Cluster hash-tag scope is explicitly decided before any multi-key Lua
    or key rename that would constrain future deployment shape.

## Open Decisions

- Should `worker:group` remain as one global hash, become sharded, or disappear
  after caller retargeting?
- Should reverse bucket membership live in group-sharded membership hashes or
  inside the encoded slot payload?
- What candidate sampling rule is acceptable for fairness and ranking?
- Which, if any, named policy justifies retaining platform-side worker
  occupancy?
- What worker meta shard count is appropriate for the largest expected worker
  group?
- Should `exclusive-leases` remain as a global diagnostic index?
- Should the Redis key names use cluster hash tags before distributed Redis
  deployment becomes a target?
- Which `RedisWorkerRegistry` methods may retain `synchronized`, and what is
  the trigger to remove each retained monitor?
- Should semantic node-group bulk gate operations stay synchronous and bounded,
  become async/repair-style, or be removed from hot mutation paths?
- Does removed-slot cleanup need a dedicated removing index, or can the cleanup
  owner avoid group-wide slot scans another way?
- Should `WorkerPresenceStore#getPresence(workerId)` and related worker-id
  projection APIs be removed entirely, or retained only as route-key-derived
  operator conveniences that do not require `worker-route:{workerId}`?
