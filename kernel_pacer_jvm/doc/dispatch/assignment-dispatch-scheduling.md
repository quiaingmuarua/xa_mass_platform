# Assignment-Dispatch Scheduling

Status: active Java Kernel Dispatch Convergence contract; policy coverage is
explicit below.

Detailed mechanisms:

- [Task Initialization](task-initialization-policy.md)
- [Task Worker Allocation Policy](task-worker-allocation-pacer.md)
- [Task Dispatch Policy](task-dispatch-pacer.md)
- [Worker HOT_ACQUIRE Lease Protocol](../../../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md)
- [Worker Delivery Dispatch](../../../doc/kernel/worker-delivery-dispatch.md)

Process lifecycle is defined by
[Kernel Application Assembly](../application-assembly.md).

## Core Decision

Dispatch Convergence has one Main Scheduler that derives four fixed root-input
sets from one RUNNING Task source and starts four single-flight Resource
Producers:

| Producer | Main-planned root input | Decision | Output |
| --- | --- | --- | --- |
| Task initialization | INITIAL RUNNING | Which Tasks have a due ACTIVE Item? | exact INITIAL to NORMAL transition |
| Worker allocation | NORMAL RUNNING | Which stable Task rules have Candidate deficits? | Expiring CandidateWorker cache evidence |
| Task dispatch | NORMAL RUNNING | Does a Task dispatch Items, idle-park, or close? | Claimed Items, Commands, or exact Task score transition |
| Worker serviceability | NORMAL RUNNING | Which demanded WorkerGroups need Adapter Route probes? | best-effort Probe Request evidence |

One descending Redis scan supplies an ordered `taskId -> opaque score` map.
The Score Owner filters its INITIAL subset; the remaining NORMAL identities
are loaded with Descriptors once. The Main Scheduler sends INITIAL directly to
Initialization, `PRECOMPUTED_TASK_RULE` NORMAL Tasks to Allocation, all valid NORMAL Tasks
to Task Dispatch, and first-occurrence ordered WorkerGroup IDs to
Serviceability. Producers have independent cadence and completion; there is no
transaction or assignment lifecycle object. A busy Producer skips the source
snapshot, and Task score remains the only persistent demand surface.

The supported Task command path commits the `TaskDescriptor` before approval
can enter RUNNING INITIAL. INITIAL uses the fixed time slot `100` and the
Owner-derived suffix `99 - priority`; initialization writes the NORMAL time
coordinate with suffix zero.
The Main Scheduler does not decode Score. INITIAL classification belongs to the
Task Score Owner, and NORMAL scores are only wrapped for exact downstream
transitions. There is no second Task Score read. Concurrent changes after the
scan are rejected by exact Owner operations rather than repaired.

## Worker Allocation Mechanisms

The caller selects one `WorkerAllocationMechanism`. The authoritative rule owner, Worker
acquisition, and candidate-cache matrix is defined by the
[Task Resource Model](../../../kernel_jvm/doc/resource-model/task-resource-model.md#worker-allocation).

`ResolvedTaskSchedulingProfile` is a non-persisted derivation of the
Worker-acquisition contract. Idle disposition is the separate persisted
`TaskIdleDisposition`; it does not alter rule owner, cache, allocation or
candidate source.

The profile does not assign priority. `PRECOMPUTED_TASK_RULE` is not above or below
`ON_DEMAND_ITEM_RULE`, and neither type implies RPC, batch, latency, or preemption
semantics. Ordering comes only from explicit Task or candidate-request priority
handled by the corresponding scheduling policy.

## Worker Candidate Contract

```python
WorkerCandidateRequest(
    priority,
    requested_count,
    allocation_rule,
)

acquire_shared_hot_candidates(
    worker_group_id,
    candidate_requests,
    lease_until_millis,
)

renew_cached_candidates(
    worker_group_id,
    candidate_requests,
    lease_until_millis,
)

acquire_on_demand_candidates(
    worker_group_id: WorkerGroupId,
    candidate_requests: Mapping[CandidateId, WorkerCandidateRequest],
    lease_until_millis,
)
```

One call is scoped to one WorkerGroup and one Worker score queue. Task creation
has already fixed that outer WorkerGroup boundary. Candidate sources may only
produce bounded Worker IDs inside it; a rule narrows that source and never
discovers another Group. There is no cross-group batch, fallback flag, score
coordinate, or caller-selected cache key in the contract.

Each request owns its count, priority, and complete rule. `CandidateId`
is an opaque call-local correlation key except when allocation explicitly
uses globally unique `taskId`. One Worker may satisfy at most one CandidateId
in one call.

`allocation_rule` is the same constraint DSL used by Task and TaskItem
metadata. `WorkerCandidateMatcher.prepare` normalizes every Candidate rule once
into one call-local Match Plan. For item-rule on-demand acquisition, Matcher
places `{}` in the shared HOT source and derives bounded explicit Worker IDs
only from `workerId`
`$eq`/`$equal`/`$in`; other non-empty rules have no current source and fail
closed. The complete rule
may use only `workerId`, `worker.*`, and `platform.*`, all read from the bounded
canonical Worker descriptor. Exact score lease and Match-Plan-based post-lease
rematch remain the scheduling truth checks.

## Candidate Source Operations

`WorkerCandidateSelectionPolicy` exposes three fixed operations rather than a
generic strategy switch. Scheduling Candidate Source, Matcher-derived
identity range, canonical matching, selection and exact lease are separate
steps. The evaluator and Match Plan are package-private fixed implementation,
not public SPI, dynamic registry or stored state:

```text
Task-rule precomputation
  observe one bounded due-HOT pool for the WorkerGroup
  match that shared Worker pool against all Task Candidate rules
  apply Task priority, deficit/requested count and unique-Worker selection
  exact-acquire only selected Workers, rematch their original pairs
  publish the still-active lease subset to CandidateWorkerCache

Cached candidate renewal
  consume CandidateWorkerCache into one scoped WorkerId range per CandidateId
  canonical-match only those Candidate/Worker pairs
  exact-validate/renew the cached Worker lease fences through WorkerScoreCore
  canonical-rematch the original successful Candidate/Worker pairs
  return partial or empty on miss/stale/mismatch

Item-rule on-demand acquisition
  for any empty rule, issue one due-HOT Group query shared by the round
  derive bounded explicit Worker IDs from workerId EQ/IN conditions
  point-observe only those explicit IDs inside the Task WorkerGroup
  give other non-empty or invalid rules no scheduling source for the round
  canonical-match each Candidate's own bounded WorkerId range
  admit at most 100 unique WorkerIds across the round in priority order
  exact-lease only the chosen Workers through WorkerScoreCore
  canonical-rematch only each Candidate's originally selected successful leases
  never read or write CandidateWorkerCache
```

The allocation policy calls `acquire_shared_hot_candidates(...)`, Task Dispatch
calls either `renew_cached_candidates(...)` or
`acquire_on_demand_candidates(...)`, and none calls another as fallback. A
cached miss never becomes an on-demand HOT scan.

The selection policy and request type are internal to Dispatch policy. The
three operations are not exported through Kernel Pacer Runtime, assembly, or
the HTTP Task contract. `WorkerAllocationMechanism` is a fixed Producer
workflow label; it is not a Matcher mode.

Candidate selection stays flat and keeps its source shape explicit:

```text
WorkerCandidateMatcher.prepare
  -> CandidateId -> normalized Conditions reused for the complete call

WorkerCandidateMatcher on-demand identity source derivation
  -> unrestricted Candidate IDs + explicit CandidateId -> WorkerId[]

Candidate Source
  -> WorkerId -> observedScore
  -> shared WorkerId[] or CandidateId -> WorkerId[]

WorkerCandidateMatcher.matchSharedWorkerPool / matchCandidateScopedWorkerIds
  -> consume the same Match Plan
  -> one bounded canonical Descriptor load over the input union
  -> CandidateId -> WorkerDescriptor[]

Policy selection
  -> priority, requestedCount and cross-Candidate unique Worker assignment
  -> exact ACQUIRE or RENEW only the selected WorkerIds

WorkerCandidateMatcher.matchCandidateScopedWorkerIds post-lease rematch
  -> only original Candidate/Worker pairs whose lease succeeded
  -> current canonical WorkerDescriptor[]

Policy terminal assembly
  -> CandidateId -> AcquiredWorkerCandidate[]

AcquiredWorkerCandidate
  workerId
  workerGroupId
  current canonical endpointManagerId
  exact Worker lease score evidence
```

Task-rule precomputation matches one shared HOT pool against multiple Task rules;
the same Worker may therefore appear in several Matcher results before
Selection. Cached candidate renewal instead consumes Task-local Cache entries
and retains one Candidate-scoped Worker range per CandidateId. Item-rule
on-demand acquisition uses one bounded Group score query for unrestricted rules
or point-observes the bounded identity ranges returned by Matcher and never
touches the Cache. Matcher-derived
identity is not Score eligibility; Selection still obtains and preserves the
opaque score evidence before choosing or leasing a Worker.

Matcher owns rule preparation, rule-derived Worker identity ranges, complete
canonical Rule Match and post-lease rematch. It does not know priority,
requested count, score, lease, cache or unique-Worker policy. Selection prepares
one Match Plan, applies `(priority, candidateId)`, requested counts and the
round's unique-Worker budget, and passes that same Plan back after the Score
Owner lease. The second call reloads only the original Candidate/Worker pairs
whose lease succeeded; it never reallocates a failed pair to another Candidate.
Its current descriptor supplies the final endpoint. Lease competition or
post-lease mismatch returns a partial result and does not refill within the
round.

Matcher context has three roots:

```text
workerId
worker.*
platform.*
```

`worker.*` and `platform.*` fields read their corresponding canonical
descriptor snapshots. The removed `index.*` namespace is invalid and cannot
discover or intersect candidate sets. A future Property index may extend the
Matcher's internal source derivation with bounded Worker IDs; ordinary
properties still participate only in the complete evaluator, and canonical
descriptor rematch remains mandatory.

## Candidate Cache

`CandidateWorkerCache` is disposable Task-oriented prefetch evidence:

```text
CandidateId -> CandidateWorkerEntry[]

CandidateWorkerEntry
  workerId
  workerGroupId
  workerLeaseScore
```

The persisted JSON uses exactly these three fields. The Cache retains the
exact Worker lease fence needed by cached candidate renewal, but does not
persist a delivery address. `endpointManagerId` is resolved from the current canonical
Worker descriptor during the post-lease rematch.

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
Item-rule on-demand results never enter this cache.

There is no Candidate scheduling index. A `PRECOMPUTED_TASK_RULE` Task remains
visible to the Main Scheduler's RUNNING Source until Task Dispatch advances,
parks, or closes it.
Allocation recomputes its deficit from Candidate Cache each observed round.

## Round Flows

Allocation:

```text
shared due RUNNING observations
  -> retain workerAllocationMechanism=PRECOMPUTED_TASK_RULE
  -> requestedCount = maximumCandidateWorkers - cachedCount
  -> one Group HOT source shared by all Task Candidate rules
  -> canonical match, priority/deficit/unique selection and exact lease
  -> canonical rematch of original successful Candidate/Worker pairs
  -> append under CandidateId=taskId
  -> allow later RUNNING discovery to retry any remaining deficit
```

Task dispatch:

```text
dispatch-visible RUNNING Tasks
  -> Policy reads due Item Score observations and canonical records
  -> Policy marks exhausted/expired Items and chooses Item/Worker pairing
  -> cached candidate renewal or item-rule on-demand source supplies Worker IDs
  -> WorkerCandidateMatcher validates complete canonical rules
  -> WorkerCandidateSelectionPolicy chooses and exact-validates Worker fences
  -> WorkerCandidateMatcher reloads only original successful pairs; Selection
     uses current endpoints to produce final AcquiredWorkerCandidate values
  -> TaskAssignmentDispatcher exact-renews Worker fences and exact-claims only
     Worker-backed Items
  -> TaskAssignmentDispatcher constructs ResultContext and DeliveryCommand,
     then appends Adapter-partitioned sparse mailboxes
  -> Policy performs ordinary Task pacing

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

`workerAllocationMechanism` is fixed by the Task. The two rule locations cannot
be mixed, and the dispatch round does not infer its workflow from Item contents.
`workerGroupId` always comes from `TaskDescriptor`.

## Owner And Failure Boundaries

- `TaskInitializationCheck` receives only the INITIAL taskId-to-score map,
  checks due Items once, and asks the Task Score Owner for one exact batch
  promotion into NORMAL.
- `TaskWorkerAllocationPolicy` receives only Main-selected
  `PRECOMPUTED_TASK_RULE` Task
  evidence and never reads or mutates Task score.
- `TaskWorkerAllocationPolicy` owns deficit and may read bounded Candidate
  counts directly from `CandidateWorkerCache`.
- `WorkerCandidateSelectionPolicy` owns finite HOT/Cache scheduling Source
  branches, Score eligibility, request priority/count/unique selection, exact
  lease/renew, Matcher calls and terminal Candidate assembly. It does not parse
  Property names or Constraint operators. It may correlate and return raw
  Scores but cannot decode or calculate them.
- `WorkerCandidateMatcher` owns one call-local Match Plan, rule-derived Worker
  identity ranges, bounded canonical descriptor reads, complete Rule Match and
  original-pair rematch. It has no Score, Cache, lease, priority, count or
  uniqueness semantics. Descriptors return only to Selection and do not flow
  into Candidate Cache or Task execution.
- `TaskDispatchPolicy` owns expiry/exhaustion decisions, pairing, per-Task
  limits, Item finality, ordinary pacing and idle-disposition choice.
- `TaskAssignmentDispatcher` protects the exact Worker renew -> Item claim ->
  Command publication ordering; `TaskIdleSettlement` protects complete ACTIVE
  recheck -> exact close/park -> post-park repair.
- Dispatch policies may call already-bounded mechanical Owners directly.
  Such calls do not justify a pass-through Mechanism; only a named cross-Owner
  legal transition does.
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
- Assignment Dispatch routes only by the endpoint resolved from the canonical
  Descriptor during post-lease rematch. Candidate Cache contains no endpoint.
  Dispatch never reads connection or session state and never exposes the
  endpoint as a Worker matching field.

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

- Do not collapse the fixed Resource Producers into one Task mutation
  procedure.
- Do not turn CandidateWorker cache into the universal dispatch mechanism.
- Do not add cached-miss on-demand fallback.
- Do not expose candidate-source switches, cache flags, or rule owner as independent
  Task configuration.
- Do not add a WorkerAllocationMechanism for a parameter variation or an imagined policy
  combination; require a named workload and vertical executable proof.
- Do not merge Task and Item allocation rules implicitly.
- Do not add a cross-CandidateId requested count or cross-WorkerGroup call.
- Do not treat an index result as final matching or Worker availability truth.
- Do not expose scan limits, Redis keys, score fields, or score decoding.
- Do not allocate Workers for RUNNING INITIAL Tasks.
- Do not introduce a second Candidate-demand cursor beside due RUNNING Task score.
