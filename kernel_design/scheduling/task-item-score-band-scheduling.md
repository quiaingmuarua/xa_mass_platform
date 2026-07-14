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

The item kernel does not model a ready queue. It stores one immutable-ish Item
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

LIST or broker intake may exist as an optional ingestion accelerator, but it is
not a kernel contract. If an API returns accepted after writing only the
accelerator, that accelerator owner must provide durable replay and eventual
canonical materialization. The default kernel acceptance point is the canonical
HASH + ZSET write.

## Item Record

The Item record carries only caller/intake facts:

```text
ItemRecord
  payload | payloadRef
  priority
  createdAtMillis
  expireAtMillis | null
```

`expireAtMillis` is policy input. The score kernel stores it but does not
interpret it. Claim/result policy may read it and choose a monotonic target
score.

## Score Axis

The item score uses the same segmented-score discipline as Task score-band, but
with fewer kernel-level assumptions:

```text
score = tag * TAG_FACTOR + timeSlot * SUFFIX_FACTOR + suffix

TAG_FACTOR = TIME_SLOT_FACTOR * SUFFIX_FACTOR
SUFFIX_FACTOR = 100
SLOT_MILLIS = 100
```

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

Cross-tag writes are used for irreversible band progress and higher-priority
final override:

```text
storedScore = current score
targetTag > storedTag
targetTimeSlot >= storedTimeSlot
targetScore > storedScore
targetSuffix satisfies target-tag suffix rule
```

No observed-score equality is required for cross-tag progress. Safety comes from
strict tag growth. A higher final tag can override a lower final tag; a lower or
equal tag cannot overwrite the current score.

Cross-tag callers pass `targetTag` and caller-facing millisecond time. The kernel
derives `targetSuffix` from the target-tag rule; callers must not construct it
from decoded score fields.

Core consequence:

```text
TAG_FINAL_SUCCESS is higher than TAG_FINAL_FAILED
late success may overwrite failed final
failure cannot overwrite success
final cannot return to active
```

## Initial Score And Priority

Priority affects only the initial `timeSlot`. It must not stay as a permanent
ordering override after the item has entered normal scheduling.

First policy:

```text
thresholdTimeMillis = floorMillis
thresholdSlot = floor(thresholdTimeMillis / SLOT_MILLIS)
nowSlot = floor(nowMillis / SLOT_MILLIS)
initialSlot = max(thresholdSlot, nowSlot - priorityBoostSlots(priority))
initialSuffix = remainingBudget
```

Higher priority maps closer to `nowSlot`. No priority or lowest priority maps
closer to `thresholdSlot`.

After the first claim, ordinary same-tag rewrites place the item at future
`timeSlot` coordinates such as claim lease expiry or retry due time. Initial
priority no longer reorders the item.

## Acquire

Acquire is a bounded score query, not a destructive pop:

```text
acquire_item_score_candidates(
  taskId,
  tag,
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

This direction is intentional. It lets priority work only as an initial
timeSlot placement: high-priority fresh items enter near `nowSlot`, while
floor/default items sit near `thresholdSlot`. Once claimed, items are placed by
the ordinary future timeSlot rule.

Acquire returns only `messageId` plus opaque `observedScore`. The caller must
not decode the score except through kernel-owned helper APIs.

## Append

Append writes the canonical Item record and initial score in one owner-local
atomic operation:

```text
append_item(
  taskId,
  messageId,
  itemRecord,
  initialTag,
  initialTimeMillis,
  initialSuffix
)

HSETNX task:{taskId}:items messageId ItemRecord
ZADD NX task:{taskId}:item-score
  score(initialTag, floor(initialTimeMillis / SLOT_MILLIS), initialSuffix)
  messageId
```

Both writes must succeed for kernel acceptance.

Duplicate `messageId` is rejected:

```text
DUPLICATE_REJECTED
```

The kernel does not promise caller idempotency for duplicate appends. A caller
that needs idempotency must own that key space or add an explicit higher-level
idempotency contract.

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

The claim score is the result fence. It is an opaque full score carried by the
dispatch/result caller and returned to the item score owner for comparison.

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

Cross-tag final:

```text
storedScore = current score
targetTag > storedTag
targetTimeSlot = floor(finalTimeMillis / SLOT_MILLIS)
targetTimeSlot >= storedTimeSlot
targetScore > storedScore
write finalScore
```

Examples:

```text
active claim -> active retry due
active claim -> final failed
active claim -> final success
final failed -> final success
final success -> rejected/no-op for lower final tags
```

Late result handling uses the same kernel tag ordering:

```text
late failure
  requires same-tag observed claimScore
  stale if score moved

late success
  may use cross-tag monotonic write
  allowed only when current tag is below TAG_FINAL_SUCCESS
```

The default policy may discard expired-lease results unless the result can be
represented as a higher-tag success override. The score kernel enforces the
same monotonic score rules either way.

## Exhausted Budget

For `TAG_ACTIVE`, suffix is remaining scheduling budget. A due active item with
`suffix == 0` is not claimable:

```text
ACTIVE due + suffix == 0
  -> write TAG_FINAL_FAILED
  -> or leave it for a later bounded score pass
```

This is a kernel-owned suffix rule for `TAG_ACTIVE`, not an external policy
interpretation.

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

## Optional Intake Accelerator

An implementation may add:

```text
external append
  -> optional LIST / broker
  -> bounded materializer
  -> canonical HASH + ZSET
```

This is not a kernel contract. It is valid only when the intake owner defines
acceptance semantics:

```text
accepted after LIST write
  LIST/broker owner must provide durable replay and materialization

accepted after HASH + ZSET write
  canonical item score-band owner has accepted the item
```

Do not let an optional materializer become the hidden correctness path for item
runtime truth.

## Minimal Primitive Surface

First executable surface:

```text
append_item(
  taskId,
  messageId,
  itemRecord,
  initialTag,
  initialTimeMillis,
  initialSuffix
)

acquire_item_score_candidates(
  taskId,
  tag,
  thresholdTimeMillis,
  beforeTimeMillis,
  limit
)
  -> messageId -> observedScore

rewrite_observed_same_tag_score(
  taskId,
  messageId,
  observedScore,
  targetTimeMillis,
  suffixAction
)

rewrite_current_cross_tag_score(
  taskId,
  messageId,
  targetTag,
  targetTimeMillis
)

get_item_records(taskId, messageIds)
get_item_score_states(taskId, messageIds)
```

Do not expose min/max score internals, Redis range bounds, raw `targetScore`,
decoded tag/timeSlot helpers, or LIST materialization details as public kernel
contracts.

## Guardrails

- Do not add a runtime ready LIST as kernel truth.
- Do not move payload between ready, retry, and active structures.
- Do not store claim evidence, retry count, or final receipt in `ItemRecord`.
- Do not use score absence as final proof.
- Do not let append refresh Task score.
- Do not let result refresh Task score.
- Do not add a repair queue for expired claims.
- Do not let same-tag writes skip observed-score CAS.
- Do not allow cross-tag writes without strict tag growth.
- Do not let lower final tags overwrite higher final tags.
- Do not let external callers define tag values or tag-local suffix rules.
- Do not add event-name branches when the score-band tag/timeSlot/suffix rules
  already express the transition.
