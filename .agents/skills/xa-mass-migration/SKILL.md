---
name: xa-mass-migration
description: Plan, review or implement an explicitly requested XA Mass migration across Owner authority, serving paths or lifecycle. Use current contracts, callers, failure boundaries and owning proof; ordinary plans, edits and documentation upkeep do not need this workflow.
---

# XA Mass Migration

Use the [reading path](../../../README.md#reading-path) and
[change contract](../../../AGENTS.md) for scope, authority and verification.
Establish the accepted outcome from the request or existing plan.

## Resolve The Changed Boundary

Trace the affected entry, state writer and consumers. Distinguish authoritative
state from candidate evidence, projections and hints; carrying a fact across a
boundary does not transfer its mutation authority. Identify the concrete cost
or failure being addressed and whether an existing Owner operation suffices.

Separate an expected migration gap from a stale factual description or a conflict
in required behavior. Correct stale facts without restoring the old design;
resolve material conflicts before the dependent change.

For an affected side effect, distinguish definitely not started, unknown/partial,
and completed. Identify the Owner that may retry, fence or reconcile each state.
Preserve the actual retry bound: a lifetime/storage bound need not become an
attempt limit. Observation layers must not acquire authoritative recovery state;
lossy evidence remains valid only within its declared contract.

Account for any added stores, queues, background work, fan-out or public contracts
by the invariant they supply. Similar signatures or symmetric types do not
establish shared authority or failure semantics.

## Cut Over And Prove The Change

Name the old serving path, replacement, affected callers and retirement point.
Order independently usable slices; a later slice must not restore an earlier
slice's correctness. Include obsolete callers and current documentation in the
cutover rather than retaining a second authority through a fallback.

Select the owning claim through [TESTING](../../../TESTING.md). Ask whether its
proof would still pass if the old or wrong Owner served the request. If so,
identify the missing authority, failure, ordering or lifecycle observation.
Protect the invariant rather than temporary class names or decomposition.
