# Assignment-Dispatch Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Detailed mechanisms:

- [Task Running Activation Pacer](task-running-activation-pacer.md)
- [Task-Worker Allocation Pacer](task-worker-allocation-pacer.md)
- [Task Dispatch Pacer](task-dispatch-pacer.md)
- [Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md)
- [DeliverSeed Outbound Delivery](deliver-seed-outbound-delivery.md)

Process lifecycle is defined by
[Kernel Application Assembly](../kernel-application-assembly.md).

## Core Decision

Assignment-dispatch keeps three independently paced mechanisms:

| Mechanism | Decision | Output |
| --- | --- | --- |
| RUNNING activation | Which PRE_DISPATCH Tasks pass Task and System admission policy? | `PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE` transition |
| Worker allocation | Which stable RUNNING Task rules should have candidates prefetched? | Expiring CandidateWorker cache evidence |
| Task dispatch | Does a RUNNING Task dispatch Items or advance empty recheck? | Claimed Items, `DeliverSeed` values, or Task empty-count transition |

The mechanisms have different cadence, cost, and policy inputs. They do not
share a transaction, lock, or assignment lifecycle object. Candidate warming
uses a disposable owner-local hint schedule; it does not reuse Task score as a
second Pacer cursor.

## Task Type Profiles

The caller selects one `TaskType`. The authoritative rule owner, Worker
acquisition, candidate-cache, and empty-behavior matrix is defined by the
[Task Resource Model](../resource-model/task-resource-model.md#task-type-and-allocation-rule).

`ResolvedTaskSchedulingProfile` is a non-persisted derivation of the
Worker-acquisition portion of that contract. Empty behavior is enforced by
Task dispatch from the same immutable `TaskType`. Neither is an external
policy registry, and callers cannot independently select cache, warmer,
rule-owner, acquisition, or empty-behavior flags.

## Worker Candidate Contract

```python
WorkerCandidateRequest(
    priority,
    requested_count,
    allocation_rule,
    target_field,
)

acquire_worker_candidates(
    strategy: WorkerCandidateAcquisitionStrategy,
    worker_group_id: WorkerGroupId,
    candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
    lease_until_millis,
) -> WorkerCandidateAcquisition
```

One call is scoped to one WorkerGroup and one Worker score queue. There is no
Task id, cross-group batch, global requested count, fallback flag, score
coordinate, or cache key in the contract.

Each request owns its count, priority, rule, and candidate source. `CandidateId`
is an opaque call-local correlation key except when the cache warmer explicitly
uses globally unique `taskId`. One Worker may satisfy at most one CandidateId
in one call.

`allocation_rule` is the same constraint DSL used by Task and TaskItem
metadata. `target_field` is an internal candidate-source coordinate:

```text
None
  only valid for PRECOMPUTED consumption requests and the dedicated
  acquire_hot_pool_candidates cache-warmer operation

workerId
  bounded $eq / $in point source

dynamic.<name>
  bounded handler-owned candidate index
```

The target source only proposes Worker IDs. Descriptor reads, dynamic point
reads, full rule matching, and exact Worker score leases remain the scheduling
truth checks.

## Acquisition Strategies

One `WorkerCandidateAcquirer` owns two isolated paths:

```text
PRECOMPUTED
  consume CandidateWorkerCache by CandidateId
  exact validate/renew the cached Worker lease fences
  rematch the current allocation rule
  return partial or empty on miss/stale/mismatch

TARGETED
  require a non-null target_field and obtain Worker IDs from that source
  observe only due HOT scores for point sources
  exact lease the observed scores
  rematch the complete allocation rule
  never read or write CandidateWorkerCache
```

The cache warmer calls `acquire_hot_pool_candidates(...)` explicitly. It is not
a third strategy and does not disguise a HOT scan as TARGETED. Neither strategy
invokes the other; a PRECOMPUTED miss never becomes a TARGETED scan.

The acquirer, request, and acquisition-strategy types are internal to the
`scheduling.worker_candidate` mechanism package. They are not exported through
the executable-spec, scheduling aggregate, assembly, or HTTP Task contract.

Matcher input is one flat Worker lease map for one WorkerGroup:

```text
workerId -> opaqueLeaseScore
```

The selected acquirer unions and deduplicates its bounded Worker sources before
the matcher call. The matcher batches descriptor and dynamic point reads,
evaluates each complete candidate rule in priority order, and assigns each
Worker to at most one CandidateId. CandidateId remains a constraint and output
correlation key; source topology is not part of the matcher contract.

## Candidate Cache

`CandidateWorkerCache` is disposable Task-oriented prefetch evidence:

```text
CandidateId -> CandidateWorkerEntry[]

CandidateWorkerEntry
  workerId
  workerGroupId
  endpointManagerId
  workerLeaseScore
```

Owner surface:

```text
append_candidate_workers(candidateId, entries, expiresAtMillis)
candidate_worker_counts(candidateIds)
consume_candidate_workers(candidateId, limit)
```

Redis key:

```text
ad:{prefix}:candidate:{candidateId}:workers
```

The ZSET score is cache expiry and matches the Worker lease deadline. The cache
does not own rules, limits, Worker validity, lifecycle truth, or fallback.
TARGETED Item results never enter this cache.

`CandidateWarmupSchedule` is a separate derived ZSET of `taskId -> dueMillis`.
It is only a cache-replenishment hint. A successful TASK_DRIVEN activation and
subsequent PRECOMPUTED cache consumption can recreate it; therefore it is not a
Task state, assignment record, or durable liveness truth.

## Round Flows

Allocation cache warming:

```text
due CandidateWarmupSchedule TaskIds
  -> batch-read Task score and retain RUNNING/non-hard-paused Tasks
  -> load descriptors and retain taskType=TASK_DRIVEN
  -> requestedCount = maximumCandidateWorkers - cachedCount
  -> acquire_hot_pool_candidates
  -> exact lease and match HOT Workers
  -> append under CandidateId=taskId
  -> requeue incomplete warmups
```

Task dispatch:

```text
dispatch-visible RUNNING Tasks
  -> suffix 0: observe due Item scores and load existing records
  -> TASK_DRIVEN: one TaskId PRECOMPUTED request
  -> ITEM_DRIVEN: one messageId TARGETED request per Item
  -> preserve CandidateId-to-messageId binding
  -> exact claim only Worker-backed Items
  -> append DeliverSeeds by endpointManagerId
  -> same-band reschedule while preserving suffix 0

empty-recheck RUNNING Tasks
  -> suffix > 0, or suffix 0 with no dispatchable Item
  -> query the complete ACTIVE Item band
  -> ACTIVE exists: exact reset suffix to 0
  -> no ACTIVE: increment empty count and apply linear delay
  -> at max: TASK_DRIVEN closes; ITEM_DRIVEN remains low-frequency RUNNING
```

`taskType` is fixed by the Task. The two rule locations cannot be mixed, and
the dispatch round does not infer type or strategy from Item contents.
`workerGroupId` always comes from `TaskDescriptor`.

## Owner And Failure Boundaries

- `TaskRunningActivationPacer` is the only assignment mechanism that changes a
  Task band.
- `TaskWorkerAllocationPacer` uses Task score only for bounded
  RUNNING/non-hard-pause validation; it never uses it as a cursor or mutates it.
- Candidate acquisition owns Worker observation, exact lease, and rematch; it
  does not own cache publication.
- `TaskDispatchPacer` does not access CandidateWorkerCache or
  WorkerScoreCore directly. It alone owns routine RUNNING same-band dispatch
  rescheduling and exact empty-count changes.
- Item observation is not a claim. Exact claim happens only after a Worker is
  bound to that Item.
- Cache miss, missing index rows, stale Worker evidence, missing records, and
  empty match are bounded no-ops.
- Unused, stale, claim-failed, or publication-failed Worker leases are not
  actively released; lease expiry restores visibility.
- Candidate cache and DeliverSeed queues are handoff evidence, not assignment
  or liveness truth.

## Deferred Policy

- Dynamic candidate indexes remain handler-owned; zero-config assembly installs
  none.
- Multi-index intersection, cardinality optimization, quotas, and fairness are
  deferred policies.
- Append-trigger acceleration remains deferred. Periodic RUNNING scans are the
  correctness fallback.
- TASK_DRIVEN empty auto-close and ITEM_DRIVEN persistent empty recheck are the
  current built-in empty-state behaviors. ITEM_DRIVEN close requires an
  external owner to submit business evidence through the explicit close
  command; deadline policy remains deferred.
- One WorkerId remains one scheduler-visible execution slot. Business batch
  work belongs inside one TaskItem payload.

## Guardrails

- Do not collapse activation, cache warming, and Item dispatch into one Pacer.
- Do not turn CandidateWorker cache into the universal dispatch mechanism.
- Do not add PRECOMPUTED-miss TARGETED fallback.
- Do not expose acquisition strategy, cache flags, or rule owner as independent
  Task configuration.
- Do not add a TaskType for a parameter variation or an imagined policy
  combination; require a named workload and vertical executable proof.
- Do not merge Task and Item allocation rules implicitly.
- Do not add a cross-CandidateId requested count or cross-WorkerGroup call.
- Do not treat an index result as final matching or Worker availability truth.
- Do not expose scan limits, Redis keys, score fields, or score decoding.
- Do not allocate Workers for PRE_DISPATCH Tasks.
- Do not use Task score as the candidate-warmer cursor.
