# Redis Runtime Baseline

Status: current `mass-runtime-redis` keyspace/design reference.

This module no longer owns task item scheduling/runtime state. The old
`RedisTaskWorkRuntime` / `RedisTaskResultRuntime` family has been removed after
the task-runtime serving-lane cutover. Task item ready backlog, active leases,
retry/finality, progress, and final-result rows belong to
`../../xa-mass-task-runtime` and are implemented by
`../mass-task-runtime-redis`.

This baseline covers only the Redis surfaces that still belong to
`mass-runtime-redis`:

- worker registry runtime SPI;
- worker score-band slot runtime SPI;
- shared keyed queue primitives.

## Worker Registry

Worker runtime registry state is runtime truth, not control-plane worker CRUD.
The Redis implementation keeps worker slots group-partitioned and validates
stale bucket candidates again at reservation time.

Namespace:

```text
xa:mass:runtime:worker
```

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
  - support index for `exclusiveLeaseWorkerIds()` without scanning all group
    slot hashes

Per-group indexes:

- `...:group:{groupId}:heartbeat:0`
  - `ZSET`
  - member: `workerId`
  - score: `lastHeartbeatMillis + heartbeatFreshnessMillis`
  - bounded stale worker discovery inside one worker group
- `...:group:{groupId}:slots`
  - `HASH`
  - field: `workerId`
  - value: encoded `WorkerSlot`
  - canonical Redis worker slot payload for the current registry slice
- `...:group:{groupId}:dispatch-blocks`
  - `HASH`
  - field: `workerId`
  - value: encoded dispatch-block evidence

Rules:

1. `WorkerRegistry.tryReserve(...)` must revalidate slot existence, removing
   flag, heartbeat freshness, dispatch gates, exclusive lease, and capacity
   inside the Redis mutation path.
2. Group-local stale candidate cleanup is allowed, but candidate membership is
   evidence, not lifecycle truth.
3. Do not introduce a single global worker hash or DB-row-style worker CRUD
   model in this module.
4. Do not persist worker capability/project/event truth here. Worker capability
   truth belongs outside runtime registry state.

## Worker Score-Band Slot Runtime

The score-band slot runtime owns current score/meta state for worker selection
mechanics used by `xa-mass-worker-runtime`.

Namespace:

```text
xa:mass:runtime:worker:score-band
```

Owner class:

- `com.xa.mass.runtime.redis.RedisWorkerScoreBandSlotKeyspace`

Keys:

- `...:score:{homeBucketId}`
  - `ZSET`
  - member: worker slot id
  - score: score-band ordering value
- `...:meta:{homeBucketId}`
  - `HASH`
  - field: worker slot id
  - value: encoded `WorkerScoreBandSlotMetadata`

Rules:

1. Score/meta state is worker-runtime selection evidence. It must not become
   task lifecycle, task lease, transport route, or result finality truth.
2. Transition evidence is audit/support data. Current score/meta state remains
   the runtime owner surface.
3. Redis implementation details stay inside this module; callers use
   `WorkerScoreBandSlotRuntime`.

## Redis Keyed Queue Primitive

The `queue` package provides generic keyed queue mechanics for runtime modules.
It owns Redis queue keys and bounded blocking-poll mechanics, but it does not
own task, transport, worker, or result lifecycle semantics.

Owner classes:

- `com.xa.mass.runtime.redis.queue.RedisKeyedQueueNamespace`
- `com.xa.mass.runtime.redis.queue.RedisKeyedBlockingQueueStore`

Rules:

1. Queue entries are opaque to the primitive.
2. Queue max-size/backpressure is a primitive outcome, not lifecycle truth.
3. Ack, visibility timeout, retry, task result finality, and transport delivery
   semantics must be owned by the caller module when needed.
4. Do not add task-runtime DTOs, transport DTOs, or worker-runtime DTOs to the
   Redis queue primitive package.

## Guardrails

- Do not reintroduce `RedisTaskWorkRuntime`, `RedisTaskResultRuntime`, or their
  keyspace classes in this module.
- Do not make `mass-runtime-redis` depend on `xa-mass-engine`, server, embedded
  SDK, or task-runtime starter modules.
- Do not let worker registry keys drive task scheduling truth directly; worker
  selection consumes worker-runtime evidence through the owning runtime.
- Do not let shared queue primitives grow ack/visibility/result lifecycle
  semantics without a separate owner decision and public primitive contract.
