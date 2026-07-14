# Task Item Dispatch Pacer

Status: new-kernel mechanism note. This document defines the second mandatory
assignment-dispatch pacer. It is not current implementation truth and not an
implementation roadmap.

Parent contract: [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md).

## Purpose

`TaskItemDispatchPacer` consumes recently allocated Task-Worker reservations and
turns one reservation into one current Item-score claim plus one `DeliverSeed`.

It answers:

```text
can this RUNNING_VISIBLE Task dispatch one concrete TaskItem through one
currently reserved candidate Worker?
```

It does not discover broad Worker universes, evaluate Task start conditions,
re-run constraint DSL, or write Task score.

## Why It Is Independent

Task Item dispatch is a higher-frequency consumption path:

```text
recent Task score scan
non-expired candidate-worker atomic consume
Worker exact-score validity recheck and release/renew
current Item score claim
DeliverSeed creation
Worker lease release/hold
```

Its throughput, per-Task quota, Worker lease interval, Item claim batch, and
transport handoff pressure must be tunable independently from allocation.

## Inputs

```text
TaskScoreBandCore
  acquire_dispatch_work_tasks(limit)
  get_score_states(taskIds)
  read-only from this pacer's perspective

TaskDispatchRuntime
  per-Task live-count point-read and expiry-ordered atomic consume

WorkerScoreCore
  current score evidence and policy-selected lease primitives

TaskRuntime
  load bounded TaskItem records by taskId + messageIds

TaskItemScoreBandCore
  acquire bounded due ACTIVE Items
  return observedScore + remainingBudget
  exact-CAS positive-budget observations into future same-band claims
  promote exhausted observations to FINAL_FAILED

Transport ingress
  accepts DeliverSeed or returns bounded rejection evidence

DispatchPolicy
  taskBatchLimit
  allocationLookback
  perTaskDispatchLimit
  workerLeaseDuration
```

`TaskDispatchRuntime` provides a Redis ZSET per-Task candidate append/count/
consume implementation. `TaskRuntime` owns TaskItem records;
`TaskItemScoreBandCore` owns acquire, same-band rewrite, and outcome promotion.

## Task Discovery

Task Item dispatch uses Task score as its only Task discovery index. It does not add
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
rewrite. TaskItem dispatches per round are bounded by the pacer's per-Task quota.
Candidate entries stop being live at batch expiry and are removed by bounded
consume/cleanup; the Worker score lease follows its own owner coordinate.

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
missing / empty
  -> bounded no-op

available
  -> atomically consume one CandidateWorkerEntry
```

Multiple dispatch workers may process different Tasks or consume different
entries concurrently. Atomic entry consumption prevents one reservation entry
from producing two Worker lease renewals. No Task lock is required.

TaskWorkerAllocationPacer appends every successfully leased entry from its
bounded batch to the Task collection with one batch expiry. Runtime does not
trim to a Task policy limit. It removes expired entries on touched append,
batched count, and consume paths, but expiry proves only that the allocation
handoff window is still open. Current Worker score, dirty, and matching
attributes are checked after atomic consume.

The core consume interface is single Task:

```python
consume_candidate_workers(
    task_id=task_id,
    limit=per_task_dispatch_limit,
)
```

A concrete runtime may pipeline consumption for multiple Task ids, but that
wrapper is explicitly non-atomic across queues.

## Worker Short Lease

Each consumed entry carries:

```text
workerId
workerLeaseScore
```

The entry score is observation/fence input, not proof that the Worker remains
valid. Dispatch must first obtain current Worker owner evidence and revalidate
the Task's matching constraints. The exact operation then depends on dispatch
policy:

```text
exclusive Worker use
  -> renew the exact current HOT lease before TaskItem assignment

non-exclusive Worker use
  -> release the exact allocation lease after validation / assignment handoff
  -> make the Worker available for another candidate allocation
```

Outcomes:

```text
VALID
  Worker remains admissible under current score and metadata
  continue to Item score claim

STALE / DIRTY / INVALID
  discard candidate entry
  try another bounded entry or move to the next Task
```

TaskItemDispatchPacer must not decode, trim, or reconstruct Worker lease score by
itself. The dispatch-side Worker owner path must recheck current score/dirty and
matching attributes. That validation interface is not frozen in the executable
spec yet.

Candidate expiry and Worker validity are separate fences:

```text
candidate batch not expired
  != Worker still valid

stored Worker score == candidate.workerLeaseScore
and dirty == 0
and current constraints still match
  -> Worker validity accepted for this dispatch decision
```

## Item Score Claim

Item claim belongs to `TaskItemScoreBandCore`. TaskItemDispatchPacer loads
TaskItem records through `TaskRuntime`; it does not pop or move payload between
ready/current/retry structures.

```text
Worker candidate revalidated and required lease established
  -> acquire one bounded due ACTIVE Item observation for taskId
  -> remainingBudget == 0: promote FINAL_FAILED; do not dispatch
  -> remainingBudget > 0: load corresponding TaskItem through TaskRuntime
  -> exact-CAS observation to future ACTIVE claim with budget delta -1
  -> TRANSITIONED + score: create DeliverSeed
  -> other result: release/retain Worker short lease according to admission policy
```

`TaskRuntime` validates and returns the record. `TaskItemScoreBandCore` validates
the observed score. For positive remaining budget it creates occupancy through
`rewrite_observed_item_scores(..., remainingBudgetDelta=-1)`. For an exhausted
candidate the pacer requests `promote_item_outcomes(..., FINAL_FAILED, now)`.
Neither owner reads or mutates the other's key. Task score-band must not inspect
Item score, and Worker score must not mutate Item state.

Claim remains thin. It does not create a separate Attempt lifecycle or a second
scheduling id. The opaque returned `claimScore` is the same-tag result/retry
fence. Lease expiry naturally makes a non-final Item due again; there is no
claim-repair queue.

If no due Item can be claimed after candidate consumption, the candidate is
disposable. TaskItemDispatchPacer does not rewrite Task score to report no-work;
Task lifecycle policy may classify that fact in a later Task scheduling round.

## DeliverSeed

`DeliverSeed` is the scheduling-owned handoff to transport:

```text
DeliverSeed
  taskId
  messageId
  workerId
  workerGroupId
  eventCode
  payload or payloadRef
  claimScore
  claimLeaseUntilMillis
  createdAtMillis
```

It carries opaque claim evidence, not current truth. Current TaskItem record
truth remains in `TaskRuntime`; current Item scheduling truth remains in
`TaskItemScoreBandCore`. Transport and result callers store and return
`claimScore`; they never decode it.

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

TaskItemDispatchPacer never writes:

```text
Task timeSlot
Task suffix
Task band
Task terminal score
```

Successful dispatch consumes a Worker reservation and one TaskItem claim, not
Task-score fairness. More entries in the transient collection may dispatch more
TaskItems. When dispatch drains the collection, TaskWorkerAllocationPacer
eventually sees the Task in oldest-first allocation order and publishes fresh
candidates. Expired
entries are popped and discarded during that drain; they are not removed by an
append-side scan.

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

            worker_admission = recheck_worker_candidate(
                task_id=task_id,
                candidate=candidate,
            )
            if not worker_admission.valid:
                continue

            observations = item_score.acquire_item_score_candidates(
                task_id=task_id,
                limit=1,
            )
            exhausted_ids = tuple(
                message_id
                for message_id, (_, budget) in observations.items()
                if budget == 0
            )
            item_score.promote_item_outcomes(
                task_id=task_id,
                message_ids=exhausted_ids,
                target_band=FINAL_FAILED,
                target_time_millis=now_millis,
            )
            claimable = {
                message_id: observed_score
                for message_id, (observed_score, budget) in observations.items()
                if budget > 0
            }
            items = task_runtime.load_task_items(
                task_id=task_id,
                message_ids=tuple(claimable),
            )
            record_backed_scores = select_record_backed_scores(
                items,
                claimable,
            )
            claim_results = item_score.rewrite_observed_item_scores(
                task_id=task_id,
                observed_scores=record_backed_scores,
                target_time_millis=claim_lease_until_millis,
                remaining_budget_delta=-1,
            )
            claim = first_claimed_record(items, claim_results)
            if claim is None:
                compensate_worker_admission(worker_admission)
                break

            seed = build_deliver_seed(claim, candidate.worker_id)
            submit_or_compensate(seed, claim, worker_admission)
```

Names are conceptual and do not freeze Python interfaces.

Worker lease disposition belongs here, after candidate consumption and current
owner validation:

```text
non-exclusive scheduling/admission succeeds
  release the exact published Worker lease score so the Worker may compete again

exclusive scheduling/admission succeeds
  retain or renew the exact Worker lease according to admission policy
```

Allocation-stage unmatched, matcher failure, and candidate append failure do
not reach this owner and therefore do not release Worker score.

## Executable-Spec Gap

The current Python package has Worker short-lease primitives, Task score
acquire, candidate-worker DTOs, and the Redis `TaskDispatchRuntime`. It has
no Task Item score implementation, no DeliverSeed model, and no
TaskItemDispatchPacer. The
dispatch-task score query also requires the reverse bounded lookback behavior
defined above.

## Failure And Compensation

```text
candidate collection missing/empty
  bounded no-op; no Task score write

candidate Worker score, dirty, or attributes are stale
  discard entry; try another bounded entry

Worker admission succeeds, Item claim fails
  release/compensate Worker admission when policy requires it

Item claim succeeds, DeliverSeed construction fails
  leave Item claim future-held until bounded lease expiry;
  release/retain Worker admission according to policy

transport rejects seed
  record delivery evidence; Item claim remains future-held until expiry;
  invoke Worker disposition policy

Task pauses/closes during dispatch
  current owner validation or claim fence rejects further Item dispatch;
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
- Do not claim an Item before a current Worker short lease succeeds.
- Do not create DeliverSeed before current Item claim succeeds.
- Do not compensate Item claim through a second ready/retry structure; bounded
  claim expiry restores due visibility.
- Do not let transport choose another Worker.
- Do not retain candidate entries as durable assignment truth.
- Do not require a Task lock around concurrent dispatch consumers.
