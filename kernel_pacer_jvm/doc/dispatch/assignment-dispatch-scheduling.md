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
| `PRECOMPUTED_TASK_RULE` | Matching-owned Task binding to a shared Rule | ordered Group Demand filters a Pacer-held pool | Task-scoped |
| `ON_DEMAND_ITEM_RULE` | Kernel stores selector; Matching interprets property conditions | explicit IDs, indexed identities, then ANY | forbidden |

Task creation still accepts a PRECOMPUTED allocation Rule, which Server stores
in Worker Matching before writing the minimal Kernel Task resource. ON_DEMAND
Item calls instead require a finite `workerSelector` Map. Kernel captures and
stores that expression unchanged; only the empty expression means ANY.

## PRECOMPUTED Flow

```text
Main selects due PRECOMPUTED Tasks
  -> Allocation reads Candidate Cache counts
  -> incomplete Tasks sort by priority then taskId within WorkerGroup
  -> current deficits bound due HOT observation to at most 100 Workers
  -> Pacer exact-holds the observed pool
  -> one ordered TaskRuleMatchDemand is offered for the Group
  -> Matching resolves Task bindings to Rules and reads Worker facts
  -> Matching appends accepted workerId + opaque held score to Candidate buckets
  -> Dispatch consumes a Candidate bucket and exact-confirms before Item claim
```

The Demand contains:

```text
workerGroupId
ordered (taskId, maximumCandidateWorkers) needs, at most 100
ordered workerId -> exact held score map, at most 100
holdUntilMillis
```

The Task maximum is static; current deficits remain Pacer-local and only bound
how many Workers are held. Candidate Cache atomically removes expired entries
and admits no more than each Candidate maximum. Matching follows Pacer Task order
and removes only Cache-accepted Workers from the local pool, so one held Worker
enters at most one Candidate bucket per Demand.

`WorkerMatchQueue` admits Demands through one complete queue contract. The
current Server assembly uses bounded process memory, while Pacer and Matching
depend only on `offer`, `size`, and blocking `consume`. Rejection does not
block and does not release its holds. Missing matches, Cache rejection,
partial failures, and unselected holds recover through lease expiry.

Candidate Cache remains disposable address-oriented state:

```text
taskId -> CandidateWorkerEntry[]
CandidateWorkerEntry = workerId + exact heldWorkerLeaseScore
ZSET score = candidate expiry
```

It contains no Rule, Properties, WorkerGroup, or endpoint. Matching is a
bounded writer through the Cache owner API, not the Cache truth owner.

## ON_DEMAND Flow

The current public selectors include:

```json
{}
{"workerId": ["worker-id"]}
{"workerId": ["worker-a", "worker-b"]}
{"worker.country": {"op": "eq", "values": ["CN"]}}
```

Kernel validates generic structure and explicit-ID mechanics; Matching admits
property conditions. Kernel persists the same selector and dispatches directly:

```text
Task Dispatch observes claimable Items in order
  -> explicit IDs: observe due HOT scores for those IDs in the Task Group
  -> property selector: take identities and touch time through WorkerCandidateIndex
     -> due HOT observation -> initial hold -> current index membership recheck
  -> empty selector: observe a bounded due HOT Group pool
  -> enforce one Worker use per dispatch round
  -> exact-hold selected scores
  -> load current minimal Worker descriptor
  -> final exact confirmation, Item claim, and Delivery Command publication
```

The per-Task per-round input contains at most 100 Items. Equal expressions share
one index call, in first Item occurrence order, taking exactly that condition's
waiting Item count. These disjoint counts already sum to at most 100; a second
equal-share cap would waste capacity for skewed demand (20/70/10 must not become
20/33/10). The budget remains a ceiling: touching surplus identities can give an entire small
country bucket the same time, repeatedly favoring its lexical prefix for sparse work.
Explicit IDs precede indexed selection, which precedes ANY; round Worker dedup
is retained. There is no same-round refill, local cursor, Match Demand or Candidate
Cache for ON_DEMAND. Index failure never falls back to ANY. Unsupported selectors
and disabled index Groups fail Server admission before Item writes.

Initial hold can clear dirty after a concurrent country change. Therefore held
indexed Workers are batch-rechecked by Matching before exact confirmation. Failed
rechecks and unused holds expire naturally, without release compensation. Pacer passes the same selector to both index calls and
does not interpret binding names/parameters, read Properties or encode index scores; facts-write and dirty invalidation
remain separate commits and do not revoke already confirmed execution.

## Named Rule Index Flow

INDEXED_TASK uses the same final assignment closure with a different bounded
identity source. Dispatch groups up to 100 Items by equal selectors, resolves
one immutable Matching Task binding, and requests up to 100 IDs total through
TaskQuery.take. It observes due HOT scores, acquires initial holds, then calls
TaskQuery.retain for current membership before describing and exact-confirming.
Matching owns both query interpretation and the shared Group index; Kernel never
receives Rule IDs, property facts or index coordinates. Query objects last for one
batch and own no lifecycle or cache. Missing bindings and index failure never
fall back to ANY, DSL or Candidate Cache. The old Allocation Producer selects
only PRECOMPUTED Tasks, so INDEXED_TASK never reserves Workers for an async match.

Named Rules constrain empty queries and explicit IDs as well. Unbound finite
Tasks use existing ON_DEMAND semantics, with CLOSE_WHEN_IDLE independently
controlling lifecycle. Grouping and Worker dedup preserve Item order. No same-round
refill, hold compensation, additional queue or persistent query cursor is added.
The Matching command budget is HMGET + EVAL + optional EVAL per Task batch.

## Candidate Selection

`WorkerCandidateSelectionPolicy` owns scheduling operations:

- bounded due HOT observation;
- exact initial hold and final cached confirmation;
- explicit-target, indexed and ANY selection for ON_DEMAND;
- one Worker use per dispatch round;
- current `workerId + workerGroupId + endpointManagerId` loading after
  acquisition.

It does not interpret binding names or parameters. Raw scores remain opaque by
usage: Pacer may retain and submit them to exact Owner operations but cannot
decode, construct, or calculate score coordinates.

## Assignment Closure

After a candidate is selected, `TaskAssignmentDispatcher` preserves:

```text
exact Worker hold confirmation (clean exact score -> execution fence with dirty=1)
  -> exact TaskItem claim
  -> construct ResultContext and DeliveryCommand
  -> append the Adapter-partitioned Worker mailbox
```

Observation is not claim. If an exact fence fails, no Command is published.
Unused or publication-failed leases recover through lease expiry. Task
Dispatch separately owns Item expiry/exhaustion, failed-result-before-
`TERMINAL(tag=5)`, ordinary Task pacing, and idle close/park. Result routing and
finality remain independent owners.

Confirmation must return TRANSITIONED with a new execution fence even when the
initial hold already covers the claim deadline. ResultContext stores that
returned fence. Properties invalidation before confirmation rejects the old
Candidate; after confirmation it preserves the dirty execution score.
PRECOMPUTED, INDEXED_TASK and ON_DEMAND share this closure. Invalid Cache entries can retain
capacity until consumption or expiry; no fan-out or compensation is added.

## Failure Semantics

| Failure | Result |
| --- | --- |
| PRECOMPUTED Demand queue full | offer is skipped; hold expires; a later due round recomputes deficit |
| Matching catalog or Cache failure | consumed Demand is dropped; completed Cache writes remain; other holds expire |
| missing or invalid Rule | Candidate is skipped inside the Demand; Workers remain available to later needs |
| Candidate expiry or stale Worker score | Cache entry is dropped or final exact confirmation fails |
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
