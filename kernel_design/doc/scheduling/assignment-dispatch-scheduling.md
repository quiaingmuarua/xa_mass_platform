# Assignment-Dispatch Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Detailed mechanisms:

- [Task Initialization Policy](task-initialization-policy.md)
- [Task Worker Allocation Policy](task-worker-allocation-pacer.md)
- [Task Dispatch Policy](task-dispatch-pacer.md)
- [Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md)
- [Worker Delivery Dispatch](worker-delivery-dispatch.md)

Process lifecycle is defined by
[Kernel Application Assembly](../kernel-application-assembly.md).

## Core Decision

Dispatch Convergence derives two projections from one RUNNING Task source and
keeps four fixed single-flight policy lanes:

| Lane | Source | Decision | Output |
| --- | --- | --- | --- |
| Task initialization | INITIAL RUNNING | Which Tasks have a due ACTIVE Item? | exact INITIAL to NORMAL transition |
| Worker allocation | NORMAL RUNNING | Which stable Task rules have Candidate deficits? | Expiring CandidateWorker cache evidence |
| Task dispatch | NORMAL RUNNING | Does a Task dispatch Items, idle-park, or close? | Claimed Items, Commands, or exact Task score transition |
| Worker serviceability | NORMAL RUNNING | Which demanded WorkerGroups need Adapter Route probes? | best-effort Probe Request evidence |

The RUNNING lanes share one immutable `DueTaskObservation` batch whenever they
are simultaneously eligible. They have independent cadence and completion;
there is no transaction or assignment lifecycle object. A busy lane skips the
batch, and Task score remains the only persistent demand surface.

The supported Task command path commits the `TaskDescriptor` before approval
can enter RUNNING INITIAL. INITIAL uses the fixed time slot `100` and the
Owner-derived suffix `99 - priority`; initialization writes the NORMAL time
coordinate with suffix zero.
`TaskSchedulingMechanism` therefore revalidates concurrent observations with
the Redis range-read time and hides the exact Task score in an opaque reference. It
is not a legacy-data repair or migration surface. A Task that changed band,
moved into the future, or disappeared between the range read and the owner
reread is omitted from that batch and remains governed by its current owner
state.

## Worker Allocation Mechanisms

The caller selects one `WorkerAllocationMechanism`. The authoritative rule owner, Worker
acquisition, and candidate-cache matrix is defined by the
[Task Resource Model](../resource-model/task-resource-model.md#worker-allocation).

`ResolvedTaskSchedulingProfile` is a non-persisted derivation of the
Worker-acquisition contract. Idle disposition is the separate persisted
`TaskIdleDisposition`; it does not alter rule owner, cache, allocation or
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
is an opaque call-local correlation key except when allocation explicitly
uses globally unique `taskId`. One Worker may satisfy at most one CandidateId
in one call.

`allocation_rule` is the same constraint DSL used by Task and TaskItem
metadata. For DIRECT, `{}` derives one bounded Worker universe from the
WorkerGroup's due-HOT score query. A non-empty rule derives it only from the
rule's `workerId` condition; without that condition it fails closed. The
complete rule may use only `workerId`, `worker.*`, and `platform.*`, all read
from the bounded canonical Worker descriptor. Exact score lease and complete
post-lease rematch remain the scheduling truth checks.

## Acquisition Strategies

`WorkerCandidateSelectionPolicy` and its package-private
`WorkerCandidateMechanism` preserve two isolated paths. Policy derives and
matches the bounded candidate universe; the internal Mechanism protects cached
opaque lease correlation and exact Worker fencing:

```text
PRECOMPUTED
  consume CandidateWorkerCache by CandidateId
  Mechanism exact-validates/renews the cached Worker lease fences
  reload the canonical descriptor and Policy rematches the current rule
  return partial or empty on miss/stale/mismatch

DIRECT
  for any empty rule, issue one bounded due-HOT Group query shared by the round
  otherwise require bounded workerId $eq/$equal/$in candidates
  fail closed on null, non-string, or empty explicit WorkerId operands
  admit at most 100 unique WorkerIds across the round in priority order
  Policy pre-matches complete non-empty rules for explicit WorkerIds
  Mechanism observes Group-query or explicit point candidates
  Policy chooses at most requestedCount due Workers
  Mechanism exact-leases only the chosen Workers and reloads descriptors
  Policy rematches the complete allocation rule after lease
  never read or write CandidateWorkerCache
```

The allocation policy calls `acquire_hot_pool_candidates(...)` explicitly. It is not
a third strategy and does not disguise a HOT scan as DIRECT. Neither strategy
invokes the other; a PRECOMPUTED miss never becomes a DIRECT scan.

The selection policy, request, and acquisition-strategy types are internal to
Dispatch policy. They are not exported through Kernel Pacer Runtime, assembly,
or the HTTP Task contract.

Matcher input is one bounded semantic observation map for one WorkerGroup:

```text
CandidateId -> WorkerCandidateObservation[]

WorkerCandidateObservation
  workerId
  canonical WorkerDescriptor
  opaque WorkerCandidateReference
```

The selection policy deduplicates its bounded Worker source before the matcher
call. PRECOMPUTED consumes Task-local cache entries. DIRECT uses one bounded
Group score query when the round contains empty rules, or the Item rule's
explicit `workerId` condition for a non-empty rule, and never touches the
cache. Across one DIRECT call, `(priority, candidateId)`
ordering admits at most 100 unique WorkerIds. Later candidates may reuse an
already admitted id, but cannot add new ids after the budget is exhausted. The
matcher therefore reads at most 100 canonical descriptors for that DIRECT
round, evaluates the complete rule in priority order, and assigns each Worker
to at most one CandidateId.

Matcher context has three roots:

```text
workerId
worker.*
platform.*
```

`worker.*` and `platform.*` fields read their corresponding canonical
descriptor snapshots. The removed `index.*` namespace is invalid and cannot
discover or intersect candidate sets. A future acceleration index may only
derive bounded Worker IDs internally; canonical descriptor rematch remains
mandatory.

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

There is no Candidate scheduling index. A PRECOMPUTED Task remains visible to
the shared RUNNING Source until Task Dispatch advances, parks, or closes it.
Allocation recomputes its deficit from Candidate Cache each observed round.

## Round Flows

Allocation:

```text
shared due RUNNING observations
  -> retain workerAllocationMechanism=PRECOMPUTED_TASK_RULE
  -> requestedCount = maximumCandidateWorkers - cachedCount
  -> acquire_hot_pool_candidates
  -> exact lease and match HOT Workers
  -> append under CandidateId=taskId
  -> allow later RUNNING discovery to retry any remaining deficit
```

Task dispatch:

```text
dispatch-visible RUNNING Tasks
  -> TaskExecutionMechanism observes due Item references and records
  -> Policy marks exhausted/expired Items and chooses Item/Worker pairing
  -> WorkerCandidateMechanism exact-validates selected Worker fences and
     reloads canonical descriptors for post-lease rematch
  -> TaskExecutionMechanism exact-claims only Worker-backed Items
  -> TaskExecutionMechanism constructs ResultContext and DeliveryCommand,
     appends Adapter-partitioned sparse mailboxes, and performs Task pacing

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

- `TaskInitializationPolicy` is the only assignment mechanism that promotes a
  RUNNING INITIAL coordinate into the NORMAL scheduling range.
- `TaskWorkerAllocationPolicy` receives validated Task evidence and never reads
  or mutates Task score.
- `TaskWorkerAllocationPolicy` owns deficit and may read bounded Candidate
  counts directly from `CandidateWorkerCache`.
- `WorkerCandidateSelectionPolicy` owns request priority, bounded selection and
  rule matching; it does not access a raw Score.
- Package-private `WorkerCandidateMechanism` protects cached opaque lease
  consume/append, Worker observation, exact lease/renew and canonical
  post-lease evidence; Policy performs the final rematch.
- `TaskDispatchPolicy` owns expiry/exhaustion decisions, pairing, per-Task
  limits and idle-disposition choice.
- `TaskExecutionMechanism` owns Item finality/claim, Command construction and
  publication, Task pacing and exact idle close/park repair.
- Dispatch policies do not access raw Score owners or construct ResultContext
  and DeliveryCommand directly. A Policy may call an already-bounded single
  Owner operation when it owns that decision; such a call does not justify a
  pass-through Mechanism.
- Item observation is not a claim. Exact claim happens only after a Worker is
  bound to that Item.
- Cache miss, stale Worker evidence, missing records, and empty match are
  bounded no-ops.
- Invalid stored rules fail closed. One matcher call emits at most one safe
  aggregate rule diagnostic without rule, property, or Item data.
- Unused, stale, claim-failed, or publication-failed Worker leases are not
  actively released; lease expiry restores visibility.
- Candidate cache and DeliveryCommand mailboxes are handoff evidence, not
  assignment or liveness truth.
- Assignment Dispatch routes only by the matched
  `CandidateWorkerEntry.endpointManagerId` snapshot. It never reads connection
  or session state and never exposes this route as a Worker matching field.

## Deferred Policy

- Alternative bounded candidate sources, derived acceleration indexes,
  preference ranking, stronger cardinality planning, quotas, and fairness are
  deferred policies. Any future index may propose only bounded identities and
  cannot replace canonical descriptor rematch.
- Ordinary TaskItem append does not alter Task scheduling. Managed Task Call
  flows call the bounded Kernel `TaskCallItemSubmission`, which
  invokes the idempotent score-owner idle-park release before and after bounded
  append. It does not interpret allocation or idle-disposition policy.
  Released Tasks use the ordinary due scan; there is no urgent selection path.
- Idle disposition is independent of Worker allocation. External owners may
  still close either profile explicitly.
- One WorkerId remains one scheduler-visible execution slot. Business batch
  work belongs inside one TaskItem payload.

## Guardrails

- Do not collapse the fixed policy lanes into one Task mutation procedure.
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
- Do not allocate Workers for RUNNING INITIAL Tasks.
- Do not introduce a second Candidate-demand cursor beside due RUNNING Task score.
