# Result-Routing Scheduling

Status: active Java Kernel Result Convergence contract.

Parent contracts:
[Task Item Score-Band Scheduling](../../../kernel_jvm/doc/score/task-item-score-band-scheduling.md)
and
[Worker HOT_ACQUIRE Lease Protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md).
Redis shape:
[Task Evidence And Result Redis Shape](../../../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md).

## Purpose

Result Routing consumes three bounded Task evidence lanes:

```text
SUCCESS
  -> TaskResultBatchPolicy decode and bounded last-wins grouping
  -> TaskItemResultEvents.onItemsSucceeded
  -> WorkerExecutionResultEvents.onTaskSucceeded

FAILURE
  -> TaskResultBatchPolicy decode and bounded last-wins grouping
  -> WorkerExecutionResultEvents.onTaskFailed

OUTCOME_OBSERVATION
  -> TaskResultBatchPolicy decode, Worker correlation check and Task grouping
  -> TaskItemResultEvents.onItemOutcomesObserved
```

It does not select Workers, claim Items, actively retry failed Items, refresh
Task score, parse score internals, interpret endpoint error codes, or own
Worker scheduling-serviceability truth. The finite event Mechanisms decide
which mechanical TaskItem and Worker operations currently implement each
semantic result event. In particular, retryable FAILURE evidence does not
decide TaskItem finality.

## Protocol Boundary And Queues

`DeliveryReport.diagnosticCode` is diagnostic only. Transport and Server validate
producer and exact event contracts; Server maps accepted Task evidence to
`TaskEvidenceType.EXECUTION_SUCCESS`, `EXECUTION_FAILURE` or `OUTCOME_OBSERVATION`.
Kernel `TaskEvidenceRuntime`, the fixed Result lane consumer, and
`TaskResultBatchPolicy` receive only that class; they never classify or branch
on the raw code.

The accepted mappings are:

```text
WORKER / platform.worker.command.succeeded         -> SUCCESS
WORKER / platform.worker.command.failed            -> FAILURE
ADAPTER / platform.adapter.command.delivery-failed -> FAILURE (validated payload)
WORKER / platform.worker.task-outcome.observed     -> OUTCOME_OBSERVATION
```

Adapter Route changes, delivery-expiry evidence and connection snapshots use
the independent `ADAPTER -> KERNEL` Worker Serviceability result chain. An
expired Task Command therefore produces two distinct facts: a Task `FAILURE`
Report and a Serviceability Evidence Report. Neither lane substitutes for the
other.

The three transient Redis LISTs are:

```text
xa_mass:<scope>:result:routing:success
xa_mass:<scope>:result:routing:failure
xa_mass:<scope>:result:routing:observation
```

`forward` remains opaque to the queue Runtime and carries `taskId`,
`messageId`, `workerId`, `workerGroupId`, and opaque `workerLeaseScore`.
`TaskResultBatchPolicy` decodes the identities, but receives the lease only as
`WorkerLeaseReference`. That reference has no public numeric accessor or
serialization surface; only the Worker event implementation may unwrap it.
Queue members are destructive best-effort evidence, not pending/ack truth.

## Fixed Lanes And Shared Batch Capacity

`ResultConvergenceApplication` installs four finite lanes in every preset.
Network Evidence consumption is independent of optional periodic Serviceability
probes:

| Priority | Lane | Owner source | Batch limit | Target | Max |
| ---: | --- | --- | ---: | ---: | ---: |
| 0 | `TASK_SUCCESS` | `TaskEvidenceRuntime.EXECUTION_SUCCESS` | 100 | 6 | 10 |
| 1 | `TASK_FAILURE` | `TaskEvidenceRuntime.EXECUTION_FAILURE` | 100 | 3 | 10 |
| 2 | `NETWORK_EVIDENCE` | `WorkerServiceabilityRuntime` | configured | 1 | 1 |
| 3 | `TASK_OBSERVATION` | `TaskEvidenceRuntime.OUTCOME_OBSERVATION` | 100 | 1 | 10 |

Each Redis key is one homogeneous lane: the whole consumed batch is handed to
one fixed policy function. Homogeneity does not require every Report in the
Network Evidence batch to have the same producer or `messageType`; that policy owns its
finite event interpretation. The Task queue lane remains the only result-class
evidence visible to Kernel. Diagnostic `diagnosticCode` has no success/failure
meaning, even when it contains `200` or `3303`. Server establishes the lane
from exact Report semantics; Kernel does not reclassify the carried event.

One non-daemon coordinator owns all dynamic lane counters and schedules at most
ten in-flight batches globally. Among eligible lanes below their maximum, it
selects the smallest `inflight / target` ratio by integer cross multiplication;
the fixed priority is only the tie-breaker. It directly attempts the existing
bounded destructive consume, without `LLEN`, peek or a second queue state. An
empty read or consumer exception delays only that lane by its existing idle
interval, leaving unused capacity available to the others.

Every non-empty batch runs on its own named JVM virtual thread. SUCCESS, FAILURE and OUTCOME_OBSERVATION may each borrow every otherwise unused slot up to ten. Their Batch
completion order is deliberately unspecified: Redis FIFO guarantees consumption
order, not concurrent policy completion order. SUCCESS remains safe because
Item promotion is monotonic and Worker release uses the completed-HOT exact
fence; FAILURE only exact-releases the correlated Worker lease. Network Evidence
retains `max=1` because it has no cross-Batch Evidence fence. Successful
completion releases capacity immediately.
A policy `RuntimeException` loses that best-effort Batch and delays future
consumption for the affected lane without cancelling its other in-flight
Batches.

For each consumed execution report, Result Routing validates:

```text
dst == TASK
forward decodes to a valid ResultContext
```

Malformed or misdirected reports are consumed and discarded. Valid inputs are
normalized into bounded owner-local indexes:

```text
taskId -> ordered TaskResultEvidence(taskId, messageId, payload)
workerGroupId -> ordered WorkerResultEvidence(workerId, WorkerLeaseReference)
```

Within one lane batch, repeated Task message IDs and Worker IDs use the last
queue occurrence. This is bounded collapse, not cross-lane winner selection.
Across concurrent SUCCESS batches, Result Owner compares the supplied tag and
policy observation time; equal ordering coordinates preserve the existing
content. Item promotion remains independently monotonic. Observation batches
use the business-reported time and preserve content separately from state-only
updates. Exact score-owner fencing decides whether a
concurrent SUCCESS or FAILURE Worker observation still applies.

Java production installs three fixed Task consumption functions and three finite
semantic event ports. `DeliveryReport`, lane identity, Adapter event names and
JSON stop at the policies; the event ports accept only bounded domain facts.
There is no public Handler map, dynamic registry, reflection, ServiceLoader or
replacement policy surface. Focused policy tests and Runtime Boundary proof
guard this Java production layering.

### SUCCESS

```text
TaskItemResultEvents.onItemsSucceeded
  -> store self-describing code=200 Results in one TaskRuntime HASH operation
  -> separately request TERMINAL(tag=6) promotion for the same Item IDs

WorkerExecutionResultEvents.onTaskSucceeded
  -> apply the correlated successful-execution event per WorkerGroup
```

Result storage precedes Item promotion, so a promoted success has stored
result truth under the normal Owner path. The Result write and Score promotion
are separate operations, not one cross-owner transaction. The current event
implementation does not interpret or retry a per-Item promotion result that
did not transition. A stored success may therefore coexist with an `ACTIVE` or
`TERMINAL(tag=5)` Score, and there is no current guarantee that it will eventually
reach `TERMINAL(tag=6)`.

One TaskRuntime operation encodes each payload with code `200` and writes the
Result HASH conditionally. A success may replace a terminal failed value; an
existing success is replaced only by a higher tag or a later time at the same
tag. Execution tag 6 cannot replace an observed higher-tag reply. When that evidence is actually consumed, the existing Item
owner contract may promote `TERMINAL(tag=5)` to `TERMINAL(tag=6)`. Terminal failed
storage uses `HSETNX` and therefore cannot replace any observed success.

The current Worker event implementation uses
`releaseCompletedHotScoreHolds`. It accepts only:

- the original positive HOT assignment lease; or
- the exact sign-flipped RECOVERY counterpart of that lease.

The same-key Lua operation may restore that exact counterpart to HOT and then
release it while preserving the lease's low bits. A newer lease, pause, cold
coordinate, dirty drift, retry advance, or unrelated RECOVERY score returns
`STALE` or `INVALID`. This is a cheap,
opportunistic use of successful execution evidence, not a general
RECOVERY-to-HOT or connection-state API.

### FAILURE

```text
WorkerExecutionResultEvents.onTaskFailed
  -> currently exact-releases each correlated Worker lease
  -> does not change Worker polarity
```

Worker handler failure and Adapter rejection are identical to Result Routing.
The Result policy neither stores a TaskItem result nor changes its score. The
Item remains at its existing claim coordinate and normal claim expiry provides
retry. Only Task Dispatch writes the fixed self-describing `failed` Result when
existing retry budget is exhausted or TTL expires, before requesting
`TERMINAL(tag=5)`. Active
connection evidence and delivery-expiry serviceability policy remain owned by
the separate Adapter Evidence Pacer.

### OUTCOME_OBSERVATION

The fixed `platform.worker.task-outcome.observed` Report must be WORKER-origin,
TASK-directed and correlated to that Worker in the original `forward`. Its
payload carries tag 6..9, positive `observedAtMillis`, and optional nonblank
`opaqueResultPayload`. Names belong to the Server application. Original lease
liveness is not checked and this path calls no Worker event or Worker Score.

The semantic mechanism groups at most 100 Item targets on the same Task into
one Score operation. It independently keeps the maximum state target and the
latest content-bearing observation for each Item. TRANSITIONED and NOOP permit
one conditional Result operation; missing, invalid and corrupt Items do not
produce content. Millisecond content updates may succeed within a NOOP 100ms
Score slot. There are no existence/confirmation reads or cross-owner transactions.

The same Item remains observable after execution and Task closure. Loss before
consumption or failure between Score and Result may leave missing or older
content. No replay, ACK, expiry extension, lease release or Task reopening is
implied. Observations share the existing coordinator and global capacity; they
add one Redis LIST and no resident thread or extra executor.

## Failure Semantics

```text
malformed context or non-TASK report
  -> consume and discard; no owner mutation

Worker STALE / NOOP / INVALID
  -> do not roll back Task result or Item truth

missing DeliveryReport
  -> UNKNOWN; Item claim and Worker lease expiry recover

process crash after queue pop, before Result storage
  -> evidence may be lost; claim/lease expiry recovers resource eligibility,
     not the consumed Result evidence

process crash after success Result storage, before Score promotion
  -> Result remains success while Score may remain ACTIVE or TERMINAL(tag=5);
     there is no pending/ack, replay or Result-to-Score repair owner

process crash after Score promotion, before Worker release
  -> Item finality remains TERMINAL(tag=6); Worker lease expiry may recover

duplicate evidence
  -> when consumed again, owner-local monotonic transition or exact fence
     decides whether it changes truth
```

An Owner `RuntimeException` loses the already-popped batch under the existing
best-effort contract, records only safe lane/operation/batch-size metadata, and
backs off future consumption for that lane. Other lanes and already-running
FAILURE Batches remain independent. A JVM `Error` or executor rejection fails
the unified Application and therefore Kernel readiness.

## Application And Guardrails

`ResultConvergenceApplication` is the only Result lifecycle. It owns one
non-daemon coordinator platform thread, a virtual-thread-per-batch executor,
ten global Batch slots, and only coordinator-owned per-lane `inflight` and
backoff state. Lifecycle is composed by `KernelPacerRuntime`; Server supplies
mechanical owners, and the Runtime constructs the three default semantic event
owners before assembling its policies. Server never observes or assembles a
lane, policy, or event Mechanism. Target and maximum
concurrency are fixed Kernel policy constants, not Server/Pacer configuration
or Health state.

- Do not let Adapter or Worker mutate score directly.
- Do not parse exact Worker or Adapter subcodes in Kernel Result Routing.
- Do not pass `DeliveryReport`, lane identity, JSON, Adapter Event Name or a raw
  Worker score into an Owner semantic event port.
- Do not let a Result policy call Task, TaskItem score, Worker score or Worker
  catalog mechanical operations directly.
- Do not partition by exact code, Task, WorkerGroup or producer.
- Do not infer connection polarity from the `FAILURE` lane.
- Do not add fast Item retry without an opaque Item claim fence and exact Item
  score-owner release operation.
- Do not infer failure from missing or timed-out evidence.
- Do not promote an Item before storing its successful payload.
- Do not add cross-lane precedence, winner aggregation or reliable queue state
  without a separately named invariant and Owner.

Worker ResultContext carries the execution score returned by final confirmation,
which has already consumed candidate eligibility. Subsequent Properties dirty
invalidation is a NOOP for that fence. Result routing and execution events do
not reconstruct the initial Candidate score or clear dirty; after release, only
new initial HOT acquisition restores candidate eligibility. The
[HOT lease protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
owns confirmation, invalidation races and upgrade limits.
