# Score-Band Worker Runtime Redis Shape

Status: target Redis shape reference for the new kernel workspace. This
document is not current Java implementation truth and is not an implementation
roadmap.

This document is the Redis companion for
[Worker Score-Band Scheduling](../scheduling/worker-score-band-scheduling.md).
It records first-slice key shape and atomicity expectations only.

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
base = epochSecond * SLOT_FACTOR + laneRank * DIRTY_FACTOR + dirty
score = polarity * base
```

Polarity:

```text
score > 0
  HOT_ACQUIRE acquisition lane

score < 0
  RECOVERY_RECHECK recovery lane

score == 0
  invalid / reserved
```

Constants:

```text
DIRTY_FACTOR = 2
LANE_RANK_FACTOR = 100
SLOT_FACTOR = LANE_RANK_FACTOR * DIRTY_FACTOR
MAX_LANE_RANK = 99
MAX_DIRTY = 1
MAX_EPOCH_SECOND = 9_999_999_999
PAUSE_EPOCH_SECOND = MAX_EPOCH_SECOND
MIN_BASE = 1
```

`abs(score)` is decoded the same way for HOT_ACQUIRE and RECOVERY_RECHECK:

```text
epochSecond = abs(score) / SLOT_FACTOR
slotRemainder = abs(score) % SLOT_FACTOR
laneRank = slotRemainder / DIRTY_FACTOR
dirty = slotRemainder % DIRTY_FACTOR
```

The zero coordinate is reserved. Redis members must not be written with score
`0`.

With a 10-digit `epochSecond`, `SLOT_FACTOR = 200` keeps the maximum worker
score around `2e12`, below the Redis sorted-set double exact-integer practical
limit. Do not increase laneRank or dirty digits without rechecking numeric
precision.

`laneRank` is lane-local:

```text
HOT_ACQUIRE
  priority / fairness / same-second tie-break / admission anti-spin hint

RECOVERY_RECHECK
  retry count / failed recheck count / remaining recovery budget
```

`dirty` is a one-bit worker core scheduling metadata dirty-page flag:

```text
0 = clean relative to current reservation/admission owner validation
1 = scheduling-critical metadata changed while a persisted task-worker
    candidate reservation / admission hold may continue from cached admission
    facts
```

It is stored inside the score only so a reservation owner can notice that it
must re-read and revalidate current worker-runtime scheduling metadata before
continuing or pre-occupying the worker. Score-band itself does not create an
`active lease`; `observedScore` is only a stale fence. If there is no persisted
task-worker candidate reservation / admission hold, dirty should remain unused
or deferred. Idle workers do not need dirty writes when metadata changes; the
next scheduling validation reads current metadata. Already dispatched work is
not interrupted through dirty; result / timeout / capacity release handles that
in its owner path. Dirty is not a version, counter, hash, priority, lifecycle,
or audit sequence. The full scheduling signature/hash belongs in worker-runtime
metadata/evidence, not in the score.

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
  base(nowEpochSecond, MAX_LANE_RANK, MAX_DIRTY)
  WITHSCORES
  LIMIT 0 limit
```

Return:

```text
list[(workerId, observedScore)]
```

Recovery recheck acquire:

```text
ZREVRANGEBYSCORE wr:{prefix}:score:{homeBucketId}
  -base(nowEpochSecond - recoveryLookbackSeconds, MIN_LANE_RANK, MIN_DIRTY)
  -base(nowEpochSecond, MAX_LANE_RANK, MAX_DIRTY)
  WITHSCORES
  LIMIT 0 limit
```

The reverse scan is intentional. RECOVERY_RECHECK scores are negative and the
routine recovery scan is bounded to a recent lookback window. Scores newer than
`nowEpochSecond` are future retry delay / hold. Scores older than
`nowEpochSecond - recoveryLookbackSeconds` are exhausted / cold parked and are
outside routine recovery.

Within the same `epochSecond`, reverse scan returns lower laneRank first. The
first slice treats lower RECOVERY_RECHECK laneRank as closer to exhaustion and
therefore more urgent. Within the same `epochSecond` and laneRank, reverse scan
returns lower dirty first. Dirty is a stale fence, not a priority signal. If
a later policy wants the opposite laneRank ordering, it must encode laneRank
inversely rather than changing the scan primitive.

`observedScore` is an opaque full-score fence returned with candidates. It is
the complete signed score, including polarity, epochSecond, laneRank, and dirty.
Do not trim the sign, dirty, or lower coordinates. It is not public worker
lifecycle truth, and callers must not decode, construct, or use it as a
lifecycle DTO. Ordinary monotonic score writes do not need it; lowering
operations such as release or recovery exhaustion use it as exact CAS
protection.

The first slice should be demand-driven or owner-controlled. Do not add a
periodic recovery-recheck scanner until a later design proves the liveness invariant
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
schedulingSignatureHash
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
  owner metadata replacement revision; not a transport session generation and
  not a score-axis fence

schedulingSignatureHash
  platform-owned digest over scheduling-critical metadata; used by the
  reservation owner to validate whether dirty may be cleared before continuing or
  pre-occupying
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
decoded laneRank
decoded dirty
```

Transport/session/freshness evidence stays in transport-owned stores or trace.
If a transport-derived fact affects scheduling, worker-runtime reads it during
validation and writes a worker-runtime-owned score transition.

## Atomic Score Primitives

### Decode Helpers

Redis scripts may decode only for score-axis validation:

```text
absScore = abs(score)
polarity = score > 0 ? HOT_ACQUIRE : RECOVERY_RECHECK
epochSecond = absScore / SLOT_FACTOR
slotRemainder = absScore % SLOT_FACTOR
laneRank = slotRemainder / DIRTY_FACTOR
dirty = slotRemainder % DIRTY_FACTOR
```

Score `0` is invalid. `absScore` must be at least `MIN_BASE`.

### Current Same-Polarity Rewrite

Admission/recheck rounds, cooldown, manual hold, drain, maintenance, and policy
hold all use current-read monotonic same-polarity rewrite:

```text
rewrite_current_score(
  key,
  workerId,
  targetEpochSecond,
  targetLaneRank
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
targetLaneRank = targetLaneRank or stored.laneRank
targetScore = sign(storedScore) *
  base(targetEpochSecond, targetLaneRank, stored.dirty)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

The Redis primitive should not validate worker declaration, capacity, transport
freshness, or placement policy. It should enforce score-axis shape and stale
fence only. It must not change polarity, clear dirty, or lower epochSecond. It
does not require full observed-score CAS because epochSecond only moves forward.

Use this for:

```text
hot admission hold -> positive future score
capacity full -> positive future score
manual disable / drain observed as HOT_ACQUIRE -> positive PAUSE_EPOCH_SECOND score
manual disable / drain observed as RECOVERY_RECHECK -> negative PAUSE_EPOCH_SECOND score
recovery-recheck failed with budget -> negative next-recheck score
```

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
targetScore = sign(observedScore) *
  base(releaseEpochSecond, observed.laneRank, observed.dirty)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

This primitive preserves polarity. Releasing a negative RECOVERY_RECHECK hold only
makes RECOVERY_RECHECK due for recovery validation; it does not produce a positive
HOT_ACQUIRE score directly.

If worker-runtime owner evidence marks a held score as owner-reset-required,
the caller must validate owner reset authorization in the same owner transition
boundary before running this score release. Redis score CAS proves only that
the held score is current; it does not prove that reset is authorized.

### Polarity Move

Polarity move is an owner-validated availability transition:

```text
toggle_current_polarity(
  key,
  workerId,
  observedScore,
  targetLaneRank
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
if observedScore == 0:
  return INVALID
targetPolarity = -observed.polarity
targetScore = targetPolarity *
  base(observed.epochSecond, targetLaneRank, observed.dirty)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

Use positive-to-negative for strong negative availability evidence. Use
negative-to-positive only after worker-runtime verified recovery. Release is not
a polarity move.

Polarity move uses full observed-score CAS. If any score coordinate already
changed, return STALE instead of toggling again. `targetLaneRank` is explicit;
do not inherit laneRank across polarity lanes. It preserves `epochSecond` and
dirty. Preserving epoch keeps future manual holds effective across HOT_ACQUIRE
-> RECOVERY_RECHECK and lets too-old recovered workers become immediately due
after RECOVERY_RECHECK -> HOT_ACQUIRE.

### Recovery Exhausted / Cold Park

Recovery exhausted writes RECOVERY_RECHECK to a too-old coordinate outside the
routine recovery lookback window:

```text
exhaust_recovery_recheck(
  key,
  workerId,
  observedScore
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
decode storedScore
if polarity(storedScore) != RECOVERY_RECHECK:
  return INVALID
coldEpochSecond = mint internal cold epoch from recovery lookback policy
targetLaneRank = stored.laneRank
targetScore = -base(coldEpochSecond, targetLaneRank, stored.dirty)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

`coldEpochSecond` is not caller-supplied. The worker score implementation mints
it from its recovery lookback policy and writes a coordinate older than the
routine recovery lookback window. This is the RECOVERY_RECHECK exhausted / cold
parked shape. Do not use a far-future epoch for exhausted recovery; a future
epoch is for retry delay, manual disable, drain, maintenance, or other holds.

### Deferred Dirty Marker

Worker-runtime owns a platform-defined scheduling signature over fields that
affect worker selection or admission. Redis dirty bit is only a one-bit
dirty-page marker for that signature.

The first `WorkerScoreCore` surface does not expose a generic dirty mark /
clear primitive. Add dirty mutation only with a real persisted candidate
reservation owner.

Metadata replacement flow while a persisted task-worker candidate reservation /
admission hold exists:

```text
update worker-runtime metadataVersion / schedulingSignatureHash
mark worker score dirty through an atomic score write
```

Metadata replacement without a persisted reservation / admission hold does not
require a score dirty write. The next worker candidate validation must read
current worker-runtime scheduling metadata before assignment. Existing
dispatched work continues until result / timeout / capacity release evidence
reaches its owner path.

Dirty mark primitive:

```text
mark_worker_score_dirty(
  key,
  workerId
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
if stored.dirty == 1:
  return NOOP storedScore
targetScore = sign(storedScore) *
  base(stored.epochSecond, stored.laneRank, 1)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

Dirty mark constraints:

```text
do not lower epochSecond
do not change polarity because metadata changed
do not change laneRank because metadata changed
do not treat dirty as priority, reason, lifecycle, or audit sequence
non-reservation owners may only write dirty = 1
```

Dirty clear is not a metadata-owner operation. Only the reservation owner may clear
dirty to `0`, and only after re-reading and validating current
`schedulingSignatureHash` / scheduling metadata before continuing or
pre-occupying the worker. Redis score equality is a stale fence, not proof that
the validation happened; that proof belongs to the worker-runtime owner wrapper
around the reservation-specific primitive.

Dirty mark may be combined atomically with metadata replacement when metadata
change itself is the cause. It is not triggered by raw transport events,
heartbeat, session refresh, trace, or display-only field changes.

## Dynamic State Boundary

The first-slice dynamic score truth is entirely in the ZSET. Do not add:

```text
wr:{prefix}:hold:{homeBucketId}
WorkerHoldState
lease token
session generation
current hold owner record
```

Future HOT_ACQUIRE unavailability is represented by:

```text
+base(futureEpochSecond, laneRank, dirty)
```

Manual disable / drain / maintenance hold preserves current polarity:

```text
HOT_ACQUIRE current score:
  +base(epochSecond, laneRank, dirty) -> +base(PAUSE_EPOCH_SECOND, laneRank, dirty)

RECOVERY_RECHECK current score:
  -base(epochSecond, laneRank, dirty) -> -base(PAUSE_EPOCH_SECOND, laneRank, dirty)
```

Recoverable negative state is represented by:

```text
-base(nextReconnectRecheckEpochSecond, remainingRecheckLaneRank, dirty)
```

RECOVERY_RECHECK is for confirmed negative connectivity or reachability evidence that
still has a recovery window. It does not enter hot eligible acquire. Recheck
validation may move it back to HOT_ACQUIRE, rewrite RECOVERY_RECHECK with lower
budget, write a future retry delay, or write a too-old cold coordinate when
recovery is exhausted.

Recovery exhausted / cold parked is represented by:

```text
-base(coldTooOldEpochSecond, laneRank, dirty)
owner evidence = parked / owner-reset-required / recovery-exhausted / policy hold
```

There is no PARKED band. Too-old RECOVERY_RECHECK falls outside the routine
recovery lookback window. Reason, reopen policy, and operator note are
diagnostics or owner evidence, not score hot-path truth in the first slice.
Ordinary reconnect or heartbeat evidence must not rewrite negative score back to
positive HOT_ACQUIRE. Verified recovery moves RECOVERY_RECHECK to HOT_ACQUIRE
without changing epochSecond; a too-old recovered worker therefore becomes
immediately due in HOT_ACQUIRE. A future manual hold remains future after a
polarity move and still requires release/enable.

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
- `observedScore` must be returned by acquire for exact epoch-lowering
  operations such as release and recovery exhaustion.
- `observedScore` must remain the full signed score including polarity,
  epochSecond, laneRank, and dirty. Do not trim it to epoch/laneRank/dirty, and
  do not let callers construct or decode it.
- Do not expose kernel-owned encoding details as caller parameters: scan ranges,
  cold epoch, polarity sign, dirty bit, base, or factor constants. Redis scripts
  may compute them internally from stable owner policy.
- Do not add fake business strategy knobs to Redis score primitives before a
  real caller workflow owns the value.
- Dirty bit is a deferred short stale fence for platform-owned scheduling metadata
  signature changes. Do not derive it directly from hash modulo, and do not use
  it as priority, reason, audit sequence, or lifecycle state.
- Epoch-lowering operations must use exact observed-score CAS, not broad
  range-mint. Monotonic current-score writes use current-read validation and
  must not lower epochSecond.
- Normal writes must not lower `epochSecond`; only release / enable may lower a
  held score through exact observed-score CAS, preserving polarity.
- Release of owner-reset-required holds must validate owner evidence
  and reset authorization; score CAS alone is not authorization.
- Polarity move must use exact expected-score CAS and explicit target laneRank,
  and must preserve epochSecond.
- Worker metadata is for placement and validation, not transport/session proof.
- Do not add worker lifecycle tags.
- Do not add `PARKED_TAG`; recovery exhausted / cold parked is a too-old
  RECOVERY_RECHECK coordinate plus owner evidence. Manual disable / drain /
  maintenance holds may still use far-future epochSecond.
- Do not add `FUTURE_BAND`; use future epochSecond inside the current polarity.
- Do not add `MANUAL_DISABLED_BAND`; manual disable is same-polarity hold.
- Do not add a hold hash in the first slice.
- Do not let transport write worker score keys directly.
- Do not let positive transport evidence move RECOVERY_RECHECK to HOT_ACQUIRE directly.
- Do not create per-task worker candidate keys.
- Do not create placement-tag score fanout in the first slice.
- Do not add broad background repair scans without a later executable design.
