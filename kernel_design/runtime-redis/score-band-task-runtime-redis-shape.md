# Score-Band Task Runtime Redis Shape

Status: superseded Redis shape draft. This document is not active execution
input, current Java implementation truth, or an implementation roadmap.

This document describes a compact Redis shape for task work runtime. It is a
directional design note for the future kernel core and executable-spec work.
It still contains older `epochSecond` / `PAUSE_EPOCH_SECOND` task score-band
vocabulary. Do not implement task score behavior from those terms.

The current task score design lives in:

- [Task Score-Band Scheduling](../scheduling/task-score-band-scheduling.md)
- [task_score.py](../py_example/kernel/task_score.py)
- [task_score_band_zset.py](../py_example/runtime_redis/task_score_band_zset.py)

The active model uses public `timeMillis`, internal `timeSlot =
floor(timeMillis / SLOT_MILLIS)`, score tags for lifecycle order, and suffix as
owner-local budget or state code.

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

Task score is not a Redis mutation lock for adjacent owner keys. Descriptor or
policy metadata update, item append, work/result mutation, projection, and trace
write their own keys without reading, leasing, or rewriting task score.
Assignment-dispatch is the only routine writer of acquired active scores;
creation and explicit lifecycle commands use only their declared initialization
or control-transition primitives.

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
- no-worker-match recheck, no-work recheck, retry backoff, and terminal
  cleanup timing are runtime convergence targets, not financial-grade timing
  guarantees.

External event emission is high-cost and disabled by default for routine
runtime activity:

```text
append
routine result notification
transport callback
worker availability change
timer callback
trace / projection update
```

These observations must not emit wakeups or dirty hints as a default mechanism.
If a later executable spec introduces event emission, it must be limited to key
owner state changes, bounded, fast-fail, and evidence-only. Emit failure must
not block or roll back the owner state transition. Correctness and automatic
liveness must still come from score-state recheck, task-local current truth
validation, bounded repair, or explicit policy closure. Human-required states
such as pending approval or manual unresolved review are the explicit
exception: they intentionally wait for an authoritative human command, and that
command still validates current state before mutating truth.

This Redis shape is score-band scheduled, not event triggered. The primary
runtime loop is:

```text
task:score due
  -> task-local state validation
  -> ready / scheduled retry / no-work / rt inspection
  -> bounded claim, retry, finality, repair, or next score
```

Event emission can only accelerate that existing scheduling loop. It must not
hold scheduling backlog or become the only dispatch trigger:

```text
allowed event delivered
  -> optional early owner-local recheck

allowed event dropped / coalesced / delayed
  -> task:score scan, task-local recheck, and repair still converge

event queue overload
  -> drop or coalesce events
  -> never backpressure append/result/claim owner mutations
```

The Redis scheduling base is the score/state structure itself: `task:score`,
task-local ready/scheduled retry structures, `task:{taskId}:rt`, and bounded
repair. Do not add a per-item or per-result event queue to make scheduling
correct.

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
taskName
Task.status
Task.intakeStatus
Task.contract
sharedConfig
terminalReason
```

Redis may cache a runtime gate or next-action hint, but assignment-dispatch
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

`laneBucketId` is an internal task-runtime partition. The first slice uses a
deterministic low-cardinality bucket function:

```text
laneBucketId = taskRuntimeBucket(taskId)
```

It is not product metadata, a worker bucket, or a per-item shard. Changing the
bucket function is a runtime placement decision, not a Task model change.

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
  "laneBucketId": "lane-0",
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
    "noWorkerMatchBaseDelayMillis": 1000,
    "noWorkerMatchMaxDelayMillis": 30000,
    "noWorkRecheckDelayMillis": 300,
    "contentionRecheckDelayMillis": 500,
    "initialSameBandBudget": {
      "PRE_DISPATCH_VISIBLE": 20,
      "RUNNING_VISIBLE": 10
    },
    "claimTimeoutMillis": {
      "healthyMatch": 10000,
      "scarceMatch": 30000,
      "default": 30000
    },
    "policyVersion": 3
  },
  "scoreRuntime": {},
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
  decides recheck delay, initial same-band budget, and optional claim timeout
  from scheduling-round evidence, but it does not store the next due time.
- `scoreRuntime` is optional small dynamic score-algorithm state such as bounded
  penalty counters. Temporary hold, next recheck time, and remaining same-band
  budget are encoded in the score, not duplicated as runtime timestamps.
- `runtimeGate` is an optional runtime fence such as `OPEN`; task
  dispatch visibility is still derived from task score state, not from
  `Task.status`.
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

If the task is already waiting on a future `RUNNING_VISIBLE` no-work recheck
score, append does not move that score or request a wakeup. The item may be
discovered by a normal `RUNNING_VISIBLE` scan when that score is acquired. If an
exhausted no-work round has already closed the task, append eligibility is an
ingress/product decision. An item accepted under stale ingress evidence is
terminal residue with no consumption guarantee and cannot retroactively reopen
the old score.

Activation fact changes follow the same rule. They update the owning fact, but
they do not rewrite the task score directly. A normal `PRE_DISPATCH_VISIBLE` scan
reads those facts and decides whether to keep `PRE_DISPATCH_VISIBLE`, promote the task
to `RUNNING_VISIBLE`, or hold it after the same-band budget is exhausted.

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
non-terminal score = tag * TAG_FACTOR + epochSecond * SUFFIX_FACTOR + suffix

tag
  positive mutable band segment; tag 0 is deliberately unused

epochSecond
  second-granularity scheduling / freshness coordinate
  RUNNING_VISIBLE / PRE_DISPATCH_VISIBLE interpret it as next scheduling time
  PRE_REVIEW interprets it as owner mutation freshness time

suffix
  two-digit same-band remaining schedule budget
  00 means same-band budget exhausted
  01..99 means remaining same-band scheduling rewrites
  PRE_REVIEW interprets it as owner-defined review state code
  not work-item retry truth
```

Core directional constraints:

```text
tag decides lifecycle direction
epochSecond decides same-band hold / recheck / freshness direction
suffix decides same-band budget / tie-break / owner-local code
write-time stored-score/CAS prevents stale overwrite
terminal close is a high-priority positive-to-negative write
score hold release uses an exact observed-score fence
transition direction rule prevents lifecycle regression

PRE_REVIEW(3) -> PRE_DISPATCH_VISIBLE(2) -> RUNNING_VISIBLE(1) -> TERMINAL(<0)
```

Lifecycle progress moves toward lower tag / terminal score. Scheduling
suppression, retry, and hold move inside the same tag by writing a later
`epochSecond`; the common case is a same-band epoch/suffix rewrite. The target
epoch is absolute. If an owner wants `now + delay`, that owner computes the
absolute epoch before calling the kernel. Kernel-internal writes compile into
`minExpectedScore`, `maxExpectedScore`, and `targetScoreBase`; those values are
not public caller inputs. Lua only verifies that the stored score is in range
and then writes `targetScoreBase + suffix`.
Suffix delta is only for scheduling-band budget changes.
`PRE_REVIEW` suffix is a review-state code and must be changed only by an
owner-validated rewrite with explicit target suffix.
Score range coordinates are trusted kernel-internal protocol values. If a
caller can pass them directly, the kernel API boundary is already broken.
Release/resume is the only path that may lower `epochSecond`, and only with
exact `observedHoldScore`.
`PRE_DISPATCH_VISIBLE` is optional: a validated owner transition may move directly
from `PRE_REVIEW` to `RUNNING_VISIBLE`. The kernel only rejects movement from a
lower tag back to a higher tag.

Recommended first-slice constants:

```text
RUNNING_VISIBLE_TAG  = 1
PRE_DISPATCH_VISIBLE_TAG   = 2
PRE_REVIEW_TAG       = 3
SUFFIX_FACTOR        = 100
EPOCH_FACTOR         = 10_000_000_000
MAX_EPOCH_SECOND     = 9_999_999_999
PAUSE_EPOCH_SECOND   = MAX_EPOCH_SECOND
TAG_FACTOR           = EPOCH_FACTOR * SUFFIX_FACTOR
TERMINAL score       = -closedScoreKey
closedScoreKey       = closedEpochSecond * SUFFIX_FACTOR + closedSuffix
closedScoreKey       > 10
```

Use `score(TAG, epochSecond, suffix)` below as shorthand for the positive
formula. With a 10-digit `epochSecond` coordinate and two-digit suffix, the
encoded value remains well under Redis sorted-set double exact-integer limits.
If a later policy changes the coordinate width or suffix width, exact score
representation must be re-checked before implementation.

```text
score absent
  undefined to score-band; not service discovery and not terminal proof

PRE_REVIEW
  score = score(PRE_REVIEW_TAG, ownerMutationEpochSecond, reviewStateCode)
  create / prepare / pending approval; positive mutable but not acquired
  same-band owner mutation requires target epochSecond > current epochSecond;
  when same-second owner mutations occur, use
  max(nowEpochSecond, currentEpochSecond + 1);
  suffix transition legality belongs to the review owner

RUNNING_VISIBLE
  score = score(RUNNING_VISIBLE_TAG, nextDispatchEpochSecond, suffix)
  dispatch-capable after owner validation and work hash claim; no-work,
  no-worker, contention, retry, and hold rechecks remain in this same band

PRE_DISPATCH_VISIBLE
  score = score(PRE_DISPATCH_VISIBLE_TAG, nextReadyRecheckEpochSecond, suffix)
  approved but not yet running; acquired for pre-running open-condition check
  still not open with suffix > 00 rewrites the same band with suffix-1;
  still not open with suffix == 00 writes a same-band pause/hold score

TERMINAL
  score = -closedScoreKey
  negative immutable final marker; final reason belongs to result/meta/trace,
  not score
```

Active band order is consumption-first:

```text
RUNNING_VISIBLE < PRE_DISPATCH_VISIBLE < PRE_REVIEW
```

Redis range scans use the tag segment directly. The hot path should not decode
every candidate to discover its band:

```text
running scan:
  ZRANGEBYSCORE task:score
    score(RUNNING_VISIBLE_TAG, 0, 0)
    score(RUNNING_VISIBLE_TAG, currentEpochSecond, MAX_SUFFIX)
    LIMIT batchSize

pre-dispatch-visible scan:
  ZRANGEBYSCORE task:score
    score(PRE_DISPATCH_VISIBLE_TAG, 0, 0)
    score(PRE_DISPATCH_VISIBLE_TAG, readyScanHorizonEpochSecond, MAX_SUFFIX)
    LIMIT batchSize
```

`PRE_REVIEW` is positive, but assignment-dispatch must not scan its tag. Positive
does not mean schedulable; schedulability comes from the explicit active tag
allow-list.
Any future scan horizon must stay below `PAUSE_EPOCH_SECOND`; hard-pause scores
are not future-prefetch candidates. Hard pause is not an ordinary due-later
delay: while a stored positive score has
`epochSecond == PAUSE_EPOCH_SECOND`, ordinary scheduling and positive lifecycle
rewrites must not consume suffix budget or advance the task to another positive
band. It can leave held mode only through exact observed-score release/resume or
owner terminal finality.

The score is an index. Before claim, the kernel runtime must validate:

- task still exists;
- task shell is not terminal;
- decoded positive score uses a known tag, `0 <= epochSecond <=
  MAX_EPOCH_SECOND`, and `0 <= suffix <= MAX_SUFFIX`;
- task score state is dispatch-eligible;
- `PRE_DISPATCH_VISIBLE` activation conditions are satisfied before entering running;
- future active-band scores are not treated as ordinary immediate
  dispatch unless the assignment-dispatch policy explicitly chose a future
  pre-allocation horizon;
- ready LIST, scheduled retry lane, no-work recheck state, task-level retry
  backoff, or recheck reason still matches the score hint.

### Score rewrite rule

Task lane score is rewritten through one deterministic rule, but only from
score-owner paths:

- direct lifecycle/scheduling commands or owner-validated transitions such as
  approve, activation, pause, resume, close, or discard;
- the assignment-dispatch round after the task has been acquired from the score
  range.

Append intake is not a scheduler-owned mutation. It writes ready backlog truth
and does not emit a generic score refresh.

| Step | Condition | Score action |
| --- | --- | --- |
| 1 | task terminal, rejected, no-work budget exhausted, cancelled, or discarded | write negative `closedScoreKey` score |
| 2 | task created but not approved | write `score(PRE_REVIEW_TAG, initialOwnerMutationEpochSecond, reviewStateCode)` |
| 2a | owner-validated pre-review mutation | write `score(PRE_REVIEW_TAG, nextOwnerMutationEpochSecond, reviewStateCode)` where `nextOwnerMutationEpochSecond > currentOwnerMutationEpochSecond`; suffix transition legality belongs to the review owner |
| 2b | owner-validated pre-review direct activation | write `score(RUNNING_VISIBLE_TAG, nextDispatchEpochSecond, initialBudget(RUNNING_VISIBLE))` |
| 3 | task approved and waiting for pre-running open facts | write `score(PRE_DISPATCH_VISIBLE_TAG, nextReadyRecheckEpochSecond, initialBudget(PRE_DISPATCH_VISIBLE))` |
| 3a | `PRE_DISPATCH_VISIBLE` is scanned and pre-running open conditions are still false with `suffix > 00` | write next ready recheck score with `suffix - 1` |
| 3b | `PRE_DISPATCH_VISIBLE` is scanned and pre-running open conditions are still false with `suffix == 00` | write same-band pause/hold score |
| 4 | active task is explicitly held | rewrite the same active band with future `epochSecond`; hard pause uses `PAUSE_EPOCH_SECOND` |
| 5 | active task has dispatchable work or a due retry candidate | write `score(RUNNING_VISIBLE_TAG, nextDispatchEpochSecond, suffix)` |
| 6a | active task has no current work with `suffix > 00` | write `score(RUNNING_VISIBLE_TAG, nextNoWorkRecheckEpochSecond, suffix - 1)` |
| 6b | active task has no current work with `suffix == 00` | write negative `closedScoreKey` score |

`RUNNING_VISIBLE` score candidates are:

| Running condition | Candidate |
| --- | --- |
| ready LIST has dispatchable work | `score(RUNNING_VISIBLE_TAG, nextDispatchEpochSecond, suffix)` |
| `retryMode=DUE_TIME` and scheduled retry lane has due entries | `score(RUNNING_VISIBLE_TAG, nextDispatchEpochSecond, suffix)` for the same scheduling round |
| task-level retry backoff blocks fresh wakeup | `score(RUNNING_VISIBLE_TAG, retryVisibleEpochSecond, suffix)` |
| last scheduling round found no work | `score(RUNNING_VISIBLE_TAG, nextNoWorkRecheckEpochSecond, suffix)` |
| last scheduling round found no eligible worker | `score(RUNNING_VISIBLE_TAG, workerRecheckEpochSecond, suffix)` |
| last scheduling round was contended | `score(RUNNING_VISIBLE_TAG, contentionRecheckEpochSecond, suffix)` |
| pause/block/future restriction while running-visible | `score(RUNNING_VISIBLE_TAG, futureEpochSecond, suffix)` |

`PRE_DISPATCH_VISIBLE` score candidates are:

| Ready condition | Candidate |
| --- | --- |
| approval / pre-running activation recheck | `score(PRE_DISPATCH_VISIBLE_TAG, nextReadyRecheckEpochSecond, initialBudget(PRE_DISPATCH_VISIBLE))` |
| pre-running no-op pacing | `score(PRE_DISPATCH_VISIBLE_TAG, nextReadyRecheckEpochSecond, suffix)` |
| approval rejected | negative `closedScoreKey` |

Positive non-terminal writes must write an `epochSecond` later than the stored
score by default. Same-band scheduling rewrites also decrement suffix by one.
Manual release/resume is the only exception: it writes a same-tag release score
derived from `observedHoldScore`, usually with `epochSecond = now`, only when
the stored score still equals `observedHoldScore`, and it copies suffix from
`observedHoldScore`. That exact score match is the stale fence. Same-task
score refresh is intentionally second-granularity;
multiple non-release updates in the same second are rejected, coalesced, or
retried later by the owner.
Release/resume is tag-preserving: `PRE_DISPATCH_VISIBLE(PAUSE_EPOCH_SECOND, suffix)`
releases to `PRE_DISPATCH_VISIBLE(releaseEpochSecond, suffix)`, and
`RUNNING_VISIBLE(PAUSE_EPOCH_SECOND, suffix)` releases to
`RUNNING_VISIBLE(releaseEpochSecond, suffix)`. It must not release a
`PRE_DISPATCH_VISIBLE` hold directly into `RUNNING_VISIBLE`; activation validation
must run after the original band becomes due again.
`PRE_REVIEW` same-band owner transitions are not scheduling rewrites: the review
owner validates the business state and writes a larger owner mutation
`epochSecond`; suffix is an owner-defined review state code.
Cross-band owner transitions initialize suffix from policy; external callers do
not provide it without owner validation. If target suffix is omitted, the score
primitive preserves the stored suffix. Budget reset is a separate
owner-authorized transition, not release/resume.

Immediate ready LIST work still wins inside the running claim source. Scheduled
retry due time is used when the task has no immediate ready frame or when the
due retry frame is the only next ready source. This decides only when the task
wakes. Once the task is awake, claim source policy still needs to prioritize or
quota due scheduled retry frames so they are not starved by a large ready LIST.

Active claim timeout is not a score candidate in this shape. Claimed items stay
recoverable in `task:{taskId}:rt`; timeout is repaired by the best-effort claim
repair loop below. The score is a task scheduling wakeup signal, not the owner
of precise claim-timeout discovery.

### Starvation boundary

The first slice does not implement a full fairness scheduler. Fairness,
priority weights, cross-scope quotas, and round-robin cursors belong to later
`scorePolicy` work.

The mechanism still needs a no-permanent-starvation floor:

- task score is the next eligible time, not a permanent `firstReadyAt`;
- first assignment-dispatch discovery of a newly ready task uses the current ready time
  so earlier-ready tasks compete first;
- every claim round has a bounded `maxItems`;
- after each claim round, a task with remaining backlog is scored at the next
  running-visible time, rather than keeping its original first-ready score;
- assignment-dispatch must acquire a bounded batch of eligible task ids from the
  lane score, not repeatedly drain one task while other due tasks remain visible;
- due scheduled retry candidates must not be hidden by a large ready backlog.

This is not a guarantee that small tasks have equal latency under a large
backlog. It is only the mechanism-level guarantee that score-visible eligible
work and due retry are not permanently suppressed by one large task. Expired
claims remain recoverable through the separate repair loop, not through task
score fairness.

### Scheduling-round algorithm

After a task score is acquired, scheduling produces a bounded round result.
The result is evidence for the score-state rewrite, not an independent task
lifecycle:

```text
scoreState
activationSatisfied?
matchedWorkerCount
admittedWorkerCount
claimedItemCount
readyRemaining
dueScheduledRetryCount
currentSuffix
roundOutcome
```

`roundOutcome` is one of:

```text
ACTIVATION_STILL_WAITING
ACTIVATION_BUDGET_EXHAUSTED
ACTIVATION_READY
WORK_CLAIMED
NO_WORK
WORKER_NO_MATCH
WORKER_CONTENDED
PAUSE_OR_BLOCK
TERMINAL
```

The score algorithm then applies these rules:

```text
if TERMINAL:
  score = -closedScoreKey

if PAUSE_OR_BLOCK:
  score = score(currentActiveTag, futureEpochSecond, suffix)

if scoreState == PRE_DISPATCH_VISIBLE and ACTIVATION_STILL_WAITING and suffix > 00:
  score = score(PRE_DISPATCH_VISIBLE_TAG, nextReadyRecheckEpochSecond, suffix - 1)

if scoreState == PRE_DISPATCH_VISIBLE and ACTIVATION_BUDGET_EXHAUSTED:
  score = score(PRE_DISPATCH_VISIBLE_TAG, holdUntilEpochSecond, 00)

if scoreState == PRE_DISPATCH_VISIBLE and ACTIVATION_READY:
  score = score(RUNNING_VISIBLE_TAG, nowEpochSecond, initialBudget(RUNNING_VISIBLE))

if scoreState == RUNNING_VISIBLE and NO_WORK and suffix > 00:
  nextNoWorkRecheckEpochSecond = nowEpochSecond + scorePolicy.noWorkRecheckDelaySeconds
  score = score(RUNNING_VISIBLE_TAG, nextNoWorkRecheckEpochSecond, suffix - 1)

if scoreState == RUNNING_VISIBLE and NO_WORK and suffix == 00:
  score = -closedScoreKey

if WORKER_NO_MATCH and suffix > 00:
  delay = healthyMatchRecheckDelayMillis
          when matchedWorkerCount >= healthyMatchWorkerThreshold
          else scarceMatchRecheckDelayMillis
  score = score(RUNNING_VISIBLE_TAG, workerRecheckEpochSecond, suffix - 1)

if WORKER_CONTENDED and suffix > 00:
  score = score(RUNNING_VISIBLE_TAG, contentionRecheckEpochSecond, suffix - 1)

if (WORKER_NO_MATCH or WORKER_CONTENDED) and suffix == 00:
  score = score(RUNNING_VISIBLE_TAG, holdUntilEpochSecond, 00)

if WORK_CLAIMED and readyRemaining:
  delay = healthyMatchRecheckDelayMillis
          when matchedWorkerCount >= healthyMatchWorkerThreshold
          else scarceMatchRecheckDelayMillis
  score = score(RUNNING_VISIBLE_TAG, nextDispatchEpochSecond, initialBudget(RUNNING_VISIBLE))

if WORK_CLAIMED and no readyRemaining and scheduled retry candidate exists:
  score = score(RUNNING_VISIBLE_TAG, scheduledRetryEpochSecond, initialBudget(RUNNING_VISIBLE))

if WORK_CLAIMED and no readyRemaining and no scheduled retry candidate:
  nextNoWorkRecheckEpochSecond = nowEpochSecond + scorePolicy.noWorkRecheckDelaySeconds
  score = score(RUNNING_VISIBLE_TAG, nextNoWorkRecheckEpochSecond, initialBudget(RUNNING_VISIBLE))
```

The no-worker path is the deliberate anti-spin penalty for active work. A task
with backlog but no matching worker is not scanned in a tight loop; it remains
`RUNNING_VISIBLE` with a future running score. No-work is the same running band
with a future no-work recheck score and a consumed same-band budget.

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

No worker match creates no current claim. It only writes a no-worker-match
recheck score inside `RUNNING_VISIBLE`. The active claim timeout and the task-lane
recheck delay are related by the same policy, but they are separate runtime
facts.

## State Machine

### Task lane state

Task lane state is derived from `task:score` plus `TaskRuntimeMeta`.

```text
NOT_INDEXED
  no score member; score-band gives no business interpretation. This is not
  terminal proof. Current claims may still exist in task:{taskId}:rt and are
  repaired separately

PRE_REVIEW
  score is in the pre-review tag range; task is created/preparing/unapproved,
  positive mutable, and not acquired by assignment-dispatch

PRE_DISPATCH_VISIBLE
  score is in the pre-dispatch-visible tag range; task is approved and may be
  acquired only for activation-condition evaluation

RUNNING_VISIBLE
  score is in the running range; task may be acquired for assignment-dispatch
  only after owner validation, worker admission, final work claim, or no-work
  recheck

TERMINAL
  negative terminal score stores immutable closed score key; final reason
  belongs to result/meta/trace
```

The transition model is deliberately small:

```text
owner input mutates local facts
  -> rewriteScore(taskId)
  -> derive PRE_REVIEW / PRE_DISPATCH_VISIBLE / RUNNING_VISIBLE / TERMINAL from the
     new score plus minimal meta
```

Owner inputs do not hand-code every state-to-state path. They update only the
fact they own:

| Owner input | Fact mutation |
| --- | --- |
| create | initialize `PRE_REVIEW + ownerSuffix`, write descriptor separately, then best-effort exact-score release |
| pre-review owner mutation | validate review owner facts and rewrite `PRE_REVIEW` with a larger owner mutation `epochSecond` and owner-defined suffix state code |
| approve | write approval fact and `PRE_DISPATCH_VISIBLE` score |
| activation condition update | update activation owner truth only; due `PRE_DISPATCH_VISIBLE` scheduling decides promotion |
| append | push `ReadyItemFrame` into ready LIST; do not rewrite task score |
| claim | move frames from ready LIST to sparse runtime HASH |
| result success | remove sparse runtime state and write final result row |
| retryable result or expiry | remove sparse runtime state, then either push retry frame to ready LIST (`FAST_READY`) or write scheduled retry lane (`DUE_TIME`) |
| no work while running | keep `RUNNING_VISIBLE` with next no-work recheck score and consumed suffix budget |
| no worker match or contention | keep `RUNNING_VISIBLE` with a future running recheck score |
| pause or hold | rewrite the same active band with future `epochSecond`; hard pause uses `PAUSE_EPOCH_SECOND` |
| resume or hold expiry | write a nearer same-band score after owner validation and expected hold-score match, or let the future epoch become due naturally |
| terminal or discard | write terminal fence and terminal closed-at score, then delete task-local runtime keys and meta rows |

Then `rewriteScore` maps the current facts back to one of the lane states:

| Facts after mutation | Derived lane state |
| --- | --- |
| no score member | `NOT_INDEXED` |
| pending approval / preparation | `PRE_REVIEW` |
| approved, activation not yet satisfied | `PRE_DISPATCH_VISIBLE` |
| running with dispatchable, retry, no-work, no-worker, contention, or hold recheck path | `RUNNING_VISIBLE` |
| final / rejected / cancelled / discarded / no-work budget exhausted | `TERMINAL` |

Reason-specific timings are score candidates, not states:

| Reason | Candidate |
| --- | --- |
| activation / ready retry budget / priority recheck | `score(PRE_DISPATCH_VISIBLE_TAG, nextReadyRecheckEpochSecond, suffix)` |
| dispatchable ready backlog | `score(RUNNING_VISIBLE_TAG, nextDispatchEpochSecond, suffix)` |
| retry backoff | `score(RUNNING_VISIBLE_TAG, retryVisibleEpochSecond, suffix)` |
| scheduled retry due time | `score(RUNNING_VISIBLE_TAG, scheduledRetryEpochSecond, suffix)` |
| no matching worker | `score(RUNNING_VISIBLE_TAG, workerRecheckEpochSecond, suffix)` |
| worker contention | `score(RUNNING_VISIBLE_TAG, contentionRecheckEpochSecond, suffix)` |
| active hold | `score(currentActiveTag, futureEpochSecond, suffix)` |
| no work while running | `score(RUNNING_VISIBLE_TAG, nextNoWorkRecheckEpochSecond, suffix)` |

The lane state may therefore change in several valid ways from the same owner
input.
For example, a claim can keep the task `RUNNING_VISIBLE` when backlog remains,
keep it `RUNNING_VISIBLE` with a future no-work score when no work remains, or
keep it `RUNNING_VISIBLE` with a future running score when workers are
contended. Current claims can still exist while the task score is waiting on a
future no-work recheck; claim timeout repair observes them from
`task:{taskId}:rt`.

The implementation may store small bounded score-runtime helper fields, but it
must not store a separate hold band. Hold timing remains encoded in the ZSET
score. Deadline-style task closure is not part of the first score-band model;
closure is driven by exhausted same-band budget.

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
- append does not emit a default wakeup or dirty hint;
- append acknowledgement guarantees accepted backlog persistence only; it does
  not guarantee Task liveness, scheduling, claim, dispatch, or final result;
- append should not rewrite `TaskRuntimeMeta` except for first-time runtime
  initialization; task policy changes own metadata updates;
- assignment-dispatch rounds own live task score rewrite after a score-visible
  task is acquired. Direct lifecycle commands or owner-validated transitions own
  explicit score changes such as approve, pause, resume, close, or discard;
- if the task is waiting on a future `RUNNING_VISIBLE` no-work recheck score,
  append does not move that existing score; the appended item may wait until
  that score is acquired;
- if the task has already closed through exhausted no-work budget, append cannot
  reopen or backdate the old score. Ingress policy may reject it before
  persistence, but accepted backlog does not create a lifecycle transition;
- if stale ingress policy admits an item after terminal, the item is invalid
  residue. Task score remains terminal and retention owns TTL, repeated cleanup,
  generation-scoped deletion, or another bounded residue policy;
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
  RUNNING_VISIBLE, if ready LIST still non-empty after this bounded round
  RUNNING_VISIBLE, if scheduled retry lane has delayed entries and no earlier ready candidate exists
  RUNNING_VISIBLE with a future no-work recheck score, if no ready or scheduled retry candidate remains
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
write terminal closed-at score through the lifecycle/score owner
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
activation check, ready backlog, scheduled retry, task-level retry backoff,
no-work recheck, worker recheck, or contention recheck until it validates
task-local state.

Processing order:

```text
1. Decode task score state and validate task shell, runtime fence, and
   task-local keys.

2. If score state is PRE_DISPATCH_VISIBLE:
      evaluate pre-running open conditions.
      If satisfied, rewrite to RUNNING_VISIBLE.
      If not satisfied and suffix > 00, rewrite PRE_DISPATCH_VISIBLE with next epoch
      and suffix-1.
      If not satisfied and suffix == 00, write PRE_DISPATCH_VISIBLE pause/hold score.
      Stop; do not claim work or produce deliver seeds.

3. If retryMode=DUE_TIME:
     read bounded due messageIds from task:{taskId}:item-score where score <= now.
     If due entries exist, load their frames from task:{taskId}:item and make
     them available to the same scheduling/claim path.
     If no due entries exist and ready LIST is empty, keep the next
     item-score timestamp as the task lane score and stop.

4. If task-level retry backoff, worker recheck, contention delay, or pause hold
   is still active:
      keep the policy-selected future same-band score and stop.

5. If score state is RUNNING_VISIBLE and task shell is dispatchable:
     inspect ready LIST and due scheduled retry evidence.
     If no work exists and suffix > 00, rewrite RUNNING_VISIBLE with next
     no-work epoch and suffix-1.
     If no work exists and suffix == 00, close to TERMINAL with closedAt score.
     If work exists, continue:
     run a scheduling round, classify WORK_CLAIMED / WORKER_NO_MATCH /
     WORKER_CONTENDED, and claim source frames only when a worker was
     selected.

6. Rewrite task score through the rewrite rule.
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
validate current score state is an active band
rewrite same active band through mint_from_range:
  minExpectedScore = score(currentActiveTag, 0, 0)
  maxExpectedScore = score(currentActiveTag, holdEpochSecond - 1, MAX_SUFFIX)
  targetScoreBase = score(currentActiveTag, holdEpochSecond, 0)
  targetSuffix = keep
HSET task:meta:{laneBucketId} taskId
  runtimeEpoch=runtimeEpoch+1
```

Pause/block is an ordinary positive write: `holdEpochSecond` must be greater
than the stored `epochSecond`. Hard pause writes
`holdEpochSecond = PAUSE_EPOCH_SECOND = 9_999_999_999`, and release can use
the exact held score as `observedHoldScore`.

For hard pause, the held score is not a runnable future score. Do not run
activation checks, observed same-band suffix rewrite, worker admission, or cross-band
positive progression from a stored `PAUSE_EPOCH_SECOND` score. Use exact
observed-score release/resume to return the same tag to a nearer epoch, or use a
terminal close when owner finality is accepted.

Do not rewrite every raw ready item or runtime item. Current claims remain in
`task:{taskId}:rt` and may still finish. Retry frames created while parked stay
in the ready LIST or scheduled retry lane according to `retryMode`, but the
future same-band score prevents new claims until that epoch becomes due, or
until an owner-validated resume writes a nearer same-band score. `PRE_REVIEW`
and `TERMINAL` must not be paused. Deadline-style task closure is not part of
the first score-band model; closure is driven by exhausted same-band budget.

Resume/unblock:

```text
validate task shell
validate stored score == observedHoldScore
derive release tag and suffix from observedHoldScore
validate releaseEpochSecond <= decoded observedHoldScore epochSecond
inspect ready LIST, scheduled retry lane, and TaskRuntimeMeta
advance or validate runtimeEpoch
rewrite score to score(expectedTag, resumeEpochSecond, expectedSuffix)
```

Manual resume/release is an acceleration path, not the default correctness path.
It is the only path that may lower `epochSecond`, and only when the stored score
matches `observedHoldScore` exactly. If it does not happen, an ordinary future
hold eventually becomes due and the normal band scan interprets the task as its
original active band; a hard pause remains parked at `PAUSE_EPOCH_SECOND` until
released. After resume, both newly appended items and retry frames are handled
under the current `TaskRuntimeMeta`. A stale resume that does not match
`observedHoldScore` must be a no-op or stale failure; it must not overwrite a
newer hold, terminal close, or scheduling rewrite.

### Terminal and discard

Terminal task cleanup is precise by `taskId`:

```text
HSET task:meta:{laneBucketId} taskId runtimeGate=TERMINAL, runtimeEpoch=runtimeEpoch+1
ZADD task:score:{laneBucketId} TERMINAL_* taskId
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
terminal fence. The terminal score records closed time only; close reason lives
in metadata, result, or trace. This cleanup does not scan the namespace and
does not require one key per item.

## Atomicity Boundaries

Use Lua, Redis transactions, or an equivalent compare-and-swap mechanism only
where the state transition crosses multiple Redis values.

The segmented score format is intended to keep normal acquisition simple:
active scans are plain numeric `ZRANGEBYSCORE` calls over one tag segment plus
`LIMIT`. Lua should not be used just to discover whether a candidate belongs to
`RUNNING_VISIBLE` or `PRE_DISPATCH_VISIBLE`; the range already answers that.
Normal positive writes should compile inside the kernel implementation to the
private `mint_from_range` primitive: the implementation computes
`minExpectedScore`, `maxExpectedScore`, and `targetScoreBase`; public callers
never pass those range coordinates. Lua only verifies range membership and
preserves or substitutes suffix. Observed same-band suffix rewrite is a
negative-delta operation and uses an exact observed-score CAS, because it is
scheduling-round evidence and must not overwrite a newer same-band
classification. Lua/transactions are reserved for score-only positive range
mint, terminal close, observed-score release, observed suffix rewrite, and
owner-local atomic mutations inside work/result structures. No Lua or
transaction may span task score together with descriptor, work, result,
projection, or trace keys merely to serialize ordinary owner mutations.

The Redis/Lua protocol is trusted inside the kernel implementation. It is not a
zero-trust public contract and must not be promoted into a public task-score
port. Safe external behavior comes from narrow public methods that accept
semantic inputs, not from letting callers provide raw range coordinates and
then defending against them in Lua.

Required atomic boundaries:

```text
score-leased Task creation:
  caller supplies one opaque owner suffix
  kernel stores and preserves suffix without interpreting it
  compute leaseUntil = nowMillis + leaseDurationMillis internally
  compute score(PRE_REVIEW_TAG, leaseUntilTimeSlot, suffix) internally
  only a missing score may be initialized through ZADD NX
  any existing score returns initialization failure without band interpretation
  descriptor residue without a score is orphan metadata, not a create conflict
  descriptor HSET is one descriptor-owner operation
  release-to-current is a separate score-only exact CAS
  no Lua or transaction spans score and descriptor keys
  stale release leaves descriptor metadata provisional under current score truth
  known failure release is best-effort; lease duration expiry is the fallback
  later recovery uses an owner transition, never a second initialization

append:
  optional idempotency/backlog guard + ready LIST push
  optional first-time TaskRuntimeMeta init only
  no task score rewrite in the append mutation
  no default wakeup / dirty event emit
  accepted means persisted backlog only; consumption is not guaranteed

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
  pause/block uses same-active-band future score through positive range-mint
  resume/release uses exact observed-hold-score protection
  hard pause does not permit scheduling-round suffix consumption or positive
  cross-band progress until release

discard/terminal:
  terminal fence + runtimeEpoch advance before physical key deletion
  task-local key deletion including scheduled retry lane
  terminal closed-at score + active/meta removal
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
- Do not use an event queue as task scheduling backlog, retry backlog, or
  ready-task index.
- Do not rely on event delivery to start scheduling; `task:score` scan,
  task-local recheck, and bounded repair are the fallback.
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
  tasks should write a terminal closed-at score before cleanup/retention removes
  that score.
- Do not add an active claim expiry ZSET unless a later policy explicitly
  requires near-exact timeout wakeup.
- Do not let retry backoff or no-worker-match penalty hide an earlier ready or
  scheduled retry candidate; task score must wake at the earliest
  score-visible candidate time.
- Do not keep a task's original `firstReadyAt` score after a claim round when
  backlog remains; rewrite it to the next eligible time.
- Do not let an assignment-dispatch loop repeatedly drain one task while other
  due task ids remain visible in the same lane score batch.
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
- no-worker-match scheduling rounds write a future `RUNNING_VISIBLE` recheck
  score and increment only bounded score-runtime penalty state;
- score rewrite picks the earliest ready/scheduled-retry/no-worker-match
  candidate so due retry visibility is not starved;
- claim rounds are bounded by `maxItems`, and a task with remaining backlog is
  rewritten to a next eligible score after each round;
- assignment-dispatch can acquire a bounded batch of eligible tasks so one large
  backlog does not permanently hide other due tasks;
- claim timeout repair discovers active tasks through the active task registry,
  scans `task:{taskId}:rt`, and eventually expires claimed messages without
  relying on task score for exact timeout wakeup;
- pause/block holds only active tasks by writing a future same-band score
  without rewriting items;
- terminal/discard writes a runtime fence and terminal closed-at score before
  physical cleanup, then deletes task-local runtime keys without namespace scan;
- memory and Redis implementations share the same kernel runtime contract
  behavior.
