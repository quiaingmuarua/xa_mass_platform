# Redis Task Runtime Shape Convergence Inventory

Status: current code and live Redis inventory for
`REDIS_TASK_RUNTIME_SHAPE_CONVERGENCE_ROADMAP.md`.

This inventory classifies the current Redis physical shape behind
`TaskWorkRuntime` and `TaskResultRuntime`. The boundary target is not a new
engine API. The target is to keep engine callers on runtime semantics while
allowing `RedisTaskWorkRuntime` to replace per-message work/lease keys with
task-local Redis Stream delivery semantics, while `RedisTaskResultRuntime`
replaces per-message visible result row keys with a task-local result hash.

## Current Implementation Notes

- `TaskWorkRuntime` in `platform_infra/mass-runtime-api` is the shared
  semantic contract for ready work, delayed visibility, active lease truth,
  retry, result apply, expiry, stats, recent final receipts, and task discard.
- `TaskResultRuntime` in `platform_infra/mass-runtime-api` is the shared
  semantic contract for staged callback repair anchors, stable-final visible
  result rows, task-local result windows, result-side barriers, and task
  discard.
- Engine mainline calls runtime APIs through `TaskManager`,
  `TaskResultService`, repair pumps, and narrow engine ports. It should not
  depend on Redis keys, Redis field names, or Redis payload encoding.
- `RedisTaskWorkRuntime` currently implements the work contract with Lua
  scripts over per-task indexes plus per-message work, lease, and recent-final
  hashes.
- Current `TaskWorkRuntime#claimReady(...)` accepts worker claim targets and
  returns `ClaimedTaskWork` containing worker/batch facts. That is current
  code, but the Stream target must split item reservation from worker
  selection: Redis runtime reserves/delivers task items; Scheduling Plane
  selects workers.
- `RedisTaskResultRuntime` currently implements the result contract with Lua
  scripts and Redis reads over per-message staged drafts, per-message visible
  result row strings, task-local visible order ZSETs, pending barrier indexes,
  and barrier claim keys. The visible result row currently carries worker,
  attempt, event, and repair context; narrowing that public/runtime result
  shape is a later contract convergence, not a prerequisite for Redis key-shape
  migration.
- `eventCode` is currently public/server/API and engine/transport/SDK surface
  data. The desired long-term owner is worker handler dispatch identity, but
  this inventory treats current `eventCode` usage as historical surface residue
  that must be preserved until a later API and handler-identity roadmap.
- `RedisTaskWorkKeyspaceTest` currently asserts per-message work key names.
  Those assertions are implementation-shape tests and must move with the Redis
  physical shape.
- `RedisTaskResultRuntimeTest` and `RedisTaskResultRuntimeContractTest`
  currently prove Redis result behavior, but the result keyspace has no
  dedicated key-name test comparable to `RedisTaskWorkKeyspaceTest`.
- `REDIS_RUNTIME_BASELINE.md` documents the current keyspace and says current
  behavior must be verified through implementation and contract tests.
- Historical `TASK_RUNTIME_SCHEDULING_LOCK_REDUCTION_ROADMAP` already
  identified `task:{taskId}:work`, `task:{taskId}:leases`, and task-local final
  result hashes as future key-shape migration targets, but kept per-message
  keys for that old lock-reduction slice. The current target supersedes the
  work/lease hash direction with task-local Redis Stream delivery.

## Live Redis Evidence

Work runtime probe on 2026-06-18 against the user-provided key:

```text
key=xa:mass:runtime:v1:task:7daf35a6-c6e5-473d-906e-f10a681568ee:work:07bbc2be-e9b7-4547-bf67-7623606de1b3
type=hash
hlen=8
memory_usage=772 bytes
```

Fields:

```text
createdAtMillis=1781766682147
eventCode=probe.phone.metadata
shardKey=
maxRetryCount=1
payloadRef=
payloadJson=<inline JSON payload>
nextVisibleAtMillis=
retryCount=0
```

Same task snapshot:

```text
task-local key count=53
work key count=50
task ready list length=50
task members count=50
task delayed count=0
task stats: totalCount=50, readyCount=50
work hash total memory=38600 bytes
average work hash memory=772 bytes
```

Result runtime probe on 2026-06-18 against WSL Redis:

```text
sample=xa:mass:runtime:v1:result:task:9fc10adf-a2de-4a73-874a-9f56306940c2:visible:7d2c5a2c-7b99-4444-b31a-368071f6c010
type=string
memory_usage=1960 bytes
```

Same result namespace snapshot:

```text
task=51d7dfd2-c039-4157-a3f1-7a432c2c03b4 visible_zcard=50 visible_row_keys=50
task=9fc10adf-a2de-4a73-874a-9f56306940c2 visible_zcard=50 visible_row_keys=50
```

Interpretation:

- The work sample has one Redis key per ready work item plus task-level
  indexes (`ready`, `members`, `stats`).
- The result sample has one Redis key per stable-final visible result row plus
  a task-level `visible` ZSET and `seq` counter.
- Empty optional work values (`shardKey`, `payloadRef`,
  `nextVisibleAtMillis`) are persisted as hash fields.
- Result row values are JSON strings encoded with null serialization, so absent
  optional result facts still occupy value space.
- Result row values currently mix public final result data, repair barrier
  bits, and worker/attempt/context evidence. That is a target-direction smell,
  but Redis key-shape slices should first move the row payload into a
  task-local hash without changing the runtime/public result contract.

## Caller Inventory

| Caller | Current Dependency | Redis Shape Exposure | Target |
| --- | --- | --- | --- |
| `TaskManager#enqueueRuntimeWork(...)` | builds `TaskWorkEnvelope` and calls `TaskWorkRuntime.enqueue(...)` | none | Keep on semantic contract. |
| `TaskManager#getRuntimeDispatchableTasks(...)` | calls `TaskWorkRuntime.readyTaskIds(limit)` then loads task shell | none | Keep; no Redis key assumption. |
| `SimpleTaskDispatchBinder` | calls `TaskAssignmentRuntimePort.claimReady(...)` and consumes `ClaimedTaskWork` with selected worker facts | none | Migrate to reserve task item first, then let Scheduling Plane select worker. Runtime value must not expose Redis Stream internals. |
| `TaskResultService` | calls `TaskWorkRuntime.applyResultWithContext(...)`, `TaskWorkRuntime.getRecentFinalReceipt(...)`, and `TaskResultRuntime.commitVisibleFinal(...)` | none | Keep; no Redis key assumption. |
| `TaskResultRepairPump` and result repair paths | call `TaskResultRuntime.scanRepairCandidates(...)`, barrier claim, and barrier mark methods | none | Keep; repair state remains runtime contract behavior. |
| Result query/read paths | call `TaskResultRuntime.readWindow(...)`, `countVisibleResults(...)`, or `getVisibleByMessageId(...)` through runtime APIs | none | Keep; stable-final row shape remains runtime value. |
| `LeaseExpireWatchdog` | calls `TaskLeaseMaintenancePort.pollExpiredLeases(...)` | none | Keep; expiry result shape remains `ActiveLeaseRecord`. |
| Engine tests | mostly use memory runtime; some assert runtime contract values | no direct Redis key dependency in engine mainline | Add/extend guard to keep this true for task runtime Redis shape. |
| Server/bootstrap assembly | instantiates `RedisTaskWorkRuntime` / `RedisTaskResultRuntime` when Redis runtime mode is selected | implementation choice only, not key/value layout | Allow explicit assembly instantiation; do not allow controllers/services/DTOs to depend on task-runtime Redis keys or fields. |
| Transport Redis delivery implementation | may use shared Redis queue infrastructure such as `com.xa.mass.runtime.redis.queue.*` | transport-owned queue layout, not task-runtime work/result layout | Allow transport queue infra; guard only task-runtime keyspace/value leakage. |
| `RedisTaskWorkRuntimeTest` | asserts Redis physical keys and side effects | intentional Redis implementation shape | Retarget during Redis work shape slices. |
| `RedisTaskWorkKeyspaceTest` | asserts key family names | intentional Redis implementation shape | Retarget during Redis work shape slices. |
| `RedisTaskResultRuntimeTest` | asserts Redis result behavior and side effects | intentional Redis implementation behavior | Extend to prove result row hash shape when implemented. |
| `RedisTaskResultKeyspaceTest` | currently absent | result key names lack direct keyspace proof | Add before or during RTR-5 so retained result indexes and the new final hash key are explicitly protected. |
| `TaskWorkRuntimeContractTest` / `TaskResultRuntimeContractTest` | assert semantic behavior across implementations | no Redis key dependency | Must remain the primary proof. |

## Current TaskWorkRuntime Redis Key Inventory

| Key Family | Type | Current Writer | Current Reader | Hot Path? | Target |
| --- | --- | --- | --- | --- | --- |
| `...:ready:tasks` | ZSET | enqueue, claim, delayed promotion, retry | `readyTaskIds(limit)`, claim cleanup | yes | Keep only as eventual dispatch hint unless Stream contract proves strong ready truth is still needed. |
| `...:delayed:work` | ZSET | enqueue, retry, delayed promotion cleanup | delayed promotion, claim pre-promotion | yes | Keep or replace as delayed/retry visibility index; Redis Stream has no native delayed message. |
| `...:lease:expiry` | ZSET | claim, result apply, expiry polling | `pollExpiredLeases(...)` | yes | Retire or reduce after Stream pending entries and `XAUTOCLAIM` own reservation reclaim. |
| `...:stats` | HASH | all mutations | runtime diagnostics | support | Keep as derived/eventual diagnostics unless progress/terminal policy requires stronger proof. |
| `...:tasks` | SET | enqueue/discard | namespace cleanup/support | no hot path | Freeze as cleanup residue; remove only if cleanup no longer needs it. |
| `...:task:{taskId}:ready` | LIST | enqueue, delayed promotion, retry | claim, ready visibility check | yes | Retire or reduce when `XREADGROUP` owns item reservation. |
| `...:task:{taskId}:delayed` | ZSET | enqueue, retry, delayed promotion cleanup | task-local delayed promotion during claim | yes/support | Keep only if chosen delayed/retry strategy needs task-local lookup. |
| `...:task:{taskId}:stats` | HASH | all task work mutations | progress/terminal policy and diagnostics | yes | Keep as derived/eventual stats unless progress/terminal contract requires stronger semantics. |
| `...:task:{taskId}:members` | SET | enqueue, final/discard | discard exact per-message key cleanup | no hot path | Replace with Stream/message-index cleanup if exact per-message key cleanup is no longer needed. |
| `...:task:{taskId}:active` | SET | claim/result/expiry/discard | `activeLeases(taskId)`, discard | support/hot-adjacent | Retire or derive from Stream pending entries. |
| `...:worker:{workerId}:active` | SET | claim/result/expiry/discard | worker active checks and release paths | hot-adjacent | Remove from task work runtime unless separate worker occupancy proof keeps it as a worker-runtime view. |
| `...:task:{taskId}:work:{messageId}` | HASH | enqueue/retry | claim, apply result, getWork, delayed promotion, ready cleanup, discard | yes | Replace with `...:task:{taskId}:work` Redis Stream entry per logical message. |
| `...:task:{taskId}:work` | STREAM | target only | target delivery/reservation path | yes | Target task-local item delivery stream; no worker selection inside Redis. |
| `...:task:{taskId}:message-index` | HASH | target only | enqueue idempotency/support reads | support | Optional `messageId -> streamId` index if needed for dedup/support reads. |
| `...:task:{taskId}:lease:{messageId}` | HASH | claim | apply result, getActiveLease, expiry, activeLeases, discard | yes | Retire; Stream pending entries plus bounded reservation metadata replace lease-key truth. |
| `...:task:{taskId}:recent-final:{messageId}` | HASH | final result apply | duplicate/late callback classification, trim, discard | support/hot-adjacent | Defer; compact into one task-local receipt hash or replace with stable final row after duplicate/late proof. |
| `...:task:{taskId}:recent-final` | SET | final result apply | discard exact recent-final cleanup | support | Defer with recent-final shape. |
| `...:recent-final` | ZSET | final result apply | bounded trim | support | Keep or replace with TTL/trim proof later. |

## Current TaskResultRuntime Redis Key Inventory

| Key Family | Type | Current Writer | Current Reader | Hot Path? | Target |
| --- | --- | --- | --- | --- | --- |
| `...:result:stages` | ZSET | `stageCallback(...)`, discard | `scanRepairCandidates(...)`, cleanup | repair | Keep. |
| `...:result:stage:{stageId}` | STRING | `stageCallback(...)` | duplicate stage detection, repair scan, discard | repair | Keep first; not the visible-row cardinality target. |
| `...:result:task:{taskId}:stages` | SET | `stageCallback(...)`, discard | task discard | cleanup | Keep until stage cleanup is redesigned. |
| `...:result:task:{taskId}:message:{messageId}:stages` | SET | `stageCallback(...)`, discard | message stage discard | repair/cleanup | Keep until stage cleanup is redesigned. |
| `...:result:task:{taskId}:seq` | STRING | final commit | final commit, row sequence | yes | Keep. |
| `...:result:task:{taskId}:visible` | ZSET | final commit, discard | result window order, count | yes | Keep as task-local order index. |
| `...:result:task:{taskId}:visible:{messageId}` | STRING | final commit, barrier mark | duplicate commit, result read, repair, barrier claim/mark, discard | yes | Replace with one task-local result row hash, for example `...:result:task:{taskId}:final`; preserve current `TaskResultRuntime` semantics in the Redis shape slice. |
| `...:result:pending:attempt-closed` | ZSET | final commit, barrier mark | repair scan | repair | Keep. |
| `...:result:pending:logical-final` | ZSET | final commit, barrier mark | repair scan | repair | Keep. |
| `...:result:pending:progress` | ZSET | final commit, barrier mark | repair scan | repair | Keep. |
| `...:result:task:{taskId}:barrier:attempt-closed:{messageId}:{seq}` | STRING | barrier claim/mark | barrier claim/mark, cleanup | repair | Keep first; later compact only after result repair proof. |
| `...:result:task:{taskId}:barrier:logical-final:{messageId}:{seq}` | STRING | barrier claim/mark | barrier claim/mark, cleanup | repair | Keep first; later compact only after result repair proof. |
| `...:result:task:{taskId}:barrier:progress:{messageId}:{seq}` | STRING | barrier claim/mark | barrier claim/mark, cleanup | repair | Keep first; later compact only after result repair proof. |

## Current Work Value Field Disposition

| Field | Current Source | Current Readers | Classification | Target |
| --- | --- | --- | --- | --- |
| `eventCode` | `TaskWorkEnvelope.eventCode` | claim event-scope matching, dispatch payload | historical dispatch envelope metadata | Preserve current behavior as stream entry/handler identity residue, but remove Redis worker-selection use in the Stream contract. Later event identity convergence removes the surface. |
| `payloadJson` | serialized inline input payload | claim return, `getWork(...)` | dispatch payload | Keep as user input only, or rename to `inputJson`; do not hide platform metadata inside user payload. Stream entry encoding should keep it opaque to Redis. |
| `payloadRef` | `TaskWorkEnvelope.payloadRef` | claim return, lease snapshot, runtime view reconstruction | dispatch/recovery payload reference | Store only when nonblank. |
| `retryCount` | enqueue/retry mutation | claim return, apply retry budget, recent-final receipt | retry hot-read/write | Keep; omit zero only if codec can default safely. |
| `maxRetryCount` | enqueue from task policy/spec | apply retry budget, result context | apply hot-read | Keep; encode compactly. |
| `shardKey` | `TaskWorkEnvelope.shardKey` | only `loadWork(...)`; production engine currently passes `null` | unused/residue candidate | Remove from Redis value unless a current production caller is found. |
| `nextVisibleAtMillis` | enqueue/retry delayed visibility | delayed promotion and retry visibility | delayed hot-read/write | Store only when delayed; omit for ready work. |
| `createdAtMillis` | enqueue timestamp | ready task zset score repair and FIFO visibility | ready-order support | Keep in stream entry unless Stream id ordering is proven sufficient. |

## Current Lease Value Field Disposition

| Field | Current Source | Current Readers | Classification | Target |
| --- | --- | --- | --- | --- |
| `leaseToken` | claim | result apply validation, active lease reads | apply hot-read | Replace with Stream delivery id / reservation token where possible; do not keep a per-message lease key. |
| `workerId` | selected worker claim target | worker active cleanup, active lease reads, result context | apply/release hot-read | Move out of Redis item claim; worker selection is Scheduling Plane/dispatch binding truth. Keep only as bounded reservation/result context if required. |
| `workerGroupId` | selected worker claim target | result context, trace/result row | result context | Move out of Redis item claim; preserve result context through dispatch/result convergence if still required by public rows. |
| `batchId` | dispatch slot | result context and trace | result context | Move out of Redis item claim; preserve through dispatch/result convergence if needed. |
| `payloadRef` | copied from work value | result context reconstruction | recovery/read-model | Prefer reading from stream entry or final result context; store only when nonblank if temporary reservation metadata is needed. |
| `retryCount` | copied from work value at claim | result context and recent-final receipt | apply hot-read | Keep in stream entry/retry metadata. |
| `leaseExpireAtMillis` | claim | `pollExpiredLeases`, active lease reads | expiry hot-read | Replace with Stream pending idle/reclaim settings. |
| `leasedAtMillis` | claim | result context and active lease reads | result context | Replace with reservation timestamp if still needed for diagnostics/result context. |

## Current Recent-Final Value Field Disposition

| Field | Current Source | Current Readers | Classification | Target |
| --- | --- | --- | --- | --- |
| `status` | final apply | duplicate/late callback classification | bounded runtime receipt | Keep until stable-final replacement proof exists. |
| `errorCode` | final apply | duplicate/late callback classification | bounded runtime receipt | Keep only when nonblank. |
| `retryCount` | final apply | duplicate/late callback classification | bounded runtime receipt | Keep. |
| `completedAtMillis` | final apply | duplicate/late callback classification and trim evidence | bounded runtime receipt | Keep. |

## Current Result Row Field Disposition

| Field | Current Source | Current Readers | Classification | Target |
| --- | --- | --- | --- | --- |
| `taskId` | final draft | API row, repair paths | row identity | May be omitted from Redis encoded value if codec reconstructs it from task-local key without changing runtime row semantics. |
| `messageId` | final draft | API row, duplicate commit, repair paths | row identity | May be omitted from Redis encoded value if codec reconstructs it from hash field without changing runtime row semantics. |
| `seq` | final commit counter | result window, barriers, repair paths | order/idempotency | Keep available to current `TaskResultRuntimeRow`; exact Redis storage can be value field or ZSET score if semantics stay stable. |
| `eventCode` | final draft | API row, trace/support | historical public/result residue | Preserve current runtime/public behavior in Redis shape slices; remove only in later event identity/result contract convergence. |
| `status` | final draft | API row, terminal convergence | result hot-read | Keep. |
| `finalReason` | final draft | API row, terminal convergence | current public/runtime result field | Preserve in Redis shape slice; later transparent-result convergence may move it. |
| `retryCount` / `maxRetryCount` | final draft | API row, retry evidence | current public/runtime result field | Preserve in Redis shape slice; later transparent-result convergence may remove it from public final rows. |
| `workerId` / `workerGroupId` / `batchId` / `attemptId` | final draft | API row, trace/support | current public/runtime result field | Preserve in Redis shape slice; later transparent-result convergence may move it to trace/review/debug evidence. |
| `payloadRef` | final draft | API row/support | current public/runtime result field | Preserve in Redis shape slice unless only null omission is proven behavior-preserving. |
| `createTime` / `assignedTime` / `startTime` | final draft | API row, repair timestamps | current public/runtime result field | Preserve in Redis shape slice; later transparent-result convergence may move lifecycle timing to trace. |
| `completeTime` / `updateTime` | final draft | API row, repair timestamps | current public/runtime result field | Preserve current behavior in Redis shape slice. |
| `errorCode` / `errorMessage` | final draft | API row, duplicate/error evidence | current public/runtime result field | Preserve current behavior; omit nulls only when contract tests prove no semantic change. |
| `output` | final draft | public result read | opaque result payload | Preserve current behavior; output ref/large-output policy is a later result payload decision. |
| `attemptClosedPublished` / `logicalFinalPublished` / `progressApplied` | final commit and barrier marks | repair scan, barrier idempotency | current repair barrier state | Preserve current behavior in Redis shape slice; moving to a separate repair hash is a later result contract/repair-state convergence. |

## Target Physical Shape Candidates

Preferred staged work-runtime direction:

```text
...:task:{taskId}:work           STREAM entry per logical message
...:task:{taskId}:message-index  HASH messageId -> streamId, optional
...:task:{taskId}:recent-final   HASH messageId -> encoded receipt value
```

Preferred staged result-runtime direction:

```text
...:result:task:{taskId}:visible  ZSET messageId -> seq
...:result:task:{taskId}:final    HASH messageId -> encoded stable-final result value
...:result:task:{taskId}:seq      STRING task-local final sequence counter
```

Rationale:

- avoids one Redis key per work item or stable-final result row
- lets `messageId` remain the lookup field
- allows task discard to delete task-local Stream/index/hash keys instead of
  scanning many per-message keys
- keeps global ready/delayed/result-pending indexes as discovery indexes rather
  than payload owners
- uses Redis Stream consumer groups for at-least-once item reservation and
  reclaim instead of per-message lease keys
- matches task runtime lifecycle: task work is normally consumed and result
  rows are task-local read truth for the lifetime of the task, unlike
  long-lived worker slot/bucket indexes
- relies on Stream growth control, terminal cleanup, or explicit max
  unfinalized item limits instead of worker-style bucket fan-out for work growth

The exact Stream growth control is an implementation decision. It must name the
owner and proof for `XTRIM`/`MAXLEN`, terminal cleanup, max unfinalized item
limits, or their combination before the Stream migration starts. Result rows do
not need a separate length cap in this roadmap because they are retained only
for the task runtime lifetime; a later terminal cleanup slice should remove
task runtime/result keys after a bounded terminal retention window.

## Decisions

- Keep `TaskWorkRuntime` and `TaskResultRuntime` as the cross-module contracts;
  do not make engine call Redis-specific work, lease, receipt, visible-row,
  stage, or barrier shapes.
- Treat `RedisTaskWorkRuntime`, `RedisTaskWorkKeyspace`,
  `RedisTaskResultRuntime`, and `RedisTaskResultKeyspace` as the owners of the
  physical Redis layout, Stream mechanics, and encoding.
- Do not add an engine wrapper that only forwards to runtime APIs; the current
  runtime API is already the owner boundary.
- Replace per-message work/lease Redis keys from inside `mass-runtime-redis`
  with task-local Redis Stream delivery. This does change `TaskWorkRuntime`
  delivery semantics from strong worker-target claim to at-least-once item
  reservation, so contract tests must be updated before Redis implementation
  rewiring.
- Keep upper-module guards layout-specific, not implementation-choice-specific:
  server/bootstrap assembly may instantiate Redis runtime implementations, but
  engine, controllers/services, SDK public surfaces, transport dispatch/result
  code, and worker-runtime hot paths must not consume task-runtime Redis
  keyspace classes, key strings, field constants, Stream groups, stream ids, or
  codecs.
- Remove Redis-side worker selection from task item claim/reservation.
  Scheduling Plane selects workers after item reservation; Redis Stream owns
  item delivery/reclaim only.
- Final result commit must happen before Stream ack. Duplicate delivery after a
  failed ack is expected and must be absorbed by idempotent final result commit.
- Remove or omit empty optional work fields as part of stream entry encoding,
  starting with `shardKey`, empty `payloadRef`, and empty
  `nextVisibleAtMillis`.
- Move stable-final result payloads into a task-local hash while preserving
  current `TaskResultRuntime` semantics. Avoid repeating `taskId` and
  `messageId` in every Redis value if the codec can reconstruct them from
  key/field identity without changing runtime/public behavior.
- Record, but do not execute here, the future transparent-result direction:
  public final rows should eventually avoid `eventCode`, worker id, worker
  group id, batch id, attempt id, retry counts, input payload ref, lifecycle
  timing context, and repair barrier flags.
- Prefer one task-local Stream over worker-style bucket fan-out. Task work is
  short-lived; Stream growth should be bounded by terminal cleanup, trimming, or
  an explicit max unfinalized item policy.
- Treat Stream consumer-group naming, idle reclaim, delayed/retry visibility,
  enqueue idempotency, and growth control as blocking RTR-2 decisions, not
  implementation details discovered during RTR-3.
- Keep result stage and barrier repair keys out of the first visible-row hash
  migration unless repair tests prove that compaction is independent.
- Add explicit result keyspace proof when the final result hash is introduced;
  `RedisTaskResultRuntimeTest` behavior checks alone should not be the only
  protection for result key names.
- Preserve current server/API/engine/transport/SDK `eventCode` behavior until
  a later event identity convergence roadmap removes the historical surface.
- Do not preserve old and new Redis physical layouts as two live runtime paths
  unless an explicit external compatibility requirement is added. Current
  pre-release runtime state may use clean namespace recreation or a namespace
  bump.
