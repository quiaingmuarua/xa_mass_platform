# Redis Runtime Baseline

Status: Redis keyspace/design reference. Verify current behavior through the
implementation and runtime contract tests.

This file documents the Redis ownership model for the Redis-backed
`TaskWorkRuntime` implementation so the runtime path can evolve without
reintroducing scans, engine-local Redis logic, or conflicting queue truth.

Current implementation note:

- hot-path mutations now converge through Redis-scripted atomic operations for
  `enqueue`, `claimReady`, `applyResult`, `pollExpiredLeases`, and
  `discardTask`
- delayed promotion and read-side visibility checks still use bounded command
  flows around the same keyspace truth
- Redis-backed `WorkerRegistry` has a first-slice implementation using
  group-partitioned slot hashes, group/node candidate source buckets, per-bucket
  lifecycle deadline indexes, group heartbeat deadline indexes, a group-local
  bucket membership hash, and `WATCH` / `MULTI` / `EXEC` over the group-local
  slot hash. It is contract-tested but is not yet the default engine/server
  runtime switch.

Use with:

- [README.md](./README.md)
- [../README.md](../README.md)
- [../../xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md](../../xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md)
- [../../doc/TASK_LIFECYCLE_BASELINE.md](../../doc/TASK_LIFECYCLE_BASELINE.md)

## 1. Scope

This baseline is only about the runtime hot path:

- ready work
- delayed visibility
- active lease truth
- expiry indexes
- runtime counters
- precise task discard

It does not define:

- task/task-message control-plane storage
- HTTP/API query models
- engine task orchestration rules
- transport protocol payloads

## 2. Rules

1. No `SCAN` or task-wide enumeration on hot paths.
2. Redis runtime owns queue, lease, delayed, and counter indexes behind
   `TaskWorkRuntime`.
3. Engine continues to own task-level orchestration, matching, finality, and
   trace interpretation.
4. Runtime recovery must start from explicit indexes, not from `TaskMsg`
   projections.
5. `discardTask(taskId)` must remain precise and bounded by task-owned member
   indexes; it must not scan global queues.

## 3. Namespace

Default namespace:

`xa:mass:runtime:v1`

The keyspace owner class is:

- `com.xa.mass.runtime.redis.RedisTaskWorkKeyspace`

## 4. Redis Keys

Global indexes:

- `...:tasks`
  - `SET`
  - member: `taskId`
  - bounded task registry used for exact shutdown cleanup without namespace scan
- `...:ready:tasks`
  - `ZSET`
  - member: `taskId`
  - score: first-known ready timestamp for that task
  - used by `readyTaskIds(limit)` so dispatch recovery never scans all tasks
- `...:delayed:work`
  - `ZSET`
  - member: encoded `taskId + messageId`
  - score: `nextVisibleAtMillis`
  - used to promote due delayed work into task ready queues without scanning
- `...:lease:expiry`
  - `ZSET`
  - member: encoded `taskId + messageId`
  - score: `leaseExpireAtMillis`
  - used by `pollExpiredLeases(limit, now)`
- `...:stats`
  - `HASH`
  - runtime-wide counters

Per-task indexes:

- `...:task:{taskId}:ready`
  - `LIST`
  - FIFO ready queue of message ids
- `...:task:{taskId}:delayed`
  - `ZSET`
  - member: `messageId`
  - score: `nextVisibleAtMillis`
- `...:task:{taskId}:active`
  - `SET`
  - member: encoded `taskId + messageId`
  - active lease membership for `activeLeases(taskId)`
- `...:task:{taskId}:members`
  - `SET`
  - member: `messageId`
  - exact ownership set for `discardTask(taskId)`
- `...:task:{taskId}:stats`
  - `HASH`
  - task-local counters used by `stats(taskId)`

Per-item records:

- `...:task:{taskId}:work:{messageId}`
  - `HASH`
  - fields:
    - `eventCode`
    - `payloadJson`
    - `payloadRef`
    - `retryCount`
    - `maxRetryCount`
    - `shardKey`
    - `nextVisibleAtMillis`
    - `createdAtMillis`
- `...:task:{taskId}:lease:{messageId}`
  - `HASH`
    - fields:
    - `leaseToken`
    - `workerId`
    - `batchId`
    - `retryCount`
    - `leaseExpireAtMillis`
    - `leasedAtMillis`

Per-worker index:

- `...:worker:{workerId}:active`
  - `SET`
  - member: encoded `taskId + messageId`
  - supports `hasActiveLeaseForWorker(taskId, workerId)` without task scans

## 5. Operation Mapping

### enqueue

- reject duplicate when work hash or active lease hash already exists
- add `taskId` into `...:tasks`
- add `messageId` into `task:{taskId}:members`
- write work hash
- if `nextVisibleAt > now`:
  - add encoded member to global delayed zset
  - add `messageId` to task delayed zset
  - increment delayed counters
- else:
  - push `messageId` into task ready list
  - ensure `taskId` exists in global ready-task zset
  - increment ready counters
- increment total counters

### claimReady(taskId, ...)

Must be atomic, expected as a Lua-script boundary.

Responsibilities:

- promote due delayed work for this task into the ready list before claim
- pop bounded ready items FIFO from `task:{taskId}:ready`
- create one active lease hash per claimed item
- add active membership to task and worker sets
- add encoded members to lease-expiry zset
- remove `taskId` from `ready:tasks` when the task queue becomes empty
- update task/runtime counters in the same atomic path

### applyResult

Must validate the active lease first.

- reject when lease hash is missing
- reject stale result when provided `leaseToken` does not match
- on every accepted result:
  - remove lease hash
  - remove task/worker active membership
  - remove lease-expiry zset member
- on success:
  - delete work hash
  - remove `messageId` from task members
  - increment success counters
- on retryable failure with remaining budget:
  - update work hash retry count and next visible time
  - route back to delayed or ready index
  - increment ready/delayed counters, decrement inflight counters
- on final failure/expiry:
  - delete work hash
  - remove `messageId` from task members
  - increment failed or expired counters

### pollExpiredLeases(limit, now)

- poll bounded members from `lease:expiry` with score `<= now`
- read their lease hashes and return active lease records
- current phase should mirror memory-runtime semantics:
  - expired members are popped from the expiry index before engine finalization
  - crash-safe replay hardening is outside the current implementation

### discardTask(taskId)

Must stay bounded by task-owned indexes:

- read `task:{taskId}:members` for exact work keys
- read `task:{taskId}:active` for exact active leases
- delete:
  - task ready list
  - task delayed zset
  - task stats hash
  - all work hashes
  - all lease hashes
  - worker active membership entries corresponding to active leases
- remove encoded members from global delayed and lease-expiry zsets
- remove `taskId` from ready-task zset
- remove `taskId` from `...:tasks`

## 6. Why This Shape

- `readyTaskIds(limit)` needs a global task-ready index; scanning every task key
  is not acceptable at high volume.
- delayed visibility needs a global due index; otherwise due retries become
  invisible until some unrelated task-local action touches the task.
- `discardTask(taskId)` needs an exact task-owned member set; otherwise delete
  becomes a global cleanup problem.
- worker-active membership must be explicit; checking whether a worker still
  owns active work cannot degenerate into task scans.

## 7. Non-Goals For This Slice

- no bootstrap default wiring
- no public server/sdk Redis mode switch
- no task-detail query model
- no storage extraction
- no hidden fallback that scans Redis keys when an index is missing

## 8. Worker Registry Keys

Worker runtime registry state is runtime truth, not control-plane worker CRUD.
The first Redis slice keeps worker slots group-partitioned and validates stale
bucket candidates again at reservation time.

Namespace:

`{runtimePrefix}:worker`

Owner class:

- `com.xa.mass.runtime.redis.RedisWorkerRegistryKeyspace`

Global worker indexes:

- `...:worker:group`
  - `HASH`
  - field: `workerId`
  - value: `groupId`
  - supports worker-id semantic APIs such as `slotByWorkerId(workerId)`,
    `workerMeta(workerId)`, worker-id admission defaults, and dispatch-gate
    defaults without relying on worker id prefix
- `...:groups`
  - `SET`
  - member: `groupId`
  - supports bounded heartbeat cleanup across group-local heartbeat indexes
- `...:exclusive-leases`
  - `SET`
  - member: `workerId`
  - support index for `exclusiveLeaseWorkerIds()`; avoids scanning all group
    slot hashes for current diagnostic/support API output

Per-group heartbeat, slot, and candidate indexes:

- `...:group:{groupId}:heartbeat:0`
  - `ZSET`
  - member: `workerId`
  - score: `lastHeartbeatMillis + heartbeatFreshnessMillis`
  - supports bounded stale worker discovery inside a worker group; expiry cleanup
    reads at most the remaining cleanup limit from each group zset

- `...:group:{groupId}:slots`
  - `HASH`
  - field: `workerId`
  - value: encoded `WorkerSlot`
  - canonical Redis worker slot payload for the current worker-registry slice
  - owns current worker metadata projection, dispatch-gate inputs, reservation
    counters, active lease counters, exclusive lease flag, and removing flag as
    one encoded aggregate
  - removed-slot cleanup uses bounded `HSCAN` field reads before deciding which
    removable fields to reclaim
  - upper runtime callers should not depend on this physical aggregate; they
    should use `WorkerRegistry` semantic methods such as `workerMeta(workerId)`,
    group-scoped scheduling admission operations when group evidence is present,
    support-only worker-id lookup methods, and dispatch-gate operations
- `...:group:{groupId}:bucket:{candidateBucketKey}:workers`
  - `SET`
  - member: `workerId`
  - group-level candidate source bucket
  - source membership for WorkerGroup / policy bucket narrowing; it is not
    canonical slot lifecycle eligibility
  - `candidateBucketKey` is produced by the injected runtime-api
    `WorkerCandidateBucketPolicy`; Redis must not hardcode or interpret worker
    attribute dimensions
  - support acquisition uses bounded `SRANDMEMBER`; scheduling acquisition uses
    the sibling lifecycle deadline zset below
- `...:group:{groupId}:bucket:{candidateBucketKey}:workers:slot-lifecycle-deadlines`
  - `ZSET`
  - member: `workerId`
  - score: heartbeat deadline millis
  - derived scheduling index for group-level candidate acquisition
  - maintained from canonical `WorkerSlot` truth on slot upsert/heartbeat
    refresh, dispatch-gate mutation, removing-slot mutation, and bucket cleanup
  - dispatch-disabled and removing workers are removed from this zset while
    ordinary source bucket membership may remain available for metadata and
    maintenance paths
  - scheduling acquisition reads bounded future-deadline members and still
    revalidates slot lifecycle and reserve before dispatch binding
- `...:group:{groupId}:buckets`
  - `SET`
  - member: `candidateBucketKey`
  - bounded cleanup discovery for group buckets
- `...:group:{groupId}:node:{adapterNodeId}:bucket:{candidateBucketKey}:workers`
  - `SET`
  - member: `workerId`
  - node-scoped placement candidate bucket
  - also backs current complete-set node/group maintenance lookup; paged
    maintenance is deferred to
    `roadmap/WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md`
- `...:group:{groupId}:node:{adapterNodeId}:bucket:{candidateBucketKey}:workers:slot-lifecycle-deadlines`
  - `ZSET`
  - member: `workerId`
  - score: heartbeat deadline millis
  - node-scoped scheduling index with the same derived-truth rules as the
    group-level lifecycle deadline zset
- `...:group:{groupId}:node-buckets`
  - `SET`
  - member: encoded `adapterNodeId + candidateBucketKey`
  - bounded cleanup discovery for node buckets
- `...:group:{groupId}:bucket-membership`
  - `HASH`
  - field: `workerId`
  - value: encoded list of group/node bucket keys currently containing the worker
  - reverse cleanup projection for `RedisWorkerRegistry` bucket updates and slot
    removal
  - group-local replacement for the retired per-worker
    `...:group:{groupId}:worker:{workerId}:bucket-membership` key family
  - this is not scheduling truth; stale bucket members remain correctness-neutral
    because reserve re-validates the current slot

Per-task worker occupancy indexes:

- `...:task:{taskId}:worker-active-count`
  - `HASH`
  - field: `workerId`
  - value: active lease count for that task-worker pair
  - supports `activeWorkerIdsByTask(taskId)`,
    `activeWorkerCountForTask(taskId)`, and
    `activeLeaseCountByTaskWorker(taskId, workerId)` without a parallel
    `active-workers` set

First-slice constraints:

- Redis 7.4 hash field TTL is an optional cleanup optimization, not required
  for correctness.
- `WorkerRegistry.tryReserve(...)` re-validates slot existence, removing flag,
  heartbeat freshness, dispatch gates, exclusive lease, and capacity inside the
  Redis mutation path. The first slice uses `WATCH` on the group-local slot hash;
  finer-grained Lua mutation is the next optimization if same-group contention
  becomes material.
- Candidate buckets may be briefly stale; stale candidates are rejected by
  the scheduling acquisition deadline zset, source guard, or Stage-2
  reservation and removed through bounded cleanup. Stale bucket cleanup samples
  at most the remaining cleanup limit from each group bucket.
- Heartbeat expiry cleanup is bounded at the per-group zset read. Group
  discovery still uses `...:groups`; sharding this index is deferred until group
  cardinality becomes a demonstrated deployment issue.
- This implementation does not introduce a single global worker hash and does
  not make Redis worker registry the server default.
- This implementation intentionally does not add writable
  `worker:meta:{workerId}`, `worker:occupancy:{workerId}`, or
  `worker:group:{groupId}:available:{shard}` keys. Adding those beside
  `group:{groupId}:slots` would create parallel writable truth. A future split
  must replace or demote the slot aggregate in the same clean-runtime recreation
  slice.
