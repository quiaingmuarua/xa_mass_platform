# Kernel Core Scheduling Architecture

Status: new-kernel mechanism workspace, not current implementation truth and
not an implementation roadmap.

This directory is the scheduling design entry for the clean kernel core. It is
intentionally separated from the current Java engine implementation: the
existing project can provide failure modes and invariant reminders, but it is
not the model to copy.

The design standard here is a small, explicit scheduling kernel that can later
be implemented as a Python kernel core. Current engine concepts may be
mentioned only to identify failure modes. They must not define the target
runtime memory model, owner split, or state-transition semantics.

Use this directory to align on:

```text
four scheduling planes
kernel_core logical state transitions
owner truth for each plane
policy mapping boundaries
transport evidence boundaries
which plane may mutate which state
which facts are only evidence
which handoff objects cross scheduling planes
which facts remain outside the kernel
```

## Purpose

The new kernel core is organized around four scheduling planes:

```text
task-score-band-scheduling
  decides which task ids may enter a scheduling round now

worker-score-band-scheduling
  decides which worker/resource ids may enter worker admission now

assignment-dispatch-scheduling
  joins a schedulable task, admitted worker/resource, claimed item score, and
  transport route evidence into a concrete deliver seed

result-routing-scheduling
  compares incoming result evidence with current item score truth and routes it
  to finality, retry, discard, or no-op handling
```

These are scheduling planes, not modules. A first Python kernel can implement
them in one package as long as the owner rules remain explicit.

The first executable kernel may use simple policies, small operator sets, and
in-process handoffs. That is a scope choice, not a license to use weaker
mechanisms. V0 should be narrow capability over correct mechanism:

```text
simple policy mapping is allowed
reduced strategy coverage is allowed
owner-mixed truth is not allowed
event-only liveness is not allowed
query predicates as lifecycle truth are not allowed
transport facts as worker selection truth are not allowed
```

## Mainline

```text
task score acquire
  -> task validation
  -> worker score acquire / admission
  -> item score acquire / claim
  -> deliver seed
  -> transport delivery
  -> result evidence
  -> current item score compare
  -> finality / retry / no-op / next scheduling decision
```

Each arrow is a handoff between owners. A later implementation may optimize
the handoff, but it must not merge owner truth.

## Score-Band Closure

The scheduling kernel is driven by score-band transitions:

```text
task score-band
  -> worker score-band
  -> assignment-dispatch
  -> result-routing
  -> task / worker score rewrite
```

These bands and bounded owner handoffs are the kernel's liveness base. Business
events may add evidence, but they do not become a scheduling plane.

Without ordinary external events, the kernel must still close the loop:

```text
task score due
  -> owner validation
  -> worker admission
  -> item score claim / no-work classification
  -> result compare
  -> retry / finality / next score
```

## Event Cost And Liveness Discipline

Scheduling planes must not depend on ordinary external events for correctness
or automatic liveness. They also must not emit events by default for ordinary
observations or high-frequency mutations:

```text
append
worker heartbeat
transport ack / reject callback
routine result notification
trace materialization
read-model update
generic dirty marker
```

Event emission is a high-cost mechanism. It is allowed only for key owner state
changes, and only after the owner transition has already committed:

```text
terminal close
manual decision accepted
verified worker unavailable / reopened
runtime recovery entered / exited
operator-visible unresolved state created
```

Even then, emission is evidence only. It must be bounded and fast-fail; failure
to emit cannot roll back or block the owner transition. If an allowed event is
dropped, repeated, delayed, or reordered, owner state machines must still
converge through score recheck, current-truth validation, bounded recheck, or
explicit policy closure.

Allowed events can only accelerate scheduling. They must not be the scheduling
mechanism:

```text
event arrives
  -> optionally shorten or request an owner-local recheck
  -> owner validates current truth
  -> score/state decides the next scheduling action

event missing
  -> bounded scheduler scan / recheck still reaches the same state
```

Do not build a path where scheduling only happens because an event was emitted.
High-frequency event sources must be drop-tolerant, coalesced, sampled, or
rate-limited. A burst of append/result/heartbeat/transport observations must
not create a larger scheduling backlog than the bounded scheduler can absorb.

Human-required gates are different:

```text
PRE_REVIEW approval / rejection
manual resume / cancel
manual unresolved-result decision
operator-required intervention
```

Those states intentionally do not promise automatic liveness. The manual
command is authoritative input, but the owning plane still validates current
truth before moving state.

## Score Writer Authority

Score coordinates do not serialize ordinary Task or Worker resource writes.

```text
resource mutation
  writes owner truth directly
  does not acquire score
  does not refresh score
  does not wait for dispatcher ownership

item append
  writes item record and item score truth directly
  does not change task lifecycle or scheduling visibility
  guarantees item acceptance only, not eventual consumption

score-acquired scheduling round
  is the only routine writer for an acquirable score
  may classify the round and write next time / budget / hold / lane

explicit lifecycle command
  may invoke only a declared transition such as approve, reject, pause,
  resume, close, verified unavailable, or verified reopen
```

For task active bands and worker HOT acquisition, assignment-dispatch owns the
routine scheduling intent. A worker recovery round owns only the
RECOVERY_RECHECK classification it acquired. Resource owners cannot imitate
either path by refreshing score after metadata, append, heartbeat, result, or
projection writes.

An explicit command may supersede an acquired round through the score stale
fence. That is a high-priority lifecycle transition, not a second pacing loop:
the command handler cannot consume routine scheduling budget, calculate retry
cadence, or perform a generic same-band refresh.

Worker dirty is the narrow exception to "resource writes do not touch score",
but it is not a prerequisite or lock. The resource update commits first. If a
real persisted assignment continuation used the changed field, the owner may
separately mark that continuation stale; failure to mark dirty cannot reject or
roll back the resource update.

Append eligibility is outside the scheduling planes. The ingress/product owner
may reject a terminal or intake-disabled Task through a cache, close-append tag,
or tombstone. If stale ingress evidence allows a late append, scheduling keeps
the terminal score unchanged and retention later removes the invalid backlog
residue. The kernel does not owe eventual consumption for every accepted item
and must not reopen a Task to provide that business guarantee.

## Plane Boundaries

### Task Score-Band Scheduling

Answers:

```text
which tasks may enter scheduling now?
```

Owns:

```text
task scheduling visibility score
task score-state interpretation
bounded score query primitive
same-band epoch/suffix rewrite / positive rewrite / terminal close / score-hold release
primitives
running-stage future-score placement for pause / contention / no-worker delay
```

Does not own:

```text
item append
item score claim / current occupancy
worker selection
transport delivery
result finality
timer / pacing loop
candidate classification after acquire
```

Task score-band uses four score states. Temporary hold is not a separate band;
pause/block is represented as the same active band with a future internal
`timeSlot`; hard pause uses the score-band pause slot. Public APIs speak
`timeMillis`; score encoding converts it to `timeSlot =
floor(timeMillis / SLOT_MILLIS)`. Positive score means mutable, not
automatically schedulable; assignment-dispatch only scans explicit active tags.

The model has two independent directions:

```text
lifecycle progresses left:
  PRE_REVIEW(3) -> PRE_DISPATCH_VISIBLE(2) -> RUNNING_VISIBLE(1) -> TERMINAL(<0)

hold / recheck moves right:
  same tag + later timeSlot
```

`tag` owns lifecycle direction, `timeSlot` owns same-band freshness /
recheck, and `suffix` is interpreted by the owning band policy. The score-write
stale fence prevents stale overwrite, and the transition direction rule blocks
lifecycle regression.
Downward lifecycle jumps are allowed; `PRE_DISPATCH_VISIBLE` is an optional
intermediate band, not a required checkpoint.

```text
PRE_REVIEW
  positive mutable create / prepare / pending approval state; not acquired;
  timeSlot is an owner mutation freshness coordinate; suffix is the
  owner-defined review state code

PRE_DISPATCH_VISIBLE
  approved but not yet running; scans evaluate pre-open worker-candidate /
  policy facts; success enters RUNNING_VISIBLE with configured initial suffix;
  false leaves PRE_DISPATCH_VISIBLE unchanged for a later bounded retry

RUNNING_VISIBLE
  active running state; may enter assignment-dispatch after validation; no-work,
  no-worker, contention, retry, and hold rechecks remain in this band

TERMINAL
  negative immutable final / cancelled / rejected / no-work budget exhausted /
  discarded
```

### Worker Score-Band Scheduling

Answers:

```text
which workers or worker resources may enter admission now?
```

Owns:

```text
worker/resource eligibility score
worker acquire range
worker validation and admission evidence
capacity / ownership / gate validation before final admission
```

Does not own:

```text
task backlog
task score
transport session truth
final work result
```

### Assignment-Dispatch Scheduling

Answers:

```text
which bounded Task-Worker candidates should be published now?
which recent candidate can become a current item-score claim and DeliverSeed now?
```

Owns:

```text
two mandatory independent pacers
oldest-first Task-Worker allocation and Task fairness rewrite intent
newest-first candidate-worker consumption
worker group / constraint / priority rule application during allocation
Worker allocation-lease duration and dispatch-side release / renewal disposition
final item claim timing
deliver seed creation from current item score evidence
```

Does not own:

```text
task score truth; TaskItemDispatchPacer is Task-score read-only
worker score lifecycle
result finality
transport session internals
transport adapter queue choice
```

### Result-Routing Scheduling

Answers:

```text
what should this incoming result do next?
```

Owns:

```text
classify business result as retryable, final-failed, or final-success
invoke one TaskItemScoreBandCore transition without interpreting score
map the returned transition status to accepted, stale/duplicate no-op, discard,
or manual review / unresolved handling
```

Does not own:

```text
worker selection
transport delivery
read-model materialization
task score refresh as a generic side effect
```

Result routing is a scheduling plane because it decides whether a result exits
the runtime, requests retry scheduling, or is ignored. TaskItemScoreBandCore alone
decides whether the requested score transition is legal. Result routing is not
a query/view owner and not a transport parser.

## Reading Order

1. [Task Score-Band Scheduling](task-score-band-scheduling.md)
   - target task active-acquisition score mechanism.
2. [Task Item Score-Band Scheduling](task-item-score-band-scheduling.md)
   - target per-Task Item record plus monotonic item score axis.
3. [Worker Score-Band Scheduling](worker-score-band-scheduling.md)
   - target worker/resource eligibility score mechanism.
4. [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md)
   - shared owner and protocol contract for two mandatory independent pacers.
5. [Task-Worker Allocation Pacer](task-worker-allocation-pacer.md)
   - oldest-first Task allocation, batch Worker matching, activation checks,
     Task timeSlot fairness, and candidate-worker publication.
6. [Task Item Dispatch Pacer](task-item-dispatch-pacer.md)
   - newest-first candidate consumption, Worker short lease, item-score claim, and
     DeliverSeed creation; Task score is read-only.
7. [Result-Routing Scheduling](result-routing-scheduling.md)
   - how result evidence is routed to finality, retry, no-op, or unresolved
     handling.
8. [Worker Runtime Redis Shape](../runtime-redis/worker-runtime-redis-shape.md)
   - first-slice Redis structure reference for worker-runtime resource catalog,
     score acquisition, and dynamic attribute storage.

This directory describes mechanisms for new-kernel alignment. A future
executable spec must define its own scope, acceptance, and residue handling
instead of treating current Java roadmaps as the execution system.

## Owner Split

The target kernel uses explicit owner planes:

```text
Task score-band
  owns task active-acquisition visibility

TaskRuntime
  owns canonical TaskItem records, append orchestration, and bounded record reads

TaskItemScoreBandCore
  owns per-Task item scheduling score, ACTIVE initialization, same-tag
  claim/retry movement, and cross-tag final movement

Result owner
  owns retryable/final classification, late-success barriers, and result projection

Worker score-band / worker-runtime
  owns worker eligibility acquire, validation, admission, capacity, and score
  placement

Transport / adapter
  owns final-hop delivery, mailbox/session/freshness evidence, and opaque
  selected-worker delivery facts
```

No owner may use score absence as lifecycle proof. Score-band is an acquire
index, not a full state machine, storage model, or read model.

First kernel cut rule:

```text
no Attempt aggregate
no claim_token as a required model concept
deliver seed carries claim evidence
TaskItem record truth belongs to TaskRuntime
Item scheduling truth belongs to TaskItemScoreBandCore
result routing passes opaque evidence to a named TaskItemScoreBandCore operation
TaskItemScoreBandCore validates same-tag retry against exact claimScore
TaskItemScoreBandCore enforces ACTIVE < FINAL_FAILED < FINAL_SUCCESS
expired claim scores naturally re-enter acquire
default ingress calls append_items directly
caller-owned outbox / broker is optional and never kernel truth
```

## Python Executable Spec

The current Python executable spec is framework-light and organized by owner:

```text
kernel_design/py_example/
  constraint_dsl/
    evaluator.py
  kernel/
    task_score_band.py
    task_item_score_band.py
    worker_score.py
    task_runtime.py
    worker_runtime.py
    worker_candidate_matcher.py
    task_dispatch_runtime.py
    task_worker_allocation.py
  runtime_redis/
    task_score_band_zset.py
    task_item_score_band_zset.py
    worker_score_zset.py
    task_runtime.py
    worker_runtime.py
    task_dispatch_runtime.py
  tests/
```

It currently proves the Task, TaskItem, and Worker score axes, TaskItem DTO
contracts, resource catalogs, bounded Worker matching, Redis candidate handoff,
and the first Task-Worker allocation pacer. `TaskItemDispatchPacer`, TaskItem
record persistence, and result routing are still executable-spec gaps. An
in-memory runtime is not a prerequisite or a parallel mainline.

## Extension Scope

Future documents in this directory should cover mechanism-level questions such
as:

```text
runtime memory model
Redis key shape
state-transition rules
score rewrite discipline
task / worker acquire protocol
failure and stale-candidate handling
proof boundaries for score-band behavior
```

Do not put phase plans, implementation TODO lists, or migration instructions
here. Future execution work should be captured in an explicit new-kernel
executable-spec plan without linking current Java roadmaps back to these
internal design notes.

## Guardrails

- Do not backfill this directory from current engine shapes unless the text is
  explicitly labelled as a failure-mode or invariant reference.
- Do not let legacy Java classes, module names, or bridge layers define the new
  kernel core.
- Do not make task score-band own item score claim, retry movement, or
  result finality.
- Do not make worker score-band own transport sessions or raw heartbeat truth.
- Do not let transport identifiers become scheduling candidate truth.
- Do not make assignment-dispatch own result finality.
- Do not make result-routing own worker selection.
- Do not add a second hot-path candidate index without naming its owner,
  lifecycle, update discipline, and deletion path.
- Do not turn a Redis shape note into public API or current implementation
  truth.
