# TaskItem Dispatch Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Parent contracts:
[Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md),
[Task Item Score-Band Scheduling](task-item-score-band-scheduling.md), and
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).

## Purpose

This is the second independent assignment-dispatch pacer:

```text
RUNNING_VISIBLE Task
  -> consume Task-local CandidateWorkerEntry values
  -> exact validate/renew active Worker leases
  -> claim no more TaskItems than valid Workers
  -> pair Worker and Item in stable order
  -> append DeliverSeed by endpointManagerId
```

It does not discover Workers, rematch constraints, update Task score, call
transport, process results, or own Worker online classification.

## Contracts

```python
TaskItemDispatchConfig(
    task_batch_limit: int,
    per_task_dispatch_limit: int,
    item_claim_lease_duration_millis: int,
)

TaskItemDispatchPacer.dispatch_task_items(config) -> int
```

The return value counts DeliverSeeds successfully appended. All limits and the
claim duration must be positive.

`CandidateWorkerEntry` carries:

```text
workerId
workerGroupId
endpointManagerId
workerLeaseScore    # opaque allocation fence
```

## Dispatch Round

One round reads `nowMillis` once and defines:

```text
taskItemClaimUntilMillis
  = nowMillis + itemClaimLeaseDurationMillis
```

Per Task:

```text
1. consume at most perTaskDispatchLimit candidate Workers
2. group candidates by workerGroupId
3. renew_active_hot_score_leases using taskItemClaimUntilMillis
4. retain only TRANSITIONED / NOOP results carrying a current score
5. acquire no more Item observations than retained Workers
6. promote zero-budget observations to FINAL_FAILED
7. load TaskItem records and discard missing records
8. exact-claim remaining Items with remainingBudgetDelta=-1
9. pair successful Item claims with validated Workers in stable order
10. encode ResultContext using the validated/renewed Worker fence
11. append endpoint-manager batches before continuing to the next Task
```

Worker validation must happen before Item claim. A dirty, offline, expired, or
stale Worker candidate cannot consume an Item claim in this round.

`renew_active_hot_score_leases` owns the opaque comparison:

```text
storedScore != observedScore -> STALE
polarity != HOT_ACQUIRE      -> rejected
dirty == 1                   -> STALE
lease expired                -> STALE
lease already covers target -> exact NOOP + current fence
lease needs extension        -> exact CAS + renewed fence
```

The pacer never decodes a Worker score and never clears dirty.

## Item Claim And Pairing

TaskItem score remains the claim truth. Missing records and lost Item CAS do not
produce DeliverSeeds. Pairing follows the order of validated candidates and
successful Item observations; `zip` stops at the smaller side.

The built-in delivery envelope contains only `eventCode` and `payload`.
`opaqueResultContext` carries Task/Item correlation plus both opaque score
fences. `endpointManagerId` partitions the queue and is not included in the
DeliverSeed itself.

## Failure Semantics

```text
no RUNNING Task or no candidate
  -> bounded no-op

all Worker validations rejected
  -> no Item acquire or claim

some Worker validations rejected
  -> claim only for remaining Workers

no due Item / missing Item record / lost Item CAS
  -> no DeliverSeed for that position

Item claimed but seed append fails
  -> no compensation; Item claim and Worker lease expire

Worker validated but not paired or not published
  -> no active release; Worker lease expires

Task pauses or closes after Task scan
  -> this bounded round may still publish already-started work
```

The pacer does not release Worker leases. Trusted `200/1xxx` result evidence
releases the exact fence; trusted `3xxx` adapter rejection moves that exact
fence to offline polarity. Missing results recover through time.

## Task Score And Queue Boundaries

Task score is read-only in this pacer. Candidate absence, stale Worker, no Item,
claim failure, or queue failure grants no Task-score write authority.

`AssignmentDispatchRuntime` owns Task-local candidate queues.
`DeliverSeedRuntime` owns endpoint-manager queues. Neither queue is TaskItem or
Worker truth, and neither promises cross-key atomicity.

## Executable-Spec Status And Deferred Policy

Implemented:

```text
RUNNING_VISIBLE Task acquisition
candidate consume
dispatch-time active clean Worker exact validation/renewal
dirty/offline/stale rejection before Item claim
TaskItem acquire/load/exact claim
validated Worker fence propagation
DeliverSeed endpoint-manager append
unit and real Redis proof
```

Deferred policy is limited to recent-first Task acquisition and future explicit
capacity/concurrency policy. It must not create an early-release branch inside
this pacer without a separate owner invariant.

## Guardrails

- Do not process `PRE_DISPATCH_VISIBLE` here.
- Do not discover or match Workers here.
- Do not decode Worker or Item scores.
- Do not claim Items before Worker lease validation.
- Do not restore consumed candidates or compensate ambiguous queue writes.
- Do not release Worker leases from this pacer.
- Do not call transport or parse result outcomes here.
- Do not write Task score.
- Do not create Attempt, reservation lifecycle, retry queue, or repair scanner.
