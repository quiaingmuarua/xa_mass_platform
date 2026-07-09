# Assignment-Dispatch Scheduling

Status: new-kernel mechanism note. This document describes the target
assignment-dispatch scheduling plane for the clean kernel core. It is not
current implementation truth and not an implementation roadmap.

## Purpose

Assignment-dispatch scheduling is the bridge between task scheduling visibility,
worker allocation, work claim, and final-hop delivery.

It is not one monolithic pacer. The target design has two independent pacers:

```text
WorkerAllocationPacer
  task scheduling candidates -> worker constraint batch -> candidate worker plans

WorkDispatchPacer
  active task + candidate worker plan -> work claim -> deliver seed
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
The dispatch pacer consumes assignment plans; it must not become a second broad
task-score scanner.

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

WorkerConstraintBatch
  ordered unique candidateId + WorkerConstraintQuery entries

WorkerCandidateMatchGraph
  ordered candidateId -> matched worker ids; one row per input candidate

RankingPolicy
  priority / ranking rules

WorkerCandidate
  workerId or workerResourceId
  observedWorkerScore
  worker metadata snapshot
  score lease / hold evidence

TaskWorkerAssignmentPlan
  taskId
  workerGroupId
  workerConstraintQuery
  validationDependencySet
  candidate workers or selected worker
  observed task / worker fences
  planEpoch
  expiresAt

ClaimableWorkEvidence
  taskId
  whether ready work appears claimable
  optional earliest retry / no-work evidence

TransportDeliveryPlan
  selected worker id
  transport-resolved delivery handle, opaque to scheduling
  transport-local delivery evidence
```

These are conceptual handoff shapes. The first Python kernel may implement them
as small dataclasses. They are not public API DTOs.

`TaskWorkerAssignmentPlan` is short-lived round evidence, not durable truth. It
must have a fence or expiry and must be safe to discard. Current truth remains
with task score-band, worker-runtime, work-item runtime, and transport.

Worker candidates inside a plan are score-leased candidates, not owned
assignments. The score lease is intentionally short. If WorkDispatchPacer does
not consume or extend it before expiry, the plan must be treated as stale.

Worker candidate freshness is score-fenced, not version-fenced. A plan may keep
the complete `observedWorkerScore`. It must not require assignment-dispatch to
understand worker metadata replacement details, dirty bits, score suffixes, or
capacity internals.

`validationDependencySet` is internal evidence about which worker metadata,
dynamic attributes, group membership facts, gates, or policy facts were used by
`WorkerCandidateMatcher` / worker-runtime validation for this plan. It is not a
public DTO, not a new runtime owner, and not a query API. It exists so a later
dynamic attribute executable spec can decide whether an update can affect this
specific plan. If a changed dependency is not in the set, it should not mark
the plan's worker score dirty.

## Pacer A: Worker Allocation

WorkerAllocationPacer starts from task scheduling visibility and produces
candidate worker plans. It does not claim work and does not create deliver
seeds.

Mainline:

```text
1. choose task score scan range and allocation batch limit
2. acquire task candidates from task score-band
3. validate task candidate and policy snapshot
4. partition task candidates by the pre-bound workerGroupId
5. compile an ordered WorkerConstraintQuery batch for each workerGroupId from
   worker identity / attribute constraints
6. derive home bucket / resource universe from the pre-bound workerGroupId
7. discover a bounded worker candidate batch and keep
   observedWorkerScoreByWorkerId as assignment-dispatch sidecar evidence
8. match worker candidates against the ordered constraint batch through
   worker-runtime
9. apply priority / ranking rules
10. optionally score-lease the selected worker or produce a fenced candidate plan
11. classify evidence and request owner score rewrites for no-match,
    contention, or stale cases
12. publish TaskWorkerAssignmentPlan for the dispatch pacer
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

### Worker Constraint Query

Worker constraint query is compiled from worker identity and attribute match
needs. It is applied before worker score lease:

```text
worker.id $eq / $in, if explicitly constrained
system/static/dynamic attribute predicates
placement / attribute constraints
```

Project / workload configuration declares the allowed worker groups. Task
create/admission selects exactly one `workerGroupId` from that allowed set. The
task's selected `workerGroupId` is immutable while the task is running.
Assignment-dispatch may validate `eventCode` against the selected group
declaration, but it must not query worker groups, switch worker groups, or do
fallback group selection as a hot-path discovery step. `eventCode` does not
participate in individual worker matching.

Hard match rules filter the candidate universe. Priority rules rank only among
workers that already satisfy hard match rules. Priority must not make an
ineligible worker eligible.

`WorkerCandidateMatcher.match_worker_candidates` receives one selected
`workerGroupId`, a bounded `workerIds` batch, and an ordered list of
`(candidateId, WorkerConstraintQuery)` entries. `candidateId` must be unique
inside one call. The ordered list preserves task / candidate priority without
making worker-runtime own ranking. One call handles exactly one worker group;
assignment-dispatch must partition task candidates by `workerGroupId` before
calling the matcher. The matcher returns one `(candidateId, matchedWorkerIds)`
entry for every input candidate in candidate order; empty `matchedWorkerIds`
means no match.
Worker score lease / hold validation must use concrete worker ids and score
fences. It must not re-run the query DSL.
The matcher does not return or own `observedWorkerScore`; assignment-dispatch
keeps the observed score from worker score acquire as
`observedWorkerScoreByWorkerId` and passes it later to worker score lease.

### Candidate Discovery

First version discovery should be simple:

```text
homeBucketId = pre-bound workerGroupId or owner-defined worker home bucket
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
worker is selected. It is not a worker group selector. If the project/task needs
multiple worker groups for different event families, model that as multiple
tasks or explicit task creation choices, not one task that dispatches across
groups.

Optional later discovery modes:

```text
attribute / placement index
worker.id point lookup
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
  before work claim / dispatch when an assignment plan already holds a short
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
score lease renewal must return STALE when dirty is present, forcing the plan to
be discarded and matched again.

## Pacer B: Work Dispatch

WorkDispatchPacer starts from active assignment plans and current task work. It
does not do broad worker discovery. It may revalidate or finalize worker
score lease before claiming work.

Mainline:

```text
1. read active TaskWorkerAssignmentPlan entries
2. validate plan fence / expiry
3. point-read and revalidate task score is still dispatch-visible
4. renew selected worker active score lease with observed-score CAS; dirty or
   stale means discard plan and return to worker matching
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
assignment plan selected
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
assignment plan expires before work claim
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
- no assignment plan may outlive its fence/expiry as a hidden worker truth.

## Python Kernel First Cut

The first Python kernel should prove the two-pacer handoff without Redis,
background threads, or external queues:

```text
allocate_workers_once(task_range, task_limit, worker_limit)
  -> list[TaskWorkerAssignmentPlan]

dispatch_work_once(plan_limit)
  -> list[DeliverSeed]
```

Required collaborators:

```text
task_score.acquire_active_task_candidates(limit)
task_score.get_score_states(task_ids)
task_score.rewrite_same_band_time_millis(...)
task_score.rewrite_observed_same_band_suffix(...)
task_score.rewrite_score(...)
task_score.close_score(...)
task_score.release_observed_score_hold(...)

worker_score.acquire_hot_acquire_candidates(home_bucket_id, limit)
worker_score.rewrite_current_score(...)
worker_score.acquire_due_hot_score_lease(...)
worker_score.renew_active_hot_score_lease(...)
worker_score.release_score_hold(...)

for workerGroupId, taskCandidates in assignment_dispatch.partition_by_worker_group(tasks):
  workerCandidates = worker_score.acquire_hot_acquire_candidates(home_bucket_id, limit)
  workerIds = [workerId for workerId, observedWorkerScore in workerCandidates]
  observedWorkerScoreByWorkerId = {workerId: observedWorkerScore, ...}
  candidateConstraints = [(candidateId, constraints), ...]
  candidateMatches = worker_candidate_matcher.match_worker_candidates(
    workerGroupId,
    workerIds,
    candidateConstraints
  )
  for candidateId, matchedWorkerIds in candidateMatches:
    selectedWorkerId = assignment_dispatch.rank_candidates(
      candidateId,
      matchedWorkerIds,
      rankingPolicy
    )
    observedWorkerScore = observedWorkerScoreByWorkerId[selectedWorkerId]
    leaseResult = worker_score.acquire_due_hot_score_lease(
      home_bucket_id,
      selectedWorkerId,
      observedWorkerScore,
      workerScoreLeaseExpiresAt
    )
    if leaseResult is stale:
      classify stale and do not create an assignment plan
    if leaseResult is transitioned:
      create assignment plan with leaseResult.score as observedLeasedWorkerScore

assignment_plans.put(plan)
assignment_plans.acquire_due(limit)
assignment_plans.complete_or_expire(plan)

work_items.peek_claimable(task_id)
leaseRenewResult = worker_score.renew_active_hot_score_lease(
  home_bucket_id,
  selectedWorkerId,
  observedLeasedWorkerScore,
  nextWorkerScoreLeaseExpiresAt
)
if leaseRenewResult is stale:
  discard plan and re-enter worker matching
work_items.claim(task_id, worker_id)
if work claim fails after worker score lease:
  worker_score.release_score_hold(
    home_bucket_id,
    worker_id,
    leaseRenewResult.score,
    releaseTimeMillis
  )

transport.resolve_delivery(worker_id)
```

This handoff is still prose only. The next executable kernel slice should add a
minimal in-memory assignment-dispatch spec under `py_example` before result
routing grows new behavior.

Do not add background loops, external queues, or Redis in the first cut. Prove
the owner handoff first. The two pacers may run as two method calls in a
single-process executable spec; "pacer" means scheduling entry and cadence, not
necessarily a thread.

## Guardrails

- Do not collapse worker allocation and work dispatch into one implicit
  `schedule_once` path.
- Do not let WorkDispatchPacer perform broad task-score acquisition. It consumes
  assignment plans and point-validates task score before claim.
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
