# XA Mass Platform Blueprints

Status: agent-first target architecture and mechanism workspace, not current
implementation truth and not an implementation roadmap.

This directory is primarily for future agents working on large architecture
changes. It is a place to keep target mechanisms, runtime memory models,
state-transition models, and owner-boundary design that are larger than one
module owner doc and broader than a single roadmap.

The goal is fast, consistent agent alignment before roadmap writing or code
changes. A blueprint should make clear:

```text
what the target mechanism is
which owner owns which truth
which current implementation facts are only failure-mode references
which runtime structures and transitions are expected
which proof boundaries future roadmaps must satisfy
```

## Design Standard

Blueprints describe the production-grade target design, not the current
implementation shape.

The current codebase may be useful as:

```text
failure-mode evidence
invariant inventory
proof-gap inventory
migration-cost input
anti-pattern examples
```

It must not lower the blueprint standard. If the current implementation is
confusing, inefficient, or owner-mixed, the blueprint should say so indirectly
by defining the cleaner target owner split and runtime mechanism. Do not
normalize current design debt into the target architecture just because it is
what exists today.

## Current Blueprints

- [Score-Band Scheduling](./score-band/README.md)
  - next-generation task/worker scheduling visibility, worker-runtime
    eligibility, Redis shape references, and score/state transition model.

## Boundary

Blueprints are not proof that the current implementation already behaves this
way. Use them as target mechanism constraints. Use code, owner docs, verified
runtime behavior, and proof registries for current implementation truth.

Roadmaps that execute a blueprint stay under [`../roadmap/`](../roadmap/).
When a blueprint becomes current implementation truth, move the relevant facts
into the owning module README, baseline, or global contract doc.

## Agent Rules

- Read the relevant blueprint before drafting a roadmap that changes the same
  owner plane.
- Do not treat a blueprint as implementation evidence.
- Do not constrain a blueprint to current engine/module shape unless the
  constraint is a deliberate production requirement.
- Do not implement directly from a blueprint without a scoped roadmap or plan
  when the change crosses owners, runtime truth, storage shape, or public
  boundary.
- If code reality diverges from the blueprint, report the gap; do not silently
  bend the blueprint into current behavior.
- If a roadmap proves part of a blueprint and makes it current truth, migrate
  that fact to the owning README or baseline and leave the blueprint as target
  context only.
