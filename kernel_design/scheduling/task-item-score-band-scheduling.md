# Task Item Score-Band Scheduling

Status: new-kernel mechanism note. This document defines the target Task Item
score-band axis. It is not current implementation truth and not an
implementation roadmap.

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
task:{taskId}:items
  HASH
  field = messageId
  value = ItemRecord

task:{taskId}:item-score
  ZSET
  member = messageId
  score = ItemScore
```

The score axis is the runtime scheduling truth. The Item record is intake data.

Vocabulary is singular across the complete path:

```text
append -> acquire -> claim -> dispatch -> result -> retry/final
                 all refer to the same TaskItem
```

Claim does not create a second `Work` entity. `Work`, `WorkItem`, `WorkRecord`,
`WorkRuntime`, `WorkClaim`, and `workItemId` are not new-kernel model or owner
names. Ordinary prose may still say "no work", but contracts use `TaskItem`,
`messageId`, `ItemRecord`, `TaskItemRuntime`, and opaque Item claim evidence.

## Non-Goals

Do not put these into the kernel Item score axis:

```text
ready LIST as runtime truth
retry ZSET
claim-expiry queue
active repair registry
leaseToken
attemptNo
claimEvidence in ItemRecord
retryCount in ItemRecord
finalReceipt in ItemRecord
```

The default ingress path calls `append_items` directly and returns only after the
canonical HASH + ZSET write. Kernel and server do not require an intake LIST,
broker, backlog, materializer, or periodic append job.

A caller may independently use an outbox or broker when its own transaction,
offline submission, or burst-buffering requirements justify one. That caller-
owned mechanism is outside the kernel contract and does not become Item truth.

## Item Record

The Item record carries only caller/intake facts:

```text
ItemRecord
  messageId
  payload | payloadRef
  priority = 5
  createdAtMillis
  expireAtMillis = createdAtMillis + defaultItemTtlMillis
```

Exactly one of `payload` and `payloadRef` is required. `priority` is an integer
from `0` through `10`; `5` is the canonical default. `createdAtMillis` is the
immutable intake timestamp. An omitted `expireAtMillis` is materialized as
`createdAtMillis + defaultItemTtlMillis`; the v0 runtime default is 365 days and
is configurable owner policy, not score encoding.

Item `priority` is distinct from `TaskDescriptor.config["priority"]`. Task
priority orders Tasks competing for Workers; Item priority places newly appended
members inside one Task's ACTIVE acquire range.

`expireAtMillis` is policy input. The score kernel stores it but does not
interpret it. Claim/result policy may read it and choose a monotonic owner
operation. Retry count is not an Item field. `TaskDescriptor.config` owns
`maxRetryTimes`; item-score initialization converts it to an internal claim
budget.

## Score Axis

The item score uses the same segmented-score discipline as Task score-band, but
with fewer kernel-level assumptions:

```text
score = tag * TAG_FACTOR + timeSlot * SUFFIX_FACTOR + suffix

TAG_FACTOR = TIME_SLOT_FACTOR * SUFFIX_FACTOR
SUFFIX_FACTOR = 100
SLOT_MILLIS = 100
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
opaque observedScore returned only as a stale fence
```

Callers must treat every score as opaque. Owner-facing operations accept Item
records, millisecond timestamps, result outcomes, and opaque observed scores
only where a stale fence is required. They do not accept tag values, timeSlot,
suffix, suffix actions, score bounds, or encoded target scores.

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
Future final categories may be added only by extending the kernel tag table and
its tag-local suffix rule. Ordinary write decisions still use the score-band
rules below: tag growth, timeSlot growth, same-tag CAS, and tag-local suffix
validation.

## Monotonic Write Rules

The item score axis has one global rule:

```text
score only moves forward
tag never decreases
timeSlot never decreases
suffix follows the source/target tag rule
```

That expands into two allowed write classes.

`targetScore` below is an internal encoded coordinate minted by the kernel from
`targetTag`, caller-facing millisecond time, and the tag-local suffix rule.
External callers must not construct or pass raw target scores, score bounds,
timeSlot values, or decoded score fields.

### Same-Tag Observed Rewrite

Same-tag writes are used for claim, retry delay, hold, or any owner-local band
movement:

```text
storedScore == observedScore
targetTag == observedTag
targetTimeSlot >= observedTimeSlot
targetScore > observedScore
targetSuffix satisfies same-tag suffix rule
```

The exact observed-score fence is mandatory because multiple schedulers may see
the same candidate.

Core same-tag suffix rules:

```text
TAG_ACTIVE
  suffix is remaining scheduling budget
  targetSuffix must be observedSuffix or observedSuffix - 1
  targetSuffix must never increase
  claim consumes one budget slot
  retry/hold may preserve the already-consumed suffix

TAG_FINAL_FAILED / TAG_FINAL_SUCCESS
  no same-tag mutation in v0 except idempotent no-op
```

The monotonic guard is still the full score plus non-decreasing timeSlot. The
ACTIVE suffix can decrease because timeSlot is expected to move forward enough
for the full score to increase.

### Cross-Tag Monotonic Write

Cross-tag writes are monotonic result-outcome promotions:

```text
storedScore = current score
targetTag > storedTag
targetTimeSlot >= storedTimeSlot
targetScore > storedScore
targetSuffix satisfies target-tag suffix rule
```

No observed-score equality is required for cross-tag progress. Safety comes from
strict outcome precedence. A higher final tag can override a lower final tag; a
lower or equal tag cannot overwrite the current score.

Result policy passes a named outcome and caller-facing millisecond time. The
kernel maps the outcome to its tag and target suffix; callers do not pass tags
or construct suffix from decoded score fields.

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
thresholdTimeMillis = floorMillis
thresholdSlot = floor(thresholdTimeMillis / SLOT_MILLIS)
nowSlot = floor(nowMillis / SLOT_MILLIS)
initialSlot = max(thresholdSlot, nowSlot - priorityBoostSlots(priority))
initialClaimBudget = 1 + TaskDescriptor.config["maxRetryTimes"]
initialSuffix = encodeRemainingClaimBudget(initialClaimBudget)
```

Higher priority maps closer to `nowSlot`. No priority or lowest priority maps
closer to `thresholdSlot`.

After the first claim, ordinary same-tag rewrites place the item at future
`timeSlot` coordinates such as claim lease expiry or retry due time. Initial
priority no longer reorders the item.

The append caller does not provide any value in this calculation. Item runtime
always initializes `TAG_ACTIVE`; it obtains `maxRetryTimes` from Task config and
owns the priority-to-time and claim-budget-to-suffix mappings.

## Acquire

Acquire is a bounded score query, not a destructive pop:

```text
acquire_item_score_candidates(
  taskId,
  thresholdTimeMillis,
  beforeTimeMillis,
  limit
)
  -> messageId -> observedScore
```

For the first active policy, scan from now toward threshold:

```text
thresholdSlot = floor(thresholdTimeMillis / SLOT_MILLIS)
beforeSlot = floor(beforeTimeMillis / SLOT_MILLIS)

ZREVRANGEBYSCORE task:{taskId}:item-score
  score(TAG_ACTIVE, beforeSlot, MAX_SUFFIX)
  score(TAG_ACTIVE, thresholdSlot, MIN_SUFFIX)
  WITHSCORES
  LIMIT 0 limit
```

This direction is intentional. It lets priority affect only initial
timeSlot placement: high-priority fresh items enter near `nowSlot`, while
floor/default items sit near `thresholdSlot`. Once claimed, items are placed by
the ordinary future timeSlot rule.

Acquire returns only `messageId` plus opaque `observedScore`. The caller must
not decode the score except through kernel-owned helper APIs.

## Append

Append is batch-first and exposes no score fields:

```text
append_items(
  taskId,
  items: Sequence[ItemRecord]
)
  -> messageId -> ItemAppendResult
```

Task Item runtime reads `TaskDescriptor.config["maxRetryTimes"]` once for the
Task-scoped batch. For each Item independently it then:

```text
validate messageId and payload XOR payloadRef
materialize priority / expiry defaults
mint internal TAG_ACTIVE / initial timeSlot / suffix

HSETNX task:{taskId}:items messageId ItemRecord
ZADD NX task:{taskId}:item-score
  score(TAG_ACTIVE, initialTimeSlot, initialSuffix)
  messageId
```

The atomic boundary is one Item's HASH field plus ZSET member. A bounded batch is
an API and pipeline optimization, not an all-or-nothing transaction across
Items. An implementation may pipeline one owner-local atomic script per Item.

Each Item script must classify both keys before mutation:

```text
record absent + score absent
  -> write both and return APPENDED

record present + score present
  -> return DUPLICATE_REJECTED

only one side present
  -> return CORRUPT
```

Duplicate `messageId` values inside one input batch are invalid before Redis
execution. A previously stored duplicate is rejected per Item:

```text
DUPLICATE_REJECTED
```

The kernel does not reinterpret duplicate append as caller idempotency. A caller
that needs a different duplicate contract must own that key space or add an
explicit higher-level idempotency decision.

## Claim

Claim is a same-tag observed rewrite:

```text
claim_observed_items(taskId, observedScores, claimLeaseUntilMillis)
```

For each candidate:

```text
require storedScore == observedScore
require targetTag == observedTag
require targetTimeSlot >= observedTimeSlot
require targetScore > observedScore
read ItemRecord
if ItemRecord missing: return stale
kernel encodes claimScore from observedTag, claimLeaseUntilMillis, and suffix rule
write claimScore
return payload | payloadRef plus claimScore
```

The first active policy uses suffix as remaining scheduling budget:

```text
observed suffix > 0
claimScore = score(TAG_ACTIVE, claimLeaseUntilSlot, observedSuffix - 1)
```

The claim score is the same-tag claim/retry stale fence. It is an opaque full
score carried by DeliverSeed/result evidence and returned to the Item score
owner for comparison. Cross-tag outcome promotion does not require claim-score
equality; it follows the result precedence defined below.

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

Result policy uses the same score primitives.

Same-tag retry:

```text
storedScore == claimScore
targetTag == claimTag
targetTimeSlot = floor(retryDueMillis / SLOT_MILLIS)
targetTimeSlot >= claimTimeSlot
targetScore > claimScore
write retryDueScore
```

Cross-tag outcome promotion:

```text
storedScore = current score
targetTag = outcomeTag(resultOutcome)
targetTag > storedTag
targetTimeSlot = max(storedTimeSlot, floor(outcomeAtMillis / SLOT_MILLIS))
targetScore > storedScore
write finalScore
```

The final score timeSlot is a monotonic score coordinate, not the business
completion timestamp. Result projection owns the original `outcomeAtMillis`.

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
retryable failure
  requires same-tag observed claimScore
  stale/no-op if score moved

final failure
  may promote current ACTIVE to FINAL_FAILED without claimScore equality
  does not cancel an already issued newer claim

late success
  may promote ACTIVE or FINAL_FAILED to FINAL_SUCCESS
  accepted while current tag is below TAG_FINAL_SUCCESS
```

Result policy owns retryable-versus-final classification. The score kernel owns
only same-tag claim CAS and cross-tag outcome precedence. Task closure does not
reopen scheduling, but result retention must continue accepting a valid
`FINAL_SUCCESS` promotion until the owner-defined late-result retention barrier
expires.

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
  writes ItemRecord + ItemScore only

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
server / SDK
  -> TaskItemRuntime.append_items
  -> canonical HASH + ZSET
```

A caller may add an outbox or broker before `append_items`, but only for a named
caller-side requirement. The kernel does not require, inspect, poll, or repair
that buffer. If the caller acknowledges before canonical append, the caller owns
durable replay until `append_items` succeeds.

```text
accepted after HASH + ZSET write
  canonical item score-band owner has accepted the item
```

Do not introduce a server backlog merely as a precaution, and do not let an
optional caller buffer become the hidden correctness path for Item runtime truth.

## Minimal Primitive Surface

First executable surface:

```text
append_items(
  taskId,
  items: Sequence[ItemRecord]
)
  -> messageId -> ItemAppendResult

acquire_item_score_candidates(
  taskId,
  thresholdTimeMillis,
  beforeTimeMillis,
  limit
)
  -> messageId -> observedScore

retry_observed_claim(
  taskId,
  messageId,
  claimScore,
  retryDueMillis
)

promote_item_outcome(
  taskId,
  messageId,
  outcome,
  outcomeAtMillis
)

get_item_records(taskId, messageIds)
get_item_score_states(taskId, messageIds)
```

Do not expose initial tag/time/suffix, min/max score internals, Redis range
bounds, raw `targetScore`, decoded tag/timeSlot helpers, or LIST materialization
details as public kernel contracts.

## Guardrails

- Do not add a runtime ready LIST as kernel truth.
- Do not move payload between ready, retry, and active structures.
- Do not store claim evidence, retry count, or final receipt in `ItemRecord`.
- Do not let append callers provide tag, timeSlot, suffix, retry budget, or
  encoded score.
- Do not use score absence as final proof.
- Do not let append refresh Task score.
- Do not let result refresh Task score.
- Do not add a repair queue for expired claims.
- Do not let same-tag writes skip observed-score CAS.
- Do not allow cross-tag writes without strict tag growth.
- Do not let lower final tags overwrite higher final tags.
- Do not treat `FINAL_FAILED` as an absorbing result tag; a later success may
  promote it to `FINAL_SUCCESS`.
- Do not physically remove Item truth before the late-success retention barrier
  permits cleanup.
- Do not let external callers define tag values or tag-local suffix rules.
- Do not add event-name branches when the score-band tag/timeSlot/suffix rules
  already express the transition.
