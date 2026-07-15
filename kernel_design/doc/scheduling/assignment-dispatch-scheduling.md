# Assignment-Dispatch Scheduling

Status: new-kernel mechanism note. This document defines the shared contract
between two mandatory independent pacers. It is not current implementation
truth and not an implementation roadmap.

Detailed mechanisms:

- [Task-Worker Allocation Pacer](task-worker-allocation-pacer.md)
- [Task Item Dispatch Pacer](task-item-dispatch-pacer.md)
- [DeliverSeed Outbound Delivery](deliver-seed-outbound-delivery.md), which
  begins after the assignment-dispatch cutpoint

Current executable-spec gap:

```text
TaskWorkerAllocationPacer has a first executable allocation-round implementation
TaskDispatchRuntime has a Redis ZSET executable implementation
Task Item score and TaskItem record mechanisms have Redis executable implementations
TaskItemDispatchPacer, DeliverSeed, and DeliverSeedQueue surface have executable implementations
no DeliverSeedQueue storage implementation or outbound consumer
current Redis dispatch-task acquire is oldest-first, not recent-allocation reverse
```

This document defines the target split. Both pacers now have executable-spec
implementations; the second pacer proof stops at a recording DeliverSeed queue.

## Core Decision

Assignment-dispatch is a bounded scheduling-round coordination plane, not a
bridge, CRUD owner, or monolithic `schedule_once` loop.

It contains two independently paced mechanisms:

```text
TaskWorkerAllocationPacer
  due Task score candidates
    -> bounded Task x Worker matching
    -> Worker short lease and reservation collection
    -> Task allocation fairness timeSlot

TaskItemDispatchPacer
  recently allocated RUNNING_VISIBLE Tasks
    -> Worker reservation consumption
    -> Item score claim
    -> DeliverSeed queue append

DeliverSeed outbound owner
  queued DeliverSeed
    -> Worker validity / lease continuation
    -> transport submit
    -> non-exclusive release or exclusive retention
```

The separation is mandatory. The two pacers have different candidate bands,
cost profiles, scheduling frequencies, batch dimensions, failure modes, and
extension policies.

```text
allocation may be slower and batch-heavy
dispatch may be faster and consumption-heavy

allocation delay must not block dispatch from an existing candidate collection
dispatch congestion must not redefine Task-Worker matching
PRE_DISPATCH_VISIBLE must participate in allocation but never in Task Item dispatch
```

They are independent scheduling entries, not necessarily dedicated threads.

## Shared Owner Boundary

Assignment-dispatch does not own:

```text
Task lifecycle truth
Task score truth
Worker lifecycle or reachability truth
Worker score truth
Worker capacity truth
Task descriptor truth
Worker descriptor or dynamic attribute truth
Task Item record or Item score truth
Result finality
Transport session or route truth
Read models, trace, or diagnostics
```

It composes bounded owner evidence and invokes narrow owner primitives. An
implementation may place the pacers and score cores in one process, but method
boundaries must preserve the owner split.

## Score Authority

The two pacers do not lock Task resources.

```text
TaskWorkerAllocationPacer
  reads due Task ids from the score axis
  acquires short Worker leases, matches lease successes, retains unmatched
  leases until expiry, and appends transient reservations
  advances the acquired band's timeSlot without decoding Task score state

TaskRunningActivationPacer
  independently reads activation/allocation owner facts
  requests the specific PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE transition

TaskItemDispatchPacer
  Task-score read-only
  consumes candidate workers and claims current Item score without rewriting Task timeSlot
  or suffix
```

Task metadata updates and item append remain independent owner writes. They do
not acquire either pacer and do not refresh Task score.

Explicit lifecycle commands may still pause, resume, reject, cancel, or close
a Task through Task-score owner primitives. Those high-priority transitions
remove the Task from a pacer's scan range or make a stale score rewrite fail;
they do not require a pacer lock.

## Shared Score Pipeline

Both pacers use the same Task score axis with opposite scan intent:

```text
TaskWorkerAllocationPacer
  scans oldest due active Tasks first
  publishes bounded Worker reservations
  independently rotates each considered Task's current same-band timeSlot

TaskRunningActivationPacer
  independently observes allocation facts
  asks TaskScoreBandCore to activate RUNNING_VISIBLE with initial suffix N

TaskItemDispatchPacer
  scans RUNNING_VISIBLE from current time backward
  prioritizes the most recently allocated Tasks
  never rewrites Task score after dispatch
```

This is a Task fairness/freshness pipeline, not a Task ownership lease. Worker
exclusivity is represented separately by each published short score lease:

```text
oldest due Task
  -> candidate Worker reservation
  -> same-band allocation time rotation
  -> independent running activation
  -> RUNNING dispatch visibility
  -> candidate-worker consumption
```

Allocation prioritizes productive `RUNNING_VISIBLE` Tasks before pre-running
activation.
Dispatch prefers fresh worker evidence. Any policy that reserves capacity for
PRE_DISPATCH_VISIBLE belongs to deployment/operations policy, not this kernel
mechanism. Dispatch fairness is bounded separately by candidate collection
publication policy, per-Task dispatch quota, and scan limits. Worker validity
after candidate consumption belongs to queued DeliverSeed outbound delivery,
not TaskItem dispatch planning.

## Inter-Pacer Protocol

The required protocol seam is one candidate-worker ZSET per Task:

```text
taskId -> candidateWorkers[]

CandidateWorkerEntry
  workerId
  workerGroupId
  endpointManagerId
  workerLeaseScore
```

The Python DTO and runtime owner are implemented in
[`py_example/kernel/task_dispatch_runtime.py`](../../py_example/kernel/task_dispatch_runtime.py).
The Redis executable spec uses one ZSET per Task, scored by candidate batch
`expiresAtMillis`:

```text
ad:{prefix}:task:{taskId}:candidate-workers
```

The member contains the complete `CandidateWorkerEntry`, including the matched
Worker's `endpointManagerId` and opaque `workerLeaseScore`. The entry DTO still
has no expiry field; one expiry is supplied by the allocation caller for the
whole appended batch.

The collection is:

```text
transient Worker reservation handoff
produced by bounded allocation lease-and-match batches; no runtime-owned length cap
appendable by allocation rounds
atomically consumable in a caller-bounded batch
expired batches excluded from count and consume
safe to lose, skip, or discard
```

The collection is not:

```text
Task truth
Worker truth
a durable assignment record
a Task lock
a second Task discovery index
a guaranteed dispatch plan
```

It must not store complete Task/Worker descriptors, dynamic attribute values,
decoded Worker-score internals, transport handles, or result state.

## Publication And Consumption

Allocation publishes evidence independently from Task lifecycle activation:

```text
lease and match bounded workers; unmatched leases expire naturally
  -> atomically append one Task's reservation entries
  -> independently request current same-band timeSlot rotation

separate running activation
  -> read current owner facts
  -> request PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE
```

There is no score/collection commit protocol. Publication occurs before the
same-band time rewrite. A failed publication does not require a Task-score
rollback or Worker-score compensation. Allocation leaves every acquired Worker
lease intact, propagates the runtime failure, and relies on bounded lease expiry
to restore Worker visibility. A stale/rejected time rewrite does not roll back
already published candidate evidence. If a lifecycle command wins after
publication, the Task leaves the dispatch scan and the collection becomes
unreachable residue for later Task physical cleanup.

Task Item dispatch consumes entries using an owner-local atomic pop/claim primitive.
Multiple TaskItemDispatchPacer workers may run concurrently without a Task lock;
one candidate entry must be returned to at most one consumer.

The atomic boundary is one Task queue:

```text
append_candidate_workers(taskId, entries, expiresAtMillis)
candidate_worker_counts(taskIds)
consume_candidate_workers(taskId, limit)
```

Append stores every candidate supplied by the caller under one batch expiry.
Runtime does not choose a collection length or trim to Task policy.
Append and batched count remove physically expired members from the touched
Task keys; consume also removes expiry before pop. This owner-local cleanup
avoids a background scanner. `candidate_worker_counts` returns only non-expired
entry counts. It still does not prove current Worker validity, lease ownership,
dirty state, or constraint match and is not a capacity or lifecycle decision.

`maximumCandidateWorkers` is the Task-owned best-effort live collection target:

```text
WorkerCandidateConstraint.limit =
  max(0, Task.maximumCandidateWorkers - currentLiveCandidateCount)
```

Candidate allocation reads the bounded count batch before matching. Runtime
still does not enforce or reinterpret the Task limit during append. Concurrent
allocation pacers may observe the same prior count and temporarily overshoot;
the first cut accepts this rather than adding cross-Task candidate-slot
reservation or append-time trimming.

The current built-in activation policy uses this live-entry count as its first
executable approximation. It is point-in-time allocation evidence and does not
prove the configured minimum remains valid through the Task score transition
or after Worker score, dirty, or attribute changes. Strong current-validity
semantics require an explicit Worker revalidation/reservation result; they must
not be hidden inside `TaskDispatchRuntime` count.

The first executable
`TaskRunningActivationPacer.activate_running_visible_tasks(config)` scans one
due `PRE_DISPATCH_VISIBLE` band range, checks
the configured `TaskRunningActivationPolicy`, and requests
`PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE` with an explicit configured initial
RUNNING suffix. `expected_band` plus the score primitive's write-time range
check rejects a Task that has already moved; the pacer does not re-read or
decode Task score state. If the policy returns false, activation leaves the
Task `PRE_DISPATCH_VISIBLE`: it does not decrement suffix and does not write an
automatic pause. A later bounded activation round may retry.

The built-in policy is `minimum_candidate_workers_satisfied(descriptor,
candidateWorkerCount) -> bool`. Alternative policies may change the activation
condition, but they return a decision only and must not mutate score, candidate
runtime, descriptors, or external state.

Cross-Task pipeline/multi-call wrappers may reduce network round trips, but each
Task key succeeds or fails independently. No interface may imply all-or-nothing
atomicity across Task queues.

## Worker Fence Handoff

Allocation first reads a bounded due HOT `(workerId, observedScore)` batch. The
pacer keeps the opaque observations in a sidecar and attempts one exact
batched observed-score lease with an independent CAS per Worker. Only
lease-success ids enter the matcher.
Unmatched leases are consumed negative scheduling evidence and remain held
until expiry. Matched lease scores and endpoint-manager queue coordinates become
`CandidateWorkerEntry` values.
Matcher does not read or mutate Worker score.

Task Item dispatch does not recheck or mutate Worker score. It copies the
consumed opaque lease evidence into DeliverSeed:

```text
consume CandidateWorkerEntry
  -> claim one current TaskItem score
  -> DeliverSeed(workerId, workerGroupId, endpointManagerId, workerLeaseScore,
       claimScore, TaskItem)
  -> append DeliverSeed to the endpointManagerId queue

outbound DeliverSeed consumer
  -> validate or renew the exact Worker lease evidence
  -> submit to the already selected Worker
  -> release after accepted non-exclusive delivery or retain for exclusive use
```

Concurrent allocation rounds may observe the same due Worker, but only one
exact-score lease CAS can pass it into matching. Within one matcher call, a
Worker is consumed by at most one candidate. This exclusivity is only a short
allocation window. A stale
collection entry can remain after dirtying, release, lease due, or metadata
change. TaskItem dispatch may still turn that opaque evidence into a queued
seed; outbound Worker validation decides whether delivery may continue. Such
an entry and seed are disposable intermediate evidence, not a second assignment
or Worker truth.

The candidate expiry must not be later than the Worker lease written for that
batch:

```text
candidateExpiresAtMillis <= workerLeaseUntilMillis
```

The first executable policy uses equality. This ensures an old candidate is no
longer live before that Worker can be leased into a later candidate batch.
`TaskDispatchRuntime` does not decode Worker score to enforce the relation; the
trusted allocation protocol supplies both values from the same round.

## Cross-Pacer Liveness

Neither pacer depends on an event from the other.

```text
allocation faster than dispatch
  producer subtracts the current live-entry count before matching; concurrent
  rounds may temporarily overshoot the best-effort Task target

dispatch faster than allocation
  empty/missing collections are bounded no-op results

collection write lost
  allocation propagates without Worker-score compensation; acquired leases
  remain held until bounded expiry and a later round may reserve the Worker again

candidate Worker stale
  outbound seed consumption rejects stale Worker evidence; later allocation
  produces fresh evidence

Task paused or terminal
  Task leaves later eligible scan ranges; an already-started bounded dispatch
  round may still append a DeliverSeed
```

Events may lower latency but cannot be required to wake either pacer.

## Shared Guardrails

- Do not collapse both pacers into one monolithic scheduling entry.
- Do not let TaskItemDispatchPacer write Task timeSlot, suffix, band, or terminal
  score.
- Do not let TaskItemDispatchPacer read, validate, renew, release, or retain
  Worker score.
- Do not let TaskItemDispatchPacer call transport; its cutpoint is DeliverSeed
  queue append.
- Do not make candidate-worker collection a second global Task index.
- Do not publish a candidate Worker unless its allocation lease succeeded.
- Do not gate candidate publication on either same-band time rotation or Task
  lifecycle activation.
- Do not let allocation decode Task score, choose a target band, change suffix,
  hold, close, or advance lifecycle. Its only score write is current same-band
  timeSlot rotation using the band-specific acquisition context.
- Do not put Task or Worker resource mutations behind either pacer.
- Do not refresh Task score because append, result, heartbeat, metadata, trace,
  or projection changed.
- Do not make transport select a Worker or reinterpret candidate entries.
- Do not create DeliverSeed without current Item `claimScore` evidence.
- Do not retain candidate collections as durable assignment truth.
- Do not require cross-key atomicity between Task score and candidate-worker
  collection.
- Do not expose cross-Task candidate append/consume as an atomic owner
  primitive. Batch wrappers are non-atomic execution optimizations only.
