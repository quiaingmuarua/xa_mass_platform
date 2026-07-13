# Work Dispatch Pacer

Status: new-kernel mechanism note. This document defines the second mandatory
assignment-dispatch pacer. It is not current implementation truth and not an
implementation roadmap.

Parent contract: [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md).

## Purpose

`WorkDispatchPacer` consumes recently allocated Task-Worker candidate evidence
and turns one candidate into one current Work claim plus one `DeliverSeed`.

It answers:

```text
can this RUNNING_VISIBLE Task dispatch one concrete Work item through one
currently leasable candidate Worker?
```

It does not discover broad Worker universes, evaluate Task start conditions,
re-run constraint DSL, or write Task score.

## Why It Is Independent

Work dispatch is a higher-frequency consumption path:

```text
recent Task score scan
candidate-worker atomic consume
Worker observed-score CAS lease
current Work claim
DeliverSeed creation
Worker lease release/hold/compensation
```

Its throughput, per-Task quota, Worker lease interval, Work claim batch, and
transport handoff pressure must be tunable independently from allocation.

## Inputs

```text
TaskScoreBandCore
  acquire_dispatch_work_tasks(limit)
  get_score_states(taskIds)
  read-only from this pacer's perspective

TaskCandidateWorkerCollectionRuntime
  point-read / atomic consume / bounded discard

WorkerScoreCore
  acquire_due_hot_score_lease
  release_score_hold or later Worker policy rewrite

TaskWorkRuntime
  claim one current Work item
  compensate/release claim when delivery handoff fails

Transport ingress
  accepts DeliverSeed or returns bounded rejection evidence

DispatchPolicy
  taskBatchLimit
  allocationLookback
  perTaskDispatchLimit
  workerLeaseDuration
```

The candidate collection and TaskWorkRuntime executable interfaces are not yet
implemented.

## Task Discovery

Work dispatch uses Task score as its only Task discovery index. It does not add
an allocation queue or global candidate-list index.

It scans only due `RUNNING_VISIBLE` Tasks from current scheduling time backward:

```text
max = current RUNNING_VISIBLE timeSlot
min = current timeSlot - allocationLookback
order = descending score
limit = taskBatchLimit
```

The public score interface may continue to expose only `limit`; current time,
lookback, band bounds, and reverse scan encoding stay internal to Task score or
the configured pacer assembly.

Reverse order intentionally prefers fresh allocation evidence. Allocation
fairness is owned by TaskWorkerAllocationPacer's oldest-first scan and timeSlot
rewrite. Dispatch fairness is bounded by per-Task quota and finite candidate
collection size.

The current Redis executable implementation of
`acquire_dispatch_work_tasks(limit)` scans the full due RUNNING range
oldest-first. That is a known implementation gap. It must not be cited as proof
of this pacer's recent-allocation reverse scan.

`PRE_DISPATCH_VISIBLE`, `PRE_REVIEW`, paused/future non-due Tasks, and
`TERMINAL` never enter this pacer.

## Candidate Consumption

For each discovered Task:

```text
point-read candidate-worker collection
missing / empty / expired
  -> bounded no-op

available
  -> atomically consume one CandidateWorkerEntry
```

Multiple dispatch workers may process different Tasks or consume different
entries concurrently. Atomic entry consumption prevents one candidate entry
from producing two Worker lease attempts. No Task lock is required.

The collection is replaceable by TaskWorkerAllocationPacer. A concurrent replace
may discard unused entries; that is allowed because they are transient evidence.

## Worker Short Lease

Each consumed entry carries:

```text
workerId
completeObservedWorkerScore
```

Dispatch attempts:

```text
worker_score.acquire_due_hot_score_lease(
  workerId,
  completeObservedWorkerScore,
  leaseUntilMillis,
)
```

Outcomes:

```text
TRANSITIONED
  Worker was still HOT, due, and exact-score current
  continue to Work claim

STALE / INVALID
  discard candidate entry
  try another bounded entry or move to the next Task
```

WorkDispatchPacer must not decode, trim, or reconstruct observed Worker score.
It does not re-run matcher constraints before lease; dirty/exact-score rules in
Worker runtime provide the current stale fence.

## Work Claim

Work claim belongs to `TaskWorkRuntime`.

```text
Worker short lease acquired
  -> claim one current Work item for taskId + workerId
  -> claim succeeds: create DeliverSeed
  -> claim fails: release/compensate Worker short lease
```

The claim owner validates current Work truth and creates current occupancy. Task
score-band must not pop backlog, and Worker score must not mutate Work state.

Claim remains thin. It does not create a separate Attempt lifecycle or a second
scheduling id. Time-bounded Work may carry `claimExpiresAtMillis`; non-time-
bounded Work relies on result/cancel/manual owner policy.

If Work disappears between allocation and dispatch, the consumed candidate is
disposable. WorkDispatchPacer does not rewrite Task score to report no-work;
TaskWorkerAllocationPacer later classifies current backlog evidence through the
normal Task-score fairness loop.

## DeliverSeed

`DeliverSeed` is the scheduling-owned handoff to transport:

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
  createdAtMillis
```

It carries claim evidence, not current truth. Current Work truth remains in the
Work owner.

Transport-specific identifiers do not belong in the seed:

```text
adapterId
sessionId
connectionId
mailboxKey
routeKey
deliveryQueueKey
```

Transport receives the already selected `workerId`, resolves its own final-hop
delivery evidence, and may accept or reject delivery. It must not choose another
Worker.

## Task Score Is Read-Only

WorkDispatchPacer never writes:

```text
Task timeSlot
Task suffix
Task band
Task terminal score
```

Successful dispatch consumes candidate-worker and Work capacity, not Task-score
fairness. More entries in the bounded collection may dispatch more Work. When
the collection empties or ages out, TaskWorkerAllocationPacer eventually sees
the Task in oldest-first allocation order and publishes fresh candidates.

Claim failure, stale Worker entry, or transport rejection also does not grant
this pacer generic Task-score write authority. Those outcomes update their own
owner truth or compensation path; allocation later observes current facts.

## Conceptual Round

```python
def dispatch_once(task_batch_limit, per_task_dispatch_limit):
    task_ids = task_score.acquire_dispatch_work_tasks(
        limit=task_batch_limit,
    )

    for task_id in task_ids:
        for _ in range(per_task_dispatch_limit):
            candidate = candidate_collections.consume_one(task_id=task_id)
            if candidate is None:
                break

            worker_lease = worker_score.acquire_due_hot_score_lease(
                worker_id=candidate.worker_id,
                observed_score=candidate.observed_worker_score,
                target_time_millis=worker_lease_until(),
            )
            if not worker_lease.transitioned:
                continue

            claim = task_work.claim_one(
                task_id=task_id,
                worker_id=candidate.worker_id,
            )
            if not claim.claimed:
                compensate_worker_lease(worker_lease)
                break

            seed = build_deliver_seed(claim, candidate.worker_id)
            submit_or_compensate(seed, claim, worker_lease)
```

Names are conceptual and do not freeze Python interfaces.

## Executable-Spec Gap

The current Python package has Worker short-lease primitives and Task score
acquire, but it has no candidate-worker collection runtime, no Work claim owner,
no DeliverSeed model, and no WorkDispatchPacer. The dispatch-task score query
also requires the reverse bounded lookback behavior defined above.

## Failure And Compensation

```text
candidate collection missing/empty
  bounded no-op; no Task score write

candidate Worker score stale
  discard entry; try another bounded entry

Worker lease succeeds, Work claim fails
  release/compensate Worker lease

Work claim succeeds, DeliverSeed construction fails
  compensate current Work claim and Worker lease

transport rejects seed
  record delivery evidence and invoke Work/Worker compensation policy

Task pauses/closes during dispatch
  current owner validation or claim fence rejects further Work;
  future scans exclude the Task
```

No failure path may select a replacement Worker through transport identifiers or
rewrite Task score as a generic side effect.

## Guardrails

- Do not process `PRE_DISPATCH_VISIBLE` in this pacer.
- Do not discover Worker groups or broad Worker candidates here.
- Do not re-run `WorkerCandidateMatcher` or constraint DSL here.
- Do not write Task score for success, no-work, stale Worker, claim failure, or
  transport rejection.
- Do not consume candidate entries without an atomic owner-local primitive.
- Do not claim Work before a current Worker short lease succeeds.
- Do not create DeliverSeed before current Work claim succeeds.
- Do not let transport choose another Worker.
- Do not retain candidate entries as durable assignment truth.
- Do not require a Task lock around concurrent dispatch consumers.
