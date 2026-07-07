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
  joins a schedulable task, admitted worker/resource, claimed work hash row,
  and transport route evidence into a concrete deliver seed

result-routing-scheduling
  compares incoming result evidence with current work hash truth and routes it
  to finality, retry, discard, or no-op handling
```

These are scheduling planes, not modules. A first Python kernel can implement
them in one package as long as the owner rules remain explicit.

## Mainline

```text
task score acquire
  -> task validation
  -> worker score acquire / admission
  -> work hash claim
  -> deliver seed
  -> transport delivery
  -> result evidence
  -> current hash compare
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
  -> work claim / no-work classification
  -> result compare or repair
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
converge through score recheck, current-truth validation, bounded repair, or
explicit policy closure.

Allowed events can only accelerate scheduling. They must not be the scheduling
mechanism:

```text
event arrives
  -> optionally shorten or request an owner-local recheck
  -> owner validates current truth
  -> score/state decides the next scheduling action

event missing
  -> bounded scheduler scan / recheck / repair still reaches the same state
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
same-band epoch bump/rewrite / positive rewrite / terminal close / lease-release
primitives
running-stage future-score placement for pause / contention / no-worker delay
```

Does not own:

```text
item append
work hash claim / current occupancy
worker selection
transport delivery
result finality
timer / pacing loop
candidate classification after acquire
```

Task score-band uses four score states. Temporary hold is not a separate band;
pause/block is represented as the same active band with a future `epochSecond`;
hard pause uses the maximum 10-digit coordinate `9_999_999_999`. Positive
score means mutable, not
automatically schedulable; assignment-dispatch only scans explicit active tags.

The model has two independent directions:

```text
lifecycle progresses left:
  PRE_REVIEW(3) -> READY_APPROVED(2) -> RUNNING_VISIBLE(1) -> TERMINAL(<0)

lease / recheck moves right:
  same tag + later epochSecond
```

`tag` owns lifecycle direction, `epochSecond` owns same-band freshness /
recheck, `suffix` owns budget or owner-local code, the score-write stale fence
prevents stale overwrite, and the transition direction rule blocks lifecycle
regression.
Downward lifecycle jumps are allowed; `READY_APPROVED` is an optional
intermediate band, not a required checkpoint.

```text
PRE_REVIEW
  positive mutable create / prepare / pending approval state; not acquired;
  epochSecond is an owner mutation freshness second; suffix is the
  owner-defined review state code

READY_APPROVED
  approved but not yet running; scans evaluate pre-open worker-candidate /
  policy facts; false with suffix > 00 rewrites same band with suffix-1,
  false with suffix == 00 writes READY_APPROVED pause/hold

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
given a schedulable task and admissible worker, what concrete deliver seed
should be produced?
```

Owns:

```text
candidate worker selection for one task scheduling round
worker group / capability / priority rule application
final work claim timing
deliver seed creation from current work hash evidence
transport adapter queue choice for already selected work
```

Does not own:

```text
task score lifecycle
worker score lifecycle
result finality
transport session internals
```

### Result-Routing Scheduling

Answers:

```text
what should this incoming result do next?
```

Owns:

```text
route result to accepted finality, retry, stale/duplicate no-op, discard, or
manual review / unresolved handling
```

Does not own:

```text
worker selection
transport delivery
read-model materialization
task score refresh as a generic side effect
```

Result routing is a scheduling plane because it decides whether a result exits
the runtime, re-enters retry scheduling, or is ignored. It is not a query/view
owner and not a transport parser.

## Reading Order

1. [Task Score-Band Scheduling](task-score-band-scheduling.md)
   - target task active-acquisition score mechanism.
2. [Worker Score-Band Scheduling](worker-score-band-scheduling.md)
   - target worker/resource eligibility score mechanism.
3. [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md)
   - how one task scheduling round chooses workers, claims work, and produces
     deliver seeds.
4. [Result-Routing Scheduling](result-routing-scheduling.md)
   - how result evidence is routed to finality, retry, no-op, or unresolved
     handling.
5. [Score-Band Worker Runtime Redis Shape](../runtime-redis/score-band-worker-runtime-redis-shape.md)
   - first-slice Redis structure reference for worker score-band runtime.
6. [Score-Band Task Runtime Redis Shape](../runtime-redis/score-band-task-runtime-redis-shape.md)
   - Redis structure reference for task score lanes plus adjacent work-item and
     result structures.

This directory describes mechanisms for new-kernel alignment. A future
executable spec must define its own scope, acceptance, and residue handling
instead of treating current Java roadmaps as the execution system.

## Owner Split

The target kernel uses explicit owner planes:

```text
Task score-band
  owns task active-acquisition visibility

Work-item owner
  owns item readiness, current work hash claim, retry frame, and claim
  compensation

Result owner
  owns result finality, recent-final barriers, and result read projection

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
work hash is the current truth
result apply validates against the current work hash
time-bounded work may compare claim_expires_at
non-time-bounded work is single-claim until result/cancel/manual intervention
```

## Python Kernel Direction

The first Python kernel core should optimize for owner clarity over framework
shape:

```text
kernel_core/
  task_score.py
  worker_score.py
  assignment_dispatch.py
  result_routing.py
  models.py
  memory_runtime.py
```

The first version can be in-memory and single-process. It should prove the
owner model before introducing Redis, HTTP, SDKs, background workers, or UI
surfaces.

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
- Do not make task score-band own work hash claim, retry-frame mutation, or
  result finality.
- Do not make worker score-band own transport sessions or raw heartbeat truth.
- Do not let transport identifiers become scheduling candidate truth.
- Do not make assignment-dispatch own result finality.
- Do not make result-routing own worker selection.
- Do not add a second hot-path candidate index without naming its owner,
  lifecycle, update discipline, and deletion path.
- Do not turn a Redis shape note into public API or current implementation
  truth.
