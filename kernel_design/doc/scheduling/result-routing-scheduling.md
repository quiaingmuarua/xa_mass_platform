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
Kernel `TaskResultRuntime` and `ResultRoutingPacer` receive only that class;
they never classify or branch on the raw code.

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

## Routing Round

One round consumes the lanes in fixed order:

```text
SUCCESS -> FAILURE
```

Each lane may consume up to `perResultClassBatchLimit` (currently 100), so one
round handles at most 200 reports. The queue lane is the only result-class
evidence visible to Kernel. A report whose raw `outcomeCode` contradicts its
lane is still processed according to the lane; preventing that contradiction
is the Server ingress invariant.

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
Exact score-owner fencing decides whether a selected Worker observation still
applies.

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

An Owner exception stops the current round. Already popped evidence is not
replayed; the Application records a safe operation-level failure and attempts
the next round.

## Application And Guardrails

`ResultRoutingApplication` owns one non-daemon loop with a 100ms default
cadence. Lifecycle is composed by `KernelPacerRuntime`; Server supplies owners
but never selects result policy.

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
