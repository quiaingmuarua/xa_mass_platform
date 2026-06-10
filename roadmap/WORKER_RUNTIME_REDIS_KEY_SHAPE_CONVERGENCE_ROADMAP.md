# Worker Runtime Redis Key Shape Convergence Roadmap

Status: proposed convergence roadmap.

Related documents:

- `platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker/WorkerRegistry.java`
- `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md`
- `platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/RedisWorkerRegistryKeyspace.java`
- `roadmap/REDIS_RUNTIME_KEY_PROOF_OPERATOR_ROADMAP.md`

## Purpose

The current worker-runtime Redis keyspace is controllable and has one clear
canonical worker slot aggregate:

```text
xa:mass:runtime:v1:worker:group:{groupId}:slots
```

However, the current keyspace still contains several derived indexes and
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
  whole worker runtime.

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
| `...:group:{groupId}:slots` | `HASH` | encoded `WorkerSlot` by worker id | Canonical worker slot truth |
| `...:group:{groupId}:heartbeat:0` | `ZSET` | heartbeat deadline by worker id | Bounded stale-worker index; valid but currently unsharded |
| `...:group:{groupId}:bucket:{candidateBucketKey}:workers` | `SET` | candidate source for group bucket | Candidate index; valid reason, but acquisition currently reads whole set |
| `...:group:{groupId}:buckets` | `SET` | bucket discovery for cleanup | Cleanup index; likely valid if bucket count is bounded |
| `...:group:{groupId}:node:{adapterNodeId}:bucket:{candidateBucketKey}:workers` | `SET` | node-scoped candidate source | Candidate index; valid reason, but acquisition cost must be bounded |
| `...:group:{groupId}:node-buckets` | `SET` | node bucket discovery for cleanup | Cleanup index; likely valid if bucket count is bounded |
| `...:group:{groupId}:worker:{workerId}:bucket-membership` | `SET` | reverse index from worker to bucket keys for cleanup | Suspect per-worker key family; strong replacement candidate |
| `...:task:{taskId}:active-workers` | `SET` | active worker ids per task | Suspect duplicate of `worker-active-count` hash fields |
| `...:task:{taskId}:worker-active-count` | `HASH` | active lease count by worker for a task | Per-task occupancy projection; likely sufficient alone |

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

`xa-mass-worker-runtime` may consume worker runtime facts through the
`WorkerRegistry` API, but it must not define Redis key structure or depend on
Redis-only projections.

Engine scheduling may consume worker records and reserve/admission outcomes
through worker-runtime contracts, but it must not read Redis worker keys or
transport presence keys directly.

Transport presence is adjacent runtime state, not worker slot truth. Transport
route-owner projections such as `xa:mass:transport:presence:v1:worker-route:*`
must not be copied into this roadmap or used as a reason to keep worker-runtime
lookup projections.

## Boundary Decision

Keep `group:{groupId}:slots` as the only canonical worker slot truth until a
later slice explicitly replaces it.

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
   `worker:occupancy:{workerId}`, or `worker:available:{shard}` beside
   `group:{groupId}:slots`.
3. Do not keep a per-worker Redis key family unless it has a named hot-path or
   cleanup reason that cannot be satisfied by a group-sharded hash or canonical
   slot data.
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
8. Candidate buckets may be stale, but Stage-2 reserve/admission must remain the
   correctness gate.
9. A projection is not truth. Any projection that can accept admission, reserve,
   release, or capacity decisions without re-reading `WorkerSlot` becomes a
   duplicate truth bug.
10. Transport presence must remain separate from worker runtime. Transport
   route ownership answers "where can this matched worker be delivered";
   WorkerRegistry answers "can this worker be scheduled or admitted".
11. Redis field TTL is an optional cleanup optimization, not a boundary
   decision.
12. Every slice must leave runtime behavior compiling and must not require a
    later slice to restore scheduling correctness.

## Do Not Start With

Do not start by deleting `worker:group`, `bucket-membership`, or
`active-workers` keys.

Start by proving caller needs, key cardinality, and hot-path cost. Then replace
one key family at a time with a single-writer shape. Deleting first would create
hidden full scans or force compatibility bridges back into the implementation.
After a replacement is accepted, delete the old residue rather than preserving
both tracks.

## Target Key Budget

Target cardinality is expressed in Redis keys, not entries:

| Category | Preferred key cardinality | Notes |
| --- | --- | --- |
| canonical worker slots | `O(group * slotShard)` | Fields may be `O(workers)`; shard if group size or write contention requires |
| heartbeat deadlines | `O(group * heartbeatShard)` | Entries are `O(workers)`; supports bounded expiry scans |
| candidate buckets | `O(group * bucket * shard)` plus optional node dimension | Must support bounded sampling; no full bucket reads on large sets |
| reverse bucket membership | `O(group * membershipShard)` | Prefer hash fields by worker id over one key per worker |
| worker id to group lookup | `O(1)` global hash or `O(shard)` hashes | Keep only if worker-id-only owner APIs remain |
| task active worker counts | `O(activeTask)` | One hash should be enough unless a second structure has a proven query |
| diagnostics | explicitly bounded | Diagnostic indexes cannot become admission truth |

The target allows more than one key per group or bucket when it prevents hot
keys or full scans. It rejects one key per worker unless the key family has a
stronger reason than convenience.

## WKRK-0: Key Family Inventory And Budget

Goal:

Build a code-grounded inventory before changing key shape.

Scope:

- Inventory every `RedisWorkerRegistryKeyspace` method.
- Inventory matching `WorkerRegistry` SPI methods and distinguish API contract
  from Redis-only physical shape.
- For each key family, record:
  - owner method that writes it,
  - production methods that read it,
  - Redis type,
  - cardinality formula,
  - whether it is canonical truth or derived index,
  - whether it can be rebuilt from `WorkerSlot`,
  - whether it is used in scheduling/admission or diagnostics only.
- Include current live Redis scan as evidence, but do not treat local key count
  as production proof.
- Record current hot-path costs:
  - `acquireCandidates(...)` reads full candidate buckets with `SMEMBERS` and
    sorts the whole list before sampling.
  - `updateSlot(...)` watches the whole group `slots` hash, so unrelated worker
    mutations in the same group can conflict.

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
- No implementation change is made in this slice.

Suggested checks:

```powershell
rg -n "RedisWorkerRegistryKeyspace|workerBucketMembershipSet|taskActiveWorkersSet|groupSlotsHash|groupHeartbeatDeadlinesZset|groupCandidateBucket|nodeCandidateBucket" platform_infra/mass-runtime-redis/src/main/java
rg -n "interface WorkerRegistry|slotByWorkerId|workerIdsByGroupId|activeWorkerIdsByTask|activeWorkerCountForTask|exclusiveLeaseWorkerIds|acquireCandidates" platform_infra/mass-runtime-api platform_infra/mass-runtime-memory platform_infra/mass-runtime-redis
rg -n "slotByWorkerId|workerIdsByGroupId|activeWorkerIdsByTask|activeWorkerCountForTask|exclusiveLeaseWorkerIds|acquireCandidates" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
```

## WKRK-1: Worker-Id Lookup Contract

Goal:

Decide whether `worker:group` remains a justified lookup projection or whether
worker-runtime callers should carry `groupId`.

Scope:

- Audit all production `slotByWorkerId(workerId)` callers.
- Classify each caller:
  - admission hot path,
  - worker control API,
  - cleanup/diagnostic,
  - result/release compensation,
  - test-only.
- For admission and release paths, decide whether the caller naturally owns
  `groupId` and should pass it explicitly.
- If `worker:group` remains, document it as a WorkerRegistry-owned lookup
  projection and prove that reserve/admission still re-read `WorkerSlot`.

Acceptance:

- `worker:group` has either a retained reason or a replacement plan.
- No path accepts scheduling or capacity decisions from `worker:group` alone.
- `tryReserve(groupId, workerId, ...)` still rejects group mismatch by reading
  the target group `slots` entry.
- Any retained worker-id-only API is documented as lookup convenience, not
  canonical worker membership truth.

Suggested checks:

```powershell
rg -n "\\.slotByWorkerId\\(|workerGroupHash\\(|worker:group" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
```

## WKRK-2: Per-Task Occupancy Projection Convergence

Goal:

Remove redundant per-task occupancy projection if `worker-active-count` can own
the whole task-worker active view.

Current issue:

`task:{taskId}:active-workers` and `task:{taskId}:worker-active-count` both
encode the active worker set. The hash already has worker ids as fields and
counts as values. Unless a measured query needs the set, the set is duplicate
projection.

Target candidate:

```text
xa:mass:runtime:v1:worker:task:{taskId}:worker-active-count
  HASH
  field = workerId
  value = active lease count
```

Scope:

- Replace `activeWorkerIdsByTask(taskId)` with hash field lookup if acceptable.
- Replace `activeWorkerCountForTask(taskId)` with hash length if acceptable.
- Remove writes to `task:{taskId}:active-workers`.
- Remove the keyspace method, tests, docs, and residue scans after all callers
  are moved. Do not leave an unused compatibility method.
- Keep task-worker active counts as a WorkerRegistry projection, not task
  lifecycle truth.

Acceptance:

- Clean runtime no longer creates `...:task:{taskId}:active-workers`.
- `WorkerRegistryContractTest` still proves active worker ids and counts.
- Release/final paths remove worker fields when counts reach zero.
- No second active-worker structure exists for the same task-worker fact.

Suggested verification:

```powershell
.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am -Dtest=WorkerRegistryContractTest,RedisWorkerRegistryTest test
rg -n "taskActiveWorkersSet|active-workers" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
```

## WKRK-3: Reverse Bucket Membership Shape Convergence

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

Option B:

```text
group:{groupId}:slots:{slotShard}
  HASH
  field = workerId
  value = WorkerSlot plus derived bucket membership
```

Option A is the safer first target because it keeps candidate-index cleanup
separate from canonical slot payload while reducing key count from `O(workers)`
to `O(group * membershipShard)`.

Scope:

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

Suggested verification:

```powershell
.\mvnw.cmd -pl platform_infra/mass-runtime-redis -am -Dtest=RedisWorkerRegistryTest test
.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am -Dtest=WorkerRegistryContractTest test
rg -n "workerBucketMembershipSet|bucket-membership" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
```

## WKRK-4: Candidate Acquisition Must Be Bounded

Goal:

Make candidate buckets scalable beyond small local sample sizes.

Current issue:

`RedisWorkerRegistry.acquireCandidates(...)` currently reads the whole candidate
bucket with `SMEMBERS`, sorts all worker ids, and only then samples. This is not
million-worker-ready even if key count is low.

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
- Keep Stage-2 reserve/admission as correctness gate.
- Preserve node-scoped candidate buckets.
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

Suggested checks:

```powershell
rg -n "smembers\\(bucketKey\\)|groupCandidateBucket\\(|nodeCandidateBucket\\(" platform_infra/mass-runtime-redis/src/main/java
```

## WKRK-5: Slot And Heartbeat Sharding Decision

Goal:

Decide whether group-local `slots` and `heartbeat:0` keys need worker shards for
million-worker groups.

Current issue:

`group:{groupId}:slots` is group-local, which is better than a global hash.
However, `updateSlot(...)` watches the whole group `slots` hash. In Redis,
`WATCH` is key-level, so two unrelated worker updates in the same large group
can conflict.

Current heartbeat shape also uses a fixed `heartbeat:0` suffix, which implies a
future shard dimension but currently creates one zset per group.

Scope:

- Measure or model expected max workers per group.
- Decide whether to introduce:
  - `group:{groupId}:slots:{slotShard}`,
  - `group:{groupId}:heartbeat:{heartbeatShard}`,
  - matching cleanup iteration over group shard metadata.
- If sharding is deferred, record the threshold that will reopen the decision.
- Do not split `WorkerSlot` into writable `worker:meta` and `worker:occupancy`
  in this slice.

Acceptance:

- A decision exists for slot shard and heartbeat shard count.
- If sharding lands, every slot read/write/reserve/release path computes the
  same shard from worker id.
- Cross-shard operations are avoided on the hot path.
- Cleanup can iterate shards without scanning the entire Redis namespace.
- WorkerRegistry contract tests pass for memory and Redis implementations.

Suggested checks:

```powershell
rg -n "watch\\(slotsKey\\)|groupSlotsHash|groupHeartbeatDeadlinesZset|heartbeat:0" platform_infra/mass-runtime-redis/src/main/java
```

## WKRK-6: Diagnostic Index Review

Goal:

Keep diagnostic indexes only when they justify their cost.

Scope:

- Review `exclusive-leases`.
- Review `groups`.
- Review bucket discovery sets: `buckets` and `node-buckets`.
- For each diagnostic or cleanup index, record:
  - production caller,
  - max cardinality,
  - whether it can be rebuilt from canonical state,
  - whether it can be wrong briefly,
  - cleanup behavior when canonical state disappears.

Acceptance:

- `exclusive-leases` is either:
  - retained with a clear global diagnostic/cleanup reason, or
  - removed and replaced by `slotByWorkerId` / bounded group scan where
    appropriate.
- `groups`, `buckets`, and `node-buckets` have bounded cleanup reasons.
- No diagnostic index participates in reserve/admission without re-reading
  `WorkerSlot`.

Suggested checks:

```powershell
rg -n "exclusiveLeasesSet|exclusiveLeaseWorkerIds|isExclusiveLeaseHeld|groupCandidateBucketsSet|groupNodeCandidateBucketsSet|workerGroupsSet" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
```

## WKRK-7: Runtime Key Proof Handoff

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
rg -n "workerBucketMembershipSet|taskActiveWorkersSet|active-workers|worker:meta|worker:occupancy|available:\\{shard\\}" platform_infra xa-mass-worker-runtime xa-mass-engine roadmap doc --glob '!**/target/**'
.\mvnw.cmd -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am test
```

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

1. Every `xa:mass:runtime:v1:worker*` key family has a documented owner,
   lifecycle, cardinality formula, and proof reason.
2. `group:{groupId}:slots` remains the only canonical worker slot truth, or a
   later slice replaces it cleanly without dual writable truth.
3. No per-worker Redis key family remains unless it has a named, reviewed,
   non-replaceable runtime reason.
4. `task:{taskId}:active-workers` is either removed or justified by a measured
   query that `worker-active-count` cannot satisfy.
5. Candidate acquisition is bounded and does not materialize entire large
   bucket sets.
6. Slot mutation contention is either sharded or explicitly accepted with a
   documented threshold for reopening the decision.
7. Worker-id-only lookup via `worker:group` is either removed or documented as a
   lookup projection with guardrails.
8. Diagnostic indexes cannot drive reserve/admission without reading
   `WorkerSlot`.
9. Redis runtime baseline and active roadmaps agree with current code.
10. Clean-runtime recreation is sufficient for the new shape; no old/new Redis
    compatibility bridge remains.
11. `platform_infra/mass-runtime-api` remains the upper-layer contract, and
    Redis-specific physical shape is not visible to `xa-mass-worker-runtime` or
    engine scheduling.
12. Superseded key families, methods, tests, docs, and roadmap wording are
    removed rather than retained as legacy explanations.

## Open Decisions

- Should `worker:group` remain as one global hash, become sharded, or disappear
  after caller retargeting?
- Should reverse bucket membership live in group-sharded membership hashes or
  inside the encoded slot payload?
- What candidate sampling rule is acceptable for fairness and ranking?
- What slot shard count is appropriate for the largest expected worker group?
- Should `exclusive-leases` remain as a global diagnostic index?
- Should the Redis key names use cluster hash tags before distributed Redis
  deployment becomes a target?
