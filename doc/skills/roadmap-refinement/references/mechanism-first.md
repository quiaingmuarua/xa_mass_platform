# Mechanism-First Review

Read this reference only when roadmap work changes a stateful or concurrent
internal mechanism. Its purpose is to prevent formal roadmap structure from
hiding an incoherent runtime model; it does not prescribe one architecture,
language, concurrency primitive, or retry strategy.

## Evidence For The Affected Mechanism

Answer the following questions from the affected production entry and flow
before choosing a new execution model or abstraction. Reuse established evidence
and combine answers in one compact sketch when that is sufficient; these are
evidence dimensions, not six required documents or a repeated approval gate.
Explain omitted dimensions when their relevance could be misunderstood. A
prose-only change does not activate this review.

1. **Representative-flow trace**: follow one real request, item, event, or state
   change through entry, validation/admission, owner transition, side effect,
   outcome/evidence, and termination or recovery.
2. **State ownership**: identify the mutable facts, stores, queues, caches or
   registries involved in the changed invariant; their mutation authority,
   readers, lifetime and cleanup. Expand to field-level detail only when it
   resolves a race or ownership question. Derived observation is not another
   state owner.
3. **Failure/side effects**: for each relevant failure boundary, record what was
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
   Explain material increases by the required invariant and its appropriate
   proof; do not demand a new test solely because a local type was introduced.

Use a compact mechanism description to expose uncertainty. A complex mechanism
may need several bounded flows; inability to fit one table does not establish
that the design is invalid. This skeleton is optional:

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
- For retry/recovery paths being changed, establish trigger, owner, state,
  ordering, resource bound, delay and termination behavior, including duplicate
  or partial effects. An attempt limit is not mandatory when the contract uses
  lifetime-bounded retries with bounded storage; preserve the actual guarantee.
- Multiple retry, replay, repair or compensation paths need explicit roles and
  coordination at overlapping failure boundaries. They may legitimately cover
  related failures at different layers; reject redundant paths that amplify
  effects or load without adding a required guarantee.
- Preserve strict order, exact pending state, epochs, versions, caches, or
  acknowledgements only when a named invariant requires them and the owning
  layer can repair or prove them.
- Intermediate best-effort layers must not copy authoritative lifecycle,
  consistency or recovery state from the end owner. Bounded loss or eventual
  observation is appropriate only when the required contract permits it; cost
  preference alone cannot weaken delivery, atomicity or recovery guarantees.

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
3. Discard the plan decisions derived from the rejected assumption. Retain
   independently valid facts, required compatibility and useful tests. This
   analysis does not authorize deleting production code before a working
   replacement or changing unrelated owners.
4. Revise the affected flow and failure decisions without preserving the rejected
   shape for sunk cost. Leave unrelated decisions intact.
5. Continue when the corrected mechanism satisfies the user's intent and current
   contracts. Ask only if a material choice still needs user input; do not add a
   new approval gate merely because the earlier sketch was rejected.

After repeated mechanism corrections, show the revised owner/state, flow and
failure reasoning before expanding the roadmap. This is a communication step,
not an automatic end to authorized work. Treat current documents and structural
tests as evidence to assess against behavior and invariants, not shapes to
preserve without justification.
