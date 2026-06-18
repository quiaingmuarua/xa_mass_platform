# Redis Task Runtime Shape Convergence Roadmap

Status: proposed direction document.

This roadmap converges the Redis physical layout behind task runtime truth:
`TaskWorkRuntime` and `TaskResultRuntime`. It follows the same lesson as the
worker-runtime cleanup: first keep upper runtime and engine callers independent
of physical Redis shape, then replace the Redis-internal layout. For task work,
the target direction is now Redis Stream based, eventual-consistency delivery:
task item delivery is at-least-once, result convergence is idempotent, and
Redis does not select workers. For result rows, the first target remains
removing one key per stable-final visible row.

```text
xa:mass:runtime:v1:task:{taskId}:work:{messageId}
xa:mass:runtime:v1:result:task:{taskId}:visible:{messageId}
```

Read with:

- [INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md)
- [platform_infra/README.md](../platform_infra/README.md)
- [mass-runtime-redis/README.md](../platform_infra/mass-runtime-redis/README.md)
- [REDIS_RUNTIME_BASELINE.md](../platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md)
- [HIGH_VOLUME_MODEL_BASELINE.md](../xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md)
- [REDIS_TASK_RUNTIME_SHAPE_CONVERGENCE_INVENTORY.md](REDIS_TASK_RUNTIME_SHAPE_CONVERGENCE_INVENTORY.md)

## Current Code Observations

- `TaskWorkRuntime` is already the semantic runtime contract consumed by
  engine: enqueue, ready task discovery, claim, result apply with context,
  expiry polling, active lease reads, stats, recent final receipts, and task
  discard.
- Current `TaskWorkRuntime#claimReady(...)` accepts worker claim targets and
  `ClaimedTaskWork` returns a selected worker/batch. That is current code, but
  it is also the main semantic blocker for the Stream direction: Redis runtime
  should reserve/deliver a task item to the engine; Scheduling Plane should
  select the concrete worker after reservation.
- `TaskResultRuntime` is already the semantic runtime contract for stable-final
  public result rows, task-local result windows, staged callback repair anchors,
  and result-side attempt-closed/logical-final/progress barriers.
- Engine mainline does not need Redis key names to perform scheduling,
  assignment, result convergence, or lifecycle transitions.
- `RedisTaskWorkRuntime` currently owns physical Redis mutation through Lua
  scripts and direct Redis reads. Many scripts protect strong multi-key claim,
  lease, counter, and cleanup semantics that should be reduced when work
  delivery moves to Stream/Pending Entries List semantics.
- `RedisTaskResultRuntime` currently owns result-stage, visible-row, pending
  barrier, and repair-state Redis mutation through Lua scripts and direct Redis
  reads.
- `RedisTaskWorkKeyspace` currently exposes per-message work and lease key
  builders: `taskWorkHash(taskId, messageId)` and
  `taskLeaseHash(taskId, messageId)`.
- `RedisTaskResultKeyspace` currently exposes per-message visible result row
  keys through `taskVisibleRow(taskId, messageId)`, while
  `taskVisibleZset(taskId)` stores the task-local result order.
- `RedisTaskWorkRuntimeTest` and `RedisTaskWorkKeyspaceTest` intentionally
  assert current Redis key shape and must move with Redis implementation
  changes.
- `RedisTaskResultRuntimeTest` and `RedisTaskResultRuntimeContractTest`
  intentionally prove result runtime behavior and must be extended before
  changing result visible-row shape.
- Live Redis evidence from the user-provided key shows one ready work hash with
  8 fields and `MEMORY USAGE=772`; the sampled task had 50 ready work hashes
  plus 3 task-level keys, so key count grows linearly with ready work count.
- Live Redis evidence for `xa:mass:runtime:v1:result:task:*` shows the same
  pattern for stable-final result rows: the sampled tasks each had
  `visible_zcard=50` and `visible_row_keys=50`; one visible row string used
  `MEMORY USAGE=1960`.
- Current work hashes persist empty optional fields such as `shardKey`,
  `payloadRef`, and `nextVisibleAtMillis`.
- Current result rows are encoded with `GsonBuilder.serializeNulls()` and carry
  worker/attempt/context fields. That is a fat runtime row. Narrowing it into
  a transparent public result shape requires a separate runtime-api/server/SDK
  contract slice; this Redis shape roadmap should not silently change that
  contract while removing per-message keys.
- `eventCode` currently appears in server append APIs, runtime work values,
  engine dispatch binding, transport/SDK worker invocation, and result
  snapshots. That is historical API design residue. The target owner is worker
  handler dispatch identity, but removing it from server API / engine /
  transport surfaces is deferred to a later boundary roadmap.
- `shardKey` is stored and loaded by runtime values, but current production
  engine creation passes `null` and no Redis claim/apply path uses it.
- Historical lock-reduction notes already identified task-local work/lease
  hashes and task-local final result hashes as future migrations, but that
  archived roadmap explicitly deferred key-shape migration.

## Owner Review

`TaskWorkRuntime` and `TaskResultRuntime` belong to
`platform_infra/mass-runtime-api`. They define the semantic contracts that
engine and other runtime callers may consume.

`RedisTaskWorkRuntime` and `RedisTaskResultRuntime` belong to
`platform_infra/mass-runtime-redis`. They own Redis keys, Redis Stream consumer
group mechanics, Redis value encoding, any remaining Lua scripts, and
Redis-specific proof.

Engine may consume `TaskWorkRuntime` values such as `TaskWorkEnvelope`,
current `ClaimedTaskWork` / future item-reservation values, `TaskWorkResult`,
`RuntimeResultApplyContext`, `RecentFinalWorkReceipt`, and `TaskWorkStats`.
Engine may also consume `TaskResultRuntime` values such as
`TaskResultCallbackDraft`, `TaskResultFinalDraft`, `TaskResultRuntimeRow`,
`TaskResultWindow`, and result repair/barrier outcomes. Engine must not
consume Redis key families, Redis field names, encoded payload formats,
Stream ids, consumer group names, Pending Entries List details, task-local hash
layout, or migration details.

Storage, server, SDK, transport, trace, and worker-runtime must not become
owners of task runtime Redis physical shape. They may observe behavior through
the shared runtime contracts, public APIs, trace, or diagnostics.

`eventCode` belongs to worker handler selection/dispatch identity in the long
term. Current server API, engine, transport, and SDK exposure is historical
surface residue. This roadmap records that residue but does not remove those
surfaces.

## Boundary Decision

Separate the work into two explicit layers:

```text
semantic runtime surface
  TaskWorkRuntime, TaskResultRuntime, and runtime-api values
  engine-facing behavior and shared memory/Redis contract tests
  TaskWorkRuntime converges from strong worker-target claim to at-least-once
  item reservation/delivery semantics

Redis physical shape
  RedisTaskWorkRuntime, RedisTaskWorkKeyspace
  RedisTaskResultRuntime, RedisTaskResultKeyspace
  Redis Streams, consumer groups, eventual indexes, value codecs, remaining Lua
  no engine/server/sdk/transport dependency on key or value layout
```

Do not add a new engine bridge just to hide Redis. The existing
runtime-api contracts are the hiding boundary. The first slice should make that
boundary executable through guards and caller inventory, then later slices can
safely change Redis internals.

Keep Redis physical-shape convergence separate from public/server/SDK contract
convergence. If a slice changes `TaskResultRuntimeRow`, public result
snapshots, server append contracts, or worker invocation event identity, it is
no longer just a Redis key-shape slice and needs its own owner decision.

This roadmap does intentionally change the task work runtime delivery contract:
work delivery may be at-least-once and counters/indexes may be eventually
consistent, while final result convergence remains idempotent by
`taskId + messageId`. That is the enabling decision for the Stream direction and
must be reflected in `TaskWorkRuntime` contract tests before Redis internals are
rewired.

## Target Shape

Preferred staged Redis physical direction for work runtime:

```text
...:task:{taskId}:work
  STREAM
  entry fields:
    messageId
    payloadJson or payloadRef
    retryCount
    maxRetryCount
    createdAtMillis
    eventCode only as current handler identity residue, not worker selector

...:task:{taskId}:message-index
  HASH messageId -> streamId
  optional enqueue idempotency / duplicate detection index

...:task:{taskId}:recent-final
  HASH messageId -> encoded bounded recent-final receipt
```

Task work is not shaped like worker lifecycle state. Worker bucket fan-out is
useful for long-lived worker registry/candidate dimensions; task work is
normally consumed quickly and should move to a task-local Stream rather than a
large per-item key family or worker buckets.

The task Stream is delivery/reservation truth between Redis runtime and engine,
not worker assignment truth. Redis may use a runtime consumer group,
`XREADGROUP`, `XAUTOCLAIM`, and `XACK` to provide at-least-once item delivery
to engine instances. Scheduling Plane still selects the concrete worker after
the item is reserved. Final result commit must happen before `XACK`; if `XACK`
fails, duplicate delivery is tolerated and absorbed by idempotent final result
commit.

Keep or replace these discovery and support indexes under eventual-consistency
rules:

- `...:ready:tasks` as a dispatch hint, not correctness truth
- `...:delayed:work` and/or `...:task:{taskId}:delayed` for delayed/retry
  visibility because Redis Stream has no native delayed message
- `...:task:{taskId}:stats` as derived diagnostics/progress input that can be
  repaired from stream/final result truth
- stream pending entries as inflight/reservation evidence instead of one
  `...:task:{taskId}:lease:{messageId}` key per item
- `...:worker:{workerId}:active` should not be owned by task work runtime after
  worker selection moves out of Redis claim; keep only if a separate worker
  occupancy/resource-release proof still needs it

Preferred staged Redis physical direction for result runtime:

```text
...:result:task:{taskId}:visible
  ZSET messageId -> seq

...:result:task:{taskId}:final
  HASH messageId -> encoded stable-final result value

...:result:task:{taskId}:seq
  STRING task-local final sequence counter
```

This removes one Redis key per stable-final result row while keeping the
task-local ordered result window. The `visible` ZSET remains the ordering
index; the `final` HASH owns the row payload. The first result-row slice should
preserve current `TaskResultRuntime` semantics while changing only Redis
physical shape. Stage and barrier repair keys are not folded into this first
result-row slice because they protect repair and idempotency behavior, not
result window payload storage.

Keep these result discovery and repair indexes unless a slice proves a safe
replacement:

- `...:result:stages`
- `...:result:task:{taskId}:stages`
- `...:result:task:{taskId}:message:{messageId}:stages`
- `...:result:pending:attempt-closed`
- `...:result:pending:logical-final`
- `...:result:pending:progress`
- barrier claim keys until fully converged barrier cleanup is proven against
  duplicate callback and repair tests

Value encoding rules:

- encode optional stream entry fields only when nonblank or non-default
- remove `shardKey` from Redis work stream entries unless a current production
  caller is proven
- do not let `eventCode` drive Redis worker selection. While current
  runtime/engine/transport contracts still carry it, keep it only as handler
  identity residue inside the stream entry or existing runtime value. Removing
  it from server API, engine dispatch binding, transport frames, and SDK worker
  invocation is a later roadmap.
- keep inline input support, but keep user input distinct from platform
  envelope metadata; allow `payloadRef` to be the preferred large-input
  carrier
- keep retry/max-retry and timestamps needed by retry, delayed visibility,
  result context, and ready hint repair
- for result rows, prefer reconstructing `taskId` and `messageId` from the
  task-local key and hash field instead of repeating them in every value
- for result rows, omit null optional fields instead of using
  `serializeNulls()` where this can be done without changing current
  `TaskResultRuntimeRow` semantics
- transparent public result rows are the target direction, but not part of the
  Redis key-shape slice. A later runtime-api/server/SDK convergence should
  decide whether public final results keep only `seq`, status, completed time,
  and opaque output/error payloads.
- keep repair/barrier progress outside any future public final result value
  unless a later slice proves a compact internal marker can be kept without
  exposing or bloating the result row
- keep stream entry encoding package-private inside `mass-runtime-redis`
- enforce a task-level maximum stream length or terminal cleanup policy so one
  task stream cannot grow without bound

## Do Not Start With

Do not start by changing engine assignment, result, query, or lifecycle callers
to know about Redis Streams, stream ids, consumer groups, pending entries, hash
fields, or encoded values.

Do not preserve the current `claimReady(taskId, workers, options)` worker-target
selection semantics while calling the result a Stream migration. The Stream
direction requires Redis to deliver/reserve items and Scheduling Plane to select
workers after reservation.

Do not start by removing `eventCode` from server APIs, engine dispatch binding,
transport frames, or SDK worker invocation. That is real boundary work, but it
is not required to remove one-key-per-item Redis shapes.

Do not start by narrowing `TaskResultRuntimeRow` or public SDK/server result
snapshots while moving Redis visible rows into a task-local hash. Preserve the
current semantic contract first; narrow it in a later contract slice.

Do not start by deleting current Redis keys before the at-least-once delivery
contract, keyspace tests, and Redis implementation tests have a replacement
shape.

Do not fold result barrier/stage repair state into the visible-result row
migration before stable-final row cardinality is fixed and result repair tests
still pass.

Do not keep old and new Redis physical paths as two long-lived live tracks.
This repo has no compatibility obligation for superseded internal runtime
key layouts. If a clean Redis runtime recreation or namespace bump is needed,
make that explicit in the slice.

## Non-Goals

- No change to public task lifecycle states, terminal policy, or public
  scheduling policy configuration.
- Worker selection result should remain Scheduling Plane owned. This roadmap may
  move worker selection out of `TaskWorkRuntime` claim semantics, but it must
  not change which scheduling owner makes the decision.
- No change to the public server/SDK task APIs.
- No removal of `eventCode` from server append APIs, engine dispatch binding,
  transport payloads, SDK worker invocation, or public result snapshots.
- No narrowing of `TaskResultRuntimeRow`, server result DTOs, or SDK result
  snapshots in the Redis physical-shape slices.
- No result-history retention policy in this roadmap. Task result rows are not
  intended as long-term history; a later terminal-task cleanup slice may retain
  terminal tasks for a bounded duration and then remove their runtime keys.
- No storage/JDBC task-message migration.
- No trace/audit materialization for runtime queue truth.
- No transport dispatch handoff redesign.
- No replacement of `TaskWorkRuntime` or `TaskResultRuntime` with an
  engine-owned facade.
- No Redis Pub/Sub ownership for queue or lease truth. Redis Stream is the
  target task-item delivery mechanism, not durable history or trace storage.
- No in-place migration obligation for pre-release Redis runtime keys unless a
  separate operator requirement is added.

## RTR-0 Inventory And Field Classification

Goal: freeze current callers, key families, and value fields before changing
physical shape.

Scope:

- Maintain
  `REDIS_TASK_RUNTIME_SHAPE_CONVERGENCE_INVENTORY.md`.
- Classify every task-work and task-result Redis key family as semantic truth,
  discovery index, repair support, cleanup residue, diagnostic support, or
  migration target.
- Classify every work/lease/recent-final/result-row field as claim hot-read,
  apply hot-read, result-window read, recovery read, optional field, or
  residue.
- Record live Redis shape evidence for at least one representative task.
- Separate engine/runtime contract callers from Redis implementation tests.

Acceptance:

- Inventory names all current per-message key families:
  `work:{messageId}`, `lease:{messageId}`, `recent-final:{messageId}`, and
  `result:task:{taskId}:visible:{messageId}`.
- Inventory states which fields may be omitted or removed from encoded Redis
  values.
- Inventory names the engine-facing callers and shows they do not need Redis
  physical shape.
- No code behavior changes are required in this slice.

## RTR-1 Protect Engine And Runtime-API Boundary

Goal: make Redis physical shape unobservable to engine and other upper
runtime callers before changing it.

Scope:

- Add or extend an architecture guard that fails if upper runtime callers
  reference task-runtime Redis physical layout: `RedisTaskWorkKeyspace`,
  `RedisTaskResultKeyspace`, task-work/task-result key family strings, Redis
  field constants, or package-private value codecs.
- Do not ban legitimate assembly-only instantiation of
  `RedisTaskWorkRuntime` / `RedisTaskResultRuntime` by server or SDK bootstrap
  wiring. Bootstrap may choose implementations; controllers, services,
  engine, SDK public surfaces, transport dispatch/result code, and
  worker-runtime hot paths must not depend on task-runtime Redis key/value
  layout.
- Allow transport to depend on its own Redis delivery/queue infrastructure
  without treating `com.xa.mass.runtime.redis.queue.*` as task-runtime shape
  leakage.
- Keep legitimate Redis implementation and Redis tests allowlisted under
  `platform_infra/mass-runtime-redis`.
- Confirm engine calls only `TaskWorkRuntime` / narrow engine ports for task
  work runtime behavior.
- Confirm engine/result services call only `TaskResultRuntime` for stable-final
  result rows and result-side repair barriers.
- Confirm `TaskWorkRuntimeContractTest` and `TaskResultRuntimeContractTest`
  cover the engine-visible invariants required by later Redis shape changes.

Acceptance:

- Cross-module production code cannot reference Redis task runtime key or value
  layout outside explicit bootstrap assembly and `mass-runtime-redis`.
- No engine production change is needed for later Redis physical shape slices.
- Contract tests remain the primary semantic proof for memory and Redis.
- Redis implementation tests remain free to assert physical shape.

## RTR-2 Define Stream Delivery Contract And Keyspace

Goal: make the Stream direction executable by changing the task work runtime
contract before Redis internals move.

Scope:

- Introduce or rename runtime-api values so Redis runtime reserves/delivers task
  items without accepting worker claim targets. Exact class names are an
  implementation choice, but the target shape is:
  - enqueue remains idempotent by `taskId + messageId`
  - runtime delivery returns a task item reservation plus stream/delivery token
  - Scheduling Plane chooses the concrete worker after the reservation
  - result final commit happens before runtime acknowledgement
  - duplicate delivery is allowed and absorbed by idempotent final result
    convergence
- Remove worker event-scope filtering from the Redis claim contract target.
  `eventCode` may still travel as current handler identity residue, but Redis
  must not use it to select a worker.
- Define memory and Redis `TaskWorkRuntime` contract tests for at-least-once
  delivery, duplicate delivery after unacknowledged reservation, and ack after
  final commit.
- Add target Stream key builders in `RedisTaskWorkKeyspace` without retargeting
  Redis implementation yet:
  - task-local work stream key
  - optional task-local message-index key
  - delayed/retry visibility indexes
  - consumer group and consumer id naming rules
- Add target task-local result row hash builders in `RedisTaskResultKeyspace`
  without retargeting scripts yet.
- Decide stream growth control before RTR-3: `MAXLEN`/approx trim, task
  terminal cleanup, explicit task-level max unfinalized item count, or a
  combination. Do not reuse the existing `maxReadyItemsPerTask` name for a
  broader Stream semantic without renaming or documenting the difference.
- Define defaults for omitted stream entry fields: empty payload/ref, retry
  count `0`, no next-visible timestamp, no shard key.
- Add focused unit tests for stream entry encoding, omitted optional fields,
  target task-local key names, and consumer group naming.

Acceptance:

- Existing Redis runtime behavior and physical key tests still pass.
- New or updated runtime-api contract tests name at-least-once work delivery as
  the target behavior and do not require exclusive worker-target claim semantics.
- Engine-facing call sites have a clear migration path away from
  `claimReady(taskId, workers, options)` to reserve item first and select worker
  second.
- New codec tests prove compact stream entry round-trip for current work and
  recent-final fields.
- New codec tests prove compact value round-trip for current stable-final
  result semantics, including `taskId`/`messageId` reconstruction from key and
  hash field. Null optional field omission may be added only where it does not
  change `TaskResultRuntimeRow` behavior.
- No external module can access the codec, Stream key plan, consumer group
  naming, or Redis delivery token layout.
- `shardKey` has an explicit keep/remove decision before RTR-3.
- RTR-2 is not complete until Stream growth control, delayed/retry visibility,
  unacknowledged reservation reclaim, and ack-after-final rules are decided and
  have focused test candidates.

## RTR-3 Replace Per-Work Keys With A Task-Local Work Stream

Goal: remove the main key-cardinality problem for ready/delayed work payloads
and move task item delivery to Redis Stream semantics.

Scope:

- Replace `task:{taskId}:work:{messageId}` with one task-local Stream:
  `task:{taskId}:work`, using one entry per logical message.
- Use `XADD` for enqueue and `XREADGROUP` / `XAUTOCLAIM` for runtime item
  reservation. Stream entries carry task item envelope fields; worker selection
  is not performed by Redis.
- Keep an optional `messageId -> streamId` index only for enqueue idempotency or
  precise support reads. If it is unnecessary, omit it and prove duplicate
  enqueue behavior another way.
- Preserve delayed/retry visibility through an explicit ZSET or re-enqueue
  strategy because Redis Stream has no native delayed message. The chosen
  strategy must not create one Redis key per item.
- Apply stream growth control decided in RTR-2.
- Replace ready-head cleanup and claim Lua with Stream/Pending Entries List
  behavior. Any remaining Lua must be justified by a specific multi-key
  correctness need.
- Keep recent-final key family unchanged in this slice unless the change is
  trivial and already proven.
- Treat ready/delayed/global indexes as hints or support indexes unless a
  contract test requires strong consistency.
- Retarget `RedisTaskWorkKeyspaceTest` and `RedisTaskWorkRuntimeTest` from
  per-work keys to the task-local work Stream.

Acceptance:

- No Redis key is created per ready work payload; one task-local Stream owns
  task item delivery.
- A high-volume single-task Redis test proves task-local key count does not
  grow with ready work count and `XLEN` grows with item count instead.
- A stream growth-control test proves the chosen max/cleanup policy.
- `TaskWorkRuntimeContractTest` passes for Redis with at-least-once delivery
  semantics.
- `RedisTaskWorkRuntimeTest` proves concurrent enqueue idempotence, duplicate
  delivery after unacknowledged reservation, `XAUTOCLAIM`/reclaim behavior,
  delayed/retry visibility, ack after final commit, discard, and namespace
  isolation.
- Redis implementation proof covers old work-key absence after enqueue,
  reservation, retry, result apply/ack, support reads, and task discard.
- Live or test memory evidence shows the 50-work sample class no longer creates
  50 work keys; Stream evidence uses `XLEN`, `XPENDING`, and task-local key
  count.

## RTR-4 Retire Per-Lease Keys And Strong Claim Lua

Goal: make Stream pending entries the reservation/inflight evidence and remove
old per-lease key semantics.

Scope:

- Remove `task:{taskId}:lease:{messageId}` as the source of active lease truth.
  Reservation/inflight truth comes from Stream consumer group pending entries
  plus any narrow reservation metadata still needed by engine callback handling.
- Replace strong lease expiry polling with Stream idle/reclaim semantics
  (`XPENDING` / `XAUTOCLAIM`) and engine-owned expiry/result convergence.
- Retarget `applyResultWithContext(...)` so final result commit can obtain the
  needed reservation/work snapshot without requiring a per-message lease key.
  If a compact side hash is temporarily needed for callback correlation, it
  must be bounded and not become a second lease truth.
- Remove worker active ownership from task work runtime unless a separate
  worker occupancy/resource-release proof still requires a runtime-owned view.
- Retire or justify each Lua script. Claim/ready-head cleanup scripts should
  disappear in the Stream model; result apply may keep minimal Lua only if it
  protects ack-after-final or bounded metadata cleanup.

Acceptance:

- No Redis key is created per active lease payload.
- Unacknowledged reserved items can be reclaimed by another engine consumer
  after the configured idle threshold.
- Final result commit happens before `XACK`; duplicate delivery after failed
  ack is absorbed by idempotent final result commit.
- Old lease expiry polling and worker-active cleanup no longer define task item
  correctness. Any retained support view is documented as derived.
- Redis implementation proof covers old lease-key absence after reservation,
  result-apply/ack, reclaim, support reads, and task discard.
- Engine result convergence and resource-release tests still pass under
  at-least-once delivery semantics.

## RTR-5 Replace Per-Result Visible Row Keys With A Task-Local Result Hash

Goal: remove the key-cardinality problem for stable-final public result rows.

Scope:

- Replace `result:task:{taskId}:visible:{messageId}` string keys with one
  task-local result row hash: `result:task:{taskId}:final`, with
  `messageId -> encoded stable-final result value`.
- Keep `result:task:{taskId}:visible` as the task-local order ZSET unless a
  separate slice proves a better ordered-window index.
- Update `commitVisibleFinal(...)`, `readWindow(...)`,
  `getVisibleByMessageId(...)`, result repair candidate scanning, barrier
  claim/mark scripts, and `discardTask(...)` to use the task-local result row
  hash.
- Preserve current `TaskResultRuntime` semantic behavior in this slice. Do not
  narrow `TaskResultRuntimeRow`, public result DTOs, SDK snapshots, or result
  archive output while changing Redis physical shape.
- Keep a field-disposition note for the later transparent-result contract
  convergence, but do not require that contract move here.
- Keep staged callback keys, pending barrier ZSETs, and barrier claim keys
  unchanged in this slice unless tests prove the compaction is independent.
- Add or retarget `RedisTaskResultKeyspaceTest` so result key names have
  explicit coverage instead of being protected only through runtime behavior
  tests.
- Retarget `RedisTaskResultRuntimeTest` and `RedisTaskResultKeyspace` coverage
  from per-message visible row keys to the task-local result row hash.
- Retarget result Lua scripts and direct Redis reads explicitly for visible
  final commit, duplicate final commit, ordered window reads, visible-by-id,
  repair candidate scanning, barrier claim/mark, and task discard.

Acceptance:

- No Redis key is created per stable-final visible result row.
- A high-volume single-task Redis test proves result task-local key count does
  not grow with visible result count.
- `TaskResultRuntimeContractTest` passes for Redis without forcing public
  result-row transparency in this slice.
- `RedisTaskResultRuntimeTest` still proves duplicate final commit
  idempotence, ordered result windows, repair candidate scanning, barrier
  claim/mark behavior, and task discard.
- `RedisTaskResultKeyspaceTest` proves the retained result keys and the new
  `result:task:{taskId}:final` hash key name.
- Redis implementation proof covers old visible-row-key absence after final
  commit, duplicate commit, barrier claim/mark, read window, visible-by-id, and
  task discard.
- Live or test memory evidence shows the 50-result sample class no longer
  creates 50 `result:task:{taskId}:visible:{messageId}` keys.

## RTR-6 Compact Or Retire Recent-Final Per-Message Keys

Goal: reduce duplicate/late callback receipt key cardinality without weakening
runtime-first callback classification.

Scope:

- Either move `recent-final:{messageId}` into one task-local receipt hash, or
  replace it with `TaskResultRuntime` stable-final evidence only if
  duplicate/late callback proof remains runtime-first.
- Preserve bounded trim semantics or replace them with explicit TTL/cleanup
  semantics.
- Keep this separate from RTR-3/RTR-4/RTR-5 because duplicate/late callback
  behavior is result-convergence sensitive.

Acceptance:

- Duplicate callback replay, late stale replay, and no-active-lease paths still
  classify correctly without falling back to review/projection rows.
- Recent-final storage no longer creates one Redis key per receipt, or the
  roadmap records why a bounded per-message receipt key remains justified.
- Result runtime and engine convergence tests pass.

## Deferred Follow-Ups

These are related, but intentionally not part of the Redis key-shape execution
slices above:

- **Task item event identity convergence**: remove historical `eventCode`
  exposure from server append APIs, engine dispatch binding, transport frames,
  public result snapshots, and SDK worker invocation after a new handler
  identity boundary is designed. Until then, Redis shape slices must preserve
  existing behavior and only avoid making `eventCode` a separate Redis field.
- **Transparent result contract convergence**: narrow `TaskResultRuntimeRow`
  and public server/SDK result DTOs so final results are opaque worker outputs
  plus minimal status/error/timing facts. This has a different caller set and
  proof set from the Redis physical shape migration.
- **Terminal task runtime cleanup**: after a task reaches terminal state,
  retain task runtime/result keys for a bounded duration, then remove them.
  Result rows are not long-term history, so the result hash does not need a
  separate length cap inside this roadmap.

## RTR-7 Baseline, Guards, And Residue Cleanup

Goal: make the implemented Redis shape the documented current truth and block
regression.

Scope:

- Update `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md` from
  per-message work/lease/result key wording to the implemented task-local
  Stream work shape and task-local result hash shape.
- Update `platform_infra/mass-runtime-redis/README.md` only if role or guardrail
  wording changes.
- Remove stale tests that preserve old key names as a hidden second API.
- Add focused guard coverage against reintroducing one key per work or visible
  result item where a task-local Stream/result hash record is expected.
- Run a residue scan for old key builders, old field constants, and stale docs.

Acceptance:

- `rg "taskWorkHash\\(|taskLeaseHash\\(|taskVisibleRow\\(|:work:\\{messageId\\}|:lease:\\{messageId\\}|:visible:\\{messageId\\}"` has no stale production hits except intentionally retained migration notes or tests for old-shape absence.
- Baseline docs match current Redis implementation.
- Architecture guards prevent upper modules from depending on Redis physical
  task runtime shape.
- Roadmap completion criteria below are satisfied before archive.

## Suggested Implementation Order

1. Complete RTR-0 inventory and field decisions.
2. Implement RTR-1 guards before touching Redis shape.
3. Add RTR-2 Stream delivery contract, runtime-api test plan, and keyspace
   builders.
4. Implement RTR-3 task-local work Stream migration.
5. Implement RTR-4 lease-key retirement and Stream pending/reclaim semantics.
6. Implement RTR-5 task-local result row hash migration.
7. Implement RTR-6 recent-final migration or justified retention.
8. Update baseline docs and residue guards in RTR-7.
9. Handle deferred event identity, transparent result, and terminal cleanup
   roadmaps separately.

## Verification Candidates

Correct exact commands during implementation if module names or suite names
change.

```bash
mvn -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am test -Dtest=TaskWorkRuntimeContractTest,TaskResultRuntimeContractTest,RedisTaskWorkRuntimeContractTest,RedisTaskWorkRuntimeTest,RedisTaskWorkKeyspaceTest,RedisTaskResultRuntimeContractTest,RedisTaskResultRuntimeTest,RedisTaskResultKeyspaceTest
```

```bash
mvn -pl xa-mass-engine -am test -Dtest=TaskKernelLifecycleTest,TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest,TaskRedispatchCompetitionTest,SimpleTaskDispatchBinderTest,EngineSchedulingCoreArchitectureGuardTest
```

Redis shape spot checks:

```bash
redis-cli --raw --scan --pattern 'xa:mass:runtime:v1:task:<taskId>:work:*' | wc -l
redis-cli --raw TYPE 'xa:mass:runtime:v1:task:<taskId>:work'
redis-cli --raw XLEN 'xa:mass:runtime:v1:task:<taskId>:work'
redis-cli --raw XINFO GROUPS 'xa:mass:runtime:v1:task:<taskId>:work'
redis-cli --raw XPENDING 'xa:mass:runtime:v1:task:<taskId>:work' '<group>'
redis-cli --raw --scan --pattern 'xa:mass:runtime:v1:result:task:<taskId>:visible:*' | wc -l
redis-cli --raw ZCARD 'xa:mass:runtime:v1:result:task:<taskId>:visible'
redis-cli --raw HLEN 'xa:mass:runtime:v1:result:task:<taskId>:final'
```

Residue checks:

```bash
rg -n "taskWorkHash\\(|taskLeaseHash\\(|taskRecentFinalReceiptHash\\(|taskVisibleRow\\(|:work:\\{messageId\\}|:lease:\\{messageId\\}|:visible:\\{messageId\\}|claimReady\\(String taskId,\\s*List<WorkerClaimTarget>|FIELD_SHARD_KEY|shardKey" platform_infra/mass-runtime-redis platform_infra/mass-runtime-api xa-mass-engine xa-mass-server sdk transport xa-mass-worker-runtime doc roadmap
```

## Roadmap Completion Criteria

- Engine, server controllers/services, SDK public surfaces, transport
  dispatch/result code, and worker-runtime mainline do not reference Redis task
  runtime key/value layout. Explicit bootstrap assembly may instantiate Redis
  runtime implementations.
- `TaskWorkRuntime` has converged to at-least-once item delivery semantics:
  Redis reserves/delivers task items, Scheduling Plane selects workers, final
  commit is idempotent, and ack happens after final commit.
- `TaskResultRuntime` semantic contracts remain stable for engine-visible
  result behavior.
- Ready/delayed work payloads no longer create one Redis key per work item; one
  task-local Stream owns item delivery.
- Active lease payloads no longer create one Redis key per active lease item;
  Stream pending entries and bounded reservation metadata replace lease-key
  truth.
- Stable-final visible result rows no longer create one Redis key per result
  item.
- Stream growth control or terminal cleanup prevents a single task work Stream
  from growing without bound.
- Empty optional fields are not persisted in Redis stream entries or encoded
  values.
- `shardKey` is removed from Redis work values or has a current production
  caller and proof.
- Result apply, duplicate delivery/final idempotence, retry scheduling, Stream
  reclaim, result window ordering, result repair, and precise task discard
  proofs still pass.
- Public/server/SDK result behavior and current `eventCode` API behavior remain
  unchanged unless a separate approved follow-up roadmap changes them.
- `REDIS_RUNTIME_BASELINE.md` matches the implemented key/value shape.
- Residue scan is run before marking this roadmap complete or archiving.

## Open Decisions

- Whether RTR-3/RTR-5 should use a clean namespace bump, explicit namespace cleanup,
  or same-namespace replacement in local/dev runtime.
- Exact compact encoding format: compact JSON, delimiter encoding, or another
  package-private codec.
- Exact Stream consumer group name, consumer id source, idle reclaim threshold,
  and `XAUTOCLAIM` batch size.
- Whether enqueue idempotency uses a `messageId -> streamId` index, stream entry
  inspection, or a different bounded dedup structure.
- Whether delayed/retry visibility uses global delayed ZSET plus task-local
  delayed ZSET, delayed re-XADD, or another non-per-item-key strategy.
- Whether Stream growth control is `XTRIM`/`MAXLEN`, terminal task cleanup,
  explicit task-level max unfinalized item count, or a combination.
- Whether result row encoding should keep `seq` in the hash value or
  reconstruct it from the visible ZSET score.
- Whether result barrier keys should later be folded into the result row hash
  or a task-local barrier hash after the visible-row migration is stable.
- Whether `payloadJson` should be renamed to `inputJson` or
  `dispatchEnvelopeJson` so platform envelope metadata is not confused with
  user business payload.
- When to start the follow-up event identity convergence that removes
  `eventCode` from server API, engine, transport, and SDK surfaces.
- When to start the follow-up transparent result contract convergence for
  `TaskResultRuntimeRow` and public result DTOs.
- What terminal retention duration and cleanup trigger should own eventual
  task runtime/result key deletion.
- Whether recent-final receipts can be folded into `TaskResultRuntime`
  stable-final rows without losing runtime-first duplicate/late callback
  classification.
