# Worker Score-Band Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Cross-pacer use of HOT leases is defined by
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md). This
document remains the owner of score encoding and transition primitives.

## Purpose

Worker score-band scheduling is the worker/resource acquisition clock.

Worker score answers one question:

```text
what network-availability polarity has worker-runtime assigned to this worker,
and is that polarity's scheduling coordinate due now?
```

It does not answer:

```text
which transport session or endpoint is currently connected?
does this worker have free capacity?
does this worker match a task demand?
is this worker finally selected?
why was this worker held, parked, disabled, or recovered?
```

Those remain worker-runtime validation, admission, policy, owner evidence, and
trace decisions.

Worker score intentionally does not copy task score lifecycle tags. A task is a
one-shot scheduling aggregate. A worker is a long-lived resource identity. The
worker score axis therefore expresses owner-classified network availability as
an acquisition polarity, not lifecycle progression. Positive/negative sign is
the scheduling truth of online/offline classification; raw socket, heartbeat,
session, and endpoint observations remain transport evidence until
worker-runtime validates them.

## Owner Boundary

Worker-runtime owns:

```text
worker declaration validation
worker group membership interpretation
dispatch gate interpretation
reachability interpretation for scheduling
capacity / admission truth
score polarity and coordinate placement
verified reopen
negative dispatch blocking
manual hold / release policy
```

Transport owns final-hop delivery evidence:

```text
endpoint/session observation
heartbeat / keepalive freshness
adapter-local consumer availability
delivery mailbox evidence
disconnect / unavailable evidence
```

Transport evidence can be read by worker-runtime during validation. It must not
directly write worker score or promote a worker into hot admission. Positive
availability is a worker-runtime-verified fact.

## Score Model

Worker score is a signed acquisition coordinate:

```text
timeSlot = floor(timeMillis / SLOT_MILLIS)
score = polarity * base
base = timeSlot * SLOT_FACTOR + laneRank * DIRTY_FACTOR + dirty
```

The sign is both the worker-runtime network classification and its scheduling
polarity:

```text
score > 0
  HOT_ACQUIRE polarity
  worker-runtime classifies the worker as network available / online
  only candidate source for assignment-dispatch worker hot acquire

score < 0
  RECOVERY_RECHECK polarity
  worker-runtime classifies the worker as network unavailable / offline
  only candidate source for worker-runtime recovery-recheck validation

score == 0
  invalid / reserved
```

Positive does not mean immediately acquirable. The sign answers online/offline;
the decoded `timeSlot` answers whether the online Worker is due, leased, held,
disabled, draining, or cooling down. This separation is why manual disable can
remain positive with a far-future coordinate, while confirmed disconnect
changes only polarity and preserves the existing hold coordinate.

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
  examples: capacity cooldown, admission hold, occupancy interval, drain,
  manual disable, maintenance hold

RECOVERY_RECHECK
  recovery validation coordinate interpreted through a recent lookback window
  examples: disconnect recheck, stale endpoint recovery, reconnect backoff,
  future retry delay, too-old recovery exhausted / cold parked
```

`laneRank` is lane-local:

```text
HOT_ACQUIRE
  priority / fairness / same-slot tie-break / admission anti-spin hint

RECOVERY_RECHECK
  retry count / failed recheck count / remaining recovery budget
```

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

Worker score is not a Worker resource mutation lease. First Worker upsert
establishes the initial HOT_ACQUIRE score using runtime-owned lane config, but
later platform/Worker/dynamic attribute writes,
handler-owned projections, heartbeat evidence, and diagnostics update their own
truth without acquiring or renewing worker score. HOT admission scheduling is
the only routine writer of acquired HOT scores; recovery scheduling is the only
routine writer of acquired RECOVERY_RECHECK scores. Explicit owner commands may
hold/release or perform verified polarity transitions, but cannot become a
generic score-refresh path.

Scheduling-critical metadata may include worker group membership, approved
scheduling attributes, capacity profile, dispatch gate generation, admission
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

Manual disable, drain, maintenance, capacity cooldown, and admission hold do not
mean network unavailable. They are same-polarity HOT_ACQUIRE rewrites with a later
`timeSlot`. A hard manual hold uses:

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
confirmed disconnect
trusted unavailable / unreachable evidence
stale endpoint requiring recovery validation
recoverable dispatch gate block caused by reachability uncertainty
failed hot admission validation that requires recovery
```

RECOVERY_RECHECK means worker-runtime must validate owner facts before reopening hot
admission. A due RECOVERY_RECHECK candidate may:

```text
pass recovery validation
  -> move polarity to HOT_ACQUIRE after declaration, gate, reachability, capacity, and
     policy validation

fail recovery validation with budget remaining
  -> stay RECOVERY_RECHECK with later timeSlot and updated laneRank

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
-base(coldTooOldTimeSlot, recoveryLaneRank, dirty)
  -> +base(coldTooOldTimeSlot, hotLaneRank, dirty)
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
acquire_hot_acquire_candidates(homeBucketId, limit)
  -> map[workerId, observedScore]
```

Score range:

```text
dueTimeSlot = nowTimeSlot - 1
MIN_BASE <= score <= base(dueTimeSlot, MAX_LANE_RANK, MAX_DIRTY)
```

Only positive due scores are returned and the query does not modify them.
Assignment-dispatch may pass a Worker into bounded matching only after an exact
observed-score lease succeeds.

Recovery recheck acquisition:

```text
acquire_recovery_recheck_candidates(homeBucketId, limit)
  -> list[(workerId, observedScore)]
```

RECOVERY_RECHECK does not scan `0..now`. It scans a bounded recent window:

```text
recoveryLookbackSlots = ceil(recoveryLookbackMillis / SLOT_MILLIS)
recoveryWindowStartTimeSlot = nowTimeSlot - recoveryLookbackSlots
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

The reverse scan is intentional. RECOVERY_RECHECK scores are negative; within
the recovery window, reverse numeric order returns the oldest window coordinate
first. Scores at or newer than `nowTimeSlot` are current-slot boundary,
future retry delay, or hold coordinates and are not scanned. Scores older than
`recoveryWindowStartTimeSlot` are exhausted / cold parked and are not scanned
by routine recovery.

Within the same `timeSlot`, reverse scan returns lower laneRank first because
RECOVERY_RECHECK scores are negative. Within the same `timeSlot` and laneRank,
it returns lower dirty first. First-slice policy should treat lower
RECOVERY_RECHECK laneRank as more urgent / closer to exhaustion. Dirty is only a
stale fence and must not be used as a priority signal. If a later policy wants
higher retry budget to run first, it must encode the laneRank in reverse order
instead of changing the scan primitive.

First slice should prefer demand-driven or owner-controlled recovery recheck. Do
not add a periodic worker-wide recovery-recheck scanner until a later design proves
the liveness invariant and cost. Without demand or an owner-controlled recheck
round, RECOVERY_RECHECK has no wall-clock guarantee to become HOT_ACQUIRE or
parked exactly when its coordinate becomes due.

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
worker group capability and task demand are compatible
approved scheduling metadata is current enough
if dirty == 1 and the caller is continuing from a persisted assignment plan /
hot score lease continuation, assignment owner discards / rematches or
revalidates through the allowed hot lease transition
dispatch gate permits scheduling
reachability evidence is acceptable by policy
capacity/admission is available
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
capacity cooldown
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
```

Polarity move rule:

```text
HOT_ACQUIRE -> RECOVERY_RECHECK
  strong negative availability transition
  examples: confirmed disconnect, trusted unavailable, owner-validated
  unreachable

RECOVERY_RECHECK -> HOT_ACQUIRE
  verified reopen transition
  requires declaration, membership, gate, reachability, capacity/admission, and
  policy validation
```

Polarity move always preserves `timeSlot`. This prevents disabled,
draining, cooldown, or far-future holds from escaping when availability polarity
changes, and it lets a recovered too-old RECOVERY_RECHECK score become
immediately due in HOT_ACQUIRE. Polarity move must not implicitly inherit
laneRank across lanes. HOT_ACQUIRE laneRank and RECOVERY_RECHECK laneRank have
different meanings, so the target laneRank must be minted explicitly by policy.
Polarity move preserves the dirty bit. Dirty score primitives are implemented;
policy may invoke them only for an active continuation that will later
revalidate or renew. Raw availability evidence never writes dirty.

Raw network-ok, heartbeat, keepalive, or latency evidence cannot move
RECOVERY_RECHECK to HOT_ACQUIRE by itself. `WorkerRuntime.upsert_worker` is the
trusted reconnect owner command: after immutable declaration validation and
attribute refresh, it may exact-CAS a negative score to positive while
preserving `abs(score)`. Other evidence remains non-authoritative until
worker-runtime validates it.

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
targetLaneRank when caller legitimately chooses lane-local priority / budget
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
capacity full -> HOT_ACQUIRE with future timeSlot
admission hold -> HOT_ACQUIRE with future timeSlot
manual disable / drain observed as HOT_ACQUIRE -> HOT_ACQUIRE(PAUSE_TIME_SLOT)
manual disable / drain observed as RECOVERY_RECHECK -> RECOVERY_RECHECK(PAUSE_TIME_SLOT)
recovery-recheck failed with budget -> RECOVERY_RECHECK with later time/laneRank
```

This primitive cannot change HOT_ACQUIRE to RECOVERY_RECHECK or
RECOVERY_RECHECK to HOT_ACQUIRE.

### Release

Release / enable is the only ordinary operation allowed to lower `timeSlot`.
It must use exact observed-score protection:

```text
release_worker_hold(
  homeBucketId,
  workerId,
  observedScore,
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

If owner evidence says a held worker is owner-reset-required, release also
requires owner reset authorization. That authorization is not encoded in the
score; it belongs to worker-runtime owner evidence and must be checked before
writing the release.

### Polarity Move

Polarity move is an owner-validated availability transition, not release and
not renew:

```text
toggle_current_polarity(
  homeBucketId,
  workerId,
  observedScore,
  targetLaneRank
)
```

Rules:

```text
storedScore must equal observedScore
observedScore must decode to HOT_ACQUIRE or RECOVERY_RECHECK
target polarity is opposite of stored polarity
targetTimeSlot = stored timeSlot
targetLaneRank is policy-owned and explicit
targetDirty = stored dirty
write signed score(targetPolarity, storedTimeSlot, targetLaneRank, storedDirty)
```

Use HOT_ACQUIRE -> RECOVERY_RECHECK for strong negative availability evidence. Use
RECOVERY_RECHECK -> HOT_ACQUIRE only after worker-runtime verified reopen. Do not use release
for polarity moves.

Polarity move preserves `timeSlot` on purpose:

```text
disabled / held HOT_ACQUIRE future score
  -> RECOVERY_RECHECK with the same future coordinate, still not routinely scanned

RECOVERY_RECHECK too-old exhausted score
  -> HOT_ACQUIRE with the same old coordinate, immediately due after verified recovery
```

Polarity move uses full `observedScore` CAS. If any coordinate has changed, the
operation is stale and must not toggle again. The target still preserves
timeSlot and dirty; `observedScore` is only the stale fence.

### Recovery Exhausted / Cold Park

Recovery exhausted is a RECOVERY_RECHECK same-polarity operation that writes a
too-old coordinate, not a far-future hold:

```text
exhaust_recovery_recheck(
  homeBucketId,
  workerId,
  observedScore
)
```

Rules:

```text
storedScore must equal observedScore
stored polarity must be RECOVERY_RECHECK
coldTimeSlot is minted internally from the routine recovery lookback policy
targetLaneRank = stored laneRank
targetDirty = stored dirty
write RECOVERY_RECHECK(coldTimeSlot, targetLaneRank, targetDirty)
```

This removes the worker from routine RECOVERY_RECHECK scans without converting
it into a far-future hold. If owner reset or verified recovery later moves it
back to HOT_ACQUIRE, the old coordinate is preserved and the worker becomes
immediately due in HOT_ACQUIRE.

### Dirty Lease Fence

Worker scheduling metadata can change without any network event. Worker-runtime
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
  do not interrupt through dirty; wait for result / timeout / capacity release
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

acquire_hot_acquire_candidates(homeBucketId, limit)
  reads positive due HOT_ACQUIRE scores in score order
  returns at most limit workerId -> observedScore entries
  does not expose score order as a caller contract
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
  targetTimeSlot must be > each accepted observed timeSlot
  independently writes HOT_ACQUIRE(targetTimeSlot, observed laneRank, dirty=0)
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
capacity profile id
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
candidate `match_rules`, matcher validation, group membership check, gate,
capacity profile, or other owner-approved validation dependency, it should not
mark dirty. If the worker is already executing dispatched work and the hot score
lease has been released, dirty should not interrupt execution; result, timeout,
capacity evidence, and the next scheduling round handle the new facts.

`validationDependencySet` is conceptual first-slice evidence, not a public DTO
and not a new interface. It records which worker metadata / dynamic attributes /
policy facts were used to validate the match so an attribute update handler can
decide whether dirty is necessary. If an implementation cannot cheaply prove a
changed dependency still satisfies the recorded query, it may conservatively
mark dirty. If it can prove the dependency remains valid, no dirty write is
needed.

## Transition Matrix

| Current polarity | Validated outcome | Target score | Rule |
| --- | --- | --- | --- |
| HOT_ACQUIRE | candidate remains usable and no delay is needed | no rewrite, or HOT_ACQUIRE(nextTime, laneRank, dirty) | same polarity |
| HOT_ACQUIRE | capacity full / contention / claim interval | HOT_ACQUIRE(nextTime, laneRank, dirty) | nextTimeSlot >= currentTimeSlot |
| HOT_ACQUIRE | manual disable / drain / maintenance hold | HOT_ACQUIRE(PAUSE_TIME_SLOT, laneRank, dirty) | same polarity hold |
| HOT_ACQUIRE | confirmed disconnect / trusted unavailable | RECOVERY_RECHECK(sameTime, recoveryLaneRank, dirty) | owner-validated polarity move |
| RECOVERY_RECHECK | recovery validation passes | HOT_ACQUIRE(sameTime, hotLaneRank, dirty) | owner-validated polarity move |
| RECOVERY_RECHECK | recovery validation fails and budget remains | RECOVERY_RECHECK(nextRecheckTime, laneRank', dirty) | same polarity |
| RECOVERY_RECHECK | recovery exhausted / cold parked | RECOVERY_RECHECK(coldTooOldTime, laneRank, dirty) | same polarity cold park + owner evidence |
| RECOVERY_RECHECK | owner hold / disabled / drain / maintenance | RECOVERY_RECHECK(PAUSE_TIME_SLOT, laneRank, dirty) | same polarity hold + owner evidence |

There is no PARKED row because PARKED is not a polarity or band. It is owner
evidence attached to a RECOVERY_RECHECK too-old cold coordinate or a policy
hold, depending on owner reason.

## Assignment-Dispatch Protocol

Worker score-band participates in assignment-dispatch like this:

```text
task score acquires due task candidate
assignment-dispatch resolves worker demand
worker-runtime acquire_hot_acquire_candidates(homeBucketId, limit)
allocation pacer exact-CAS leases unchanged due Workers
allocation pacer passes only lease-success Worker ids to matcher
worker-runtime validates and matches candidates
allocation pacer retains unmatched leases until expiry and publishes matched leases
worker-runtime admits one or more selected workers
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
inspect adapter sessions directly
write task score
create or own per-task worker candidate sets; transient `AssignmentDispatchRuntime`
candidate reservation ZSETs belong to the assignment inter-pacer protocol
```

RECOVERY_RECHECK acquisition is a worker-runtime recovery validation path, not an
assignment-dispatch worker selection path.

## Input Write Taxonomy

| Input kind | May write worker score? | Required path |
| --- | --- | --- |
| hot candidate observation | no | bounded due range read with scores |
| hot Worker allocation lease | yes | exact observed-score CAS before bounded matching |
| recovery-recheck validation round | yes | same-polarity rewrite / polarity move / cold park |
| capacity full / contention | yes | same-polarity HOT_ACQUIRE rewrite |
| manual disable / drain / maintenance | yes | same-polarity hold |
| manual enable / release | yes | exact observed-score same-polarity release |
| platform scheduling metadata signature changed while persisted task-worker assignment plan / hot score lease continuation exists | yes | `mark_current_lease_dirty` may only set dirty = 1 |
| platform scheduling metadata signature changed while no persisted assignment plan / hot score lease continuation exists | no score write required | metadata/evidence only; next candidate validation reads current metadata |
| trusted Worker reconnect upsert after declaration validation | yes only when negative | exact polarity flip to HOT_ACQUIRE preserving `abs(score)`; positive score is unchanged |
| assignment owner leases due HOT_ACQUIRE observations | yes | `acquire_observed_hot_score_leases` pipelines independent exact-CAS writes, future leases, and dirty clear before matching |
| assignment owner extends active clean HOT_ACQUIRE leases | yes | `renew_active_hot_score_leases`; dirty entries return STALE and force rematch |
| confirmed disconnect / trusted unavailable | yes | owner-validated HOT_ACQUIRE -> RECOVERY_RECHECK polarity move preserving time coordinate |
| verified owner reopen | yes | owner-validated RECOVERY_RECHECK -> HOT_ACQUIRE polarity move preserving time coordinate |
| recovery exhausted / cold parked | yes | RECOVERY_RECHECK too-old cold coordinate + owner evidence |
| transport heartbeat / keepalive | no | evidence only |
| raw connected / session refresh outside Worker upsert | no | evidence only |
| trusted result arrival | yes | exact release of the correlated Worker lease fence; no generic score refresh |
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

capacity admission:
  capacity mutation and score rewrite if the rewrite protects that admission

manual disable / drain / maintenance:
  owner gate fact and same-polarity hold score write

trusted reachability block:
  owner block fact and HOT_ACQUIRE -> RECOVERY_RECHECK polarity move

verified reopen:
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

HOT_ACQUIRE score due but reachability/readiness validation fails strongly
  move to RECOVERY_RECHECK by owner policy, preserving timeSlot

RECOVERY_RECHECK score due but recovery validation fails
  rewrite RECOVERY_RECHECK with next recheck time if budget remains, or write
  a RECOVERY_RECHECK too-old cold coordinate if exhausted

score due but capacity is full
  rewrite HOT_ACQUIRE with future time or reject according to admission policy

stale lease renew / observed-score polarity move
  return STALE / no-op; do not overwrite newer score

raw positive transport evidence
  never moves RECOVERY_RECHECK to HOT_ACQUIRE directly
```

Stale handling must be bounded. Do not scan all workers to repair score.
Score absence is not normal unavailability. It may be used only for absent
resources or confirmed orphan cleanup, not as the ordinary way to hold, park,
disable, or disconnect a long-lived worker id.

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
initial HOT_ACQUIRE score
candidate ranking / laneRank meaning
platform scheduling signature policy
dirty mark invocation and continuation revalidation rule, only when a
persisted assignment continuation exists
cooldown duration
admission hold interval
manual hold / enable rule
RECOVERY_RECHECK retry cadence
RECOVERY_RECHECK initial retry budget
RECOVERY_RECHECK lookback window
cold parked / owner-reset evidence rule
negative evidence mapping
capacity contention delay
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
per-task worker candidate keys
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
- Do not make score replace capacity/admission validation.
- Do not pass observed or leased HOT scores across the matcher boundary; the
  allocation pacer keeps observations in a private sidecar and matcher receives
  Worker ids only.
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
- Do not split the HOT due predicate and future lease write into separate Redis
  operations.
- Do not create per-task worker candidate keys.
- Do not fan out score across placement-tag buckets in the first slice.
- Do not store transport/session evidence in worker scheduling metadata.
- Do not let read projections or trace materialization drive worker score.
- Do not force task lifecycle score semantics onto worker-runtime polarity.
