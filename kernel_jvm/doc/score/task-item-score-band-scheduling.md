# Task Item Score-Band Scheduling

Status: active Java Kernel TaskItem Score Owner contract.

Parent contract: [Task Score-Band Scheduling](task-score-band-scheduling.md)
and
[Assignment-Dispatch Scheduling](../../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md).

## Purpose

Task score-band answers:

```text
which Task should enter a scheduling round now?
```

Task Item score-band answers, after a Task has already entered a scheduling
round:

```text
which Item members inside this Task are worth checking now?
```

The item kernel does not model a ready queue. It stores one canonical Item
record and one monotonic score coordinate:

```text
xa_mass:<scope>:task:<taskId>:items
  HASH
  field = messageId
  value = TaskItem

xa_mass:<scope>:task:<taskId>:item_score
  ZSET
  member = messageId
  score = ItemScore
```

The score axis is Item scheduling truth. The `TaskItem` record is Task runtime
resource truth. They are paired by `(taskId, messageId)` but have different
owners:

```text
TaskRuntime
  TaskItem validation, defaults, persistence, and bounded record reads

TaskItemScoreBandCore
  ItemScore initialization, bounded acquire, claim, retry, and outcome movement
```

Vocabulary is singular across the complete path:

```text
append -> acquire -> claim -> dispatch -> result -> retry/final
                 all refer to the same TaskItem
```

Claim does not create a second `Work` entity. `Work`, `WorkItem`, `WorkRecord`,
`WorkRuntime`, `WorkClaim`, and `workItemId` are not new-kernel model or owner
names. Ordinary prose may still say "no work", but contracts use `TaskItem`,
`messageId`, `TaskRuntime`, `TaskItemScoreBandCore`, and opaque Item claim
evidence.

## Non-Goals

Do not put these into the kernel Item score axis:

```text
ready LIST as runtime truth
retry ZSET
claim-expiry queue
active repair registry
leaseToken
attemptNo
claimEvidence in TaskItem
retryCount in TaskItem
finalReceipt in TaskItem
```

The default ingress path calls `append_items` directly and returns only after the
canonical HASH + ZSET write. Kernel and server do not require an intake LIST,
broker, backlog, materializer, or periodic append job.

A caller may independently use an outbox or broker when its own transaction,
offline submission, or burst-buffering requirements justify one. That caller-
owned mechanism is outside the kernel contract and does not become Item truth.

## TaskItem Model

The Item record carries only caller/intake facts:

```text
TaskItem
  messageId
  eventCode
  payload
  priority = 5
  createdAtMillis
  expireAtMillis = createdAtMillis + defaultItemTtlMillis
```

`messageId` is unique inside one Task. `eventCode` selects the worker-local
handler after Worker selection and does not participate in matching. `payload`
is the caller-owned mapping. A reference is ordinary payload data, for example
`{"ref": "item://payload-2"}`; the kernel does not define a separate reference
field. `priority` is an integer
from `0` through `10`; `5` is the canonical default. An omitted `expireAtMillis` is materialized as
`createdAtMillis + defaultItemTtlMillis`; the current Owner uses a fixed
365-day runtime default. A later configurable value remains TaskRuntime owner
policy and must not become score encoding.

Only `(taskId, messageId)` is stable Item identity. The HASH value is the
latest-write TaskItem record for that identity. Re-appending the same
`messageId` may replace payload, event code, priority, creation time, expiry, or
any caller-defined payload reference without creating a second scheduling
identity.

Item `priority` is distinct from `TaskDescriptor.config["priority"]`. Both use
the common lower-value-first convention. Task priority is `0..99`; Item
priority is `0..10` and places newly appended members inside one Task's ACTIVE
acquire range.

`expireAtMillis` is policy input stored by `TaskRuntime`; it never enters
`TaskItemScoreBandCore`. It is the latest time at which a new dispatch attempt
may begin. TaskRuntime rejects an Item that is already expired when appended.
Task dispatch rechecks the persisted value after score observation and, before
Worker acquisition, stores the failed Item Result and then promotes expired
ACTIVE Items to `TERMINAL(tag=5)` through the existing outcome primitive. If
Result storage fails, score promotion does not run.

Expiry does not retract an attempt claimed before the cutoff. That attempt
continues under its Item claim and Worker lease, and a later success may still
promote `TERMINAL(tag=5)` to `TERMINAL(tag=6)`. The kernel does not put expiry into
the score encoding, add a global expiry scanner, or expose it to transport.
Retry count is not an Item field.
`TaskDescriptor.config` owns `maxRetryTimes`; item-score initialization converts
it to an internal claim budget.

## Score Axis

```text
score = tag * TAG_FACTOR + timeSlot * SUFFIX_FACTOR + suffix
TAG_FACTOR = 10_000_000_000_000
SUFFIX_FACTOR = 100
SLOT_MILLIS = 100
0 <= timeSlot <= 99_999_999_999
0 <= timeMillis <= 9_999_999_999_900
```

The existing integer encoding and Redis key are unchanged. Time is rounded
down to a 100ms slot. The two mechanical bands are:

| Band | Actual tag | Suffix | Meaning |
| --- | --- | --- | --- |
| `ACTIVE` | 1 | 0..99 | Remaining scheduling budget; the only schedulable band |
| `TERMINAL` | 2..9 | 0 | Execution has left scheduling; business meaning belongs to the caller |

`TaskItemScoreState(score, band, tag, timeMillis, remainingBudget)` exposes an
opaque score plus Owner-decoded state. Remaining budget is present only for
ACTIVE. Time is the coordinate within its band, not a business event timestamp.
The Score Owner does not define success, failure, delivered, read or replied.

## Monotonic Write Rules

Every update to an existing member must increase its complete score. A terminal
member can keep advancing but can never re-enter ACTIVE. Initialization uses
`NX`; it cannot reset existing members or create a new execution generation.

### Same-Tag Observed Rewrite

`rewriteObservedItemScores(taskId, observedScores, targetTimeMillis,
remainingBudgetDelta)` remains an ACTIVE-only exact CAS. The Owner validates
the observed encoding, a delta of -1 or 0, nonnegative resulting budget, and
strictly later time slot. Lua then requires `storedScore == observedScore`
before writing the encoded target. A competing larger ACTIVE score is not
permission to claim again: the same observation has at most one winner.
A terminal promotion invalidates every prior ACTIVE observation.

### Terminal Outcome Promotion

`promoteItemOutcomes(taskId, targets)` accepts an ordered Map from Message ID to
`TaskItemOutcomeTarget(tag, timeMillis)`, with at most 100 entries. Each Item may
have a different terminal tag and time. Java validates each target and encodes
its score with suffix 0; invalid entries return INVALID without blocking valid
entries. An invalid Task ID or oversized batch rejects the entire batch. One same-key Lua
handles the entire batch; for each ID it reads and validates the stored score
before comparing `targetScore > currentScore` and optionally writing.

| Condition | Per-Item result |
| --- | --- |
| Legal target greater than current score | `TRANSITIONED`, new score |
| Legal equal or smaller target | `NOOP`, current score |
| Missing member | `NOT_FOUND`, no score |
| Invalid ID, tag, time or oversized batch | `INVALID`, no score and no writes |
| Non-integer, out-of-range or terminal nonzero-suffix stored score | `CORRUPT`, no score and no write |

A higher tag advances even with an earlier time. A lower tag cannot advance
even with a later time. At the same terminal tag only a later slot advances;
different milliseconds in the same slot are a no-op. Empty owner batches are
empty no-ops. Missing and corrupt members are never initialized by promotion.
There is no difference threshold, confirmation read, Redis `TIME`, cross-key
transaction or Result lookup in this operation.

Concurrent promotions retain the maximum legal score. A terminal promotion
racing an exact ACTIVE claim ends terminal regardless of which operation wins
first. These guarantees concern Score only, not Result payload ordering.

## Initial Score And Priority

Priority affects only the initial `timeSlot`. It must not stay as a permanent
ordering override after the item has entered normal scheduling.

First policy:

```text
priorityStepMillis = 100
initialDueMillis = max(
  0,
  createdAtMillis - priority * priorityStepMillis
)
initialClaimBudget = 1 + TaskDescriptor.config["maxRetryTimes"]
initialSuffix = encodeRemainingClaimBudget(initialClaimBudget)
```

Smaller values have higher priority and receive the larger initial due
coordinate. Because ACTIVE acquisition is descending, equal-created Items are
observed from priority `0` toward priority `10`. This placement is used only
when `messageId` is first inserted into ItemScore.

After the first claim, ordinary same-tag rewrites place the item at future
`timeSlot` coordinates such as claim lease expiry or retry due time. Initial
priority no longer reorders the item.

Append scheduling policy maps TaskItem priority to an initial due millisecond
coordinate before calling the score core. `TaskItemScoreBandCore` does not
understand `ItemPriority`; it receives `messageId -> initialDueMillis`, always
initializes `ACTIVE_TAG`, and owns only the retry-budget-to-suffix mapping.

## Acquire

Acquire is a bounded score query, not a destructive pop:

```text
acquire_item_score_candidates(
  taskId,
  limit
)
  -> messageId -> (observedScore, remainingBudget)

has_due_active_items(taskIds)
  -> taskId -> bool
  -> Task Initialization policy read; current due ACTIVE only

has_active_items(taskIds)
  -> taskId -> bool
  -> Task idle-lifecycle read; complete ACTIVE band including future and budget 0
```

For the v0 active policy, scan the complete due ACTIVE range from `now` toward
the minimum ACTIVE coordinate:

```text
beforeSlot = floor(beforeTimeMillis / SLOT_MILLIS)

ZREVRANGEBYSCORE xa_mass:<scope>:task:<taskId>:item_score
  score(ACTIVE_TAG, beforeSlot, MAX_SUFFIX)
  score(ACTIVE_TAG, MIN_TIME_SLOT, MIN_SUFFIX)
  WITHSCORES
  LIMIT 0 limit
```

This direction is intentional. It lets priority affect only initial
timeSlot placement: lower numeric priorities start closer to creation time and
are observed first by the descending query. Once claimed, items are
placed by the ordinary future timeSlot rule. The fixed ACTIVE lower bound keeps
older due Items eligible; bounded work comes from `limit`, not from a moving
lookback threshold or pagination cursor.

The public caller supplies only `taskId + limit`. `TaskItemScoreBandCore` owns
current-time capture, the ACTIVE range bounds, score ordering, and limit
enforcement. Acquire returns an opaque `observedScore` stale fence plus the
semantic `remainingBudget` needed to choose claim or exhausted promotion. The
caller does not decode score fields itself.

## Append

Append is batch-first and exposes no score fields:

```text
append_items(
  taskId,
  items: Sequence[TaskItem]
)
  -> messageId -> TaskItemAppendResult
```

`TaskRuntime` owns this public append operation. Its append scheduling policy
resolves `TaskItem.priority` into `initialDueMillis`. It reads
`TaskDescriptor.config["maxRetryTimes"]` once for the Task-scoped batch,
validates and persists each `TaskItem`, then invokes
`TaskItemScoreBandCore.initialize_item_scores(...)` with stable initialization
inputs only:

```text
messageId
initialDueMillis
maxRetryTimes
```

The current external HTTP realization is Java `TaskDataService` calling the
JVM `TaskRuntime` contract. Its Java `RedisTaskRuntime` provider performs the
same record-first append and ACTIVE `ZADD NX` initialization directly against
the shared Redis shape. There is no HTTP fallback or dual write.

For each Item independently:

```text
TaskRuntime
  validate messageId / eventCode and payload mapping
  materialize priority / expiry defaults
  HSET xa_mass:<scope>:task:<taskId>:items messageId latestTaskItem

TaskItemScoreBandCore
  convert initialDueMillis to timeSlot
  mint internal ACTIVE_TAG / timeSlot / remaining-budget suffix
  ZADD NX xa_mass:<scope>:task:<taskId>:item_score internalScore messageId
```

Neither owner writes the other's key or reconstructs its encoding. A bounded
batch is an API/pipeline optimization, not an all-or-nothing transaction across
Items. HASH write is latest-write-wins. ItemScore initialization is `ZADD NX`:
an existing scheduling identity keeps its current band, time, and budget.

The record-first composition uses only owner results:

```text
TaskRuntime HSET succeeds
  -> invoke TaskItemScoreBandCore.initialize_item_scores
  -> TRANSITIONED: return APPENDED
  -> NOOP: return APPENDED; latest record is stored and existing score is unchanged
  -> retryable infrastructure failure: return RETRYABLE
  -> INVALID: return INVALID
```

A score failure after HSET leaves a record-only state. Repeating append writes
the latest record again and retries `ZADD NX`; no repair scanner is required.
Repeated `messageId` values inside one input batch collapse to the last record
value before HSET. TaskRuntime observes only initialization status and never
reads, decodes, or rewrites an existing ItemScore.

Replacing a record does not reset scheduling. Updated priority or creation time
does not reorder an existing ItemScore; updated payload, event code, or expiry
is visible to later bounded loads. An already-issued delivery may still carry
an older record, and its result still converges the same `messageId`. The kernel
has no payload generation or attempt identity beneath `messageId`.

## Claim

Acquire returns `observedScore + remainingBudget`. The dispatch caller partitions
the bounded result without decoding score:

```text
remainingBudget > 0
  -> claim set

remainingBudget == 0
  -> exhausted set
```

The claim set uses the general same-band primitive:

```text
rewrite_observed_item_scores(
  taskId,
  observedScores,
  claimLeaseUntilMillis,
  remainingBudgetDelta = -1
)
```

For each Item, the core decodes only the caller-returned observed score, validates
`ACTIVE`, decrements remaining budget, mints a later same-band target score, and
uses exact Redis score CAS. `TRANSITIONED + score` is the claim result; that
opaque score is used only to accept the Item/Worker binding inside dispatch.
The current DeliveryCommand and ResultContext do not carry Item claim score.

The exhausted set uses cross-band promotion rather than same-band rewrite:

```text
promote_item_outcomes(
  taskId,
  exhaustedMessageId -> (failedOutcomeTag, exhaustedAtMillis)
)
```

`TaskItemScoreBandCore` never reads the TaskItem HASH. The dispatch caller loads
records through bounded `TaskRuntime.load_task_items(...)` and combines only
successfully rewritten claim scores with their TaskItems. Missing records are
record/retention corruption evidence, not a score transition rule.

## Lease Expiry

There is no repair owner and no claim-expiry queue.

A claim is a future same-tag score. When time passes, the score naturally falls
back into the due acquire range:

```text
ACTIVE future claim score
  time passes
  -> ACTIVE due candidate again
```

If the item has no final score, the next scheduling round may claim it again
according to the same score rules. This is natural score-band retry visibility,
not repair.

## Result And Finality

The Server execution contract supplies `5 = failed` and `6 = succeeded` through
`KernelPacerRuntime.assemble(...)`. Dispatch and the TaskItem result mechanism
receive these tags; the Score Owner knows only the legal terminal range.
A late execution success can advance 5 to 6. Terminal tags 2..9 remain legal
mechanically, including updates after Task closure, without reopening that Task.

`EXECUTION_SUCCESS` stores the existing self-describing Result, separately
requests tag 6, then handles the original Worker execution lease.
`EXECUTION_FAILURE` only handles the correlated Worker lease. The ACTIVE Item
claim naturally becomes due for retry; failure evidence does not rewrite it.
Dispatch stores a failed Result before requesting tag 5 for exhaustion or TTL.

Result HASH and Item Score are independent commits. A successful Result can
coexist with ACTIVE or tag 5 after interruption. Result content has its own
conditional tag/time ordering inside the Result Owner. There is no activation ACK, replay or
Result-to-Score repair path. See the [Result storage contract](../runtime-redis/task-result-runtime-redis-shape.md).

TRACKED observations arrive through their own Task evidence LIST and fixed
consumer. They reuse the same per-Item target Map and never release a Worker
execution lease or reopen Task scheduling. The observation mechanism promotes
Score first, then conditionally stores any content for TRANSITIONED or NOOP
Items. No Task mode or business meaning is added to this Score Owner.

## Exhausted Budget

For `ACTIVE_TAG`, suffix is remaining scheduling budget. A due active item with
`suffix == 0` is not claimable:

```text
ACTIVE due + suffix == 0
  -> store failed Item Result unless success already exists
  -> request the Server-supplied failed tag 5
```

This transition is mandatory once the exhausted Item is selected. Leaving an
unclaimable ACTIVE member in the bounded acquire range would create permanent
hot no-op residue. Dispatch owns exhaustion policy; the Score Owner validates only legal coordinates.

## Relationship To Task Score

Item score-band must not refresh Task score as a generic side effect:

```text
append item
  TaskRuntime writes TaskItem
  TaskItemScoreBandCore initializes ItemScore

claim / retry / final
  writes ItemScore only

task score acquire
  later asks item score-band for due item candidates
```

Task score-band remains the Task-level scheduling entry. Item score-band is the
per-Task member acquire and stale-fence axis. The two axes compose through a
scheduling round; neither axis rewrites the other as routine evidence.

## Optional Caller-Owned Intake Buffer

The normal path is direct:

```text
Java Runtime Server
  -> TaskDataService
  -> TaskRuntime.appendItems
  -> persist TaskItem record
  -> initialize ItemScore with ZADD NX
```

A caller may add an outbox or broker before the append operation, but only for
a named caller-side requirement. The kernel does not require, inspect, poll,
or repair that buffer. If the caller acknowledges before canonical append, the
caller owns durable replay until append succeeds.

```text
accepted after HASH write + ItemScore initialization call
  TaskRuntime stored the latest TaskItem record
  TaskItemScoreBandCore initialized the score or returned NX no-op for the
  existing messageId identity
```

Do not introduce a server backlog merely as a precaution, and do not let an
optional caller buffer become the hidden correctness path for Item runtime truth.

## Minimal Primitive Surface

First executable surfaces:

```text
TaskRuntime

append_items(
  taskId,
  items: Sequence[TaskItem]
)
  -> messageId -> TaskItemAppendResult

load_task_items(taskId, messageIds)

TaskItemScoreBandCore

initialize_item_scores(
  taskId,
  initialDueMillisByMessageId,
  maxRetryTimes
)

acquire_item_score_candidates(
  taskId,
  limit
)
  -> messageId -> (observedScore, remainingBudget)

rewrite_observed_item_scores(
  taskId,
  observedScores,
  targetTimeMillis,
  remainingBudgetDelta
)
  -> messageId -> TaskItemScoreTransitionResult

promote_item_outcomes(
  taskId,
  messageId -> (tag, timeMillis)
)

get_item_score_states(taskId, messageIds)
```

Do not expose initial tag/time/suffix, min/max score internals, Redis range
bounds, raw `targetScore`, decoded tag/timeSlot helpers, or LIST materialization
details as public kernel contracts.

## Java Owner Status

[`RedisTaskItemScoreBandCore`](../../src/main/java/com/xa/mass/kernel/score/redis/RedisTaskItemScoreBandCore.java)
implements the complete surface. Initialization retains pipelined `ZADD NX`;
acquisition remains a bounded ACTIVE range read; claims keep their exact CAS.
Terminal promotion uses one Lua for up to 100 IDs.

`getItemScoreStates(taskId, messageIds)` accepts at most 100 IDs and returns an
ordered deduplicated map from one `ZMSCORE`. Missing members map to null.
Invalid input raises an input error; corrupt stored scores raise an Owner data
error for the query, never a fabricated terminal state. Empty input is an empty
no-op. No Result HASH or Task score is read.

The Server `POST /api/v1/tasks/{taskId}/items:states` query performs one Task
catalog read followed by this Owner read. It returns band, actual tag, score
timeMillis and optional application name, without raw score or Result content.
`results:load`, `items:call` and Result export keep their independent projection
contracts.

Real Redis proof in `RedisTaskOwnerRuntimeIntegrationTest` covers every terminal
tag, slot boundaries, invalid/corrupt values, NX reappend, maximum-score races,
exact claim races and one-Lua/one-ZMSCORE budgets. It uses an isolated `test_*`
scope; Server query command counting additionally verifies one `HGETALL` plus
one `ZMSCORE`.

## Deferred Policy

- Task/runtime policy chooses the initial remaining budget and initialization
  coordinate; callers never provide encoded score fields.
- Dispatch policy chooses the future claim coordinate. Failure result routing
  does not rewrite retry time; the existing claim coordinate becoming due
  restores acquisition visibility without changing score rules.
- A future trusted pre-execution rejection policy may release an Item claim
  early only after the opaque claim score is carried back to a
  TaskItemScoreBandCore exact-CAS primitive. The current ResultContext and score
  interface intentionally provide no such path; `UNKNOWN` delivery evidence
  can never justify early release.
- Final Item retention requires a separate retention owner; score finality does
  not define physical deletion time.

## Guardrails

- Do not add a runtime ready LIST as kernel truth.
- Do not move payload between ready, retry, and active structures.
- Do not store claim evidence, retry count, or final receipt in `TaskItem`.
- Do not let `TaskRuntime` read, decode, mint, or directly write ItemScore.
- Do not let `TaskItemScoreBandCore` read or write TaskItem records.
- Do not let append callers provide tag, timeSlot, suffix, retry budget, or
  encoded score.
- Do not use score absence as final proof.
- Do not let append refresh Task score.
- Do not let result refresh Task score.
- Do not add a repair queue for expired claims.
- Do not let ACTIVE rewrites skip observed-score CAS.
- Compare complete legal scores for terminal updates; time need not increase
  across tags, and later slots within the same terminal tag may advance.
- Do not let lower final tags overwrite higher final tags.
- Do not treat `TERMINAL(tag=5)` as an absorbing result tag; a later success may
  promote it to `TERMINAL(tag=6)`.
- Do not physically remove Item truth until a separate retention owner defines
  when late-success acceptance may end.
- Callers define terminal tag meanings; the Score Owner fixes their legal range
  and suffix rule. No caller constructs raw target scores.
- Do not add event-name branches when the score-band tag/timeSlot/suffix rules
  already express the transition.
