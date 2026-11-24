---
name: roadmap-refinement
description: Plan or review explicitly requested complex cross-Owner migrations and convergence work in XA Mass, and guide their authorized implementation. Use current authority, callers, failure semantics and proof; ordinary plans, small fixes and general code reviews do not need this workflow.
---

# Roadmap Refinement

Use the repository [reading path](../../../README.md#reading-path) to establish
only the affected Owners, implementation, callers and proof. An accepted plan
in the conversation is sufficient; this skill requires no roadmap file, goal
tool, fixed document set or extra approval. Follow the active execution mode
and requested scope: review is not implementation authorization.

## Ground The Change

For each changed boundary, establish the invariant, its mutation authority,
current callers, and the concrete limitation the change resolves. Classify
transferred facts as truth, evidence, address, correlation, projection or hint;
their presence in a DTO does not grant another Owner authority over them.

Compare the proposed seam with an existing Owner operation or smaller change.
Name material additions to state, coordination, public contracts and operating
cost. Owner clarity does not require a type per noun, symmetric paths or a
framework for hypothetical variation.

Separate differences between the plan and current source:

- **Expected migration gap:** the accepted change has not been implemented yet.
- **Stale fact:** correct a renamed caller, obsolete description or other factual
  detail while preserving the accepted outcome.
- **Material conflict:** resolve contradictory required behavior, authority,
  compatibility or destructive effects before the dependent action; continue
  independent authorized work.

For changes to state, concurrency, retry/recovery or external side effects,
read [mechanism-first.md](references/mechanism-first.md). A document mentioning
those mechanisms does not by itself require that deeper review.

## Make The Migration Executable

Identify the old serving path, its replacement, affected callers and the point
at which old dependencies can be removed. Order independently usable slices:
a later slice must not be needed to restore an earlier slice's correctness.
Include cleanup and Owner updates in the accepted outcome; do not preserve an
old authority through an unrequested fallback or compatibility alias.

For each material change, connect its intended behavior to the owning proof.
Ask whether that proof would still pass if the old or wrong Owner handled the
behavior. If so, identify the missing failure, ordering, lifecycle or authority
check. Protect the invariant, not incidental class names or lock keywords.

Keep decisions in the requested plan and link canonical contracts. Add an
inventory only when many changing callers actually need one, not to satisfy a
template. Explicitly deferred non-goals do not block the accepted scope.

## Verify The Claimed Outcome

Select checks through [TESTING](../../../TESTING.md). Documentation changes
use content/link validation; runtime, Redis or performance claims require their
own evidence. A structural refactor or green functional test proves no new
capacity figure.

During authorized implementation, continue through the accepted slices and
required cleanup without treating a status label as a new approval gate. Report
what changed, the checks actually executed and any remaining dependency.
Distinguish source inspection, submitted changes and external application when
the requested outcome depends on an external system.
