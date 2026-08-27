# Result-Routing Scheduling

Status: active Kernel mechanism contract; fixed Java production Pacer and
Python executable-spec oracle implemented.

Parent contracts:
[Task Item Score-Band Scheduling](task-item-score-band-scheduling.md) and
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
Redis shape: [Task Result Runtime Redis Shape](../runtime-redis/task-result-runtime-redis-shape.md).

## Purpose

Result Routing consumes two bounded Task result evidence lanes:

```text
SUCCESS
  -> TaskRuntime last-success result HASH
  -> TaskItemScoreBandCore FINAL_SUCCESS
  -> WorkerScoreCore completed-HOT exact release

FAILURE
  -> WorkerScoreCore exact release
```

It does not select Workers, claim Items, actively retry failed Items, refresh
Task score, parse score internals, interpret endpoint error codes, or own
Worker scheduling-serviceability truth.

## Protocol Boundary And Queues

`DeliveryReport.outcomeCode` remains an endpoint-owned Wire fact. Transport and
Server validate the producer and code namespace, then Server maps accepted
Task evidence to `TaskResultClass.SUCCESS` or `TaskResultClass.FAILURE`.
Kernel `TaskResultRuntime`, the fixed Result lane consumer, and
`TaskResultBatchPolicy` receive only that class; they never classify or branch
on the raw code.

The accepted mappings are:

```text
Worker 200                    -> SUCCESS
Worker-owned 3...             -> FAILURE
Adapter-owned Task rejection  -> FAILURE
```

Adapter Route changes, delivery-expiry evidence and connection snapshots use
the independent `ADAPTER -> KERNEL` Worker Serviceability result chain. An
expired Task Command therefore produces two distinct facts: a Task `FAILURE`
Report and a Serviceability Evidence Report. Neither lane substitutes for the
other.

The two transient Redis LISTs are:

```text
xa_mass:<scope>:result:routing:success
xa_mass:<scope>:result:routing:failure
```

`forward` remains opaque to the queue Runtime and carries `taskId`,
`messageId`, `workerId`, `workerGroupId`, and opaque `workerLeaseScore`.
TaskItem identity and the Worker lease fence are decoded only by Result
Routing. Queue members are destructive best-effort evidence, not pending/ack
truth.

## Fixed Lanes And Shared Batch Capacity

`ResultConvergenceApplication` owns three finite lane definitions; the two Task
lanes are always present and the Adapter Evidence lane exists only when Worker
Serviceability is enabled:

| Priority | Lane | Owner source | Batch limit | Target | Max |
| ---: | --- | --- | ---: | ---: | ---: |
| 0 | `TASK_SUCCESS` | `TaskResultRuntime.SUCCESS` | 100 | 1 | 1 |
| 1 | `TASK_FAILURE` | `TaskResultRuntime.FAILURE` | 100 | 3 | 10 |
| 2 | `ADAPTER_EVIDENCE` | `WorkerServiceabilityRuntime` | configured | 1 | 1 |

Each Redis key is one homogeneous lane: the whole consumed batch is handed to
one fixed policy function. Homogeneity does not require every Report in the
Adapter Evidence batch to have the same `messageType`; that policy owns its
finite event interpretation. The Task queue lane remains the only result-class
evidence visible to Kernel. A report whose raw `outcomeCode` contradicts its
Task lane is still processed according to the lane; preventing that
contradiction is the Server ingress invariant.

One non-daemon coordinator owns all dynamic lane counters and schedules at most
ten in-flight batches globally. Among eligible lanes below their maximum, it
selects the smallest `inflight / target` ratio by integer cross multiplication;
the fixed priority is only the tie-breaker. It directly attempts the existing
bounded destructive consume, without `LLEN`, peek or a second queue state. An
empty read or consumer exception delays only that lane by its existing idle
interval, leaving unused capacity available to the others.

Every non-empty batch runs on its own named JVM virtual thread. SUCCESS and
Adapter Evidence retain `max=1`, preserving consumed-Batch processing order.
FAILURE may borrow every otherwise unused slot up to ten because its policy is
only exact Worker-lease release; its Batch completion order is deliberately
unspecified. Redis FIFO therefore guarantees consumption order, not concurrent
FAILURE completion order. Successful completion releases capacity immediately.
A policy `RuntimeException` loses that best-effort Batch and delays future
consumption for the affected lane without cancelling its other in-flight
Batches.

For each consumed report, Result Routing validates only:

```text
dst == TASK
forward decodes to a valid ResultContext
```

Malformed or misdirected reports are consumed and discarded. Valid inputs are
normalized into bounded owner-local indexes:

```text
taskId -> ordered TaskResultEvidence(taskId, messageId, payload)
workerGroupId -> ordered WorkerResultEvidence(workerId, workerLeaseScore)
```

Within one lane batch, repeated Task message IDs and Worker IDs use the last
queue occurrence. This is bounded collapse, not cross-lane winner selection.
SUCCESS remains single-flight so its last-payload behavior is not reordered
across Batches. Exact score-owner fencing makes concurrent FAILURE release
safe and decides whether a selected Worker observation still applies.

Python Oracle and Java production both install exactly the two built-in
strategies. There is no public Handler map, dynamic registry, reflection,
ServiceLoader or replacement policy surface.

### SUCCESS

```text
store last payload per Task/messageId
-> promote the same Item IDs to FINAL_SUCCESS
-> exact-release each correlated Worker lease
```

Payload storage precedes Item promotion, so a promoted success has stored
result truth. A later success may replace an earlier payload and may promote
an Item from `FINAL_FAILED` according to the existing Item owner contract.

Worker release uses `releaseCompletedHotScoreHolds`. It accepts only:

- the original positive HOT assignment lease; or
- the exact RECOVERY counterpart derived by the Score Owner from that lease.

The same-key Lua operation may restore that exact counterpart to HOT and then
release it. A newer lease, pause, cold coordinate, dirty drift, retry advance,
or unrelated RECOVERY score returns `STALE` or `INVALID`. This is a cheap,
opportunistic use of successful execution evidence, not a general
RECOVERY-to-HOT or connection-state API.

### FAILURE

```text
exact-release each correlated Worker lease
-> do not store payload
-> do not mutate TaskItem retry/finality coordinate
-> do not change Worker polarity
```

Worker handler failure and Adapter rejection are identical to Result Routing.
The Item remains at its existing claim coordinate and normal claim expiry
provides retry. Active connection evidence and delivery-expiry serviceability
policy remain owned by the separate Adapter Evidence Pacer.

## Failure Semantics

```text
malformed context or non-TASK report
  -> consume and discard; no owner mutation

Worker STALE / NOOP / INVALID
  -> do not roll back Task result or Item truth

missing DeliveryReport
  -> UNKNOWN; Item claim and Worker lease expiry recover

process crash after queue pop
  -> evidence may be lost; normal owner expiry paths recover

duplicate evidence
  -> may be consumed again; owner-local monotonic transition or exact fence
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
owners but never observes or assembles a lane or policy. Target and maximum
concurrency are fixed Kernel policy constants, not Server/Pacer configuration
or Health state.

- Do not let Adapter or Worker mutate score directly.
- Do not parse exact Worker or Adapter subcodes in Kernel Result Routing.
- Do not partition by exact code, Task, WorkerGroup or producer.
- Do not infer connection polarity from the `FAILURE` lane.
- Do not add fast Item retry without an opaque Item claim fence and exact Item
  score-owner release operation.
- Do not infer failure from missing or timed-out evidence.
- Do not promote an Item before storing its successful payload.
- Do not add cross-lane precedence, winner aggregation or reliable queue state
  without a separately named invariant and Owner.
