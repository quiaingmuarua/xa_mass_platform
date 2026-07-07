# Assignment-Dispatch Scheduling

Status: new-kernel mechanism note. This document describes the target
assignment-dispatch scheduling plane for the clean kernel core. It is not
current implementation truth and not an implementation roadmap.

## Purpose

Assignment-dispatch scheduling turns one scheduling opportunity into concrete
deliver seeds:

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
1. choose task score scan range and batch limit
2. acquire task candidates from task-score-band query(range, limit)
3. compile worker demand from task policy and item/capability requirements
4. acquire candidate workers from worker-score-band scheduling
5. validate worker group, capability, filters, priority, and capacity
6. admit/hold one or more workers for this scheduling round
7. claim current work hash rows from the work-item owner
8. compensate worker admission if claim fails
9. resolve transport delivery lane for the selected worker
10. produce deliver seed
11. classify round evidence and request task/worker score rewrites
```

The deliver seed is the last object produced by scheduling. Transport receives
already selected work and executes delivery; transport must not choose a
different worker.

Assignment-dispatch is the running-state pacer. Task score-band does not run a
separate timer, recheck job, or pagination cursor for running tasks;
assignment-dispatch consumes bounded task-score queries and writes score-owner
evidence after a bounded round:

```text
query task score range + limit per active band
  -> default order: RUNNING_VISIBLE, EMPTY_RUNNING, READY_APPROVED
  -> classify every successfully acquired candidate

RUNNING_VISIBLE scan
  -> scheduling evaluates runnable task facts
  -> claim work only after worker admission evidence is sufficient
  -> produce deliver seed when work and worker admission both succeed
  -> rewrite to RUNNING_VISIBLE or EMPTY_RUNNING

deliver seed produced
  -> running policy rewrites task score for next dispatch opportunity

no ready work
  -> task score becomes EMPTY_RUNNING with idle close deadline

EMPTY_RUNNING scan
  -> if backlog / retry / dispatchable work appears, continue toward dispatch
  -> if still empty before idleCloseEpochSecond, keep score unchanged or
     same-band lease-rewrite by policy
  -> if still empty at or after idleCloseEpochSecond, close through task
     score/lifecycle owner

READY_APPROVED scan
  -> scheduling evaluates pre-running open facts
  -> if ready, rewrite to RUNNING_VISIBLE
  -> if not ready before readyDeadlineEpochSecond, keep score unchanged or
     same-band lease-rewrite by policy
  -> if not ready at or after readyDeadlineEpochSecond, close to TERMINAL
  -> do not produce a deliver seed in READY_APPROVED
```

No task-score pagination is required. Assignment-dispatch owns scan range,
ordering, quota, horizon, and idle backoff. A pre-deadline `READY_APPROVED` or
`EMPTY_RUNNING` candidate may be classified as no-op and either keep the same
score or same-band lease-rewrite to a later epoch/suffix by policy; that is
bounded idle fallback work when no higher-priority running consumption is
available. Dispatch-visible `RUNNING_VISIBLE` candidates must still be moved,
rewritten, closed, cleaned, or rejected by expected-score protection.
Assignment-dispatch must not collapse active task acquisition into one broad
score range that reaches negative terminal markers or inactive bands.

Append, result notification, trace, and read projection do not refresh task
score directly. Later transport delivery evidence also does not refresh task
score directly; the score rewrite belongs to the assignment-dispatch round that
successfully produced the deliver seed or classified a no-dispatch outcome.
Activation fact updates behave the same way: they update owner truth, while the
due scheduling round decides whether `READY_APPROVED` becomes
`RUNNING_VISIBLE`.

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

The plane may produce no deliver seed. No-dispatch outcomes are normal
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
  -> deliver seed produced
```

If claim fails after worker admission:

```text
release / compensate worker admission
classify scheduling round as no-ready or stale
rewrite task score through task-score owner
```

Do not make task score-band pop work. Do not make worker score-band claim work.

Claim is intentionally thin. It does not create an `Attempt`, a separate
timeout owner, or a scheduling id. It writes the current work hash row and
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
ACTIVATION_WAIT_NOT_EXPIRED -> keep score or same-band lease rewrite
ACTIVATION_DEADLINE_EXPIRED -> TERMINAL
ACTIVATION_READY -> RUNNING_VISIBLE due score
EMPTY_WAIT_NOT_EXPIRED -> keep score or same-band lease rewrite
EMPTY_DEADLINE_EXPIRED -> TERMINAL
NO_READY_WORK -> EMPTY_RUNNING with idle close deadline, or RUNNING_VISIBLE delayed by scheduled retry evidence
NO_WORKER_CANDIDATE -> RUNNING_VISIBLE with future no-worker-match recheck
WORKER_CONTENDED -> RUNNING_VISIBLE with future contention recheck
WORK_CLAIMED_MORE_REMAINS -> RUNNING_VISIBLE at now or policy delay
WORK_CLAIMED_NO_READY_REMAINS -> EMPTY_RUNNING with idle close deadline unless scheduled retry evidence keeps RUNNING_VISIBLE delayed
PAUSE_OR_BLOCK -> same active band with future or far-future epochSecond
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
schedule_once(range, limit) -> list[DeliverSeed]
```

Required collaborators:

```text
task_score.query(range, limit)
task_score.try_update(expected_score, next_score)
worker_score.acquire_due(demand)
work_items.claim(task_id, worker_id)
transport.resolve_delivery(worker_id)
result_router.accept_later(...)
```

Do not add background loops, external queues, or Redis in the first cut. Prove
the owner handoff first.

## Guardrails

- Do not let assignment-dispatch refresh task score because append happened.
- Do not keep a task-score pagination cursor; use bounded range + limit queries
  and consume dispatch-visible candidates by score rewrite, band move, close,
  cleanup, or expected-score failure. Pre-deadline `READY_APPROVED` and
  `EMPTY_RUNNING` false checks may no-op as bounded idle fallback after
  higher-priority running consumption.
- Do not let assignment-dispatch select workers from transport sessions.
- Do not let transport choose backup workers.
- Do not claim work before worker admission unless the claim owner has an
  explicit reversible claim model.
- Do not create deliver seeds without a current work hash claim.
- Do not make result finality a dispatch concern.
- Do not store full worker objects or task objects inside deliver seeds.
