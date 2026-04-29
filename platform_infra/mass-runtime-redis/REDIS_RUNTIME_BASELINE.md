# Redis Runtime Baseline

Status: design/refactor reference, not current verified runtime behavior.

This file fixes the intended Redis ownership model for the future
`TaskWorkRuntime` implementation so the runtime path can grow without
reintroducing scans, engine-local Redis logic, or conflicting queue truth.

Use with:

- [README.md](./README.md)
- [../README.md](../README.md)
- [../../doc/HIGH_VOLUME_MODEL_BASELINE.md](../../doc/HIGH_VOLUME_MODEL_BASELINE.md)
- [../../doc/STATE_MACHINE_BASELINE.md](../../doc/STATE_MACHINE_BASELINE.md)

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
    - `workerContextId`
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
  - crash-safe replay hardening is a later phase, not hidden behavior in this one

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
