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

The scheduler hot path does not interpret a full task lifecycle object. It only
asks:

```text
which task ids have score in the acquire range now?
```

Task score answers:

```text
should this task enter a bounded scheduling round now?
```

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
- acquire is a bounded range query;
- acquired tasks still require lifecycle/gate validation and work-item owner
  validation before dispatch;
- append, result, retry frame write, and result lookup are not score refresh
  triggers.

Conceptual flow:

```text
task score acquire
  -> task lifecycle / gate / epoch validation
  -> work-item owner reports or claims claimable work
  -> worker score-band acquire / admission
  -> dispatch selected work through transport
  -> scheduling round classifies evidence
  -> optional task score rewrite for fairness / anti-spin / future retry
```

## Band Definitions

Task score uses these conceptual ranges:

```text
score absent
  undefined to score-band
  not service discovery
  not terminal proof
  scheduler ignores it

score < 0
  retained closed marker
  terminal / closed / discarded
  not reopenable through normal scheduling
  not acquired

0 <= score < TIME_SCORE_FLOOR
  live but not schedulable by the hot path
  enum-like code such as created / unapproved / policy hold
  concrete meaning belongs to task lifecycle / scheduling visibility policy
  not acquired

TIME_SCORE_FLOOR <= score <= now
  due scheduling visibility
  task may be acquired for one bounded scheduling round

score > now
  future scheduling visibility
  retry backoff / empty-match recheck / contention delay / scheduled retry
  pause or manual hold may be represented as a far-future timestamp such as
  SCHEDULER_HOLD_FLOOR
```

The hot-path acquire range is:

```text
TIME_SCORE_FLOOR <= score <= now
```

Everything outside that range is invisible to normal acquire. Score-band does
not need to know why.

## Acquire Semantics

Conceptual acquire query:

```text
ZRANGEBYSCORE task:score:{laneBucketId}
  TIME_SCORE_FLOOR
  now
  LIMIT 0 N
```

Acquire returns candidate task ids only. It does not prove work exists, reserve
a worker, or accept a result.

After acquire, the engine asks the owning planes to validate their own facts:

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
score says the task may enter one bounded scheduling round
work-item owner proves whether there is claimable work now
worker-runtime proves whether a concrete worker can be admitted now
```

An acquired score with no claimable work is a stale or no-work candidate. It is
classified by the scheduling round and rewritten by the score owner; append,
result, and read paths do not directly refresh the live score.

## Engine Scheduling Protocol

The target protocol deliberately keeps score-band, work-item ownership,
worker-runtime, and transport separate:

```text
1. acquire task id from task score-band
2. validate task lifecycle / gate / epoch
3. ask work-item owner for bounded claimable-work evidence
4. acquire and admit worker through worker score-band / worker-runtime
5. let work-item owner perform the final item claim
6. dispatch claimed work to the selected worker through transport
7. classify scheduling evidence and rewrite task score if needed
```

Step 3 may be a cheap evidence read rather than a final claim. The final claim
belongs to the work-item owner and should happen only when the scheduling round
has enough worker-admission evidence to avoid consuming work without a viable
dispatch path.

If final item claim fails after worker admission, the scheduling round releases
or compensates worker admission through worker-runtime and classifies the round
evidence. Task score-band does not own that compensation state.

## Score Update Discipline

Task score updates are intentionally narrow.

### Acquire-Range Rewrite

Objects already in the acquire range may be rewritten by the scheduling /
matching round that observed them:

```text
task score due
  -> scheduler acquires task
  -> lifecycle / work-item / worker-runtime validation produces evidence
  -> score owner may rewrite score
```

This rewrite is not required to make the task schedulable. The task was already
in the acquire range. The rewrite exists for:

```text
priority
fairness
anti-spin
empty-match delay
contention delay
large-backlog requeue
scheduled retry visibility
```

### Direct Owner Event Writes

Tasks outside the acquire range should not be periodically refreshed because
unrelated evidence changed. Their score changes through directly related owner
events:

```text
approve / make schedulable
pause / hold
resume / unblock
terminal close
discard
policy command that explicitly changes scheduling visibility
```

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

They may be observed later when a direct owner event or acquired scheduling
round computes a new score.

## Event Trigger Taxonomy

| Event kind | May write task score? | Notes |
| --- | --- | --- |
| approve / make schedulable | yes | direct lifecycle/scheduling visibility event |
| pause / hold | yes | usually writes far-future score |
| resume / unblock | yes | validates facts and writes due/future/remove |
| terminal / discard | yes | writes retained closed marker |
| append item | no | writes work-item owner truth only |
| result / retry | no for live score | updates result/work-item owner truth; terminal handoff may close through lifecycle owner |
| scheduling round after acquire | yes | primary live score rewrite path |
| read projection / trace | no | observability only |

## Transition Rules

Task score transitions are fact-driven, not event-name-driven:

```text
direct lifecycle event
  -> mutate lifecycle / scheduling visibility fact
  -> compute score
  -> write score

acquired scheduling round
  -> validate task
  -> ask work-item owner for claimable-work evidence or claim
  -> match/reserve worker through worker-runtime
  -> classify round evidence
  -> rewrite score
```

Typical scheduling round outcomes:

```text
WORK_CLAIMED and more work may remain
  -> score = now or now + policy delay

WORK_CLAIMED and no current work but a scheduled retry exists
  -> score = earliest retry due time

WORK_CLAIMED and no current work / no retry
  -> remove score

EMPTY_MATCH
  -> score = now + empty-match penalty

CONTENDED
  -> score = now + contention delay

NO_READY
  -> remove score or earliest scheduled retry due time

PARKED / PAUSED
  -> score = SCHEDULER_HOLD_FLOOR or owner-approved future hold

TERMINAL / DISCARDED
  -> score = retained closed marker
```

## Atomicity Boundaries

Task score updates must be atomic only with the task scheduling visibility facts
they protect. They must not absorb work-item claim state or result-finality
state.

Important boundaries:

```text
pause / resume / unblock
  lifecycle gate + lifecycle epoch + task score

terminal / discard
  terminal/closed fence + retained closed score before physical cleanup

scheduling round rewrite
  acquired score evidence + round classification + next score

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
  owns whether a task id is visible to the next scheduling round
```

Do not add a distributed lock around task scheduling by default. The owner-local
transition should carry the concurrency control for its own facts.

## Failure And Stale Handling

Score is an index, so stale candidates are normal.

Rules:

- score due but task shell missing: reject candidate, clean opportunistically;
- score due but task terminal fence exists: reject candidate, write retained
  closed marker or clean according to owner policy;
- score due but the work-item owner reports no claimable work: classify
  `NO_READY` and remove or future-score through the score owner;
- score due but no worker matches: classify `EMPTY_MATCH` and future-score;
- score due but worker admission contends/fails: classify `CONTENDED` or failed
  validation and future-score according to policy;
- late result with no matching active work cannot rewrite live score directly;
  result-side convergence may only close visibility through the lifecycle owner.

Stale handling must be bounded. Do not scan the namespace to repair task score.

## Policy Seams

Score-band mechanism should stay stable while policy remains replaceable.

Policy owns:

```text
empty-match delay
contention delay
large-backlog requeue delay
healthy/scarce match delay
scheduled retry score
pause/hold score value
created/unapproved code values
terminal closed marker codes
```

Mechanism owns:

```text
linear score axis
bounded acquire range
owner validation after acquire
task scheduling visibility transition boundaries
no broad refresh from low-value events
```

## Guardrails

- Do not use score absence as terminal proof.
- Do not let append write or refresh task score.
- Do not let result/retry write live task score.
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
