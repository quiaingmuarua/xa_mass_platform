# Mechanism-First Review

Read this reference only when roadmap work changes a stateful or concurrent
internal mechanism. Its purpose is to prevent formal roadmap structure from
hiding an incoherent runtime model; it does not prescribe one architecture,
language, concurrency primitive, or retry strategy.

## Required Evidence

Derive six compact artifacts from the live production entry and hot path before
target types or slices:

1. **Representative-flow trace**: follow one real request, item, event, or state
   change through entry, validation/admission, owner transition, side effect,
   outcome/evidence, and termination or recovery.
2. **State-owner ledger**: list every mutable field, store, queue, cache, or
   registry; its mutation authority or gate; readers; lifetime; invariant; and
   cleanup. Derived observation is not another state owner.
3. **Failure/side-effect table**: for every failure boundary, record what was
   accepted, whether an external or irreversible effect definitely did not
   start, may have started, or completed, and which owner may retry, reconcile,
   compensate, fail, or drop.
4. **Execution/blocking map**: name callers, threads/tasks/executors, blocking or
   suspending calls, wake/cancel/interrupt paths, and state gates held across
   external work.
5. **Minimal pseudocode**: express executable control flow and state transitions
   before mapping them to classes, interfaces, DTOs, strategies, or phases.
6. **Complexity delta**: compare before/after mutable owners, stored facts,
   lifecycle states, queues/caches/registries, coordination gates, background
   work, retry/recovery paths, public contracts, and internal abstractions.
   Every increase needs a production invariant and focused proof.

If the mechanism cannot be stated compactly, its owner or failure model is not
understood. Use this neutral skeleton only to expose missing decisions:

```text
entry/input
  -> validate or admit
  -> owner state transition
  -> optional external side effect
  -> outcome/evidence
  -> complete, fail, defer, reconcile, compensate, or terminate
```

## Abstraction Admission

- Owner clarity means one authority for an invariant, not one type per noun.
  Keep facts and transitions that form one invariant under one mutation owner.
- Split only for independent state or lifetime, a real protocol/module/security
  boundary, dependency direction, or multiple current implementations with the
  same contract.
- An abstraction must remove duplicated semantics, protect a boundary, or own
  an invariant. Pass-through wrappers, vocabulary aliases, and one-branch
  policy interfaces do not qualify by themselves.
- Genericity requires shared semantics and failure behavior, not similar method
  signatures. Keep genuine variation at the smallest callback/value boundary.
- For fixed topology, prefer explicit composition over a registry, plugin
  point, dynamic list, or framework intended only for hypothetical growth.
- Do not force symmetric types onto paths with different authorities, side
  effects, ordering, cardinality, blocking, or failure semantics.
- If a coordinator already owns a value or operation context, collaborators
  should return the smallest outcome/evidence needed for its decision rather
  than mirror that context without adding meaning.
- Prefer standard-library primitives and existing owner operations. Wrap them
  only to add a missing invariant such as compound admission, lifecycle,
  ownership, or protocol translation.

## Failure, Retry, And Recovery

- Classify failure by side-effect boundary before choosing retry, replay,
  requeue, polling, reconciliation, compensation, or drop.
- Failure before authoritative admission or work creation must not fabricate
  work merely to make a generic retry path uniform.
- A definitely-not-started side effect may be replayable; an unknown or partial
  effect requires owner-supplied idempotency, deduplication, exact fencing, or
  reconciliation before replay can be claimed safe.
- Invalid input or an unsupported outcome is not repaired by retry.
- Every retry/recovery path must name its trigger, state owner, storage/location,
  ordering effect, capacity or attempt bound, delay policy, termination rule,
  and duplicate/partial-effect consequence.
- Multiple retry, replay, repair, or compensation paths require distinct,
  non-overlapping invariants. Reject duplicate paths added only for defensive
  comfort; they obscure authority and can amplify load or side effects.
- Preserve strict order, exact pending state, epochs, versions, caches, or
  acknowledgements only when a named invariant requires them and the owning
  layer can repair or prove them.
- Intermediate best-effort layers must not copy authoritative lifecycle,
  consistency, or recovery state from the end owner. Prefer bounded loss or
  eventual observation when that matches the contract and ROI.

## Concurrency And Performance

- Map actual participants, shared mutable facts, and required happens-before
  edges before selecting a lock, atomic transition, queue, actor, task model,
  or executor.
- Do not optimize by keyword substitution. Replacing one coordination or
  execution primitive with another is not an improvement without removing
  contention, blocking, allocation, fan-out, queueing, or failure complexity.
- Delete synchronization when no cross-caller invariant exists; retain the
  smallest owner-local compound-transition gate when it does.
- Keep blocking/suspending I/O, callbacks, logging, and external owner calls
  outside state gates unless atomicity truly spans that operation and its cost
  is accepted explicitly.
- Do not repeat lifecycle or validity checks at every layer. Commit the decisive
  transition at the owner and add another fence only for a concrete race.
- Separate offered-load, throughput, latency, resource, fairness, and soak
  claims. A structural refactor or green functional test proves none of them.

## Rejection Reset

When the user rejects an owner, abstraction, lifecycle, consistency, or failure
model:

1. State the invalidated assumption.
2. Re-read the scoped production entry, representative flow, mutable state,
   callers, and tests.
3. Remove target types, states, and proof derived from that assumption; do not
   retain them for sunk cost, compatibility, documents, or existing tests.
4. Rewrite minimal pseudocode and the failure/side-effect table from zero.
5. Resume roadmap work only when the new model explains the correction without
   an exception, duplicated owner, or compatibility layer.

After repeated mechanism corrections, stop producing longer roadmaps. Show the
owner/state summary, minimal flow, failure decisions, and complexity delta
first. Treat current documents and structure tests as evidence to classify, not
implementation shapes to preserve.
