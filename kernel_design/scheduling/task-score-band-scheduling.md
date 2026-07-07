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
is the task terminal?
which worker should run it?
is result finality accepted?
```

## Relationship To Current Runtime

This document defines a target mechanism. Current task runtime code can be used
only as a failure-mode and invariant reference, not as the target owner model.

Useful lessons from the current runtime:

```text
bounded acquire is mandatory
duplicate claim / duplicate final must be impossible in their owning planes
stale candidates are normal and must be cheap to reject
paused / terminal tasks must be fail-closed for new dispatch
trace evidence is valuable for proof but must not become runtime truth
```

What must not be copied into task score-band:

```text
ready backlog ownership
item claim ownership
current work occupancy ownership
scheduled retry frame ownership
result finality ownership
result read-model ownership
```

Those are separate owners. Task score-band is only the active scheduling
visibility index for task ids.

## Core Model

Task score is an acquire index, not task lifecycle truth.

Core mechanics:

```text
tag decides lifecycle direction
epochSecond decides same-band lease / recheck / freshness direction
suffix decides same-band budget / tie-break / owner-local code
write-time stored-score/CAS prevents stale overwrite
terminal close and lease release use exact observed-score fences
transition direction rule prevents lifecycle regression
```

Lifecycle progresses left by lower tag or terminal score:

```text
PRE_REVIEW(3) -> READY_APPROVED(2) -> RUNNING_VISIBLE(1) -> TERMINAL(<0)
```

Scheduling suppression, retry, hold, and lease move right inside the same band
by writing a later `epochSecond`. Release/resume is the only right-to-left lease
shortcut and must carry the exact `observedLeaseScore`.

Rules:

- score is one sortable value owned by the task active-acquisition score owner;
- score absence has no business meaning to score-band;
- score-band does not explain service discovery or why a task is absent;
- acquire is a bounded range + limit query;
- task score-band does not own pagination cursors, scan ordering, or no-op
  pacing;
- dispatch-visible candidates must be classified and moved, closed, cleaned, or
  rejected by the score-write stale fence;
- acquired tasks still require lifecycle/gate validation and work-item owner
  validation before dispatch;
- append, result, retry frame write, and result lookup are not score refresh
  triggers.

Conceptual flow:

```text
assignment-dispatch pacing round
  -> task score query(range, limit)
  -> task lifecycle / gate / epoch validation
  -> work-item owner reports or claims claimable work
  -> worker score-band acquire / admission
  -> dispatch selected work through transport
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
only path that discovers backlog, retry lease key, worker contention, or
no-work closure.

Examples:

```text
append item
  -> writes backlog truth
  -> does not write task score
  -> does not emit a default wakeup
  -> does not become RUNNING_VISIBLE correctness

READY_APPROVED
  -> activation facts may change outside the score owner
  -> they do not directly promote the task
  -> scheduling acquire evaluates activation and writes RUNNING_VISIBLE if ready

RUNNING_VISIBLE with no current work
  -> remains RUNNING_VISIBLE with a later score and reduced suffix budget
  -> append does not have to wake it
  -> work discovery and no-work closure come from owner recheck / policy budget
  -> append may wait until the next RUNNING_VISIBLE score becomes due
  -> append after exhausted no-work closure must not reopen or backdate the old
     score

allowed key transition event, if introduced later
  -> may shorten recheck latency
  -> may be dropped or coalesced
  -> cannot be the only scheduling trigger

RUNNING_VISIBLE
  -> worker / transport evidence may be read during owner validation
  -> owner validation decides whether dispatch can happen

result evidence
  -> result owner validates current work hash
  -> live task score changes only through owner-approved rewrite
```

Explicit human gates are the exception. `PRE_REVIEW` may require an approve or
reject command because its policy is intentionally human-gated. That command is
not ordinary evidence; it is an authoritative owner command that still validates
the current state before moving to `READY_APPROVED` or `TERMINAL`.

## Score Write Categories

Task score writes come from several business-side categories, but those
categories are not kernel transition inputs:

```text
score-acquired scheduling round
  -> owned by assignment-dispatch-scheduling
  -> computes the next score after a bounded scheduling action
  -> READY_APPROVED: evaluate activation facts
  -> RUNNING_VISIBLE: attempt bounded assignment-dispatch

owner command
  -> handles human gates and manual controls
  -> create, approve, reject, cancel, write temporary restriction, discard

owner-evidence-write
  -> updates its own truth only
  -> append, result, retry frame, worker evidence, transport evidence, trace
  -> does not directly refresh task score
```

The score lease coordinate itself is only a lease/recheck coordinate. The
current epoch makes a score member acquirable; it does not complete the
transition without owner validation.
Non-score gates remain blocked until an owner command changes the underlying
fact.

## Score Bands And Encoding

Task score-band uses four score bands. Temporary restriction is not a separate
band; pause/block/hold is represented by rewriting the same active band with a
future lease key. A hard pause uses the maximum 10-digit `epochSecond`
coordinate. These are scheduling visibility states, not a
second business lifecycle. Kernel code should branch on the decoded band and
owner validation result, not on concrete product events.

Positive non-terminal bands use a fixed segmented numeric score:

```text
score = tag * TAG_FACTOR + epochSecond * SUFFIX_FACTOR + suffix

tag = score / TAG_FACTOR
epochSecond = (score % TAG_FACTOR) / SUFFIX_FACTOR
suffix = score % SUFFIX_FACTOR
```

`epochSecond` is a sortable second-granularity coordinate. For
`RUNNING_VISIBLE` and `READY_APPROVED`, it is the next scheduling/recheck
second. For `PRE_REVIEW`, it is the owner mutation freshness second. Kernel code
does not interpret review business flow; it only rejects stale positive writes
whose target `epochSecond` is not newer than the stored `epochSecond`, except
for observed-score release/resume.
`suffix` is a bounded two-digit same-band remaining schedule budget. `00` means
same-band budget exhausted; `01..99` means that many same-band scheduling
rewrites remain for scheduling bands. For `PRE_REVIEW`, `suffix` is an
owner-defined review state code. Kernel code does not validate suffix ordering.
It is not work-item retry truth and must not become the item retry counter
owner.

```text
RUNNING_VISIBLE_TAG = 1
READY_APPROVED_TAG = 2
PRE_REVIEW_TAG = 3

SUFFIX_FACTOR = 100
EPOCH_FACTOR = 10_000_000_000
MAX_EPOCH_SECOND = 9_999_999_999
PAUSE_EPOCH_SECOND = MAX_EPOCH_SECOND
TAG_FACTOR = EPOCH_FACTOR * SUFFIX_FACTOR

TERMINAL score = -closedScoreKey
closedScoreKey = closedEpochSecond * SUFFIX_FACTOR + closedSuffix
closedScoreKey > 10
```

Tag `0` is deliberately unused so decode never depends on a missing high-order
tag. With a 10-digit `epochSecond` coordinate and two-digit suffix, positive
scores remain
well under Redis sorted-set double exact-integer limits. If the coordinate width
or suffix width changes, the owner must re-check exact numeric representation.

Use `score(TAG, epochSecond, suffix)` below as shorthand for the formula above.

| Band | Score encoding | Acquirable by scheduling | Dispatch-eligible | Kernel action after acquire | Notes |
| --- | --- | --- | --- | --- | --- |
| `PRE_REVIEW` | `score(PRE_REVIEW_TAG, ownerMutationEpochSecond, reviewStateCode)` | no | no | none | Positive mutable state, not schedulable. The review owner defines and validates suffix state semantics. Kernel only requires positive non-release writes to carry a larger `epochSecond`. |
| `RUNNING_VISIBLE` | `score(RUNNING_VISIBLE_TAG, nextDispatchOrRecheckEpochSecond, remainingBudget)` | when included by the running scan | yes, after owner validation, work evidence, worker admission, and work claim | run bounded assignment-dispatch | Also handles `NO_WORK`, `NO_WORKER`, and contention classifications. Temporary restriction uses this same tag with a future `epochSecond`; hard pause uses `PAUSE_EPOCH_SECOND`. |
| `READY_APPROVED` | `score(READY_APPROVED_TAG, nextReadyRecheckEpochSecond, remainingBudget)` | when included by the ready scan horizon | no | evaluate pre-running open facts only | Pre-running open-condition recheck. It must not create a deliver seed. If still not open and budget remains, scheduling rewrites the same band with `remainingBudget - 1`. If budget is exhausted and still not open, write a same-band pause/hold score. |
| `TERMINAL` | negative closed score key | no | no | none | Negative score space is final and immutable. Close reason belongs to meta/result/trace. |

Band order is consumption-first:

```text
RUNNING_VISIBLE < READY_APPROVED < PRE_REVIEW
```

This order keeps cross-band lifecycle movement numerically downward:
`PRE_REVIEW -> READY_APPROVED -> RUNNING_VISIBLE -> TERMINAL`. Same-band
recheck/hold writes may numerically increase because `epochSecond` moves
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
  scheduling may continue into worker admission, work hash claim, and deliver
  seed creation
```

`READY_APPROVED` and `RUNNING_VISIBLE` both use same-band recheck budgets, but
they protect different allowed actions:

```text
READY_APPROVED
  running pre-open condition
  when acquired: scans evaluate worker-candidate / open facts
  ready: move to RUNNING_VISIBLE
  not open and suffix > 00: rewrite READY_APPROVED with next epoch and suffix-1
  not open and suffix == 00: pause/hold in READY_APPROVED

RUNNING_VISIBLE
  running-stage dispatch/recheck condition
  when acquired: scans work, worker, retry, contention, and dispatch facts
  work dispatched: rewrite RUNNING_VISIBLE by policy
  no work and suffix > 00: rewrite RUNNING_VISIBLE with next no-work-recheck epoch and suffix-1
  no work and suffix == 00: close to TERMINAL
  no worker / contention and suffix > 00: rewrite RUNNING_VISIBLE with next recheck epoch and suffix-1
  no worker / contention and suffix == 00: pause/hold in RUNNING_VISIBLE
```

`READY_APPROVED` is a real band because an approved task may still need
pre-running open conditions before running, such as a candidate-work count
range, batch-size threshold, ready retry budget, priority window, or policy
gate. `nextReadyRecheckEpochSecond` and the initial same-band budget are
written by approval / activation policy. The task does not derive these values
from its own shell. Typical open facts include worker-candidate conditions such
as minimum matching worker count. Acquiring `READY_APPROVED` evaluates open
facts; it must not create a deliver seed.

Scheduling scan ranges are per active acquisition tag. The default active order
is running consumption/recheck first, then ready activation:

```text
running scan:
  ZRANGEBYSCORE task:score
    score(RUNNING_VISIBLE_TAG, 0, 0)
    score(RUNNING_VISIBLE_TAG, currentEpochSecond, 99)
    LIMIT batchSize

ready scan:
  ZRANGEBYSCORE task:score
    score(READY_APPROVED_TAG, 0, 0)
    score(READY_APPROVED_TAG, readyScanHorizonEpochSecond, 99)
    LIMIT batchSize
```

Do not collapse these into one broad scan from `RUNNING_VISIBLE_TAG` through
`READY_APPROVED_TAG`. A paused active tag uses `PAUSE_EPOCH_SECOND` and must
stay invisible until released by observed-score resume. Terminal scores are
negative and must not enter the active assignment-dispatch batch.

Assignment-dispatch chooses the scan horizon per band. Normally each scan uses
`epochSecond <= now`. If a policy deliberately scans a small future horizon,
that horizon must stay below `PAUSE_EPOCH_SECOND`; hard-pause scores are not
future-prefetch candidates. A future horizon is an early recheck optimization,
not a close-time decision. If the relevant facts remain false, same-band
scheduling rewrites the score to the next `epochSecond` and decrements
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

Keeping the same score after a successful acquire is not the normal path. A
same-band false classification is consumed by writing a later `epochSecond` and
`suffix - 1`, or by executing the exhausted action when `suffix == 00`.
Assignment-dispatch scans `RUNNING_VISIBLE` before `READY_APPROVED`, so ready
activation rechecks must not block real running consumption. Repeated no-work,
no-worker, contention, and ready rechecks are bounded by suffix budget plus
policy-owned horizon, quota, ordering, and backoff.

Same-band scheduling rewrites obey:

```text
target tag == current tag
current suffix > 00
target epochSecond > acquire currentEpochSecond
target suffix = current suffix - 1
```

Positive non-terminal writes obey the freshness rule by default:

```text
target epochSecond > stored epochSecond
```

This applies to scheduling rewrites, owner transitions, pause/block/hold, and
cross-band lifecycle movement. Same-task score refresh is intentionally
second-granularity. Multiple non-release updates for the same task within the
same second must be rejected, coalesced, or retried later by the owner. Do not
upgrade to millisecond precision until a concrete invariant needs it.

Cross-band lifecycle transitions also obey the tag direction implied by the
band order:

```text
PRE_REVIEW -> READY_APPROVED -> RUNNING_VISIBLE -> TERMINAL
```

`READY_APPROVED` is an optional intermediate band, not a required checkpoint.
The kernel permits lifecycle jumps toward lower tags, such as
`PRE_REVIEW -> RUNNING_VISIBLE`, when the business owner has already validated
the required facts. It only rejects movement from a lower tag back to a higher
tag, such as `RUNNING_VISIBLE -> READY_APPROVED`. Same-band recheck/hold writes
may still raise the numeric score because `epochSecond` is later.

If `suffix == 00`, scheduling must not write another ordinary same-band recheck.
It must execute the exhausted action for the current band:

```text
READY_APPROVED exhausted
  -> same-band pause/hold score

RUNNING_VISIBLE exhausted after NO_WORK
  -> TERMINAL

RUNNING_VISIBLE exhausted after NO_WORKER / contention
  -> same-band pause/hold score
```

Release/resume is the explicit exception. It releases a known lease/hold score:

```text
stored score must still equal observedLeaseScore
target score is derived from observedLeaseScore with a release epochSecond
target suffix is copied from observedLeaseScore
otherwise return STALE / NOOP and keep the newer hold
```

Pause/block/hold uses the ordinary freshness rule: write the same active tag
with a future `epochSecond`; hard pause writes `PAUSE_EPOCH_SECOND`.
Release/resume uses
`observedLeaseScore` as the stale fence; matching that exact score proves no
newer hold, terminal close, or scheduling rewrite has happened.

`PRE_REVIEW` same-band owner transitions obey the same positive-write freshness
rule:

```text
target tag == PRE_REVIEW_TAG
target epochSecond > current epochSecond
suffix is owner-defined review state code
owner validates the business state, material completeness, permission, and
allowed suffix transition
```

The kernel does not know whether `suffix=30` means draft, uploaded, rejected, or
submitted. Those names and allowed transitions belong to the review owner. The
kernel only prevents an older `epochSecond` write from overwriting a newer
`PRE_REVIEW` score. When two owner mutations occur in the same wall-clock
second, the review owner should compute:

```text
nextOwnerMutationEpochSecond = max(nowEpochSecond, currentEpochSecond + 1)
```

This keeps the second-granularity score model monotonic without requiring
millisecond precision.

Cross-band writes, such as `PRE_REVIEW -> RUNNING_VISIBLE` or
`READY_APPROVED -> RUNNING_VISIBLE`, are allowed only by the transition
direction rule and the write-time stale fence. `targetSuffix` is a score
coordinate chosen only after owner or policy validation; if omitted, the
primitive preserves the stored suffix. Cross-band owner transitions initialize
suffix from policy. Ordinary same-band owner transitions preserve suffix unless
the current band owner defines suffix as owner-local state, such as
`PRE_REVIEW`. A budget reset is a separate owner-authorized reset transition,
not release/resume and not a generic event side effect.

After acquire, decode `tag` and `suffix`:

```text
if acquired READY_APPROVED and open condition is satisfied:
  targetBand = RUNNING_VISIBLE
if acquired READY_APPROVED and open condition is false and suffix > 00:
  targetBand = READY_APPROVED with next epochSecond and suffix-1
if acquired READY_APPROVED and open condition is false and suffix == 00:
  targetBand = READY_APPROVED paused/held score
if acquired RUNNING_VISIBLE and no work exists and suffix > 00:
  targetBand = RUNNING_VISIBLE with next no-work-recheck epochSecond and suffix-1
if acquired RUNNING_VISIBLE and no work exists and suffix == 00:
  targetBand = TERMINAL
if acquired RUNNING_VISIBLE and no worker / contention and suffix > 00:
  targetBand = RUNNING_VISIBLE with next worker-recheck epochSecond and suffix-1
if acquired RUNNING_VISIBLE and no worker / contention and suffix == 00:
  targetBand = RUNNING_VISIBLE paused/held score
```

Temporary restriction is deliberately not listed as a band. Pause/block writes
the same active tag with a future `epochSecond`; hard pause writes
`PAUSE_EPOCH_SECOND = 9_999_999_999`. Ordinary future holds eventually become
due and are interpreted as the original band. Hard pause is released through the
lease-release primitive with exact `observedLeaseScore`. Deadline-style task
closure is not part of the first score-band model; closure is driven by
exhausted same-band budget.

## Transition Direction Rule

Transition validation is expressed as direction rules, not business-event
enumerations. Product commands, scheduling rounds, and business events validate
their own owner facts before asking the kernel to write a score. Kernel
transition code sees only the stored score and requested target coordinates:

```text
storedScore
targetBand?
targetEpochSecond
targetSuffix?
owner validation
bounded scheduling round result
```

Owner validation happens before the kernel primitive. Do not name business
events inside the kernel rule.

Hot-path same-band epoch rewrite primitive:

```text
rewrite_same_band_epoch(taskId, expectedBand, targetEpochSecond):
  storedScore = read_current_score(taskId)

  if storedScore < 0:
    reject TERMINAL_IMMUTABLE

  current = decode_positive(storedScore)
  require band(current.tag) == expectedBand
  require current.tag in {RUNNING_VISIBLE_TAG, READY_APPROVED_TAG, PRE_REVIEW_TAG}
  require 0 <= current.epochSecond <= MAX_EPOCH_SECOND
  require 0 <= targetEpochSecond <= MAX_EPOCH_SECOND
  require 0 <= current.suffix <= MAX_SUFFIX
  require targetEpochSecond > current.epochSecond

  write score(current.tag, targetEpochSecond, current.suffix)
```

This is the preferred primitive when the caller only needs to move the same tag
rightward in time. It preserves suffix and avoids the broader target-band /
target-suffix surface.

General positive rewrite primitive:

```text
rewrite_score(taskId, expectedBand, targetBand?, targetEpochSecond, targetSuffix?):
  storedScore = read_current_score(taskId)

  if storedScore < 0:
    reject TERMINAL_IMMUTABLE

  current = decode_positive(storedScore)
  require band(current.tag) == expectedBand

  targetTag = tag(targetBand or expectedBand)
  targetSuffix = targetSuffix if supplied else current.suffix

  require current.tag in {RUNNING_VISIBLE_TAG, READY_APPROVED_TAG, PRE_REVIEW_TAG}
  require targetTag in {RUNNING_VISIBLE_TAG, READY_APPROVED_TAG, PRE_REVIEW_TAG}
  require 0 <= current.epochSecond <= MAX_EPOCH_SECOND
  require 0 <= targetEpochSecond <= MAX_EPOCH_SECOND
  require 0 <= current.suffix <= MAX_SUFFIX
  require 0 <= targetSuffix <= MAX_SUFFIX
  require targetEpochSecond > current.epochSecond

  if targetTag > current.tag:
    reject LIFECYCLE_REGRESSION

  write score(targetTag, targetEpochSecond, targetSuffix)
```

If `rewrite_score` is called without `targetBand` and `targetSuffix`, it is
equivalent to `rewrite_same_band_epoch` and should use the same hot path.

Terminal close is separate because it is destructive and final:

```text
close_score(taskId, observedScore, terminalScore):
  storedScore = read_current_score(taskId)
  require storedScore == observedScore
  require observedScore > 0
  require terminalScore < 0
  write terminalScore
```

Lease release is a separate primitive because it is the only legal way to move
the same tag to an earlier `epochSecond`:

```text
release_lease(taskId, observedLeaseScore, releaseEpochSecond):
  storedScore = read_current_score(taskId)
  require storedScore == observedLeaseScore
  observed = decode_positive(observedLeaseScore)
  require observed.tag in {RUNNING_VISIBLE_TAG, READY_APPROVED_TAG}
  require 0 <= releaseEpochSecond <= MAX_EPOCH_SECOND
  write score(observed.tag, releaseEpochSecond, observed.suffix)
```

The release primitive has no business-event meaning. It only proves that the
held score being released is still exactly the stored score. If another pause,
terminal close, or scheduling rewrite happened first, the exact-score match
fails and the release is stale.

The derived view is:

```text
PRE_REVIEW(3)
  normal target: PRE_REVIEW(3), READY_APPROVED(2), RUNNING_VISIBLE(1), TERMINAL(<0)

READY_APPROVED(2)
  normal target: READY_APPROVED(2), RUNNING_VISIBLE(1), TERMINAL(<0)

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
future `epochSecond`, the tag direction rule alone does not distinguish held
mode from ordinary future scheduling. The required protection is
single-task write ordering or a write-time stale fence. If assignment-dispatch
acquired an active score and a same-task owner transition has already written a
newer lease score or terminal score, the stale rewrite must fail instead of
replacing that newer score.

This permits multi-task concurrency while keeping single-task score writes
ordered by the current band. A single task may be protected by a per-task writer
or by a compare-and-set on the decoded current band; the kernel design does not
require a distributed lock.

## Acquire Semantics

Acquire is state-aware. It returns task ids whose score state can do useful
bounded work now. Active acquisition order is consumption-first:

```text
RUNNING_VISIBLE
  acquired for work evidence, worker admission, work hash claim, deliver seed
  creation, and no-work / no-worker recheck

READY_APPROVED
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
same active band with a future `epochSecond`. Before that epoch is due, the
band scan does not return it; once due, it is interpreted as the same band.

Acquire returns candidate task ids only. It does not prove work exists, admit a
worker, claim work, or accept a result.

After acquire, assignment-dispatch asks the owning planes to validate their own
facts:

```text
task lifecycle owner:
  task shell exists
  task shell is not terminal
  lifecycle gate / epoch permits a scheduling round
  policy snapshot is current enough for the round

work-item owner:
  claimable work exists now, or no-work evidence is returned
  final item claim happens only through the work-item owner

worker-runtime owner:
  selected worker can be acquired and admitted now
```

The score-band boundary is therefore:

```text
score state says which bounded action may run
RUNNING_VISIBLE may enter dispatch after validation, or consume a no-work /
no-worker / contention classification through same-band rewrite
READY_APPROVED runs activation check
work-item owner proves whether there is claimable work now
worker-runtime proves whether a concrete worker can be admitted now
```

An acquired `RUNNING_VISIBLE` score first asks the work-item owner for bounded
work evidence. If no claimable work exists and `suffix > 00`, scheduling keeps
the task in `RUNNING_VISIBLE`, writes the next no-work recheck epoch, and
decrements `suffix`. If no claimable work exists and `suffix == 00`, the task
closes. If work exists, assignment-dispatch continues through worker admission,
final item claim, and deliver seed creation. Append, result, and read paths do
not directly refresh the live score.

This makes no-work closure a deliberate latency tradeoff. If an item is
appended while the task is parked by a future `RUNNING_VISIBLE` no-work recheck
score, scheduling may discover it later when that score becomes due. If the
exhausted round closes the task before a later append arrives, that ordering is
accepted by score-band; intake/lifecycle policy must decide whether the append
is rejected, routed to a new task, or handled by an explicit owner transition.

## Assignment-Dispatch Protocol

The target protocol deliberately keeps score-band, work-item ownership,
worker-runtime, and transport separate. The active loop belongs to
assignment-dispatch; task score-band only supplies query plus same-band epoch
rewrite, general positive rewrite, terminal close, and lease-release primitives:

```text
1. choose task score scan range and limit according to active band order
2. acquire task ids from task score-band query(range, limit)
3. load task score state and validate gate / epoch
4. if RUNNING_VISIBLE:
     ask work-item owner for bounded claimable-work evidence
     if no work and suffix > 00, rewrite RUNNING_VISIBLE with next epoch and
     suffix-1
     if no work and suffix == 00, close to TERMINAL
     acquire and admit worker through worker score-band / worker-runtime
     let work-item owner perform the final item claim
     produce deliver seed for transport
     rewrite to RUNNING_VISIBLE after the round classification
5. if READY_APPROVED:
     evaluate pre-running open conditions only
     if satisfied, write RUNNING_VISIBLE
     if not satisfied and suffix > 00, rewrite READY_APPROVED with next epoch
     and suffix-1
     if not satisfied and suffix == 00, write READY_APPROVED pause/hold score
```

The work evidence read may be cheap and non-consuming. The final claim belongs
to the work-item owner and should happen only when the scheduling round has
enough worker-admission evidence to avoid consuming work without a viable
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
  -> lifecycle / work-item / worker-runtime validation produces evidence
  -> score owner writes the next score through the score-write stale fence
```

This rewrite is not required to make the task schedulable; the task was already
in the acquire range. It prevents candidates from spinning at a dominant score
after a real round classification. Same-band false classifications for
`READY_APPROVED` and `RUNNING_VISIBLE` also consume budget by writing the next
epoch and `suffix - 1`, unless the budget is already exhausted.

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
PRE_REVIEW -> PRE_REVIEW owner mutation | READY_APPROVED | TERMINAL
active band -> same-band future lease score | same-band expected-lease release score | TERMINAL
any non-terminal band -> TERMINAL
```

Activation fact changes are not direct score writes. They update activation
owner truth; an acquired `READY_APPROVED` scheduling round later reads those
facts and decides whether the band remains `READY_APPROVED`, becomes
`RUNNING_VISIBLE`, or holds because same-band budget is exhausted.

### Non-Triggers

These update their own owner truth and do not trigger generic score refresh:

```text
append item
result success
retry frame write
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
| score-acquired assignment-dispatch round | yes | Decode score, validate owner facts, run the band action, then call same-band epoch rewrite, general positive rewrite, terminal close, or lease release. |
| owner command | yes | Validate owner facts, then call same-band epoch rewrite, general positive rewrite, terminal close, or lease release. |
| owner-evidence-write | no direct live score | Update its own truth only; later scheduling or an owner command may observe it. |
| read projection / trace | no | Observability only. |

## Transition Execution Rules

Task score transitions are fact-driven, not external-event-driven:

```text
1. decode the stored current score into currentBand at write time
2. validate owner facts required by that band
3. choose target coordinates according to the transition direction rule
4. encode target score inside the score primitive
5. reject negative-to-positive rewrite, stale epochSecond, or higher-tag
   cross-band movement
6. write score with the owner facts that the score protects
```

For release/resume, use the lease-release primitive instead of ordinary positive
rewrite. It is intentionally separate because it may lower
`epochSecond` after exact `observedLeaseScore` match. Terminal close is also
separate because final negative scores require an exact observed-score fence.

## Atomicity Boundaries

Task score updates must be atomic only with the task scheduling visibility facts
they protect. They must not absorb work-item claim state or result-finality
state.

Important boundaries:

```text
PRE_REVIEW gate command
  owner review/control fact + PRE_REVIEW fresh score, READY_APPROVED score, or
  TERMINAL score

READY_APPROVED activation scheduling round
  read activation facts + write RUNNING_VISIBLE when satisfied
  otherwise decrement suffix and rewrite READY_APPROVED, or hold READY_APPROVED
  when suffix is exhausted

active-band temporary restriction
  same active band + future epochSecond; hard pause uses PAUSE_EPOCH_SECOND

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
work-item owner
  owns item readiness, current work hash claim, retry frame, and claim
  compensation

result owner
  owns result finality, recent-final barriers, and result read projection

task score owner
  owns the score value, decode rules, bounded query primitive, and
  same-band epoch rewrite / positive rewrite / terminal close / lease-release
  primitives

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
- `READY_APPROVED` acquired and open condition is satisfied: transition to
  `RUNNING_VISIBLE`;
- `READY_APPROVED` acquired and open condition is still false with `suffix >
  00`: rewrite `READY_APPROVED` with a later epoch and `suffix - 1`;
- `READY_APPROVED` acquired and open condition is still false with `suffix ==
  00`: write a same-band pause/hold score;
- `RUNNING_VISIBLE` acquired but the work-item owner reports no claimable work
  and `suffix > 00`: rewrite `RUNNING_VISIBLE` with a later no-work recheck
  epoch and `suffix - 1`;
- `RUNNING_VISIBLE` acquired but the work-item owner reports no claimable work
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
  lease score or terminal score: reject the rewrite and keep the newer score;
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
temporary restriction epoch policy for active bands
initial same-band budget per target band
same-band exhausted action per source band
pre-review epochSecond source and suffix state-code values
terminal closed marker / closed score key
```

Mechanism owns:

```text
linear score axis
state-aware bounded range + limit query
owner validation after acquire
same-band epoch rewrite / positive rewrite / terminal close / lease release
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
- Do not lower `epochSecond` on a positive score write except for release/resume
  with exact `observedLeaseScore` match.
- Do not let append write or refresh task score.
- Do not let result/retry write live task score.
- Do not put a timer, periodic recheck loop, or pagination cursor inside the
  task-score owner.
- Do not treat same-band false recheck as a task-score pagination problem.
  Assignment-dispatch owns fallback horizon, quota, ordering, and backoff while
  keeping `RUNNING_VISIBLE` consumption first.
- Do not let dispatch-visible `RUNNING_VISIBLE` candidates spin at the same
  dominant score after classification; rewrite, move, close, clean, or lose to
  the score-write stale fence. `READY_APPROVED` and `RUNNING_VISIBLE` same-band
  false classifications must also be consumed by `suffix - 1`, exhausted
  action, or stale-fence loss.
- Do not use event delivery as the only trigger for scheduling, retry, or
  no-work closure.
- Do not use an event queue as ready-task backlog or as a second scheduling
  index.
- Do not collapse active scheduling into one broad range scan across tags.
  Assignment-dispatch must scan active bands through separate band ranges and
  must not include terminal markers or positive inactive tags such as
  `PRE_REVIEW` in active acquire.
- Do not let `READY_APPROVED` enter worker admission, work claim, or deliver
  seed creation; assignment-dispatch may scan it only for activation checks.
- Do not write a temporary restriction score for `PRE_REVIEW` or `TERMINAL`.
  Active bands may be held only by rewriting the same active band with a future
  epoch.
- Do not rely on tag direction alone to protect temporary restriction mode;
  because it preserves the same active tag, use per-task write ordering or
  a score-write stale fence.
- Do not close `RUNNING_VISIBLE` after a no-work classification without applying
  the owner policy to the decoded suffix budget and current work evidence.
- Do not put work-item claim, current work occupancy, retry-frame mutation, or
  result finality inside task score-band atomicity.
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
