# Assignment-Dispatch Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Detailed mechanisms:

- [Task Running Activation Pacer](task-running-activation-pacer.md)
- [Task-Worker Allocation Pacer](task-worker-allocation-pacer.md)
- [TaskItem Dispatch Pacer](task-item-dispatch-pacer.md)
- [Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md)
- [DeliverSeed Outbound Delivery](deliver-seed-outbound-delivery.md)

Process lifecycle is defined by
[Kernel Application Assembly](../kernel-application-assembly.md).

## Core Decision

Assignment-dispatch keeps three independently paced mechanisms:

| Mechanism | Decision | Output |
| --- | --- | --- |
| RUNNING activation | Which PRE_DISPATCH Tasks pass Task and System admission policy? | Declared `PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE` transition |
| Worker allocation | Which stable RUNNING Task requests should have candidates prefetched? | Expiring candidate cache evidence |
| TaskItem dispatch | Which observed Items and acquired Workers become assigned delivery? | Claimed Items and `DeliverSeed` values |

The mechanisms have different cadence, cost, and policy inputs. They do not
share a transaction, lock, or assignment lifecycle object.

## Worker Candidate Contract

TaskItem dispatch depends only on:

```python
WorkerCandidateRequest(
    priority,
    requested_count,
    match_rules,
)

acquire_worker_candidates(
    strategy: WorkerCandidateAcquisitionStrategy,
    worker_group_id: WorkerGroupId,
    candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
    lease_until_millis,
) -> WorkerCandidateAcquisition
```

There is no Task id, global requested count, fallback flag, or score coordinate
in this contract. The explicit strategy is one of the system-owned `CACHED` or
`REALTIME` names. One call is scoped to exactly one WorkerGroup and therefore
one Worker score queue; cross-group acquisition is a caller-owned grouping
operation. Each request owns its own bounded count. One Worker resource may
satisfy at most one CandidateId in one call.

Alternative rules for one demand are normalized into one request by the
calling policy. Multiple requests mean independent demands; the acquirer does
not interpret OR, fallback, or aggregate budgets between them.

One `WorkerCandidateAcquirer` owns two deliberately isolated internal paths:

```text
CACHED
  consume only CandidateWorkerCache
  batch exact validate/renew Worker leases for the explicit WorkerGroup
  rematch current rules
  return partial/empty on cache miss or stale evidence

REALTIME
  bounded due HOT scan for the explicit WorkerGroup
  exact lease observed Workers
  match lease successes
  never read CandidateWorkerCache
```

Neither path calls the other. Cache miss never changes the explicit strategy.
TaskItem dispatch receives only a strategy name from its resolver; callers do
not construct or select acquirer implementations.

## Candidate Cache

`CandidateWorkerCache` is disposable, expiring evidence used only by the cache
acquirer and the allocation cache warmer:

```text
CandidateId -> CandidateWorkerEntry[]

CandidateWorkerEntry
  workerId
  workerGroupId
  endpointManagerId
  workerLeaseScore       # opaque exact fence
```

Owner surface:

```text
append_candidate_workers(candidateId, entries, expiresAtMillis)
candidate_worker_counts(candidateIds)
consume_candidate_workers(candidateId, limit)
```

Redis representation:

```text
ad:{prefix}:candidate:{candidateId}:workers
```

The ZSET score is cache expiry. Built-in allocation uses the Worker lease
deadline as the same expiry. Append/count/consume remove expired rows
opportunistically; there is no scanner.

The cache does not own request rules, limits, Worker validity, capacity, or
lifecycle truth. `CandidateId` is opaque. The current stable Task policy uses
globally unique `taskId` as its cache id.

## Round Flows

Allocation cache warming:

```text
due RUNNING_VISIBLE Tasks
  -> load stable Task-level rules
  -> requestedCount = maximumCandidateWorkers - cachedCount
  -> WorkerCandidateAcquirer(strategy=REALTIME)
  -> append results under CandidateId = taskId
  -> rotate every considered RUNNING Task within its band
```

TaskItem dispatch:

```text
dispatch-visible RUNNING Tasks
  -> load descriptors
  -> observe due Item scores and load existing Item records
  -> build bounded candidate requests
  -> resolver selects one acquisition strategy name
  -> acquire Worker candidates
  -> exact claim only the corresponding number of Items
  -> stable Worker/Item pairing
  -> append DeliverSeeds by endpointManagerId
```

The built-in policy creates one request per Task:

```text
CandidateId = taskId
requestedCount = record-backed claimable Item count
```

Future Item-directed RPC policy may create multiple Item-level CandidateIds and
select realtime acquisition without changing the contract. That policy and its
Item DSL are not implemented yet.

## Owner And Failure Boundaries

- `TaskRunningActivationPacer` is the only assignment mechanism that changes a
  Task band.
- `TaskWorkerAllocationPacer` may same-band rotate considered RUNNING Tasks.
- Candidate acquirers invoke only declared Worker score lease primitives; score
  encoding remains opaque.
- `TaskItemDispatchPacer` does not access CandidateWorkerCache or WorkerScoreCore
  directly and never rewrites Task score.
- Item observation is not a claim. Exact claim happens only after candidate
  acquisition.
- Cache miss, stale Worker evidence, missing descriptor/Item, and empty match
  are bounded no-ops.
- Unmatched, unused, stale, claim-failed, or publication-failed Worker leases
  are not actively released; lease expiry restores visibility.
- Candidate cache and DeliverSeed queues are handoff evidence, not assignment
  or liveness truth.

## Deferred Policy

- Zero-config assembly selects cached acquisition for the current stable
  Task-level rule.
- Item-directed request construction, RPC mode selection, quotas, fairness,
  and resource estimates remain policy work.
- Worker scan limit is an internal realtime-acquirer bound, not an acquisition
  call parameter.
- One WorkerId remains one scheduler-visible execution slot. Business batch
  work belongs inside one TaskItem payload.

## Guardrails

- Do not collapse activation, cache warming, and Item dispatch into one Pacer.
- Do not turn CandidateWorker cache into the universal dispatch mechanism.
- Do not add cache-miss realtime fallback.
- Do not add a global requested count across CandidateIds.
- Do not let an acquirer interpret OR relationships between requests.
- Do not expose cache keys, scan limits, score fields, or lease decoding through
  the acquisition contract.
- Do not let TaskItem dispatch call Worker score or candidate cache directly.
- Do not allocate Workers for PRE_DISPATCH Tasks.
- Do not preserve the removed broad candidate-runtime path as an alias.
