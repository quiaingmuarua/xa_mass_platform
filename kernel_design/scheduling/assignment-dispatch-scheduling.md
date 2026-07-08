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
  -> default order: RUNNING_VISIBLE, PRE_DISPATCH_VISIBLE
  -> classify every successfully acquired candidate

RUNNING_VISIBLE scan
  -> scheduling evaluates runnable task facts
  -> claim work only after worker admission evidence is sufficient
  -> produce deliver seed when work and worker admission both succeed
  -> rewrite to RUNNING_VISIBLE or TERMINAL according to round classification

deliver seed produced
  -> running policy rewrites task score for next dispatch opportunity

no ready work
  -> if suffix > 00, keep RUNNING_VISIBLE with next no-work recheck epoch and
     suffix-1
  -> if suffix == 00, close through task score/lifecycle owner

PRE_DISPATCH_VISIBLE scan
  -> scheduling evaluates pre-running open facts
  -> if ready, rewrite to RUNNING_VISIBLE
  -> if not ready and suffix > 00, rewrite PRE_DISPATCH_VISIBLE with next epoch and
     suffix-1
  -> if not ready and suffix == 00, write PRE_DISPATCH_VISIBLE pause/hold score
  -> do not produce a deliver seed in PRE_DISPATCH_VISIBLE
```

Assignment-dispatch must treat lifecycle direction and hold/recheck direction as
separate checks:

```text
cross-band lifecycle movement goes to a lower tag / terminal score
same-band suppression, retry, and hold write a later epochSecond
release/resume may lower epochSecond only with exact observedHoldScore
```

Do not validate a requested task score with one global `nextScore < currentScore`
or `nextScore > currentScore` rule. Decode tag, epochSecond, and suffix, then
apply the transition direction rule and write-time stale fence.

No task-score pagination is required. Assignment-dispatch owns scan range,
ordering, quota, horizon, and no-work/backoff policy. A `PRE_DISPATCH_VISIBLE` candidate that
remains in the same band must be consumed by writing a later epoch with `suffix
- 1`, by executing the exhausted action, or by losing the write-time stale
fence.
Dispatch-visible `RUNNING_VISIBLE` candidates, including no-work
classifications, must still be moved, rewritten, held, cleaned, closed, or
rejected by the score-write fence.
Assignment-dispatch must not collapse active task acquisition into one broad
positive score range. Negative terminal markers are final and immutable;
`PRE_REVIEW` is positive but inactive. Active acquisition must use the explicit
`RUNNING_VISIBLE` / `PRE_DISPATCH_VISIBLE` tag allow-list.

Append, result notification, trace, and read projection do not refresh task
score directly. Later transport delivery evidence also does not refresh task
score directly; the score rewrite belongs to the assignment-dispatch round that
successfully produced the deliver seed or classified a no-dispatch outcome.
Activation fact updates behave the same way: they update owner truth, while the
due scheduling round decides whether `PRE_DISPATCH_VISIBLE` becomes
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
ACTIVATION_STILL_WAITING and suffix > 00 -> PRE_DISPATCH_VISIBLE with next epoch and suffix-1
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
PAUSE_OR_BLOCK -> same active band with future epochSecond; hard pause uses 9_999_999_999
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
task_score.rewrite_same_band_epoch(
  task_id,
  expected_band,
  target_epoch_second
)
task_score.rewrite_observed_same_band_suffix(
  task_id,
  observed_score,
  target_epoch_second,
  suffix_delta
)
task_score.rewrite(
  task_id,
  expected_band,
  target_epoch_second,
  target_band?,
  target_suffix?
)
task_score.close(task_id, terminal_score)
task_score.release_observed_score_hold(task_id, observed_hold_score, release_epoch_second)
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
  cleanup, or stale-fence failure. `PRE_DISPATCH_VISIBLE` same-band false checks
  and `RUNNING_VISIBLE` no-work/no-worker/contention classifications must
  consume suffix budget or execute the exhausted action after higher-priority
  running consumption.
- Do not let assignment-dispatch select workers from transport sessions.
- Do not let transport choose backup workers.
- Do not claim work before worker admission unless the claim owner has an
  explicit reversible claim model.
- Do not create deliver seeds without a current work hash claim.
- Do not make result finality a dispatch concern.
- Do not store full worker objects or task objects inside deliver seeds.
