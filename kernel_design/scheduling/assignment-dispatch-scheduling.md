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
  task scheduling candidate -> worker demand -> candidate worker plan

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
to an already selected/admitted worker.

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

WorkerDemand
  workerGroup requirement
  capability / event requirement
  worker match rules
  priority / ranking rules
  optional target worker constraint

WorkerCandidate
  workerId or workerResourceId
  observedWorkerScore
  optional workerReservationHandle
  workerReservationExpiresAt
  worker metadata snapshot
  validation / admission evidence

TaskWorkerAssignmentPlan
  taskId
  workerDemand
  candidate workers or selected worker
  observed task / worker fences
  optional workerReservationHandle
  workerReservationExpiresAt
  planEpoch
  expiresAt

ClaimableWorkEvidence
  taskId
  whether ready work appears claimable
  optional earliest retry / no-work evidence

TransportDeliveryPlan
  adapter id or adapter family
  delivery queue key for already selected worker
  transport-local delivery evidence
```

These are conceptual handoff shapes. The first Python kernel may implement them
as small dataclasses. They are not public API DTOs.

`TaskWorkerAssignmentPlan` is short-lived round evidence, not durable truth. It
must have a fence or expiry and must be safe to discard. Current truth remains
with task score-band, worker-runtime, work-item runtime, and transport.

Worker candidates inside a plan are leased candidates, not owned assignments.
The lease is intentionally short. If WorkDispatchPacer does not consume or
extend it before expiry, worker-runtime may release the candidate and the plan
must be treated as stale.

Worker candidate freshness is score-fenced, not version-fenced. A plan may keep
the complete `observedWorkerScore` and an opaque worker-runtime reservation or
admission handle. It must not require assignment-dispatch to understand worker
metadata versions, dirty bits, score suffixes, or capacity internals.

## Pacer A: Worker Allocation

WorkerAllocationPacer starts from task scheduling visibility and produces
candidate worker plans. It does not claim work and does not create deliver
seeds.

Mainline:

```text
1. choose task score scan range and allocation batch limit
2. acquire task candidates from task score-band
3. validate task candidate and policy snapshot
4. compile WorkerDemand from task policy and item/capability requirement
5. choose worker group / home bucket / resource universe
6. discover worker candidates
7. validate hard match rules
8. apply priority / ranking rules
9. optionally reserve/admit or produce a fenced candidate plan
10. classify evidence and request owner score rewrites for no-match,
    contention, or stale cases
11. publish TaskWorkerAssignmentPlan for the dispatch pacer
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

### Worker Demand

Worker demand is compiled from task-side policy and work/capability needs:

```text
workerGroupId / workerGroupIds
capability / eventCode requirement
targetWorkerId, if explicitly constrained
match rules
priority / ranking rules
placement / attribute constraints
```

Hard match rules filter the candidate universe. Priority rules rank only among
workers that already satisfy hard match rules. Priority must not make an
ineligible worker eligible.

### Candidate Discovery

First version discovery should be simple:

```text
homeBucketId = workerGroupId or owner-defined worker home bucket
worker_score.acquire_hot_acquire_candidates(homeBucketId, limit)
for each worker candidate:
  validate worker group / capability / match rules / metadata
  record complete observed worker score
  rank by priority rules
```

This mode may scan more workers than an indexed design, but it has one clear
truth source: the worker score bucket plus worker-runtime validation.

Optional later discovery modes:

```text
capability index
attribute / placement index
target-worker point lookup
precomputed demand bucket
```

These indexes are hints only. They must not own worker truth, score truth,
capacity truth, or transport reachability. Every candidate found through an
index must still be validated by worker-runtime against current score,
metadata, gates, and admission state.

### Capacity And Admission

`capacityLimit` is declaration/configuration metadata. Current capacity,
reservation, locks, and admission truth belong to worker-runtime.

WorkerAllocationPacer may use metadata for cheap ranking or filtering, but final
capacity admission must be performed by worker-runtime. If the allocation pacer
creates a reservation, that reservation must be fenced and either consumed by
WorkDispatchPacer or compensated on expiry/stale failure.

The worker reservation/admission fence includes:

```text
observedWorkerScore
optional workerReservationHandle
workerReservationExpiresAt
```

Worker-runtime owns the current scheduling metadata signature and
capacity/admission truth. Score `dirty` is not a worker-global state or version;
it is only a reservation-local stale hint embedded in the observed score.
Assignment-dispatch only keeps the full observed score and the opaque
reservation/admission handle returned by worker-runtime.
If worker group membership, capability declaration, match metadata, dispatch
gate, resource policy, or capacity declaration changes, worker-runtime decides
whether the existing reservation remains valid. The next admission renewal or
dispatch revalidation must read current worker-runtime evidence instead of
trusting cached worker metadata.

## Pacer B: Work Dispatch

WorkDispatchPacer starts from active assignment plans and current task work. It
does not do broad worker discovery. It may revalidate or finalize worker
admission before claiming work.

Mainline:

```text
1. read active TaskWorkerAssignmentPlan entries
2. validate plan fence / expiry
3. point-read and revalidate task score is still dispatch-visible
4. revalidate selected worker or candidate worker score fence, reservation, and
   admission are still valid
5. claim current work hash rows from the work-item owner
6. compensate worker admission if claim fails
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
admission, but the final claim must happen only when there is enough worker
admission evidence to avoid consuming work with no viable dispatch path.

Claim sequence:

```text
assignment plan selected
  -> worker reservation/admission finalized or revalidated
  -> work hash claim writes current occupancy
  -> deliver seed produced
```

If claim fails after worker admission:

```text
release / compensate worker admission
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
  transportAdapterId or adapterFamily
  deliveryQueueKey
  createdAt
```

`workerId` is the selected execution identity. `deliveryQueueKey` is transport
queue placement for already selected work. It must not become worker selection
truth.

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
worker reservation/admission revalidation fails after allocation
worker admitted but work claim fails
work claimed but transport delivery lane rejects
worker admission expires before deliver seed is accepted
```

Rules:

- every stale outcome must be bounded;
- every partial worker admission must have a compensation path;
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
worker_score.renew_current_lease(...)
worker_score.release_score_hold(...)

worker_runtime.validate_match(worker, demand)
worker_runtime.rank_candidates(candidates, demand)
worker_runtime.admit_worker(candidate, demand)
worker_runtime.revalidate_candidate(workerReservationHandle, observedWorkerScore)
worker_runtime.release_admission(admission)

assignment_plans.put(plan)
assignment_plans.acquire_due(limit)
assignment_plans.complete_or_expire(plan)

work_items.peek_claimable(task_id)
work_items.claim(task_id, worker_id)

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
- Do not claim work before worker admission unless the claim owner has an
  explicit reversible claim model.
- Do not create deliver seeds without a current work hash claim.
- Do not make result finality a dispatch concern.
- Do not store full worker objects or task objects inside deliver seeds.
