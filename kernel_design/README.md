# Kernel Core Design Workspace

Status: new-kernel design workspace, not current implementation truth and not
an implementation roadmap.

This directory is primarily for the next kernel design. The current Java
project is a historical reference for failure modes, invariants, and
anti-patterns; it is not the architecture to preserve.

Treat this workspace as kernel-core design notes for a clean rewrite,
including the planned Python kernel core, not as a roadmap for incrementally
repairing the current codebase.

It is a place to keep target mechanisms, runtime memory models,
state-transition models, and owner-boundary design that are larger than one
module owner doc and broader than a single roadmap.

The goal is fast, consistent agent alignment before executable specs or code
changes. A kernel design note should make clear:

```text
what the target mechanism is
which owner owns which truth
which current implementation facts are only failure-mode references
which runtime structures and transitions are expected
which proof boundaries future executable specs must satisfy
```

## Core Axioms

The kernel core should stay small:

```text
kernel_core moves between logical states
owners maintain truth
policies decide mapping
transports carry evidence
```

Scheduling mechanisms should be mostly score-band state transitions, bounded
claim/result mutations, and invariant checks. Do not introduce production-style
framework layers, bridge modules, CRUD owners, attempt aggregates, or lifecycle
facades just to make the design look complete.

The kernel is score-band scheduled, not event triggered:

```text
score-bands are the scheduling clock
score-bands decide bounded acquire / recheck opportunities
owners validate truth before moving state
policies map evidence to the next score
external events only provide business evidence or optional acceleration
```

The kernel must close its own loop without ordinary external events:

```text
task score-band recheck
worker score-band acquire / admission
assignment-dispatch bounded claim
result-routing current hash compare
retry / empty-running / timeout / repair
terminal / policy closure
```

## Event Cost And Correctness Contract

External event emission is high-cost and opt-in. The default kernel design is
no event emission for ordinary observations or high-frequency mutations.

```text
event emission is high-cost
event emission must be explicitly justified
event emission must fast-fail
event emission is allowed only for key owner state changes
external events must not be required for correctness
external events must not be required for automatic liveness
external events must not be the only state-transition trigger
external events may only accelerate scheduling that already has a fallback
```

The kernel must still converge when ordinary external observations are not
emitted, or when an explicitly allowed event is lost, duplicated, delayed, or
delivered out of order.

Allowed event emission must satisfy all of these constraints:

```text
owner-local state has already changed
the change is a key state transition, not routine evidence
the event carries evidence only, not truth
the event only shortens scheduling / recheck latency
the scheduler has a non-event fallback path
the emit path is bounded and fast-fail
emit failure cannot roll back or block the owner state transition
there is a cheaper score/recheck/repair path for correctness and liveness
```

Every event-accelerated path must still be reachable through owner-local
scheduling:

```text
score scan
score-state recheck
current-truth validation
bounded repair
policy timeout / closure
```

Forbidden event shapes:

```text
per-append wakeup fanout
per-heartbeat scheduling wakeup
per-result global reschedule
unbounded event queue as scheduling backlog
retrying emit until success inside the owner mutation
event-only scheduling with no score/recheck fallback
```

These shapes create avalanche risk: a burst of routine observations can create
more scheduling pressure than the scheduler itself can bound. Event delivery
may be dropped, coalesced, sampled, or rate-limited without changing kernel
correctness.

Routine observations and high-frequency writes must not emit by default:

```text
append
heartbeat / keepalive
transport ack
read-model update
trace materialization
generic dirty marker
```

The hard rule is:

```text
Events may report that truth moved, but only owners may move truth.
```

Explicit human gates are the exception because they intentionally suspend
automatic liveness:

```text
manual review
approval / rejection
manual resume / cancel
operator-required unresolved handling
```

These are not ordinary evidence events. They are authoritative commands whose
owner transaction still validates the current state before changing truth.

## Design Standard

Design notes describe the production-grade target design, not the current
implementation shape.

The current codebase may be useful as:

```text
failure-mode evidence
invariant inventory
proof-gap inventory
migration-cost input
anti-pattern examples
```

It must not lower the kernel design standard. If the current implementation is
confusing, inefficient, or owner-mixed, the design note should say so indirectly
by defining the cleaner target owner split and runtime mechanism. Do not
normalize current design debt into the target architecture just because it is
what exists today.

## Current Design Notes

- [Kernel Core Scheduling](scheduling/README.md)
  - four scheduling planes for the new kernel core: task score-band, worker
    score-band, assignment-dispatch, and result-routing.

## Boundary

These design notes are not proof that the current implementation already
behaves this way. Use them as target mechanism constraints. Use current code
only as legacy evidence when checking why the old design failed or which
invariants the new kernel must preserve.

This workspace is isolated from the current Java project. Current
implementation docs, active roadmaps, architecture explanations, proof
registries, and runbooks should not deep-link these internal design notes as
execution input, implementation proof, or migration direction. If a current
Java roadmap needs a mechanism or Redis shape, define it inside that roadmap or
the owning module contract instead.

Python executable specs belong outside this directory, for example under a
future `kernel_core/` package. Current Java project roadmaps should not execute
these design notes directly. If a future executable-spec plan is needed, write
that plan for the new kernel core explicitly instead of treating this workspace
as an extension of the old Java roadmap system.

## Agent Rules

- Read the relevant design note before drafting a new-kernel executable-spec
  plan that changes the same owner plane.
- Do not treat a design note as implementation evidence.
- Do not constrain a design note to current engine/module shape unless the
  constraint is a deliberate production requirement.
- Do not implement directly from a design note without a scoped executable-spec
  plan when the change crosses owners, runtime truth, storage shape, or public
  boundary.
- If code reality diverges from the design note, report the gap; do not silently
  bend the design note into current behavior.
- If a future executable spec proves part of a design note and makes it current
  new-kernel truth, migrate that fact to the owning kernel-core documentation
  and leave the design note as target context only.
