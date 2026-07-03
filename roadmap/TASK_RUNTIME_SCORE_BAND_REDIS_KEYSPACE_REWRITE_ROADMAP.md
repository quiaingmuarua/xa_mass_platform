# Task Runtime Score-Band Redis Keyspace Rewrite Roadmap

Status: completed for the score-band Redis runtime mainline. SBRK-0D
pre-mechanism old-surface cleanup has fresh compile/guard proof: old
command-bucket DTO source files are removed from core main, old test callers
have moved to the grouped runtime surface, and old DTO names now remain only in
guard strings. SBRK-1 Redis mutation gaps are closed: claim, retry promotion,
result apply, close, and discard use Redis-owner-local Lua boundaries; claim
also fences `ScoreCandidate` lane/epoch/fence/observed-score before moving
`backlog -> rt`.
SBRK-4/SBRK-5 cutover is closed for the core serving path: `RedisTaskRuntime`
serves through the score-band grouped implementation, `TaskRuntimePortSet`
exposes only the four core runtime surfaces, and ordered result windows are
separated into the non-core `TaskRuntimeResultWindowReadModel`. SBRK-6 guard,
proof-registry, server trace proof, and residue scans have passed; later
server/SDK read-window deletion is outside this roadmap because it no longer
defines core Redis runtime truth.

This roadmap owns the Redis runtime data-structure rewrite for
`xa-mass-task-runtime`. It exists because the old Redis implementation did not
implement the score-band task-runtime shape: it used global task
scanning and convenience keys such as `tasks`, `dirty`, `ids`, per-task
`eligibility`, and ordered final-result keys instead of a task-lane score index.

This roadmap is intentionally ordered as pre-convergence, mechanism proof, then
cutover:

1. freeze the Redis keyspace, Redis types, and logical value types;
2. inventory the current old runtime API/caller surface and old-key pressure;
3. pre-converge that exposed surface without changing
   serving runtime truth and without introducing new score-band serving truth;
4. reconcile any already-written score-band code as frozen residue, proof-only
   code, or an explicitly reviewed early cutover candidate;
5. close or explicitly park remaining old-surface/test/read-model residue that
   would confuse the new mechanism proof;
6. lock the new state-machine owner transitions and atomicity rules;
7. only after that, prove the new Redis keyspace and mutations behind the
   narrowed API;
8. cut the serving path over only after the new mechanism has a closed proof;
9. close and clean the old path after serving cutover.

Do not start by reshaping API DTOs, view models, server routes, diagnostics, or
memory-runtime behavior. Those are follow-up consumers of the runtime mechanism,
not the mechanism itself. "Clean old mechanism first" in this roadmap means
pre-converging exposed ports, caller assumptions, and proof seams so old-key
Redis behavior is hidden behind the target runtime API. It does not mean closing
the production old path before the new score-band path is proven. First narrow
the API so the old key is implementation residue, close the old public pressure
points that can be closed without changing runtime truth, then replace the
mechanism, then delete the residue.

The mixed worktree that triggered SBRK-0C is treated as an exceptional
reconciliation state, not as the preferred execution model. If implementation
has already moved a Redis wrapper toward score-band behavior, the next work is
to stop expanding that mechanism, restore a compiling narrowed surface,
backfill the closure matrix and owner-state rules, and only then decide whether
the existing score-band code is quarantined proof or an SBRK-4 serving cutover
candidate. Until that decision and proof exist, no current score-band class,
test, key codec, or grouped-port delegation is completion evidence.

## Owner Decision

The score-band Redis keyspace is task-runtime runtime truth. It is not engine
shell truth, server view truth, worker-runtime truth, transport truth, or trace
truth.

`xa-mass-task-runtime` owns the logical contract for accepted backlog, task
scheduling status visibility, active lease recovery, retry visibility, result
finality, and short-retained final rows. The Redis implementation owns only the
physical storage mapping and atomic mutations for that contract.

Task score is task-runtime lifecycle scheduling-status truth:

- task-runtime lifecycle commands, maintenance owners, result/finality owners,
  and close/discard owners control score membership and score value;
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

## Pre-Convergence Rule

The first implementation work is old-mechanism pre-convergence, not new Redis
truth. It may change Java interfaces, starter wiring, engine call sites, tests,
and guards when those changes shrink the old runtime API surface. It may not:

- change which Redis keys are serving production truth;
- introduce score-band keys as serving truth;
- dual-write old and new Redis truth for the same work item;
- delete old Redis keys that are still required by the current serving path;
- widen DTOs or add bridge objects only to hide unclear ownership.

This cleanup is not "close the old path first". It is an exposure reduction
slice: remove old runtime facts from caller-visible APIs, collapse unnecessary
ports, and make old-key assumptions implementation-local. The current serving
mechanism may stay old during this slice. The slice fails if it changes Redis
truth, routes new score-band serving traffic, or uses compatibility bridges to
leave both owner stories public.

The expected output is a narrower path where old Redis keys are implementation
residue behind `TaskRuntimeWorkPort`, `TaskRuntimeScorePort`,
`TaskRuntimeConvergencePort`, and `TaskRuntimeReadPort`. Once that is true, the
new score-band implementation can be built and proven without inheriting
`tasks`, `dirty`, `ids`, `ready`, ordered result windows, or worker reverse
indexes as API commitments.

Pre-convergence is allowed to be invasive when it removes old exposure from the
current call graph. It is not a low-risk compatibility bridge. It should make it
harder for engine, starter, or SDK code to depend on old runtime facts, while
leaving the current Redis serving truth intact until the new score-band path is
proven.

Use the terms precisely:

- pre-convergence means caller/interface cleanup while old Redis remains the
  serving mechanism;
- proof means Redis-backed non-serving evidence that the new score-band
  mechanism works;
- cutover means serving traffic uses the score-band mechanism as the only owner
  for newly accepted runtime work;
- closure means old serving writers/readers, old key builders, and old
  compatibility DTOs are deleted or guarded after cutover.

Pre-convergence is complete only when a reviewer can answer these questions
from code and guards:

- which old port methods still exist only because the old implementation needs
  them internally;
- which callers already use the narrowed runtime surface;
- which old-key assumptions have no caller-visible contract left;
- which remaining old-key readers/writers are purely serving implementation
  residue waiting for SBRK-4/SBRK-5;
- which new score-band classes, if any, are non-serving proof code and cannot be
  confused with production truth.

## Pre-Mechanism Cleanup Rule

SBRK-0D is the final cleanup gate before Redis mechanism implementation. It is
not a second attempt to delete the old serving path. It is allowed to touch
ports, call sites, guards, tests, and inventory rows only when that makes the
new score-band mechanism easier to prove and later cut over.

Allowed SBRK-0D cleanup:

- remove old command-bucket DTOs and same-signature methods from production
  caller surfaces when they already have target grouped-port replacements;
- move old methods that are still needed by memory/test fixtures into
  test-local or implementation-local helpers instead of public task-runtime
  ports;
- keep non-core read-window residue only behind a clearly non-core surface that
  cannot force `final:order` / `final:seq` back into Redis runtime truth;
- strengthen guards so engine/starter/SDK callers cannot regain old dirty,
  global-scan, worker-active, ordered-result, or command-bucket dependencies;
- record any residue that cannot be removed before SBRK-1 as an explicit
  SBRK-4/SBRK-5 blocker or non-core deferred decision.

Not allowed in SBRK-0D:

- add or expand score-band Redis keys, Lua scripts, frame codecs, or serving
  delegation;
- change which Redis keys are production-serving truth;
- delete old Redis keys required by the current serving implementation;
- clean module-internal field names, server views, diagnostics, or memory-only
  behavior unless that cleanup directly removes old runtime API exposure;
- use bridges, aliases, or compatibility wrappers to keep two public owner
  stories alive.

SBRK-1 cannot start as implementation work until SBRK-0D says either
"removed", "test-local only", "implementation-local only", "temporary non-core
read-model", or "explicit SBRK-4/SBRK-5 blocker" for every remaining old
surface in the inventory. It also cannot start while the current SBRK-0D
worktree is non-compiling or while guard proof for the narrowed runtime surface
is pending.

## Current Execution Cursor

Current slice: **complete**. SBRK-0D old-surface cleanup is closed for core
command-bucket DTOs and focused guard proof. SBRK-1 mutation boundaries are
implemented for the Redis score-band path through owner-local Lua scripts.
SBRK-2/SBRK-3 proof covers approved keyspace writes, independent score
candidate discovery plus backlog claim, and result/retry/lease/close/discard
behavior. SBRK-4/SBRK-5 cutover proof
exists because `RedisTaskRuntime` delegates its grouped serving ports to
`RedisScoreBandTaskRuntime`, Redis tests prove no old runtime keys are created,
and ordered final-window reads are no longer part of `TaskRuntimePortSet`.
SBRK-6 is closed by focused guard/proof-registry proof, server E2E/trace proof,
and residue scans over production source.

The SBRK-1 matrix is closure evidence for Redis mutation atomicity, not a
blocker to mechanism implementation.

Execution lock for the current dirty worktree:

- do not reintroduce old task-runtime command buckets, dirty/global-scan
  discovery, worker reverse active reads, ordered result Redis keys, or old
  Redis key writers;
- do not add a bridge/fallback that lets the same accepted Redis work item be
  owned by both old and score-band keyspaces;
- treat any remaining ordered result-window vocabulary as non-core read-model
  surface, not score-band runtime truth.

Current acceptable SBRK-0B movements:

- move engine/starter/SDK callers off old task-runtime command buckets and old
  result/read/repair vocabulary;
- keep old core/memory/test residue inventoried instead of hiding it behind a
  compatibility adapter;
- add guards that prevent old surfaces from re-entering engine/starter/SDK or
  Redis serving assembly;
- update roadmap/inventory status only when a concrete caller surface is closed.

SBRK-0D closure evidence:

- old command-bucket DTO source files are deleted from
  `xa-mass-task-runtime/src/main`;
- `TaskRuntimeArchitectureGuardTest` guards old port and old command-bucket DTO
  source files remain deleted;
- `TaskRuntimeEngineCutoverPreparationTest`, `TaskRuntimeRecoveryPortTest`, and
  `TaskResultConcurrencyConvergenceTest` no longer compile against old
  task-runtime command DTOs;
- SBRK-0D verification passed:
  `mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine,xa-mass-engine-starter -am -DskipTests test-compile`;
  `mvn -pl xa-mass-task-runtime '-Dtest=TaskRuntimeArchitectureGuardTest,TaskRuntimeContractShapeTest' test`;
  `mvn -pl platform_infra/mass-task-runtime-redis -Dtest=RedisTaskRuntimeArchitectureGuardTest test`;
  `mvn -pl sdk/xa-mass-task-runtime-starter-sdk '-Dtest=TaskRuntimeStarterArchitectureGuardTest' test`;
  `mvn -pl xa-mass-engine '-Dtest=TaskRuntimeServingLaneOldPathClosureGuardTest,TaskRuntimeEngineCutoverPreparationTest,TaskRuntimeRecoveryPortTest,TaskResultConcurrencyConvergenceTest' test`;
  `mvn -pl platform_infra/mass-task-runtime-memory -am test`.

SBRK-1/SBRK-3/SBRK-4 closure evidence:

- `RedisScoreBandTaskRuntimeScripts` owns atomic claim, retry promotion, result
  apply, close, discard-runtime, and discard-work mutations;
- `RedisTaskRuntime` exposes only grouped task-runtime ports and delegates them
  to the score-band implementation;
- Redis proof tests cover approved keyspace, append/score separation, claim,
  result success, retry promotion, lease repair, stale/duplicate result,
  close-with-retry-item residue, discard runtime, discard work, owner reconnect,
  and network partition;
- focused verification passed:
  `mvn -pl platform_infra/mass-task-runtime-redis test`;
  `mvn -pl platform_infra/mass-task-runtime-memory -am test`;
  `mvn -pl sdk/xa-mass-task-runtime-starter-sdk '-Dtest=TaskRuntimeStarterArchitectureGuardTest' test`;
  `mvn -pl xa-mass-engine '-Dtest=TaskRuntimeServingLaneOldPathClosureGuardTest,TaskRuntimeEngineCutoverPreparationTest,TaskRuntimeRecoveryPortTest,TaskResultConcurrencyConvergenceTest' test`.

Current stop triggers:

- a grouped port would both accept new serving work and fall back to old keys
  for append, score discovery, claim, result, retry, lease repair, or close;
- an old surface cannot be narrowed without first deciding SBRK-1 state-machine
  ownership;
- implementation pressure requires a bridge/facade whose only role is to hide
  unclear ownership.

## Mixed Worktree Reconciliation Rule

This roadmap intentionally separates old-mechanism exposure cleanup from the new
Redis mechanism. A current worktree may violate that ideal order by already
containing some of the target classes or by routing grouped ports to a
score-band implementation. That does not make the ideal order obsolete; it
creates a reconciliation gate.

Allowed reconciliation outcomes:

- **Quarantine**: the default. Keep `TaskRuntimeWorkPort`, `TaskRuntimeScorePort`,
  `TaskRuntimeConvergencePort`, and `TaskRuntimeReadPort` as the narrowed caller
  surface, but ensure the serving Redis implementation behind them still uses
  the old keyspace until SBRK-3 proof is complete. Any score-band classes remain
  package-local proof code and are not referenced by starter/engine serving
  assembly.
- **Advance**: if grouped ports already route to score-band Redis code, then the
  work is no longer SBRK-0B. It must be frozen as an early cutover candidate
  until SBRK-0A/SBRK-0B and SBRK-1 are backfilled. After that, it must satisfy
  SBRK-3 and SBRK-4 criteria before more serving behavior is added: append,
  score discovery, claim, result apply, retry promotion, lease repair, close,
  and discard must all use the new keyspace, with old serving fallbacks disabled
  for newly accepted work.

Advance is allowed only when quarantine would create more churn than completing
the proof/cutover and the implementation review can satisfy SBRK-3 and SBRK-4
without leaving partial active-lease ownership. It is not the normal path.

Not accepted as reconciliation:

- grouped port interfaces exist, therefore score-band is done;
- key codec or frame codec tests pass, therefore serving cutover is done;
- append and claim use `backlog` / `rt`, but result/retry/lease repair still use
  old `active`, `delayed`, `final:*`, or `worker:*:active`;
- engine/starter no longer import old ports, but `RedisTaskRuntime` still mixes
  old and new serving owners for the same newly accepted item;
- a test calls old `ResultApplyCommand`, ordered result windows, or worker-active
  reads while claiming to prove the target Redis mechanism.

## Old Keyspace To Retire

These are the old Redis runtime keys and pressures that this roadmap retires.
The dirty worktree may already have removed some of them from specific classes;
the current-code status is tracked in
`roadmap/TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_INVENTORY.md`. This
table remains the closure target: these keys must not re-enter as production
runtime truth after cutover.

| Old key | Redis type | Old role | Target decision |
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
| `<tr>:task:score:{laneKey}` | ZSET | `TaskIdKey` | `TaskScoreV1` score | The task-runtime lifecycle scheduling-status index. Score is controlled by score-owner lifecycle/maintenance/result changes, not by backlog writes. |

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
score >= TIME_SCORE_FLOOR: schedulable-time task, evaluated at or after score millis
0 < score < TIME_SCORE_FLOOR: positive non-schedulable enum, not dispatch-visible
score < 0: terminal/discarded/canceled/closed state, not transitionable and not dispatch-visible
missing member: no scheduler-visible task state in this lane

TIME_SCORE_FLOOR = 1_000_000_000_000
NON_SCHED_PENDING_APPROVAL = 100
NON_SCHED_BLOCKED = 200
NON_SCHED_REJECTED = 250
NON_SCHED_ACTIVE_MAINTENANCE = 300
TERMINAL_CLOSED = -100
TERMINAL_DISCARDED = -200
```

The score says "this task is on the schedulable-time axis, in a
non-schedulable positive enum, or terminal". It does not say "this task has N
ready items". A dispatch evaluator may find an empty backlog and skip it. That
is not a correctness bug. Retry promotion, lease repair, idle close, periodic
checks, and wakeup hints are separate owner paths, not work for the dispatch
evaluator.

V0 score visibility invariant:

- tasks are created as a positive non-schedulable enum by default;
- only TaskScoreOwner may ZADD, update, park, or ZREM a task in
  `<tr>:task:score:{laneKey}`;
- schedulable tasks use a timestamp score; `score <= now` is due for dispatch,
  and `score > now` is scheduled future work;
- pause is encoded by moving the timestamp forward using the runtime default
  pause window, for example `now + defaultPauseMillis`; it is not a negative
  parked state and does not accept caller-provided pause time;
- indefinite manual hold is encoded as a positive non-schedulable enum, not as
  a future timestamp that silently auto-resumes;
- non-schedulable non-terminal tasks use positive enum scores, such as pending
  approval, blocked/rejected, or active-maintenance-only;
- TERMINAL/DISCARDED/CANCELED/CLOSED tasks use negative terminal scores and do
  not transition back to non-terminal states;
- retention cleanup may eventually remove a terminal score member, but
  lifecycle convergence first records the negative terminal score;
- if task-local `retry` or `rt` still contains owner work, TaskScoreOwner must
  keep the task score-visible in some non-terminal lane state until the owning
  repair/result/close path clears it.

Dispatch reads only the due schedulable timestamp band:
`ZRANGEBYSCORE task:score:{laneKey} TIME_SCORE_FLOOR now`. RetryOwner,
LeaseOwner, and CloseOwner may scan positive enum and future timestamp ranges
that their owner policy names. They own their own cadence, limits, and
mutations. If that becomes too expensive, the successor design must add a named
retry/lease candidate key to the Target Redis Keyspace table before
implementation; do not smuggle back `task:active:{laneKey}` as an unreviewed
helper.

### Task-Local Keys

| Key | Redis type | Member/field type | Value type | Owner and role |
| --- | --- | --- | --- | --- |
| `<tr>:task:{taskIdKey}:meta` | HASH | metadata field name | primitive or compact sub-frame | Task-local lane, epoch, fence, score reason, policy, and dispatch-intent fields. Scheduler reads selected fields. |
| `<tr>:task:{taskIdKey}:backlog` | LIST | list element | encoded backlog frame JSON | Raw accepted item backlog. Append writes here. Claim consumes from here. |
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
runtimeEpoch
fenceToken               nullable
scoreState               SCHEDULABLE_TIME | NON_SCHED_ENUM | TERMINAL
scoreReason              PENDING_APPROVAL | MANUAL_HOLD | REVIEW_REJECTED | ACTIVE_MAINTENANCE | TERMINAL_CLOSED | ...
dispatchIntent           workerGroupIds, targetWorkerId, routingCode, match rule evidence
retryPolicy              retryMode, maxRetryCount, retryDelayMillis, backoff mode, version
scorePolicy              positiveMatchDelay, emptyMatchDelay, contentionRecheckDelay, lease policy evidence
resultRetentionMillis
updatedAtMillis
```

Nested groups such as `dispatchIntent`, `retryPolicy`, and `scorePolicy` may be
stored as compact sub-frames, but hot fields such as `runtimeEpoch`,
`laneBucketId`, `scoreState`, `scoreReason`, and `updatedAtMillis` must remain
directly readable fields. `RuntimeGate` is old vocabulary; do not make it the
target lifecycle truth or a required claim/discovery field.

Dispatch evaluator flow should be:

```text
ZRANGEBYSCORE <tr>:task:score:{laneKey} TIME_SCORE_FLOOR now
pipeline HMGET <tr>:task:{taskId}:meta selected-fields for candidate task ids
trust task score as the schedulable status source
read policy / epoch / dispatch intent needed for worker selection
if backlog is empty, skip
if backlog is non-empty, call atomic claimBacklog(candidate, reservations, ...)
```

`claimBacklog` is the atomic fence between score discovery and active lease
creation. It must re-read the task-local meta and lane score in the same
`LPOP backlog -> HSET rt` boundary, reject stale candidates when lane,
`runtimeEpoch`, `fenceToken`, or observed score no longer matches, and reject
candidates whose observed score is outside the dispatch-visible timestamp band.
It must not require `RuntimeGate.OPEN`. This is not a second terminal/discard
policy check; it is the consistency fence for the score fact that dispatch
already consumed.

Do not put raw item counters, payload, final result projections, server view
fields, worker-runtime state, transport route fields, trace-only details, or
full task definitions into task meta.

Backlog frame JSON logical fields:

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
sourceFrame               append item frame or retry frame
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
- Task score and backlog length are separate. Score-owner lifecycle,
  maintenance, result, and close/discard mutations own score; append writes
  backlog.
- Dispatch evaluation does not own retry promotion, lease repair, or close.
  Retry and lease owners use the score-visible task universe in V0, but their
  mutations stay separate. Do not add a separate active-task or precise
  per-lease timeout ZSET in v0.

## Superseded Blueprint Points

`architecture/score-band-task-runtime-redis-shape.md` remains useful for the
score-band direction, but this roadmap supersedes the following points for the
task-runtime Redis keyspace rewrite.

| Blueprint point | V0 decision in this roadmap |
| --- | --- |
| Active task registry / `task:active` is used for lease repair discovery | Superseded. V0 has no active task registry. LeaseOwner discovers candidates from score-visible task lanes and task-local `rt`. |
| Append can dirty/wakeup scheduler state | Superseded. Append only writes backlog. Latency wakeup is a later best-effort optimization, not scheduling truth. |
| `ready` is the accepted item queue name | Superseded. The queue is `<tr>:task:{taskId}:backlog`; "ready" is not task status. |
| Claim may directly claim due retry entries | Superseded for V0. RetryOwner promotes due retry entries back to backlog, or a later roadmap adds a dedicated retry queue. Dispatch claim consumes backlog only. |
| Ordered final-result window is runtime truth | Superseded. Runtime stores short-retained result rows by `taskId + messageId`; ordered view projection cannot force Redis runtime to keep `final:order` / `final:seq`. |
| Worker reverse active index is task-runtime truth | Superseded. V0 removes `worker:{workerId}:active`; any worker-centric diagnostic must be re-owned or closed before serving cutover. |

## Current Port Pre-Convergence Gates

These gates are not API polish. They are required because several current ports
force old Redis keys to remain part of the public runtime contract. The
pre-convergence phase may keep the old Redis implementation and old serving
behavior, but it must narrow or hide these API shapes so the new mechanism can
land without inheriting their truth model.

This is the only cleanup allowed before the new mechanism: caller-visible
surface cleanup, method/port convergence, guard setup, and inventory status.
Module-internal field naming, server views, diagnostics, memory-only behavior,
and old-key implementation cleanup are not part of this gate unless they block
the caller-visible convergence directly.

| Current port/method | Current old-key pressure | Pre-convergence target |
| --- | --- | --- |
| formerly `TaskRuntimeReadPort#readFinalResults(FinalResultReadRequest)` with `afterSeq + limit` | Requires ordered `final:order` / `final:seq` projection if treated as core runtime truth | Move out of core runtime API. If a read window remains, it must sit behind a separate non-core read-model port and cannot define score-band Redis runtime truth. |
| `TaskRuntimeRepairPort#getActiveWorkForWorker(ActiveWorkQuery)` | Requires `worker:{workerId}:active` reverse index or a global scan | Move out of core runtime API or mark as temporary diagnostic residue. Do not let the new Redis runtime implement worker reverse truth. |
| `TaskRuntimeSchedulerPort#markTaskDirty(String taskId)` | Requires `dirty` hint semantics | Remove from caller-visible runtime API. Existing implementation may keep dirty internally until cutover, but append must not expose a dirty contract. |
| `TaskRuntimeRepairPort#pollExpiredActiveLeases(...)` | Currently depends on global task scan plus active hashes | Narrow to LeaseOwner semantics. Existing implementation may still use old keys internally until the new mechanism replaces it. |
| `TaskRuntimeSchedulerPort#discoverEligibleTasks(...)` | Currently depends on global task registry, eligibility, and ready length | Narrow to score discovery semantics. Existing implementation may still use old keys internally until the new mechanism replaces it. |
| `appendBatch(...)` | Currently writes `ids`, `ready`, `tasks`, and `dirty` | Narrow to backlog append semantics. Existing implementation may still write old keys internally until cutover, but the API must not expose ids/dirty/score behavior. |

SBRK-0A must turn this table into a method-level pre-convergence matrix. SBRK-0B
is the first code slice that applies that matrix. It is allowed to keep old
Redis keys behind the narrowed API. It must not delete old serving truth or
introduce new score-band serving truth.

The matrix lives in
`roadmap/TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_INVENTORY.md` unless a
reviewer explicitly chooses a different sibling artifact before SBRK-0A starts.
Minimum columns:

| Current method | Current callers | Old keys forced | Target grouped method | SBRK-0B action | SBRK-4 serving cutover action | SBRK-5 delete/guard action | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |

Rows are closed by interface family, not by vague feature area. For example,
`appendBatch` closes through the work surface, `discoverEligibleTasks` and
`markTaskDirty` close through the score surface, result-correlation point reads
close through the read surface, and result apply / retry / lease repair close
through the convergence surface.

## Target Runtime Redis API Surface

This roadmap scopes only the `xa-mass-task-runtime` API directly implemented by
the Redis runtime. It should not create one Java port per internal owner. Too
many ports would make the runtime look more modular while actually scattering
one Redis state machine across many tiny interfaces.

Target shape: four direct core Redis runtime ports, with owner boundaries
enforced by method semantics, accepted model types, and key guards.
Temporary read-model ports may exist only outside this core surface; they cannot
be used as score-band mechanism proof or as a reason to keep ordered runtime
truth.

### API Shape Rules

- Do not use method-local `Command`, `Request`, `Context`, or `Options` records
  as target API shapes just to group parameters.
- Prefer primitive identities such as `taskId`, `laneKey`, `messageId`,
  `workerId`, `leaseToken`, `nowMillis`, and `limit`.
- Prefer runtime-owned models that map to real Redis values:
  encoded backlog frame JSON, `TaskRuntimeMetaV1`, `TaskScoreV1`,
  `RuntimeItemStateV1`, `RetryFrameV1`, `FinalResultV1`, and
  `RuntimeResultFact`.
- Prefer opaque evidence returned by the runtime and handed back unchanged, such
  as `ScoreCandidate`.
- A grouped port is not permission for a method to touch unrelated owner keys.
  The key impact column below is the contract.

### Target Grouped Ports

| Target port | Target methods | Key impact |
| --- | --- | --- |
| `TaskRuntimeWorkPort` | `appendBacklog(String taskId, List<AppendItemInput> items, int maxBatchSize)`; `claimBacklog(ScoreCandidate candidate, List<WorkerReservationEvidence> reservations, int maxItems, long leaseMillis, long nowMillis)` | Append writes only `backlog`. Claim atomically validates `ScoreCandidate` and moves `backlog -> rt`; its only score effect is dispatch-band to `NON_SCHED_ACTIVE_MAINTENANCE` when backlog drains and active work remains. No dirty, ids, retry, repair, terminal, or close. |
| `TaskRuntimeScorePort` | `putRuntimeMeta(TaskRuntimeMetaV1 meta)`; `setTaskScore(String taskId, String laneKey, RuntimeEpoch epoch, TaskScoreV1 score)`; `removeTaskScore(String taskId, String laneKey, RuntimeEpoch epoch)`; `scoreCandidate(String taskId, String laneKey)`; `discoverSchedulable(String laneKey, long maxScore, int limit)` | Owns task-local `meta` and lane `task:score`. Discovery and point-read return opaque `ScoreCandidate`; callers do not fabricate score candidates. |
| `TaskRuntimeConvergencePort` | `promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit)`; `scanExpiredLeases(String laneKey, long nowMillis, int taskLimit, int itemLimit)`; `applyResult(RuntimeResultFact fact)`; `closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch)`; `discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason)`; `discardWork(String taskId, RuntimeEpoch epoch, String reason)` | Owns post-claim convergence. `scanExpiredLeases` is discovery-only; timeout mutation must enter through `applyResult(LEASE_TIMEOUT)` so engine/review events and runtime state converge through the same result boundary. |
| `TaskRuntimeReadPort` | `finalResult(String taskId, String messageId)`; `resultCorrelation(String taskId, String messageId)`; `progressSnapshot(String taskId)`; `activeWorkForTask(String taskId, int limit)` | Task-local point reads only. No ordered final window, worker reverse lookup, global scan, or server view projection. |

### Temporary Non-Core Read Surface

`TaskRuntimeResultWindowReadModel#readFinalResults(FinalResultReadRequest)` is
the non-core read-model surface used while current server/engine callers still
need an ordered final-result window. It is not one of the four direct Redis
runtime ports, and it must not write, require, or prove `final:order` /
`final:seq` as
core runtime truth. SBRK-5 must either delete it or re-own it under a read-model
projection that is explicitly outside the task-runtime score-band mechanism.

### Current API Pre-Convergence Targets

| Current API | Target decision |
| --- | --- |
| `TaskRuntimeAppendPort#appendBatch(AppendBatchCommand)` | Expose as `TaskRuntimeWorkPort#appendBacklog(...)`; keep all-or-rejected batch semantics, but remove `ready` vocabulary and avoid expandable command DTOs. Old implementation may still write old keys until cutover. |
| old append DTO backlog cap | Remove from target core API, or introduce a non-runtime `maxBacklogItems` bound only if a real backlog bound is implemented. `maxAppendBatchSize` is enough for the first cut. |
| `TaskRuntimeSchedulerPort` | Expose as `TaskRuntimeScorePort`. `markTaskDirty` must not remain caller-visible core runtime API. |
| `ClaimReadyCommand` / `claimReady` | Expose as `claimBacklog(...)`; consume `ScoreCandidate` evidence from score discovery or score-owner point-read instead of caller-built score fields. Old implementation may still read `ready` internally until cutover. |
| `TaskRuntimeRepairPort#pollExpiredActiveLeases(...)` | Expose as `scanExpiredLeases(...)` for bounded expired-active discovery, then apply timeout through `applyResult(LEASE_TIMEOUT)`. Old implementation may still poll old active keys until cutover. |
| `TaskRuntimeRepairPort#getActiveWorkForWorker(...)` | Remove from target core runtime API. Re-own later as diagnostics/read-model only if needed. |
| `TaskRuntimeResultPort#getResultCorrelation(...)` | Move to `TaskRuntimeReadPort#resultCorrelation(taskId, messageId)`. Correlation is task-local point-read evidence; it must not keep result apply, ordered final windows, or worker reverse indexes alive. |
| `ResultApplyCommand` | Expose result apply as `RuntimeResultFact`; retry/finality/retention policy is read from runtime meta or `rt`, not passed as engine snapshots. |
| `TaskRuntimeReadPort#readFinalResults(FinalResultReadRequest)` | Remove from target core runtime API. If still needed for current callers, expose it only through `TaskRuntimeResultWindowReadModel` as a non-core read-model surface outside `TaskRuntimePortSet`. |
| `TaskRuntimeProgressPort` | Fold into `TaskRuntimeReadPort#progressSnapshot(String taskId)`. |
| `TaskRuntimeDiscardPort` | Fold into `TaskRuntimeConvergencePort`; discard remains an explicit fence/cleanup operation. |

## Slice Plan

Execution rule:

- SBRK-0 and SBRK-0A are documentation/inventory gates.
- SBRK-0B is the first code gate and must be reviewable without any new
  score-band Redis serving implementation.
- SBRK-0C is required only if the worktree already contains mixed score-band
  implementation or grouped-port delegation; it reconciles that state before
  proof/cutover claims.
- SBRK-0D is the final pre-mechanism cleanup gate. It closes or parks remaining
  old public/test/read-model pressure without changing serving Redis truth, and
  it must end with fresh compile/guard proof for the current worktree.
- SBRK-1 locks the owner state-machine design and must be reviewable without
  writing new serving Redis truth.
- SBRK-2/SBRK-3 are Redis-backed proof gates and must be reviewable without
  production serving cutover.
- SBRK-4 is the only slice that may make score-band Redis keys serving truth.
- SBRK-5 is the only slice that may claim the old Redis path is closed.

If an implementation slice cannot respect that separation, stop and revise this
roadmap before continuing. Do not hide the problem behind a bridge or a
temporary fallback unless the slice names the owner, shutdown point, and guard.

No later slice may start from a non-compiling SBRK-0B or SBRK-0D worktree. If
old port deletion, command-bucket deletion, or caller movement temporarily
breaks compile, the next work remains old-surface cleanup repair, not SBRK-1/
SBRK-2 expansion.

No Redis mechanism implementation may start while SBRK-0D still has
unclassified old exposure or pending guard proof. SBRK-0D does not require
every old helper to be deleted; it does require every survivor to be explicitly
local-only, non-core read-model, or a named SBRK-4/SBRK-5 blocker.

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
- every old key in Old Keyspace To Retire has a remove,
  replace, or explicitly deferred decision;
- lane membership is defined as one lane per task per runtime epoch;
- metadata is defined as one task-local HASH per task, with directly readable
  fields for hot scheduler/runtime values;
- state-machine mutation rules remain draft only;
- API DTO/port changes remain draft only.

Verification:

- document review only; no code behavior changes.

### SBRK-0A: Old Runtime Surface Closure Matrix

Goal: identify exactly what must be narrowed in the current old runtime surface
before any new score-band Redis code becomes serving behavior.

Scope:

- create or update
  `roadmap/TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_INVENTORY.md`;
- inventory all current callers of `readFinalResults`, `getActiveWorkForWorker`,
  `markTaskDirty`, `pollExpiredActiveLeases`, `discoverEligibleTasks`, and
  `appendBatch`;
- for each method, record whether pre-convergence will hide it, rename it,
  fold it into one of the four grouped ports, or move it to a temporary
  diagnostic/read-model lane;
- record the old Redis keys each current method forces to remain visible;
- record the final closure target for each method and old-key pressure point;
- record the blueprint points superseded by this roadmap so implementation does
  not re-add active registry, dirty hints, ready naming, ordered final windows,
  or worker reverse indexes as "compatibility";
- confirm `TaskRuntimeWorkPort`, `TaskRuntimeScorePort`,
  `TaskRuntimeConvergencePort`, and `TaskRuntimeReadPort` as the direct Redis
  runtime API surface, or record a reviewed reason for any split/merge before
  code edits;
- classify each current `TaskRuntime*Port` method as one of: target grouped
  surface, internal old-mechanism residue, temporary read-model/diagnostic
  residue, or delete;
- decide whether any existing DTO is a real runtime model, temporary adapter
  input, or old API bucket that must disappear by SBRK-5;
- do not modify runtime behavior in this slice except for documentation,
  inventory, or guard scaffolding that does not affect serving code.

Acceptance:

- every current port method that pressures a forbidden key has a current caller
  list, current old-key pressure, pre-convergence target, and final closure
  target;
- no row may say "later classify" if it blocks the new mechanism API from
  landing behind the four grouped ports;
- the matrix makes clear which old path remains production-serving until
  SBRK-4 and which surfaces are safe to narrow in SBRK-0B;
- `readFinalResults`, `getActiveWorkForWorker`, and `markTaskDirty` are no
  longer accepted as target core runtime commitments;
- no new score-band Redis key becomes serving truth in this slice;
- every target method uses primitive identities, runtime-owned models, or
  opaque runtime evidence; no target method uses expandable method-local
  command/request/options DTOs as its primary API shape;
- the inventory can tell a reviewer exactly which old Redis key pressure is
  still serving implementation residue, and which exposure has already been
  removed from callers.

Verification:

- document/inventory review only, plus optional guard compile if a guard is
  added;
- no Redis keyspace behavior change is allowed in this slice.

### SBRK-0B: Old Mechanism Pre-Convergence Code Slice

Goal: apply the SBRK-0A matrix so current serving code talks through the
narrowed runtime API, while the old Redis keyspace remains the serving truth.

This is the next preferred implementation slice. It is allowed to be invasive
where it removes old exposure, but it is not a new Redis mechanism slice and it
is not old-key deletion. Any score-band classes already present in the worktree
must be ignored for this slice's success criteria unless the slice is explicitly
running SBRK-0C reconciliation.

Scope:

- move current callers from old wide ports to the approved direct runtime Redis
  API surface where SBRK-0A says it is safe;
- keep old Redis key writers/readers inside the Redis implementation as
  implementation residue until SBRK-4/SBRK-5;
- treat this slice as old exposure cleanup only: it may hide, narrow, or delete
  caller-facing old ports, but it must not claim the old Redis path is closed;
- remove caller-visible dirty, ordered result-window, and worker-active
  reverse-index commitments from core runtime paths when their callers can be
  narrowed without changing production behavior;
- update starter/runtime port sets only to expose the narrowed target surface;
- update tests and architecture guards to prove the exposed surface narrowed;
- if score-band proof classes already exist in a worktree, keep them outside
  the SBRK-0B completion claim and do not route serving callers through them;
- do not implement the score-band keyspace, do not expand proof code, and do
  not make new keys serving truth in this slice.
- do not clean module-internal field names, server views, or memory-runtime
  behavior unless that cleanup is required to remove a direct old Redis runtime
  API exposure.

Acceptance:

- existing production behavior remains on the old Redis mechanism;
- old Redis keys may still be used internally, but old-key vocabulary no longer
  leaks through target runtime ports;
- if a grouped target method still delegates to old implementation logic, that
  delegation is documented as SBRK-0B residue and is not counted as score-band
  mechanism proof;
- append callers can express "append backlog" without requiring `ids`,
  `dirty`, `ready`, or score mutation semantics;
- dispatch callers can express "discover score candidate" and "claim backlog"
  without requiring global task scan or caller-fabricated score fields as
  public commitments;
- result/read callers no longer force ordered final-result windows or
  worker-reverse active indexes into the core runtime API; any ordered window
  that still exists is explicit non-core read-model residue;
- result-correlation callers use task-local read semantics, not the old result
  apply port as a mixed read/write owner;
- no score-band Redis proof or serving cutover code is mixed into this slice;
- old core ports/DTOs may remain only when they are implementation-local residue
  or test residue named in the inventory; they must no longer be required by
  engine, starter, SDK, or Redis serving assembly;
- compile or architecture guards fail if engine/starter/SDK callers re-enter
  old dirty, ordered final-window, worker-active reverse lookup, or global task
  discovery APIs after the narrowed surface exists.

Verification:

```text
mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine,xa-mass-engine-starter -am -DskipTests test-compile
```

Focused tests should be added only for the caller surface that actually moved.
They must not be presented as score-band Redis mechanism proof.

SBRK-0B closure evidence established before SBRK-0D:

- engine serving result apply no longer imports, constructs, or maps through
  `ResultApplyCommand`; `TaskRuntimeServingLane` applies
  `RuntimeResultFact` through `TaskRuntimeConvergencePort`;
- `TaskRuntimeResultFactMapper` replaces the old engine result-command mapper
  and does not accept engine retry/finality policy snapshots;
- `RuntimeResultFact.from(ResultApplyCommand)` has been removed so the target
  fact cannot be reconstructed from the old command bucket;
- ordered final-window reading has moved out of core `TaskRuntimeReadPort` and
  `TaskRuntimePortSet` into `TaskRuntimeResultWindowReadModel`, so the core read
  surface no longer forces ordered `final:order` / `final:seq` semantics;
- engine assignment no longer imports or accepts the old
  `ClaimReadyCommand` bucket; `TaskAssignmentRuntimePort#claimReady` keeps the
  engine vocabulary but takes direct claim parameters and delegates to
  `TaskRuntimeWorkPort#claimBacklog`;
- `TaskRuntimeServingLane#claimReady` no longer constructs `ScoreCandidate`
  from task id and clock; it asks `TaskRuntimeScorePort#scoreCandidate` for the
  score-owner candidate and refuses claim when the task is not score-visible;
- engine-starter tests now compile against the same direct claim parameter
  surface, so `ClaimReadyCommand` is no longer needed to prove starter assembly;
- `TaskRuntimeProgressPort` has been deleted; `progressSnapshot` is now only on
  `TaskRuntimeReadPort`, so progress no longer has a separate old core port;
- old command-bucket port interfaces have been deleted from
  `xa-mass-task-runtime/src/main`: `TaskRuntimeAppendPort`,
  `TaskRuntimeSchedulerPort`, `TaskRuntimeClaimPort`,
  `TaskRuntimeResultPort`, `TaskRuntimeRepairPort`,
  `TaskRuntimeProgressPort`, and `TaskRuntimeDiscardPort`;
- `TaskRuntimeArchitectureGuardTest` now guards that those old port source files
  stay deleted and that the target public ports do not expose engine, transport,
  Spring, Redis, or shared runtime internals;
- memory runtime and engine concurrency wrappers keep old command-style methods
  only as explicit test-local/implementation residue; they no longer extend
  deleted old port interfaces;
- SBRK-0B compile and focused guard proof was established before the current
  SBRK-0D cleanup edits:
  `mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine,xa-mass-engine-starter -am -DskipTests test-compile`,
  task-runtime guard/shape tests, memory contract/retention tests,
  starter architecture/bootstrap tests, Redis architecture/score-band candidate
  tests, and engine serving-lane/result-convergence tests;
- this closes engine serving exposure for result apply and closes the core read
  port's ordered-window exposure, plus the engine claim command-bucket
  exposure and caller-fabricated score-candidate exposure. It does not close old
  memory-runtime residue, the non-core result-window read-model residue, the
  `claimReady` engine vocabulary, or score-band Redis proof/cutover.

This is a SBRK-0B proof only. It is not SBRK-3 Redis mechanism proof and not
SBRK-4 serving cutover proof. It is also not current SBRK-0D proof after new
cleanup edits. If an old command DTO or same-signature method still exists, it
must stay inventoried as implementation/test residue instead of being described
as closed runtime truth.

### SBRK-0C: Mixed Implementation Reconciliation Gate

Goal: repair any worktree that already mixed pre-convergence, proof code, and
serving score-band delegation before the roadmap proceeds.

This slice exists because the correct order is not always the order found in a
dirty worktree. It is not a feature slice. It decides whether the existing
score-band code is non-serving proof residue or a complete serving cutover
candidate.

SBRK-0C cannot be used to avoid SBRK-0B. If the worktree already advanced too
far, the reconciliation action is to freeze the new path, document what has
already moved, finish old exposure pre-convergence, and then either quarantine
the new code or finish the full proof/cutover gate. It is not permission to keep
adding Redis behavior while old public surfaces are still open.

Scope:

- inventory all score-band Redis classes, codecs, tests, and grouped-port
  delegations currently present in the worktree;
- classify each one as pre-convergence surface, non-serving proof harness,
  serving implementation, or old implementation residue;
- if choosing **Quarantine**, remove starter/engine serving reachability from
  score-band Redis code while keeping the narrowed port surface intact;
- if choosing **Advance**, require the SBRK-3 mechanism proof and SBRK-4 serving
  cutover criteria in the same implementation review path;
- update
  `roadmap/TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_INVENTORY.md` so the
  current grouped surface says whether it is quarantined proof or serving
  cutover candidate;
- do not use SBRK-0C to add new Redis behavior unrelated to the reconciliation.

Acceptance:

- a reviewer can tell which code path is production-serving and which path is
  proof-only;
- no grouped-port Redis method both accepts newly serving work and falls back to
  old keys for result, retry, lease repair, or close;
- tests that prove pre-convergence do not call target score-band Redis code;
- tests that prove score-band Redis do not call old `ResultApplyCommand`,
  ordered final-window reads, worker-active reverse reads, or old key builders;
- the roadmap status remains active unless either the quarantine path is clean
  or the advance path satisfies SBRK-3/SBRK-4;
- if **Advance** is selected before old exposure is fully converged, every
  remaining old surface must stay inventoried as a blocker and the next code
  slice must be SBRK-0B/SBRK-0D closure; SBRK-1 may proceed only as
  design/state-machine backfill that does not expand Redis mechanism code.

Verification:

```text
rg -n "RedisScoreBandTaskRuntime|ResultApplyCommand|readFinalResults|getActiveWorkForWorker|worker:\\*:active|final:order|final:seq" platform_infra/mass-task-runtime-redis xa-mass-engine sdk/xa-mass-task-runtime-starter-sdk
mvn -pl platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine -am -DskipTests compile
```

The exact focused tests depend on whether the implementation chooses
Quarantine or Advance.

### SBRK-0D: Pre-Mechanism Old-Surface Cleanup Closure

Goal: remove or park the old public pressure points that would make the
score-band Redis proof ambiguous, while leaving the current serving Redis truth
unchanged.

This is the cleanup the roadmap does before writing the new mechanism. It is not
field-name cleanup, server view cleanup, memory-runtime polishing, or old Redis
key deletion. It exists so SBRK-1/SBRK-3 can prove the new score-band mechanism
through a narrow runtime surface instead of repeatedly defending against old
port vocabulary and view-model pressure.

Scope:

- close any remaining production caller dependency on old command-bucket DTOs
  and same-signature methods;
- delete old command-bucket DTO source files from core main when they no longer
  have production callers; if tests still need the old shape, move that shape
  into test-local fixtures instead of keeping it as runtime API;
- move old command-style helpers that are still needed by tests into test-local
  fixtures, or mark them implementation-local in the inventory;
- keep `TaskRuntimeResultWindowReadModel` only as a non-core read-model surface,
  and prevent it from defining Redis runtime keys or proof;
- strengthen architecture/keyspace guards so engine, starter, SDK, and Redis
  serving assembly cannot reintroduce `markTaskDirty`, global task discovery,
  worker-active reverse reads, ordered final-window core reads, or old command
  buckets;
- keep existing score-band classes frozen as SBRK-0C residue;
- do not add Redis Lua scripts, new score-band mutations, new Redis keys, or
  serving delegation in this slice;
- do not delete old Redis keys that are still needed by the current serving
  implementation.

Acceptance:

- every row in the inventory has one of these SBRK-0D statuses: `removed from
  production surface`, `test-local only`, `implementation-local only`,
  `temporary non-core read-model`, `SBRK-4 blocker`, or `SBRK-5 cleanup`;
- no production engine/starter/SDK caller can compile against the old
  task-runtime command-bucket ports;
- no core runtime port exposes dirty hints, ordered result windows, worker
  reverse active lookup, global task scan, or caller-fabricated score candidate
  construction;
- old command-bucket DTO source files are either deleted from core main or
  inventoried as implementation-local blockers with an explicit SBRK-5 removal
  condition;
- Redis score-band candidate code remains frozen and is not expanded by this
  slice;
- tests added or changed in this slice prove exposure closure or guard behavior
  only; they are not counted as Redis mechanism proof.

Verification:

```text
mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine,xa-mass-engine-starter -am -DskipTests test-compile
mvn -pl xa-mass-task-runtime -Dtest=TaskRuntimeArchitectureGuardTest,TaskRuntimeContractShapeTest test
mvn -pl platform_infra/mass-task-runtime-redis -Dtest=RedisTaskRuntimeArchitectureGuardTest test
```

Focused module tests should be added when a row moves from production surface to
test-local or implementation-local. Do not use `-Dsurefire.failIfNoSpecifiedTests=false`
for guard proof.

### SBRK-1: State-Machine Mutation Design

Goal: lock how each owner mutates the approved keyspace without turning one
loop or interface into a defensive catch-all.

This slice is discussion/design before serving cutover. It must answer at least:

- which TaskScoreOwner mutation creates, updates, parks, or removes score
  membership;
- where initial task-local metadata is created;
- how append stays a backlog-only mutation and does not change task score
  visibility;
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

Required TaskScoreOwner transition table:

| Trigger | Score mutation | Local-key precondition | Non-owner behavior |
| --- | --- | --- | --- |
| Task shell created | ZADD positive non-schedulable enum, such as `NON_SCHED_PENDING_APPROVAL` | Initial meta may be created | Append may write backlog later but does not change score visibility. |
| Runtime opens / resume / manual schedulable | ZADD timestamp score in schedulable-time band | Task lane is known and owner policy chose a due time | Dispatch may evaluate only because score owner made it visible. |
| Runtime pause event / scheduled delay | ZADD future timestamp score supplied by runtime default pause policy | Task lane is known | This is a time-bounded delay and will become due automatically. |
| Runtime blocked, manual hold, or rejected | ZADD positive non-schedulable enum | Owner-local reason is recorded | Dispatch does not read enum band. |
| Backlog appended | No score mutation | Append writes backlog only | Append never changes score visibility, opens, dirties, wakes, or rescans score. Backlog and score meet only when claim consumes a score candidate and backlog frame. |
| Dispatch claim creates `rt` | No terminal/discard check in dispatch; if backlog drains while `rt` remains, atomically move score to `NON_SCHED_ACTIVE_MAINTENANCE` | Candidate came from score, lane/epoch/fence/score still match, backlog had frames, claim writes `rt` | LeaseOwner/result path owns active item convergence; dispatch does not scan maintenance enum by default. |
| RetryOwner promotes due retry to backlog | RetryOwner updates retry/backlog and moves eligible task score back to dispatch due-time | Due retry row exists | Dispatch still consumes backlog only. |
| ResultOwner clears active item | ResultOwner updates `rt`, retry/result; it may move score to dispatch due-time when backlog exists, otherwise `NON_SCHED_ACTIVE_MAINTENANCE` until close | Active lease correlation matches or result is stale/duplicate | Result apply does not become scheduler owner or terminal owner. |
| LeaseOwner expires active item | LeaseOwner updates `rt` and retry/final state; score changes go through TaskScoreOwner | `rt` item is expired | Dispatch does not repair leases. |
| Normal close request | ZADD negative terminal score only if backlog, retry, and `rt` are empty | CloseOwner proves empty local owner keys | Empty backlog alone is not close proof. |
| Hard discard | Fence, ZADD negative terminal score, then cleanup owned local keys | Discard owner sets fence/epoch | Stale claim/result/repair must reject by fence/epoch. |

Owner pseudocode:

```text
TaskScoreOwner.setScoreState(taskId, laneKey, epoch, scoreState, scoreValue):
  HMSET <tr>:task:{taskId}:meta scoreState/laneBucketId/runtimeEpoch/updatedAt
  if scoreState == SCHEDULABLE_TIME:
    require scoreValue >= TIME_SCORE_FLOOR
    ZADD <tr>:task:score:{laneKey} scoreValue taskId
  else if scoreState == PAUSED_DELAY:
    require scoreValue >= TIME_SCORE_FLOOR
    require scoreValue > now
    ZADD <tr>:task:score:{laneKey} scoreValue taskId
  else if scoreState == NON_SCHEDULABLE_ENUM:
    require 0 < scoreValue < TIME_SCORE_FLOOR
    ZADD <tr>:task:score:{laneKey} scoreValue taskId
  else if scoreState == TERMINAL:
    require scoreValue < 0
    require CloseOwner or DiscardOwner terminal precondition
    ZADD <tr>:task:score:{laneKey} scoreValue taskId

TaskScoreOwner.openOrResume(taskId, laneKey, epoch, dueAtMillis):
  require dueAtMillis >= TIME_SCORE_FLOOR
    ZADD <tr>:task:score:{laneKey} dueAtMillis taskId

AppendOwner.appendBatch(taskId, frames):
  RPUSH <tr>:task:{taskId}:backlog frames
  return accepted

DispatchOwner.evaluateTask(laneKey, taskId, now):
  // precondition: taskId came from positive due score band
  meta = HMGET <tr>:task:{taskId}:meta policy/epoch/dispatch fields
  outcome = claimBacklog(candidate, workerReservations, limit, leaseMillis, now)
  if outcome == STALE_CANDIDATE:
    return STALE_SKIP
  if outcome == EMPTY:
    return EMPTY_SKIP
  return CLAIMED

ClaimOwner.claimBacklog(candidate, reservations, limit, leaseMillis, now):
  // one Lua/atomic boundary
  require candidate.observedScore >= TIME_SCORE_FLOOR
  require candidate.observedScore <= now
  require meta.laneBucketId == candidate.laneKey
  require meta.runtimeEpoch/fenceToken == candidate.runtimeEpoch/fenceToken
  require ZSCORE task:score:{laneKey} taskId == candidate.observedScore
  frames = LPOP <tr>:task:{taskId}:backlog up to limit
  HSET <tr>:task:{taskId}:rt messageId -> RuntimeItemStateV1 for each frame
  if frames claimed and LLEN backlog == 0 and HLEN rt > 0:
    ZADD <tr>:task:score:{laneKey} NON_SCHED_ACTIVE_MAINTENANCE taskId
  return claimed frames

RetryOwner.promoteDueRetry(taskId, now):
  ids = ZRANGEBYSCORE <tr>:task:{taskId}:retry:score -inf now LIMIT ...
  for each id:
    frame = HGET <tr>:task:{taskId}:retry:item id
    RPUSH <tr>:task:{taskId}:backlog encodedBacklogFrame(frame)
    ZREM retry:score id
    HDEL retry:item id
  if any promoted:
    ZADD <tr>:task:score:{laneKey} dueAt(now) taskId

LeaseOwner.expireLeases(laneKey, now):
  for taskId from owner-selected positive enum or future timestamp candidates:
    scan bounded <tr>:task:{taskId}:rt entries
    for expired entry:
      applyResult(LEASE_TIMEOUT) moves to retry or final according to retry policy

ResultOwner.applyResult(fact):
  require matching active lease token/worker/attempt in rt
  HDEL <tr>:task:{taskId}:rt messageId
  if retryable:
    write retry keys or FAST_RETRY backlog
  else:
    HSET <tr>:task:{taskId}:result messageId -> FinalResultV1
  if LLEN backlog > 0:
    ZADD <tr>:task:score:{laneKey} dueAt(now) taskId
  else:
    ZADD <tr>:task:score:{laneKey} NON_SCHED_ACTIVE_MAINTENANCE taskId

CloseOwner.tryClose(taskId):
  if LLEN backlog == 0 and ZCARD retry:score == 0 and HLEN rt == 0:
    request TaskScoreOwner negative terminal score
  else:
    keep task non-terminal
```

Expected atomicity direction:

- append batch can be single-command or pipeline plus a bounded append limit;
  Lua is not required for v0 append unless the accepted batch and a backlog
  limit must become one atomic unit;
- score/status update is usually direct `HMSET + ZADD/ZREM`; Lua is required
  only when the score owner's own mutation needs an epoch/fence check, lane
  migration, or terminal precondition in the same atomic boundary;
- claim uses Lua to keep `ScoreCandidate` fence validation, `LPOP backlog`,
  `HSET rt`, and the dispatch-to-`NON_SCHED_ACTIVE_MAINTENANCE` score transition atomic; it
  should not become a terminal/discard/retry/repair guard;
- result apply / lease repair likely need Lua because they validate active lease
  correlation and move one item to final, retry, or stale rejection;
- retry promotion may need Lua for `retry:score` + `retry:item` + `backlog`
  movement; it must not be folded into dispatch claim just to avoid a script.

Acceptance:

- the mutation design references only approved keys;
- no mutation reintroduces global task scan, dirty set, id set, or result window
  projection;
- the design names TaskScoreOwner, AppendOwner, DispatchOwner, RetryOwner,
  LeaseOwner, ResultOwner, and CloseOwner responsibilities;
- the design includes the TaskScoreOwner transition table and the V0 candidate
  source decision for RetryOwner and LeaseOwner;
- dispatch evaluation does not perform terminal/discard checks, retry promotion,
  lease repair, result cleanup, or task close;
- append remains all accepted or rejected for v0;
- duplicate `messageId` is explicitly undefined input in V0, not an append
  responsibility.

### SBRK-2: Redis Keyspace Proof Harness

Goal: create adapter-local proof that the Redis implementation can write the
approved keyspace without serving production traffic.

Do not start this slice until SBRK-0B has removed old-key pressure from the
caller-facing runtime surface, SBRK-0C has reconciled any mixed implementation
already present in the worktree, SBRK-0D has closed or parked remaining old
surface residue, and SBRK-1 has locked the owner transition rules. This proof
may introduce key codecs, frame codecs, and Redis-backed tests, but it must not
become a serving runtime path.

If the worktree already contains key codecs or Redis proof tests before this
slice, SBRK-2 does not expand them. First classify them through SBRK-0C, restore
SBRK-0B proof, finish SBRK-0D cleanup classification, and then decide whether
they are reused as proof scaffolding or rewritten under the locked
state-machine rules.

Scope:

- add internal key codec and frame codec classes under the Redis implementation;
- add Redis-backed keyspace contract tests that assert `TYPE`, members/fields,
  and logical frame round trip for every approved target key;
- add negative tests proving append does not write `ids`, `dirty`, `tasks`, or
  per-item keys;
- prove score visibility is separate from append by seeding task-local meta and
  lane score through the score owner path;
- do not use pure memory runtime as proof.

Acceptance:

- a Redis-backed test can append a batch and observe only the approved backlog
  key plus any pre-existing task-local meta and lane score keys created by
  score-owner visibility;
- a Redis-backed test can seed lane score and task-local meta, then discover
  candidates through ZSET operations plus pipelined task-local meta field reads,
  not global task scanning or full payload parsing;
- forbidden old keys are absent after the proof scenario;
- the proof does not route production serving callers to the score-band
  implementation and does not dual-write old/new serving truth.

Verification candidates:

```text
mvn -pl platform_infra/mass-task-runtime-redis -Dtest=*Redis*Keyspace* test
```

The exact test class name must be set by the implementation slice.

### SBRK-3: New Redis Mechanism Proof Behind Narrow API

Goal: build and prove the approved score-band Redis mechanism behind the
narrowed runtime API before it becomes the serving path.

Scope:

- start only after SBRK-0B has removed old-key exposure from starter/engine
  callers, SBRK-0C has reconciled any mixed implementation already present,
  SBRK-0D has closed or parked old public/test/read-model pressure, and SBRK-1
  has locked the state-machine owner rules; otherwise the proof path will
  inherit the wrong public contract, encode the wrong transition semantics, or
  accidentally become serving truth;
- implement a Redis proof path for `TaskRuntimeWorkPort`,
  `TaskRuntimeScorePort`, `TaskRuntimeConvergencePort`, and
  `TaskRuntimeReadPort` using only approved score-band keys;
- replace proof-path discovery from `SMEMBERS <tr>:tasks` with bounded lane
  `ZRANGEBYSCORE`;
- replace proof-path per-task `eligibility` with task-local `meta`;
- replace proof-path `ready` with `backlog`;
- replace proof-path `active` with `rt`;
- replace proof-path `delayed` with retry score/item;
- replace proof-path `final:*` with `result`;
- keep the serving Redis implementation on the old keyspace during this slice
  unless SBRK-0C has explicitly recorded an early Advance exception; in that
  case, freeze serving expansion and use this slice only to prove the already
  reachable score-band path;
- do not delete old serving keys in this slice;
- do not dual-write old and new keys as two serving truths.

Acceptance:

- independent score discovery plus backlog claim uses only the new keyspace in
  Redis;
- TaskScoreOwner visibility is explicit in the proof: append alone cannot make
  a task schedulable;
- claim creates recoverable `rt` state before dispatch handoff and rejects
  stale or non-dispatch-band `ScoreCandidate` evidence without moving backlog;
- result apply can converge success, retryable failure, final failure, duplicate,
  stale, and late result cases through the new keyspace;
- RetryOwner can promote due retry entries back to backlog without dispatch
  evaluation owning retry;
- LeaseOwner can converge expired `rt` entries without dispatch evaluation
  owning repair and without adding `task:active:{laneKey}` in V0;
- a task with empty backlog and nonempty `rt` remains visible through
  `NON_SCHED_ACTIVE_MAINTENANCE` for repair/close, while dispatch discovery ignores it;
- proof-path code writes no forbidden old keys;
- existing serving path remains on the old implementation until SBRK-4 cutover;
- proof-path code is not allowed to become a second production owner before
  SBRK-4.

Verification candidates:

```text
mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-redis test
mvn -pl xa-mass-server -Dtest=*Task*Runtime*,*Redis*Runtime* test
```

The final verification commands must be corrected after test inventory.

### SBRK-4: Serving Cutover To New Redis Mechanism

Goal: route the serving runtime path through the new Redis keyspace behind the
pre-converged API.

Do not do this before SBRK-0A matrix, SBRK-0B pre-convergence, any required
SBRK-0C reconciliation, SBRK-0D cleanup classification, and SBRK-3 Redis proof
are complete. Do not delete old Redis keys in this slice unless the deletion is
necessary to prove there is no dual serving truth.

Scope:

- switch the serving implementation behind the four grouped ports from old
  keys to score-band keys;
- ensure backlog append, independent score discovery, claim, dispatch handoff,
  result apply, retry/lease repair, and close all use one runtime truth;
- disable or bypass old-key serving writers for the cutover lane;
- prove TaskScoreOwner, not append, changes task score visibility in
  `<tr>:task:score:{laneKey}`;
- keep old-key cleanup as SBRK-5 unless it is required to prevent double owner
  writes.

Acceptance:

- serving path creates no new old-key runtime truth for task work accepted after
  cutover;
- no serving path has both old and new keys as owners for the same accepted
  item, active lease, retry, or final result;
- if the serving path creates new `rt`, result apply, retry promotion, lease
  repair, and close are all routed to the new mechanism in the same slice;
- a serving path must not stop at score discovery plus backlog claim if that
  path can create production `rt`; creating `rt` without new result/retry/lease
  convergence is a double-owner bug, not an acceptable partial cutover;
- append creates backlog only; a task with backlog but no independent
  score-owner visibility is not schedulable until TaskScoreOwner opens it;
- focused Redis-backed integration proof covers the serving path, not only the
  adapter-local proof path.

### SBRK-5: Old Path Closure And Cleanup

Goal: remove the old Redis runtime path after serving cutover.

Do not start this slice until SBRK-4 proves that newly accepted production work
uses the score-band keyspace as the only serving truth.

Scope:

- close old behavior by target interface family, not by broad feature label:

  | Family | Old path to close | Closed when |
  | --- | --- | --- |
  | Work | `appendBatch`, `ready`, `ids`, append-side `tasks`/`dirty` writes | Accepted items enter only `backlog`; duplicate message ids remain undefined input; append does not change score visibility. |
  | Score | `discoverEligibleTasks`, `markTaskDirty`, `tasks`, `dirty`, `eligibility` | Discovery reads only lane `task:score` plus task-local `meta`; dirty is absent from the API and keyspace. |
  | Claim | `claimReady`, `ready -> active`, caller-built score/eligibility assumptions | Claim consumes fenced `ScoreCandidate` evidence, atomically moves `backlog -> rt`, and may move dispatch score to `NON_SCHED_ACTIVE_MAINTENANCE` for active-only repair visibility. |
  | Convergence | `delayed`, `active`, result apply through old active/final scripts | Result, retry, lease repair, and close mutate only `rt`, `retry:*`, `result`, and score-owner state. |
  | Read | ordered `final:order` / `final:seq`, worker reverse active index | Core reads are task-local point reads and progress snapshots only. |

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
- old `Command` / `Request` / `Options` DTO buckets that only supported the old
  runtime API are removed;
- every old port method that survives after this slice is either deleted from
  the core runtime surface or documented as non-core diagnostic/read-model
  residue with no production writer;
- TROM completion claims are downgraded or updated if they depended on the old
  Redis shape.

### SBRK-6: Guards, Proof Registry, And Residue Scan

Goal: prevent the old keyspace and old owner vocabulary from re-entering after
cutover.

Scope:

- add or update a Redis keyspace guard that rejects key builders for `tasks`,
  `dirty`, `ids`, `ready`, `eligibility`, `active`, `final:order`,
  `final:seq`, and `worker:*:active`;
- add a port-shape guard that fails if `markTaskDirty`, ordered final window
  reads, or worker-active reverse-index reads reappear in core runtime ports;
- update `doc/PROOF_REGISTRY.md` and testing index entries only after focused
  Redis-backed proof exists;
- scan roadmap/docs/tests for old-key vocabulary preserved as current truth.

Acceptance:

- forbidden old keys cannot be reintroduced without failing focused tests or
  guard tests;
- old blueprint points are marked superseded where they would otherwise be
  read as current implementation direction;
- no proof registry row claims score-band Redis runtime convergence without
  the Redis-backed proof required by this roadmap.

SBRK-6 closure evidence:

- Cross-module mainline compile proof passed:
  `mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine,xa-mass-engine-starter,xa-mass-server,xa-mass-testing -am -DskipTests test-compile`.
- Redis/runtime/starter focused proof passed:
  `mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk '-Dtest=TaskRuntimePortContractTest,InMemoryTaskRuntimeContractTest,RedisScoreBandTaskRuntimeTest,RedisTaskRuntimeArchitectureGuardTest,RedisTaskRuntimeNetworkPartitionTest,RedisTaskRuntimeOwnerReconnectTest,RedisTaskRuntimeScoreBandAdvanceCandidateTest,RedisTaskRuntimeScoreBandKeyspaceProofTest,TaskRuntimeStarterArchitectureGuardTest,TaskRuntimeStarterBootstrapTest' test`.
- Engine focused serving proof passed:
  `mvn -pl xa-mass-engine '-Dtest=TaskRuntimeServingLaneOldPathClosureGuardTest,TaskRuntimeEngineCutoverPreparationTest,TaskRuntimeRecoveryPortTest,TaskResultConcurrencyConvergenceTest,TaskRuntimeServingLaneTest,TaskResultRuntimeConvergenceTest' test`.
- Starter serving-lane wiring proof passed:
  `mvn -pl xa-mass-engine-starter '-Dtest=EngineConfigTaskRuntimeServingLaneTest' test`.
- Server Redis/trace proof passed:
  `mvn -pl xa-mass-server '-Dtest=RedisRuntimeLateReplayE2eScenario,TaskApiAllMessagesFailedTraceObservedIntegrationTest,TaskApiCallbackReplayTraceObservedIntegrationTest,TaskApiMixedResultsTraceObservedIntegrationTest' test`.
- Testing/proof-registry proof passed:
  `mvn -pl xa-mass-testing -DskipTests test-compile`;
  `mvn -pl xa-mass-testing '-Dtest=ProofRegistryClosureGuardTest' test`.
- Production-source residue scans passed with no matches for deleted old port
  names, old command DTO names, old result-window port names, or forbidden old
  Redis key names:
  `rg -n "repairExpiredLeases|TaskRuntimeResultWindowReadPort|ResultApplyCommand|ClaimReadyCommand|RedisTaskRuntimeContractTest|TaskRuntimeResultPort|TaskRuntimeAppendPort|PollActiveLeaseRepairCommand" xa-mass-task-runtime/src/main/java platform_infra/mass-task-runtime-memory/src/main/java platform_infra/mass-task-runtime-redis/src/main/java sdk/xa-mass-task-runtime-starter-sdk/src/main/java xa-mass-engine/src/main/java xa-mass-engine-starter/src/main/java xa-mass-server/src/main/java xa-mass-testing/src/main/java --glob '!**/target/**'`;
  `rg -n "final:order|final:seq|:ready|:ids|:dirty|task:active|worker:\{workerId\}:active|worker:.*:active" platform_infra/mass-task-runtime-redis/src/main/java xa-mass-task-runtime/src/main/java platform_infra/mass-task-runtime-memory/src/main/java sdk/xa-mass-task-runtime-starter-sdk/src/main/java xa-mass-engine/src/main/java xa-mass-engine-starter/src/main/java --glob '!**/target/**'`.

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

- the Redis keyspace in production code matches Target Redis Keyspace V0 or an
  explicitly reviewed successor table in this file;
- SBRK-0A and SBRK-0B are closed before any further score-band serving behavior
  is introduced; if earlier mixed implementation already exists, SBRK-0C records
  it as an exception and blocks expansion until old exposure closure is
  backfilled;
- SBRK-0B has compiling focused proof that engine/starter/SDK no longer need
  old runtime command buckets or old key-pressure APIs before SBRK-0D can close
  and before SBRK-2/SBRK-3 Redis mechanism implementation continues;
- if score-band classes or grouped-port delegation were introduced early,
  SBRK-0C records whether they were quarantined as proof-only or advanced
  through the SBRK-3/SBRK-4 proof and cutover gates;
- SBRK-0D has classified every remaining old surface as removed,
  test-local-only, implementation-local-only, temporary non-core read-model,
  SBRK-4 blocker, or SBRK-5 cleanup before Redis mechanism implementation is
  expanded;
- independent score discovery plus backlog claim -> dispatch handoff -> result
  apply has one Redis-backed proof through the score-band keyspace;
- claim proof rejects stale `ScoreCandidate` evidence and non-dispatch-band
  maintenance candidates without moving backlog;
- retry promotion has one Redis-backed proof through retry keys back to backlog,
  without dispatch evaluation owning retry;
- lease repair has one Redis-backed proof through LeaseOwner and task-local
  `rt`, without dispatch evaluation owning repair and without
  `task:active:{laneKey}` in V0;
- empty-backlog active tasks remain repair/close-visible through
  `NON_SCHED_ACTIVE_MAINTENANCE`, while dispatch discovery ignores that maintenance band;
- the pre-convergence matrix and final closure matrix are closed for core
  ordered final-read exposure, worker-active reads, dirty hints, global task
  discovery, and append side writes;
- any remaining ordered final-result window is explicitly outside the core
  score-band runtime mechanism and is either deleted or re-owned by a non-core
  read-model projection before roadmap completion;
- no production path writes or reads forbidden old keys;
- public/internal task-runtime ports have converged to the approved direct
  Redis runtime API surface, without one-owner-one-Java-port sprawl or
  expandable method-local DTO buckets;
- interface pre-convergence, new mechanism proof, serving cutover, and old path
  cleanup were completed as separate steps rather than mixed into one risky
  slice;
- no pure memory runtime proof is used as the main acceptance evidence;
- stale docs and roadmaps no longer describe the old Redis keyspace or
  superseded blueprint points as current or acceptable.

## Do Not Start With

- Do not split or polish server view APIs first.
- Do not implement or route new score-band Redis serving truth before old
  runtime caller exposure has been pre-converged through SBRK-0A/SBRK-0B.
- Do not add memory-only runtime tests and call the Redis mechanism proven.
- Do not keep `tasks + dirty` as "temporary equivalent" discovery.
- Do not add `ids` or duplicate checks to append unless a separate
  identity/idempotency owner is explicitly designed.
- Do not mix interface pre-convergence, Redis keyspace rewrite, serving cutover,
  and old-key cleanup in one slice.
- Do not treat grouped-port wiring or constructor cleanup as score-band Redis
  mechanism proof.
- Do not leave a mixed worktree unresolved: choose Quarantine or Advance in
  SBRK-0C before claiming later slices.
- Do not start Redis mechanism implementation while SBRK-0D still has
  unclassified old public/test/read-model residue.
- Do not maintain final-result ordered windows in the core runtime just to
  satisfy a view API. If the window survives temporarily, keep it on a separate
  non-core read-model surface and prevent it from defining Redis runtime truth.
- Do not delete `final:*`, `dirty`, or `worker:*:active` in a serving path
  before the port methods that require them are closed.
- Do not let engine policy DTOs become Redis frame values.
- Do not use transport result concerns to redefine task-runtime finality.
- Do not turn dispatch evaluation into terminal/discard validation, retry
  promotion, lease repair, result cleanup, or task close.
- Do not create one Java port per internal owner unless the split protects a
  real lifecycle or protocol boundary.
- Do not replace old ports with new `Command` / `Request` / `Options` records
  that simply gather fields without becoming runtime-owned models.
