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
base = epochSecond * SLOT_FACTOR + laneRank * VERSION_FACTOR + version
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
VERSION_FACTOR = 100
LANE_RANK_FACTOR = 100
SLOT_FACTOR = LANE_RANK_FACTOR * VERSION_FACTOR
MAX_LANE_RANK = 99
MAX_VERSION = 99
MAX_EPOCH_SECOND = 9_999_999_999
PAUSE_EPOCH_SECOND = MAX_EPOCH_SECOND
MIN_BASE = 1
```

`abs(score)` is decoded the same way for HOT_ACQUIRE and RECOVERY_RECHECK:

```text
epochSecond = abs(score) / SLOT_FACTOR
slotRemainder = abs(score) % SLOT_FACTOR
laneRank = slotRemainder / VERSION_FACTOR
version = slotRemainder % VERSION_FACTOR
```

The zero coordinate is reserved. Redis members must not be written with score
`0`.

With a 10-digit `epochSecond`, `SLOT_FACTOR = 10_000` keeps the maximum worker
score around `1e14`, below the Redis sorted-set double exact-integer practical
limit. Do not increase version digits without rechecking numeric precision.

`laneRank` is lane-local:

```text
HOT_ACQUIRE
  priority / fairness / same-second tie-break / admission anti-spin hint

RECOVERY_RECHECK
  retry count / failed recheck count / remaining recovery budget
```

`version` is a worker core scheduling metadata revision fence. It is 00..99 and
stored inside the score only to make stale acquired rounds fail after
platform-defined scheduling metadata changes. The full scheduling signature or
hash belongs in worker-runtime metadata/evidence, not in the score.

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
  base(nowEpochSecond, MAX_LANE_RANK, MAX_VERSION)
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
  -MIN_BASE
  -base(nowEpochSecond, MAX_LANE_RANK, MAX_VERSION)
  WITHSCORES
  LIMIT 0 limit
```

The reverse scan is intentional. RECOVERY_RECHECK scores are negative, so the oldest
absolute due coordinates are closer to zero. Reverse order returns those first.
A plain ascending negative scan would prefer larger absolute coordinates and can
starve older due recovery candidates under `LIMIT`.

Within the same `epochSecond`, reverse scan returns lower laneRank first. The
first slice treats lower RECOVERY_RECHECK laneRank as closer to exhaustion and
therefore more urgent. Within the same `epochSecond` and laneRank, reverse scan
returns lower version first. Version is a stale fence, not a priority signal. If
a later policy wants the opposite laneRank ordering, it must encode laneRank
inversely rather than changing the scan primitive.

`observedScore` is an opaque stale fence for the worker-runtime admission or
recheck round. It is the complete signed score, including polarity,
epochSecond, laneRank, and version. Do not trim the sign, version, or lower
coordinates. It is not public worker lifecycle truth, and callers must not
decode, construct, or use it as a lifecycle DTO; they may only pass it back to
worker-runtime score primitives.

The first version should be demand-driven or owner-controlled. Do not add a
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
scoreVersion
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

schedulingSignatureHash
  platform-owned digest over scheduling-critical metadata; used to decide
  whether the score version fence should be refreshed

scoreVersion
  last score-version fence written for the current scheduling signature; not a
  standalone owner truth and not an audit sequence
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
decoded version
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
laneRank = slotRemainder / VERSION_FACTOR
version = slotRemainder % VERSION_FACTOR
```

Score `0` is invalid. `absScore` must be at least `MIN_BASE`.

### Observed Rewrite

Admission and recovery-recheck rounds must use exact observed-score CAS:

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
if target.version != observed.version:
  require caller already performed worker-runtime scheduling signature refresh
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

Target score is computed by worker-runtime before calling the Redis primitive.
The Redis primitive should not validate worker declaration, capacity, transport
freshness, or placement policy. It should enforce score-axis shape and stale
fence only.

Before calling this primitive, worker-runtime must mint cross-polarity
`targetScore` with an explicit policy-owned target laneRank. Redis receives only
the final `targetScore`; it should not infer whether laneRank was intentionally
chosen.

The exact equality check must compare the full signed `observedScore`. A
trimmed fence without sign is invalid because it cannot distinguish stale
cross-polarity changes, such as `-base(epoch, laneRank, version)` being replaced
by `+base(epoch, laneRank, version)`. A trimmed fence without version is also
invalid because it cannot detect that worker scheduling metadata changed after
acquire.

Use this for:

```text
hot admission hold -> positive future score
capacity full -> positive future score
manual disable / drain observed as HOT_ACQUIRE -> positive PAUSE_EPOCH_SECOND score
manual disable / drain observed as RECOVERY_RECHECK -> negative PAUSE_EPOCH_SECOND score
disconnect with recovery window -> negative score
recovery-recheck failed with budget -> negative next-recheck score
recovery-recheck exhausted / parked -> negative PAUSE_EPOCH_SECOND score + owner evidence
recovery-recheck recovery -> positive score after validation
```

### Current-Polarity Hold

Owner paths that hold a worker without changing availability polarity use a
current-read same-polarity write:

```text
hold_current_worker_polarity(
  key,
  workerId,
  targetEpochSecond,
  targetLaneRank,
  targetVersion
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
targetVersion = targetVersion or stored.version
targetScore = sign(storedScore) * base(targetEpochSecond, targetLaneRank, targetVersion)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

This primitive is for manual disable, drain, maintenance, owner hold, admission
hold, or parked/recovery-exhausted far-future hold. It must not flip HOT_ACQUIRE to
RECOVERY_RECHECK or RECOVERY_RECHECK to HOT_ACQUIRE.

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
  base(releaseEpochSecond, observed.laneRank, observed.version)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

This primitive preserves polarity. Releasing a negative RECOVERY_RECHECK hold only
makes RECOVERY_RECHECK due for recovery validation; it does not produce a positive
HOT_ACQUIRE score directly.

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
  targetLaneRank
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
targetScore = targetPolarity *
  base(targetEpochSecond, targetLaneRank, stored.version)
ZADD key targetScore workerId
return TRANSITIONED targetScore
```

Use positive-to-negative for strong negative availability evidence. Use
negative-to-positive only after worker-runtime verified reopen. Release is not a
polarity flip. A parked RECOVERY_RECHECK score at `PAUSE_EPOCH_SECOND` must first be
released to due RECOVERY_RECHECK; only after recovery validation passes may a
separate polarity flip write a positive HOT_ACQUIRE score.

Polarity flip always requires exact `expectedScore` and an explicit
policy-owned `targetLaneRank`. Do not inherit laneRank across polarity lanes.

### Scheduling Signature Refresh

Worker-runtime owns a platform-defined scheduling signature over fields that
affect worker selection or admission. Redis score version is only a low-order
stale fence for that signature.

Refresh flow:

```text
compute platform-owned scheduling signature
compare with wr:{prefix}:meta:{homeBucketId}[workerId].schedulingSignatureHash
if unchanged:
  do not bump score version
if changed:
  update schedulingSignatureHash in metadata/evidence
  refresh score version through an atomic score write
```

The exact version bump rule is deferred to the executable spec, but the Redis
shape must preserve these constraints:

```text
do not set version = hash % N directly
do not lower epochSecond during signature refresh
do not change polarity because metadata changed
do not treat version as priority, reason, or audit sequence
do not allow rollover to recreate a live old observedScore coordinate
```

Typical score effect:

```text
sign, epochSecond, laneRank preserved
version changes to the next owner-approved fence value
```

Signature refresh may be combined atomically with metadata replacement when the
metadata change itself is the cause. It is not triggered by raw transport
events, heartbeat, session refresh, trace, or display-only field changes.

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
+base(futureEpochSecond, laneRank, version)
```

Manual disable / drain / maintenance hold preserves current polarity:

```text
HOT_ACQUIRE current score:
  +base(epochSecond, laneRank, version) -> +base(PAUSE_EPOCH_SECOND, laneRank, version)

RECOVERY_RECHECK current score:
  -base(epochSecond, laneRank, version) -> -base(PAUSE_EPOCH_SECOND, laneRank, version)
```

Recoverable negative state is represented by:

```text
-base(nextReconnectRecheckEpochSecond, remainingRecheckLaneRank, version)
```

RECOVERY_RECHECK is for confirmed negative connectivity or reachability evidence that
still has a recovery window. It does not enter hot eligible acquire. Recheck
validation may move it back to HOT_ACQUIRE, rewrite RECOVERY_RECHECK with lower budget, or
write RECOVERY_RECHECK far-future hold when recovery is exhausted.

Parked is represented by:

```text
-base(PAUSE_EPOCH_SECOND, laneRank, version)
owner evidence = parked / owner-reset-required / recovery-exhausted / policy hold
```

There is no PARKED band. Reason, reopen policy, and operator note are
diagnostics or owner evidence, not score hot-path truth in the first slice.
Ordinary reconnect or heartbeat evidence must not rewrite negative score back to
positive HOT_ACQUIRE. Reopen requires release to due RECOVERY_RECHECK followed by
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
- `observedScore` must remain the full signed score including polarity,
  epochSecond, laneRank, and version. Do not trim it to epoch/laneRank/version, and
  do not let callers construct or decode it.
- Score version is a short stale fence for platform-owned scheduling metadata
  signature changes. Do not derive it directly from hash modulo, and do not use
  it as priority, reason, audit sequence, or lifecycle state.
- Admission/recheck rewrites must use exact observed-score CAS, not broad
  range-mint.
- Normal writes must not lower `epochSecond`; only release / enable may lower a
  held score through exact observed-score CAS, preserving polarity.
- Release of parked / owner-reset-required holds must validate owner evidence
  and reset authorization; score CAS alone is not authorization.
- Polarity flip must use exact expected-score CAS and explicit target laneRank.
- Worker metadata is for placement and validation, not transport/session proof.
- Do not add worker lifecycle tags.
- Do not add `PARKED_TAG`; parked is negative far-future hold plus owner
  evidence.
- Do not add `FUTURE_BAND`; use future epochSecond inside the current polarity.
- Do not add `MANUAL_DISABLED_BAND`; manual disable is same-polarity hold.
- Do not add a hold hash in the first slice.
- Do not let transport write worker score keys directly.
- Do not let positive transport evidence flip RECOVERY_RECHECK to HOT_ACQUIRE directly.
- Do not create per-task worker candidate keys.
- Do not create placement-tag score fanout in the first slice.
- Do not add broad background repair scans without a later executable design.
