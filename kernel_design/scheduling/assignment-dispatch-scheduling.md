# Assignment-Dispatch Scheduling

Status: new-kernel mechanism note. This document describes the target
assignment-dispatch scheduling plane for the clean kernel core. It is not
current implementation truth and not an implementation roadmap.

Current design state: phase-one batch allocation is being specified. No Python
allocation interface or allocation-result storage shape is frozen yet.

## Purpose

Assignment-dispatch scheduling is the bridge between task scheduling visibility,
worker allocation, work claim, and final-hop delivery.

It is not one monolithic pacer. The target design has two independent pacers:

```text
WorkerAllocationPacer
  task scheduling candidates -> worker constraint batch -> allocation handoff

WorkDispatchPacer
  dispatch-visible task + allocation handoff -> work claim -> deliver seed
```

The separation is intentional:

```text
task can be allocated a worker candidate
  !=
task can dispatch a concrete work item now
```

Worker allocation answers whether a task has matching worker resources.
Work dispatch answers whether a current work item can be claimed and delivered
to an already selected and score-leased worker.

The two pacers can run at different cadence and with different batch limits.
They are independent scheduling entries, not necessarily independent threads.
The dispatch pacer consumes a point-readable allocation handoff for task ids
selected by task score; it must not introduce a second global task index.

## Non-Owners

Assignment-dispatch does not own:

```text
task lifecycle / task score truth
worker lifecycle / worker score truth
worker capacity truth
work-item persistence
result finality
transport session lifecycle
read models or diagnostics
```

It observes these facts only through owner-approved handoff objects and returns
bounded round evidence back to the owning plane.

## Owner Inputs

Assignment-dispatch consumes owner-approved inputs:

```text
TaskSchedulingCandidate
  taskId
  observedTaskScore
  task scheduling policy snapshot

WorkerGroupSelection
  immutable task workerGroupId

WorkerCandidateConstraintBatch
  candidateId -> WorkerCandidateConstraint map

WorkerCandidateMatchGraph
  ordered candidateId -> matched worker ids; one row per input candidate

RankingPolicy
  priority / ranking rules

WorkerCandidate
  workerId or workerResourceId
  observedWorkerScore
  worker metadata snapshot
  score lease / hold evidence

TaskWorkerAllocationHandoff
  taskId
  ordered worker entries
  worker score fences
  expiry / lease evidence when required

ClaimableWorkEvidence
  taskId
  whether ready work appears claimable
  optional earliest retry / no-work evidence

TransportDeliveryPlan
  selected worker id
  transport-resolved delivery handle, opaque to scheduling
  transport-local delivery evidence
```

These are target vocabulary only. The current Python interface and concrete
allocation-handoff record are not frozen.

`TaskWorkerAllocationHandoff` is short-lived round evidence, not durable truth.
It must have a fence or expiry and must be safe to discard. Current truth
remains with task score-band, worker-runtime, work-item runtime, and transport.

If worker entries inside the handoff are score-leased, they are still not owned
assignments. The score lease is intentionally short. If WorkDispatchPacer does
not consume or extend it before expiry, the handoff must be treated as stale.

Worker candidate freshness is score-fenced, not version-fenced. A handoff may
keep the complete `observedWorkerScore` or leased score. It must not require
assignment-dispatch to understand worker metadata replacement details, dirty
bits, score suffixes, or capacity internals.

`validationDependencySet` remains possible internal evidence about which worker
metadata, dynamic attributes, group membership facts, gates, or policy facts
were used by matching. It is not part of the current handoff contract and must
not become a public DTO, new runtime owner, or reverse-query API.

## Pacer A: Worker Allocation

WorkerAllocationPacer starts from task scheduling visibility and produces the
transient allocation handoff. It does not claim work and does not create
deliver seeds.

The current design slice starts from a bounded task-id batch. It does not need a
complete Task aggregate. It batch-reads stable allocation metadata from
`TaskResourceCatalog` and builds matcher constraints directly from the inline
allocation rule. The interface must not be frozen before the phase-two handoff
is explicit.

Mainline:

```text
1. acquire one bounded task-id batch from task score-band
2. batch-read TaskDescriptor rows from TaskResourceCatalog
3. build one WorkerCandidateConstraint for each accepted task id
4. partition tasks by `workerGroupId`
5. build one `taskId -> WorkerCandidateConstraint` map for each partition
6. let worker runtime resolve the group-local home bucket, discover one bounded
   worker candidate batch, and keep
   observedWorkerScoreByWorkerId as assignment-dispatch sidecar evidence
7. match workers against the priority-ordered task constraints through
   worker-runtime
8. for PRE_DISPATCH_VISIBLE tasks, validate
   `matchedWorkerCount >= runningVisibleMinimumCandidateWorkers`
9. request the task score owner transition satisfied tasks to RUNNING_VISIBLE;
   already RUNNING_VISIBLE tasks skip this first-start condition
10. classify eligible matched worker rows and perform the still-to-be-frozen
    worker lease step
11. process allocation results into the transient task-to-worker handoff
12. request owner score rewrites for no-match,
    contention, or stale cases
13. let the dispatch-work round consume the handoff by task id
```

WorkerAllocationPacer answers:

```text
does this task currently have a usable worker candidate?
```

It does not answer:

```text
is there a concrete work item to claim right now?
has transport accepted delivery?
is the result final?
```

### Worker Candidate Constraint

Each candidate constraint carries worker identity and attribute match needs.
Dynamic reads remain handler-owned IO, but their required field set is derived
from `dynamic.*` rule keys instead of repeated by the caller. The constraint is
applied before worker score lease:

```text
priority = candidate worker-consumption priority
limit = maximum workers consumed by this candidate in one matcher call
match_rules = workerId + system.* + static.* + dynamic.* predicates
```

`match_rules` is a structured map, not a JSON string. The independent
constraint DSL compiles that map once per matcher call; worker-runtime supplies
the worker context but does not own DSL syntax or operator evaluation.
Qualified fields split only at the first `.`: `dynamic.battery.level` means
domain `dynamic` and exact dynamic attribute name `battery.level`.

Worker matcher preparation derives and deduplicates the `dynamic.*` field union
from compiled `match_rules`. Assignment-dispatch cannot request speculative
dynamic reads outside the predicates it actually supplies.

Task create/admission validates and fixes exactly one registered
`workerGroupId`. The task's selected `workerGroupId` is immutable while the task
is running.
Assignment-dispatch may validate `eventCode` against the selected group
declaration, but it must not query worker groups, switch worker groups, or do
fallback group selection as a hot-path discovery step. `eventCode` does not
participate in individual worker matching.

Hard match rules filter the candidate universe. Priority rules rank only among
workers that already satisfy hard match rules. Priority must not make an
ineligible worker eligible.

`WorkerCandidateMatcher.match_worker_candidates` receives one selected
`workerGroupId`, a bounded `workerIds` batch, and a
`candidateId -> WorkerCandidateConstraint` map. Each constraint carries
`priority`, `limit`, and `match_rules`. The matcher resolves worker
consumption order by priority descending and `candidateId` ascending; map
insertion order is not scheduling policy. One call handles exactly one worker group;
assignment-dispatch must partition task candidates by `workerGroupId` before
calling the matcher. The matcher returns an insertion-ordered
`candidateId -> matchedWorkerIds` map in resolved priority order containing
every input candidate; an empty worker-id list means no match. Worker ids are
the outer loop: candidates already at `limit` are skipped, and the first
remaining match consumes that worker. One worker cannot appear in two candidate
results, and candidate limits do not persist across matcher calls.
The matcher batches each derived dynamic field once for descriptor-supported
bounded workers, then builds only one temporary context for the current worker.
It does not retain all worker contexts or run metadata/dynamic matching passes.
Worker score lease / hold validation must use concrete worker ids and score
fences. It must not re-run the constraint DSL.
The matcher does not return or own `observedWorkerScore`; assignment-dispatch
keeps the observed score from worker score acquire as
`observedWorkerScoreByWorkerId` and passes it later to worker score lease.

### Candidate Discovery

First version discovery should be simple:

```text
homeBucketId = worker-runtime-owned lookup from workerGroupId
worker_score.acquire_hot_acquire_candidates(homeBucketId, limit)
observedWorkerScoreByWorkerId = {workerId: observedWorkerScore, ...}
worker_candidate_matcher.match_worker_candidates(workerGroupId, workerIds, candidateConstraints)
for each matched candidate worker:
  validate worker group / metadata / score lease evidence as needed
  join complete observed worker score from observedWorkerScoreByWorkerId
  rank by priority rules
```

This mode may scan more workers than an indexed design, but it has one clear
truth source: the worker score bucket plus worker-runtime validation.

The work item's `eventCode` routes to a worker-local `EventHandler` after a
worker is selected. It is not a worker group selector. If one use case needs
multiple worker groups for different event families, model that as multiple
tasks or explicit task creation choices, not one task that dispatches across
groups.

Optional later discovery modes:

```text
attribute / placement index
workerId point lookup
precomputed constraint bucket
```

These indexes are hints only. They must not own worker truth, score truth,
capacity truth, or transport reachability. Every candidate found through an
index must still be validated by worker-runtime against current score,
metadata, gates, and score lease state.

### Capacity And Score Lease

`capacityLimit` is declaration/configuration metadata. Current load and
capacity can be projected through dynamic attributes such as `runningCount`,
`freeSlots`, local queue depth, or load. These values narrow / rank candidate
workers through worker-runtime matching and assignment policy.

WorkerAllocationPacer may use metadata for cheap ranking or filtering, but final
assignment admission must still be protected by worker score lease / hold in
v0. The score lease is a short assignment fence, not an execution-duration lock
and not the owner of in-flight concurrency counters. If the allocation pacer
creates a score lease, that lease must be fenced and either consumed by
WorkDispatchPacer or released / expired on stale failure.

The worker score lease fence includes:

```text
observedWorkerScore
workerScoreLeaseExpiresAt
```

Worker score lease has two explicit transitions:

```text
acquire_due_hot_score_lease(observedWorkerScore, leaseUntil)
  after WorkerCandidateMatcher has validated current descriptor / dynamic
  metadata for a due HOT_ACQUIRE worker score. It uses observed-score CAS and
  may clear dirty because validation happened before the CAS write.

renew_active_hot_score_lease(observedLeasedWorkerScore, leaseUntil)
  before work claim / dispatch when an allocation handoff already holds a short
  active lease. It uses observed-score CAS and returns STALE on dirty because it
  does not re-run matching.
```

Worker-runtime owns the current scheduling metadata signature, dynamic capacity
evidence, and score lease truth. Score `dirty` is not a worker-global state or
version; it is only a score-lease-local stale hint embedded in the observed
score. Assignment-dispatch keeps the full observed score as an opaque fence.
If worker group membership, eventCode declaration, match metadata, dispatch
gate, resource policy, capacity declaration, or dynamic capacity projection
changes, worker-runtime decides whether the existing score lease remains valid.
The next due-score lease must use the post-match observed score. The next active
score lease renewal must return STALE when dirty is present, forcing the handoff
to be discarded and matched again.

## Pacer B: Work Dispatch

WorkDispatchPacer starts from task ids selected by
`acquire_dispatch_work_tasks(limit)`, their point-readable allocation handoff,
and current task work. It does not do broad worker discovery. It may revalidate
or finalize worker score lease before claiming work.

Mainline:

```text
1. acquire dispatch-visible task ids from task score-band
2. point-read and validate each task allocation handoff fence / expiry
3. point-read and revalidate task score is still dispatch-visible
4. renew selected worker active score lease with observed-score CAS; dirty or
   stale means discard the handoff and return to worker matching
5. claim current work hash rows from the work-item owner
6. release or compensate worker score lease if claim fails
7. resolve transport delivery lane for the selected worker
8. produce DeliverSeed
9. classify round evidence and request task/worker score rewrites
```

WorkDispatchPacer answers:

```text
can this task dispatch one concrete work item to an allocated worker now?
```

It does not answer:

```text
which broad worker universe should compete?
which disconnected worker should recover?
what result finality means?
```

## Work Claim Boundary

Work claim belongs to the work-item owner.

WorkDispatchPacer may ask for cheap claimable-work evidence before final worker
score lease, but the final claim must happen only when there is enough worker
score lease evidence to avoid consuming work with no viable dispatch path.

Claim sequence:

```text
task allocation handoff selected
  -> worker active score lease renewed with observed-score CAS
  -> work hash claim writes current occupancy
  -> deliver seed produced
```

If claim fails after worker score lease:

```text
release / compensate worker score lease
classify dispatch round as no-ready or stale
rewrite task score through task-score owner
```

Do not make task score-band pop work. Do not make worker score-band claim work.

Claim is intentionally thin. It does not create an `Attempt`, a separate timeout
owner, or a scheduling id. It writes the current work hash row and returns the
same claim evidence inside the deliver seed.

## Deliver Seed

A deliver seed is the scheduling-owned handoff to transport:

```text
DeliverSeed
  taskId
  workItemId
  workerId
  workerGroupId
  eventCode
  payload or payloadRef
  claimExpiresAtMillis?
  runtimeEpoch?
  createdAt
```

`workerId` is the selected execution identity. Adapter id, adapter family,
session id, mailbox, route, and delivery queue key are transport-internal
delivery resolution facts. They are not scheduling-owned fields and must not
become worker selection truth. If worker matching needs a network category, use
worker attributes such as `networkType`, not adapter/session identifiers.

`eventCode` is carried so the selected worker can invoke the worker-local
`EventHandler`. It must already be valid for the task's selected worker group;
transport must not reinterpret it as a worker or worker-group selector.

The seed carries evidence, not truth. Current truth remains in the work hash:

```text
work hash current fields
  state = CLAIMED
  workerId
  claimExpiresAtMillis?  # only for time-bounded work
  retryCount
  maxRetryCount or runtime policy reference
```

Transport may reject delivery as unavailable. That produces delivery evidence
for retry/compensation; it does not give transport permission to pick another
worker.

## Score Rewrite Evidence

Assignment-dispatch produces evidence for score owners; it does not directly
own those scores unless the implementation collapses owners inside one process
while preserving explicit method boundaries.

Task-side examples:

```text
ACTIVATION_STILL_WAITING and suffix > 00 -> PRE_DISPATCH_VISIBLE with next time slot and suffix-1
ACTIVATION_STILL_WAITING and suffix == 00 -> PRE_DISPATCH_VISIBLE pause/hold
ACTIVATION_READY -> RUNNING_VISIBLE due score
NO_READY_WORK and suffix > 00 -> RUNNING_VISIBLE with next no-work recheck and suffix-1, or RUNNING_VISIBLE delayed by scheduled retry evidence
NO_READY_WORK and suffix == 00 -> TERMINAL
NO_WORKER_CANDIDATE and suffix > 00 -> RUNNING_VISIBLE with future no-worker-match recheck and suffix-1
NO_WORKER_CANDIDATE and suffix == 00 -> RUNNING_VISIBLE pause/hold
WORKER_CONTENDED and suffix > 00 -> RUNNING_VISIBLE with future contention recheck and suffix-1
WORKER_CONTENDED and suffix == 00 -> RUNNING_VISIBLE pause/hold
WORK_CLAIMED_MORE_REMAINS -> RUNNING_VISIBLE at now or policy delay
WORK_CLAIMED_NO_READY_REMAINS -> RUNNING_VISIBLE with next no-work recheck unless scheduled retry evidence keeps RUNNING_VISIBLE delayed
PAUSE_OR_BLOCK -> same active band with a future time slot; hard pause uses the score-band pause slot
```

Worker-side examples:

```text
ADMITTED -> worker future score / capacity interval
CAPACITY_FULL -> worker future score
FILTER_REJECTED_STALE_METADATA -> worker low-recheck or parked
DELIVERY_LANE_UNAVAILABLE -> worker low-recheck or transport evidence only,
depending on owner policy
```

## Failure And Stale Handling

Stale candidates are expected:

```text
task score due but task gate closed
worker score due but worker metadata stale
allocation handoff expires before work claim
worker candidate lease expires before work claim
worker score lease revalidation fails after allocation
worker score-leased but work claim fails
work claimed but transport delivery lane rejects
worker score lease expires before deliver seed is accepted
```

Rules:

- every stale outcome must be bounded;
- every partial worker score lease must have a compensation path;
- no scheduling round may create an unowned active claim;
- no scheduling round may dispatch without a selected worker and current work
  hash claim evidence;
- no scheduling round may retry by reinterpreting transport identifiers as
  worker-selection facts;
- no allocation handoff may outlive its fence/expiry as hidden worker truth.

## Current Design Slice: Batch Task-Worker Allocation

This section is the active design contract for the next Python interface. It is
deliberately more concrete than the target-plane description above, but it does
not freeze storage or method signatures yet.

### Main Decision

Worker allocation is a batch join:

```text
bounded task ids
  -> taskId -> WorkerCandidateConstraint
  -> partition by workerGroupId
  -> worker runtime resolves its home bucket
  -> bounded workers for that partition
  -> WorkerCandidateMatcher
  -> taskId -> matched worker ids
  -> allocation-result processing
```

For the first cut:

```text
CandidateId = TaskId
```

Do not call `WorkerCandidateMatcher` once per task. Per-task calls lose dynamic
attribute batching and allow the same worker to be consumed independently by
multiple tasks in what should be one allocation round. One matcher call must see
all task constraints competing for the same worker partition.

`taskBatchLimit` and `workerScanLimit` are independent controls. The task limit
caps scheduling demand inspected in one round. The worker limit caps the worker
universe read for one `workerGroupId` partition.

### Required Task-Runtime Semantics

The stable allocation metadata is defined by the
[Task Resource Model](../resource-model/task-resource-model.md).

The existing score primitive can provide the first bounded ids:

```text
task_score.acquire_active_task_candidates(limit)
  -> taskIds
```

Assignment-dispatch then needs one batch read that can resolve, for every task:

```text
taskId
workerGroupId
candidate priority
config["runningVisibleMinimumCandidateWorkers"]
config["priority"]
allocationRule
```

This does not require a complete Task aggregate or a task-runtime allocation
projection. `TaskResourceCatalog.load_task_allocation_descriptors(taskIds)`
returns the stable allocation metadata defined by the Task Resource Model. It is
not a general Task read surface.

The returned config is the descriptor snapshot resolved at registration.
Assignment-dispatch does not query an external configuration source during a
scheduling round.

Task-runtime must not evaluate worker descriptors or dynamic worker values.
Worker-runtime must not read Task metadata to construct constraints.

### Inline Constraint Boundary

`constraint_dsl` owns rule validation and evaluation. It remains independent of
Task and Worker models. Assignment-dispatch performs only the direct value
mapping:

```text
TaskDescriptor
  -> config["priority"] + allocationRule
  -> taskId -> WorkerCandidateConstraint
```

Assignment-dispatch parses and validates the two config values it owns;
`match_rules` is the loaded allocation rule. One uniform allocation
configuration supplies
`WorkerCandidateConstraint.limit`, defaulting to `100` in the first cut. There
is no allocation-rule name, handler registry, or separate rule-resolution
owner.

### Batch Round Pseudocode

```python
def allocate_candidate_workers(task_batch_limit, worker_scan_limit):
    task_ids = task_score.acquire_active_task_candidates(
        limit=task_batch_limit,
    )
    task_score_states = task_score.get_score_states(task_ids=task_ids)
    task_descriptors = task_resource_catalog.load_task_allocation_descriptors(
        task_ids=task_ids,
    )
    grouped_descriptors = group_by_worker_group(task_descriptors)

    for worker_group_id, grouped_tasks in grouped_descriptors:
        candidate_constraints = build_candidate_constraints(
            grouped_tasks,
            maximum_candidate_workers=allocation_config.maximum_candidate_workers,
        )
        home_bucket_id = worker_runtime.resolve_home_bucket(worker_group_id)

        worker_candidates = worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=home_bucket_id,
            limit=worker_scan_limit,
        )
        observed_worker_scores = {
            worker_id: observed_score
            for worker_id, observed_score in worker_candidates
        }

        matches = worker_candidate_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=list(observed_worker_scores),
            candidate_constraints=candidate_constraints,
        )

        running_visible_results = evaluate_running_visible_condition(
            grouped_tasks=grouped_tasks,
            task_score_states=task_score_states,
            matches=matches,
        )

        allocation_results = resolve_worker_allocations(
            grouped_tasks=grouped_tasks,
            running_visible_results=running_visible_results,
            matches=matches,
            observed_worker_scores=observed_worker_scores,
        )
        process_worker_allocation_results(
            grouped_tasks=grouped_tasks,
            allocation_results=allocation_results,
        )
```

The names in this pseudocode are conceptual. They are not committed Python
interfaces.

### Allocation Result Handoff

`process_worker_allocation_results` is not presentation or diagnostics. It is
the phase-one to phase-two scheduling handoff and therefore needs explicit
runtime semantics.

The current candidate shape is one ordered collection per task:

```text
taskId -> [worker allocation entry, ...]
```

A physical Redis `LIST` per task is a possible implementation, but the key type
is not frozen. Before selecting `LIST`, the design must prove the required
replace, consume, expiry, and compensation operations.

The handoff is transient assignment-dispatch evidence. It is not Task truth,
Worker truth, or a durable assignment record. It must not store full Task or
Worker descriptors, dynamic values, or decoded score internals.

At minimum, each worker entry needs enough information to preserve the worker
score fence:

```text
workerId
complete observedWorkerScore or complete leasedWorkerScore
leaseUntilMillis when phase one already acquired a lease
```

Whether the store contains matched workers or already leased workers is not yet
decided:

```text
Option A: store matched worker + observed score
  phase two revalidates and acquires the worker lease
  lower reservation pressure, but a wider stale window

Option B: acquire worker lease before store
  store worker + leased score
  stronger handoff fence, but store failure requires release compensation and
  leasing multiple workers for one task may over-reserve capacity
```

This decision must precede the kernel interface because it changes both the
allocation result and the phase-two input.

### Phase-Two Discovery

Do not add an allocation queue or a second global task index in the first cut.
Task score remains the scheduling pacer:

```text
task_score.acquire_dispatch_work_tasks(limit)
  -> taskIds
for each taskId
  -> point-read taskId worker allocation handoff
  -> validate fence / lease
  -> claim work
  -> create deliver seed
```

The per-task collection therefore does not need to discover tasks. It only
provides the point-readable handoff after task score selects the task.

### Store Semantics Still To Freeze

Before a Redis key or Python interface is written, decide:

```text
replace versus append when allocation runs again for the same task
ordered fallback list versus multiple concurrently usable workers
matched score versus leased score in each entry
TTL / expiry source
empty result: delete, empty collection, or explicit short-lived marker
atomic consume behavior for phase two
store failure compensation after worker lease
task terminal / pause cleanup of remaining entries
```

Repeated allocation must be idempotent. A retry must not append duplicate worker
entries or leave an older leased score ahead of a newer fence.

### Interface Freeze Condition

Do not recreate `WorkerAllocationPacer` until all of these are answered:

```text
1. grouping key and independent task/worker limits
2. match-only versus lease-before-store result
3. per-task handoff record and replace/consume semantics
4. task-score transition or validation between allocation and dispatch-work
```

Only then should method signatures be extracted from the pseudocode. No Task
aggregate, assignment-plan store abstraction, ranking SPI, Redis key, or
WorkDispatch implementation should be introduced earlier.

## Guardrails

- Do not collapse worker allocation and work dispatch into one implicit
  `schedule_once` path.
- Do not let WorkDispatchPacer introduce a second task index. It uses
  `acquire_dispatch_work_tasks(limit)` and point-reads the allocation handoff
  before claim.
- Do not let assignment-dispatch refresh task score because append happened.
- Do not keep a task-score pagination cursor; use bounded range + limit queries
  and consume dispatch-visible candidates by score rewrite, band move, close,
  cleanup, or stale-fence failure.
- Do not make worker score-band understand task match rules. It only exposes
  due worker candidates by score.
- Do not let auxiliary worker indexes become truth. They are stale hints and
  must validate through worker-runtime.
- Do not let assignment-dispatch select workers from transport sessions.
- Do not let transport choose backup workers.
- Do not claim work before worker score lease unless the claim owner has an
  explicit reversible claim model.
- Do not create deliver seeds without a current work hash claim.
- Do not make result finality a dispatch concern.
- Do not store full worker objects or task objects inside deliver seeds.
