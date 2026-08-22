# Assignment-Dispatch Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Detailed mechanisms:

- [Task Running Activation Pacer](task-running-activation-pacer.md)
- [Task-Worker Allocation Pacer](task-worker-allocation-pacer.md)
- [Task Dispatch Pacer](task-dispatch-pacer.md)
- [Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md)
- [Worker Delivery Dispatch](worker-delivery-dispatch.md)

Process lifecycle is defined by
[Kernel Application Assembly](../kernel-application-assembly.md).

## Core Decision

Assignment-dispatch keeps three independently paced mechanisms:

| Mechanism | Decision | Output |
| --- | --- | --- |
| RUNNING activation | Which ADMISSION Tasks pass Task and System admission policy? | `ADMISSION_VISIBLE -> RUNNING_VISIBLE` transition |
| Worker allocation | Which stable RUNNING Task rules should have candidates prefetched? | Expiring CandidateWorker cache evidence |
| Task dispatch | Does a RUNNING Task dispatch Items, idle-park, or close? | Claimed Items, `DeliveryCommand` values, or exact Task score transition |

The mechanisms have different cadence, cost, and policy inputs. They do not
share a transaction, lock, or assignment lifecycle object. Candidate warming
uses a disposable owner-local hint schedule; it does not reuse Task score as a
second Pacer cursor.

## Worker Allocation Mechanisms

The caller selects one `WorkerAllocationMechanism`. The authoritative rule owner, Worker
acquisition, and candidate-cache matrix is defined by the
[Task Resource Model](../resource-model/task-resource-model.md#worker-allocation).

`ResolvedTaskSchedulingProfile` is a non-persisted derivation of the
Worker-acquisition contract. Idle disposition is the separate persisted
`TaskIdleDisposition`; it does not alter rule owner, cache, warmer or
acquisition strategy.

The profile does not assign priority. `PRECOMPUTED_TASK_RULE` is not above or below
`DIRECT_ITEM_RULE`, and neither type implies RPC, batch, latency, or preemption
semantics. Ordering comes only from explicit Task or candidate-request priority
handled by the corresponding scheduling policy.

## Worker Candidate Contract

```python
WorkerCandidateRequest(
    priority,
    requested_count,
    allocation_rule,
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

Each request owns its count, priority, and complete rule. `CandidateId`
is an opaque call-local correlation key except when the cache warmer explicitly
uses globally unique `taskId`. One Worker may satisfy at most one CandidateId
in one call.

`allocation_rule` is the same constraint DSL used by Task and TaskItem
metadata. For DIRECT, `{}` derives one bounded Worker universe from the
WorkerGroup's due-HOT score query. A non-empty rule derives it only from the
rule's `workerId` condition; without that condition it fails closed. Property
indexes do not propose Worker IDs. Explicit `index.*` fields point-load
projections for bounded candidates while the complete rule is matched. Exact
score lease and complete rematch remain the scheduling truth checks.

## Acquisition Strategies

One `WorkerCandidateAcquirer` owns two isolated paths:

```text
PRECOMPUTED
  consume CandidateWorkerCache by CandidateId
  exact validate/renew the cached Worker lease fences
  rematch the current allocation rule
  return partial or empty on miss/stale/mismatch

DIRECT
  for any empty rule, issue one bounded due-HOT Group query shared by the round
  otherwise require bounded workerId $eq/$equal/$in candidates
  admit at most 100 unique WorkerIds across the round in priority order
  pre-match complete non-empty rules for explicit WorkerIds
  use Group-query scores for empty rules; point-observe explicit matches
  choose at most requestedCount due Workers
  exact lease only the chosen Workers
  rematch the complete allocation rule after lease
  point-load explicit index.* fields only for those WorkerIds
  never read or write CandidateWorkerCache
```

The cache warmer calls `acquire_hot_pool_candidates(...)` explicitly. It is not
a third strategy and does not disguise a HOT scan as DIRECT. Neither strategy
invokes the other; a PRECOMPUTED miss never becomes a DIRECT scan.

The acquirer, request, and acquisition-strategy types are internal to the
`scheduling.worker_candidate` mechanism package. They are not exported through
the executable-spec, scheduling aggregate, assembly, or HTTP Task contract.

Matcher input is one flat Worker lease map for one WorkerGroup:

```text
workerId -> opaqueLeaseScore
```

The selected acquirer deduplicates its bounded Worker source before the matcher
call. PRECOMPUTED consumes Task-local cache entries. DIRECT uses one bounded
Group score query when the round contains empty rules, or the Item rule's
explicit `workerId` condition for a non-empty rule, and never touches the
cache. Across one DIRECT call, `(priority, candidateId)`
ordering admits at most 100 unique WorkerIds. Later candidates may reuse an
already admitted id, but cannot add new ids after the budget is exhausted. The
matcher therefore reads at most 100 descriptors for that DIRECT round,
batches projection reads only for candidates that reference each `index.*`
field, evaluates the complete rule in priority order, and assigns each Worker
to at most one CandidateId.

Matcher context has four roots:

```text
workerId
worker.*
platform.*
index.*
```

For explicit `index.*` fields, the matcher point-loads values for only its
current bounded WorkerIds. Missing or unavailable projections fail that
matching round closed and do not fall back to descriptor Properties.
`worker.*` and `platform.*` fields always read their corresponding descriptor
snapshots. Property projections never discover or intersect candidate sets.

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

The persisted JSON uses exactly these four fields. `endpointManagerId` is the
assignment-time delivery-route snapshot copied from the matched Worker
descriptor. It is not part of the allocation-rule context.

Owner surface:

```text
append_candidate_workers(candidateId, entries, expiresAtMillis)
candidate_worker_counts(candidateIds)
consume_candidate_workers(candidateId, limit)
```

Redis key:

```text
xa_mass:<scope>:dispatch:candidate:<candidateId>:workers
```

The ZSET score is cache expiry and matches the Worker lease deadline. The cache
does not own rules, limits, Worker validity, lifecycle truth, or fallback.
DIRECT Item results never enter this cache.

`CandidateWarmupSchedule` is a separate derived ZSET of `taskId -> dueMillis`.
It is only a cache-replenishment hint. A successful PRECOMPUTED_TASK_RULE activation and
subsequent PRECOMPUTED cache consumption can recreate it; therefore it is not a
Task state, assignment record, or durable liveness truth.

## Round Flows

Allocation cache warming:

```text
due CandidateWarmupSchedule TaskIds
  -> batch-read Task score and retain RUNNING/non-hard-paused suffix-zero Tasks
  -> load descriptors and retain workerAllocationMechanism=PRECOMPUTED_TASK_RULE
  -> requestedCount = maximumCandidateWorkers - cachedCount
  -> acquire_hot_pool_candidates
  -> exact lease and match HOT Workers
  -> append under CandidateId=taskId
  -> requeue incomplete warmups
```

Task dispatch:

```text
dispatch-visible RUNNING suffix-zero Tasks
  -> observe due Item scores and load existing records
  -> TaskItemDispatcher
     -> PRECOMPUTED_TASK_RULE: one TaskId PRECOMPUTED request
     -> DIRECT_ITEM_RULE: one messageId DIRECT request per Item
     -> preserve CandidateId-to-messageId binding
     -> exact claim only Worker-backed Items
     -> encode one DeliveryCommand in each DeliveryCommand
     -> return Worker commands grouped by endpointManagerId
  -> append each group to its Adapter-partitioned sparse mailbox
  -> same-band reschedule while preserving suffix 0

no claimable Item
  -> query the complete ACTIVE Item band
  -> ACTIVE exists: ordinary same-band pacing
  -> no ACTIVE and CLOSE_WHEN_IDLE: exact terminal close
  -> no ACTIVE and PARK_WHEN_IDLE: exact private idle park
  -> after a successful park, recheck ACTIVE once and exact-release the park
     if an append raced with it
```

Assignment Dispatch ends at Adapter mailbox publication. `APPENDED` and
`REPLACED` both mean the caller's handoff is visible when append returns. The
normal path expects replacement to overwrite unconsumed residue, not a
competing active assignment. The mailbox itself does not compare Worker lease
recency, so a delayed stale publisher can still become the last write; deadline
checks, exact result fences, and natural expiry preserve owner truth. Mailbox
consume, execute-before recheck, protocol forwarding, Worker invocation, and
DeliveryReport append belong to Worker Delivery Dispatch.

`workerAllocationMechanism` is fixed by the Task. The two rule locations cannot be mixed, and
the dispatch round does not infer type or strategy from Item contents.
`workerGroupId` always comes from `TaskDescriptor`.

## Owner And Failure Boundaries

- `TaskRunningActivationPacer` is the only assignment mechanism that changes a
  Task band.
- `TaskWorkerAllocationPacer` uses Task score only for bounded
  RUNNING/non-hard-pause validation; it never uses it as a cursor or mutates it.
- Candidate acquisition owns Worker observation, exact lease, and rematch; it
  does not own cache publication.
- `TaskDispatchPacer` owns bounded RUNNING discovery, mailbox publication,
  routine same-band rescheduling, and exact idle close or private park/unpark.
- `TaskItemDispatcher` owns one RUNNING Task's Item observation, candidate
  acquisition, exact Item claim, and DeliveryCommand construction. It has no
  Task score or mailbox-publication authority.
- Neither `TaskDispatchPacer` nor `TaskItemDispatcher` accesses
  CandidateWorkerCache or WorkerScoreCore directly.
- Item observation is not a claim. Exact claim happens only after a Worker is
  bound to that Item.
- Cache miss, missing index rows, stale Worker evidence, missing records, and
  empty match are bounded no-ops.
- Invalid stored rules and unavailable or corrupt Property Index reads fail
  closed. One matcher call emits at most one safe aggregate rule diagnostic
  and one safe aggregate index diagnostic without rule, property, or Item data.
- Unused, stale, claim-failed, or publication-failed Worker leases are not
  actively released; lease expiry restores visibility.
- Candidate cache and DeliveryCommand mailboxes are handoff evidence, not
  assignment or liveness truth.
- Assignment Dispatch routes only by the matched
  `CandidateWorkerEntry.endpointManagerId` snapshot. It never reads connection
  or session state and never exposes this route as a Worker matching field.

## Deferred Policy

- Alternative bounded candidate sources, numeric/range projection stores,
  preference ranking, stronger cardinality planning, quotas, and fairness are
  deferred policies. Point Property Indexes do not discover or intersect
  candidate sets.
- Ordinary TaskItem append does not alter Task scheduling. Reusable RPC and
  Task Batch flows call the bounded Kernel `TaskCallItemSubmission`, which
  invokes the idempotent score-owner idle-park release before and after bounded
  append. It does not interpret allocation or idle-disposition policy.
  Released Tasks use the ordinary due scan; there is no urgent selection path.
- Idle disposition is independent of Worker allocation. External owners may
  still close either profile explicitly.
- One WorkerId remains one scheduler-visible execution slot. Business batch
  work belongs inside one TaskItem payload.

## Guardrails

- Do not collapse activation, cache warming, and Item dispatch into one Pacer.
- Do not turn CandidateWorker cache into the universal dispatch mechanism.
- Do not add PRECOMPUTED-miss DIRECT fallback.
- Do not expose acquisition strategy, cache flags, or rule owner as independent
  Task configuration.
- Do not add a WorkerAllocationMechanism for a parameter variation or an imagined policy
  combination; require a named workload and vertical executable proof.
- Do not merge Task and Item allocation rules implicitly.
- Do not add a cross-CandidateId requested count or cross-WorkerGroup call.
- Do not treat an index result as final matching or Worker availability truth.
- Do not expose scan limits, Redis keys, score fields, or score decoding.
- Do not allocate Workers for ADMISSION Tasks.
- Do not use Task score as the candidate-warmer cursor.
