# Score-Band Worker Runtime Redis Shape

Status: target Redis shape reference for the new kernel workspace. This
document is not current Java implementation truth and is not an implementation
roadmap.

This document is the Redis companion for
[Worker Score-Band Scheduling](../scheduling/worker-score-band-scheduling.md).
It records first-version key shape and atomicity expectations only.

## Purpose

Keep worker-runtime Redis structures small, queryable, and owner-separated.

Worker score Redis owns the acquisition index:

```text
workerId -> signed score
```

It does not own:

```text
transport session truth
task assignment truth
task item claim truth
worker declaration storage
full capacity/admission model
trace / repair stream truth
owner reset reason
```

## First-Slice Assumptions

```text
resourceKind = worker
resourceId = workerId
each resourceId has exactly one homeBucketId
homeBucketId = workerGroupId
homeBucketId is worker-runtime partitioning
homeBucketId is not a placement tag bucket
homeBucketId is not a task-created key
```

Score and scheduling metadata use the same `homeBucketId`.

## Score Encoding

Use the signed numeric score format:

```text
base = epochSecond * SUFFIX_FACTOR + suffix
score = polarity * base
```

Polarity:

```text
score > 0
  HOT acquisition lane

score < 0
  LOW_RECHECK recovery lane

score == 0
  invalid / reserved
```

Constants:

```text
SUFFIX_FACTOR = 100
MAX_SUFFIX = 99
MAX_EPOCH_SECOND = 9_999_999_999
PAUSE_EPOCH_SECOND = MAX_EPOCH_SECOND
MIN_BASE = 1
```

`abs(score)` is decoded the same way for HOT and LOW_RECHECK:

```text
epochSecond = abs(score) / SUFFIX_FACTOR
suffix = abs(score) % SUFFIX_FACTOR
```

The zero coordinate is reserved. Redis members must not be written with score
`0`.

`suffix` is lane-local:

```text
HOT
  priority / fairness / same-second tie-break / admission anti-spin hint

LOW_RECHECK
  retry count / failed recheck count / remaining recovery budget
```

## Key Shape

First-slice keys:

```text
wr:{prefix}:score:{homeBucketId}
  ZSET member = workerId
  score = signed worker score

wr:{prefix}:meta:{homeBucketId}
  HASH field = workerId
  value = WorkerSchedulingMetadata
```

The score ZSET is dynamic acquisition truth. The metadata hash is stable
worker-runtime scheduling metadata. Do not collapse them into one blob.

## Eligibility Index

Use one bounded ZSET per `homeBucketId`:

```text
wr:{prefix}:score:{homeBucketId}
```

Hot eligible acquire:

```text
ZRANGEBYSCORE wr:{prefix}:score:{homeBucketId}
  MIN_BASE
  base(nowEpochSecond, MAX_SUFFIX)
  WITHSCORES
  LIMIT 0 limit
```

Return:

```text
list[(workerId, observedScore)]
```

Low recheck acquire:

```text
ZREVRANGEBYSCORE wr:{prefix}:score:{homeBucketId}
  -MIN_BASE
  -base(nowEpochSecond, MAX_SUFFIX)
  WITHSCORES
  LIMIT 0 limit
```

The reverse scan is intentional. LOW_RECHECK scores are negative, so the oldest
absolute due coordinates are closer to zero. Reverse order returns those first.
A plain ascending negative scan would prefer larger absolute coordinates and can
starve older due recovery candidates under `LIMIT`.

Within the same `epochSecond`, reverse scan returns lower suffix first. The
first slice treats lower LOW_RECHECK suffix as closer to exhaustion and therefore
more urgent. If a later policy wants the opposite ordering, it must encode
suffix inversely rather than changing the scan primitive.

`observedScore` is an opaque stale fence for the worker-runtime admission or
recheck round. It is the complete signed score, including polarity,
epochSecond, and suffix. Do not trim the sign or expose an epoch/suffix-only
fence. It is not public worker lifecycle truth, and callers must not decode,
construct, or use it as a lifecycle DTO; they may only pass it back to
worker-runtime score primitives.

The first version should be demand-driven or owner-controlled. Do not add a
periodic low-recheck scanner until a later design proves the liveness invariant
and cost. Parked inventory, if needed for diagnostics, is a bounded diagnostic
query and must not become a hot-path maintenance loop.

## Scheduling Metadata

Scheduling metadata is not full worker state. It is the stable, low-frequency,
worker-runtime-approved projection used for demand validation and placement
projection.

Target shape:

```text
wr:{prefix}:meta:{homeBucketId}
  HASH field = workerId
  value = WorkerSchedulingMetadata
```

First-slice fields:

```text
workerGroupId
capacityLimit
approvedSchedulingAttributes
placementTagValues
metadataVersion
```

Field roles:

```text
workerGroupId
  capability / management scope for the worker resource

capacityLimit
  stable capacity declaration for first-slice worker resource admission

approvedSchedulingAttributes
  bounded attributes allowed to participate in demand validation

placementTagValues
  compact owner-approved values for diagnostics and later auxiliary indexes

metadataVersion
  owner metadata replacement version; not a transport session generation
```

Do not store these in scheduling metadata:

```text
connectionId
sessionId
routeKey
deliveryBucketId
lastHeartbeatAt
transportEndpointLeaseId
websocketChannelId
polling cursor
raw online flag from transport
raw IP
raw latency
raw battery percent when high-frequency
last error detail
rawEventName
transitionId
current score
decoded polarity
decoded epochSecond
decoded suffix
```

Transport/session/freshness evidence stays in transport-owned stores or trace.
If a transport-derived fact affects scheduling, worker-runtime reads it during
validation and writes a worker-runtime-owned score transition.

## Atomic Score Primitives

### Decode Helpers

Redis scripts may decode only for score-axis validation:

```text
absScore = abs(score)
polarity = score > 0 ? HOT : LOW_RECHECK
epochSecond = absScore / SUFFIX_FACTOR
suffix = absScore % SUFFIX_FACTOR
```

Score `0` is invalid. `absScore` must be at least `MIN_BASE`.

### Observed Rewrite

Admission and low-recheck rounds must use exact observed-score CAS:

```text
rewrite_observed_worker_score(
  key,
  workerId,
  observedScore,
  targetScore
)
```

Lua shape:

```text
stored = ZSCORE key workerId
if stored == nil:
  return STALE
storedScore = tonumber(stored)
if storedScore ~= observedScore:
  return STALE with storedScore
decode observedScore
decode targetScore
if targetScore == 0:
  return INVALID
if target.epochSecond < observed.epochSecond:
  return INVALID
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

Target score is computed by worker-runtime before calling the Redis primitive.
The Redis primitive should not validate worker declaration, capacity, transport
freshness, or placement policy. It should enforce score-axis shape and stale
fence only.

Before calling this primitive, worker-runtime must mint cross-polarity
`targetScore` with an explicit policy-owned target suffix. Redis receives only
the final `targetScore`; it should not infer whether suffix was intentionally
chosen.

The exact equality check must compare the full signed `observedScore`. A
trimmed fence without sign is invalid because it cannot distinguish stale
cross-polarity changes, such as `-base(epoch, suffix)` being replaced by
`+base(epoch, suffix)`.

Use this for:

```text
hot admission hold -> positive future score
capacity full -> positive future score
manual disable / drain observed as HOT -> positive PAUSE_EPOCH_SECOND score
manual disable / drain observed as LOW_RECHECK -> negative PAUSE_EPOCH_SECOND score
disconnect with recovery window -> negative score
low-recheck failed with budget -> negative next-recheck score
low-recheck exhausted / parked -> negative PAUSE_EPOCH_SECOND score + owner evidence
low-recheck recovery -> positive score after validation
```

### Current-Polarity Hold

Owner paths that hold a worker without changing availability polarity use a
current-read same-polarity write:

```text
hold_current_worker_polarity(
  key,
  workerId,
  targetEpochSecond,
  targetSuffix
)
```

Lua shape:

```text
stored = ZSCORE key workerId
if stored == nil:
  return STALE
storedScore = tonumber(stored)
if storedScore == 0:
  return INVALID
decode storedScore
if targetEpochSecond < stored.epochSecond:
  return INVALID
targetSuffix = targetSuffix or stored.suffix
targetScore = sign(storedScore) * base(targetEpochSecond, targetSuffix)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

This primitive is for manual disable, drain, maintenance, owner hold, admission
hold, or parked/recovery-exhausted far-future hold. It must not flip HOT to
LOW_RECHECK or LOW_RECHECK to HOT.

### Release / Enable

Manual enable or hold release is the only ordinary score primitive that lowers
`epochSecond`:

```text
release_worker_hold(
  key,
  workerId,
  observedScore,
  releaseEpochSecond
)
```

Lua shape:

```text
stored = ZSCORE key workerId
if stored == nil:
  return STALE
storedScore = tonumber(stored)
if storedScore ~= observedScore:
  return STALE with storedScore
decode observedScore
if releaseEpochSecond > observed.epochSecond:
  return INVALID
targetScore = sign(observedScore) * base(releaseEpochSecond, observed.suffix)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

This primitive preserves polarity. Releasing a negative LOW_RECHECK hold only
makes LOW_RECHECK due for recovery validation; it does not produce a positive
HOT score directly.

If worker-runtime owner evidence marks the held negative score as parked,
owner-reset-required, or recovery-exhausted, the caller must validate owner reset
authorization in the same owner transition boundary before running this score
release. Redis score CAS proves only that the held score is current; it does not
prove that unpark is authorized.

### Polarity Flip

Polarity flip is an owner-validated availability transition:

```text
flip_worker_polarity(
  key,
  workerId,
  expectedScore,
  targetPolarity,
  targetEpochSecond,
  targetSuffix
)
```

Lua shape:

```text
stored = ZSCORE key workerId
if stored == nil:
  return STALE
storedScore = tonumber(stored)
if storedScore ~= expectedScore:
  return STALE with storedScore
decode storedScore
if targetPolarity == polarity(storedScore):
  return INVALID
if targetEpochSecond < stored.epochSecond:
  return INVALID
targetScore = targetPolarity * base(targetEpochSecond, targetSuffix)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

Use positive-to-negative for strong negative availability evidence. Use
negative-to-positive only after worker-runtime verified reopen. Release is not a
polarity flip. A parked LOW_RECHECK score at `PAUSE_EPOCH_SECOND` must first be
released to due LOW_RECHECK; only after recovery validation passes may a
separate polarity flip write a positive HOT score.

Polarity flip always requires exact `expectedScore` and an explicit
policy-owned `targetSuffix`. Do not inherit suffix across polarity lanes.

## Dynamic State Boundary

The first-slice dynamic score truth is entirely in the ZSET. Do not add:

```text
wr:{prefix}:hold:{homeBucketId}
WorkerHoldState
lease token
session generation
current hold owner record
```

Future HOT unavailability is represented by:

```text
+base(futureEpochSecond, suffix)
```

Manual disable / drain / maintenance hold preserves current polarity:

```text
HOT current score:
  +base(epochSecond, suffix) -> +base(PAUSE_EPOCH_SECOND, suffix)

LOW_RECHECK current score:
  -base(epochSecond, suffix) -> -base(PAUSE_EPOCH_SECOND, suffix)
```

Recoverable negative state is represented by:

```text
-base(nextReconnectRecheckEpochSecond, remainingRecheckBudget)
```

LOW_RECHECK is for confirmed negative connectivity or reachability evidence that
still has a recovery window. It does not enter hot eligible acquire. Recheck
validation may move it back to HOT, rewrite LOW_RECHECK with lower budget, or
write LOW_RECHECK far-future hold when recovery is exhausted.

Parked is represented by:

```text
-base(PAUSE_EPOCH_SECOND, suffix)
owner evidence = parked / owner-reset-required / recovery-exhausted / policy hold
```

There is no PARKED band. Reason, reopen policy, and operator note are
diagnostics or owner evidence, not score hot-path truth in the first slice.
Ordinary reconnect or heartbeat evidence must not rewrite negative score back to
positive HOT. Reopen requires release to due LOW_RECHECK followed by
worker-runtime verified polarity flip. Release of a parked hold must validate
owner reset authorization outside the score value before writing the release.

## Capacity / Admission Boundary

Capacity/admission runtime truth is worker-runtime owned, but it is not modeled
inside the score ZSET.

If a capacity mutation and score rewrite protect the same admission invariant,
the implementation must update them atomically through Lua, Redis transaction,
or another compare-and-swap mechanism.

Do not copy capacity counters into `WorkerSchedulingMetadata` as current truth.
The metadata field `capacityLimit` is declaration/configuration, not current
availability.

## Transition Evidence

Transition evidence is proof/trace, not current state.

Default sink:

```text
trace event / deterministic test assertion
```

Deferred optional sink:

```text
wr:{prefix}:transition
  Redis Stream for explicit repair/debug needs only
```

Do not include a transition stream in first-slice Redis completion criteria.

Suggested evidence fields, if later needed:

```text
transitionType
workerId
homeBucketId
oldScore
newScore
oldPolarity
newPolarity
reasonCode
sourceType
ownerAction
correlationId
observedAt
```

Evidence must not drive hot-path acquire.

## Forbidden Keys

Do not create these in the first slice:

```text
task:{taskId}:candidate-workers
worker:{workerId}:score
attribute:{name}:{value}:workers
wr:{prefix}:hold:{homeBucketId}
wr:{prefix}:session:{homeBucketId}
wr:{prefix}:heartbeat:{homeBucketId}
```

Auxiliary placement indexes, if added later, must not own score or metadata
truth. They may only provide stale candidates that are validated against the
resource's home bucket.

## Guardrails

- `polarity` is derived from ZSET score sign; do not store it as independent
  truth.
- `score` lives in score ZSETs; do not copy it into metadata.
- `observedScore` must be returned by acquire and used as the admission stale
  fence.
- `observedScore` must remain the full signed score including polarity. Do not
  trim it to epoch/suffix, and do not let callers construct or decode it.
- Admission/recheck rewrites must use exact observed-score CAS, not broad
  range-mint.
- Normal writes must not lower `epochSecond`; only release / enable may lower a
  held score through exact observed-score CAS, preserving polarity.
- Release of parked / owner-reset-required holds must validate owner evidence
  and reset authorization; score CAS alone is not authorization.
- Polarity flip must use exact expected-score CAS and explicit target suffix.
- Worker metadata is for placement and validation, not transport/session proof.
- Do not add worker lifecycle tags.
- Do not add `PARKED_TAG`; parked is negative far-future hold plus owner
  evidence.
- Do not add `FUTURE_BAND`; use future epochSecond inside the current polarity.
- Do not add `MANUAL_DISABLED_BAND`; manual disable is same-polarity hold.
- Do not add a hold hash in the first slice.
- Do not let transport write worker score keys directly.
- Do not let positive transport evidence flip LOW_RECHECK to HOT directly.
- Do not create per-task worker candidate keys.
- Do not create placement-tag score fanout in the first slice.
- Do not add broad background repair scans without a later executable design.
