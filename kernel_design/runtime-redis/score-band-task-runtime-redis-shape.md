# Score-Band Task Runtime Redis Shape

Status: target runtime design reference, not current implementation truth.

This document describes a compact Redis shape for task work runtime. It is a
directional design note for the future kernel core and executable-spec work.

Boundary note:

- [Task Score-Band Scheduling](../scheduling/task-score-band-scheduling.md) defines the
  task active-acquisition score mechanism.
- This document also covers concrete work-item and result-adjacent Redis shapes
  because those structures must coexist in a task runtime implementation.
- The presence of claim, retry, or result sections here must not be read as
  task score-band owning those facts. Work-item current-claim state and result
  finality remain separate owner planes.

Task work has a different cardinality profile from worker slots:

- worker slots are long-lived runtime resources;
- task items may be append-heavy, short-lived, and quickly consumed;
- a task can contain very large raw backlog, including million-item batches;
- runtime state should be sparse and created only for current active claims.

## Purpose

Keep task runtime Redis structures small, inspectable, and separated from task
shell/control-plane truth.

This shape targets:

- one score index per task lane bucket;
- raw ready backlog stored as a Redis LIST of append-generated message frames;
- sparse runtime item state only while an item is actively claimed;
- no one-key-per-ready-item runtime record;
- no Redis Stream/Pending Entry List as the task claim owner;
- result storage grouped by task, not one result key per item.

Kernel runtime interfaces are the contracts. Scheduling code should not know
whether Redis uses LIST, HASH, ZSET, Lua, or a future implementation detail.

## Production Bias

The first production target is:

```text
large raw backlog, small active state
```

A million-item task should mostly consume memory as LIST entries. The runtime
hash should be proportional to current active claims only. In the default
`FAST_READY` mode, retry delay should not create a million delayed runtime
records; retry frames go back to the ready LIST, and the task lane score
decides when that task may compete again. In opt-in `DUE_TIME` mode, only
delayed retry frames use the task-local scheduled retry lane.

Every accepted raw item receives its logical `messageId` at append time. Claim
does not mint identity; it only turns an existing ready frame into an active
claim. This keeps result lookup, idempotent callbacks, and user-facing
`taskId + messageId` correlation stable before dispatch happens.

This design optimizes for:

- process-crash safety through Redis-owned state transitions;
- best-effort active claim repair;
- low Redis key count;
- no per-item Redis object allocation before claim;
- task-level backoff for the default `FAST_READY` path;
- opt-in per-message due index only for `DUE_TIME` delayed retry frames;
- compact payload encoding instead of verbose runtime DTOs.

Task type chooses one retry visibility mode:

```text
FAST_READY
  retry count and quick re-dispatch are preferred; retry frames return to the
  ready LIST and task score controls the next task-level recheck

DUE_TIME
  message-level next scheduling time is preferred; delayed retry frames live in
  a task-local item score ZSET plus item HASH until they become due
```

The first slice should not promise both guarantees for the same task type.
`FAST_READY` is the compact default. `DUE_TIME` is opt-in for task types that
need a per-message scheduled retry interval.

`FAST_READY` is task-wide backoff. A retry frame returned to the ready LIST may
sit in front of newly appended frames. The runtime must not promise
per-message retry due time in this mode, and claim should not skip or rotate
LIST head entries to simulate per-message scheduling. Task types that require
message-level due time must use `DUE_TIME`.

## Durability Boundary

"No loss" has two separate meanings:

```text
runtime transition no-loss
  a scheduler or adapter process may crash between claim, dispatch, result,
  retry, and expiry without losing the item

Redis durability no-loss
  Redis itself must persist the mutation before acknowledging it
```

This design note covers runtime transition no-loss by requiring atomic Redis
mutations. Redis durability no-loss is an operations choice:

- use AOF and a tested fsync policy for the desired recovery point objective;
- use replication / `WAIT` only as an explicit latency-vs-durability tradeoff;
- do not describe async replica acknowledgement as zero data loss;
- if strict zero-loss across Redis node loss is required, choose storage and
  acknowledgement policy accordingly before advertising that guarantee.

Runtime profiles must surface this choice. A profile that claims no-loss must
fail closed or explicitly report degraded mode when Redis durability settings
do not satisfy the profile. A development or best-effort profile may run with
weaker durability, but it must not present the same guarantee as the no-loss
profile.

## Runtime Guarantees

This task runtime separates hard correctness from best-effort timing.

Design principle:

```text
Task runtime guarantees durable convergence, not real-time precision.
```

The default runtime optimizes for high-throughput, flexible, extensible
convergence. High-cost mechanisms are only justified when a task policy names
the stronger guarantee and the ROI is clear. The default path should not pay
for financial-grade timing, strict fairness, or exact cleanup semantics when
durable item recovery and idempotent result convergence are enough.

Hard commitments:

- an accepted item is durably represented in runtime Redis before append
  returns, subject to the Redis durability configuration above;
- a claimed item remains recoverable from `task:{taskId}:rt` until result,
  retry, finality, or discard removes it;
- active claim repair can discover tasks that still have `rt` records;
- result, retry, and final mutations are idempotent by `messageId` and guarded
  by the current active runtime row plus selected worker identity;
- late replay with no matching current active row, a mismatched worker, or an
  existing final result must not overwrite runtime truth.

Best-effort commitments:

- active claim timeout is repaired eventually, not exactly at
  `claimExpiresAtMillis`;
- pause/block/resume takes effect at the next runtime boundary and does not
  revoke current claims;
- empty-match recheck, retry backoff, and terminal cleanup timing are runtime
  convergence targets, not financial-grade timing guarantees.

`claimExpiresAtMillis` is stored inside `RuntimeItemState` only for
time-bounded work. It is claim evidence and a repair threshold, not a separate
timeout owner. Result apply loads the current `rt` row and compares the returned
worker and optional `claimExpiresAtMillis` against that row. If the current row
still matches, the result may converge the item. Once repair moves the item to
retry backlog or a final result row, the old result has no matching current row.

Append acknowledgement is at-least-once unless the caller supplies a stable
idempotency key and the API layer enables bounded dedupe. If Redis commits the
append and the response is lost, a retry without idempotency may create another
logical item. That is duplicate work, not runtime item loss.

Runtime guarantees work convergence and short-retained final result lookup.
The task-local result hash is not a durable user result ledger; long-term
result truth belongs to a future ledger, trace materialization, or business
storage owner.

## Policy Consistency Model

Task runtime uses claim-boundary consistency, not whole-task stop-the-world
consistency.

The rule:

```text
the current claim row stores the minimal policy/evidence snapshot used by claim
the next claim reads the current task runtime policy
```

This matters when a task is paused, resumed, or its matching / retry policy is
changed while items are already claimed.

Semantics:

- `TaskRuntimeMeta` is the current policy snapshot for future claims.
- `RuntimeItemState` stores the policy versions and resolved claim values used
  when the item was claimed.
- pause/block holds the task lane score and stops new claims, but does not
  revoke current claims.
- active results are validated against the current work hash row, not against
  a newer match policy.
- non-success claim close resolves whether retry should be scheduled from the
  current `TaskRuntimeMeta.retryPolicy`, unless a future policy explicitly opts
  into claim-snapshot retry semantics.
- retried items and newly appended items compete under the current
  `TaskRuntimeMeta` when they are claimed.

Example:

```text
task policy v1 claims item A to worker-1
task is paused and policy changes to v2
worker-1 may still finish item A under the v1 current claim row
if item A needs retry, the retry frame re-enters ready backlog
resume lets item A retry and new item B claim under policy v2
```

This keeps active work stable while allowing pause/resume and policy changes to
take effect at the next scheduling boundary.

## Non-Goals

This design note does not define:

- task shell storage;
- task status as Redis truth;
- worker selection or worker score-band slot state;
- transport dispatch queues;
- a durable result ledger;
- long-term result archive retention;
- a public Redis key contract.

## Owner Boundaries

Runtime Redis owns only hot-path work state:

```text
raw ready backlog
current active claim state
active task repair discoverability
task-level retry backoff through lane score
opt-in message-level scheduled retry visibility
short-retained final result rows
runtime policy snapshot for matching and retry
```

Control-plane storage remains the owner of task shell facts:

```text
taskId
projectId
taskName
Task.status
Task.intakeStatus
Task.contract
sharedConfig
terminalReason
```

Redis may cache a runtime gate or next-action hint, but the kernel scheduler
must validate the task shell before dispatching. A Redis score hit is a
candidate, not a lifecycle authority.

## Core Shape

The first target shape has three lanes:

```text
Task Lane Score
  one ZSET per lane bucket; member is taskId

Raw Ready Backlog
  one LIST per active task; values are append-generated ready frames

Sparse Runtime State
  one HASH per active task; fields exist only for current active claims
```

Active claim repair discovers task ids through a lane registry:

```text
Active Task Registry
  one SET or ZSET per lane bucket; members are task ids with non-empty rt state
```

Opt-in scheduled retry state is task-local:

```text
Scheduled Retry Lane
  one ZSET plus one HASH per task only when retryMode=DUE_TIME; members are
  retry message ids waiting for their next schedulable time
```

Result read truth is task-local and short-retained:

```text
Task Result Rows
  one HASH per task; field is messageId
```

## Key Shape

Use `tr:{prefix}` below as the task-runtime namespace. The default deployment
prefix may still be derived from `xa:mass:runtime:v1`.

### Lane score

```text
tr:{prefix}:task:score:{laneBucketId}
  type: ZSET
  member: taskId
  score: task next-action score
```

`laneBucketId` is the primary task-runtime partition. First slice can use:

```text
laneBucketId = projectId
```

or a deterministic low-cardinality default lane when project partitioning is
not required. It is not a worker bucket and not a per-item shard.

The score answers:

```text
which task lanes are actionable now
which task lanes have a future scheduled retry, backoff, or recheck due time
which task lanes are parked out of scheduling
```

### Active task registry

```text
tr:{prefix}:task:active:{laneBucketId}
  type: ZSET
  member: taskId
  score: repairCandidateAtMillis or lastTouchedAtMillis
```

This registry is not a precise claim-expiry index. It is the discoverability
surface for best-effort active claim repair:

- claim adds `taskId` when it writes at least one `rt` record;
- result, retry, finality, and discard remove `taskId` when `rt` becomes empty;
- repair scans bounded task ids from this registry, then scans the corresponding
  `task:{taskId}:rt` hash within a bounded budget;
- score may be a coarse repair candidate or last-touched time. It does not
  claim exact `claimExpiresAtMillis` ordering.

Without this registry, `task:{taskId}:rt` protects payload recovery but repair
cannot discover which task ids still have current active claims.

### Lane metadata

```text
tr:{prefix}:task:meta:{laneBucketId}
  type: HASH
  field: taskId
  value: TaskRuntimeMeta JSON
```

This is not task shell metadata and not an item-count index. It is the compact
runtime policy snapshot used by the scheduling and retry path. The lane score
is the only next-action schedule; do not duplicate next dispatch or next due
timestamps in metadata.

```json
{
  "taskId": "task-1",
  "laneBucketId": "project-a",
  "dispatchIntent": {
    "workerGroupIds": ["group-a"],
    "targetWorkerId": null,
    "routingCode": null,
    "targetWorkerAttributes": {},
    "matchRuleSetId": "default",
    "matchRuleVersion": 1
  },
  "retryPolicy": {
    "retryMode": "FAST_READY",
    "maxRetryCount": 3,
    "retryDelayMillis": 1000,
    "backoffMode": "FIXED",
    "policyVersion": 7
  },
  "scorePolicy": {
    "healthyMatchWorkerThreshold": 4,
    "healthyMatchRecheckDelayMillis": 0,
    "scarceMatchRecheckDelayMillis": 200,
    "emptyMatchBaseDelayMillis": 1000,
    "emptyMatchMaxDelayMillis": 30000,
    "emptyMatchMaxStreak": 6,
    "contentionRecheckDelayMillis": 500,
    "claimTimeoutMillis": {
      "healthyMatch": 10000,
      "scarceMatch": 30000,
      "default": 30000
    },
    "policyVersion": 3
  },
  "scoreRuntime": {
    "emptyMatchStreak": 0
  },
  "runtimeGate": "OPEN",
  "runtimeEpoch": 42,
  "updatedAtMillis": 1760000000000
}
```

Field rules:

- `dispatchIntent` contains task-side matching and routing constraints, not
  worker facts or transport ids.
- `retryPolicy` contains the current runtime retry budget and backoff policy.
  It is updated when task policy changes; raw ready items do not carry
  `maxRetryCount`.
- `retryPolicy.retryMode` chooses the runtime retry visibility shape. Use
  `FAST_READY` for LIST-based quick retry. Use `DUE_TIME` only when the task
  type needs message-level scheduled retry through `item-score` plus `item`.
- `scorePolicy` contains the compact task-lane scheduling algorithm knobs. It
  decides recheck delay and optional claim timeout from scheduling-round
  evidence, but it does not store the next due time.
- `scoreRuntime` is small dynamic score-algorithm state, such as consecutive
  empty-match penalty. It must not contain item counts or next timestamps.
- `runtimeGate` is a runtime scheduling gate such as `OPEN` or `PARKED`; it is
  not `Task.status`.
- `runtimeEpoch` is a runtime fence. Claim, result, repair, pause/resume, and
  discard mutations must validate the expected epoch inside their Redis atomic
  boundary. Terminal/discard advances the epoch or writes a terminal fence
  before deleting task-local keys.
- `Task.status` must not be copied here as current lifecycle truth.
- item counts may be sampled through Redis commands or maintained as optional
  diagnostics, but they must not become the core `TaskRuntimeMeta` purpose.

### Raw ready backlog

```text
tr:{prefix}:task:{taskId}:ready
  type: LIST
  value: ReadyItemFrame JSON or compact frame
```

This key is allowed to contain many items. One million ready items should remain
one Redis LIST with one million entries, not one million runtime HASH fields and
not one million Redis keys.

Append intake owns this LIST write only. It must not be required to rewrite
task-level scheduling score, task status, or lane state when the backlog length
changes. Scheduler-owned discovery, lane scoring, and wakeup repair are separate
task-level mechanisms.

`ReadyItemFrame` is intentionally close to the original task item. It carries
the append-generated logical `messageId` plus the opaque payload. The runtime
generates and returns `messageId` when append accepts the item; claim must not
generate it later.

```json
{
  "messageId": "07bbc2be-e9b7-4547-bf67-7623606de1b3",
  "payloadJson": {
    "eventCode": "demo.event",
    "target": "raw user payload"
  }
}
```

Notes:

- `messageId` is assigned at append and is required for all ready, claimed, and
  result frames.
- `payloadJson` is opaque to task runtime except for serialization and
  transport handoff.
- handler identity such as `eventCode`, if still needed by the public API, is
  embedded inside `payloadJson` for this target shape. It is not a top-level
  runtime scheduling field.
- retry budget, retry delay, item expiry, routing, and match rules are runtime
  policy facts, not raw item fields.
- retried items re-enter the LIST as a ready retry frame that carries the same
  payload plus `messageId` and `retryCount` when `retryMode=FAST_READY`; no
  sparse runtime hash field remains after requeue.
- when `retryMode=DUE_TIME`, delayed retry frames do not re-enter the ready
  LIST until their scheduled time is due. They are stored in the scheduled
  retry lane below.

The JSON above is a readable logical shape. A production Redis implementation
should store a compact encoded frame instead of pretty JSON:

```text
fresh frame:
  type = READY
  messageId = logical id generated at append
  payload = opaque bytes

retry frame:
  type = RETRY
  messageId = same logical id
  retryCount = current retry count
  payload = opaque bytes
```

Using a compact binary encoding, short field names, or a codec such as CBOR /
MessagePack is an implementation choice. The important rule is that ready
backlog does not allocate Redis hashes or keys per item.

### Scheduled retry lane

```text
tr:{prefix}:task:{taskId}:item-score
  type: ZSET
  member: messageId
  score: nextSchedulableAtMillis

tr:{prefix}:task:{taskId}:item
  type: HASH
  field: messageId
  value: RetryFrame JSON or compact frame
```

These keys exist only for task types using `retryMode=DUE_TIME`. They are not
the default ready backlog and must not receive every appended item.

`item-score` is the message-level visibility index. `item` holds the retry
frame payload:

```json
{
  "messageId": "07bbc2be-e9b7-4547-bf67-7623606de1b3",
  "retryCount": 1,
  "payloadJson": {
    "eventCode": "demo.event",
    "target": "raw user payload"
  }
}
```

Rules:

- result success and final failure delete `messageId` from both `item-score`
  and `item` if present;
- retryable failure under `DUE_TIME` writes the retry frame to `item` and
  writes `nextSchedulableAtMillis` to `item-score`;
- due processing reads due `messageId` values from `item-score`, loads the
  frame from `item`, removes both entries, and feeds the frame into the same
  scheduling/claim path as ready LIST work;
- the task lane score should include the earliest `item-score` timestamp as a
  ready candidate when no earlier score-visible ready candidate exists;
- `item-score` is allowed because normal completion deletes entries quickly and
  because it represents delayed retry visibility, not the whole raw backlog.

### Sparse runtime state

```text
tr:{prefix}:task:{taskId}:rt
  type: HASH
  field: messageId
  value: RuntimeItemState JSON
```

This hash is created only when an item leaves the raw ready LIST for an active
claim. It should remain bounded by active dispatch concurrency, not by total raw
backlog size and not by total retry backlog size.

`RuntimeItemState`:

```json
{
  "messageId": "07bbc2be-e9b7-4547-bf67-7623606de1b3",
  "state": "CLAIMED",
  "readyFrame": {
    "messageId": "07bbc2be-e9b7-4547-bf67-7623606de1b3",
    "retryCount": 0,
    "payloadJson": {
      "eventCode": "demo.event",
      "target": "raw user payload"
    }
  },
  "claimPolicy": {
    "dispatchIntentVersion": 12,
    "matchRuleVersion": 1,
    "retryPolicyVersion": 7,
    "claimTimeoutMillis": 30000
  },
  "runtimeEpoch": 42,
  "workerId": "worker-1",
  "workerGroupId": "group-a",
  "batchId": "batch-1",
  "retryCount": 0,
  "scoreBandClaimScore": null,
  "claimedAtMillis": 1760000001000,
  "claimExpiresAtMillis": 1760000031000,
  "updatedAtMillis": 1760000001000
}
```

Allowed `state` values:

```text
CLAIMED
```

Initial backlog and retry backlog entries do not have sparse runtime state.
First slice should avoid adding more item states unless a new state changes
active claim behavior. Trace, review, and diagnostics can describe richer phases
without becoming Redis runtime state.

`claimPolicy` is not a copy of the full task policy. It is the minimal
version/evidence snapshot needed to prove which policy produced this current
claim and which optional timeout applies to it. The current policy for the next
claim still lives in `TaskRuntimeMeta`.

`runtimeEpoch`, `workerId`, and optional `claimExpiresAtMillis` bind the active
item to the current runtime truth. A result or repair mutation must reject stale
epochs, mismatched workers, and mismatched time bounds when the work is
time-bounded. If worker admission fails, claim must not consume a ready frame;
if a claim mutation fails after worker admission, the caller must compensate
that admission.

### Short-retained final results

```text
tr:{prefix}:task:{taskId}:result
  type: HASH
  field: messageId
  value: FinalResult JSON
```

This hash supports direct short-lived result lookup by `taskId + messageId`.
It is intended for API/RPC-style callers that submit or know a message id and
need to retrieve the corresponding final result. It is not an ordered result
window, not a result queue, and not a durable result ledger.

`FinalResult`:

```json
{
  "messageId": "07bbc2be-e9b7-4547-bf67-7623606de1b3",
  "status": "SUCCESS",
  "finalReason": "WORKER_SUCCESS",
  "retryCount": 0,
  "workerId": "worker-1",
  "workerGroupId": "group-a",
  "batchId": "batch-1",
  "completedAtMillis": 1760000003000,
  "errorCode": null,
  "errorMessage": null,
  "outputJson": {
    "ok": true
  },
  "outputRef": null
}
```

Result payloads should stay opaque. Runtime may classify success/failure and
retry/finality, but it should not parse business result fields into scheduling
truth.

## Score Bands And Rewrite Algorithm

Task score uses the same high-level band idea as resource slots, but it is a
task-lane next-action index, not an item queue and not task shell status:

```text
score absent
  undefined to score-band; not service discovery and not terminal proof

score < 0
  retained closed marker for terminal / closed / discarded tasks

0 <= score < TIME_SCORE_FLOOR
  live but not schedulable by the hot path; enum-like codes owned by task
  runtime lifecycle command policy

TIME_SCORE_FLOOR <= score <= now
  eligible for one bounded scheduling round

score > now
  future scheduling visibility; retry backoff, empty-match recheck, contention
  delay, scheduled retry, or long scheduler hold
```

Recommended first-slice constants:

```text
CLOSED_TERMINAL      = -10
CLOSED_DISCARDED     = -20
CREATED_UNAPPROVED   = 10
TIME_SCORE_FLOOR     = 1_000_000_000_000
SCHEDULER_HOLD_FLOOR = safe far-future epoch millis
```

Score meanings:

```text
ready list has dispatchable item
  score = max(TIME_SCORE_FLOOR, now)

ready list has retryable work but task-level backoff is active
  score = retry visible time

scheduled retry lane has delayed retry work and ready list has no immediate work
  score = earliest item-score nextSchedulableAtMillis

ready list is non-empty, but no worker matched in the last scheduling round
  score = now + empty-match recheck delay

task paused or manually held
  score = SCHEDULER_HOLD_FLOOR or another owner-approved future hold score

task terminal or discarded
  score = retained closed marker
```

The score is an index. Before claim, the kernel runtime must validate:

- task still exists;
- task shell is not terminal;
- task is not paused or blocked;
- intake and terminal policies still allow work;
- ready LIST, scheduled retry lane, task-level retry backoff, or recheck reason
  still matches the score hint.

### Score rewrite rule

Task lane score is rewritten through one deterministic rule, but only from
score-owner paths:

- direct lifecycle/scheduling events such as approve, pause, resume, close, or
  discard;
- the scheduling/matching round after the task has been acquired from the
  score range.

Append intake is not a scheduler-owned mutation. It writes ready backlog truth
and does not emit a generic score refresh.

| Step | Condition | Score action |
| --- | --- | --- |
| 1 | task terminal or discarded | `ZADD CLOSED_* taskId` |
| 2 | task is live but not schedulable | `ZADD 0 <= code < TIME_SCORE_FLOOR taskId` |
| 3 | runtime gate is paused or manually held | `ZADD SCHEDULER_HOLD_FLOOR taskId` |
| 4 | otherwise compute `readyCandidate` from ready LIST / scheduled retry lane / retry backoff / last scheduling round | candidate timestamp or none |
| 5 | candidate exists | `ZADD readyCandidate taskId` |
| 6 | no candidate exists | `ZREM taskId` |

`readyCandidate` is:

| Ready condition | Candidate |
| --- | --- |
| ready LIST has dispatchable work | `max(TIME_SCORE_FLOOR, now + positiveMatchDelay)` |
| `retryMode=DUE_TIME` and scheduled retry lane has entries | earliest `item-score` due time, unless ready LIST has immediate work |
| task-level retry backoff blocks fresh wakeup | `retryVisibleAtMillis` |
| last scheduling round found no eligible worker | `now + emptyMatchDelay` |
| last scheduling round was contended | `now + contentionRecheckDelayMillis` |
| no ready LIST work and no scheduled retry entry | none |

Immediate ready LIST work wins inside `readyCandidate`; scheduled retry due
time is used when the task has no immediate ready frame or when the due retry
frame is the only next ready source. This decides only when the task wakes.
Once the task is awake, claim source policy still needs to prioritize or quota
due scheduled retry frames so they are not starved by a large ready LIST.

Active claim timeout is not a score candidate in this shape. Claimed items stay
recoverable in `task:{taskId}:rt`; timeout is repaired by the best-effort claim
repair loop below. The score is a task scheduling wakeup signal, not the owner
of precise claim-timeout discovery.

### Starvation boundary

The first slice does not implement a full fairness scheduler. Fairness,
priority weights, per-project quotas, and round-robin cursors belong to later
`scorePolicy` work.

The mechanism still needs a no-permanent-starvation floor:

- task score is the next eligible time, not a permanent `firstReadyAt`;
- first scheduler discovery of a newly ready task uses the current ready time
  so earlier-ready tasks compete first;
- every claim round has a bounded `maxItems`;
- after each claim round, a task with remaining backlog is scored at the next
  eligible time, such as `now + positiveMatchDelay`, rather than keeping its
  original first-ready score;
- the scheduler must acquire a bounded batch of eligible task ids from the lane
  score, not repeatedly drain one task while other due tasks remain visible;
- due scheduled retry candidates must not be hidden by a large ready backlog.

This is not a guarantee that small tasks have equal latency under a large
backlog. It is only the mechanism-level guarantee that score-visible eligible
work and due retry are not permanently suppressed by one large task. Expired
claims remain recoverable through the separate repair loop, not through task
score fairness.

### Scheduling-round algorithm

After a task becomes due, scheduling produces a bounded round result:

```text
matchedWorkerCount
admittedWorkerCount
claimedItemCount
readyRemaining
dueScheduledRetryCount
roundOutcome
```

`roundOutcome` is one of:

```text
CLAIMED
EMPTY_MATCH
CONTENDED
NO_READY
PARKED
TERMINAL
```

The score algorithm then applies these rules:

```text
if TERMINAL:
  score = retained closed marker

if PARKED:
  score = SCHEDULER_HOLD_FLOOR or another owner-approved future hold score

if EMPTY_MATCH:
  scoreRuntime.emptyMatchStreak = min(streak + 1, emptyMatchMaxStreak)
  delay = min(emptyMatchMaxDelayMillis,
              emptyMatchBaseDelayMillis * 2^(streak - 1))
  score = now + delay

if CONTENDED:
  scoreRuntime.emptyMatchStreak = 0
  score = now + contentionRecheckDelayMillis

if CLAIMED and readyRemaining:
  scoreRuntime.emptyMatchStreak = 0
  delay = healthyMatchRecheckDelayMillis
          when matchedWorkerCount >= healthyMatchWorkerThreshold
          else scarceMatchRecheckDelayMillis
  score = now + delay

if CLAIMED and no readyRemaining and no scheduled retry candidate:
  scoreRuntime.emptyMatchStreak = 0
  remove score

if CLAIMED and no readyRemaining and scheduled retry candidate exists:
  scoreRuntime.emptyMatchStreak = 0
  score = earliest scheduled retry candidate

if NO_READY and scheduled retry candidate exists:
  score = earliest scheduled retry candidate

if NO_READY and no scheduled retry candidate:
  remove score
```

The empty-match path is the deliberate anti-spin penalty. A task with backlog
but no matching worker is not scanned in a tight loop; it receives a future
recheck score. A task with many matching workers gets a short or zero recheck
delay so it can drain quickly. A task with only scarce matches receives a small
positive delay to avoid monopolizing the lane.

Optional claim timeout is resolved from the same scheduling evidence at claim
time and then stored in `RuntimeItemState.claimPolicy.claimTimeoutMillis`:

```text
matchedWorkerCount >= healthyMatchWorkerThreshold
  claimTimeout = scorePolicy.claimTimeoutMillis.healthyMatch

0 < matchedWorkerCount < healthyMatchWorkerThreshold
  claimTimeout = scorePolicy.claimTimeoutMillis.scarceMatch

fallback
  claimTimeout = scorePolicy.claimTimeoutMillis.default
```

No worker match creates no current claim. It only writes an empty-match recheck
score. The active claim timeout and the task-lane recheck delay are related by
the same policy, but they are separate runtime facts.

## State Machine

### Task lane state

Task lane state is derived from `task:score` plus `TaskRuntimeMeta`.

```text
NOT_INDEXED
  no score member; score-band gives no business interpretation. This is not
  terminal proof. Current claims may still exist in task:{taskId}:rt and are
  repaired separately

ELIGIBLE
  score is in eligible window; task may be acquired for claim

FUTURE
  score is a future timestamp; task-level retry backoff, empty-match recheck,
  contention recheck, scheduled retry, or long scheduler hold is not due yet

CLOSED
  score is a retained negative marker; task is terminal, closed, or discarded

LIVE_NON_SCHEDULABLE
  score is a non-negative enum-like code below TIME_SCORE_FLOOR; task is live
  but not schedulable by the hot path
```

The transition model is deliberately small:

```text
event mutates local facts
  -> rewriteScore(taskId)
  -> derive NOT_INDEXED / ELIGIBLE / FUTURE / LIVE_NON_SCHEDULABLE / CLOSED
     from the new score
```

Events do not hand-code every state-to-state path. They update only the fact
they own:

| Event | Fact mutation |
| --- | --- |
| append | push `ReadyItemFrame` into ready LIST |
| claim | move frames from ready LIST to sparse runtime HASH |
| result success | remove sparse runtime state and write final result row |
| retryable result or expiry | remove sparse runtime state, then either push retry frame to ready LIST (`FAST_READY`) or write scheduled retry lane (`DUE_TIME`) |
| empty scheduling match | increment bounded `scoreRuntime.emptyMatchStreak` |
| positive scheduling match | reset `scoreRuntime.emptyMatchStreak` |
| pause or block | set runtime gate to parked |
| resume or unblock | clear runtime gate and validate shell |
| terminal or discard | write terminal fence and retained closed score, then delete task-local runtime keys and meta rows |

Then `rewriteScore` maps the current facts back to one of the lane states:

| Facts after mutation | Derived lane state |
| --- | --- |
| no score member | `NOT_INDEXED` |
| score in eligible window | `ELIGIBLE` |
| score is a future timestamp | `FUTURE` |
| score is a retained negative marker | `CLOSED` |
| score is non-negative and below `TIME_SCORE_FLOOR` | `LIVE_NON_SCHEDULABLE` |

Reason-specific timings are score candidates, not states:

| Reason | Candidate |
| --- | --- |
| dispatchable ready backlog | `now + positiveMatchDelay` |
| retry backoff | `retryVisibleAtMillis` |
| scheduled retry due time | earliest `item-score` member score |
| no matching worker | `now + emptyMatchDelay` |
| worker contention | `now + contentionRecheckDelayMillis` |

The lane state may therefore change in several valid ways from the same event.
For example, a claim can leave the task `ELIGIBLE` when ready backlog remains
and delay is zero, move it to `FUTURE` when a scheduled retry or recheck
candidate remains, or remove it to `NOT_INDEXED` when no score-visible work
remains. Current claims can still exist after score removal; claim timeout repair
observes them from `task:{taskId}:rt`.

The implementation may store a small `scoreRuntime.emptyMatchStreak` to make
empty-match penalty progressive, but it must not store a separate next due
timestamp in metadata. The next due timestamp remains the ZSET score.

### Item state

Ready items do not have sparse active runtime state. They are LIST entries.
Scheduled retry entries have passive visibility state in `item-score` plus
`item`, but they are not current active claims.

```text
READY_BACKLOG
  raw item or retry frame is inside task:{taskId}:ready LIST

SCHEDULED_RETRY
  retry frame is inside task:{taskId}:item and indexed by
  task:{taskId}:item-score for message-level next scheduling time

CLAIMED
  item was moved from ready LIST or scheduled retry lane into task:{taskId}:rt

FINAL
  item left runtime work ownership; optional short-retained result row exists
```

Transitions:

```text
append
  none -> READY_BACKLOG

claim
  READY_BACKLOG -> CLAIMED

dispatch submit failure before transport accepts handoff
  CLAIMED -> READY_BACKLOG

worker success
  CLAIMED -> FINAL

worker failure, retry budget remains, no delay
  CLAIMED -> READY_BACKLOG

worker failure, retry budget remains, delayed
  FAST_READY: CLAIMED -> READY_BACKLOG, with task lane score set to retry visible time
  DUE_TIME: CLAIMED -> SCHEDULED_RETRY, with item-score set to nextSchedulableAtMillis

scheduled retry due
  SCHEDULED_RETRY -> READY_BACKLOG or CLAIMED through the normal scheduling/claim path

worker failure, retry exhausted
  CLAIMED -> FINAL

claim timeout repair, retry budget remains
  FAST_READY: CLAIMED -> READY_BACKLOG, with task lane score set to now or retry visible time
  DUE_TIME: CLAIMED -> SCHEDULED_RETRY, with item-score set to nextSchedulableAtMillis

claim timeout repair, retry exhausted
  CLAIMED -> FINAL

task terminal/discard
  READY_BACKLOG/SCHEDULED_RETRY/CLAIMED -> removed
```

## Operation Flows

### Append

Input:

```text
taskId
laneBucketId
opaque payload
```

Mutation:

```text
messageId = generate logical id
RPUSH task:{taskId}:ready ReadyItemFrame(messageId, payload)
```

Rules:

- append returns `messageId` to the caller when API/RPC-style result lookup is
  needed;
- no sparse runtime HASH field is created;
- no item-level score is created;
- append does not rewrite the task lane score and does not request a generic
  score refresh;
- append should not rewrite `TaskRuntimeMeta` except for first-time runtime
  initialization; task policy changes own metadata updates;
- scheduler/matching rounds own live task score rewrite after a score-visible
  task is acquired. Direct lifecycle events own explicit score changes such as
  approve, pause, resume, close, or discard;
- submit is at-least-once by default. If Redis commits the append but the
  response is lost, a caller retry without a stable idempotency key may append
  a second logical item;
- API-level non-duplicate append requires caller-supplied idempotency key and
  bounded dedupe. The first runtime slice should still prefer
  runtime-generated `messageId` as the item identity;
- task max length/backpressure should be enforced before or during append.

### Claim

Input:

```text
laneBucketId
taskId
selected worker handles
expectedRuntimeEpoch
matched/admitted worker evidence
claimExpiresAtMillis?
maxItems
```

Mutation must be atomic for each claimed batch:

```text
validate task score is still actionable
read TaskRuntimeMeta for current matching and retry policy
validate runtimeGate is open and runtimeEpoch == expectedRuntimeEpoch
validate selected worker admission evidence belongs to the selected worker
validate ready LIST is non-empty or scheduled retry lane has due entries
resolve optional claim timeout from TaskRuntimeMeta.scorePolicy and scheduling-round evidence
collect claim source frames up to min(claim count, maxItems):
  if retryMode=DUE_TIME:
    read due messageIds from task:{taskId}:item-score where score <= now
    HGET task:{taskId}:item messageId for each due retry frame
    remove claimed messageIds from task:{taskId}:item-score and task:{taskId}:item
  LPOP task:{taskId}:ready for immediate ready frames
for each source frame:
  if entry is ready frame:
    validate messageId
    HSET task:{taskId}:rt messageId RuntimeItemState(CLAIMED, retryCount=0, runtimeEpoch, selected worker, claimPolicy=current policy versions and optional timeout)
  if entry is retry frame:
    validate messageId and retryCount
    HSET task:{taskId}:rt messageId RuntimeItemState(CLAIMED, retryCount=entry.retryCount, runtimeEpoch, selected worker, claimPolicy=current policy versions and optional timeout)
ZADD task:active:{laneBucketId} coarseRepairCandidateAtMillis taskId
rewrite task score through the score rewrite rule:
  now + positiveMatchDelay, if ready LIST still non-empty after this bounded round
  earliest item-score nextSchedulableAtMillis, if scheduled retry lane still has delayed entries and no earlier ready candidate exists
  remove, if no ready or scheduled retry candidate remains
```

Claim returns deliver seed item values. For initial
backlog entries, the worker payload comes from the raw LIST entry. For retry
frames, the worker payload comes from either the ready LIST (`FAST_READY`) or
the scheduled retry lane (`DUE_TIME`). The scheduled retry lane must not
dispatch directly to an adapter; it only supplies due frames to the same
scheduling, worker admission, claim, and deliver-seed path.
When ready LIST backlog is large, the claim source policy must give due
scheduled retry frames priority or a bounded quota so message-level retry due
time is not starved by fresh backlog.

Worker admission is a precondition for claim. If admission fails, claim must
not consume ready or scheduled retry frames. If claim fails after admission was
acquired, the caller must compensate that admission. `RuntimeItemState` stores
the selected worker and optional `claimExpiresAtMillis`; the deliver seed
passes the same evidence through transport, but the current hash row remains
truth.

### Result apply

Input:

```text
taskId
messageId
expectedRuntimeEpoch
workerId
claimExpiresAtMillis?
success/failure/expired
retryable
output or error payload
```

Mutation must be atomic:

```text
HGET task:{taskId}:rt messageId
if no runtime item:
  return existing FinalResult if present, otherwise reject as unknown/stale
reject if runtimeEpoch mismatches expectedRuntimeEpoch
reject stale result if current workerId mismatches
if current claim has claimExpiresAtMillis:
  reject stale result if input claimExpiresAtMillis mismatches
  reject expired result if policy says now > claimExpiresAtMillis must not converge

if success:
  HDEL task:{taskId}:rt messageId
  ZREM task:{taskId}:item-score messageId
  HDEL task:{taskId}:item messageId
  write FinalResult

if failure and retry remains:
  increment retryCount in RuntimeItemState
  resolve next retry eligibility and delay from current TaskRuntimeMeta.retryPolicy
  if retryMode=FAST_READY:
    RPUSH task:{taskId}:ready RetryFrame(messageId, retryCount, payload)
  if retryMode=DUE_TIME:
    HSET task:{taskId}:item messageId RetryFrame(messageId, retryCount, payload)
    ZADD task:{taskId}:item-score nextSchedulableAtMillis messageId
  HDEL task:{taskId}:rt messageId
  move retry state to ready backlog or scheduled retry state; task score is not
  refreshed by result apply except for owner-authorized terminal transition

if failure and retry exhausted:
  HDEL task:{taskId}:rt messageId
  ZREM task:{taskId}:item-score messageId
  HDEL task:{taskId}:item messageId
  write FinalResult

leave TaskRuntimeMeta unchanged unless a separate task policy or runtime-gate
update owns that change
remove taskId from task:active:{laneBucketId} when task:{taskId}:rt is empty
do not rewrite live task score from result apply; direct terminal handoff may
write retained closed score through the lifecycle/score owner
```

Duplicate and late callbacks are handled by runtime result reads or recent-final
receipts. They must not scan server review rows.

Retry budget is resolved from current `TaskRuntimeMeta.retryPolicy` unless a
future policy explicitly chooses claim-snapshot retry semantics. Raw ready
items do not own `maxRetryCount`. The current claim's `claimPolicy` proves the
claim policy and optional timeout semantics, but it does not force the next
retry to use the old policy.

Final result rows are short-retained idempotency and lookup evidence. They are
not the durable user result ledger. Runtime finality means the work item left
runtime ownership; long-term user result retention must be provided by another
owner if required.

### Due processing

When a task score becomes due, the owner does not know whether the reason is
ready backlog, scheduled retry, task-level retry backoff, or recheck until it
validates task-local state.

Processing order:

```text
1. Validate task shell, runtime gate, and task-local keys.

2. If retryMode=DUE_TIME:
     read bounded due messageIds from task:{taskId}:item-score where score <= now.
     If due entries exist, load their frames from task:{taskId}:item and make
     them available to the same scheduling/claim path.
     If no due entries exist and ready LIST is empty, keep the next
     item-score timestamp as the task lane score and stop.

3. If task-level retry backoff or empty-match penalty is still active:
     keep the future score selected by policy and stop.

4. If ready LIST is non-empty or due scheduled retry frames exist, and task
   shell is dispatchable:
     run a scheduling round, classify CLAIMED / EMPTY_MATCH / CONTENDED, and
     claim source frames only when a worker was selected.

5. Rewrite or remove task score through the rewrite rule.
```

The expensive part is bounded by the due scheduled retry read and the claim
batch size, not raw backlog size.

### Claim timeout repair

Active claim timeout is repaired eventually from `task:{taskId}:rt`; task lane
score does not guarantee exact timeout wakeup.

`RuntimeItemState.claimExpiresAtMillis` is the optional deadline after which a
repair loop may expire the current claim:

```text
read bounded task ids from task:active:{laneBucketId}
for each taskId:
  scan task:{taskId}:rt within a bounded repair budget
  for each RuntimeItemState where claimExpiresAtMillis <= now:
    expire through the same result apply / retry / finality path
    validate runtimeEpoch and current active worker identity before mutating
    remove or update rt only inside the result/retry/finality atomic boundary
  if task:{taskId}:rt is empty:
    remove taskId from task:active:{laneBucketId}
  else:
    keep or update task:active:{laneBucketId} with a coarse repair score
  rewrite task score only for ready/scheduled retry/recheck candidates
```

The repair loop may be periodic or opportunistic. It does not need to run on
every task-score wake. Missing the exact timeout moment is acceptable; losing
the message is not. If a late result arrives before repair moves the item, the
current active row may still converge the item. If repair already moved the
item to retry backlog or a final row, there is no matching active row for the
old result.

Do not add a claim-expiry ZSET in the first slice. If a later task type needs
near-exact timeout wakeup, add a separate active-claim expiry index as an
explicit policy upgrade; do not overload task lane score for that purpose.

### Pause, block, resume

Pause/block:

```text
ZADD task:score:{laneBucketId} SCHEDULER_HOLD_FLOOR taskId
HSET task:meta:{laneBucketId} taskId runtimeGate=PARKED, runtimeEpoch=runtimeEpoch+1
```

Do not rewrite every raw ready item or runtime item. Current claims remain in
`task:{taskId}:rt` and may still finish. Retry frames created while parked stay
in the ready LIST or scheduled retry lane according to `retryMode`, but the
future hold score prevents new claims until resume or until an explicit policy
chooses a different due time.

Resume/unblock:

```text
validate task shell
inspect ready LIST, scheduled retry lane, and TaskRuntimeMeta
advance or validate runtimeEpoch
rewrite score to ELIGIBLE/FUTURE or remove
```

After resume, both newly appended items and retry frames are claimed under the
current `TaskRuntimeMeta`.

### Terminal and discard

Terminal task cleanup is precise by `taskId`:

```text
HSET task:meta:{laneBucketId} taskId runtimeGate=TERMINAL, runtimeEpoch=runtimeEpoch+1
ZADD task:score:{laneBucketId} CLOSED_* taskId
ZREM task:active:{laneBucketId} taskId
DEL task:{taskId}:ready
DEL task:{taskId}:item-score
DEL task:{taskId}:item
DEL task:{taskId}:rt
DEL task:{taskId}:result
HDEL task:meta:{laneBucketId} taskId
```

The terminal fence is the hard boundary. It must be visible to claim, result,
and repair atomic mutations before physical cleanup is attempted. Physical key
deletion is allowed to be best-effort delayed, but no new claim may pass the
terminal fence. The retained closed score is scheduler-visible diagnostic
evidence, not the lifecycle authority. This cleanup does not scan the namespace
and does not require one key per item.

## Atomicity Boundaries

Use Lua, Redis transactions, or an equivalent compare-and-swap mechanism only
where the state transition crosses multiple Redis values.

Required atomic boundaries:

```text
append:
  optional idempotency/backlog guard + ready LIST push
  optional first-time TaskRuntimeMeta init or dirty marker
  no task score rewrite in the append mutation

claim:
  validate task score, runtime gate, runtimeEpoch, and selected worker admission
  ready LIST pop and/or due item-score claim
  runtime HASH write
  active task registry update
  no task score refresh

result apply:
  runtime HASH runtimeEpoch, current worker, and optional claim-expiry validation
  retry/final mutation
  item-score/item cleanup
  active task registry maintenance
  no live task score refresh

claim timeout repair mutation:
  validate runtimeEpoch and current active worker identity
  move expired active runtime item through the same retry/final mutation path
  active task registry maintenance
  no live task score refresh

pause/block/resume:
  runtime gate + runtimeEpoch + score update

discard/terminal:
  terminal fence + runtimeEpoch advance before physical key deletion
  task-local key deletion including scheduled retry lane
  retained closed score + active/meta removal
```

Do not add a distributed lock around task scheduling by default. The runtime
state transition itself should be the concurrency control.

## Why Not Redis Stream

Redis Stream is not the first choice for this task runtime lane because:

- stream pending-entry state becomes a second claim owner;
- retry and expiry would need to reconcile stream PEL with task-level backoff
  and active claim state;
- trimming and replay semantics are more history-oriented than this hot path
  needs;
- task results are short-retained runtime state, not a durable event ledger;
- raw LIST plus current-claim HASH keeps failure handling explicit.

A stream may still be useful later for trace or repair evidence, but not as the
current task item runtime truth.

## Guardrails

- Do not create one Redis key per work item.
- Do not create runtime HASH fields for every ready item at append time.
- Do not store `Task.status` as Redis truth.
- Do not let `eventCode` select workers.
- Do not make transport result queues own retry or finality.
- Do not use server review rows to accept or reject runtime callbacks.
- Do not rely on shell validation alone to block terminal/discard races; Redis
  runtime fence validation is required in claim, result, and repair mutations.
- Do not claim work unless the runtime gate is open, `runtimeEpoch` matches, and
  selected worker admission evidence is valid.
- Do not leave current claimed items discoverable only by caller-known `taskId`.
  A task with non-empty `rt` must be represented in the active task registry.
- Do not use task-local `item-score` for normal ready backlog. It is allowed
  only for `retryMode=DUE_TIME` delayed/scheduled retry visibility.
- Do not let the scheduled retry lane dispatch directly to adapters; due retry
  frames must enter the same scheduling/claim path as normal ready work.
- Do not promote best-effort timing into a hard runtime guarantee without a
  task policy that explicitly opts into the higher-cost mechanism.
- Do not put item counters or due timestamps in `TaskRuntimeMeta` as scheduling
  truth.
- Do not keep retryable ready items in the runtime HASH after requeue.
- Do not generate `messageId` during claim; identity is created when append
  accepts the item.
- Do not make task lane score responsible for precise active claim timeout.
  Claim timeout is repaired from `task:{taskId}:rt` on a best-effort basis.
- Do not use score absence as terminal proof; terminal, closed, or discarded
  tasks should write a retained closed marker before cleanup/retention removes
  that marker.
- Do not add an active claim expiry ZSET unless a later policy explicitly
  requires near-exact timeout wakeup.
- Do not let retry backoff or empty-match penalty hide an earlier ready or
  scheduled retry candidate; task score must wake at the earliest score-visible
  candidate time.
- Do not keep a task's original `firstReadyAt` score after a claim round when
  backlog remains; rewrite it to the next eligible time.
- Do not let a scheduler loop repeatedly drain one task while other due task
  ids remain visible in the same lane score batch.
- Do not claim zero Redis-node-loss unless the Redis durability configuration
  actually provides that acknowledgement contract.
- Do not present append as exactly-once unless the API accepts a stable
  caller-supplied idempotency key and enforces bounded dedupe.
- Do not make result rows long-term archive truth in this shape.
- Do not claim a no-loss runtime profile unless the profile fails closed or
  explicitly reports degraded mode when Redis durability is insufficient.

## Acceptance Checks

A first implementation should prove:

- default task runtime guarantees durable convergence, not real-time precision;
- no-loss claims are scoped to accepted runtime state and require the configured
  Redis durability profile to acknowledge the write;
- append without an idempotency key is at-least-once, while API-level
  non-duplicate append requires caller-supplied idempotency evidence;
- append assigns and returns `messageId` before the item can be claimed;
- appending many ready items creates no per-item Redis keys;
- raw ready backlog can hold large batches as LIST entries;
- claim reuses append-generated `messageId` and creates sparse runtime state
  only for claimed items;
- claim rejects stale runtime epochs, closed runtime gates, and missing or
  mismatched worker admission evidence before consuming ready or due frames;
- claimed work adds the task to the active task registry, and result/retry/final
  convergence removes it when `rt` is empty;
- result success removes sparse runtime state and writes one task-local result
  row;
- result and repair reject stale `runtimeEpoch`, missing current active rows, or
  mismatched active worker identity so late replay cannot overwrite a newer
  active row or terminal fence;
- task-local result rows are bounded-retention lookup/idempotency evidence, not
  a durable user result ledger;
- `FAST_READY` retry returns a retry frame to the ready LIST and deletes active
  runtime state without creating task-local `item-score` entries;
- `DUE_TIME` retry writes one `item-score` member and one `item` hash field for
  the delayed retry message, then deletes both on success or final failure;
- due `DUE_TIME` retry frames enter the same scheduling/claim path as
  ready LIST frames;
- empty-match scheduling rounds write a future recheck score and increment only
  bounded score-runtime penalty state;
- score rewrite picks the earliest ready/scheduled-retry/empty-match candidate
  so due retry visibility is not starved;
- claim rounds are bounded by `maxItems`, and a task with remaining backlog is
  rewritten to a next eligible score after each round;
- the scheduler can acquire a bounded batch of eligible tasks so one large
  backlog does not permanently hide other due tasks;
- claim timeout repair discovers active tasks through the active task registry,
  scans `task:{taskId}:rt`, and eventually expires claimed messages without
  relying on task score for exact timeout wakeup;
- pause/block holds the task with a far-future score without rewriting items;
- terminal/discard writes a runtime fence and retained closed score before
  physical cleanup, then deletes task-local runtime keys without namespace scan;
- memory and Redis implementations share the same kernel runtime contract
  behavior.
