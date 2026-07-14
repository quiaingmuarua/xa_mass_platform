# Task Score-Band Scheduling

Status: architecture mechanism note. This document describes the target task
score-band scheduling mechanism. It is not current implementation truth and not
an implementation roadmap.

## Purpose

Task score-band scheduling compresses task scheduling visibility onto one
linear numeric axis:

```text
taskId -> score
```

The assignment-dispatch hot path does not interpret a full task lifecycle
object. It only asks:

```text
which task ids have score in the acquire range now?
```

Task score answers:

```text
which lifecycle band currently owns this task?
should this task enter a bounded scheduling round now?
```

This document defines the task score axis and the narrow query/update contract
around it. It does not define a timer job, scheduler daemon, or dispatch loop.
`assignment-dispatch-scheduling` owns the pacing loop that chooses scan ranges,
batch limits, classifies acquired candidates, and asks the score owner to write
the next score.

It does not answer:

```text
does the task have backlog?
why did the task become terminal?
which worker should run it?
is result finality accepted?
```

## Relationship To Current Runtime

This document defines a target mechanism. Current task runtime code can be used
only as a failure-mode and invariant reference, not as the target owner model.

Useful lessons from the current runtime:

```text
bounded acquire is mandatory
duplicate claim must be fenced; duplicate final evidence must converge to no-op
stale candidates are normal and must be cheap to reject
paused / terminal tasks must be fail-closed for new dispatch
trace evidence is valuable for proof but must not become runtime truth
```

What must not be copied into task score-band:

```text
ready backlog ownership
Item record ownership
Item score claim / current occupancy ownership
Item same-tag retry movement ownership
result finality ownership
result read-model ownership
```

Those are separate owners. Task score-band owns Task lifecycle coordination and
the sortable scheduling visibility coordinate, not the complete business
aggregate or terminal attribution.

## Core Model

Task score is Task lifecycle coordination truth and the acquire index. It is not
the complete Task business aggregate.

Core mechanics:

```text
tag decides lifecycle direction
timeMillis decides caller-facing hold / recheck / freshness intent
timeSlot is the internal sortable coordinate derived from timeMillis
suffix carries band-owned budget, tie-break, or owner-local code; not every band
consumes it
write-time stored-score/CAS prevents stale overwrite
terminal close is a high-priority positive-to-negative write
score hold release uses an exact observed-score fence
transition direction rule prevents lifecycle regression
```

Lifecycle progresses left by lower tag or terminal score:

```text
PRE_REVIEW(3) -> PRE_DISPATCH_VISIBLE(2) -> RUNNING_VISIBLE(1) -> TERMINAL(<0)
```

Scheduling suppression, retry, and hold move right inside the same band
by writing a later time coordinate. Release/resume is the only right-to-left hold
shortcut and must carry the exact `observedHoldScore`.

Rules:

- score is one sortable value owned by the task active-acquisition score owner;
- score absence has no business meaning to score-band;
- score-band does not explain service discovery or why a task is absent;
- acquire is a bounded range + limit query;
- task score-band does not own pagination cursors, scan ordering, or no-op
  pacing;
- dispatch-visible candidates must be classified and moved, closed, cleaned, or
  rejected by the score-write stale fence;
- acquired tasks still require descriptor and TaskItemScoreBandCore validation before
  dispatch; no second lifecycle gate may override the score band;
- append, Item retry/outcome movement, and result lookup are not score refresh
  triggers.

Score is not a Task resource mutation lease. Task metadata/configuration writes,
item append, Item/result evidence, projections, and trace commit through their
own owners without reading, acquiring, or rewriting task score. Their success
must not depend on dispatcher ownership of the current score.

For `PRE_DISPATCH_VISIBLE` and `RUNNING_VISIBLE`, assignment-dispatch is the only
routine score-intent writer. It owns recheck cadence, scheduling-budget
consumption, ordinary future placement, and round classification. Explicit
lifecycle commands may still use the declared owner-transition primitives for
approve/reject/pause/resume/cancel/close. Those commands are high-priority
state changes protected by the score stale fence; they are not allowed to
perform generic scheduling refresh or become another pacing loop.

Conceptual flow:

```text
assignment-dispatch pacing round
  -> task score query(range, limit)
  -> task lifecycle / gate / time-coordinate validation
  -> TaskItemScoreBandCore reports or claims a due TaskItem score
  -> worker score-band acquire / admission
  -> dispatch the selected TaskItem through transport
  -> scheduling round classifies evidence
  -> task score rewrite/close/release for fairness / anti-spin / future retry
```

The score value is the task-side scheduling clock. The task-score owner stores
that clock and exposes bounded query/update primitives. It is not responsible
for running a periodic recheck thread. Business events may add evidence to owner
truth, but they do not replace score-band acquire:

```text
business evidence written
  -> owner truth changes, if valid
  -> policy may influence next score
  -> score-band recheck decides when scheduling observes it
```

## Event Discipline

Task score-band does not rely on external events for correctness or automatic
liveness. It also does not emit events by default for routine task/runtime
activity:

```text
append item
worker availability update
transport delivery outcome
result notification
trace / projection update
timer callback
```

These are inputs or observations for their own owners. They are not default
score-band events, wakeups, dirty hints, or state-transition triggers. If a
future design wants event emission, it must be a high-cost opt-in for a key
owner state change, not a routine append/result/heartbeat side effect.

Allowed event emission must be:

```text
after owner truth changes
limited to key state transitions
bounded
fast-fail
evidence-only
non-blocking for correctness and automatic liveness
only an acceleration of an existing score/recheck path
```

The fallback scheduling path is mandatory:

```text
task score acquire
  -> owner validation
  -> work/retry/no-work recheck
  -> score rewrite or terminal closure
```

An event may at most ask the score owner to check sooner. It must not create the
only path that discovers backlog, retry visibility, worker contention, or
no-work closure.

Examples:

```text
append item
  -> TaskRuntime writes canonical TaskItem record
  -> TaskItemScoreBandCore initializes Item score truth
  -> does not write task score
  -> does not emit a default wakeup
  -> does not become RUNNING_VISIBLE correctness
  -> guarantees Item acceptance only, not eventual consumption

PRE_DISPATCH_VISIBLE
  -> activation facts may change outside the score owner
  -> they do not directly promote the task
  -> scheduling acquire evaluates activation and writes RUNNING_VISIBLE if ready

RUNNING_VISIBLE with no due Item
  -> remains RUNNING_VISIBLE with a later score and reduced suffix budget
  -> append does not have to wake it
  -> work discovery and no-work closure come from owner recheck / policy budget
  -> append may wait until the next RUNNING_VISIBLE score becomes due
  -> append after exhausted no-work closure is invalid residue and must not
     reopen or backdate the old score

allowed key transition event, if introduced later
  -> may shorten recheck latency
  -> may be dropped or coalesced
  -> cannot be the only scheduling trigger

RUNNING_VISIBLE
  -> worker / transport evidence may be read during owner validation
  -> owner validation decides whether dispatch can happen

result evidence
  -> result owner classifies business outcome and invokes TaskItemScoreBandCore
  -> TaskItemScoreBandCore validates opaque claimScore / outcome movement
  -> live Task score changes only through Task-score owner-approved rewrite
```

Explicit human gates are the exception. `PRE_REVIEW` may require an approve or
reject command because its policy is intentionally human-gated. That command is
not ordinary evidence; it is an authoritative owner command that still validates
the current state before moving to `PRE_DISPATCH_VISIBLE` or `TERMINAL`.

## Score Write Categories

Task score writes come from several business-side categories, but those
categories are not kernel transition inputs:

```text
score-acquired scheduling round
  -> owned by assignment-dispatch-scheduling
  -> computes the next score after a bounded scheduling action
  -> PRE_DISPATCH_VISIBLE: evaluate activation facts
  -> RUNNING_VISIBLE: attempt bounded assignment-dispatch

owner command
  -> handles human gates and manual controls
  -> create, approve, reject, cancel, write temporary restriction, discard

owner-evidence-write
  -> updates its own truth only
  -> Item append/retry/outcome, worker evidence, transport evidence, trace
  -> does not directly refresh task score
```

The score hold coordinate itself is only a hold/recheck coordinate. The
current time coordinate makes a score member acquirable; it does not complete the
transition without owner validation.
Non-score gates remain blocked until an owner command changes the underlying
fact.

## Score Bands And Encoding

Task score-band uses four score bands. Temporary restriction is not a separate
band; pause/block/hold is represented by rewriting the same active band with a
future hold coordinate. A hard pause uses the maximum internal `timeSlot`
coordinate. These are scheduling visibility states, not a second business
lifecycle. Kernel code should branch on the decoded band and owner validation
result, not on concrete product events.

Hard pause is stronger than an ordinary future delay:

```text
ordinary future delay
  same active tag, future timeSlot
  becomes eligible for that same band's normal action when the time coordinate is due

hard pause
  same active tag, PAUSE_TIME_SLOT
  never becomes due through normal scheduling
  must not consume suffix budget
  must not advance to another positive band
  can only be released by exact observed-score resume, or closed by owner
  terminal finality
```

Positive non-terminal bands use a fixed segmented numeric score:

```text
timeSlot = floor(timeMillis / SLOT_MILLIS)
score = tag * TAG_FACTOR + timeSlot * SUFFIX_FACTOR + suffix

tag = score / TAG_FACTOR
timeSlot = (score % TAG_FACTOR) / SUFFIX_FACTOR
suffix = score % SUFFIX_FACTOR
```

Public kernel interfaces use millisecond timestamps. Redis score encoding uses
an internal fixed-width `timeSlot`; first version uses `TIME_SCALE = 10`, so
`SLOT_MILLIS = 100`. Decoded score state returns the slot start as
`timeMillis = timeSlot * SLOT_MILLIS`.

`timeSlot` is a sortable slot-granularity coordinate. For
`RUNNING_VISIBLE` and `PRE_DISPATCH_VISIBLE`, it is the next scheduling/recheck
time. For `PRE_REVIEW`, it is the owner mutation freshness coordinate. Kernel code
does not interpret review business flow; it only rejects stale positive writes
whose target `timeSlot` is not newer than the stored `timeSlot`, except
for observed-score release/resume.
`suffix` is a bounded two-digit band-owned coordinate. For `RUNNING_VISIBLE`,
`00` means same-band scheduling budget exhausted and `01..99` is the remaining
budget. For `PRE_REVIEW`, `suffix` is an owner-defined review state code. The
built-in `PRE_DISPATCH_VISIBLE` activation policy does not consume or interpret
suffix; it preserves the value until activation succeeds and initializes the
new `RUNNING_VISIBLE` suffix from configuration. Kernel code does not validate
suffix ordering. It is not Item retry truth and must not become the Item
retry counter owner.

```text
RUNNING_VISIBLE_TAG = 1
PRE_DISPATCH_VISIBLE_TAG = 2
PRE_REVIEW_TAG = 3

SUFFIX_FACTOR = 100
TIME_SCALE = 10
SLOT_MILLIS = 100
TIME_SLOT_FACTOR = 100_000_000_000
MAX_TIME_SLOT = 99_999_999_999
PAUSE_TIME_SLOT = MAX_TIME_SLOT
PAUSE_TIME_MILLIS = PAUSE_TIME_SLOT * SLOT_MILLIS
TAG_FACTOR = TIME_SLOT_FACTOR * SUFFIX_FACTOR

TERMINAL score = -closedScoreKey
closedScoreKey = closedTimeSlot * SUFFIX_FACTOR + closedSuffix
closedScoreKey > 10
```

Tag `0` is deliberately unused so decode never depends on a missing high-order
tag. With the fixed-width `timeSlot` coordinate and two-digit suffix, positive
scores remain
well under Redis sorted-set double exact-integer limits. If the coordinate width
or suffix width changes, the owner must re-check exact numeric representation.
Changing slot granularity is an encoding change, not a public API change.

Use `score(TAG, timeSlot, suffix)` below as shorthand for the formula above.

| Band | Score encoding | Acquirable by scheduling | Dispatch-eligible | Kernel action after acquire | Notes |
| --- | --- | --- | --- | --- | --- |
| `PRE_REVIEW` | `score(PRE_REVIEW_TAG, ownerMutationTimeSlot, reviewStateCode)` | no | no | none | Positive mutable state, not schedulable. The review owner defines and validates suffix state semantics. Kernel only requires positive non-release writes to carry a larger `timeSlot`. |
| `RUNNING_VISIBLE` | `score(RUNNING_VISIBLE_TAG, nextDispatchOrRecheckTimeSlot, remainingBudget)` | when included by the running scan | yes, after owner validation, Item evidence, worker admission, and Item score claim | run bounded assignment-dispatch | Also handles `NO_WORK`, `NO_WORKER`, and contention classifications. Temporary restriction uses this same tag with a future `timeSlot`; hard pause uses `PAUSE_TIME_SLOT`. |
| `PRE_DISPATCH_VISIBLE` | `score(PRE_DISPATCH_VISIBLE_TAG, allocationTimeSlot, preservedSuffix)` | when included by the ready scan horizon | no | evaluate pre-running open facts only | Pre-running open-condition recheck. It must not create a deliver seed. If still not open, activation leaves the score unchanged; allocation may independently rotate the same-band timeSlot while preserving suffix. There is no automatic exhaustion pause. |
| `TERMINAL` | negative closed score key | no | no | none | Negative score space is final and immutable. Close reason belongs to meta/result/trace. |

Band order is consumption-first:

```text
RUNNING_VISIBLE < PRE_DISPATCH_VISIBLE < PRE_REVIEW
```

This order keeps cross-band lifecycle movement numerically downward:
`PRE_REVIEW -> PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE -> TERMINAL`. Same-band
recheck/hold writes may numerically increase because `timeSlot` moves
forward; that is not lifecycle regression. The order is not permission to run
one global positive range scan across tags. Assignment-dispatch must scan the
allowed active bands through separate band ranges and policy-owned quotas.
`PRE_REVIEW` is positive and mutable but not in the active scheduling
allow-list. Negative scores are final and immutable, so terminal state has a
sign barrier and a band allow-list barrier.

The important split is:

```text
score-acquirable
  assignment-dispatch may pick the task id and run the band's owner check

dispatch-eligible
  scheduling may continue into worker admission, Item score claim, and deliver
  seed creation
```

`PRE_DISPATCH_VISIBLE` and `RUNNING_VISIBLE` have different same-band policies:

```text
PRE_DISPATCH_VISIBLE
  running pre-open condition
  when acquired: scans evaluate worker-candidate / open facts
  ready: move to RUNNING_VISIBLE
  not open: remain PRE_DISPATCH_VISIBLE without suffix consumption or auto-pause
  later bounded rounds may evaluate the condition again

RUNNING_VISIBLE
  running-stage dispatch/recheck condition
  when acquired: scans work, worker, retry, contention, and dispatch facts
  TaskItem dispatched: rewrite RUNNING_VISIBLE by policy
  no work and suffix > 00: rewrite RUNNING_VISIBLE with next no-work-recheck time coordinate and suffix-1
  no work and suffix == 00: close to TERMINAL
  no worker / contention and suffix > 00: rewrite RUNNING_VISIBLE with next recheck time coordinate and suffix-1
  no worker / contention and suffix == 00: pause/hold in RUNNING_VISIBLE
```

`PRE_DISPATCH_VISIBLE` is a real band because an approved task may still need
pre-running open conditions before running, such as a candidate-work count
range, batch-size threshold, priority window, or policy gate. The task does not
derive these values from its own shell. Typical open facts include
worker-candidate conditions such as minimum matching worker count. Acquiring
`PRE_DISPATCH_VISIBLE` evaluates open facts; it must not create a deliver seed.
The built-in activation policy retries indefinitely through bounded scheduling
rounds rather than consuming a PRE_DISPATCH suffix budget.

This is unbounded over Task lifetime, not unbounded within one round. Cost is
bounded by RUNNING-first acquisition order, per-round scan limit, pacer cadence,
and allocation's same-band time rotation. If a deployment later needs an
activation-attempt ceiling, that must be an explicit policy and owner action;
the kernel must not infer an automatic pause from PRE_DISPATCH suffix.

Scheduling scan ranges are per active acquisition tag. The default active order
is running consumption/recheck first, then ready activation:

```text
running scan:
  ZRANGEBYSCORE task:score
    score(RUNNING_VISIBLE_TAG, 0, 0)
    score(RUNNING_VISIBLE_TAG, currentTimeSlot - 1, MAX_SUFFIX)
    LIMIT batchSize

ready scan:
  ZRANGEBYSCORE task:score
    score(PRE_DISPATCH_VISIBLE_TAG, 0, 0)
    score(PRE_DISPATCH_VISIBLE_TAG, readyScanHorizonTimeSlot - 1, MAX_SUFFIX)
    LIMIT batchSize
```

Do not collapse these into one broad scan from `RUNNING_VISIBLE_TAG` through
`PRE_DISPATCH_VISIBLE_TAG`. A paused active tag uses `PAUSE_TIME_SLOT` and must
stay invisible until released by observed-score resume. Terminal scores are
negative and must not enter the active assignment-dispatch batch.

Assignment-dispatch chooses the scan horizon per band. Normally each scan uses
`timeSlot < nowSlot`; the current slot is not consumed so slot-granularity
refreshes do not create same-slot acquire/rewrite ambiguity. If a policy
deliberately scans a small future horizon,
that horizon must stay below `PAUSE_TIME_SLOT`; hard-pause scores are not
future-prefetch candidates. A future horizon is an early recheck optimization,
not a close-time decision. If the relevant facts remain false, same-band
scheduling rewrites the score to the next `timeSlot` and decrements
`suffix`; if the facts are true, the task may move to `RUNNING_VISIBLE`.

There is no pagination cursor in the task-score owner. The caller repeats
bounded range scans. A successfully acquired candidate must be consumed by one
of these outcomes:

```text
rewrite score within the same band
move to another band
write negative terminal score
clean stale residue
fail the score-write stale fence because newer score already won
```

Keeping the same score after a successful `RUNNING_VISIBLE` acquire is not the
normal path; running false classifications use their configured budget and
next time coordinate. `PRE_DISPATCH_VISIBLE` is the explicit exception: a false
activation check may leave its score unchanged and does not consume suffix.
Assignment-dispatch scans `RUNNING_VISIBLE` before `PRE_DISPATCH_VISIBLE`, so
ready activation retries do not block real running consumption. Each activation
invocation remains bounded by scan horizon and limit; allocation may
independently rotate the PRE_DISPATCH timeSlot while preserving suffix.

Budget-consuming same-band scheduling rewrites obey:

```text
target tag == current tag
current suffix > 00
target timeSlot > acquire currentTimeSlot
target suffix = current suffix - 1
```

Positive non-terminal writes obey the freshness rule by default:

```text
target timeSlot > stored timeSlot
```

This applies to scheduling rewrites, owner transitions, pause/block/hold, and
cross-band lifecycle movement. Same-task score refresh is intentionally
slot-granularity. Multiple non-release updates for the same task within the
same internal slot must be rejected, coalesced, or retried later by the owner.
Public APIs still accept millisecond timestamps; the kernel converts them to
the internal slot before applying monotonicity.

Cross-band lifecycle transitions also obey the tag direction implied by the
band order:

```text
PRE_REVIEW -> PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE -> TERMINAL
```

`PRE_DISPATCH_VISIBLE` is an optional intermediate band, not a required checkpoint.
The kernel permits lifecycle jumps toward lower tags, such as
`PRE_REVIEW -> RUNNING_VISIBLE`, when the business owner has already validated
the required facts. It only rejects movement from a lower tag back to a higher
tag, such as `RUNNING_VISIBLE -> PRE_DISPATCH_VISIBLE`. Same-band recheck/hold writes
may still raise the numeric score because `timeSlot` is later.

If `RUNNING_VISIBLE` suffix is `00`, scheduling must not write another ordinary
budgeted same-band recheck. It must execute the running band's exhausted action:

```text
RUNNING_VISIBLE exhausted after NO_WORK
  -> TERMINAL

RUNNING_VISIBLE exhausted after NO_WORKER / contention
  -> same-band pause/hold score
```

Release/resume is the explicit exception. It releases a known held score:

```text
stored score must still equal observedHoldScore
target score is derived from observedHoldScore with a release timeMillis
target suffix is copied from observedHoldScore
otherwise return STALE / NOOP and keep the newer hold
```

Pause/block/hold uses the ordinary freshness rule: write the same active tag
with a future `timeSlot`; hard pause writes `PAUSE_TIME_SLOT`.
Release/resume uses
`observedHoldScore` as the stale fence; matching that exact score proves no
newer hold, terminal close, or scheduling rewrite has happened.

Release/resume is tag-preserving. The target score is always derived from the
observed held score:

```text
PRE_DISPATCH_VISIBLE(PAUSE_TIME_SLOT, suffix)
  -> PRE_DISPATCH_VISIBLE(releaseTimeSlot, suffix)

RUNNING_VISIBLE(PAUSE_TIME_SLOT, suffix)
  -> RUNNING_VISIBLE(releaseTimeSlot, suffix)
```

It is never:

```text
PRE_DISPATCH_VISIBLE(PAUSE_TIME_SLOT, suffix)
  -> RUNNING_VISIBLE(releaseTimeSlot, suffix)
```

After release, the original band's normal owner validation runs again. A
released `PRE_DISPATCH_VISIBLE` task still needs activation validation before it can
become `RUNNING_VISIBLE`.

`PRE_REVIEW` same-band owner transitions obey the same positive-write freshness
rule:

```text
target tag == PRE_REVIEW_TAG
target timeSlot > current timeSlot
suffix is owner-defined review state code
owner validates the business state, material completeness, permission, and
allowed suffix transition
```

The kernel does not know whether `suffix=30` means draft, uploaded, rejected, or
submitted. Those names and allowed transitions belong to the review owner. The
kernel only prevents an older internal `timeSlot` write from overwriting a newer
`PRE_REVIEW` score. When two owner mutations occur in the same internal slot,
the review owner should compute a later millisecond timestamp that maps to the
next slot:

```text
nextOwnerMutationTimeMillis >= (currentTimeSlot + 1) * SLOT_MILLIS
```

This keeps the score model monotonic without exposing a slot API.

Cross-band writes, such as `PRE_REVIEW -> RUNNING_VISIBLE` or
`PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE`, are allowed only by the transition
direction rule and the write-time stale fence. `targetSuffix` is a score
coordinate chosen only after owner or policy validation; if omitted, the
primitive preserves the stored suffix. Cross-band owner transitions initialize
suffix from policy. Ordinary same-band owner transitions preserve suffix unless
the current band owner defines suffix as owner-local state, such as
`PRE_REVIEW`. A budget reset is a separate owner-authorized reset transition,
not release/resume and not a generic event side effect.

For the built-in `PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE` transition, the
caller must supply the configured initial `RUNNING_VISIBLE` remaining budget as
`targetSuffix = N`. It must not preserve the PRE_DISPATCH budget and must not
write `00` unless policy intentionally starts RUNNING in exhausted mode.

After acquire, decode `tag` and `suffix`:

```text
if acquired PRE_DISPATCH_VISIBLE and open condition is satisfied:
  targetBand = RUNNING_VISIBLE
if acquired PRE_DISPATCH_VISIBLE and open condition is false:
  no activation score write; remain PRE_DISPATCH_VISIBLE for later bounded retry
if acquired RUNNING_VISIBLE and no work exists and suffix > 00:
  targetBand = RUNNING_VISIBLE with next no-work-recheck timeSlot and suffix-1
if acquired RUNNING_VISIBLE and no work exists and suffix == 00:
  targetBand = TERMINAL
if acquired RUNNING_VISIBLE and no worker / contention and suffix > 00:
  targetBand = RUNNING_VISIBLE with next worker-recheck timeSlot and suffix-1
if acquired RUNNING_VISIBLE and no worker / contention and suffix == 00:
  targetBand = RUNNING_VISIBLE paused/held score
```

Temporary restriction is deliberately not listed as a band. Pause/block writes
the same active tag with a future `timeSlot`; hard pause writes
`PAUSE_TIME_SLOT = 99_999_999_999`. Ordinary future holds eventually become
due and are interpreted as the original band. Hard pause is released through the
score-hold release primitive with exact `observedHoldScore`. Deadline-style task
closure is not part of the first score-band model; `RUNNING_VISIBLE` no-work
closure is driven by its exhausted same-band budget.

## Transition Direction Rule

Transition validation is expressed as direction rules, not business-event
enumerations. Product commands, scheduling rounds, and business events validate
their own owner facts before asking the kernel to write a score. Kernel
transition code sees only the stored score and requested target coordinates:

```text
storedScore
targetBand?
targetTimeMillis
targetSuffix?
owner validation
bounded scheduling round result
```

Owner validation happens before the kernel primitive. Do not name business
events inside the kernel rule.

Internal positive mint primitive:

```text
mint_from_range(
  taskId,
  minExpectedScore,
  maxExpectedScore,
  targetScoreBase,
  targetSuffix?
):
  lua:
    storedScore = read_current_score(taskId)
    require minExpectedScore <= storedScore <= maxExpectedScore
    storedSuffix = storedScore % SUFFIX_FACTOR
    suffix = targetSuffix if supplied else storedSuffix
    require 0 <= suffix <= MAX_SUFFIX
    write targetScoreBase + suffix
```

All positive rewrites should compile to this private primitive inside the
kernel implementation. Public callers do not compute scores, score ranges, or
target bases, and no public port should accept `minExpectedScore`,
`maxExpectedScore`, or `targetScoreBase`. The kernel implementation derives the
exact score range and target base from band / time / suffix intent, so Lua only
checks range membership and preserves or substitutes suffix. It does not perform
owner validation and does not protect scheduling-round evidence. Any rewrite
that consumes same-band suffix budget must use exact observed-score fencing.

Score range coordinates are trusted kernel-internal protocol values. If a
caller can pass `minExpectedScore`, `maxExpectedScore`, or `targetScoreBase`
directly, the boundary is already broken. The performance model depends on
stable safe public methods plus a small trusted implementation protocol, not
zero-trust validation at every internal layer.

Task creation uses a dedicated initialization lease rather than ordinary
positive rewrite:

```text
initialize_score(taskId, suffix, leaseDurationMillis):
  nowMillis = redisTimeMillis()
  leaseUntilTimeSlot = floor((nowMillis + leaseDurationMillis) / SLOT_MILLIS)
  if score missing:
    write score(PRE_REVIEW_TAG, leaseUntilTimeSlot, suffix)
    return leaseScore
  return NOOP
```

Initialization is `ZADD NX`; it never interprets or rewrites an existing score.
Duration locks the first owner event, but expiry does not authorize a second
initialization. Descriptor write and score release are separate owner-local
operations. The descriptor owner writes metadata first; score owner then makes
an exact observed-score release to `currentTimeSlot`. Release is best-effort; if
it is stale or lost, `leaseUntilTimeSlot` is the liveness fallback for a later
owner transition. That transition is not `initialize_score`. No Lua spans the
score and descriptor keys. This lease is Task creation coordination, not a
scheduling hold and not an active-acquire state.

```text
rewrite_same_band_time_millis(expectedBand, targetTimeMillis):
  targetTimeSlot = floor(targetTimeMillis / SLOT_MILLIS)
  tag = tag(expectedBand)
  mint_from_range(
    minExpectedScore = score(tag, 0, 0),
    maxExpectedScore = score(tag, targetTimeSlot - 1, MAX_SUFFIX),
    targetScoreBase = score(tag, targetTimeSlot, 0),
    targetSuffix = keep
  )
```

`targetTimeMillis` is absolute. The kernel does not expose a stable
time-slot-delta API. If a business owner wants `now + delay`, that owner
computes the absolute target millisecond timestamp before calling the kernel.
This range-mint path preserves stored suffix and is only for same-band time
movement that does not consume
scheduling-round budget.

```text
rewrite_observed_same_band_suffix(taskId, observedScore, targetTimeMillis, suffixDelta):
  targetTimeSlot = floor(targetTimeMillis / SLOT_MILLIS)
  storedScore = read_current_score(taskId)
  require storedScore == observedScore
  observed = decode_positive(observedScore)
  require observed.tag in {RUNNING_VISIBLE_TAG, PRE_DISPATCH_VISIBLE_TAG}
  require targetTimeSlot > observed.timeSlot
  require suffixDelta < 0
  targetSuffix = observed.suffix + suffixDelta
  require 0 <= targetSuffix <= MAX_SUFFIX
  write score(observed.tag, targetTimeSlot, targetSuffix)
```

Observed suffix rewrite is scheduling-round evidence, not a broad same-band
hold rewrite. It must carry the exact score observed when the round acquired or
read the candidate. If a newer same-band score was written first, this operation
is stale and must not overwrite that newer classification. `suffixDelta` is
negative because this primitive only moves the observed same-band suffix
downward; positive suffix movement or review state changes use owner-validated
positive rewrites with explicit `targetSuffix`. `PRE_REVIEW` suffix is an
owner-defined review-state code and is never changed through this primitive.

```text
rewrite_score(expectedBand, targetBand?, targetTimeMillis, targetSuffix?):
  targetTimeSlot = floor(targetTimeMillis / SLOT_MILLIS)
  expectedTag = tag(expectedBand)
  targetTag = tag(targetBand or expectedBand)
  require targetTag <= expectedTag

  mint_from_range(
    minExpectedScore = score(expectedTag, 0, 0),
    maxExpectedScore = score(expectedTag, targetTimeSlot - 1, MAX_SUFFIX),
    targetScoreBase = score(targetTag, targetTimeSlot, 0),
    targetSuffix = targetSuffix or keep
  )
```

If `rewrite_score` is called without `targetBand` and `targetSuffix`, it is
equivalent to `rewrite_same_band_time_millis` and should use the absolute same-band
hot path.

Terminal close is separate because it is destructive, final, and high priority:

```text
close_score(taskId, terminalScore):
  storedScore = read_current_score(taskId)
  if storedScore < 0:
    return NOOP
  require storedScore > 0
  require terminalScore < 0
  write terminalScore
```

Score hold release is a separate primitive because it is the only legal way to move
the same tag to an earlier `timeSlot`:

```text
release_observed_score_hold(taskId, observedHoldScore):
  releaseTimeSlot = currentRedisTimeSlot()
  storedScore = read_current_score(taskId)
  require storedScore == observedHoldScore
  observed = decode_positive(observedHoldScore)
  require observed.tag in {
    RUNNING_VISIBLE_TAG,
    PRE_DISPATCH_VISIBLE_TAG,
    PRE_REVIEW_TAG only for an owner-validated initialization lease
  }
  require 0 <= releaseTimeSlot <= MAX_TIME_SLOT
  require releaseTimeSlot <= observed.timeSlot
  write score(observed.tag, releaseTimeSlot, observed.suffix)
```

The release primitive has no business-event meaning. It only proves that the
held score being released is still exactly the stored score. If another owner,
pause, terminal close, or scheduling rewrite happened first, the exact-score
match fails and the release is stale. Initialization failure release is an
acceleration path; duration expiry remains the correctness fallback.

The derived view is:

```text
PRE_REVIEW(3)
  normal target: PRE_REVIEW(3), PRE_DISPATCH_VISIBLE(2), RUNNING_VISIBLE(1), TERMINAL(<0)

PRE_DISPATCH_VISIBLE(2)
  normal target: PRE_DISPATCH_VISIBLE(2), RUNNING_VISIBLE(1), TERMINAL(<0)

RUNNING_VISIBLE(1)
  normal target: RUNNING_VISIBLE(1), TERMINAL(<0)

TERMINAL(<0)
  no target
```

The rule is checked against the stored current score at write time, not only
against the band observed when assignment-dispatch acquired the candidate. The
acquired band is evidence for a round; it is not authority to overwrite newer
task score.

Because temporary restriction is represented as the same active band with a
future `timeSlot`, the tag direction rule alone does not distinguish held
mode from ordinary future scheduling. The required protection is
single-task write ordering or a write-time stale fence. If assignment-dispatch
acquired an active score and a same-task owner transition has already written a
newer hold score or terminal score, the stale rewrite must fail instead of
replacing that newer score.

If the stored positive score is a hard pause
(`timeSlot == PAUSE_TIME_SLOT`), ordinary positive lifecycle and
scheduling rewrites must fail. The only score writes that may change it are:

```text
release_observed_score_hold with exact observedHoldScore
close_score to negative TERMINAL
```

That prevents a paused task from consuming suffix budget, running activation,
or moving from `PRE_DISPATCH_VISIBLE` to `RUNNING_VISIBLE` without an explicit resume.

This permits multi-task concurrency while keeping single-task score writes
ordered by the current band. A single task may be protected by a per-task writer
or by a compare-and-set on the decoded current band; the kernel design does not
require a distributed lock.

## Acquire Semantics

Acquire is state-aware. It returns task ids whose score state can do useful
bounded work now. Active acquisition order is consumption-first:

```text
RUNNING_VISIBLE
  acquired for Item evidence, worker admission, Item score claim, DeliverSeed
  creation, and no-work / no-worker recheck

PRE_DISPATCH_VISIBLE
  acquired for pre-running open-condition evaluation only
```

These states are not acquired for dispatch:

```text
PRE_REVIEW
  pending approval / preparation

TERMINAL
  closed state
```

Temporary restriction is not listed here because it is not a state. It is the
same active band with a future `timeSlot`. Before an ordinary future time is
due, the band scan does not return it; once due, it is interpreted as the same
band. Hard pause is the reserved `PAUSE_TIME_SLOT` form and is not an
ordinary due-later delay. It leaves held mode only through exact observed-score
release/resume or owner terminal finality.

Acquire returns candidate task ids only. It does not prove work exists, admit a
worker, claim work, or accept a result.

The score owner exposes an exact single-band query primitive:

```text
acquire_band_task_candidates(band, beforeTimeMillis, limit)
```

It accepts positive non-terminal bands only, converts the exclusive millisecond
horizon into the internal time-slot range, and returns ordered Task ids from
that band. Callers do not construct score bounds or decode returned rows.

After acquire, assignment-dispatch asks the owning planes to validate their own
facts:

```text
task lifecycle owner:
  task shell exists
  task shell is not terminal
  lifecycle gate / time coordinate permits a scheduling round
  policy snapshot is current enough for the round

TaskItemScoreBandCore:
  a due TaskItem score exists now, or no-work evidence is returned
  final Item claim happens only through TaskItemScoreBandCore

TaskRuntime:
  the corresponding canonical TaskItem record exists for DeliverSeed creation

worker-runtime owner:
  selected worker can be acquired and admitted now
```

The score-band boundary is therefore:

```text
score state says which bounded action may run
RUNNING_VISIBLE may enter dispatch after validation, or consume a no-work /
no-worker / contention classification through same-band rewrite
PRE_DISPATCH_VISIBLE runs activation check
TaskItemScoreBandCore proves whether there is a due TaskItem score now
worker-runtime proves whether a concrete worker can be admitted now
```

An acquired `RUNNING_VISIBLE` score first asks TaskItemScoreBandCore for bounded
Item availability evidence. If no due TaskItem exists and `suffix > 00`, scheduling keeps
the task in `RUNNING_VISIBLE`, writes the next no-work recheck time, and
decrements `suffix`. If no due TaskItem exists and `suffix == 00`, the task
closes. If work exists, assignment-dispatch continues through worker admission,
final item claim, and deliver seed creation. Append, result, and read paths do
not directly refresh the live score.

The append owner does not prove Task liveness before accepting an Item. Its
success contract ends at accepted-item persistence. Ingress/product policy may
reject append using a bounded Task status cache, close-append tag, or tombstone,
but stale policy observation may still admit a late item. That item has no
consumption guarantee and cannot reopen a terminal score. TTL, repeated cleanup,
generation-scoped keys, or another retention mechanism owns orphan Item
removal after physical cleanup races.

This makes no-work closure a deliberate latency tradeoff. If an item is
appended while the task is parked by a future `RUNNING_VISIBLE` no-work recheck
score, scheduling may discover it later when that score becomes due. If the
exhausted round closes the task before a later append arrives, that ordering is
accepted by score-band. Ingress policy may reject the append before persistence;
if stale ingress evidence accepts it, the item remains terminal residue and
does not create an owner transition.

## Assignment-Dispatch Protocol

The target protocol deliberately keeps Task score-band, TaskRuntime records,
TaskItemScoreBandCore, worker-runtime, and transport separate. The active loop belongs to
assignment-dispatch; task score-band only supplies query plus same-band
time/suffix rewrite, general positive rewrite, terminal close, and
score-hold release primitives:

```text
1. choose task score scan range and limit according to active band order
2. acquire task ids from task score-band single-band query(band, horizon, limit)
3. load task score state and validate gate / time coordinate
4. if RUNNING_VISIBLE:
     ask TaskItemScoreBandCore for bounded due-Item evidence
     if no work and suffix > 00, rewrite RUNNING_VISIBLE with next time and
     suffix-1
     if no work and suffix == 00, close to TERMINAL
     acquire and admit worker through worker score-band / worker-runtime
     load TaskItem records through TaskRuntime
     let TaskItemScoreBandCore perform the final Item claim
     produce deliver seed for transport
     rewrite to RUNNING_VISIBLE after the round classification
5. if PRE_DISPATCH_VISIBLE:
     evaluate pre-running open conditions only
     if satisfied, write RUNNING_VISIBLE
     if not satisfied, leave PRE_DISPATCH_VISIBLE unchanged for a later bounded
     activation retry
```

The Item availability read may be cheap and non-consuming. The final claim
belongs to TaskItemScoreBandCore and should happen only when the scheduling round has
enough worker-admission evidence to avoid consuming an Item without a viable
dispatch path.

If final item claim fails after worker admission, the scheduling round releases
or compensates worker admission through worker-runtime and classifies the round
evidence. Task score-band does not own that compensation state.

## Score Update Discipline

Task score updates are intentionally narrow.

### Acquire-Range Rewrite

Objects acquired by a range + limit query must be classified by the
assignment-dispatch round that observed them:

```text
task score due
  -> assignment-dispatch acquires task
  -> lifecycle / Task Item / worker-runtime validation produces evidence
  -> score owner writes the next score through the score-write stale fence
```

This rewrite is not required to make the task schedulable; the task was already
in the acquire range. It prevents running candidates from spinning at a
dominant score after a real round classification. The built-in
`PRE_DISPATCH_VISIBLE` activation check is different: a false result does not
consume suffix or require an activation-owned score write. Candidate allocation
may independently rotate its same-band time coordinate while preserving suffix.

The rewrite exists for:

```text
priority
fairness
anti-spin
no-worker-match delay
contention delay
large-backlog requeue
scheduled retry visibility
```

If the stored score has already changed, the score-write stale fence fails and the
newer score wins. That also counts as consuming the acquired candidate.

### Owner-Transition Writes

Tasks outside the acquire range should not be periodically refreshed because
unrelated evidence changed. Non-scheduling score writes are limited to explicit
owner commands that move the current band through the transition direction rule:

```text
PRE_REVIEW -> PRE_REVIEW owner mutation | PRE_DISPATCH_VISIBLE | TERMINAL
active band -> same-band future hold score | same-band expected-score hold release score | TERMINAL
any non-terminal band -> TERMINAL
```

Activation fact changes are not direct score writes. They update activation
owner truth; an acquired `PRE_DISPATCH_VISIBLE` scheduling round later reads those
facts and either moves the band to `RUNNING_VISIBLE` or leaves
`PRE_DISPATCH_VISIBLE` unchanged for a later bounded retry.
Candidate-worker allocation is one such evidence write: it may discover Tasks
through the score axis, but its match/append operation neither reads decoded
Task score state nor gates publication on a score transition.

### Non-Triggers

These update their own owner truth and do not trigger generic score refresh:

```text
append item
result success
Item same-tag retry movement
recent-final receipt
result lookup
trace materialization
read-model projection
```

They may be observed later when an owner command or acquired scheduling round
computes a new score.

## Score Write Categories

These categories describe caller responsibility only. They are not a parameter
on the kernel primitive.

| Category | May write task score? | Allowed shape |
| --- | --- | --- |
| score-acquired assignment-dispatch round | yes | Decode score, validate owner facts, run the band action, then call same-band time/suffix rewrite, general positive rewrite, terminal close, or score hold release. |
| initialization owner | yes, once | Establish the first score during Task creation; it cannot reacquire or rewrite an existing score. |
| owner command | yes, declared transitions only | Validate owner facts, then approve/reject/pause/resume/cancel/close through the matching transition primitive. It cannot perform generic cadence or budget rewrites. |
| resource / backlog / evidence write | no direct live score | Metadata/config update, Item append, Item retry/outcome movement, and result evidence update their own truth without a Task-score lease. Later scheduling or an owner command may observe them. |
| read projection / trace | no | Observability only. |

## Transition Execution Rules

Task score transitions are fact-driven, not external-event-driven:

```text
1. decode the stored current score into currentBand at write time
2. validate owner facts required by that band
3. choose target coordinates according to the transition direction rule
4. encode target score inside the score primitive
5. reject negative-to-positive rewrite, stale timeSlot, or higher-tag
   cross-band movement
6. write score with the owner facts that the score protects
```

For release/resume, use the score-hold release primitive instead of ordinary positive
rewrite. It is intentionally separate because it may lower
`timeSlot` after exact `observedHoldScore` match. Terminal close is also
separate because final negative scores are high-priority writes over any current
positive score and no-op if the score is already terminal.

## Atomicity Boundaries

Task score updates must be atomic only with the task scheduling visibility facts
they protect. They must not absorb Task Item claim state or result-finality
state.

Important boundaries:

```text
PRE_REVIEW gate command
  owner review/control fact + PRE_REVIEW fresh score, PRE_DISPATCH_VISIBLE score, or
  TERMINAL score

PRE_DISPATCH_VISIBLE activation scheduling round
  read activation facts + write RUNNING_VISIBLE when satisfied
  otherwise leave PRE_DISPATCH_VISIBLE unchanged; no suffix consumption or
  automatic hold

active-band temporary restriction
  same active band + future timeSlot; hard pause uses PAUSE_TIME_SLOT

terminal close
  terminal/closed fence + negative immutable terminal score before physical
  cleanup

scheduling round rewrite
  acquired score state + round classification + next score

policy command that changes scheduling visibility
  policy/gate fact + score write
```

Separate owner boundaries:

```text
TaskItemScoreBandCore
  owns Item record, ACTIVE acquire, claim lease, same-tag retry movement, and
  monotonic outcome promotion

result owner
  owns result finality, recent-final barriers, and result read projection

task score owner
  owns the score value, decode rules, bounded query primitive, and
  same-band time/suffix rewrite / positive rewrite / terminal close /
  score-hold release primitives

assignment-dispatch
  owns scan range choice, batch limit, candidate classification, and request for
  the next score write
```

Do not add a distributed lock around task scheduling by default. The owner-local
transition should carry the concurrency control for its own facts.
Multiple tasks may be processed concurrently. For one task, score writes must be
serialized by a per-task writer or guarded by a score-write stale fence. The
required invariant is that a stale scheduling round cannot overwrite a newer
budget rewrite, temporary restriction score, or terminal score.

## Failure And Stale Handling

Score is an index, so stale candidates are normal.

Rules:

- acquired score but task shell missing: reject candidate, clean opportunistically;
- acquired score but task terminal fence exists: reject candidate, write
  negative terminal score or clean according to owner policy;
- `PRE_DISPATCH_VISIBLE` acquired and open condition is satisfied: transition to
  `RUNNING_VISIBLE`;
- `PRE_DISPATCH_VISIBLE` acquired and open condition is still false: leave the
  score unchanged; a later bounded activation round may retry;
- `RUNNING_VISIBLE` acquired but TaskItemScoreBandCore reports no due TaskItem
  and `suffix > 00`: rewrite `RUNNING_VISIBLE` with a later no-work recheck
  time and `suffix - 1`;
- `RUNNING_VISIBLE` acquired but TaskItemScoreBandCore reports no due TaskItem
  and `suffix == 00`: close to `TERMINAL`; close reason is
  metadata/result/trace, not score;
- `RUNNING_VISIBLE` acquired but no worker matches and `suffix > 00`: stay in
  `RUNNING_VISIBLE` with a future score according to policy;
- `RUNNING_VISIBLE` acquired but no worker matches and `suffix == 00`: stay in
  `RUNNING_VISIBLE` with a same-band hold/backpressure score according to
  policy;
- worker admission contends/fails: stay in the active running state and
  future-score according to policy;
- stale scheduling rewrite observes that the stored current score is a newer
  hold score or terminal score: reject the rewrite and keep the newer score;
- temporary restriction score must only be written for active bands, never for
  `PRE_REVIEW` or `TERMINAL`;
- late result with no matching active work cannot rewrite live score directly;
  result-side convergence may only close visibility through the lifecycle owner.

Stale handling must be bounded. Do not scan the namespace to repair task score.

## Policy Seams

Score-band mechanism should stay stable while policy remains replaceable.

Policy owns:

```text
no-worker-match delay
no-work recheck delay
activation condition
contention delay
large-backlog requeue delay
healthy/scarce match delay
scheduled retry score
scan range / horizon choice
batch limit
temporary restriction time policy for active bands
initial RUNNING_VISIBLE same-band budget
same-band exhausted action for budgeted source bands
pre-review timeSlot source and suffix state-code values
terminal closed marker / closed score key
```

Mechanism owns:

```text
linear score axis
state-aware bounded range + limit query
owner validation after acquire
same-band time/suffix rewrite / positive rewrite / terminal close / score hold release
task scheduling visibility transition boundaries
no event-driven scheduling dependency
no broad refresh from low-value observations
```

## Guardrails

- Do not use score absence as terminal proof.
- Do not treat positive score as schedulable. Positive score means
  non-terminal/mutable; scheduling eligibility still comes only from the active
  tag allow-list.
- Do not rewrite a negative terminal score. Terminal is final and immutable;
  retention may physically remove residue but must not move it back into a
  positive band.
- Do not lower `timeSlot` on a positive score write except for release/resume
  with exact `observedHoldScore` match.
- Do not let append write or refresh task score.
- Do not make append prove Task liveness or promise eventual consumption.
- Do not reopen a terminal Task because a late item exists in backlog. Ingress
  owns append eligibility and retention owns invalid terminal residue.
- Do not let result/retry write live task score.
- Do not put a timer, periodic recheck loop, or pagination cursor inside the
  task-score owner.
- Do not treat same-band false recheck as a task-score pagination problem.
  Assignment-dispatch owns fallback horizon, quota, ordering, and backoff while
  keeping `RUNNING_VISIBLE` consumption first.
- Do not let dispatch-visible `RUNNING_VISIBLE` candidates spin at the same
  dominant score after classification; rewrite, move, close, clean, or lose to
  the score-write stale fence. `PRE_DISPATCH_VISIBLE` is intentionally exempt
  from suffix-budget consumption: false activation remains eligible for later
  bounded retry and never auto-pauses because suffix reached zero.
- Do not use event delivery as the only trigger for scheduling, retry, or
  no-work closure.
- Do not use an event queue as ready-task backlog or as a second scheduling
  index.
- Do not collapse active scheduling into one broad range scan across tags.
  Assignment-dispatch must scan active bands through separate band ranges and
  must not include terminal markers or positive inactive tags such as
  `PRE_REVIEW` in active acquire.
- Do not let `PRE_DISPATCH_VISIBLE` enter worker admission, Item claim, or
  DeliverSeed creation; assignment-dispatch may scan it only for activation checks.
- Do not write a scheduling restriction score for `PRE_REVIEW` or `TERMINAL`.
  The owner-local `PRE_REVIEW` creation lease is permitted only for
  lease-controlled Task creation; kernel code stores its suffix opaquely and
  never interprets it. It
  never enters active acquire. Active bands may be held only by rewriting the
  same active band with a future time coordinate.
- Do not treat hard pause as ordinary future delay. `PAUSE_TIME_SLOT` is a
  reserved hard hold: no scheduling-round suffix consumption, no positive
  cross-band progress, and no release to a different tag.
- Do not rely on tag direction alone to protect temporary restriction mode;
  because it preserves the same active tag, use per-task write ordering or
  a score-write stale fence.
- Do not close `RUNNING_VISIBLE` after a no-work classification without applying
  the owner policy to the decoded suffix budget and current Item evidence.
- Do not put Item claim/current occupancy, Item retry/outcome movement, or result
  finality classification inside Task score-band atomicity.
- Do not use current implementation classes as target owner proof for this
  mechanism; this document defines the new owner split.
- Do not create a second serving ready-task scheduling index after score
  cutover.
- Do not let `Task.status`, read projections, or trace materialization drive
  dispatch.
- Do not let task score select workers.
- Do not make task score responsible for precise active claim timeout.
- Do not use Redis Stream / PEL as the task claim owner in this shape.
- Do not explain service discovery from score-band absence.
