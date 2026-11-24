# Mechanism-First Review

Use this reference for a changed stateful or concurrent mechanism. A compact
flow or sketch is enough; the questions below are not required documents.

## State And Execution

Trace a representative input through admission, the authoritative transition,
external side effects, returning evidence and termination or recovery. Identify
mutable facts and their writers, readers, lifetime and cleanup. Expand to
field-level ownership only where it resolves a race or disputed boundary.

Map actual callers, threads/executors, blocking work and cancellation. Establish
required happens-before edges before choosing locks, queues or another execution
model. Keep I/O, callbacks and other Owner calls outside state gates unless the
required atomicity spans that operation and its cost is justified. Another
validation or fence needs a concrete race, not symmetry between layers.

## Failure And Recovery

At each affected failure boundary, distinguish an effect that definitely did
not start, may have started, or completed. Unknown or partial effects require
Owner-supplied idempotency, exact fencing or reconciliation before replay can
be considered safe. Invalid input is not repaired by retry; failure before
admission must not fabricate work to fit a generic recovery path.

For changed retry paths, establish the trigger, owner, ordering, retained state,
resource bound and termination behavior. An attempt limit is not mandatory
when the contract instead bounds retries by lifetime and storage. Where multiple
layers retry, explain their distinct roles and whether they can amplify effects
or load at the same boundary.

Do not weaken required delivery, atomicity or recovery guarantees to simplify
the design. Intermediate observation must not acquire authoritative recovery
state. Bounded loss is valid only where the actual contract permits it.

## Boundary And Cost

Separate mechanisms for independent state/lifetime, protocol or dependency
boundaries, or current implementations with shared semantics. Similar method
signatures do not establish common authority or failure behavior. A helper or
carrier is useful when it protects an invariant, removes duplicated semantics
or crosses a real boundary; neither adding nor deleting types proves simplicity.

Compare material changes in stored facts, lifecycle states, queues, caches,
background work, scans, fan-out and coordination. Fixed topology usually permits
explicit composition. Explain additional machinery by the invariant it supplies
and how it will be verified, rather than hypothetical growth.

When a model is rejected, identify the invalidated assumption, re-read the
affected flow and revise decisions derived from it. Retain independently valid
facts and unrelated decisions. Show the corrected mechanism before expanding
its plan; this is not a reason to discard working code or restart the whole
repository investigation.
