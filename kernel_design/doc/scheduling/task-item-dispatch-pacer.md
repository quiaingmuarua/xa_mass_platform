# Task Item Dispatch Pacer

Status: new-kernel mechanism note. This document defines the second mandatory
assignment-dispatch pacer. It is not current implementation truth and not an
implementation roadmap.

Parent contract: [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md).
The next owner after this pacer is
[DeliverSeed Outbound Delivery](deliver-seed-outbound-delivery.md).

## Purpose

`TaskItemDispatchPacer` turns two bounded scheduling inputs into queued
`DeliverSeed` values:

```text
Task-local CandidateWorkerEntry consumption
  + due TaskItem score acquisition and claim
  -> endpointManagerId-partitioned DeliverSeed queue append
```

Its cutpoint is successful DeliverSeed queue append. It does not consume the
DeliverSeed again, call transport, resolve adapters, validate or renew Worker
score, or decide whether an accepted dispatch releases or retains the Worker.

The word `dispatch` in this document means assignment-side TaskItem dispatch
planning. It does not mean final-hop transport delivery.

## Inputs

```text
TaskScoreBandCore
  acquire_dispatch_work_tasks(limit)
  read-only RUNNING_VISIBLE Task discovery

AssignmentDispatchRuntime
  atomically consume one Task's bounded CandidateWorkerEntry batch
  append one bounded DeliverSeed batch to one endpointManagerId queue

TaskItemScoreBandCore
  acquire due ACTIVE Item observations
  exact-CAS positive-budget observations into future ACTIVE claims
  promote selected exhausted observations to FINAL_FAILED

TaskRuntime
  load bounded canonical TaskItem records

TaskItemDispatchConfig
  taskBatchLimit
  perTaskDispatchLimit
  itemClaimLeaseDurationMillis
```

There is deliberately no `WorkerScoreCore`, Worker matcher, transport ingress,
adapter resolver, or result owner in this pacer.

## Task Discovery

Task Item dispatch uses Task score as its only Task discovery index:

```text
acquire_dispatch_work_tasks(limit=taskBatchLimit)
  -> due RUNNING_VISIBLE Task ids
```

`PRE_DISPATCH_VISIBLE`, `PRE_REVIEW`, future-held Tasks, and `TERMINAL` do not
enter this pacer. The public caller supplies only `limit`; Task score owns
band bounds, current-time capture, score ordering, and range construction.

The intended dispatch scan prefers recently rotated RUNNING evidence. The
current Redis implementation still scans the due RUNNING range oldest-first;
that is an implementation gap inside the existing score method, not permission
to add another Task discovery index or expose score ranges to the pacer.

Task discovery is a round-admission decision, not a Task lock. A Task that is
paused or closed after it was returned may finish this already-started bounded
round. The design intentionally accepts that in-flight race; it does not add a
second Task score read, rollback, or cross-key fence.

## Candidate Worker Consumption

For each discovered Task:

```text
consume_candidate_workers(
  taskId,
  limit=perTaskDispatchLimit
)
```

The runtime atomically removes at most the requested number of non-expired
entries from that one Task collection. Multiple pacers may consume different
entries concurrently without a Task lock. One candidate entry is returned to
at most one consumer.

Each entry already carries allocation-owned opaque evidence:

```text
CandidateWorkerEntry
  workerId
  workerGroupId
  endpointManagerId
  workerLeaseScore
```

This pacer does not interpret or validate `workerLeaseScore`. It copies the
opaque value into the generated DeliverSeed. The outbound owner later decides
whether the Worker evidence is still usable.

Consumed candidates are not restored when no Item is claimable or queue append
fails. Their Worker leases remain bounded and recover through Worker score
time semantics.

## Item Acquisition And Claim

The pacer requests at most one Item candidate per consumed Worker:

```text
acquire_item_score_candidates(
  taskId,
  limit=len(candidateWorkers)
)
  -> messageId -> (observedScore, remainingBudget)
```

Acquire is read-only. It cannot directly produce DeliverSeed because concurrent
pacer rounds may observe the same Item. A DeliverSeed requires an exact
same-band claim first:

```text
remainingBudget > 0
  -> load the canonical TaskItem record
  -> rewrite_observed_item_scores(
       taskId,
       recordBackedObservedScores,
       claimLeaseUntilMillis,
       remainingBudgetDelta=-1
     )
  -> only TRANSITIONED + claimScore may produce DeliverSeed

remainingBudget == 0
  -> promote_item_outcomes(
       taskId,
       exhaustedMessageIds,
       FINAL_FAILED,
       nowMillis
     )
  -> no DeliverSeed
```

Missing or corrupt TaskItem records are skipped. The pacer does not invent a
payload, create another Work model, or infer a terminal reason from missing
record truth. A successful Item claim is future ACTIVE score placement; queue
append failure needs no retry queue or compensating score write because claim
expiry restores acquire visibility.

## DeliverSeed

`DeliverSeed` is the assignment-side handoff produced by this pacer:

```text
DeliverSeed
  taskId
  selectedWorkerId
  workerGroupId
  endpointManagerId
  taskItem
  claimScore
  workerLeaseScore
```

`taskItem` is the canonical stable `TaskItem` value loaded from `TaskRuntime`.
The seed does not mirror its event code, payload, payload reference, priority,
creation time, or expiry into a second Item-shaped DTO.

`claimScore` and `workerLeaseScore` are opaque owner fences. This pacer stores
and forwards them but never decodes, trims, compares, or reconstructs either
score.

`endpointManagerId` is copied from the consumed `CandidateWorkerEntry` and is
the Deliver Queue partition key. The pacer does not choose or recompute it:

```text
deliverQueue[endpointManagerId].append(DeliverSeed)
```

Live transport identifiers are forbidden:

```text
adapterId
sessionId
connectionId
mailboxKey
routeKey
deliveryQueueKey
```

`endpointManagerId` is the explicit exception because it identifies the
physical endpoint-manager queue, not a live adapter/session/connection handle.

Transport receives an already selected Worker only after the queued seed
crosses into the outbound owner.

## DeliverSeed Queue

The minimal queue operation is batch-first:

```text
append_deliver_seeds(
  endpointManagerId,
  deliverSeeds: Sequence[DeliverSeed]
)
```

The Redis executable spec writes one LIST per endpoint manager:

```text
ad:{prefix}:endpoint-manager:{endpointManagerId}:deliver-seeds
```

One call writes exactly one endpoint-manager queue. The pacer may aggregate
seeds from multiple Tasks in the current bounded round before appending. Calls
for different endpoint managers succeed or fail independently; the interface
does not imply cross-queue atomicity.

The queue has one semantic role: handoff of already claimed Items to outbound
delivery. It is not Item truth, Worker truth, result truth, or a second claim
owner. Candidate collections and DeliverSeed queues share
`AssignmentDispatchRuntime` ownership but remain independent structures.

The queue does not promise cross-key atomicity with Item score or candidate
consumption. Its correctness fallback is the existing score timing:

```text
candidate consumed, no seed appended
  -> Worker lease eventually becomes due

Item claimed, seed append fails or process stops
  -> Item claim eventually becomes due
```

No background repair scanner is required.

## Task Score Is Read-Only

This pacer never writes:

```text
Task timeSlot
Task suffix
Task band
Task terminal score
```

Candidate absence, no due Item, stale Item claim, missing record, or seed queue
failure does not grant Task-score write authority. Task scheduling policy may
classify later evidence in another bounded round.

## Conceptual Round

```python
def dispatch_task_items(config):
    now_millis = current_time_millis()
    claim_lease_until_millis = (
        now_millis + config.item_claim_lease_duration_millis
    )
    task_ids = task_score.acquire_dispatch_work_tasks(
        limit=config.task_batch_limit,
    )
    pending_seeds_by_endpoint_manager = {}
    generated_seed_count = 0

    for task_id in task_ids:
        candidate_workers = dispatch_runtime.consume_candidate_workers(
            task_id=task_id,
            limit=config.per_task_dispatch_limit,
        )
        if not candidate_workers:
            continue

        observations = item_score.acquire_item_score_candidates(
            task_id=task_id,
            limit=len(candidate_workers),
        )

        exhausted_message_ids = tuple(
            message_id
            for message_id, (_, budget) in observations.items()
            if budget == 0
        )
        if exhausted_message_ids:
            item_score.promote_item_outcomes(
                task_id=task_id,
                message_ids=exhausted_message_ids,
                target_band=FINAL_FAILED,
                target_time_millis=now_millis,
            )

        claimable_observed_scores = {
            message_id: observed_score
            for message_id, (observed_score, budget) in observations.items()
            if budget > 0
        }
        if not claimable_observed_scores:
            continue

        items = task_runtime.load_task_items(
            task_id=task_id,
            message_ids=tuple(claimable_observed_scores),
        )
        record_backed_scores = {
            message_id: observed_score
            for message_id, observed_score in claimable_observed_scores.items()
            if items.get(message_id) is not None
        }
        if not record_backed_scores:
            continue

        claim_results = item_score.rewrite_observed_item_scores(
            task_id=task_id,
            observed_scores=record_backed_scores,
            target_time_millis=claim_lease_until_millis,
            remaining_budget_delta=-1,
        )

        claimed_items = []
        for message_id in record_backed_scores:
            result = claim_results.get(message_id)
            if result is None:
                continue
            if result.status != TRANSITIONED or result.score is None:
                continue
            claimed_items.append((items[message_id], result.score))

        for candidate_worker, (task_item, claim_score) in zip(
            candidate_workers,
            claimed_items,
        ):
            seed = DeliverSeed(
                task_id=task_id,
                selected_worker_id=candidate_worker.worker_id,
                worker_group_id=candidate_worker.worker_group_id,
                endpoint_manager_id=candidate_worker.endpoint_manager_id,
                task_item=task_item,
                claim_score=claim_score,
                worker_lease_score=candidate_worker.worker_lease_score,
            )
            pending_seeds_by_endpoint_manager.setdefault(
                candidate_worker.endpoint_manager_id,
                [],
            ).append(seed)

    for endpoint_manager_id, deliver_seeds in (
        pending_seeds_by_endpoint_manager.items()
    ):
        assignment_dispatch_runtime.append_deliver_seeds(
            endpoint_manager_id=endpoint_manager_id,
            deliver_seeds=tuple(deliver_seeds),
        )
        generated_seed_count += len(deliver_seeds)

    return generated_seed_count
```

`zip` intentionally stops at the smaller successful side. A consumed candidate
without a claimed Item is not restored; a successfully claimed Item without a
paired candidate cannot occur because Item acquire is bounded by the consumed
candidate count. Queue append is fail-fast per endpoint manager: previously
appended manager batches remain published, while failed and unattempted claims
recover through their score leases.

## Executable-Spec Gap

The Python executable spec already provides:

```text
TaskScoreBandCore.acquire_dispatch_work_tasks
AssignmentDispatchRuntime with Redis candidate ZSET and DeliverSeed LIST
TaskRuntime bounded TaskItem load
TaskItemScoreBandCore and Redis Item score implementation
DeliverSeed model and AssignmentDispatchRuntime owner surface
TaskItemDispatchConfig and TaskItemDispatchPacer
unit proof plus real Redis orchestration proof
```

It does not yet provide:

```text
DeliverSeed outbound consumer
recent-first Redis implementation for acquire_dispatch_work_tasks
```

The current implementation proof stops at queued DeliverSeed creation. It does
not call transport or execute Worker release/renew policy merely to claim an
end-to-end delivery demo.

## Failure Semantics

```text
candidate collection missing or empty
  bounded no-op

no due Item
  consumed candidates are not restored; Worker leases expire naturally

Item observation loses exact claim CAS
  no DeliverSeed for that Item

TaskItem record missing or corrupt
  no DeliverSeed; no inferred final transition

Item claim succeeds, seed construction or queue append fails
  no compensation; Item claim and Worker lease expire naturally

Task pauses or closes after scan
  this already-started bounded round may still append a seed
```

## Guardrails

- Do not process `PRE_DISPATCH_VISIBLE` in this pacer.
- Do not discover Worker groups or broad Worker candidates here.
- Do not invoke `WorkerCandidateMatcher` or read Worker metadata here.
- Do not call `WorkerScoreCore` from this pacer.
- Do not validate, renew, release, or retain Worker leases here.
- Do not call transport or resolve adapters here.
- Do not decide exclusive versus non-exclusive Worker disposition here.
- Do not write Task score for success, no-work, claim failure, or queue failure.
- Do not consume candidate entries without an owner-local atomic primitive.
- Do not create DeliverSeed before current Item claim succeeds.
- Do not decode Item or Worker scores.
- Do not create a second Work, Attempt, retry queue, or claim-expiry owner.
- Do not require a Task lock or post-scan Task score recheck.
