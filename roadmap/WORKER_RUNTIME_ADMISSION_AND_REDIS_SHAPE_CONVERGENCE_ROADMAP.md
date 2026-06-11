# Worker Runtime Admission And Redis Shape Convergence Roadmap

Status: proposed convergence roadmap.

Related documents:

- `platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker/WorkerRegistry.java`
- `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md`
- `platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/RedisWorkerRegistryKeyspace.java`
- `xa-mass-worker-runtime/CONTRACTS.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `doc/PROOF_REGISTRY.md`
- `xa-mass-engine/doc/roadmap/WORKER_SLOT_REGISTRY_ROADMAP.md`
- `roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md`

Supersedes:

- `xa-mass-engine/doc/roadmap/WORKER_SLOT_REGISTRY_ROADMAP.md` remaining
  phases. WSR remains historical context for how the project converged from
  storage/lock-owned worker truth into `WorkerRegistry`; it is not the active
  target roadmap for future worker admission or Redis key-shape work.

## Purpose

This is not a Redis-only key cleanup roadmap. It is a worker-runtime admission
ownership convergence roadmap with Redis physical key-shape follow-through.
Redis key shape must be changed only after the worker admission owner,
TaskWorkRuntime lease semantics, and transport offer boundary are explicit.

The current worker-runtime Redis keyspace is controllable and currently has one
heavy worker slot aggregate:

```text
xa:mass:runtime:v1:worker:group:{groupId}:slots
```

However, this aggregate may be too heavy for the long-term worker lifecycle
model. It currently mixes worker metadata, candidate attributes, heartbeat
evidence, dispatch gates, reservation counts, active lease counts, exclusive
lease state, and removing tombstone into one encoded `WorkerSlot`.

This roadmap therefore sets a preferred target: delete the heavy platform-side
`WorkerSlot` admission aggregate and demote the platform to candidate selection,
metadata/hard-gate filtering, route reachability, and dispatch offer handling.
Workers should perform final accept/reject admission unless a later proof shows
that a specific platform-side admission policy is required. That target is not
permission to delete occupancy fields until WKRK-2.5 proves the worker-offer
protocol through a real external worker path.

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
| Worker-id route projection | Transport child slice | `...:worker-route:{workerId}` | Delete/demote outside this roadmap; do not use for scheduling |
| Online worker list projection | Transport child slice | `...:workers` | Delete/demote or replace with operator-only path outside hot path |
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

Model B: worker-side accept/reject owns final admission after WKRK-2.5 proves
the worker-offer protocol through a real external worker path.

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
matches the eventual-consistency direction only after the offer protocol and
TaskWorkRuntime lease semantics are proven.

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
2. Any product or runtime semantic decision must receive explicit owner review
   before implementation. This includes admission ownership, task-offer lease
   semantics, public/SDK worker protocol changes, policy exceptions,
   worker-side accept/reject vocabulary, retry/backoff semantics, and any
   decision that changes caller-visible scheduling behavior. Do not smuggle
   these decisions into a Redis key-shape slice.
3. Kernel and upper runtime callers must depend on
   `platform_infra/mass-runtime-api` contracts, not Redis physical structure.
   `mass-runtime-redis` may change key names, shards, indexes, Lua/transaction
   strategy, or cleanup shape without requiring engine or `xa-mass-worker-runtime`
   caller changes, unless the SPI itself is intentionally revised through owner
   review.
4. Do not introduce writable `worker:meta:{workerId}`,
   `worker:occupancy:{workerId}`, or `worker:available:{shard}` beside a still
   active `group:{groupId}:slots` admission aggregate. A lightweight worker meta
   shape may replace the slot aggregate only after WKRK-0 records the target
   owner and WKRK-2.5 proves the replacement admission protocol, or after
   WKRK-0 accepts another explicit demotion model.
5. Do not keep a per-worker Redis key family unless it has a named hot-path or
   cleanup reason that cannot be satisfied by a bounded worker-metadata shape
   or a group-scoped index.
6. Do not use key count alone as proof. A low key count with unbounded
   `SMEMBERS`, `HGETALL`, `HKEYS`, or group-wide `WATCH` contention is not
   scalable.
7. Do not add compatibility dual writes for old and new Redis shapes. This
   repo is pre-release; use clean runtime recreation for key-shape replacement
   unless a later decision explicitly requires live migration.
8. Once a new key family becomes mainline, remove the superseded internal key
   family, keyspace method, tests, docs, and roadmap wording in the same track.
   Do not leave aliases, fallback readers, or "temporary" compatibility paths
   inside the repo.
9. Redis physical shape must not leak above `platform_infra/mass-runtime-redis`.
   Upper layers talk to `platform_infra/mass-runtime-api`; memory and Redis
   implementations prove parity through the shared contract.
10. Candidate buckets may be stale. Correctness must come from Stage-2
   metadata/hard-gate validation plus worker-side accept/reject. Platform-side
   reserve/admission is allowed only for an accepted WKRK-0 exception.
11. A projection is not truth. Any projection that can accept admission, reserve,
   release, or capacity decisions without re-reading current worker metadata or
   going through worker-side accept/reject becomes a duplicate truth bug.
12. Transport presence must remain separate from worker runtime. Transport
   route ownership answers "where can this matched worker be delivered";
   WorkerRegistry answers "what worker metadata and hard gates are known before
   dispatch offer".
13. Redis field TTL is an optional cleanup optimization, not a boundary
   decision.
14. Every slice must leave runtime behavior compiling and must not require a
    later slice to restore scheduling correctness.
15. Redis key-shape convergence must be reviewed together with Redis
    connection, transaction, and locking boundaries. A lower key count with one
    JVM-wide monitor or one shared connection transaction bottleneck is not a
    production-ready shape.
16. Bounded cleanup means Redis-side bounded reads, not reading a full key and
    enforcing `limit` only inside Java.
17. If Redis Cluster is in scope for a key-shape change, hash-tag placement must
    be decided before multi-key Lua or transaction boundaries land. If Redis
    Cluster is out of scope for this roadmap, state that explicitly.
18. Do not preserve transport `worker-route:{workerId}` as a sharded hash or
    replacement projection. The mainline already has `workerGroupId + workerId`
    and must compute canonical route key directly.
19. `WorkerPresenceStore#getPresence(workerId)`, `isWorkerOnline(workerId)`,
    `findOwners(workerId)`, and `listActivePresences()` must not be production
    scheduling or dispatch-route inputs. Retarget callers to route-key-first
    lookup or demote the APIs to bounded operator/support surfaces before
    deleting their Redis projections.
20. Worker-registry heartbeat and transport presence are not interchangeable.
    If both gates remain, their names and tests must prove distinct semantics:
    registry stale/admission evidence versus transport reachability.
21. Do not optimize `WorkerSlot` sharding, WATCH contention, or occupancy
    indexes before deciding whether platform-side worker admission remains.
22. If worker-side accept/reject becomes final admission, platform
    `reservedCount`, `activeLeaseCount`, `activeLeaseCountByTask`, and
    `exclusiveLeaseHeld` must be demoted or deleted rather than reimplemented in
    a new key family.
23. Do not expose Redis physical key shapes above the infra runtime boundary.
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

Do not start by deleting occupancy fields before there is a proven worker-offer
protocol across at least one real external worker path. Worker-side final
admission is a protocol and lifecycle change, not a Redis key rename.

Start by proving caller needs, key cardinality, and hot-path cost. Then replace
one key family at a time with a single-writer shape. Deleting first would create
hidden full scans or force compatibility bridges back into the implementation.
After a replacement is accepted, delete the old residue rather than preserving
both tracks.

## First Executable Slice

The first implementation slice is documentation/inventory only. It must produce
or update `WORKER_RUNTIME_ADMISSION_AND_REDIS_SHAPE_INVENTORY.md` before any
runtime or Redis key-shape code changes.

Minimum contents:

- WSR supersession note and remaining active facts that WKRK inherits.
- Product/runtime semantic decisions that require owner review before code.
- `WorkerSlot` field decision table.
- `WorkerRegistry` SPI delta table.
- engine/runtime caller replacement table.
- external worker protocol surface table.
- Redis key family classification and bounded-read / contention budget.
- SPI isolation check proving engine and `xa-mass-worker-runtime` consume
  `platform_infra/mass-runtime-api` semantic operations rather than Redis
  keyspace, codec, or storage-adapter classes.
- `doc/PROOF_REGISTRY.md` note saying the current worker-state invariant remains
  platform-side until WKRK-2.5 proves worker-offer outcomes.

WKRK-SPI is the first blocking gate. After WKRK-SPI is green, WKRK-0, WKRK-1,
and WKRK-1.5 may be completed as the first decision/inventory bundle. No
production behavior may change in that bundle.

## WKRK-SPI: Runtime SPI Isolation Gate

Goal:

Make Redis physical shape an infra runtime implementation detail before any
admission semantic rewrite or Redis key-shape change.

Why this is first:

If engine or `xa-mass-worker-runtime` can see Redis keys, Redis keyspace
classes, codecs, or adapter implementation types, every later key reshape
becomes a kernel change. That turns physical storage into accidental runtime
truth and makes the roadmap expensive to execute. The first gate therefore
freezes the dependency direction:

```text
engine / xa-mass-worker-runtime
  -> platform_infra/mass-runtime-api semantic SPI
    -> mass-runtime-memory
    -> mass-runtime-redis physical implementation
```

Scope:

- Inventory engine and `xa-mass-worker-runtime` production imports and string
  references for Redis runtime implementation details.
- Confirm upper runtime callers consume semantic SPI methods from
  `platform_infra/mass-runtime-api`, not Redis-shaped helper methods.
- Classify any lower-level `WorkerRegistry` method exposed through
  `mass-runtime-api` as:
  - semantic runtime SPI,
  - implementation-only surface that should not be used by upper layers,
  - contract-test-only surface,
  - remove/retarget candidate.
- If an upper runtime caller needs a new access pattern, propose it as a
  storage-independent SPI method before touching `mass-runtime-redis`.
- Do not add a new pass-through facade. The target is a narrow semantic SPI,
  not an extra wrapper around the current Redis implementation.

Acceptance:

- Engine and `xa-mass-worker-runtime` production source has no imports or
  references to:
  - `com.xa.mass.runtime.redis`,
  - `RedisWorkerRegistry`,
  - `RedisWorkerRegistryKeyspace`,
  - Redis key suffixes such as `worker:group`, `group:{groupId}:slots`,
    `bucket-membership`, or `worker-active-count`.
- Engine and `xa-mass-worker-runtime` production callers use
  `platform_infra/mass-runtime-api` semantic operations for candidate,
  metadata, admission, dispatch-gate, and cleanup needs.
- Any required new upper-layer access pattern is recorded as a
  `mass-runtime-api` SPI change before Redis implementation begins.
- The shared memory and Redis implementations remain substitutable through the
  same API contract; no kernel code branches on Redis vs memory runtime.
- This gate is green before WKRK-0 owner decisions are implemented, before
  WKRK-1 key-shape decisions become code, and before WKRK-2.5 offer protocol
  work starts.

Suggested checks:

```powershell
rg -n "com\\.xa\\.mass\\.runtime\\.redis|RedisWorkerRegistry|RedisWorkerRegistryKeyspace|worker:group|group:\\{groupId\\}:slots|bucket-membership|worker-active-count" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "\\.slotByWorkerId\\(|\\.markSlotRemoving\\(|\\.workerIdsByGroupId\\(|\\.workerIdsByAdapterNodeGroup\\(" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
.\mvnw.cmd -pl xa-mass-engine "-Dtest=EngineSchedulingCoreArchitectureGuardTest#upperRuntimeCallersUseWorkerRegistrySemanticOperations" test
```

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
| transport worker-id route projection | `0` target mainline keys | Transport-owned delete/demote; do not replace with sharded hash |
| diagnostics | explicitly bounded | Diagnostic indexes cannot become admission truth |

The target allows more than one key per group or bucket when it prevents hot
keys or full scans. It rejects one key per worker unless the key family has a
stronger reason than convenience.

## WKRK-0: Worker Admission Ownership Decision

Goal:

Decide the preferred final worker admission owner and record the engine,
worker-runtime, transport, SDK/API, and Redis SPI deltas before any physical key
deletion. A decision that prefers worker-side accept/reject is not enough to
delete platform occupancy; WKRK-2.5 must still prove the protocol can carry the
runtime lifecycle.

Scope:

- Treat this as an ownership decision, not a key-shape refactor. The output must
  say who owns final admission, when a work lease becomes active, and which
  runtime contract exposes that evidence.
- Inventory every `WorkerSlot` field and classify it as:
  - declaration/meta,
  - candidate/filter input,
  - platform hard gate,
  - platform admission/occupancy,
  - task-runtime-derived projection,
  - transport reachability duplicate,
  - diagnostic-only.
- Produce a `WorkerSlot` field decision table with:
  - field name or field group,
  - current writer,
  - current reader,
  - target owner,
  - keep / delete / demote / defer decision,
  - replacement path when deleted.
- Produce a `WorkerRegistry` SPI delta table with:
  - method name,
  - current semantic role,
  - keep as semantic API,
  - demote to implementation-only,
  - replace with a new semantic method,
  - delete after caller migration,
  - accepted platform-admission exception if any.
- Produce an engine/runtime caller replacement table for at least:
  - `SimpleTaskDispatchBinder` claim / confirm / dispatch path,
  - `RuleBasedTaskWorkerMatchingStrategy` worker reserve path,
  - `TaskWorkerAssignListener` active worker count / assignment planning path,
  - `TaskResourceReleaseListener` and result / expiry / terminal release paths,
  - `WorkerAdmissionRuntime` public contract.
- Produce an external worker protocol surface table for at least:
  - `xa-mass-server` `ExternalWorkerApiController` worker API endpoints,
  - Java SDK `WorkerClient`, `PollingWorkerSession`, and
    `WebSocketWorkerSession`,
  - embedded SDK `PullWorkerSession`,
  - `integrations/xa-mass-worker-pack` sample clients,
  - Node polling / websocket / socket sample workers.
  The table must say whether each path reports offer accept/reject in the first
  implementation, is explicitly out of scope for that slice, or remains on a
  platform-side admission exception.
- Inventory every production call path that mutates or reads:
  - `reservedCount`,
  - `activeLeaseCount`,
  - `activeLeaseCountByTask`,
  - `exclusiveLeaseHeld`,
  - `lastHeartbeatMillis`,
  - `diagnosticStatus`.
- Define the worker-side offer protocol needed to delete platform occupancy.
  This protocol must use a named seam such as `WorkerDispatchOfferOutcome`;
  it must not reuse transport `DispatchOutcome` and must not be represented as
  a task result. Offer outcome values include:
  - `ACCEPTED`,
  - `BUSY`,
  - `CAPACITY_FULL`,
  - `DEVICE_NOT_READY`,
  - `ATTRIBUTE_MISMATCH`,
  - `DRAINING`,
  - timeout/no-ack.
- Define the `TaskWorkRuntime` lease semantics for offers by choosing exactly
  one model:
  - transitional active claim: `claimReady(...)` creates an active lease before
    worker acceptance, and every reject/no-ack must synchronously release and
    requeue or delay the work;
  - target pending-offer lease: dispatch offer creates pending-offer evidence,
    and work becomes active only after `ACCEPTED`.
- The preferred target is pending-offer lease. Transitional active claim is
  allowed only as a bounded first slice with explicit release/requeue proof.
- If no bounded protocol path can be proven for at least one real external
  worker surface, WKRK-0 must keep platform-side admission as an explicit
  temporary exception rather than declaring worker-side admission complete.
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
- Update `xa-mass-worker-runtime/CONTRACTS.md` in the same phase if the
  decision changes `WorkerAdmissionRuntime` semantics. If no contract text
  changes, record why the existing contract remains valid.

Acceptance:

- The roadmap records worker-side accept/reject as the preferred
  final-admission target, but marks deletion of platform occupancy as gated on
  WKRK-2.5 protocol proof.
- WSR remaining phases are explicitly superseded by this roadmap; any WSR fact
  retained by WKRK is copied into the first inventory or named as historical
  context only.
- The WKRK-0 artifact contains the `WorkerSlot` field decision table,
  `WorkerRegistry` SPI delta table, and engine/runtime caller replacement
  table described above.
- The artifact contains the external worker protocol surface table and names
  which public/SDK/integration path provides the first proof path.
- The artifact states whether dispatch offer uses transitional active claim or
  pending-offer lease, and names the `TaskWorkRuntime` method or new seam that
  owns each transition.
- The offer outcome channel is named and explicitly separated from transport
  `DispatchOutcome` and task result finality.
- Any platform-side admission exception names the policy that requires it and
  why worker-side reject/backoff is insufficient.
- `WorkerSlot` occupancy fields become delete/demotion candidates and
  subsequent WKRK slices retarget to lightweight worker meta only after WKRK-2.5
  proves the offer protocol, unless an exception is accepted.
- `WorkerAdmissionRuntime` contract docs are updated or explicitly marked as
  still valid.
- `doc/PROOF_REGISTRY.md` is updated or annotated to state that the current
  `sched.worker-state-dimensions` invariant remains platform-side until
  WKRK-2.5 proves worker-offer outcomes.
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

- A sibling inventory,
  `WORKER_RUNTIME_ADMISSION_AND_REDIS_SHAPE_INVENTORY.md`, exists or this
  roadmap has been updated with an equivalent table.
- The inventory contains the first-slice bundle named in this roadmap:
  WSR supersession, `WorkerSlot` field decisions, `WorkerRegistry` SPI deltas,
  caller replacement table, external worker protocol surface table, and Redis
  bounded-read / contention budget.
- Every key family has a classification:
  `canonical`, `bounded-index`, `cleanup-index`, `diagnostic-index`,
  `duplicate-candidate`, or `remove-candidate`.
- Every `WorkerRegistry` API method used by upper layers is mapped to either a
  storage-independent contract or a retarget/removal decision.
- No engine or `xa-mass-worker-runtime` production caller imports or depends on
  `com.xa.mass.runtime.redis`, `RedisWorkerRegistryKeyspace`, Redis codecs, or
  Redis key names. Any required new access pattern is proposed as a
  `mass-runtime-api` SPI change before Redis implementation work starts.
- The inventory explicitly names whether a key family is allowed to be
  `O(worker keys)`.
- Every full-key read on a mainline or cleanup path is classified as retained,
  replaced, or bounded by a follow-up slice.
- Every JVM monitor / Redis connection / transaction bottleneck is named with
  either a removal plan or a retained reason.
- Redis Cluster is explicitly in or out of scope for this roadmap.
- Transport `worker-route:{workerId}` and `workers` are classified as
  transport-owned delete/demotion candidates unless a bounded operator-only
  replacement is explicitly approved.
- The inventory includes a sample proving `diagnosticStatus` is not scheduling
  truth and cannot override transport reachability or WorkerRegistry gates.
- No implementation change is made in this slice.

Suggested checks:

```powershell
rg -n "RedisWorkerRegistryKeyspace|workerBucketMembershipSet|taskActiveWorkersSet|groupSlotsHash|groupHeartbeatDeadlinesZset|groupCandidateBucket|nodeCandidateBucket" platform_infra/mass-runtime-redis/src/main/java
rg -n "interface WorkerRegistry|workerMeta|workerAdmissionSnapshot|markWorkersRemovingByGroup|disableDispatchForAdapterNodeGroup|slotByWorkerId|workerIdsByGroupId|activeWorkerIdsByTask|activeWorkerCountForTask|exclusiveLeaseWorkerIds|acquireCandidates" platform_infra/mass-runtime-api platform_infra/mass-runtime-memory platform_infra/mass-runtime-redis
rg -n "com\\.xa\\.mass\\.runtime\\.redis|RedisWorkerRegistry|RedisWorkerRegistryKeyspace|groupSlotsHash|workerBucketMembershipSet|worker:group|group:\\{groupId\\}" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
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
- For every semantic worker-id API that currently delegates through
  `slotByWorkerId(workerId)`, record the concrete memory and Redis
  implementation source after `worker:group` is removed or sharded. This covers
  at least `workerMeta(workerId)`, `workerAdmissionSnapshot(workerId)`,
  worker-id `tryReserve(...)`, `confirmReservation(...)`,
  `releaseReservation(...)`, `recordWorkClaimed(...)`, `recordWorkFinal(...)`,
  dispatch-disable methods, and exclusive-lease methods.
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
- If the replacement plan removes or shards `worker:group`, every retained
  semantic worker-id API has an implementation-level memory and Redis source
  that does not depend on the removed projection. Otherwise `worker:group`
  remains and is documented as an active lookup projection.
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
.\mvnw.cmd -pl xa-mass-engine "-Dtest=EngineSchedulingCoreArchitectureGuardTest#upperRuntimeCallersUseWorkerRegistrySemanticOperations" test
```

The full `EngineSchedulingCoreArchitectureGuardTest` class is a repo-health
prerequisite, not WKRK-2 slice proof. Unrelated scheduling-policy guard failures
must not be treated as WKRK-2 failures.

## WKRK-2.5: Worker-Side Offer Accept/Reject Runtime

Goal:

Land the replacement correctness loop before deleting platform-side occupancy.

Why this exists:

This roadmap defaults to worker-side final admission. That cannot stay as a
documentation-only decision. Before `reservedCount`, `activeLeaseCount`,
`activeLeaseCountByTask`, or `exclusiveLeaseHeld` are removed from the worker
runtime, the dispatch path must have an explicit offer outcome and recovery
model.

WKRK-0 may choose worker-side accept/reject as the preferred target, but this
phase is the executable feasibility gate. If this phase cannot prove the
protocol through at least one real external worker path, platform-side admission
must remain as an explicit temporary exception and later Redis key-shape slices
must not delete occupancy fields.

Scope:

- Define a worker offer outcome value contract at the worker-offer protocol
  boundary. The recommended name is `WorkerDispatchOfferOutcome`. It is not
  transport `DispatchOutcome`, which only records envelope delivery, and it is
  not `TaskResult`, which records work finality. Offer outcomes include:
  - `ACCEPTED`,
  - `BUSY`,
  - `CAPACITY_FULL`,
  - `DEVICE_NOT_READY`,
  - `ATTRIBUTE_MISMATCH`,
  - `DRAINING`,
  - timeout/no-ack.
- Keep the owner split explicit:
  - `TaskWorkRuntime` owns pending-offer evidence, active lease evidence,
    requeue visibility, retry/expiry counters, and stale result validity.
  - engine binder/recovery owns offer orchestration, release/requeue calls,
    backoff decision, and redispatch scheduling.
  - transport, server, SDK, and worker sessions carry protocol evidence and
    route delivery; they do not own task-work lifecycle truth.
  - `WorkerRegistry` owns worker metadata, candidate indexes, hard gates, and
    worker-side admission evidence only. It must not gain task-work lease,
    pending-offer, active-execution, or stale-result truth.
  - trace observes offer transitions after the runtime owners emit evidence; it
    does not own admission or lease truth.
- Define the offer outcome ingestion channel:
  - polling workers may return it when polling/accepting assigned work,
  - realtime workers may send an explicit accept/reject frame,
  - command/event ack paths must not be reused unless they are renamed as the
    worker-offer outcome owner.
- Include public and integration worker surfaces in the scope, not only engine
  internals:
  - `xa-mass-server` external worker endpoints,
  - Java SDK `WorkerClient`, `PollingWorkerSession`, and
    `WebSocketWorkerSession`,
  - embedded SDK `PullWorkerSession`,
  - `integrations/xa-mass-worker-pack`,
  - Node polling / websocket / socket samples.
  At least one path must be implemented and proven in this phase; any deferred
  path must be named with the reason it does not block deleting platform
  occupancy.
- Define the `TaskWorkRuntime` lease model selected in WKRK-0:
  - if transitional active claim is used, rejected/no-ack offers must call a
    foreground release/requeue path that clears active lease evidence and makes
    the work visible again;
  - if pending-offer lease is used, offer timeout/reject releases pending
    evidence and returns work to ready/delayed without ever counting it active.
- `ACCEPTED` becomes active execution evidence in `TaskWorkRuntime`. A retained
  worker-runtime projection may read or derive worker-side admission evidence
  only for a proven policy; it must not become task-work lease truth.
- Define rejection handling:
  - release or avoid platform reservation if the transitional path still uses it,
  - return work to ready/delayed state,
  - bounded backoff so busy workers do not hot-loop,
  - stale candidate marking when rejection implies stale metadata.
- Define timeout handling for no-ack dispatch offer.
- Define how worker-side reject interacts with retry, pause, resume, terminal,
  and result finality.
- Define stale-result behavior:
  - result for a rejected or timed-out offer must be ignored or classified as
    stale evidence, not accepted as visible final output;
  - result for an accepted offer follows the existing result convergence path.
- Define black-box proof with an external worker that deliberately returns
  `BUSY` / `CAPACITY_FULL` before later accepting.
- Update server, SDK, integration, and transport docs/tests in the same slice
  if the worker wire contract or worker session API changes.
- Keep policy separate:
  - retry delay/backoff is policy,
  - offer outcome recording and work return are mechanism.

Acceptance:

- A task can be dispatched to a candidate worker, receive worker-side `BUSY` or
  `CAPACITY_FULL`, return work to competition, and later complete on another or
  the same worker without duplicate final result.
- The proof includes at least one real external worker path through server/SDK
  or worker-pack/Node sample code; engine-only, transport-runtime-only, and
  constructor/unit tests are support regressions, not the main proof.
- The implementation or decision record states whether `claimReady(...)` creates
  active lease evidence before worker accept, or whether a new pending-offer
  state is used.
- `TaskWorkRuntime` is the only owner that turns `ACCEPTED` into active work
  evidence. Any retained worker-runtime projection is read-only or
  policy-specific evidence and cannot own task-work lease state.
- No new `WorkerRegistry` or worker-runtime projection owns pending-offer,
  active-execution, requeue visibility, or stale-result validity.
- No rejected offer leaves a stuck active lease or invisible in-flight item.
- Timeout/no-ack follows the same recovery path as explicit rejection.
- A stale result from a rejected or timed-out offer is not accepted as visible
  final output.
- Trace or proof output distinguishes:
  - candidate selected,
  - offer sent,
  - worker accepted/rejected/timed out,
  - work requeued or finalized.
- Server/API/SDK docs are updated when the worker wire contract changes, or the
  first proof path is explicitly recorded as internal-only and platform
  occupancy deletion remains blocked for external workers.
- `doc/PROOF_REGISTRY.md` is updated when this phase lands so
  `sched.worker-state-dimensions` names worker-offer outcome proof instead of
  only the current platform-side admission proof.
- Only after this phase is proven may WKRK-3 delete platform-side occupancy
  fields.

Suggested verification:

```powershell
rg -n "ACCEPTED|CAPACITY_FULL|DEVICE_NOT_READY|ATTRIBUTE_MISMATCH|DRAINING|offer" xa-mass-engine xa-mass-worker-runtime transport sdk xa-mass-server integrations --glob '!**/target/**'
.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime,transport/transport_runtime,sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk,xa-mass-server,integrations/xa-mass-worker-pack -am test
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
- Inventory current allocation callers before deleting any projection. At
  minimum, `TaskWorkerAssignListener` currently uses
  `WorkerAdmissionRuntime#getActiveWorkerCountForTask(taskId)` as assignment
  planning evidence.
- First decide whether any named policy still needs WorkerRegistry task-worker
  active counts outside TaskWorkRuntime and trace.
- Define the replacement source before deleting WorkerRegistry task occupancy:
  - `TaskWorkRuntime` accepted-active worker count,
  - offer-accepted worker projection,
  - or a policy decision that assignment planning no longer consumes active
    worker count.
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
- `TaskWorkerAssignListener` no longer depends on WorkerRegistry task-worker
  occupancy before the backing keys are deleted.
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
`O(workers)` to `O(group * membershipShard)`. If WKRK-0 records worker-side
accept/reject as the target and WKRK-2.5 proves the offer protocol, do not fold
membership into `WorkerSlot`; retarget cleanup around lightweight worker meta
instead.

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
- Update the shared `WorkerRegistry` contract before Redis switches away from
  full-bucket reads. Production Redis acquisition may be bounded and unordered;
  deterministic ordering belongs only in a named test seam or in a real
  ranking stage after candidate validation.
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
- Contract tests no longer require Redis to materialize the full bucket or
  preserve full-bucket deterministic ordering. If deterministic behavior is
  needed for tests, it is injected explicitly and does not define production
  Redis sampling.

Suggested checks:

```powershell
rg -n "smembers\\(bucketKey\\)|groupCandidateBucket\\(|nodeCandidateBucket\\(" platform_infra/mass-runtime-redis/src/main/java
.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am -Dtest=WorkerRegistryContractTest test
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

## WKRK-7: Diagnostic Index Review And Transport Dependency

Goal:

Keep worker-runtime diagnostic indexes only when they justify their cost, and
record the transport-owned projection cleanup that must happen beside this
roadmap without making worker runtime own transport keys.

Scope:

- Review `exclusive-leases`.
- Review `groups`.
- Review bucket discovery sets: `buckets` and `node-buckets`.
- Coordinate with a transport-owned child slice for presence projection keys:
  - `xa:mass:transport:presence:v1:worker-route:{workerId}`,
  - `xa:mass:transport:presence:v1:workers`.
  This roadmap may record the dependency and verify that worker runtime /
  engine do not depend on those projections, but transport owns their deletion
  or demotion.
- Review whether `hasExclusiveLease(workerId)` and
  `exclusiveLeaseWorkerIds()` belong in the core `WorkerRegistry` contract or
  should be demoted to diagnostic/support surfaces before the physical index is
  changed.
- Review whether `WorkerPresenceStore#getPresence(workerId)`,
  `isWorkerOnline(workerId)`, `findOwners(workerId)`, and
  `listActivePresences()` are still needed as public/operator surfaces. If they
  remain, they must not require per-worker Redis keys and must not be used in
  scheduling.
- The transport child slice must classify each retained worker-id presence API
  as one of:
  - removed,
  - route-key-derived bounded lookup,
  - bounded operator/support projection,
  - test-only helper retargeted away from Redis physical keys.
  It must not silently keep `worker-route:{workerId}` or `workers` as a renamed
  mainline projection.
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
- A transport-owned child slice is linked or created for
  `worker-route:{workerId}` / `workers` deletion or demotion.
- That child slice names the public/operator APIs that remain, the replacement
  read model for each retained API, and the proof that no retained API is a
  scheduling or dispatch-route dependency.
- Worker runtime and engine scheduling do not depend on worker-id transport
  projections for scheduling, admission, or dispatch route selection.
- Transport-owned cleanup proves scheduling and dispatch route selection use
  canonical route key derived from `workerGroupId + workerId`, or records a
  bounded operator-only exception outside the hot path.
- Tests and production callers that currently use worker-id presence projection
  are retargeted to `currentOwner(routeKey)` when they need route reachability.
- No diagnostic index participates in reserve/admission without re-reading
  current worker metadata and reaching worker-side accept/reject, or without an
  accepted platform-admission exception.

Suggested checks:

```powershell
rg -n "exclusiveLeasesSet|exclusiveLeaseWorkerIds|isExclusiveLeaseHeld|groupCandidateBucketsSet|groupNodeCandidateBucketsSet|workerGroupsSet" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
rg -n "worker-route|workersKey\\(|getPresence\\(|isWorkerOnline\\(|findOwners\\(|listActivePresences\\(" transport sdk xa-mass-engine xa-mass-worker-runtime xa-mass-server integrations --glob '!**/target/**'
```

## WKRK-8: Runtime Key Proof Handoff

Goal:

Make the converged key shape ready for proof-runner codification.

Scope:

- Update `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md` with the
  final key families and their classification.
- Update `roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md` if its deferred
  assumptions are now stale.
- Update `doc/PROOF_REGISTRY.md` so the proof registry describes the current
  implemented invariant: platform-side admission before WKRK-2.5, or
  worker-offer outcome proof after WKRK-2.5 lands.
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
- `doc/PROOF_REGISTRY.md` no longer describes the pre-WKRK-2.5 platform-side
  admission proof as the only current proof if worker-offer outcomes have
  landed.
- RRKP remains deferred until both key shape and scenario proof needs are
  stable.
- The roadmap can be archived only after current facts are moved to the owning
  Redis runtime baseline and residue scans pass.

Suggested verification:

```powershell
git diff --check
rg -n "workerBucketMembershipSet|taskActiveWorkersSet|active-workers|worker:occupancy|available:\\{shard\\}" platform_infra xa-mass-worker-runtime xa-mass-engine roadmap doc --glob '!**/target/**'
rg -n "worker-route|workersKey\\(|WorkerPresenceStore#getPresence|isWorkerOnline\\(|findOwners\\(|listActivePresences\\(" transport sdk xa-mass-engine xa-mass-worker-runtime xa-mass-server integrations roadmap doc --glob '!**/target/**'
.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am test
```

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

1. WKRK-SPI passed before any Redis physical key-shape change or worker-offer
   semantic rewrite: engine and `xa-mass-worker-runtime` production code depend
   only on `platform_infra/mass-runtime-api` semantic SPI, not Redis keyspace,
   codecs, or implementation classes.
2. Every `xa:mass:runtime:v1:worker*` key family has a documented owner,
   lifecycle, cardinality formula, and proof reason.
3. Admission ownership is explicit: worker-side accept/reject owns final
   admission only after WKRK-2.5 proves the offer protocol through at least one
   real external worker path, and any platform-side reserve/confirm/release
   exception is justified by a named policy.
4. No per-worker Redis key family remains unless it has a named, reviewed,
   non-replaceable runtime reason.
5. WorkerRegistry task-worker occupancy is either removed entirely after
   worker-side accept/reject proof, or exactly one retained projection is
   justified by a named policy. If retained, `task:{taskId}:active-workers` is
   removed unless a measured query proves `worker-active-count` cannot satisfy
   it.
6. Candidate acquisition is bounded and does not materialize entire large
   bucket sets. Contract tests no longer require Redis full-bucket
   deterministic ordering unless a named test seam provides it.
7. Node-group worker lookup is either bounded or classified as diagnostic and
   removed from hot-path mutation logic.
8. Worker meta mutation contention is either sharded,
   Lua/per-operation-connection bounded, or explicitly accepted with a
   documented threshold for reopening the decision. Slot mutation contention is
   optimized only for an accepted platform-admission exception.
9. Worker-id-only lookup via `worker:group` is either removed with
   implementation-level replacements for retained semantic worker-id APIs, or
   documented as an active lookup projection with guardrails.
10. Diagnostic indexes cannot drive reserve/admission without reading current
   worker metadata and reaching worker-side accept/reject, or without an
   accepted platform-admission exception.
11. Transport presence `owner:{shard}` is the only route reachability truth used
    by scheduling reachability and dispatch route selection.
12. A transport-owned child slice removes `worker-route:{workerId}` and
    `workers` from mainline Redis keyspace or demotes them to named bounded
    operator-only surfaces with no scheduling usage and no dispatch-route
    dependency.
13. `WorkerSlot.meta.diagnosticStatus` is documented and guarded as
    diagnostic-only.
14. Offer outcome is represented by a named worker-offer seam, not by
    transport `DispatchOutcome` or task result rows.
15. `TaskWorkRuntime` offer/active lease semantics are explicit and proven for
    accept, reject, timeout, stale result, pause, resume, and terminal paths
    through engine plus at least one public/SDK/integration worker path.
16. Redis runtime baseline and active roadmaps agree with current code.
17. Clean-runtime recreation is sufficient for the new shape; no old/new Redis
    compatibility bridge remains.
18. `platform_infra/mass-runtime-api` remains the upper-layer contract, and
    Redis-specific physical shape is not visible to `xa-mass-worker-runtime` or
    engine scheduling.
19. Superseded key families, methods, tests, docs, and roadmap wording are
    removed rather than retained as legacy explanations.
20. Redis Cluster hash-tag scope is explicitly decided before any multi-key Lua
    or key rename that would constrain future deployment shape.

## Open Decisions

- Should `worker:group` remain as one global hash, become sharded, or disappear
  after caller retargeting?
- Should reverse bucket membership live in group-sharded membership hashes or
  inside the encoded slot payload?
- What candidate sampling rule is acceptable for fairness and ranking?
- Which, if any, named policy justifies retaining platform-side worker
  occupancy?
- Should the first implementation use transitional active claim with
  foreground release/requeue, or introduce pending-offer lease before deleting
  platform occupancy?
- Which owner surface should define `WorkerDispatchOfferOutcome`, and which
  protocol path should polling/realtime workers use to report accept/reject?
- Which external worker path is the first WKRK-2.5 proof path: external polling
  API, Java SDK polling session, Java SDK realtime session, worker-pack sample,
  or Node sample?
- If WorkerRegistry task-worker occupancy is removed, does
  `TaskWorkerAssignListener` use TaskWorkRuntime accepted-active view,
  offer-accepted projection, or a policy that no longer needs active worker
  count?
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
- Which transport-owned child slice removes or demotes
  `WorkerPresenceStore#getPresence(workerId)` and related worker-id projection
  APIs, and can any retained operator surface be route-key-derived without
  `worker-route:{workerId}`?
