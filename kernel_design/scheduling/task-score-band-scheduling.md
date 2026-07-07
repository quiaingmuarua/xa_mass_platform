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

Rules:

- score is one sortable value owned by the task active-acquisition score owner;
- score absence has no business meaning to score-band;
- score-band does not explain service discovery or why a task is absent;
- acquire is a bounded range + limit query;
- task score-band does not own pagination cursors, scan ordering, or no-op
  pacing;
- dispatch-visible candidates must be classified and moved, closed, cleaned, or
  rejected by expected-score protection;
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
  -> task score expected-score update for fairness / anti-spin / future retry
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
  -> work/retry/empty-running recheck
  -> score rewrite or terminal closure
```

An event may at most ask the score owner to check sooner. It must not create the
only path that discovers backlog, retry lease key, worker contention, or
empty-running closure.

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

EMPTY_RUNNING
  -> must eventually recheck from score state
  -> append does not have to wake it
  -> work discovery and idle-close come from owner recheck / policy
  -> append before idleCloseAt may wait until the idle-close score becomes due
  -> append after idle-close closure must not reopen or backdate the old score

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
not ordinary evidence; it is an authoritative owner-transition that still validates
the current state before moving to `READY_APPROVED` or `TERMINAL`.

## Transition Entry Classes

Task score transitions have three entry classes:

```text
score-acquired scheduling round
  -> owned by assignment-dispatch-scheduling
  -> handles epochSecond / lease bands
  -> READY_APPROVED: evaluate activation facts
  -> RUNNING_VISIBLE: attempt bounded assignment-dispatch
  -> EMPTY_RUNNING: run idle-close check

owner-transition
  -> handles non-score-lease human gates and manual controls
  -> create, approve, reject, cancel, write temporary restriction, discard

owner-evidence-write
  -> updates its own truth only
  -> append, result, retry frame, worker evidence, transport evidence, trace
  -> does not directly refresh task score
```

The score lease coordinate itself is only a lease/recheck coordinate. The
current epoch makes a score member acquirable; it does not complete the
transition without owner validation.
Non-score-lease gates remain blocked until an owner-transition changes the
underlying fact.

## Score Bands And Encoding

Task score-band uses five score bands. Temporary restriction is not a separate
band; pause/block/hold is represented by rewriting the same active band with a
future or far-future lease key. These are scheduling visibility states, not a
second business lifecycle. Kernel code should branch on the decoded band and
owner validation result, not on concrete product events.

Non-negative active scheduling bands use a fixed segmented numeric score:

```text
score = tag * TAG_FACTOR + epochSecond * SUFFIX_FACTOR + suffix

tag = score / TAG_FACTOR
epochSecond = (score % TAG_FACTOR) / SUFFIX_FACTOR
suffix = score % SUFFIX_FACTOR
```

`epochSecond` is the sortable lease coordinate: due time, deadline, hold-until,
recheck-at, or far-future pause. `suffix` is a bounded two-digit ordering field
for same-second retry/ordinal/priority/tie-break decisions. It is not lifecycle
truth and must not become the retry counter owner. The first slice keeps
`suffix` in `00..99`.

```text
PRE_REVIEW_MIN = -10
PRE_REVIEW_MAX = -1

RUNNING_VISIBLE_TAG = 1
EMPTY_RUNNING_TAG = 2
READY_APPROVED_TAG = 3

SUFFIX_FACTOR = 100
TAG_FACTOR = 10_000_000_000 * SUFFIX_FACTOR

TERMINAL score = -closedScoreKey
closedScoreKey = closedEpochSecond * SUFFIX_FACTOR + suffix
closedScoreKey > 10
```

Tag `0` is deliberately unused so decode never depends on a missing high-order
tag. With a 10-digit epoch second and two-digit suffix, active scores remain
well under Redis sorted-set double exact-integer limits. If the time coordinate
or suffix width changes, the owner must re-check exact numeric representation.

Use `score(TAG, epochSecond, suffix)` below as shorthand for the formula above.

| Band | Score encoding | Acquirable by scheduling | Dispatch-eligible | Kernel action after acquire | Notes |
| --- | --- | --- | --- | --- | --- |
| `PRE_REVIEW` | `-10 <= score <= -1` | no | no | none | Human / owner gate. May allow append, edit, and review outside score-band. |
| `RUNNING_VISIBLE` | `score(RUNNING_VISIBLE_TAG, nextDispatchEpochSecond, suffix)` | when included by the running scan | yes, after owner validation and work claim | run bounded assignment-dispatch | Temporary restriction uses this same tag with a future or far-future `epochSecond`. When that epoch becomes due, running scan interprets it as normal `RUNNING_VISIBLE`. |
| `EMPTY_RUNNING` | `score(EMPTY_RUNNING_TAG, idleCloseEpochSecond, suffix)` | when included by the empty scan horizon | only if continue facts are true | evaluate post-running continue facts | Post-running close-condition deadline. Before deadline, false continue facts may keep the same score or lease-rewrite within the same band. At or after deadline, false continue facts close to `TERMINAL`. |
| `READY_APPROVED` | `score(READY_APPROVED_TAG, readyDeadlineEpochSecond, suffix)` | when included by the ready scan horizon | no | evaluate pre-running open facts only | Pre-running open-condition deadline. It must not create a deliver seed. Before deadline, false open facts may keep the same score or lease-rewrite within the same band. At or after deadline, false open facts close to `TERMINAL`. |
| `TERMINAL` | negative closed score key | no | no | none | Negative score space is not scheduling-acquired. `closedScoreKey` must not collide with `PRE_REVIEW` enum values. Close reason belongs to meta/result/trace. |

Band order is consumption-first:

```text
RUNNING_VISIBLE < EMPTY_RUNNING < READY_APPROVED
```

This order improves active consumption priority. It is not permission to run one
global range scan across tags. Assignment-dispatch must scan the allowed active
bands through separate band ranges and policy-owned quotas. Negative scores are
outside active scheduling by construction, so terminal state has both a sign
barrier and a band allow-list barrier.

The important split is:

```text
score-acquirable
  assignment-dispatch may pick the task id and run the band's owner check

dispatch-eligible
  scheduling may continue into worker admission, work hash claim, and deliver
  seed creation
```

`READY_APPROVED` and `EMPTY_RUNNING` are the same deadline template applied to
two different running stages:

```text
READY_APPROVED
  running pre-open condition
  before due: scans may evaluate worker-candidate / open facts
  before due and not open: keep the same score
  at or after due and still not open: close to TERMINAL

EMPTY_RUNNING
  running post-close condition
  before due: scans may evaluate backlog / retry / dispatchable work facts
  before due and still empty: keep the same score
  at or after due and still empty: close to TERMINAL
```

`READY_APPROVED` is a real band because an approved task may still need
pre-running open conditions before running, such as a candidate-work count
range, batch-size threshold, max-ready-stall deadline, priority window, or
policy gate. `readyDeadlineEpochSecond` is written by approval / activation
policy. It is the deadline coordinate for the open-condition deadline, the same
kind of score coordinate as `EMPTY_RUNNING.idleCloseEpochSecond`. The task does
not derive this key from its own shell. Typical open facts include
worker-candidate
conditions such as minimum matching worker count. Acquiring `READY_APPROVED`
evaluates open facts; it must not create a deliver seed.

Scheduling scan ranges are per active acquisition tag. The default active order
is running consumption first, then empty-running recheck, then ready activation:

```text
running scan:
  ZRANGEBYSCORE task:score
    score(RUNNING_VISIBLE_TAG, 0, 0)
    score(RUNNING_VISIBLE_TAG, currentEpochSecond, 99)
    LIMIT batchSize

empty scan:
  ZRANGEBYSCORE task:score
    score(EMPTY_RUNNING_TAG, 0, 0)
    score(EMPTY_RUNNING_TAG, emptyScanHorizonEpochSecond, 99)
    LIMIT batchSize

ready scan:
  ZRANGEBYSCORE task:score
    score(READY_APPROVED_TAG, 0, 0)
    score(READY_APPROVED_TAG, readyScanHorizonEpochSecond, 99)
    LIMIT batchSize
```

Do not collapse these into one broad scan from `RUNNING_VISIBLE_TAG` through
`READY_APPROVED_TAG`. A far-future temporary restriction in any active tag must
stay invisible until its own `epochSecond` is due. Terminal scores are negative
and must not enter the active assignment-dispatch batch.

Assignment-dispatch chooses the scan horizon per band. For `READY_APPROVED` and
`EMPTY_RUNNING`, the horizon may include scores whose decoded deadline is still
in the future. A candidate whose `epochSecond` is greater than the current epoch
is a pre-deadline evaluation: if the relevant facts are false, the score may be
left unchanged or rewritten inside the same band by policy; if they are true,
the task may move to `RUNNING_VISIBLE`.

There is no pagination cursor in the task-score owner. The caller repeats
bounded range scans. A successfully acquired candidate must be consumed by one
of these outcomes:

```text
rewrite score within the same band
keep the same score after a pre-deadline false check
move to another band
write negative terminal score
clean stale residue
fail expected-score / CAS because newer score already won
```

Keeping the same score after a pre-deadline false check is a deliberate
closed-loop fallback, not a task-score starvation repair. Assignment-dispatch
scans `RUNNING_VISIBLE` before `EMPTY_RUNNING` and `READY_APPROVED`, so no-op
deadline checks must not block real running consumption. If there are no
running tasks to consume, repeated `READY_APPROVED` / `EMPTY_RUNNING` no-op
checks are bounded idle work: policy owns the horizon, quota, ordering, and
backoff that decide how much idle recheck cost to spend.

Policy may alternatively consume a no-op candidate by rewriting the same band to
a later `epochSecond` and/or suffix. That is a lease/pacing rewrite, not a
lifecycle transition.

Same-band ordinary lease rewrites obey:

```text
target tag == current tag
target epochSecond >= current epochSecond
suffix remains bounded and policy-owned
```

Release/resume is the explicit exception:

```text
target tag == current tag
target epochSecond may move backward to now or a nearer time
stored score must still equal expectedLeaseScore
otherwise return STALE / NOOP and keep the newer hold
```

Cross-band writes, such as `READY_APPROVED -> RUNNING_VISIBLE` or
`EMPTY_RUNNING -> RUNNING_VISIBLE`, are allowed only by the transition matrix
and expected-score / CAS protection.

After acquire, decode `tag`. For deadline bands, the decoded `epochSecond` is
the deadline coordinate:

```text
if acquired READY_APPROVED and open condition is satisfied:
  targetBand = RUNNING_VISIBLE
if acquired READY_APPROVED and open condition is false before due:
  keep score or same-band lease rewrite
if acquired READY_APPROVED and open condition is still false at or after due:
  targetBand = TERMINAL
if acquired EMPTY_RUNNING and work exists:
  targetBand = RUNNING_VISIBLE
if acquired EMPTY_RUNNING and still empty before due:
  keep score or same-band lease rewrite
if acquired EMPTY_RUNNING and still empty at or after due:
  targetBand = TERMINAL
```

Temporary restriction is deliberately not listed as a band. Pause/block writes
the same active tag with a future `epochSecond`; far-future pause is one valid
policy choice. When that epoch becomes due, the band scan acquires the score and
interprets it as the original band. No external resume event is required for the
default restore path. If a product needs to preserve a separate hard deadline
while held, that deadline belongs to policy/meta truth; the score stores only
the next scheduling lease coordinate.

## Band Transition Matrix

The matrix is expressed in score-band terms and split by transition source
class. Product commands and business events may update their own owner facts,
but kernel transition code sees only:

```text
currentBand
transitionSourceClass
targetBand
decoded epochSecond
owner validation
bounded scheduling round result
```

Use `owner-transition` for external handlers after owner validation. Do not name
business events inside the kernel matrix.

| From band | Scheduling-round allowed targets | Owner-transition allowed targets |
| --- | --- | --- |
| `PRE_REVIEW` | none | `READY_APPROVED`, `TERMINAL` |
| `READY_APPROVED` | `READY_APPROVED`, `RUNNING_VISIBLE`, `TERMINAL` | `READY_APPROVED`, `TERMINAL` |
| `RUNNING_VISIBLE` | `RUNNING_VISIBLE`, `EMPTY_RUNNING` | `RUNNING_VISIBLE`, `TERMINAL` |
| `EMPTY_RUNNING` | `EMPTY_RUNNING`, `RUNNING_VISIBLE`, `TERMINAL` | `EMPTY_RUNNING`, `RUNNING_VISIBLE`, `TERMINAL` |
| `TERMINAL` | none | none |

The table is an allow-list. Any `currentBand + transitionSourceClass +
targetBand` combination not listed above is invalid by default. Validation still
belongs to the source class:

```text
scheduling-round
  -> validate decoded epochSecond and facts required by the current band
  -> READY_APPROVED may stay READY_APPROVED, promote to RUNNING_VISIBLE, or
     close to TERMINAL
  -> RUNNING_VISIBLE may rewrite only to RUNNING_VISIBLE or EMPTY_RUNNING
  -> EMPTY_RUNNING may stay EMPTY_RUNNING, promote to RUNNING_VISIBLE, or
     close to TERMINAL

owner-transition
  -> validate the owner gate/control fact required by the target band
  -> temporary restriction writes the same active band with a future or
     far-future epochSecond
  -> release/resume may write a nearer same-band score only when the stored
     score still equals the expected lease score
```

The matrix is checked against the stored current band and transition source
class at score-write time, not only against the band observed when
assignment-dispatch acquired the candidate. The acquired band is evidence for a
round; it is not authority to overwrite newer task score.

Because temporary restriction is represented as the same active band with a
future or far-future `epochSecond`, the band matrix alone does not distinguish
held mode from ordinary future scheduling. The required protection is
single-task write ordering or an expected-score check. If assignment-dispatch
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
  acquired for worker admission, work hash claim, and deliver seed creation

EMPTY_RUNNING
  acquired for post-running continue-condition evaluation; may dispatch if work
  exists

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
RUNNING_VISIBLE may enter dispatch after validation
EMPTY_RUNNING runs idle close due check and may enter dispatch if work exists
READY_APPROVED runs activation check
work-item owner proves whether there is claimable work now
worker-runtime proves whether a concrete worker can be admitted now
```

An acquired `RUNNING_VISIBLE` score with no claimable work transitions to
`EMPTY_RUNNING` with an idle close score. An acquired `EMPTY_RUNNING` score may
be either a pre-deadline horizon check or a due idle-close check: if work exists,
assignment-dispatch may continue into the running dispatch path; if it is still
empty before the idle-close key, the score is left unchanged; if it is still
empty at or after the idle-close key, the task closes. Append, result, and read
paths do not directly refresh the live score.

This makes idle close a deliberate latency tradeoff. If an item is appended
while the task is `EMPTY_RUNNING`, scheduling may discover it through an
empty-scan horizon before the idle-close key, or later when the idle-close score
becomes due. If the due round closes the task before a later append arrives,
that ordering is accepted by score-band; intake/lifecycle policy must decide
whether the append is rejected, routed to a new task, or handled by an explicit
owner transition.

## Assignment-Dispatch Protocol

The target protocol deliberately keeps score-band, work-item ownership,
worker-runtime, and transport separate. The active loop belongs to
assignment-dispatch; task score-band only supplies query and expected-score
update primitives:

```text
1. choose task score scan range and limit according to active band order
2. acquire task ids from task score-band query(range, limit)
3. load task score state and validate gate / epoch
4. if RUNNING_VISIBLE:
     ask work-item owner for bounded claimable-work evidence
     acquire and admit worker through worker score-band / worker-runtime
     let work-item owner perform the final item claim
     produce deliver seed for transport
     rewrite to RUNNING_VISIBLE or EMPTY_RUNNING
5. if EMPTY_RUNNING:
     recheck work facts
     if work appears, continue into the RUNNING_VISIBLE dispatch path
     if still empty before idleCloseEpochSecond, keep score unchanged or
     lease-rewrite within EMPTY_RUNNING
     if still empty at or after idleCloseEpochSecond, close to TERMINAL
6. if READY_APPROVED:
     evaluate pre-running open conditions only
     if satisfied, write RUNNING_VISIBLE
     if not satisfied before readyDeadlineEpochSecond, keep score unchanged or
     lease-rewrite within READY_APPROVED
     if not satisfied at or after readyDeadlineEpochSecond, close to TERMINAL
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
  -> score owner writes the next score through expected-score protection
```

This rewrite is not required to make the task schedulable; the task was already
in the acquire range. It prevents dispatch-visible candidates from spinning at a
dominant score after a real round classification. It does not force
pre-deadline `READY_APPROVED` / `EMPTY_RUNNING` no-op candidates to rewrite
their score. Those no-op checks are idle fallback work controlled by
assignment-dispatch policy.

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

If the stored score has already changed, the expected-score update fails and the
newer score wins. That also counts as consuming the acquired candidate.

### Owner-Transition Writes

Tasks outside the acquire range should not be periodically refreshed because
unrelated evidence changed. Non-scheduling score writes are limited to explicit
owner-transitions that move the current band through the transition matrix:

```text
PRE_REVIEW -> READY_APPROVED | TERMINAL
active band -> same-band future lease score | same-band expected-lease release score | TERMINAL
any non-terminal band -> TERMINAL
```

Activation fact changes are not direct score writes. They update activation
owner truth; an acquired `READY_APPROVED` scheduling round, either pre-deadline
horizon or due, later reads those facts and decides whether the band remains
`READY_APPROVED` or becomes `RUNNING_VISIBLE`.

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

They may be observed later when an owner-transition or
acquired scheduling round computes a new score.

## Score Write Source Classes

| Source class | May write task score? | Allowed shape |
| --- | --- | --- |
| score-acquired assignment-dispatch round | yes | Decode band, validate owner facts, run the band action, then write a target band allowed by the matrix through expected-score protection. |
| owner-transition | yes | Move only through the owner-transition targets allowed for the stored current band. |
| owner-evidence-write | no direct live score | Update its own truth only; later scheduling or owner-transition may observe it. |
| read projection / trace | no | Observability only. |

## Transition Execution Rules

Task score transitions are fact-driven, not external-event-driven:

```text
1. decode the stored current score into currentBand at write time
2. validate owner facts required by that band
3. choose targetBand from the Band Transition Matrix currentBand row
4. encode target score
5. write score with the owner facts that the score protects
```

## Atomicity Boundaries

Task score updates must be atomic only with the task scheduling visibility facts
they protect. They must not absorb work-item claim state or result-finality
state.

Important boundaries:

```text
PRE_REVIEW gate command
  owner gate fact + READY_APPROVED or TERMINAL score

READY_APPROVED activation scheduling round
  read activation facts + write RUNNING_VISIBLE when satisfied
  otherwise close to TERMINAL

active-band temporary restriction
  same active band + future or far-future epochSecond

terminal close
  terminal/closed fence + negative terminal score before physical cleanup

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
  expected-score update primitive

assignment-dispatch
  owns scan range choice, batch limit, candidate classification, and request for
  the next score write
```

Do not add a distributed lock around task scheduling by default. The owner-local
transition should carry the concurrency control for its own facts.
Multiple tasks may be processed concurrently. For one task, score writes must be
serialized by a per-task writer or guarded by an expected-score check. The
required invariant is that a stale scheduling round cannot overwrite a newer
temporary restriction score or terminal score.

## Failure And Stale Handling

Score is an index, so stale candidates are normal.

Rules:

- acquired score but task shell missing: reject candidate, clean opportunistically;
- acquired score but task terminal fence exists: reject candidate, write
  negative terminal score or clean according to owner policy;
- `READY_APPROVED` acquired and open condition is satisfied: transition to
  `RUNNING_VISIBLE`;
- `READY_APPROVED` acquired before `readyDeadlineEpochSecond` and open condition
  is still false: keep the same score or lease-rewrite within `READY_APPROVED`;
- `READY_APPROVED` acquired at or after `readyDeadlineEpochSecond` and open
  condition is still false: close to `TERMINAL`;
- `RUNNING_VISIBLE` acquired but the work-item owner reports no claimable work:
  transition to `EMPTY_RUNNING`;
- `EMPTY_RUNNING` acquired before `idleCloseEpochSecond` and still empty: keep
  the same score or lease-rewrite within `EMPTY_RUNNING`;
- `EMPTY_RUNNING` acquired at or after `idleCloseEpochSecond` and still empty:
  close to `TERMINAL`; close reason is metadata/result/trace, not score;
- `EMPTY_RUNNING` acquired and work exists: continue through the
  `RUNNING_VISIBLE` dispatch path;
- `RUNNING_VISIBLE` or `EMPTY_RUNNING` acquired but no worker matches: stay in
  the active running state and future-score according to policy;
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
idle close delay
activation condition
contention delay
large-backlog requeue delay
healthy/scarce match delay
scheduled retry score
scan range / horizon choice
batch limit
temporary restriction epoch policy for active bands
created/unapproved code values
terminal closed marker / closed score key
```

Mechanism owns:

```text
linear score axis
state-aware bounded range + limit query
owner validation after acquire
expected-score score update
task scheduling visibility transition boundaries
no event-driven scheduling dependency
no broad refresh from low-value observations
```

## Guardrails

- Do not use score absence as terminal proof.
- Do not let append write or refresh task score.
- Do not let result/retry write live task score.
- Do not put a timer, periodic recheck loop, or pagination cursor inside the
  task-score owner.
- Do not treat pre-deadline no-op as a task-score pagination problem.
  Assignment-dispatch owns idle horizon, quota, ordering, and backoff while
  keeping `RUNNING_VISIBLE` consumption first.
- Do not let dispatch-visible `RUNNING_VISIBLE` candidates spin at the same
  dominant score after classification; rewrite, move, close, clean, or lose to
  expected-score protection. `READY_APPROVED` and `EMPTY_RUNNING` may keep the
  same score only when their deadline has not expired and their condition is
  still false.
- Do not use event delivery as the only trigger for scheduling, retry, or
  empty-running closure.
- Do not use an event queue as ready-task backlog or as a second scheduling
  index.
- Do not collapse active scheduling into one broad range scan across tags.
  Assignment-dispatch must scan active bands through separate band ranges and
  must not include negative terminal markers in active acquire.
- Do not let `READY_APPROVED` enter worker admission, work claim, or deliver
  seed creation; assignment-dispatch may scan it only for activation checks.
- Do not write a temporary restriction score for `PRE_REVIEW` or `TERMINAL`.
  Active bands may be held only by rewriting the same active band with a future
  epoch.
- Do not rely on band matrix alone to protect temporary restriction mode;
  because it preserves the same active tag, use per-task write ordering or
  expected-score protection.
- Do not close `EMPTY_RUNNING` without applying the owner policy to the
  decoded idle-close epoch and current work evidence.
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
