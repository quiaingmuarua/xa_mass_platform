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
