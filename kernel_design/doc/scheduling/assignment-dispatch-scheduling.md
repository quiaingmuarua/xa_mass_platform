# Assignment-Dispatch Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Detailed rounds:

- [Task-Worker Allocation Pacer](task-worker-allocation-pacer.md)
- [Task Item Dispatch Pacer](task-item-dispatch-pacer.md)
- [DeliverSeed Outbound Delivery](deliver-seed-outbound-delivery.md), which
  begins after the assignment-dispatch cutpoint

Process lifecycle is defined by
[Kernel Application Assembly](../kernel-application-assembly.md).

## Core Decision

Assignment-dispatch is a bounded scheduling coordination plane. It joins owner
evidence but does not become a Task, Worker, Item, transport, or result owner.

Two independently paced mechanisms are mandatory:

| Pacer | Input | Cutpoint | Score authority |
| --- | --- | --- | --- |
| Task-Worker allocation | Due active Tasks, Task constraints, due Worker observations | Expiring `CandidateWorkerEntry` values in one Task-local queue | Exact Worker lease plus current same-band Task time rotation |
| TaskItem dispatch | RUNNING Task ids, consumed candidate entries, due Item observations and TaskItem records | `DeliverSeed` append to one endpoint-manager queue | TaskItem observed claim only; Task and Worker scores are read-only/opaque |

`TaskRunningActivationPacer` is the independent lifecycle classification between
them. It evaluates activation owner facts and may request the declared
`PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE` transition. It is not folded into
allocation publication or Item dispatch.

The mechanisms remain separate because their cadence, cost, batch dimensions,
failure behavior, and policy extension points differ. Existing candidate
evidence must remain dispatchable while a later allocation round is slow or
fails.

## Owner Boundary

Assignment-dispatch does not own:

```text
Task lifecycle, descriptor, or score truth
Worker resource, reachability, capacity, or score truth
TaskItem record, score, retry, or finality truth
transport sessions or endpoint reachability
result classification or projection
```

It may invoke narrow owner primitives and retain only bounded handoff evidence.
It must not mirror descriptors, decode opaque score fences, or invent a shared
assignment lifecycle.

## Score Authority

```text
TaskWorkerAllocationPacer
  reads due PRE_DISPATCH_VISIBLE / RUNNING_VISIBLE Task ids
  exact-leases unchanged due HOT Workers
  publishes matched Worker evidence
  rotates each considered Task within its current band for fairness

TaskRunningActivationPacer
  reads due PRE_DISPATCH_VISIBLE Task ids and candidate evidence
  asks TaskScoreBandCore for PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE

TaskItemDispatchPacer
  discovers RUNNING_VISIBLE Task ids
  consumes candidate evidence
  claims observed TaskItem scores
  never rewrites Task score or reads/mutates Worker score
```

Task/Worker metadata updates and TaskItem append remain independent owner
writes. They do not acquire a pacer lock and do not refresh Task score.

Explicit lifecycle commands may win over an already-started scheduling round.
A stale owner primitive rejects an invalid rewrite; no cross-owner Task lock or
rollback protocol is introduced.

## Inter-Pacer Protocol

`AssignmentDispatchRuntime` owns one expiring candidate collection per Task:

```text
taskId -> CandidateWorkerEntry[]

CandidateWorkerEntry
  workerId
  workerGroupId
  endpointManagerId
  workerLeaseScore     # opaque exact-release fence
```

Its owner surface is:

```text
append_candidate_workers(taskId, entries, expiresAtMillis)
candidate_worker_counts(taskIds)
consume_candidate_workers(taskId, limit)
```

Current Redis representation uses one ZSET per Task:

```text
ad:{prefix}:task:{taskId}:candidate-workers
```

The batch expiry is the ZSET score. It must not be later than the Worker lease
deadline; the built-in policy uses equality. Expired entries are removed on
bounded append/count/consume operations, not by a background scanner.

The runtime stores every supplied match. It does not own Task candidate limits,
trim entries, validate Worker state, or promise cross-Task atomicity.
`candidate_worker_counts` reports current non-expired evidence only; it is not
proof that Worker metadata, dirty state, lease, or constraints are still valid.

## Publication And Consumption

Allocation follows this owner order:

```text
bounded Worker observation
  -> exact observed-score leases
  -> match only lease successes
  -> append matched CandidateWorkerEntries
  -> rotate considered Tasks within their current score band
```

Every successful Worker lease is consumed scheduling evidence. Unmatched
Workers and publication failures are not actively released; bounded lease
expiry restores visibility. A stale Task rotation does not roll back already
published candidate evidence.

Activation is a separate bounded round:

```text
due PRE_DISPATCH_VISIBLE Task
  -> activation policy(candidate count, Task descriptor)
  -> declared RUNNING_VISIBLE transition when satisfied
```

Item dispatch follows this owner order:

```text
RUNNING_VISIBLE Task
  -> atomic bounded candidate consume
  -> bounded TaskItem observation/load/claim
  -> pair successful claims with consumed candidates
  -> append DeliverSeeds grouped by endpointManagerId
```

Candidate consume and DeliverSeed append are separate runtime operations. A
consumed candidate is not restored after a missing/stale Item or queue failure;
Worker and Item score deadlines provide bounded recovery.

## DeliverSeed Cutpoint

`DeliverSeedRuntime` owns endpoint-manager-partitioned append/consume queues:

```text
ad:{prefix}:endpoint-manager:{endpointManagerId}:deliver-seeds
```

Assignment-dispatch appends opaque already-assigned evidence. It does not call
transport. Queue consumption, expiry-before-submit handling, Worker-local
resolution, handler invocation, and SeedResult append belong to the outbound
boundary.

The DeliverSeed carries only:

```text
workerId
opaqueDeliveryItem
opaqueResultContext
taskItemClaimUntilMillis
```

`workerGroupId` and `endpointManagerId` have completed their scheduling and
queue-partition roles and are not duplicated in the seed.

## Cross-Pacer Liveness

Neither pacer requires an event from the other:

```text
allocation slower or failed
  existing candidate queues remain consumable; later bounded rounds retry

dispatch faster than allocation
  empty candidate consume is a bounded no-op

candidate publication lost
  Worker leases expire; a later allocation round may rebuild evidence

Task pauses or closes after discovery
  the started bounded round may finish; later scans exclude the Task

Item claim or DeliverSeed append is lost
  Item claim and Worker lease deadlines restore scheduling visibility
```

Candidate queues and DeliverSeed queues are disposable handoffs, not a second
source of liveness or lifecycle truth.

## Deferred Policy

- Allocation currently prioritizes `RUNNING_VISIBLE` before
  `PRE_DISPATCH_VISIBLE`; quotas or weighting are deployment policy, not a
  candidate-runtime feature.
- Dispatch intends to prefer recently allocated RUNNING Tasks; the current
  Redis Task acquisition remains oldest-first.
- Candidate target, scan limits, lease duration, activation condition, and
  per-Task dispatch limit are bounded policy values owned by their pacer
  configs or Task descriptor.
- Strong persisted assignment continuation would require an explicit owner
  protocol; candidate queues must not be promoted into that role.

## Guardrails

- Do not collapse allocation, activation, and Item dispatch into one round.
- Do not let allocation decode Task score or change Task band/suffix.
- Do not let Item dispatch rewrite Task score or inspect/renew/release Worker
  score.
- Do not publish a candidate before its exact Worker lease succeeds.
- Do not release unmatched or failed-publication Worker leases as if the
  scheduling attempt had not occurred.
- Do not put Task or Worker resource updates behind a pacer.
- Do not make candidate count a lifecycle, capacity, or current-validity fact.
- Do not add cross-Task atomic append/consume or a score/candidate commit
  protocol.
- Do not let transport select a Worker or assignment-dispatch classify results.
- Do not retain candidate or DeliverSeed queues as durable assignment truth.
