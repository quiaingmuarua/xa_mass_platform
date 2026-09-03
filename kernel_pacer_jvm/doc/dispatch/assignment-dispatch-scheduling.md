# Assignment-Dispatch Scheduling

Status: active Java Kernel Dispatch Convergence contract.

Detailed owners:

- [Task Initialization](task-initialization-policy.md)
- [Task Worker Allocation](task-worker-allocation-pacer.md)
- [Task Dispatch](task-dispatch-pacer.md)
- [Worker Matching](../../../worker_matching_jvm/README.md)
- [Worker HOT_ACQUIRE Lease Protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
- [Worker Delivery Dispatch](../../../doc/kernel/worker-delivery-dispatch.md)

## Authority

Dispatch Convergence has one Main Scheduler and four fixed, single-flight
Resource Producers:

| Producer | Main input | Decision | Output |
| --- | --- | --- | --- |
| Task initialization | INITIAL RUNNING Tasks | Does the Task have due ACTIVE work? | exact INITIAL to NORMAL transition |
| Worker allocation | PRECOMPUTED NORMAL Tasks | Is Candidate Cache below its target? | ordered Match Demand for exact-held Workers |
| Task dispatch | NORMAL Tasks | Which Item may claim which held Worker? | Delivery Commands and Task pacing |
| Worker serviceability | demanded WorkerGroups | Which Routes need a Probe? | best-effort serviceability evidence |

Kernel remains the only scheduling authority. Worker Matching interprets
Properties and Rules but cannot observe Score, rank Tasks, lease Workers,
claim Items, or dispatch Commands.

## Allocation Mechanisms

`WorkerAllocationMechanism` selects one fixed vertical workflow:

| Mechanism | Persistent Rule owner | Worker acquisition | Candidate Cache |
| --- | --- | --- | --- |
| `PRECOMPUTED_TASK_RULE` | Matching Candidate Rule keyed by candidateId | ordered Group Demand filters a Pacer-held pool | used |
| `ON_DEMAND_ITEM_RULE` | Kernel finite Worker Selector | Kernel directly observes normalized explicit IDs or ANY | forbidden |

Task creation still accepts a PRECOMPUTED allocation Rule, which Server stores
in Worker Matching before writing the minimal Kernel Task resource. ON_DEMAND
Item calls instead require a finite `workerSelector` array. Kernel normalizes
it and stores only the Worker-ID list, where an empty list means ANY.

## PRECOMPUTED Flow

```text
Main selects due PRECOMPUTED Tasks
  -> Allocation reads Candidate Cache counts
  -> incomplete Tasks sort by priority then taskId within WorkerGroup
  -> current deficits bound due HOT observation to at most 100 Workers
  -> Pacer exact-holds the observed pool
  -> one ordered TaskRuleMatchDemand is offered for the Group
  -> Matching reads Candidate Rules and Worker facts
  -> Matching appends accepted workerId + opaque held score to Candidate buckets
  -> Dispatch consumes a Candidate bucket and exact-renews before Item claim
```

The Demand contains:

```text
workerGroupId
ordered (candidateId, maximumCandidateWorkers) needs, at most 100
ordered workerId -> exact held score map, at most 100
holdUntilMillis
```

The Task maximum is static; current deficits remain Pacer-local and only bound
how many Workers are held. Candidate Cache atomically removes expired entries
and admits no more than each Candidate maximum. Matching follows Pacer Task order
and removes only Cache-accepted Workers from the local pool, so one held Worker
enters at most one Candidate bucket per Demand.

Only one Demand may be pending per WorkerGroup. Rejection does not block and
does not release its holds. Missing matches, Cache rejection, partial failures,
and unselected holds recover through lease expiry. Other WorkerGroups are not
serialized behind that Group.

Candidate Cache remains disposable address-oriented state:

```text
candidateId -> CandidateWorkerEntry[]
CandidateWorkerEntry = workerId + exact heldWorkerLeaseScore
ZSET score = candidate expiry
```

It contains no Rule, Properties, WorkerGroup, or endpoint. Matching is a
bounded writer through the Cache owner API, not the Cache truth owner.

## ON_DEMAND Flow

Kernel validates only these Item Worker Selectors:

```json
[]
["workerId", "$eq", "worker-id"]
["workerId", "$in", ["worker-a", "worker-b"]]
```

Kernel persists normalized target IDs with the TaskItem and dispatches
directly:

```text
Task Dispatch observes claimable Items in order
  -> explicit IDs: observe due HOT scores for those IDs in the Task Group
  -> empty target: observe a bounded due HOT Group pool
  -> enforce one Worker use per dispatch round
  -> exact-hold selected scores
  -> load current minimal Worker descriptor
  -> final exact renewal, Item claim, and Delivery Command publication
```

There is no ON_DEMAND Match Demand, Evidence, resident Matching work, cursor,
or Candidate Cache fallback. Unsupported Selector names, operators or operands
are rejected by Kernel before TaskItem creation.

## Candidate Selection

`WorkerCandidateSelectionPolicy` owns scheduling operations:

- bounded due HOT observation;
- exact initial hold and final cached renewal;
- explicit-target and ANY selection for ON_DEMAND;
- one Worker use per dispatch round;
- current `workerId + workerGroupId + endpointManagerId` loading after
  acquisition.

It does not parse property names or operators. Raw scores remain opaque by
usage: Pacer may retain and submit them to exact Owner operations but cannot
decode, construct, or calculate score coordinates.

## Assignment Closure

After a candidate is selected, `TaskAssignmentDispatcher` preserves:

```text
exact Worker lease renewal
  -> exact TaskItem claim
  -> construct ResultContext and DeliveryCommand
  -> append the Adapter-partitioned Worker mailbox
```

Observation is not claim. If an exact fence fails, no Command is published.
Unused or publication-failed leases recover through lease expiry. Task
Dispatch separately owns Item expiry/exhaustion, failed-result-before-
`FINAL_FAILED`, ordinary Task pacing, and idle close/park. Result routing and
finality remain independent owners.

## Failure Semantics

| Failure | Result |
| --- | --- |
| PRECOMPUTED Demand busy or queue full | offer is skipped; hold expires; a later due round recomputes deficit |
| Matching catalog or Cache failure | pending Group is released; completed Cache writes remain; other holds expire |
| missing or invalid Candidate Rule | Candidate is skipped inside the Demand; Workers remain available to later needs |
| Candidate expiry or stale Worker score | Cache entry is dropped or final exact renewal fails |
| invalid ON_DEMAND Worker Selector | public mutation fails before Kernel TaskItem append |
| Matching runtime unexpected exit | Matching health DOWN; no silent restart or Pacer fallback |
| Server restart | transient Demand is lost; persistent facts, Rules, Cache, and Score keep owner semantics |

No failure path lets Server or Matching make the final assignment.

## Guardrails

- Do not put Rule or Properties maps back into Kernel descriptors, TaskItems,
  Candidate Cache, or Match Demand.
- Do not add a Server-side scheduling fallback when Matching is unavailable.
- Do not let Matching interpret score, consume Cache, renew leases, claim
  Items, or publish Commands.
- Do not add ON_DEMAND matching queues, Evidence, Candidate Cache, or general
  Property operators without a new mechanism design.
- Do not compensate-release unmatched or unaccepted holds.
- Do not infer connection state from score or matching facts.
