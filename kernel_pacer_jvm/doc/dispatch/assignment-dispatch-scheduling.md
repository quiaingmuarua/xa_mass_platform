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
| Worker allocation | PRECOMPUTED NORMAL Tasks | Is Candidate Cache below its target? | Match Demand or leased Candidate entries |
| Task dispatch | NORMAL Tasks | Which Item may claim which leased Worker? | Delivery Commands and Task pacing |
| Worker serviceability | demanded WorkerGroups | Which Routes need a Probe? | best-effort serviceability evidence |

Kernel remains the only scheduling authority. Worker Matching interprets
Properties and Rules but cannot rank, lease, claim or dispatch.

## Match Demand And Evidence

`WorkerMatchRuntime` is an identity-only bounded handoff:

```text
Kernel Pacer
  -> exact-hold a bounded due HOT Worker pool
  -> offer Task or Item Match Demand for successfully held Worker IDs
Worker Matching
  -> load persistent Rule and facts for the supplied pool
  -> evaluate constraints
  -> publish short-lived WorkerId Evidence
Kernel Pacer
  -> take Evidence once
  -> confirm the exact active hold
  -> apply priority and round uniqueness
  -> load minimal delivery descriptor
```

Demand and Evidence contain no Rule, Properties, Score, Endpoint or Delivery
DTO. One call and one batch contain at most 100 entries; one Evidence contains
at most 100 Worker IDs. Offer is non-blocking. Capacity or expired/missing
Evidence is a bounded no-op and a later Pacer round may publish again.

Evidence is not availability or a scheduling decision. It reflects facts
observed for a Kernel-supplied held pool before the hold deadline. Kernel
always confirms the exact clean HOT hold before selection. This cut
deliberately does not add a facts revision or post-hold Properties rematch.

## Allocation Mechanisms

`WorkerAllocationMechanism` chooses one fixed workflow:

| Mechanism | Rule owner | Matching source | Candidate Cache |
| --- | --- | --- | --- |
| `PRECOMPUTED_TASK_RULE` | Matching Task Rule keyed by taskId | Kernel-supplied held Worker IDs | used |
| `ON_DEMAND_ITEM_RULE` | Matching Item Rule keyed by taskId + messageId | Kernel-supplied held Worker IDs | forbidden |

The Task or Item API still accepts an allocation rule. Server stores it in
Worker Matching before writing the corresponding minimal Kernel resource.
Kernel descriptors and items never carry the rule.

## PRECOMPUTED Flow

```text
Main selects due PRECOMPUTED Tasks
  -> Allocation observes Candidate Cache counts
  -> consumes available Task Evidence
  -> Kernel confirms active holds and applies priority/count/unique selection
  -> Kernel loads minimal Worker delivery descriptors
  -> selected exact holds enter Candidate Cache
  -> remaining deficits hold a Worker pool and publish Task Match Demands
```

A Task Demand contains the current bounded due HOT Worker IDs for its fixed
WorkerGroup and their shared hold deadline. Matching filters only that supplied
set against the persistent Task Rule and canonical Worker/Platform facts.

Candidate Cache remains disposable Task-oriented lease evidence:

```text
CandidateId=taskId -> CandidateWorkerEntry[]
CandidateWorkerEntry = workerId + workerGroupId + exact workerLeaseScore
```

It contains no Rule, Properties or Endpoint.

## ON_DEMAND Flow

```text
Task Dispatch observes claimable Items
  -> consume available Item Evidence
  -> Kernel confirms active holds and applies priority/round uniqueness
  -> exact claim and Delivery Command publication
  -> unassigned Items hold a Worker pool and publish Item Match Demands
```

Matching evaluates only the bounded Worker IDs supplied in each Item Demand.
It does not scan a WorkerGroup or retain an Item cursor. Demand and Evidence
disappear on Server restart; persistent Rules and facts do not.

## Candidate Selection

`WorkerCandidateSelectionPolicy` owns only scheduling operations:

- observe a bounded due HOT identity pool and exact-hold it before Demand
  publication;
- order Candidate requests by priority and stable candidate identity;
- enforce one Worker per Candidate assignment and one use per Pacer round;
- confirm the exact hold for matched evidence and renew cached Worker leases;
- load minimal `workerId + workerGroupId + endpointManagerId` descriptors
  after lease success.

It does not parse property names or operators. Worker Matching does not read
scores, priority, Candidate Cache, endpoints or TaskItem state.

Cached renewal consumes Candidate Cache, exact-renews its score fence and loads
the current endpoint. A miss never falls back to ON_DEMAND matching.

## Assignment Closure

After a Worker is leased, `TaskAssignmentDispatcher` preserves:

```text
exact Worker lease renewal
  -> exact TaskItem claim
  -> construct ResultContext and DeliveryCommand
  -> append the Adapter-partitioned Worker mailbox
```

Observation is not claim. If an exact fence fails, no Command is published.
Unused or publication-failed leases recover through lease expiry.

Task Dispatch separately owns Item expiry/exhaustion, failed-result storage
before `FINAL_FAILED`, ordinary Task pacing and idle close/park. Result
routing and finality remain independent owners.

## Failure Semantics

| Failure | Result |
| --- | --- |
| Demand queue capacity | skip this offer; later due round retries |
| Matching catalog failure | pending ownership released; no Evidence |
| missing or invalid Rule | empty Evidence plus safe diagnostic |
| expired Evidence | discarded |
| stale or unavailable Worker score | exact hold confirmation fails |
| Matching runtime unexpected exit | Matching health DOWN; no silent restart |
| Server restart | transient Demand/Evidence lost; Pacer republishes |

No failure path lets Server or Matching choose a Worker.

## Guardrails

- Do not put Rules or Properties back into Kernel descriptors, TaskItems,
  Candidate Cache or Demand/Evidence.
- Do not add a Server-side scheduling fallback when Matching is unavailable.
- Do not turn Evidence into availability, ACK, cache truth or a final
  scheduling decision.
- Do not add generic matching SPI, event bus or dynamic Producer registry.
- Do not infer connection state from score or matching facts.
- Do not add cached-to-on-demand fallback or mix Task and Item rule workflows.
