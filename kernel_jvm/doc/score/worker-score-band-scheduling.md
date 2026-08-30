# Worker Score-Band Scheduling

Status: active Java Kernel Worker Score Owner contract.

Cross-pacer use of HOT leases is defined by
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md). This
document remains the owner of score encoding and transition primitives.

## Purpose

Worker score-band scheduling is the worker/resource acquisition clock.

Worker score answers one question:

```text
what TaskItem scheduling-serviceability polarity has the kernel assigned to this worker,
and is that polarity's scheduling coordinate due now?
```

It does not answer:

```text
which transport session or endpoint is currently connected?
does this physical executor expose additional parallel slots?
does this worker match a task demand?
is this worker finally selected?
why was this worker held, parked, disabled, or recovered?
```

Those remain worker-runtime validation, admission, policy, owner evidence, and
trace decisions.

Worker score intentionally does not copy task score lifecycle tags. A task is a
one-shot scheduling aggregate. A worker is a long-lived resource identity. The
worker score axis therefore expresses kernel-owned TaskItem scheduling
serviceability as an acquisition polarity, not lifecycle progression. The sign
answers whether the Worker may enter ordinary allocation or only recovery
validation. It does not mirror a socket, heartbeat, session, or endpoint state.

One `WorkerId` is one scheduler-visible execution slot and owns one score
coordinate. A physical executor with concurrency `N` exposes `N` logical
WorkerIds. The score model never mints several independent active assignments
behind one WorkerId. Business batch input belongs inside one TaskItem payload;
the score model never coalesces multiple TaskItems behind one Worker lease.

## Owner Boundary

Worker-runtime owns:

```text
worker declaration validation
worker group membership interpretation
dispatch gate interpretation
reachability interpretation for scheduling
slot admission truth
score polarity and coordinate placement
validated recovery promotion
RECOVERY_RECHECK allocation blocking
manual hold / release policy
```

Adapter and endpoint managers own local final-hop observations:

```text
endpoint/session observation
heartbeat / keepalive freshness
adapter-local consumer availability
delivery mailbox evidence
pre-execution rejection evidence
```

These observations may become evidence for a Worker score owner operation. They
must not directly write Worker score or promote a Worker into HOT_ACQUIRE. The
kernel owns the resulting scheduling-serviceability classification.

## Score Model

Worker score is a signed acquisition coordinate:

```text
timeSlot = floor(timeMillis / SLOT_MILLIS)
score = polarity * base
base = timeSlot * SLOT_FACTOR + laneRank * DIRTY_FACTOR + dirty
```

The sign is the kernel scheduling-serviceability polarity:

```text
score > 0
  HOT_ACQUIRE polarity
  scheduling-available for the ordinary allocation lane
  only candidate source for assignment-dispatch worker hot acquire

score < 0
  RECOVERY_RECHECK polarity
  scheduling-unavailable for ordinary allocation
  only candidate source for worker-runtime recovery-recheck validation

score == 0
  invalid / reserved
```

HOT_ACQUIRE does not mean immediately acquirable or physically connected. The
sign selects the ordinary-allocation versus recovery-validation lane; the
decoded `timeSlot` answers whether a HOT Worker is due, leased, held, disabled,
draining, or cooling down. Task result disposition releases an exact lease but
does not infer scheduling serviceability from the Task result class.

`abs(score)` is decoded the same way for both polarities:

```text
timeSlot = abs(score) / SLOT_FACTOR
slotRemainder = abs(score) % SLOT_FACTOR
laneRank = slotRemainder / DIRTY_FACTOR
dirty = slotRemainder % DIRTY_FACTOR
```

First-slice constants:

```text
TIME_SCALE = 10
SLOT_MILLIS = 100
DIRTY_FACTOR = 2
LANE_RANK_FACTOR = 100
SLOT_FACTOR = LANE_RANK_FACTOR * DIRTY_FACTOR
MAX_LANE_RANK = 99
MAX_DIRTY = 1
MAX_TIME_SLOT = 99_999_999_999
PAUSE_TIME_SLOT = MAX_TIME_SLOT
PAUSE_TIME_MILLIS = PAUSE_TIME_SLOT * SLOT_MILLIS
MIN_BASE = 1
```

Public kernel interfaces use millisecond timestamps. Redis score encoding uses
the internal `timeSlot`; first version uses 100ms slots. Decoded score state
returns the slot start as `timeMillis = timeSlot * SLOT_MILLIS`.

The zero coordinate is reserved because score `0` has no polarity. In normal
wall-clock use, `timeSlot` is positive. If a test or bootstrap path needs a
minimum score, use `MIN_BASE`, not `0`.

`timeSlot` means:

```text
HOT_ACQUIRE
  next time the worker may enter hot admission
  examples: slot cooldown, admission hold, occupancy interval, drain,
  manual disable, maintenance hold

RECOVERY_RECHECK
  recovery validation coordinate interpreted through a recent lookback window
  examples: failed Adapter Route serviceability checks, stale endpoint validation,
  future retry delay, too-old recovery exhausted / cold parked
```

`laneRank` is lane-local:

```text
HOT_ACQUIRE
  priority / fairness / same-slot tie-break / admission anti-spin hint

RECOVERY_RECHECK
  recovery retryCount; 0 is the first validation and each later retry increments it
```

This meaning is intentionally incompatible with older negative scores that
encoded remaining budget. Deployments must clear or rebuild those persisted
RECOVERY_RECHECK scores; the score owner does not provide a compatibility
decoder or migration path.

`dirty` is an assignment-continuation stale hint embedded in the worker score:

```text
dirty = 0
  clean relative to the current hot score lease / assignment continuation

dirty = 1
  a validation dependency used by a persisted task-worker assignment plan or
  hot score lease continuation may have changed enough to invalidate cached
  match facts; the assignment owner must revalidate before continuing
```

The dirty bit is not a metadata hash, not a counter, not a lifecycle state, not
a global worker state, and not a version. Platform policy owns the critical
scheduling signature definition. The full signature or hash lives in
worker-runtime metadata/evidence; score `dirty` only tells a real persisted
assignment continuation that its cached match / admission facts may be stale.

HOT candidate acquisition is a bounded read-only range query. It returns
`(workerId, observedScore)` pairs to the allocation pacer, which keeps each
opaque score in a private sidecar and submits an exact-score lease batch before
matching. Only lease-success Worker ids cross the matcher boundary. Concurrent
rounds may observe the same due Worker, but only one can win the
compare-and-write. Unmatched leases remain held until bounded expiry; allocation
does not release them.

Worker score is not a Worker resource mutation lease. Worker upsert
establishes the initial HOT_ACQUIRE score with `laneRank=0`, but
later Platform/Worker property or index writes,
handler-owned projections, heartbeat evidence, and diagnostics update their own
truth without acquiring or renewing worker score. HOT admission scheduling is
the only routine writer of acquired HOT scores; recovery scheduling is the only
routine writer of acquired RECOVERY_RECHECK scores. Explicit owner commands may
hold/release or perform verified polarity transitions, but cannot become a
generic score-refresh path.

Scheduling-critical metadata may include worker group membership, approved
scheduling attributes, slot admission profile, dispatch gate generation, admission
policy, or other platform-defined fields that affect worker selection or
admission. It must not include transport heartbeat, session id, latency sample,
connection id, trace fields, diagnostic details, or high-frequency load
counters.

Reason codes, reconnect source, operator notes, owner-reset policy, and
diagnostics stay in worker-runtime evidence, trace, or optional diagnostics.
Score laneRank may bound scheduling/recheck work. Dirty only marks that a
persisted assignment continuation needs metadata revalidation; it must not
become a hidden reason owner.

## Polarity Lanes

### HOT_ACQUIRE

HOT_ACQUIRE is the only worker lane assignment-dispatch may use for worker candidate
acquisition.

```text
score = +base(timeSlot, laneRank, dirty)
```

Interpretation:

```text
timeSlot < nowSlot
  due for worker hot acquisition

timeSlot >= nowSlot
  not acquired by hot acquisition

timeSlot == nowSlot
  current-slot occupied boundary; not acquired or renewed

timeSlot > nowSlot
  future-held / occupied / temporarily unavailable for hot acquisition
```

Manual disable, drain, maintenance, slot cooldown, and admission hold do not
require recovery validation. They are same-polarity HOT_ACQUIRE rewrites with a
later `timeSlot`. A hard manual hold uses:

```text
+base(PAUSE_TIME_SLOT, laneRank, dirty)
```

Release of that hold preserves polarity:

```text
+base(PAUSE_TIME_SLOT, laneRank, dirty)
  -> +base(releaseTimeSlot, laneRank, dirty)
```

### RECOVERY_RECHECK

RECOVERY_RECHECK is the recovery validation lane. It is not a worker selection lane
and must not return a selected worker handle.

```text
score = -base(timeSlot, laneRank, dirty)
```

Typical inputs:

```text
bounded-age Adapter Route evidence that the Worker is unavailable
owner-validated evidence that TaskItem serviceability is uncertain
stale endpoint observation requiring recovery validation
recoverable dispatch gate block requiring a probe
failed hot admission validation that requires recovery
```

RECOVERY_RECHECK means worker-runtime must validate owner facts before reopening hot
admission. A due RECOVERY_RECHECK candidate may:

```text
pass recovery validation
  -> move polarity to HOT_ACQUIRE after declaration, gate, Adapter evidence,
     slot admission, and policy validation

fail recovery validation and another retry is allowed
  -> a future exact retry operation keeps RECOVERY_RECHECK, advances timeSlot,
     and writes retryCount + 1

exhaust recovery / cold park
  -> stay RECOVERY_RECHECK with too-old timeSlot and owner evidence explaining
     why routine recovery no longer scans it
```

### Parked

There is no PARKED band.

Recovery exhausted / cold parked is a RECOVERY_RECHECK too-old coordinate plus
owner evidence:

```text
score = -base(coldTooOldTimeSlot, laneRank, dirty)
owner evidence = parked / owner-reset-required / recovery-exhausted / policy hold
```

This is outside hot admission and outside routine recovery-recheck due ranges,
but it does not create a third scheduling lane. Worker id remains long-lived;
score does not say the worker was deleted or terminal.

Owner reset / verified recovery can preserve the too-old coordinate when moving back
to HOT_ACQUIRE:

```text
-base(coldTooOldTimeSlot, recoveryRetryCount, dirty)
  -> +base(coldTooOldTimeSlot, 0, dirty)
```

Because HOT_ACQUIRE scans old due coordinates, the recovered worker becomes
immediately eligible after verified recovery. This is why exhausted recovery
must not be represented by far-future timeSlot.

Manual disable / drain is different:

```text
RECOVERY_RECHECK(PAUSE_TIME_SLOT)
  -> HOT_ACQUIRE(PAUSE_TIME_SLOT)
```

Preserving the far-future coordinate keeps the manual hold effective after recovery.

There is no PARKED band and no direct transport-driven parked-to-HOT_ACQUIRE
shortcut. Verified recovery / owner reset must validate owner evidence before a
polarity move writes HOT_ACQUIRE.

## Acquire Queries

Hot worker acquisition:

```text
acquire_hot_acquire_candidates(
  homeBucketId,
  hotEligibilityFloorMillis?,
  limit
)
  -> map[workerId, observedScore]

observe_due_hot_scores(
  homeBucketId,
  workerIds,
  hotEligibilityFloorMillis?
)
  -> map[dueHotWorkerId, observedScore]

acquire_pre_epoch_hot_candidates(
  homeBucketId,
  hotEligibilityFloorMillis,
  maximumScoreExclusive,
  limit
)
  -> list[(workerId, observedScore)] descending by score
```

Score range:

```text
dueTimeSlot = nowTimeSlot - 1
lower = floor is absent ? MIN_BASE : base(floorTimeSlot, 0, 0)
lower <= score <= base(dueTimeSlot, MAX_LANE_RANK, MAX_DIRTY)
```

Only positive due scores are returned and neither query modifies them. The
point form preserves the bounded caller-supplied Worker universe and is used
after ON_DEMAND_ITEM_RULE extracts request-local WorkerIds from its allocation rule.
Assignment-dispatch may pass a Worker into bounded matching only after an exact
observed-score lease succeeds.

When optional Worker Serviceability is enabled, Assignment supplies its
process-local HOT eligibility floor to both ordinary reads. The pre-epoch form
returns only positive scores in `[MIN_BASE, base(floorTimeSlot,0,0))` and belongs
exclusively to Serviceability discovery. `maximumScoreExclusive=0` starts at
the floor; otherwise the opaque score returned at the end of the previous page
is the next exclusive upper bound. Without Serviceability, ordinary reads
receive no floor and retain the original `MIN_BASE` range.

Recovery recheck acquisition:

```text
acquire_recovery_recheck_candidates(
  homeBucketId,
  maximumScoreExclusive,
  limit
)
  -> list[(workerId, observedScore)]
```

RECOVERY_RECHECK does not scan `0..now`. It scans a bounded recent window:

```text
recoveryLookbackSlots = ceil(recoveryLookbackMillis / SLOT_MILLIS)
recoveryWindowStartTimeSlot = max(
  coldParkTimeSlot + 1,
  nowTimeSlot - recoveryLookbackSlots
)
dueTimeSlot = nowTimeSlot - 1
absolute timeSlot in [recoveryWindowStartTimeSlot, dueTimeSlot]
```

Redis shape:

```text
ZREVRANGEBYSCORE key
  -base(recoveryWindowStartTimeSlot, MIN_LANE_RANK, MIN_DIRTY)
  -base(dueTimeSlot, MAX_LANE_RANK, MAX_DIRTY)
  LIMIT 0 limit
```

Both Serviceability reads return descending score pages. A zero maximum starts
a new range sweep; the last returned score becomes the next exclusive maximum.
If more than one Worker shares a truncated boundary score, the remaining tied
Workers may be skipped until the next sweep. This is an explicit best-effort
tradeoff rather than a second `(score, workerId)` cursor.

The reverse scan is intentional. RECOVERY_RECHECK scores are negative; within
the recovery window, reverse numeric order returns the oldest window coordinate
first. Scores at or newer than `nowTimeSlot` are current-slot boundary,
future retry delay, or hold coordinates and are not scanned. Scores older than
`recoveryWindowStartTimeSlot` are exhausted / cold parked and are not scanned
by routine recovery. The lower bound explicitly starts above the owner-internal
cold coordinate, so cold entries remain excluded even when the configured
lookback reaches the beginning of the valid time range.

Within the same `timeSlot`, reverse scan returns lower laneRank first because
RECOVERY_RECHECK scores are negative. Within the same `timeSlot` and laneRank,
it returns lower dirty first. Because RECOVERY_RECHECK laneRank is retryCount,
the first validation (`0`) precedes retries (`1..N`) in the same slot. Dirty is
only a stale fence and must not be used as a priority signal.

The optional Worker Serviceability Pacer performs bounded recovery discovery
only for WorkerGroups derived from the current bounded page of due
`RUNNING_VISIBLE` Tasks. It does not globally discover Groups, mutate Task
score, lease candidates, or infer serviceability from the score. It asks the
owning Adapter for a route snapshot and only the later returned evidence may
invoke a score transition. When the feature is disabled or evidence is lost,
RECOVERY_RECHECK has no wall-clock guarantee to move or park exactly when its
coordinate becomes due.

`observedScore` remains an opaque full-score fence for operations that lower or
replace a specific existing coordinate, including release, polarity move,
recovery exhaustion, and active lease renewal. Recovery recheck acquisition may
return it for those owner transitions. HOT candidate scan and due HOT lease
acquisition neither expose nor accept it. No caller should decode, construct,
or trim an observed score.

## Candidate Validation

Acquired workers are candidates only. After acquire, worker-runtime validates:

```text
worker declaration exists
worker belongs to homeBucketId / workerGroupId
worker belongs to the Task-selected WorkerGroup scheduling partition
approved scheduling metadata is current enough
if dirty == 1 and the caller is continuing from a persisted assignment plan /
hot score lease continuation, assignment owner discards / rematches or
revalidates through the allowed hot lease transition
dispatch gate permits scheduling
Adapter/serviceability evidence is acceptable by policy
the logical slot is due and admission is allowed
selection policy still wants this worker
```

Only after validation and admission may worker-runtime return a selected worker
handle to assignment-dispatch.

## Transition Rules

Worker score transitions are polarity-aware, not lifecycle-tag transitions.

Default rewrite rule:

```text
same-polarity rewrite
  preserve sign
  rewrite abs(score) coordinate
  normally require targetTimeMillis to map after currentTimeSlot
  preserve dirty by default
```

Most changes are same-polarity rewrites:

```text
slot cooldown
admission hold
manual disable / drain
maintenance hold
same-lane retry / recheck
anti-spin backoff
future recovery delay
recovery exhausted / cold parked too-old coordinate
```

Release rule:

```text
release lowers timeSlot only with exact observedScore match
release preserves polarity
release is not a reopen and not a polarity move

completed HOT release accepts only the exact ResultContext HOT lease or its
exact sign-flipped RECOVERY counterpart; it atomically restores only that
counterpart and then releases
```

Polarity move rule:

```text
HOT_ACQUIRE -> RECOVERY_RECHECK
  scheduling-serviceability demotion
  examples: newer unavailable Route evidence or failed owner validation

RECOVERY_RECHECK -> HOT_ACQUIRE
  validated recovery transition
  requires declaration, membership, gate, reachability, slot admission, and
  policy validation
```

The general `toggle_current_polarity` operation preserves `timeSlot` and dirty
while resetting the target lane to `laneRank=0`. Worker Serviceability Result
does not use that general primitive: its dedicated Evidence operation changes
only the sign and therefore preserves `timeSlot`, `laneRank`, and dirty exactly.
The two operations remain distinct because an explicit lane transition and an
Evidence polarity correction own different low-bit semantics. Dirty score
primitives are implemented; policy may invoke them only for an active
continuation that will later revalidate or renew. Raw external observation
never writes dirty.

Raw socket, heartbeat, keepalive, session, latency observation, and
`WorkerRuntime.upsert_worker` cannot move RECOVERY_RECHECK to HOT_ACQUIRE.
Upsert initializes only a missing score and preserves every existing score
exactly while replacing the Worker Properties snapshot. Only normalized
Adapter Route evidence interpreted by the Kernel Serviceability Result Policy
may reach `WorkerServiceabilityEvents`; its default event Mechanism composes
bounded WorkerGroup resolution with the Score Owner's atomic Evidence fence.
Transport and the Result Policy never call the score owner directly.

## Interface Rule

`WorkerScoreCore` exposes mechanisms that real callers need, not imagined
business strategy. A method may line up with a concrete caller workflow, but its
contract must still protect score ownership instead of becoming a business
event API.

Allowed caller-owned inputs:

```text
homeBucketId
workerId
limit
observedScore returned by acquire/read when exact CAS is required
targetTimeMillis when caller legitimately chooses a next visible/held time
targetLaneRank when a same-polarity caller legitimately chooses HOT priority or
RECOVERY retryCount
```

Not caller-owned inputs:

```text
score range min/max
scan window bounds
cold/exhausted coordinate
polarity sign or encoded tag
dirty bit
base / SLOT_FACTOR / DIRTY_FACTOR coordinates
reason encoded into laneRank
fake source/event names for unimplemented workflows
```

The kernel may internally mint score coordinates such as cold exhausted coordinate,
recovery scan ranges, dirty transitions, and sign/base encoding. Expose those
only after a real owner object or caller workflow proves why the caller can own
the value.

## Score Primitives

Worker-score primitives are intentionally small.

### Current Same-Polarity Rewrite

Admission/recheck rounds, cooldown, manual hold, drain, maintenance, and policy
hold all use the same current-read monotonic same-polarity rewrite:

```text
rewrite_current_scores(
  homeBucketId,
  workerIds,
  targetTimeMillis,
  targetLaneRank?
)
```

Rules:

```text
targetTimeSlot = floor(targetTimeMillis / SLOT_MILLIS)
storedScore must exist and be signed non-zero
targetTimeMillis must be valid
targetTimeSlot must be greater than stored timeSlot
targetPolarity = stored polarity
targetLaneRank defaults to stored laneRank
targetDirty = stored dirty
write signed score(storedPolarity, targetTimeSlot, targetLaneRank, targetDirty)
```

This primitive does not require `observedScore`. It only writes within the
currently stored polarity and rejects lower timeSlot. Stale callers may lose
freshness, but they cannot lower or rewrite the same time coordinate, clear
dirty, or cross polarity.

Typical uses:

```text
slot contention / cooldown -> HOT_ACQUIRE with future timeSlot
admission hold -> HOT_ACQUIRE with future timeSlot
manual disable / drain observed as HOT_ACQUIRE -> HOT_ACQUIRE(PAUSE_TIME_SLOT)
manual disable / drain observed as RECOVERY_RECHECK -> RECOVERY_RECHECK(PAUSE_TIME_SLOT)
future recovery retry -> RECOVERY_RECHECK with later time and retryCount + 1
```

This primitive cannot change HOT_ACQUIRE to RECOVERY_RECHECK or
RECOVERY_RECHECK to HOT_ACQUIRE. The default Worker Serviceability event Mechanism
uses it only after an exact polarity transition or while advancing one observed
RECOVERY retry; it does not turn rewrite into a cross-polarity operation.

### Release

Release / enable is the only ordinary operation allowed to lower `timeSlot`.
It must use exact observed-score protection:

```text
release_score_holds(homeBucketId, observedScores, releaseTimeMillis)
release_completed_hot_score_holds(
  homeBucketId,
  observedHotScores,
  releaseTimeMillis
)
```

Rules:

```text
releaseTimeSlot = floor(releaseTimeMillis / SLOT_MILLIS)
storedScore must equal observedScore
observedScore must be non-zero
currentSlotStartMillis <= releaseTimeMillis
abs(releaseSlotBase) < abs(observedScore)
targetLaneRank = observed laneRank
targetDirty = observed dirty
write observed polarity with releaseTimeSlot, targetLaneRank, and targetDirty
```

The implementation compares the owner-minted release slot base directly with
the opaque observed score and preserves its low bits; it does not decode an
observed lease time or need a business-level polarity branch. A release in the
current score-clock slot is valid. A time before the current slot start is
rejected so release cannot accidentally act as recovery cold parking.

Release does not reopen a worker:

```text
+base(PAUSE_TIME_SLOT, laneRank, dirty)
  -> +base(releaseTimeSlot, laneRank, dirty)

-base(PAUSE_TIME_SLOT, laneRank, dirty)
  -> -base(releaseTimeSlot, laneRank, dirty)
```

If the released score is RECOVERY_RECHECK, the worker still has to pass recovery
validation before returning to HOT_ACQUIRE acquisition.

The completed-HOT variant is deliberately narrower. Each input is the original
positive HOT lease from `ResultContext`; one per-Worker Lua operation accepts
only that exact score or the exact negative score produced when Serviceability
Evidence flips only that lease's polarity. The latter is restored to HOT and
released atomically. Other
RECOVERY coordinates, a newer lease, dirty drift, pause, or a missing score are
`STALE`. Result Routing never decodes or constructs the counterpart.

If owner evidence says a held worker is owner-reset-required, release also
requires owner reset authorization. That authorization is not encoded in the
score; it belongs to worker-runtime owner evidence and must be checked before
writing the release.

### Polarity Move

Polarity move is an owner-validated scheduling-serviceability transition, not release and
not renew:

```text
toggle_current_polarity(
  homeBucketId,
  workerId,
  observedScore
)
```

Rules:

```text
storedScore must equal observedScore
observedScore must decode to HOT_ACQUIRE or RECOVERY_RECHECK
target polarity is opposite of stored polarity
targetTimeSlot = stored timeSlot
targetLaneRank = 0
targetDirty = stored dirty
write signed score(targetPolarity, storedTimeSlot, 0, storedDirty)
```

Use HOT_ACQUIRE -> RECOVERY_RECHECK for owner-validated evidence that the Worker
must leave ordinary TaskItem scheduling. Use RECOVERY_RECHECK -> HOT_ACQUIRE only
after validated reconnect or recovery evidence. Do not use release for polarity
moves.

Polarity move preserves `timeSlot` on purpose:

```text
disabled / held HOT_ACQUIRE future score
  -> RECOVERY_RECHECK with the same future coordinate, still not routinely scanned

RECOVERY_RECHECK too-old exhausted score
  -> HOT_ACQUIRE with the same old coordinate, immediately due after verified recovery
```

Polarity move uses full `observedScore` CAS. If any coordinate has changed, the
operation is stale and must not toggle again. The target preserves timeSlot and
dirty, resets laneRank to zero, and uses `observedScore` only as the stale
fence.

### Worker Serviceability Probe Hold

Serviceability Dispatch advances the check coordinate before publishing a
best-effort Probe request. Both batch operations use one WorkerGroup Score key,
one Redis `TIME`, and exact observed-score comparison:

```text
hold_observed_hot_for_serviceability_probes
  exact HOT -> RECOVERY(redisNowSlot, laneRank=0, preserve dirty)

advance_observed_recovery_rechecks
  exact RECOVERY(rank=n)
    -> RECOVERY(redisNowSlot, laneRank=n+1, preserve dirty)
```

Only `TRANSITIONED` Workers may be offered to the Adapter Probe HASH. A stale
observation cannot advance a newer lease, hold, pause, or check. Probe offer
loss is not rolled back: the updated RECOVERY coordinate becomes the source of
the next bounded retry scan.

### Worker Serviceability Evidence Polarity

Adapter Evidence applies one target polarity through a same-key batch Lua. For
each Worker, with all times reduced to the 100ms score slot, Evidence is valid
only when:

```text
storedTimeSlot > redisNowSlot
OR storedTimeSlot <= evidenceTimeSlot
```

The first branch permits an observed Route fact to correct the polarity of a
future lease, hold, or pause without lowering that coordinate. The second is
the normal non-future freshness fence and deliberately accepts the same slot.
When valid, the owner changes only the sign; `timeSlot`, `laneRank`, and dirty
remain byte-for-byte represented by the same absolute score. A score already
at the target polarity is `NOOP`; a newer non-future coordinate makes older
Evidence `STALE`.

The Result path therefore owns no HOT-floor rewrite, retry increment, cold
park, or PAUSE exception. Dispatch owns check timing and retry progression;
Evidence owns only the observed target polarity.

### Recovery Exhausted / Cold Park

Recovery exhausted is a RECOVERY_RECHECK same-polarity operation that writes a
too-old coordinate, not a far-future hold:

```text
exhaust_recovery_recheck(
  homeBucketId,
  workerId,
  observedScore,
  maxRecoveryAttempts
)
```

Rules:

```text
storedScore must equal observedScore
stored polarity must be RECOVERY_RECHECK
coldParkTimeSlot is the fixed owner-internal near-zero valid time coordinate
routine recovery ranges always start above coldParkTimeSlot
targetLaneRank = maxRecoveryAttempts
targetDirty = stored dirty
write RECOVERY_RECHECK(coldParkTimeSlot, targetLaneRank, targetDirty)
```

This removes the worker from routine RECOVERY_RECHECK scans without converting
it into a far-future hold. If owner reset or verified recovery later moves it
back to HOT_ACQUIRE, the old coordinate is preserved and the worker becomes
immediately due in HOT_ACQUIRE.

### Dirty Lease Fence

Worker scheduling metadata can change without any connection observation. Worker-runtime
owns a platform-defined scheduling signature over critical worker fields. The
signature definition is built into platform policy, not supplied by external
events or arbitrary business callers.

The score encoding and Redis primitives for mark, lease-clear, stale renewal,
polarity preservation, cold park, and exact release are implemented. Dirty is
useful only when it protects a real scheduling continuation object:

```text
idle / no persisted reservation:
  metadata update does not need a score dirty write; the next scheduling
  candidate validation reads current metadata

already dispatched work is running:
  do not interrupt through dirty; wait for result / timeout / lease expiry
  path and validate current metadata before the next assignment

persisted task-worker assignment plan / hot score lease continuation:
  metadata update commits independently, then may mark dirty = 1 so that the assignment owner cannot
  continue from cached candidate facts without revalidation
```

Dirty marking is an additional bounded stale-fence write, never a precondition
for the metadata update. A stale, failed, or skipped dirty mark must not reject,
roll back, or delay resource truth. If that best-effort rule is insufficient for
a named continuation invariant, the continuation owner needs a stronger
owner-local protocol; the resource update still must not acquire the worker
score as a global lock.

If the runtime has no persisted assignment plan or active score-lease
continuation, there is no dirty consumer to protect. Do not invoke the marker or
invent a score lease merely because the bit exists. The remaining deferred
work is owner policy and end-to-end continuation use, not score representation
or Redis primitive implementation.

`WorkerScoreCore` does not expose a generic dirty clear method. It exposes one
bounded HOT observation query, one observed-score lease batch, one active
renewal primitive, and one dirty marker:

```text
mark_current_lease_dirty(homeBucketId, workerId)
  reads the current score
  if dirty == 0, writes dirty = 1
  preserves polarity, timeSlot, and laneRank
  already-dirty scores are no-op
  applies to both due and future scores

acquire_hot_acquire_candidates(homeBucketId, hotEligibilityFloorMillis?, limit)
  reads positive due HOT_ACQUIRE scores at or above the optional floor
  returns at most limit workerId -> observedScore entries
  does not expose score order as a caller contract
  does not mutate score

observe_due_hot_scores(homeBucketId, workerIds, hotEligibilityFloorMillis?)
  reads only the supplied bounded Worker ids
  returns only currently due HOT_ACQUIRE scores at or above the optional floor
  does not mutate score

acquire_pre_epoch_hot_candidates(
  homeBucketId, hotEligibilityFloorMillis, maximumScoreExclusive, limit
)
  reads positive HOT_ACQUIRE scores strictly below the floor
  returns a descending opaque-score page for a scalar best-effort sweep
  belongs to Serviceability discovery, never ordinary Assignment
  does not mutate score

acquire_observed_hot_score_leases(
  homeBucketId, observedScores, targetTimeMillis
)
  each observedScore must decode to a due HOT_ACQUIRE score
  targetTimeSlot must be after nowTimeSlot
  independently writes HOT_ACQUIRE(targetTimeSlot, observed laneRank, dirty=0)
  each generic CAS requires storedScore == observedScore

renew_active_hot_score_leases(homeBucketId, observedScores, targetTimeMillis)
  each observedScore must decode to HOT_ACQUIRE
  each storedScore must equal its observedScore
  each observed timeSlot must be >= nowSlot
  each observed dirty must be 0
  targetTimeMillis must describe a future slot
  if the observed lease already covers targetTimeSlot, exact validation returns
    NOOP plus the observed score
  otherwise independently writes HOT_ACQUIRE(targetTimeSlot, observed laneRank, dirty=0)
  dirty entries return STALE and caller must discard / rematch

```

These batch APIs operate on one WorkerGroup/ZSET key and shared target
parameters. Redis pipelines the existing single-Worker Lua primitive; the
pipeline reduces round trips but does not create cross-Worker atomicity.

RECOVERY_RECHECK scores must not pass either hot score lease primitive. Recovery
validation must first move the worker back to HOT_ACQUIRE through owner-validated
polarity transition.

Dirty clear is only available as part of a hot score lease transition. There is
no standalone `clear_dirty` operation. A future-held score may become dirty
while an assignment plan exists; active lease renewal must treat dirty as STALE,
discard the plan, and force a fresh worker match. If no owner is occupying or
extending that score, the dirty bit has no independent scheduling meaning.

Typical signature inputs:

```text
worker group membership
approved scheduling attributes
slot admission profile id
dispatch gate generation
admission policy id
placement / home-bucket fields that affect candidate validity
```

Excluded inputs:

```text
heartbeat
session id
connection id
latency sample
trace / diagnostic fields
high-frequency load counters
display-only worker fields
```

When platform-owned scheduling-critical metadata changes while a persisted
task-worker assignment plan / hot score lease continuation exists:

```text
stored signature hash changes in worker metadata/evidence
non-lease owner may only set score dirty = 1
polarity is preserved
laneRank is preserved
timeSlot is preserved unless the owner policy also writes an allowed hold
```

Dirty clear is stricter:

```text
only successful observed-score lease CAS may write dirty = 0 through
`acquire_observed_hot_score_leases`
active hot lease renewal must not clear dirty; dirty active renewal returns
STALE and forces a fresh match
the allocation pacer batch-leases before matcher validation; a relevant
metadata change during or after matching may mark the future-held score dirty
non-lease owners must never clear dirty
```

If dirty is already `1`, further metadata changes keep it at `1`. Dirty is an
assignment-continuation stale hint, not a change counter. The metadata
signature/hash is the fact that prevents incorrectly clearing newer changes.

Dirty should be written only when all of these are true:

```text
1. a task-worker assignment plan or hot score lease continuation still exists
2. the changed field is part of that continuation's validationDependencySet
3. the changed value invalidates, or may invalidate, the recorded
   candidate constraint / matcher validation evidence
```

If the changed attribute is not referenced by the continuation's
candidate `allocation_rule`, matcher validation, group membership check, gate,
slot admission profile, or other owner-approved validation dependency, it should not
mark dirty. If the worker is already executing dispatched work and the hot score
lease remains active, dirty should not interrupt execution; result, timeout,
lease expiry, and the next scheduling round handle the new facts.

`validationDependencySet` is conceptual first-slice evidence, not a public DTO
and not a new interface. It records which Worker Properties / indexed projections /
policy facts were used to validate the match so an attribute update handler can
decide whether dirty is necessary. If an implementation cannot cheaply prove a
changed dependency still satisfies the recorded query, it may conservatively
mark dirty. If it can prove the dependency remains valid, no dirty write is
needed.

## Transition Matrix

| Current polarity | Validated outcome | Target score | Rule |
| --- | --- | --- | --- |
| HOT_ACQUIRE | candidate remains usable and no delay is needed | no rewrite, or HOT_ACQUIRE(nextTime, laneRank, dirty) | same polarity |
| HOT_ACQUIRE | slot contention / cooldown / claim interval | HOT_ACQUIRE(nextTime, laneRank, dirty) | nextTimeSlot >= currentTimeSlot |
| HOT_ACQUIRE | manual disable / drain / maintenance hold | HOT_ACQUIRE(PAUSE_TIME_SLOT, laneRank, dirty) | same polarity hold |
| HOT_ACQUIRE | Adapter rejection result | exact lease release, polarity preserved | complete observed-score CAS |
| HOT_ACQUIRE | bounded-age Adapter Route evidence says unavailable, or delivery expires before start | RECOVERY_RECHECK(sameTime, 0, dirty) | exact observed-score CAS; evidence time is not a score fence |
| RECOVERY_RECHECK | recovery validation passes | HOT_ACQUIRE(sameTime, 0, dirty) | owner-validated polarity move |
| RECOVERY_RECHECK | recovery validation fails and retry remains | RECOVERY_RECHECK(nextRecheckTime, retryCount + 1, dirty) | future exact retry operation; not implemented in this slice |
| RECOVERY_RECHECK | recovery exhausted / cold parked | RECOVERY_RECHECK(coldTooOldTime, laneRank, dirty) | same polarity cold park + owner evidence |
| RECOVERY_RECHECK | owner hold / disabled / drain / maintenance | RECOVERY_RECHECK(PAUSE_TIME_SLOT, laneRank, dirty) | same polarity hold + owner evidence |

There is no PARKED row because PARKED is not a polarity or band. It is owner
evidence attached to a RECOVERY_RECHECK too-old cold coordinate or a policy
hold, depending on owner reason.

## Assignment-Dispatch Protocol

Worker score-band participates in assignment-dispatch like this:

```text
task score acquires due task candidate
assignment-dispatch builds WorkerCandidateRequests from the WorkerAllocationMechanism-owned rule location
Task-rule precomputation scans HOT; Item-rule on-demand acquisition uses one
bounded Group HOT query for `{}` or point-observes rule-supplied Worker ids
candidate acquirer exact-CAS leases unchanged due Workers and fully rematches
allocation pacer may publish Task-rule results into CandidateWorkerCache
cached candidate renewal exact-validates/renews and rematches Task rules
Task dispatch resolves the WorkerAllocationMechanism acquisition path
and keeps CandidateId-to-Item bindings
TaskRuntime loads the selected TaskItem record
TaskItemScoreBandCore claims the observed Item score
transport receives already-selected worker dispatch
worker-runtime rewrites worker scores only through owner/admission rules
```

Worker score-band does not:

```text
read TaskItem records
claim Item score
select transport route
inspect adapter connections directly
write task score
create or own Worker candidate cache; transient `CandidateWorkerCache` ZSETs
belong to assignment-dispatch
```

RECOVERY_RECHECK acquisition is a worker-runtime recovery validation path, not an
assignment-dispatch worker selection path.

## Input Write Taxonomy

| Input kind | May write worker score? | Required path |
| --- | --- | --- |
| hot candidate observation | no | bounded due range read with scores |
| hot Worker allocation lease | yes | exact observed-score CAS before bounded matching |
| recovery-recheck validation round | yes | same-polarity rewrite / polarity move / cold park |
| slot contention / cooldown | yes | same-polarity HOT_ACQUIRE rewrite |
| manual disable / drain / maintenance | yes | same-polarity hold |
| manual enable / release | yes | exact observed-score same-polarity release |
| platform scheduling metadata signature changed while persisted task-worker assignment plan / hot score lease continuation exists | yes | `mark_current_lease_dirty` may only set dirty = 1 |
| platform scheduling metadata signature changed while no persisted assignment plan / hot score lease continuation exists | no score write required | metadata/evidence only; next candidate validation reads current metadata |
| Worker upsert during Server Prepare | only when score is missing | initialize HOT_ACQUIRE laneRank=0, dirty=0; replace Worker Properties and preserve every existing score exactly |
| assignment owner leases due HOT_ACQUIRE observations | yes | `acquire_observed_hot_score_leases` pipelines independent exact-CAS writes, future leases, and dirty clear before matching |
| assignment owner extends active clean HOT_ACQUIRE leases | yes | `renew_active_hot_score_leases`; dirty entries return STALE and force rematch |
| trusted Adapter evidence that execution was not entered | yes | exact release of the correlated Worker lease fence; no online inference |
| bounded-age Adapter Route evidence | yes | Adapter Evidence Batch policy orders within one batch, then uses current-score read plus exact CAS; there is no cross-batch evidence fence |
| recovery exhausted / cold parked | yes | RECOVERY_RECHECK too-old cold coordinate + owner evidence |
| transport heartbeat / keepalive | no | evidence only |
| raw socket/session observation | no | local observation only; only the Adapter's exact verified Route transition becomes scheduling evidence |
| trusted Worker execution result (`200` or Worker-owned `3...`) | yes | exact release of the correlated Worker lease fence |
| trusted Adapter pre-execution rejection (`COMMAND_EXPIRED` for command expiry) | yes | exact release of the correlated Worker lease fence, preserving polarity |
| task finality without a correlated Worker result | no | Task/Item owner movement only |
| read projection / trace | no | diagnostics only |

## Atomicity Boundaries

Use atomic write/CAS where stale intermediate state would allow wrong admission:

```text
due HOT allocation lease:
  scan may interleave with other rounds; one point CAS atomically requires
  storedScore == observedScore before the Worker enters matching

time-lowering operations:
  score CAS with complete signed observedScore

manual disable / drain / maintenance:
  owner gate fact and same-polarity hold score write

trusted serviceability evidence:
  stored score time must be older than Adapter observed time before polarity move

validated recovery:
  validated owner facts and RECOVERY_RECHECK -> HOT_ACQUIRE polarity move

pause release / enable:
  exact observed-score release preserving polarity
```

Do not add a broad distributed lock around worker scheduling by default. The
runtime transition itself carries the concurrency boundary.

## Failure And Stale Handling

Score is an index, so stale candidates are normal.

Rules:

```text
score due but worker declaration missing
  reject candidate; clean opportunistically only when the index is a confirmed
  orphan, otherwise write RECOVERY_RECHECK hold by owner policy

score due but worker disabled/draining
  stale candidate or owner mismatch; rewrite same polarity to far-future hold
  if the owner hold fact is current

HOT_ACQUIRE score due but serviceability validation fails strongly
  move to RECOVERY_RECHECK laneRank=0 by owner policy, preserving timeSlot/dirty

RECOVERY_RECHECK score due but recovery validation fails
  a future exact retry operation writes next time and retryCount+1 if allowed,
  or writes a RECOVERY_RECHECK too-old cold coordinate if exhausted

score due but slot admission defers use
  rewrite HOT_ACQUIRE with future time or reject according to admission policy

stale lease renew / observed-score polarity move
  return STALE / no-op; do not overwrite newer score

raw connect/session observation
  never moves RECOVERY_RECHECK to HOT_ACQUIRE directly
```

Stale handling must be bounded. Do not scan all workers to repair score.
Score absence is not RECOVERY_RECHECK. It may be used only for absent
resources or confirmed orphan cleanup, not as the ordinary way to hold, park,
disable, or demote a long-lived worker id.

## Mechanism And Deferred Policy

Mechanism owns:

```text
signed score encoding
positive hot acquire range
negative recovery-recheck acquire range
observed-score stale fence for active renewal, lowering, and polarity moves
dirty bit mark / hot lease clear / stale-renew protocol
same-polarity release
owner-validated polarity move boundary preserving timeSlot
RECOVERY_RECHECK lookback-window acquisition
RECOVERY_RECHECK cold-too-old exhausted coordinate
home bucket score key
no transport-driven positive refresh
```

Policy owns:

```text
initial HOT_ACQUIRE score time coordinate; laneRank is fixed at 0
candidate ranking / laneRank meaning
platform scheduling signature policy
dirty mark invocation and continuation revalidation rule, only when a
persisted assignment continuation exists
cooldown duration
admission hold interval
manual hold / enable rule
RECOVERY_RECHECK retry cadence
RECOVERY_RECHECK maximum retry policy
RECOVERY_RECHECK lookback window
cold parked / owner-reset evidence rule
negative evidence mapping
slot contention delay
verified reopen policy
```

## First-Version Non-Goals

Do not include these in the first worker-score version:

```text
lifecycle-like worker tags
PARKED band
independent FUTURE_BAND
independent MANUAL_DISABLED_BAND
worker hold hash
WorkerHoldState
transition Redis stream
per-task worker candidate keys owned by WorkerScoreCore
placement-tag score fanout
transport/session evidence in worker metadata
HOT_ACQUIRE laneRank as failure retry truth
score laneRank as reason / reconnect source truth
background worker-wide repair scan
slot registry redesign
```

## Guardrails

- Do not use score absence as worker lifecycle proof.
- Do not let transport heartbeat, session keepalive, or raw connected events
  write HOT_ACQUIRE.
- Do not add worker lifecycle tags. Sign is acquisition polarity; abs(score) is
  the time/laneRank coordinate.
- Do not add a PARKED band. Recovery exhausted / cold parked is a
  RECOVERY_RECHECK too-old coordinate plus owner evidence; manual disable /
  drain / maintenance holds may still use far-future timeSlot.
- Do not add `MANUAL_DISABLED_BAND`; manual disable is same-polarity hold.
- Do not add a per-Worker capacity pool beside score. One WorkerId is one
  execution slot; physical concurrency is multiple logical WorkerIds.
- Do not release an active Worker lease early to simulate immediate slot reuse
  or assign independent Items concurrently to one WorkerId.
- Worker Candidate Selection Policy may call bounded HOT observation and exact
  lease operations directly. A raw score may be retained, associated by
  WorkerId, exact-compared and returned to this Owner; Policy and Matcher must
  not decode, construct, print or calculate score coordinates.
- Where an operation explicitly requires `observedScore`, do not trim it to
  time/laneRank/dirty. It remains the full signed score and callers must not
  construct or decode it.
- Do not expose kernel-owned encoding details as public parameters: scan range,
  cold coordinate, polarity sign, dirty bit, base, or factor constants.
- Do not add fake business strategy knobs to score-core methods before a real
  caller workflow owns the value.
- Do not set dirty bit directly from a hash modulo. Store the full scheduling
  signature/hash in worker-runtime metadata or evidence and use score dirty only
  as a one-bit revalidation flag.
- Do not let heartbeat, session refresh, trace, diagnostics, or display-only
  metadata bump dirty bit.
- Do not invent a score lease just to justify dirty. Dirty only has a consumer
  if a real persisted assignment plan / hot score lease continuation exists.
- Do not let non-lease owners clear dirty. A successful exact observed-score HOT
  lease may clear dirty before matching; active renewal must return STALE on dirty.
- Do not use RECOVERY_RECHECK scores as assignment leases. Recovery validation
  must move the worker back to HOT_ACQUIRE before any hot score lease primitive
  can run.
- A read-only HOT due observation may precede a lease write, but the write must
  use exact observed-score CAS. Never write a future lease from WorkerId or due
  membership alone.
- Do not create per-task candidate keys inside WorkerScoreCore.
  CandidateWorkerCache is the separate owner of candidate-scoped keys.
- Do not fan out score across placement-tag buckets in the first slice.
- Do not store transport/session evidence in worker scheduling metadata.
- Do not let read projections or trace materialization drive worker score.
- Do not force task lifecycle score semantics onto worker-runtime polarity.
