# Task Runtime Score-Band Redis Keyspace Rewrite Roadmap

Status: proposed.

This roadmap owns the Redis runtime data-structure rewrite for
`xa-mass-task-runtime`. It exists because the current Redis implementation does
not implement the score-band task-runtime shape: it uses global task scanning
and convenience keys such as `tasks`, `dirty`, `ids`, per-task `eligibility`,
and ordered final-result keys instead of a task-lane score index.

This roadmap is intentionally ordered as:

1. freeze the Redis keyspace, Redis types, and logical value types;
2. discuss and lock the task-runtime state-machine mutations that use those
   keys;
3. refine public/internal task-runtime ports only after the keyspace and state
   machine are stable.

Do not start by reshaping API DTOs, view models, server routes, diagnostics, or
memory-runtime behavior. Those are follow-up consumers of the runtime mechanism,
not the mechanism itself.

## Owner Decision

The score-band Redis keyspace is task-runtime runtime truth. It is not engine
shell truth, server view truth, worker-runtime truth, transport truth, or trace
truth.

`xa-mass-task-runtime` owns the logical contract for accepted backlog, task
scheduling status visibility, active lease recovery, retry visibility, result
finality, and short-retained final rows. The Redis implementation owns only the
physical storage mapping and atomic mutations for that contract.

Task score is task-runtime status/gate truth:

- task status and runtime gate mutations control score membership and score
  value;
- append writes item backlog truth only;
- backlog length must not be mirrored through a second task registry;
- backlog, retry, active lease, and result keys each own their own local truth;
- no queue updates another queue's owner state merely to keep views synchronized.

Mechanical interfaces must stay inside their owner range. Do not add duplicate
checks, terminal checks, repair sweeps, retry promotion, cleanup, diagnostics, or
future consistency guarantees to a mechanical operation just because another
owner might be wrong later. Downstream paths trust the owner fact they consume;
if a terminal task is still score-visible, the bug belongs to the score/status
writer, not to dispatch claim.

## Current Implementation Observations

Current implementation: `platform_infra/mass-task-runtime-redis/.../RedisTaskRuntime.java`.

| Current key | Redis type | Current role | Target decision |
| --- | --- | --- | --- |
| `<tr>:tasks` | SET | Global task registry scanned by discovery | Remove. Discovery must use per-lane score. |
| `<tr>:dirty` | SET | Best-effort wakeup/dirty hint | Remove from v0 runtime truth. Event wakeup can be a later optimization. |
| `<tr>:task:{taskId}:ids` | SET | Append-time message id dedupe | Remove from v0. Append does not dedupe; duplicate message id is undefined input. |
| `<tr>:task:{taskId}:ready` | LIST | Raw ready frames | Replace with `<tr>:task:{taskId}:backlog`; avoid implying task status. |
| `<tr>:task:{taskId}:delayed` | ZSET | Delayed retry ids | Replace with explicit retry score + retry item pair. |
| `<tr>:task:{taskId}:active` | HASH | Active leased frames | Replace with `<tr>:task:{taskId}:rt`. |
| `<tr>:task:{taskId}:eligibility` | HASH | Per-task scheduling gate | Replace with task-local meta. |
| `<tr>:task:{taskId}:final:rows` | HASH | Final result rows | Replace with `<tr>:task:{taskId}:result`. |
| `<tr>:task:{taskId}:final:order` | ZSET | Ordered result window / cleanup scan | Remove from core runtime. Task-level result cleanup owns release. |
| `<tr>:task:{taskId}:final:seq` | STRING | Result sequence counter | Remove. Ordered final window is not a core runtime truth. |
| `<tr>:worker:{workerId}:active` | SET | Worker reverse index over active work | Remove from core task-runtime truth unless a later API gate proves it is required. |

These keys are not acceptable compatibility aliases after cutover. If a slice
needs temporary dual-write for migration proof, the slice must name the producer,
reader, shutdown point, and guard that removes it.

## Redis Namespace And Encoding

Use `<tr>` below for the configured task-runtime Redis namespace. Example:

```text
xa:mass:runtime:v1:task-runtime
```

Key segment values use `TaskRuntimeRedisKeyCodecV1`:

- normal generated ids such as UUID task ids are stored as readable key-safe
  segments;
- allowed key segment characters are `[A-Za-z0-9._-]`;
- values that do not match that set must be percent-encoded by the codec;
- Redis values always store the original logical ids, not only encoded key
  segments.

The codec is internal to the Redis adapter. Public task-runtime APIs must not
expose Redis key encoding.

## Target Redis Keyspace V0

The table below is the first gate. Adding a Redis key outside this table means
the keyspace gate is not complete.

### Lane Index Keys

| Key | Redis type | Member/field type | Score/value type | Owner and role |
| --- | --- | --- | --- | --- |
| `<tr>:lanes` | SET | `LaneKey` | none | Low-cardinality lane discovery for scheduler/repair loops. Not a task registry. |
| `<tr>:task:score:{laneKey}` | ZSET | `TaskIdKey` | `TaskScoreV1` score | The task-runtime status/gate scheduling index. Score is controlled by task status changes, not by backlog writes. |

`LaneKey` is a low-cardinality task-runtime bucket id. First implementation can
use `projectId`, or `default` when project partitioning is not ready. It is not
a worker id, worker group id, adapter id, message shard, random hash shard, or
transport route.

Rules:

- a task belongs to one lane for a given runtime epoch;
- `<tr>:task:score:{laneKey}` stores only task ids and task-level status scores;
- task metadata lives in the task-local meta HASH, and stores the current
  `laneBucketId`;
- if a task changes lane, the state-machine slice must define the atomic
  remove-from-old-lane, update task meta, and add-to-new-lane boundary before
  implementation;
- do not introduce hash shards until a measured single-lane score ZSET or
  pipelined task-local meta read cost proves `projectId` or `default` is
  insufficient.

`TaskScoreV1`:

```text
score >= TIME_SCORE_FLOOR: task should be evaluated at or after score millis
score < 0: parked state
missing member: no scheduler-visible task state in this lane

TIME_SCORE_FLOOR = 1_000_000_000_000
PARKED_PAUSED = -10
PARKED_BLOCKED = -20
TERMINAL or discarded = ZREM, not parked score
```

The score says "this task status is currently schedulable, parked, or scheduled
for re-evaluation". It does not say "this task has N ready items". A dispatch
evaluator may find an empty backlog and skip it. That is not a correctness bug.
Retry promotion, lease repair, idle close, periodic checks, and wakeup hints are
separate owner paths, not work for the dispatch evaluator.

### Task-Local Keys

| Key | Redis type | Member/field type | Value type | Owner and role |
| --- | --- | --- | --- | --- |
| `<tr>:task:{taskIdKey}:meta` | HASH | metadata field name | primitive or compact sub-frame | Task-local runtime status, lane, policy, and gate fields. Scheduler reads selected fields. |
| `<tr>:task:{taskIdKey}:backlog` | LIST | list element | `BacklogFrameV1` | Raw accepted item backlog. Append writes here. Claim consumes from here. |
| `<tr>:task:{taskIdKey}:retry:score` | ZSET | `MessageIdKey` | `nextSchedulableAtMillis` | Message-level delayed retry visibility for `DUE_TIME` retry mode only. |
| `<tr>:task:{taskIdKey}:retry:item` | HASH | `MessageIdKey` | `RetryFrameV1` | Payload and retry evidence for delayed retry items. |
| `<tr>:task:{taskIdKey}:rt` | HASH | `MessageIdKey` | `RuntimeItemStateV1` | Sparse active lease state. Bounded by active concurrency, not backlog size. |
| `<tr>:task:{taskIdKey}:result` | HASH | `MessageIdKey` | `FinalResultV1` | Short-retained final row lookup by known `taskId + messageId`. Not a durable ledger. |

`TaskRuntimeMetaV1` is stored as HASH fields, not as one large JSON payload.
This is intentional: task runtime metadata is wider than worker slot metadata,
and scheduler/result/repair often need only a subset of fields.

Recommended field groups:

```text
schemaVersion
taskId
laneBucketId
runtimeGate              OPEN | PAUSED | BLOCKED | TERMINAL
runtimeEpoch
fenceToken               nullable
dispatchIntent           workerGroupIds, targetWorkerId, routingCode, match rule evidence
retryPolicy              retryMode, maxRetryCount, retryDelayMillis, backoff mode, version
scorePolicy              positiveMatchDelay, emptyMatchDelay, contentionRecheckDelay, lease policy evidence
resultRetentionMillis
updatedAtMillis
```

Nested groups such as `dispatchIntent`, `retryPolicy`, and `scorePolicy` may be
stored as compact sub-frames, but hot fields such as `runtimeGate`,
`runtimeEpoch`, `laneBucketId`, and `updatedAtMillis` must remain directly
readable fields.

Dispatch evaluator flow should be:

```text
ZRANGEBYSCORE <tr>:task:score:{laneKey}
pipeline HMGET <tr>:task:{taskId}:meta selected-fields for candidate task ids
trust task score as the schedulable status source
read policy / epoch / dispatch intent needed for worker selection
if backlog is empty, skip
if backlog is non-empty, claim backlog into rt and dispatch
```

Do not put raw item counters, payload, final result projections, server view
fields, worker-runtime state, transport route fields, trace-only details, or
full task definitions into task meta.

`BacklogFrameV1` logical fields:

```text
schemaVersion
frameType                 RAW | FAST_RETRY
taskId
messageId
retryCount                0 for raw append
payloadJson               opaque
payloadRef                nullable opaque reference
createdAtMillis
enqueuedAtMillis
```

Append receives the already-admitted `messageId` before writing the frame. V0
append does not keep an `ids` set and does not validate duplicate `messageId`.

`RetryFrameV1` logical fields:

```text
schemaVersion
taskId
messageId
retryCount
payloadJson
payloadRef
nextSchedulableAtMillis
reason                    retryable failure | lease timeout
updatedAtMillis
```

`RuntimeItemStateV1` logical fields:

```text
schemaVersion
taskId
messageId
state                     LEASED
sourceFrame               BacklogFrameV1 or RetryFrameV1
attemptNo
retryCount
runtimeEpoch
fenceToken                nullable
leaseToken
workerReservationToken
workerId
workerGroupId
dispatchTargetRef         opaque, nullable
batchId                   nullable
scoreBandClaimScore       nullable evidence
leasedAtMillis
leaseExpireAtMillis
updatedAtMillis
```

`FinalResultV1` logical fields:

```text
schemaVersion
taskId
messageId
attemptNo
retryCount
workerId
workerGroupId
batchId                   nullable
leaseToken
status                    SUCCESS | FAILED
finalReason
resultPayloadJson         opaque
resultPayloadRef          nullable
errorCode                 nullable
errorMessage              nullable
completedAtMillis
```

Physical encoding is owned by the Redis adapter. The first implementation may
use compact JSON for debuggability, but the state-machine and API must depend on
these logical frame types, not on JSON field names.

## Keyspace Rules

- No per-ready-item Redis key. One million accepted items must be one LIST with
  one million frames, not one million HASH fields or Redis keys.
- No global task SET for discovery. Scheduler discovery reads lane score ZSETs.
- No `dirty` key in v0. A later wakeup channel can be added only as a
  best-effort latency optimization, not as scheduling truth.
- No append-time `ids` set in v0. Append does not dedupe `messageId`.
  Duplicate `messageId` within one task is undefined input in V0. If a later
  identity/idempotency guarantee is needed, add a named identity owner and
  bounded mechanism without changing append into a universal guard.
- No ordered final-result window in core runtime. Public result views must not
  force runtime to maintain a durable ordered projection.
- No per-message result expiry index in v0. Result release is task-level cleanup
  until a later owner decision proves a per-message retention index is worth the
  cost.
- No worker reverse index in core task-runtime truth unless a later API/state
  gate proves it protects a production invariant.
- Task score and backlog length are separate. Task status/gate mutations own
  score; append writes backlog.
- Dispatch evaluation does not own retry promotion, lease repair, or close.
  Retry and lease owners may use the score-visible task universe as their
  candidate source in V0, but their mutations stay separate. Do not add a
  separate active-task or precise per-lease timeout ZSET in v0.

## Slice Plan

### SBRK-0: Keyspace Freeze

Goal: finish the Redis key/type/value contract before touching state-machine or
API code.

Scope:

- review the table in this roadmap against
  `architecture/score-band-task-runtime-redis-shape.md`;
- decide the first-lane strategy: `projectId` or `default`;
- decide whether `backlog` replaces `ready` permanently;
- decide whether readable key-safe ids replace default base64 key segments;
- decide whether any worker reverse index is truly needed in core runtime.

Acceptance:

- every Redis key used by the target implementation appears in the Target Redis
  Keyspace V0 table;
- every current key in Current Implementation Observations has a remove,
  replace, or explicitly deferred decision;
- lane membership is defined as one lane per task per runtime epoch;
- metadata is defined as one task-local HASH per task, with directly readable
  fields for hot scheduler/runtime values;
- state-machine mutation rules remain draft only;
- API DTO/port changes remain draft only.

Verification:

- document review only; no code behavior changes.

### SBRK-1: Redis Keyspace Proof Harness

Goal: create adapter-local proof that the Redis implementation can write the
approved keyspace without serving production traffic.

Scope:

- add internal key codec and frame codec classes under the Redis implementation;
- add Redis-backed keyspace contract tests that assert `TYPE`, members/fields,
  and logical frame round trip for every approved target key;
- add negative tests proving append does not write `ids`, `dirty`, `tasks`, or
  per-item keys;
- do not use pure memory runtime as proof.

Acceptance:

- a Redis-backed test can append a batch and observe only the approved backlog
  key plus any pre-existing task-local meta and lane score keys created by
  scheduler enrollment;
- a Redis-backed test can seed lane score and task-local meta, then discover
  candidates through ZSET operations plus pipelined task-local meta field reads,
  not global task scanning or full payload parsing;
- forbidden current keys are absent after the proof scenario.

Verification candidates:

```text
mvn -pl platform_infra/mass-task-runtime-redis -Dtest=*Redis*Keyspace* test
```

The exact test class name must be set by the implementation slice.

### SBRK-2: State-Machine Mutation Design

Goal: lock how each owner mutates the approved keyspace without turning one
loop or interface into a defensive catch-all.

This slice is discussion/design before API refinement. It must answer at least:

- which TaskScoreOwner mutation creates, updates, parks, or removes score
  membership;
- where initial task-local metadata is created;
- how append stays a backlog-only mutation and does not enroll or update task
  score;
- how the dispatch evaluator stays thin: consume score candidate, skip empty
  backlog, claim backlog into `rt`, and dispatch;
- how RetryOwner promotes due `retry:score` / `retry:item` entries back into
  backlog, or when a later dedicated retry queue is required;
- which mutations need Lua atomicity;
- how ResultOwner updates `rt`, retry keys, final result keys, and requests
  score changes only through the score owner boundary;
- how LeaseOwner finds and mutates expired `rt` entries without making dispatch
  evaluation own repair;
- how CloseOwner proves backlog/retry/rt are empty before normal terminal close;
- how hard discard fencing prevents stale claim/result/repair after cleanup.

Expected atomicity direction:

- append batch can be single-command or pipeline plus bounded admission; Lua is
  only needed if the admitted batch and backlog limit must be one atomic unit;
- claim may need Lua to keep `LPOP backlog` and `HSET rt` atomic; it should not
  become a terminal/discard/retry/repair guard;
- result apply / lease repair likely need Lua because they validate active lease
  correlation and move one item to final, retry, or stale rejection;
- score/status updates need Lua only when they are the score owner's own
  mutation boundary, not because downstream interfaces are adding defense.

Acceptance:

- the mutation design references only approved keys;
- no mutation reintroduces global task scan, dirty set, id set, or result window
  projection;
- the design names TaskScoreOwner, AppendOwner, DispatchOwner, RetryOwner,
  LeaseOwner, ResultOwner, and CloseOwner responsibilities;
- dispatch evaluation does not perform terminal/discard checks, retry promotion,
  lease repair, result cleanup, or task close;
- append remains all accepted or rejected for v0;
- duplicate `messageId` is explicitly undefined input in V0, not an append
  responsibility.

### SBRK-3: Redis Runtime Rewrite Behind Existing Serving Path

Goal: replace the current Redis implementation with the approved score-band
keyspace and mutation semantics while keeping the serving path narrow.

Scope:

- replace discovery from `SMEMBERS <tr>:tasks` with bounded lane
  `ZRANGEBYSCORE`;
- replace per-task `eligibility` with task-local `meta`;
- replace `ready` with `backlog`;
- replace `active` with `rt`;
- replace `delayed` with retry score/item;
- replace `final:*` with `result`;
- delete `ids`, `dirty`, `tasks`, and `worker:*:active` usage unless SBRK-0
  explicitly approved a replacement.

Acceptance:

- append -> score scan -> thin dispatch evaluation -> backlog claim uses the new
  keyspace in Redis;
- claim creates recoverable `rt` state before dispatch handoff;
- result apply can converge success, retryable failure, final failure, duplicate,
  stale, and late result cases through the new keyspace;
- RetryOwner can promote due retry entries back to backlog without dispatch
  evaluation owning retry;
- LeaseOwner can converge expired `rt` entries without dispatch evaluation
  owning repair and without adding `task:active:{laneKey}` in V0;
- no production code writes the forbidden old keys.

Verification candidates:

```text
mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-redis test
mvn -pl xa-mass-server -Dtest=*Task*Runtime*,*Redis*Runtime* test
```

The final verification commands must be corrected after test inventory.

### SBRK-4: API And Port Refinement

Goal: refine ports after the Redis mechanism proves which inputs are actually
required.

Do not do this before SBRK-0 through SBRK-3 clarify the keyspace and mutation
rules.

Known API gaps to resolve:

- scheduler discovery likely needs lane scope or a lane iterator handle instead
  of only `limit + nowMillis`;
- claim likely needs lane bucket, expected score/epoch evidence, and selected
  worker reservation evidence;
- result apply may need lane resolution evidence, or `rt` must contain enough
  data for result apply to update lane score/active state safely;
- task runtime enrollment/update must be explicit enough to create lane meta and
  score without routing through engine fat policy objects;
- read/progress APIs must not force ordered final-result projections into Redis
  runtime truth.

Acceptance:

- public/internal ports carry only stable mechanism inputs;
- no API exposes Redis key names or adapter frame encoding;
- no API carries server view models, trace projections, or engine fat policy
  objects into task-runtime;
- old ports are closed by method, not left as parallel live paths.

### SBRK-5: Old Path Closure And Guards

Goal: remove the old Redis path and prevent recurrence.

Scope:

- delete old key builders and scripts for `tasks`, `dirty`, `ids`,
  `eligibility`, `ready`, `delayed`, `active`, `final:rows`, `final:order`,
  `final:seq`, and `worker:*:active`;
- update tests that asserted old key behavior to assert the new keyspace;
- add an architecture/keyspace guard that enumerates approved Redis key suffixes
  or key-builder methods;
- update `doc/PROOF_REGISTRY.md` only after a focused Redis-backed proof exists.

Acceptance:

- current implementation cannot compile if a forbidden key builder is re-added
  without updating the approved keyspace test;
- no serving path reads old keys as fallback;
- no migration dual-write remains active;
- TROM completion claims are downgraded or updated if they depended on the old
  Redis shape.

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

- the Redis keyspace in production code matches Target Redis Keyspace V0 or an
  explicitly reviewed successor table in this file;
- append -> score scan -> thin dispatch evaluation -> claim -> dispatch handoff
  -> result apply has one Redis-backed proof through the score-band keyspace;
- retry promotion has one Redis-backed proof through retry keys back to backlog,
  without dispatch evaluation owning retry;
- lease repair has one Redis-backed proof through LeaseOwner and task-local
  `rt`, without dispatch evaluation owning repair and without
  `task:active:{laneKey}` in V0;
- no production path writes or reads forbidden old keys;
- public/internal task-runtime ports have been refined only after the state
  machine proves the required inputs;
- no pure memory runtime proof is used as the main acceptance evidence;
- stale docs and roadmaps no longer describe the old Redis keyspace as current
  or acceptable.

## Do Not Start With

- Do not split or polish server view APIs first.
- Do not add memory-only runtime tests and call the Redis mechanism proven.
- Do not keep `tasks + dirty` as "temporary equivalent" discovery.
- Do not add `ids` or duplicate checks to append unless a separate
  identity/idempotency owner is explicitly designed.
- Do not maintain final-result ordered windows in runtime just to satisfy a
  view API.
- Do not let engine policy DTOs become Redis frame values.
- Do not use transport result concerns to redefine task-runtime finality.
- Do not turn dispatch evaluation into terminal/discard validation, retry
  promotion, lease repair, result cleanup, or task close.
