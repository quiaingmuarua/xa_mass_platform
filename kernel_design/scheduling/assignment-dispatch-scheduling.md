# Assignment-Dispatch Scheduling

Status: new-kernel mechanism note. This document defines the shared contract
between two mandatory independent pacers. It is not current implementation
truth and not an implementation roadmap.

Detailed mechanisms:

- [Task-Worker Allocation Pacer](task-worker-allocation-pacer.md)
- [Work Dispatch Pacer](work-dispatch-pacer.md)

Current executable-spec gap:

```text
no WorkDispatchPacer implementation
TaskWorkerAllocationPacer has a first executable allocation-round implementation
TaskDispatchRuntime has a Redis LIST executable implementation
Work/backlog owner and claim interface are intentionally unfrozen
current Redis dispatch-task acquire is oldest-first, not recent-allocation reverse
```

This document defines the target split. Only the first allocation pacer and its
Redis handoff runtime currently have executable-spec implementations.

## Core Decision

Assignment-dispatch is a bounded scheduling-round coordination plane, not a
bridge, CRUD owner, or monolithic `schedule_once` loop.

It contains two independently paced mechanisms:

```text
TaskWorkerAllocationPacer
  due Task score candidates
    -> bounded Task x Worker matching
    -> candidate-worker collection
    -> Task allocation fairness timeSlot

WorkDispatchPacer
  recently allocated RUNNING_VISIBLE Tasks
    -> candidate-worker consumption
    -> Worker short lease
    -> Work claim
    -> DeliverSeed
```

The separation is mandatory. The two pacers have different candidate bands,
cost profiles, scheduling frequencies, batch dimensions, failure modes, and
extension policies.

```text
allocation may be slower and batch-heavy
dispatch may be faster and consumption-heavy

allocation delay must not block dispatch from an existing candidate collection
dispatch congestion must not redefine Task-Worker matching
PRE_DISPATCH_VISIBLE must participate in allocation but never in work dispatch
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
Backlog or current work truth
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
  only routine Task-score writer in assignment-dispatch
  owns allocation fairness, activation classification, allocation recheck,
  and no-worker allocation outcomes

WorkDispatchPacer
  Task-score read-only
  consumes candidate workers and current work without rewriting Task timeSlot
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
  successful allocation writes timeSlot = current scheduling time
  moves the Task toward the newest end of the due active range

WorkDispatchPacer
  scans RUNNING_VISIBLE from current time backward
  prioritizes the most recently allocated Tasks
  never rewrites Task score after dispatch
```

This is a fairness/freshness pipeline, not an ownership lease:

```text
oldest due Task
  -> allocation
  -> current timeSlot
  -> recent dispatch candidate
  -> candidate-worker consumption
```

Allocation prioritizes productive RUNNING work before pre-running activation.
Dispatch prefers fresh worker evidence. Any policy that reserves capacity for
PRE_DISPATCH_VISIBLE belongs to deployment/operations policy, not this kernel
mechanism. Dispatch fairness is bounded separately by candidate collection
publication policy, per-Task dispatch quota, scan limits, and consume-time
candidate expiry validation.

## Inter-Pacer Protocol

The required protocol seam is one expiring candidate-worker LIST per Task:

```text
taskId -> candidateWorkers[]

CandidateWorkerEntry
  workerId
  workerGroupId
  completeObservedWorkerScore
  expiresAtMillis
```

The Python DTO and runtime owner are implemented in
[`py_example/kernel/task_dispatch_runtime.py`](../py_example/kernel/task_dispatch_runtime.py).
The Redis executable spec uses one LIST per Task:

```text
ad:{prefix}:task:{taskId}:candidate-workers
```

The collection is:

```text
transient scheduling evidence
produced by bounded allocation batches; no runtime-owned length cap
appendable by allocation rounds
atomically consumable in a caller-bounded batch
entry expiry validated only when consumed
safe to lose, skip, expire, or discard
```

The collection is not:

```text
Task truth
Worker truth
an assignment record
a Worker reservation
a Task lock
a second Task discovery index
a guaranteed dispatch plan
```

It must not store complete Task/Worker descriptors, dynamic attribute values,
decoded Worker-score internals, transport handles, or result state.

## Publication And Consumption

Allocation publishes evidence only after the Task-score fairness rewrite is
accepted:

```text
match bounded workers
  -> request Task timeSlot rewrite
  -> stale / rejected: do not publish
  -> transitioned: atomically append one Task's candidate entries
```

This deliberately avoids cross-key atomicity. If the score rewrite succeeds but
collection publication fails, the Task loses one allocation opportunity and
later returns through normal oldest-first allocation. If a lifecycle command
wins after publication, the Task leaves the dispatch scan and the collection
becomes unreachable residue for later Task physical cleanup.

Work dispatch consumes entries using an owner-local atomic pop/claim primitive.
Multiple WorkDispatchPacer workers may run concurrently without a Task lock;
one candidate entry must be returned to at most one consumer.

The atomic boundary is one Task queue:

```text
append_candidate_workers(taskId, entries)
candidate_worker_count(taskId)
consume_candidate_workers(taskId, limit)
```

Append stores every entry supplied by the caller. Runtime does not
choose a queue length or trim to Task policy. `candidate_worker_count` is a
point-read of the current stored LIST length; it is not a capacity decision and
does not scan, decode, or subtract expired entries.

`TaskWorkerAllocationPacer` owns the policy calculation before matching:

```text
remaining = max(0, Task.maximumCandidateWorkers - storedQueueLength)
```

A full queue skips Worker scan and matching. The Task score is still rotated so
WorkDispatchPacer can consume existing entries, including expired entries that
must leave through the consume path.

Cross-Task pipeline/multi-call wrappers may reduce network round trips, but each
Task key succeeds or fails independently. No interface may imply all-or-nothing
atomicity across Task queues.

## Worker Fence

Allocation does not lease Workers. It stores the complete observed Worker score
returned by HOT acquisition.

Work dispatch performs the actual short Worker lease:

```text
consume CandidateWorkerEntry
  -> acquire_due_hot_score_lease(completeObservedWorkerScore)
  -> success: continue to Work claim
  -> stale: discard entry and try another bounded entry
```

The same Worker may appear in collections produced by different allocation
rounds. Exact observed-score CAS decides which dispatch wins. Other entries are
stale disposable evidence, not consistency failures.

## Cross-Pacer Liveness

Neither pacer depends on an event from the other.

```text
allocation faster than dispatch
  producer match policy controls each publication size; the queue may accumulate
  until dispatch consumes it

dispatch faster than allocation
  empty/missing collections are bounded no-op results

collection write lost
  Task returns through Task-score allocation rotation

candidate Worker stale
  dispatch drops entry; later allocation produces fresh evidence

Task paused or terminal
  Task leaves eligible scan range; later Task physical cleanup owns LIST residue
```

Events may lower latency but cannot be required to wake either pacer.

## Shared Guardrails

- Do not collapse both pacers into one monolithic scheduling entry.
- Do not let WorkDispatchPacer write Task timeSlot, suffix, band, or terminal
  score.
- Do not make candidate-worker collection a second global Task index.
- Do not lease Workers during Task-Worker allocation.
- Do not put Task or Worker resource mutations behind either pacer.
- Do not refresh Task score because append, result, heartbeat, metadata, trace,
  or projection changed.
- Do not make transport select a Worker or reinterpret candidate entries.
- Do not create DeliverSeed without current Work claim evidence.
- Do not retain candidate collections as durable assignment truth.
- Do not require cross-key atomicity between Task score and candidate-worker
  collection.
- Do not expose cross-Task candidate append/consume as an atomic owner
  primitive. Batch wrappers are non-atomic execution optimizations only.
