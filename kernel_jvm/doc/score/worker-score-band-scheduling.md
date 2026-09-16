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

`WorkerScoreCore` owns legal score encoding, exact fences and coordinate
transitions. `WorkerResourceCatalog` owns scheduling identity/Group metadata and the
current Endpoint address; registration does not perform Endpoint migration.
[Worker Matching](../../../worker_matching_jvm/README.md)
owns Properties and Rule interpretation. Production allocation, dispatch and
Serviceability policies live in `kernel_pacer_jvm`.

Adapter connection owners retain verified Route truth and produce bounded
observation evidence. [Serviceability Policy](../../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
interprets that evidence and requests named semantic Owner transitions. Neither
Adapter nor Server writes Score or promotes a Worker into HOT directly.

Generic hold/release primitives do not imply a separate current pause, drain,
slot-admission or reset-state registry. Their production callers and policy
limits remain defined by the linked Pacer documents.

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

Encoding constants:

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
  next eligible recheck time, selected through a recent lookback window
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

`dirty` fences consumption of the current initial hold:

```text
dirty = 0
  current initial hold candidate eligibility is available

dirty = 1
  candidate eligibility has been invalidated or consumed by confirmation
```

The dirty bit is not a metadata hash, not a counter, not a lifecycle state, not
a global worker state, and not a version. Worker Matching facts do not write
Worker score. Server requests bounded dirty invalidation after actual Worker or
Platform facts changes; final assignment confirmation also sets dirty=1.

HOT candidate acquisition is a bounded read-only range query. It returns
`(workerId, observedScore)` pairs to Pacer. Production refill always uses the
bounded Group range; explicit-ID queries do not initiate point acquisition.
Pacer exact-acquires those observations before Matching reads the successful
held IDs for qualification. Matching retains their original 1-second deadlines
and returned scores as opaque fences.
Concurrent rounds may observe the same due
Worker, but only one exact compare-and-write succeeds.

Worker score is not a Worker resource mutation lease. Worker registration
initializes only missing members at the fixed cold RECOVERY_RECHECK coordinate
`timeSlot=1, laneRank=0, dirty=0` (currently -200), but
later Platform/Worker Properties writes,
handler-owned projections, heartbeat evidence, and diagnostics update their own
truth without acquiring or renewing worker score. HOT admission scheduling is
the only routine writer of acquired HOT scores; recovery scheduling is the only
routine writer of acquired RECOVERY_RECHECK scores. Explicit owner commands may
hold/release or perform verified polarity transitions, but cannot become a
generic score-refresh path.

Rule and Properties interpretation belongs to Worker Matching. Score remains a
separate scheduling coordinate and does not mirror those facts.
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
  current-slot occupied boundary; no due acquisition
  exact clean active holds may still confirm in this slot

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

Validated available evidence refreshes an old cold coordinate to its observed
time while preserving rank and dirty when moving back to HOT_ACQUIRE:

```text
-base(coldTooOldTimeSlot, recoveryRetryCount, dirty)
  -> +base(evidenceTimeSlot, recoveryRetryCount, dirty)
```

The refreshed coordinate becomes due in a later slot and can cross the
optional process HOT floor after verified recovery. This is why exhausted recovery
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

Due HOT candidate observation:

```text
observe_due_hot_score_candidates(
  workerGroupId,
  hotEligibilityFloorMillis?,
  limit
)
  -> immutable map[dueHotWorkerId, observedScore], ascending by score/member

acquire_hot_candidates_before(
  homeBucketId,
  hotCutoffMillis,
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

Only positive due scores are returned by the due-head operation. Both reads
leave scores unchanged. Pacer uses the Group range for every Rule, exact-acquires a 1-second lease for the
observed batch, and supplies only successful returned fences to Matching. Matching
qualifies that closed held batch and retains its original deadlines. Dispatch
consumes those inventory fences and exact-confirms execution.
The Score Owner never interprets selectors; Matching cannot initiate observation.

**Core scheduling change: each Group observation starts at the due range head;
Pacer no longer carries a within-Group rank offset.** One read-only Lua uses Redis
TIME for the due upper bound and one ZRANGE BYSCORE LIMIT 0 limit WITHSCORES. There
are no range counts or wrap calculations. Limit is 1..100 raw rows; the returned
Map is immutable and ordered by Score, then Redis member order for equal scores.

Successful acquisition advances each candidate to a future time before Matching,
so even unmatched candidates leave the due head. On expiry they retain their newer
Score, behind older unacquired Workers. Read-only observations alone do not progress.
Corrupt rows are filtered within the raw budget, with no replacement read or write.
A fully corrupt head returns empty and has no automatic bypass guarantee. Concurrent
changes remain subject to exact acquisition; no stable snapshot is promised.

When optional periodic Worker Serviceability is enabled, Assignment supplies its
process-local HOT eligibility floor to both ordinary reads. The bounded
Serviceability form returns only positive scores in
`[MIN_BASE, base(hotCutoffTimeSlot,0,0))`. Its production caller supplies the
later of that process floor and a stale-HOT compensation threshold.
Every call starts at that range head with a bounded limit and no continuation
score. Successful exact holds move Workers out of the range.
Without periodic Serviceability, ordinary reads receive no floor and retain the original
`MIN_BASE` range.

Recovery recheck acquisition:

```text
acquire_recovery_recheck_candidates(
  homeBucketId,
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

Both Serviceability reads return bounded descending score heads with
`LIMIT 0 limit`. They retain no continuation score. Successful holds schedule the next
check outside the due range, allowing later reads to reach remaining equal-score
members without a tie-skipping sweep. Read-only or rejected observations do not
move the head and do not create an automatic bypass or data-repair guarantee.

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
Score Owner to schedule the next recheck before offering an Adapter route snapshot.
Later returned evidence may correct polarity while preserving a current/future
time. When the feature is disabled or evidence is lost,
RECOVERY_RECHECK has no wall-clock guarantee to move or park exactly when its
coordinate becomes due.

`observedScore` remains an opaque full-score fence for operations that lower or
replace a specific existing coordinate, including release, polarity move,
recovery exhaustion, and active hold confirmation. Recovery recheck acquisition
returns it for owner transitions. HOT due observation returns the exact score
and initial acquisition must submit it unchanged. No caller should decode, construct,
or trim an observed score.

## Candidate Validation

Matching Evidence is only a bounded identity proposal. Kernel validates:

```text
Evidence WorkerGroup equals the Task-selected WorkerGroup
current Worker score is the exact clean HOT hold named by Evidence
priority and round uniqueness still select this WorkerId
minimal Kernel Worker descriptor still exists in that WorkerGroup
```

Only after those checks may selection return an endpoint-bearing candidate to
assignment-dispatch. Kernel does not reload or reinterpret Properties after
the hold; Evidence expiry and exact Score hold confirmation are the accepted
stale-snapshot boundary in this cut.

## Registration Members

`initializeRegisteredScores(group, workerIds)` creates missing members in one
bounded Lua using ZADD NX and returns newly created IDs. No TIME or Score
confirmation read participates. Existing values, including invalid/reserved
values, leases, dirty values and PAUSE, are never overwritten by registration.
The cold initial negative coordinate is excluded from ordinary HOT allocation,
stale-HOT probes and recovery rechecks, regardless of preset. Score membership
is the registration source; it is not online evidence.

Only later admitted network evidence can request activation through the finite
Worker Serviceability mechanism. Evidence loss leaves the member cold until a
new valid observation; no activation ACK, replay or cold scan is installed.
`sampleRegisteredWorkerIds` accepts `1..1000` and returns at most 1000 member IDs without exposing or
interpreting their scheduling coordinates in Catalog.

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
does not use that general primitive. Its dedicated Evidence operation preserves
`laneRank` and dirty; unavailable Evidence changes only the sign, while accepted
connected Evidence advances an older past-slot coordinate to the Evidence slot.
Current/future coordinates and PAUSE keep their exact coordinate. The two operations remain
distinct because an explicit lane transition and a serviceability correction
own different time and low-bit semantics. Dirty score
primitives invalidate candidate eligibility after APPLIED facts writes and
consume it at final confirmation. Raw external observations cannot write Score;
Server validates and writes facts before requesting best-effort invalidation.

Raw socket, heartbeat, keepalive, session, latency observation, and
`WorkerResourceCatalog.registerWorkers` cannot move RECOVERY_RECHECK to HOT_ACQUIRE.
Registration initializes only a missing score and preserves every existing score
exactly. Worker Properties replacement belongs to Worker Matching after validated
Adapter observation; Prepare does not write facts. Kernel WorkerResourceCatalog
stores only minimal identity/Group/Endpoint metadata. Only normalized
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

## Java Composition and Fixed Atomic Operations

The convergence implementation retains `RedisWorkerScoreCore` as the connection,
Key and lifecycle owner. Package-private `WorkerScoreEncoding` centralizes
validation, decoding, range arithmetic and field replacement. Pacer, Matching and
Server cannot construct Score coordinates through it. No Store layer or generic
rule/patch language is involved.

For absolute score `a` and sign `p`, Java reuses these field operations:

```text
replace time:     p * (targetSlot * 200 + a % 200)
replace rank:     p * (a - a % 200 + targetRank * 2 + a % 2)
replace dirty:    p * (a - a % 2 + targetDirty)
replace polarity: targetPolarity * a
```

Public methods validate inputs and compose private Redis operations. Known
observations produce complete targets in Java. Only reads of the current member,
execution-time checks, relative time and current-value field changes remain in Lua.

| Fixed operation | Atomic work |
| --- | --- |
| Initialize absent | NX with one Java-prepared cold Score; return actual new IDs |
| Exact replace | Read once; accept the original or the Java-supplied exact counterpart; write a complete target |
| Due exact replace | Reject expired requested target, exact-compare and require due before writing |
| Active exact replace | Reject expired requested target, exact-compare, require clean/current-or-future/non-PAUSE |
| Due relative deferral | Exact-compare, validate execution-time target and due, write Redis now plus delay with supplied low bits |
| Current time advance | Advance only an earlier absolute coordinate; preserve sign and dirty, optionally replace rank |
| Current dirty | Set dirty=1 atomically; preserve all other fields and never create |
| Current polarity correction | Apply the fixed supplied-time fence and optional past-time refresh |

The fixed scripts share exact read/compare, write-if-changed and batch-result
functions. They have no business-operation mode or runtime-generated conditions.
The fixed entries retain their original result conventions, including raw Score
text echoes for deferral rejection and unchanged polarity correction; corrupt
fractional echoes still fail integer parsing instead of silently truncating.
Ordinary exact replacement returns NOOP for an accepted equal target. The observed
HOT release maps that accepted NOOP to TRANSITIONED in Java, preserving its existing
completion semantics. It allows only the supplied original and exact negative,
never a general list of alternatives.

Read primitives retain each path's original range, ordering and raw-row budget.
Batch writes remain at most 100 same-Group identities per script; lease operations
still split larger inputs, while release and current-time advance retain their
per-member pipeline. Release still samples TIME once before preparing targets.
No pre-read, per-member TIME, retry, cursor, new key or changed input capacity is added.

`WorkerScoreEncodingTest`, `WorkerScoreRedisBoundaryTest` and the real-Redis
`RedisWorkerOwnerRuntimeIntegrationTest` cover arithmetic, private I/O boundaries,
atomic statuses, races and command budgets. Existing Pacer, Matching and Runtime
proofs retain ownership of policy, original deadlines, correlation and delivery.

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
```

This primitive cannot change HOT_ACQUIRE to RECOVERY_RECHECK or
RECOVERY_RECHECK to HOT_ACQUIRE. Server pause uses this current-value operation.
Serviceability uses the separate exact relative deferral or current polarity
correction; it does not compose a Probe hold from two score writes.

### Release

Release / enable is the only ordinary operation allowed to lower `timeSlot`.
It must use exact observed-score protection:

```text
release_score_holds(homeBucketId, observedScores, releaseTimeMillis)
release_observed_hot_score_holds(
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

The current release primitive has no companion reset-authorization state.
Any future business authorization for manual release requires its own caller
contract; it must not be inferred from Score or invented as WorkerResourceCatalog data.

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

### Exact Due Coordinate Deferral

`deferObservedToRecovery` accepts at most 100
`workerId -> WorkerScoreDelayTarget(observedScore, delayMillis, targetLaneRank)`
entries on one Group key. The DTO carries data without validating numbers;
invalid coordinates, delays and ranks return `INVALID` from the Owner.

Java validates the observation and prepares `targetLaneRank * 2 + observedDirty`.
The fixed relative-delay Lua reads Redis TIME once and exact-compares each member:

```text
nextSlot = floor((redisNowMillis + delayMillis) / SLOT_MILLIS)
exact due HOT or RECOVERY -> RECOVERY(nextSlot, supplied targetLaneRank, preserved dirty)
```

Pacer chooses rank 0 for the first HOT Probe and rank n+1 for a Recovery Probe.
It also owns backoff multiplication, attempt limits and whether to offer a Probe.
The Score Owner does not increment rank or interpret Probe outcomes.
Due requires `storedSlot < redisNowSlot`. Nonpositive or out-of-range delays,
invalid observations, cold RECOVERY inputs, out-of-range target ranks, and targets
at or beyond PAUSE are `INVALID`. Missing or changed exact fences and current/future
observations are `STALE`. Each rejected member remains unchanged. Exact comparison
precedes the execution-time target and due checks; results retain the current Score
where the existing contract requires it.

The delay starts at Redis execution, including delayed submission, with addition
before 100ms rounding. There is no preceding TIME or Score read. Only successful
Score transitions may be offered as Probes; offer failure or evidence loss retains
the next time. CONNECTED retains a current/future time even when restoring HOT.
RECOVERY continues to mean the next permitted recheck; this refactor changes
neither stored coordinates nor deployment configuration.

### Current Polarity Within a Time Fence

`rewriteCurrentPolarityWithinTimeFence(group, suppliedTimes, targetPolarity,
refreshPastTime)` applies one caller-selected polarity through a same-key batch
Lua. The Serviceability event Mechanism selects HOT with `refreshPastTime=true`
for available evidence, or RECOVERY with `refreshPastTime=false` for unavailable
evidence. Score Owner accepts no event names. All times are reduced to 100ms slots.
A current value can be modified only when:

```text
storedTimeSlot >= redisNowSlot
OR storedTimeSlot <= suppliedTimeSlot
```

**Core mechanism change:** the first branch includes the current slot, matching
the inclusive active-lease confirmation boundary. It permits an observed Route
fact to correct HOT or RECOVERY polarity without changing a current/future
coordinate or PAUSE. The second branch is the past-slot freshness fence and
deliberately accepts evidence from the same slot as that past coordinate.
When `refreshPastTime=false`, the operation changes only the sign. When true,
it replaces time with `suppliedTimeSlot` only if `storedTimeSlot < redisNowSlot`
and `storedTimeSlot < suppliedTimeSlot`. Current/future coordinates and PAUSE
retain their time. Both paths preserve
`laneRank` and dirty. A score already at the target polarity is `NOOP` only when
its time coordinate also remains unchanged. A newer past-slot coordinate
makes older Evidence `STALE`.

Score time may come from a lease, network observation or RECOVERY probe. It is
not a separate network-observation version. Valid delayed reports may therefore
change polarity in the current slot; across batches this remains best-effort
arrival-order behavior. Binding/source and bounded evidence age checks remain
upstream. The operation does not replay rejected evidence, clear dirty, shorten
leases or broaden probe coverage. Evidence arriving after the stored slot has
become past may still be rejected by the existing freshness check.

The Result path therefore owns no floor calculation, retry increment, cold
park, or PAUSE exception. Dispatch owns check timing and retry progression.
Connected Evidence refreshes a past coordinate above the process floor or keeps
a current/future coordinate intact; it does not shorten a future recheck wait.
Unavailable Evidence only removes that Worker from the HOT polarity.

### Recovery Exhausted / Cold Park

Recovery exhausted is a RECOVERY_RECHECK same-polarity operation that writes a
too-old coordinate, not a far-future hold:

```text
park_observed_recovery_score(
  homeBucketId,
  workerId,
  observedScore,
  targetLaneRank
)
```

Rules:

```text
storedScore must equal observedScore
stored polarity must be RECOVERY_RECHECK
coldParkTimeSlot is the fixed owner-internal near-zero valid time coordinate
routine recovery ranges always start above coldParkTimeSlot
targetLaneRank is supplied by the caller and must be in 1..99
targetDirty = stored dirty
write RECOVERY_RECHECK(coldParkTimeSlot, targetLaneRank, targetDirty)
```

This removes the worker from routine RECOVERY_RECHECK scans without converting
it into a far-future hold. If owner reset or verified recovery later moves it
back to HOT_ACQUIRE, the old coordinate is preserved and the worker becomes
immediately due in HOT_ACQUIRE.

### Dirty Lease Fence

Dirty remains an encoded score fence. Worker Matching does not interpret or
mutate it. Server coordinates facts-first, best-effort invalidation through the
Score Owner; no Properties signature or version is stored in Score.

`WorkerScoreCore` exposes bounded HOT observation and exact lease operations:

```text
mark_current_leases_dirty(homeBucketId, workerIds)
  1..100 unique identities; one bounded Lua command on the Group Score ZSET
  only sets dirty=1, preserving polarity, timeSlot and laneRank
  clean -> TRANSITIONED; already dirty -> NOOP; missing -> STALE
  invalid stored score -> INVALID without mutation; never creates a member
  marks due/active/recovery scores alike; not restricted to currently leased Workers
  no TIME, client pre-read or confirmation read

observe_due_hot_score_candidates(workerGroupId, hotEligibilityFloorMillis?, limit)
  reads the smallest positive due HOT_ACQUIRE scores at or above the optional floor
  returns an immutable ascending Score/member Map within a 1..100 raw-row limit
  one Lua: Redis TIME and ZRANGE BYSCORE LIMIT 0 limit WITHSCORES
  corrupt rows are filtered without replacement reads or cross-round bypass
  no score mutation, stable-snapshot or business-priority guarantee

acquire_hot_candidates_before(
  homeBucketId, hotCutoffMillis, limit
)
  reads positive HOT_ACQUIRE scores strictly below the supplied cutoff
  returns a bounded descending opaque-score head without a continuation score
  belongs to Serviceability discovery, never ordinary Assignment
  does not mutate score

acquire_observed_hot_score_leases(
  homeBucketId, observedScores, targetTimeMillis
)
  each observedScore must decode to a due HOT_ACQUIRE score
  targetTimeSlot must be after nowTimeSlot
  independently writes HOT_ACQUIRE(targetTimeSlot, observed laneRank, dirty=0)
  each generic CAS requires storedScore == observedScore

confirm_active_hot_score_leases(homeBucketId, observedScores, targetTimeMillis)
  each observedScore must decode to HOT_ACQUIRE
  each storedScore must equal its observedScore
  each observed timeSlot must be >= nowSlot and must not be PAUSE
  each observed dirty must be 0
  targetTimeMillis must describe a future slot
  independently writes HOT_ACQUIRE(max(targetTimeSlot, observed timeSlot), observed laneRank, dirty=1)
  even an already sufficient deadline must TRANSITION; no successful NOOP
  returns the new execution fence for ResultContext and exact result release
  dirty entries return STALE and caller must discard the cached continuation

```

First acquisition before Matching and execution confirmation each use a fixed Lua
entry per 100 identities on one WorkerGroup/ZSET. Each script reads Redis TIME before
checking deadlines and exact fences, then returns per-Worker results. There is no
separate time confirmation read or per-Worker command. Dirty invalidation is also
a bounded Lua. Properties writes and invalidation remain separate commits.

RECOVERY_RECHECK scores must not pass any hot score lease primitive. Recovery
validation must first move the worker back to HOT_ACQUIRE through owner-validated
polarity transition.

Dirty clear is only available as part of a hot score lease transition. There is
no standalone `clear_dirty` operation. Active confirmation treats a dirty
score as stale. Due acquisition includes dirty=1 and clears it in the next
initial hold. Properties invalidation never shortens or renews a hold; existing
Candidate capacity and expiry remain unchanged. The HOT lease protocol owns
the facts-write/dirty failure windows and upgrade behavior.

## Transition Matrix

| Current polarity | Validated outcome | Target score | Rule |
| --- | --- | --- | --- |
| HOT_ACQUIRE | candidate remains usable and no delay is needed | no rewrite, or HOT_ACQUIRE(nextTime, laneRank, dirty) | same polarity |
| HOT_ACQUIRE | slot contention / cooldown / claim interval | HOT_ACQUIRE(nextTime, laneRank, dirty) | nextTimeSlot >= currentTimeSlot |
| HOT_ACQUIRE | manual disable / drain / maintenance hold | HOT_ACQUIRE(PAUSE_TIME_SLOT, laneRank, dirty) | same polarity hold |
| HOT_ACQUIRE | Adapter rejection result | exact lease release, polarity preserved | complete observed-score CAS |
| HOT_ACQUIRE | accepted unavailable Adapter evidence | RECOVERY_RECHECK(sameTime, sameRank, dirty) | dedicated evidence-time fence; preserve the entire absolute coordinate |
| RECOVERY_RECHECK | explicit owner-validated general polarity move | HOT_ACQUIRE(sameTime, 0, dirty) | exact observed-score CAS; distinct from Serviceability evidence |
| either | accepted connected Adapter evidence | HOT_ACQUIRE(retained or refreshed time, sameRank, dirty) | dedicated evidence-time fence; advance an older past-slot coordinate, preserve current/future coordinates and PAUSE |
| RECOVERY_RECHECK | due Serviceability probe round | RECOVERY_RECHECK(owner Redis time + supplied delay, retryCount + 1, dirty) | exact advance before probe offer; Dispatch policy owns retry cadence |
| RECOVERY_RECHECK | recovery exhausted / cold parked | RECOVERY_RECHECK(coldTooOldTime, laneRank, dirty) | same polarity cold park + owner evidence |
| RECOVERY_RECHECK | owner hold / disabled / drain / maintenance | RECOVERY_RECHECK(PAUSE_TIME_SLOT, laneRank, dirty) | same polarity hold + owner evidence |

There is no PARKED row because PARKED is not a polarity or band. It is owner
evidence attached to a RECOVERY_RECHECK too-old cold coordinate or a policy
hold, depending on owner reason.

## Cross-Owner Use

The [HOT Lease Protocol](worker-hot-acquire-lease-protocol.md) owns the opaque
fence from initial acquisition through matching, confirmation, claim and Result
release. This Score Owner supplies bounded mechanical operations; it does not
read TaskItems, interpret Rules, select physical routes or own Candidate Cache.
Serviceability policy and evidence classification are defined in
[Worker Serviceability](../../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md).

## Input Write Taxonomy

| Input kind | May write worker score? | Required path |
| --- | --- | --- |
| hot candidate observation | no | bounded due range read with scores |
| hot Worker allocation lease | yes | Pacer bounded Group HOT observation, then exact observed-score CAS |
| recovery-recheck validation round | yes | same-polarity rewrite / polarity move / cold park |
| slot contention / cooldown | yes | same-polarity HOT_ACQUIRE rewrite |
| manual disable / drain / maintenance | yes | same-polarity hold |
| manual enable / release | yes | exact observed-score same-polarity release |
| Worker Matching Properties change | no | Matching facts only; later supplied-ID projection sees the new snapshot |
| Worker registration during Server Prepare | only when score is missing | initialize cold RECOVERY_RECHECK timeSlot=1, laneRank=0, dirty=0; preserve every existing score exactly |
| assignment owner leases observed HOT_ACQUIRE identities | yes | `acquire_observed_hot_score_leases` checks Redis time and exact-CAS writes a 1-second clean inventory lease before Matching; either due dirty value is allowed |
| assignment owner consumes active clean HOT_ACQUIRE holds | yes | `confirm_active_hot_score_leases` exact-CAS sets dirty=1 and returns the execution fence; dirty entries return STALE |
| Server after APPLIED Worker or Platform facts writes | best-effort | `mark_current_leases_dirty` preserves coordinates; failure does not undo the facts response |
| trusted Adapter evidence that execution was not entered | yes | exact release of the correlated Worker lease fence; no online inference |
| bounded-age Adapter Route evidence | yes | dedicated same-key evidence operation checks past stored time against evidence time, accepting current/future coordinates as described in Serviceability Evidence; this is not a total cross-batch ordering guarantee |
| recovery exhausted / cold parked | yes | RECOVERY_RECHECK too-old cold coordinate + owner evidence |
| transport heartbeat / keepalive | no | evidence only |
| raw socket/session observation | no | local observation only; only the Adapter's exact verified Route transition becomes scheduling evidence |
| trusted Worker command succeeded/failed event | yes | exact release of the correlated Worker lease fence |
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
  stored score time must not be newer than Adapter observed time unless it is a
  current/future coordinate or PAUSE
  connected evidence advances an older past-slot coordinate to its evidence slot
  unavailable evidence and current/future coordinates preserve the stored time

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

accepted unavailable Adapter evidence
  apply the Serviceability evidence-time fence; preserve timeSlot, laneRank and dirty

RECOVERY_RECHECK score due but recovery validation fails
  the next eligible Dispatch round exact-advances the due score before offering
  another probe, or cold-parks the exact exhausted observation

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

## Mechanism And Policy

Mechanism owns:

```text
signed score encoding
positive hot acquire range
negative recovery-recheck acquire range
observed-score stale fence for active confirmation, lowering, and polarity moves
dirty bit mark / hot lease clear / stale-confirmation protocol
same-polarity release
current polarity time fence preserving laneRank and dirty
RECOVERY_RECHECK lookback-window acquisition
RECOVERY_RECHECK cold-too-old exhausted coordinate
home bucket score key
optional refresh of a strictly older past coordinate to the supplied time
```

Production policy is defined in the [Pacer documents](../../../kernel_pacer_jvm/README.md).
The following choices belong outside encoding; their presence here does not
claim that every possible policy has a current production caller:

```text
network-evidence freshness and optional HOT eligibility floor
Serviceability event mapping to target polarity and past-time refresh
candidate ranking / laneRank meaning
platform scheduling signature policy
facts-change dirty invalidation and exact candidate confirmation
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

## Non-Goals

These remain outside the Worker Score Owner:

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
  WorkerId, exact-compared and returned to this Owner; Policy and Worker
  Matching must not decode, construct, print or calculate score coordinates.
- Where an operation explicitly requires `observedScore`, do not trim it to
  time/laneRank/dirty. It remains the full signed score and callers must not
  construct or decode it.
- Do not expose kernel-owned encoding details as public parameters: scan range,
  cold coordinate, polarity sign, dirty bit, base, or factor constants.
- Do not add fake business strategy knobs to score-core methods before a real
  caller workflow owns the value.
- Do not derive dirty directly from a Properties hash or store Matching
  signatures in WorkerResourceCatalog. Dirty is only a score-local lease fence; a new
  producer requires an explicit continuation invariant and owning caller.
- Do not let heartbeat, session refresh, trace, diagnostics, or display-only
  metadata bump dirty bit.
- Do not invent a score lease just to justify dirty. Candidate confirmation
  is its consumer; invalidation may mark any existing coordinate without
  checking for an active hold or acquiring a resource lock.
- Do not let non-lease owners clear dirty. A successful exact observed-score HOT
  lease may clear dirty before matching; active confirmation must return STALE on dirty.
- Do not use RECOVERY_RECHECK scores as assignment leases. Recovery validation
  must move the worker back to HOT_ACQUIRE before any hot score lease primitive
  can run.
- A read-only HOT due observation may precede a lease write, but the write must
  use exact observed-score CAS. Never write a future lease from WorkerId or due
  membership alone.
- Do not create per-task candidate keys inside WorkerScoreCore.
  Matching owns derived eligibility index keys.
- Do not fan out score across placement-tag buckets in the first slice.
- Do not store transport/session evidence in worker scheduling metadata.
- Do not let read projections or trace materialization drive worker score.
- Do not force task lifecycle score semantics onto worker-runtime polarity.
