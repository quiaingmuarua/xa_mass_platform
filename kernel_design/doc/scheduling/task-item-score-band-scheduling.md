# Task Item Score-Band Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Parent contract: [Task Score-Band Scheduling](task-score-band-scheduling.md)
and [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md).

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
`createdAtMillis + defaultItemTtlMillis`; the v0 executable spec uses a fixed
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
Worker acquisition, promotes expired ACTIVE Items to `FINAL_FAILED` through the
existing outcome primitive.

Expiry does not retract an attempt claimed before the cutoff. That attempt
continues under its Item claim and Worker lease, and a later success may still
promote `FINAL_FAILED` to `FINAL_SUCCESS`. The kernel does not put expiry into
the score encoding, add a global expiry scanner, or expose it to transport.
Retry count is not an Item field.
`TaskDescriptor.config` owns `maxRetryTimes`; item-score initialization converts
it to an internal claim budget.

## Score Axis

The item score uses the same segmented-score discipline as Task score-band, but
with fewer kernel-level assumptions:

```text
score = tag * TAG_FACTOR + timeSlot * SUFFIX_FACTOR + suffix

TAG_FACTOR = TIME_SLOT_FACTOR * SUFFIX_FACTOR
SUFFIX_FACTOR = 100
SLOT_MILLIS = 100
TAG_STRIDE = 4
MAX_SAME_BAND_SCORE_DELTA = TAG_FACTOR - 1
```

This formula is an internal encoding sketch, not a stable interface. The kernel
may change score packing, factor widths, slot scale, or Redis encoding as long
as it preserves the public score-band semantics:

```text
bounded acquire by kernel tag and millisecond horizon
same-tag observed-score fence
cross-tag monotonic progress
kernel-owned tag vocabulary
kernel-owned tag-local suffix rule
opaque observedScore plus semantic remainingBudget returned by acquire
```

Callers must treat every score as opaque. Owner-facing operations accept
millisecond timestamps, semantic target bands for final promotion, remaining-
budget delta `0/-1`, and opaque observed scores only where a stale fence is
required. They do not accept numeric tag values, timeSlot, raw suffix, score
bounds, or encoded target scores.

Kernel mechanics:

```text
tag is kernel-owned score-band identity
timeSlot decides due / future scheduling coordinate
suffix follows the kernel rule for its tag
score absence means the item member does not exist in the score axis
score must be positive and non-zero
```

The kernel owns the minimal tag vocabulary. External callers do not define or
interpret tag values.

```text
TAG_ACTIVE = 1
TAG_FINAL_FAILED = 5
TAG_FINAL_SUCCESS = 9
```

The names above are kernel categories, not per-event transition branches.
Encoded tags advance by `TAG_STRIDE`. Future categories preserve that stride so
the complete numeric distance between two scores can distinguish same-band
movement from cross-band movement without Redis understanding tag names.
Ordinary write decisions still use the score-band rules below: same-tag time
growth with exact CAS, strict cross-tag growth, and tag-local suffix validation.

## Monotonic Write Rules

The item score axis has one global ordering rule:

```text
score only moves forward
tag never decreases
suffix follows the source/target tag rule
```

Time is band-local rather than globally monotonic:

```text
same-band rewrite
  targetTimeSlot > storedTimeSlot

cross-band promotion
  targetScore - storedScore > MAX_SAME_BAND_SCORE_DELTA
  targetTimeSlot is interpreted only inside targetTag
  no comparison with storedTimeSlot
```

An `ACTIVE` score may carry a future claim lease while a result arrives now.
Final promotion therefore replaces that lease with the final band's outcome
time. Tag spacing still guarantees the complete target score is greater than
every score in a lower tag.

`targetScore` below is an internal encoded coordinate minted by the kernel from
`targetTag`, caller-facing millisecond time, and the tag-local suffix rule.
External callers must not construct or pass raw target scores, score bounds,
timeSlot values, or decoded score fields.

### Same-Tag Observed Rewrite

Same-tag writes are used for claim, retry delay, or hold:

```text
storedScore == observedScore
targetTag == observedTag
targetTimeSlot > observedTimeSlot
targetScore > observedScore
targetRemainingBudget = observedRemainingBudget + remainingBudgetDelta
remainingBudgetDelta is -1 or 0
```

The exact observed-score fence is mandatory because multiple schedulers may see
the same candidate.

Core same-tag suffix rules:

```text
TAG_ACTIVE
  suffix is remaining scheduling budget
  target remaining budget must be observed budget or observed budget - 1
  remaining budget must never increase
  claim consumes one budget slot
  retry/hold may preserve the already-consumed suffix

TAG_FINAL_FAILED / TAG_FINAL_SUCCESS
  no same-tag mutation in v0 except idempotent no-op
```

The monotonic guard is still the full score plus non-decreasing timeSlot. The
ACTIVE suffix can decrease because timeSlot is expected to move forward enough
for the full score to increase.

### Cross-Tag Promotion

Cross-tag writes are monotonic result-outcome promotions:

```text
storedScore = current score
scoreDelta = targetScore - storedScore
scoreDelta > MAX_SAME_BAND_SCORE_DELTA
targetTimeSlot = floor(targetTimeMillis / SLOT_MILLIS)
do not compare targetTimeSlot with storedTimeSlot
targetScore > storedScore
targetSuffix satisfies target-tag suffix rule
```

No caller-supplied observed score is required for cross-tag progress. The core
mints the target coordinate and atomically compares its numeric distance from
the stored score. A positive delta no larger than
`MAX_SAME_BAND_SCORE_DELTA` is same-band movement and therefore a promotion
no-op. A larger delta is cross-band progress and is written directly. A
concurrent same-band claim or retry rewrite remains inside that same-band
distance and cannot block the promotion. A higher final tag can override a
lower final tag; a lower or equal tag cannot overwrite the current score.

Result policy passes a semantic final `targetBand` and caller-facing millisecond
time. The kernel maps the band to its numeric tag and fixed final suffix;
callers do not pass numeric tags or construct suffix from decoded score fields.
Redis does not whitelist final band names or decode the stored score.

Core consequence:

```text
TAG_FINAL_SUCCESS is higher than TAG_FINAL_FAILED
late success may overwrite failed final
failure cannot overwrite success
final cannot return to active
```

This is an outcome lattice, not claim-generation isolation:

```text
ACTIVE < FINAL_FAILED < FINAL_SUCCESS
```

An older final-failure result may promote a currently ACTIVE item even if a
newer claim has already been issued. That promotion stops future acquisition;
it does not cancel an already issued claim. A later success from any issued
claim may still promote the item to `FINAL_SUCCESS`. `FINAL_SUCCESS` is the only
absorbing result tag. Result policy must not use final-failure promotion for an
ordinary retryable failure.

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
initializes `TAG_ACTIVE`, and owns only the retry-budget-to-suffix mapping.

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
  score(TAG_ACTIVE, beforeSlot, MAX_SUFFIX)
  score(TAG_ACTIVE, MIN_TIME_SLOT, MIN_SUFFIX)
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
the shared Redis shape. Python `RedisTaskRuntime` and `TaskItemScoreBandCore`
remain the executable-spec oracle. There is no Python
HTTP fallback or dual write.

For each Item independently:

```text
TaskRuntime
  validate messageId / eventCode and payload mapping
  materialize priority / expiry defaults
  HSET xa_mass:<scope>:task:<taskId>:items messageId latestTaskItem

TaskItemScoreBandCore
  convert initialDueMillis to timeSlot
  mint internal TAG_ACTIVE / timeSlot / remaining-budget suffix
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
  exhaustedMessageIds,
  FINAL_FAILED,
  exhaustedAtMillis
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

Result routing does not perform a same-tag retry rewrite. A claim already places
the ACTIVE Item at a future time coordinate. Worker and Adapter failures leave that score
unchanged; when the claim coordinate becomes due, ordinary acquisition retries
the Item and consumes no additional result-owned budget.

Cross-tag outcome promotion:

```text
storedScore = current score
targetTag = tag(targetBand)
targetTag > storedTag
targetTimeSlot = floor(outcomeAtMillis / SLOT_MILLIS)
storedTimeSlot is not compared
targetScore > storedScore
write finalScore
```

The final score timeSlot is target-band outcome time. It may be earlier than a
future ACTIVE claim lease because lifecycle progress is carried by the larger
tag. Result projection still owns the original millisecond timestamp; score
stores the slot-rounded scheduling coordinate.

Examples:

```text
active claim -> active retry due
active -> final failed
active -> final success
final failed -> final success
final success -> rejected/no-op for lower final tags
```

Late result handling uses the same kernel tag ordering:

```text
worker failure / adapter rejection
  no Item score write
  existing ACTIVE claim becomes due naturally

final failure
  produced by exhausted scheduling budget
  may be overwritten only by late success

late success
  may promote ACTIVE or FINAL_FAILED to FINAL_SUCCESS
  accepted while current tag is below TAG_FINAL_SUCCESS
```

The score kernel owns same-tag claim CAS and cross-tag outcome precedence.
Result routing owns successful payload storage followed by FINAL_SUCCESS
promotion. Task closure does not reopen scheduling, but result retention must
continue accepting a valid `FINAL_SUCCESS` promotion until the owner-defined
late-result retention barrier expires.

## Exhausted Budget

For `TAG_ACTIVE`, suffix is remaining scheduling budget. A due active item with
`suffix == 0` is not claimable:

```text
ACTIVE due + suffix == 0
  -> write TAG_FINAL_FAILED
```

This transition is mandatory once the exhausted Item is selected. Leaving an
unclaimable ACTIVE member in the bounded acquire range would create permanent
hot no-op residue. This is a kernel-owned suffix rule for `TAG_ACTIVE`, not an
external policy interpretation.

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
  messageIds,
  targetBand,
  targetTimeMillis
)

get_item_score_states(taskId, messageIds)
```

Do not expose initial tag/time/suffix, min/max score internals, Redis range
bounds, raw `targetScore`, decoded tag/timeSlot helpers, or LIST materialization
details as public kernel contracts.

## Executable-Spec Status

The interface is implemented in
[`executable_spec/kernel/task_item_score_band.py`](../../executable_spec/kernel/task_item_score_band.py).
The Redis ZSET owner is implemented in
[`executable_spec/redis_runtime/task_item_score_band.py`](../../executable_spec/redis_runtime/task_item_score_band.py).
Its physical key is `xa_mass:<scope>:task:<taskId>:item_score`.

Canonical TaskItem record append and bounded load are implemented by
[`executable_spec/redis_runtime/task_runtime.py`](../../executable_spec/redis_runtime/task_runtime.py)
using `xa_mass:<scope>:task:<taskId>:items`. Real-Redis integration proof covers the
complete owner composition:

```text
append -> acquire -> claim -> load -> retry -> final promotion
```

The public Java append and last-success query are implemented by
[`server_jvm/taskdata`](../../../server_jvm/src/main/java/com/xa/mass/server/taskdata).
Real-Redis parity tests lock record JSON, due-score encoding, retry convergence,
`ZADD NX` behavior, and opaque result reads.

The Redis implementation uses only:

```text
pipeline ZADD NX
bounded ZREVRANGEBYSCORE WITHSCORES
pipeline ZSCORE
one exact-score CAS Lua primitive
one encoded-score-distance promotion Lua primitive
```

Band decoding, remaining-budget validation, and target score minting stay in
Python core code. Same-band Lua compares stored score with one expected score.
Cross-band Lua compares the precomputed target score distance with
`MAX_SAME_BAND_SCORE_DELTA`; it does not decode or whitelist tags. Neither
script contains band names, budget, time, result, or retry policy.

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
- Do not let same-tag writes skip observed-score CAS.
- Do not compare cross-tag target time with the source band's lease/recheck
  time; strict tag growth is the lifecycle fence.
- Do not allow cross-tag writes without strict tag growth.
- Do not let lower final tags overwrite higher final tags.
- Do not treat `FINAL_FAILED` as an absorbing result tag; a later success may
  promote it to `FINAL_SUCCESS`.
- Do not physically remove Item truth until a separate retention owner defines
  when late-success acceptance may end.
- Do not let external callers define tag values or tag-local suffix rules.
- Do not add event-name branches when the score-band tag/timeSlot/suffix rules
  already express the transition.
