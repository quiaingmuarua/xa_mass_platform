# Assignment-Dispatch Scheduling

Status: new-kernel mechanism note. This document describes the target
assignment-dispatch scheduling plane for the clean kernel core. It is not
current implementation truth and not an implementation roadmap.

## Purpose

Assignment-dispatch scheduling turns one scheduling opportunity into concrete
dispatch seeds:

```text
schedulable task
  + admitted worker/resource
  + claimed work item
  + selected transport delivery lane
  -> deliver seed
```

It answers:

```text
which worker should receive which claimed work, through which delivery lane,
for this one scheduling round?
```

It does not answer:

```text
should the task be visible to scheduling?
is the worker generally schedulable?
is the result final?
is the transport session connected right now?
```

Those questions belong to task score-band, worker score-band, result routing,
and transport / adapter owners.

## Inputs

Assignment-dispatch consumes owner-approved inputs:

```text
TaskSchedulingCandidate
  taskId
  task score evidence
  task scheduling policy snapshot

WorkerDemand
  workerGroup requirement
  capability / event requirement
  worker filter rules
  priority / ranking rules
  optional target worker constraint

WorkerCandidate
  workerId or workerResourceId
  worker score evidence
  worker metadata snapshot
  admission/capacity evidence

ClaimableWorkEvidence
  taskId
  whether ready work appears claimable
  optional earliest retry / no-work evidence

TransportDeliveryPlan
  adapter id or adapter family
  delivery queue key for already selected worker
  transport-local delivery evidence
```

These are conceptual handoff shapes. The first Python kernel may implement
them as small dataclasses. They are not public API DTOs.

## Mainline

```text
1. receive a task candidate from task-score-band scheduling
2. compile worker demand from task policy and item/capability requirements
3. acquire candidate workers from worker-score-band scheduling
4. validate worker group, capability, filters, priority, and capacity
5. reserve/admit one or more workers for this scheduling round
6. claim current work hash rows from the work-item owner
7. compensate worker admission if claim fails
8. resolve transport delivery lane for the selected worker
9. emit deliver seed
10. classify round evidence for task/worker score rewrites
```

The deliver seed is the last object produced by scheduling. Transport receives
already selected work and executes delivery; transport must not choose a
different worker.

## Candidate Worker Discovery

The worker-discovery part of this plane applies these questions in order:

```text
is the task still schedulable?
which worker group or resource universe may compete?
which candidate workers are visible now?
which candidates satisfy worker-group / capability rules?
which candidates satisfy filter rules?
which candidate wins by priority/ranking?
can the winning worker be admitted now?
```

The plane may produce no dispatch seed. No-dispatch outcomes are normal
scheduling evidence, not errors.

Typical no-dispatch outcomes:

```text
NO_READY_WORK
NO_WORKER_GROUP_MATCH
NO_WORKER_CANDIDATE
WORKER_FILTER_REJECTED
WORKER_CAPACITY_CONTENDED
WORK_CLAIM_FAILED
TRANSPORT_DELIVERY_LANE_UNAVAILABLE
```

Each outcome must be routed back to the owning score/policy plane for bounded
future scheduling. Assignment-dispatch must not hide failures by spinning.

## Work Claim Boundary

Work claim belongs to the work-item owner.

Assignment-dispatch may ask for cheap claimable-work evidence before worker
admission, but the final claim must happen only when there is enough worker
admission evidence to avoid consuming work with no viable dispatch path.

Claim sequence:

```text
task candidate acquired
  -> worker candidate admitted
  -> work hash claim writes current occupancy
  -> deliver seed emitted
```

If claim fails after worker admission:

```text
release / compensate worker admission
classify scheduling round as no-ready or stale
rewrite task score through task-score owner
```

Do not make task score-band pop work. Do not make worker score-band claim work.

Claim is intentionally thin. It does not create an `Attempt`, a separate active
lease owner, or a scheduling id. It writes the current work hash row and
returns the same claim evidence inside the deliver seed.

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
NO_READY_WORK -> task score remove or future retry time
NO_WORKER_CANDIDATE -> task score future empty-match recheck
WORKER_CONTENDED -> task score future contention recheck
WORK_CLAIMED_MORE_REMAINS -> task score now or policy delay
```

Worker-side examples:

```text
ADMITTED -> worker future score / capacity interval
CAPACITY_FULL -> worker future score
FILTER_REJECTED_STALE_METADATA -> worker low-recheck or parked
DELIVERY_LANE_UNAVAILABLE -> worker low-recheck or transport evidence only,
depending on owner policy
```

## Non-Owners

Assignment-dispatch does not own:

```text
task lifecycle / task score truth
worker lifecycle / worker score truth
work-item persistence
result finality
transport session lifecycle
read models or diagnostics
```

It may observe these facts only through owner-approved handoff objects.

## Failure And Stale Handling

Stale candidates are expected:

```text
task score due but task gate closed
worker score due but worker metadata stale
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
  worker-selection facts.

## Python Kernel First Cut

The first Python kernel can keep this plane small:

```text
schedule_one(task_candidate) -> list[DeliverSeed]
```

Required collaborators:

```text
task_score.acquire_due()
worker_score.acquire_due(demand)
work_items.claim(task_id, worker_id)
transport.resolve_delivery(worker_id)
result_router.accept_later(...)
```

Do not add background loops, external queues, or Redis in the first cut. Prove
the owner handoff first.

## Guardrails

- Do not let assignment-dispatch refresh task score because append happened.
- Do not let assignment-dispatch select workers from transport sessions.
- Do not let transport choose backup workers.
- Do not claim work before worker admission unless the claim owner has an
  explicit reversible claim model.
- Do not create deliver seeds without a current work hash claim.
- Do not make result finality a dispatch concern.
- Do not store full worker objects or task objects inside deliver seeds.
