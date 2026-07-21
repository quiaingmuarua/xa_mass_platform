# Task Dispatch Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskDispatchPacer` owns one bounded round over due `RUNNING_VISIBLE` Tasks. It
has two lanes selected only by the current Task score suffix:

```text
suffix = 0
  -> ordinary TaskItem dispatch

suffix > 0
  -> ACTIVE Item existence recheck
```

The suffix is the consecutive empty recheck count. It is not Item retry budget,
Worker capacity, fairness, or a remaining countdown.

## Contract

```python
TaskDispatchConfig(
    task_batch_limit,
    per_task_dispatch_limit,
    item_claim_lease_duration_millis,
    max_empty_recheck_times,
    empty_recheck_interval_millis,
)
```

`max_empty_recheck_times` is in `1..99`. Both empty-recheck settings are
assembly-installed System Policy constants; they are not Task metadata or
public JSON fields.

Dependencies:

```text
TaskScoreBandCore       RUNNING discovery, suffix CAS, pacing, and close
TaskResourceCatalog     bounded Task allocation descriptors
TaskItemScoreBandCore   due Item observation, ACTIVE existence, exact claim
TaskRuntime             canonical Item records
WorkerCandidateAcquirer PRECOMPUTED or TARGETED Worker acquisition
CandidateWarmupSchedule TASK_DRIVEN replenishment hints
DeliverSeedRuntime      endpoint-manager handoff queues
```

The Pacer does not read CandidateWorker cache or Worker score directly. Those
details remain behind `WorkerCandidateAcquirer`.

## Round Flow

One round computes its dispatch time and Item claim deadline once:

1. Acquire a bounded due `RUNNING_VISIBLE` Task batch.
2. Batch-load current score states and Task descriptors.
3. For `suffix > 0`, skip Worker acquisition and Item claim and add the Task to
   the activity-check batch.
4. For `suffix = 0`, observe record-backed due ACTIVE Items.
5. Promote observed zero-budget Items to `FINAL_FAILED`.
6. If no dispatchable Item remains, add the Task to the activity-check batch.
7. Otherwise acquire Workers, exact-claim only Worker-backed Items, build
   `DeliverSeed` values, publish by endpoint manager, and preserve suffix zero
   while advancing ordinary dispatch time.
8. Call `has_active_items` once for the activity-check batch and apply the
   recheck transition rules below.

Observation precedes Worker acquisition. Worker acquisition precedes Item
claim. Item claim precedes DeliverSeed publication.

## ACTIVE Item Truth

`has_active_items(taskIds)` checks the complete Task-local ACTIVE score band:

```text
due ACTIVE Item             -> true
future claim/retry Item     -> true
ACTIVE Item with budget 0   -> true
FINAL Item                  -> false
missing Item score          -> false
```

This query does not load Item payload. Therefore a Task is empty only when no
ACTIVE score exists, not merely when no Item is currently due.

## Empty Recheck Transitions

All suffix changes use `rewrite_observed_same_band_suffix` with the exact score
observed for this round. The target time must advance.

```text
ACTIVE exists, suffix = 0
  -> keep suffix 0
  -> ordinary same-band time pacing

ACTIVE exists, suffix > 0
  -> suffixDelta = -currentSuffix
  -> reset suffix to 0
  -> do not dispatch in the reset round

no ACTIVE, suffix = 0
  -> suffix = 1
  -> nextTime = now + 1 * interval

no ACTIVE, 0 < suffix < max
  -> suffix = suffix + 1
  -> nextTime = now + suffix * interval

no ACTIVE, suffix = max, TASK_DRIVEN
  -> close Task to TERMINAL

no ACTIVE, suffix = max, ITEM_DRIVEN
  -> remain RUNNING at max suffix
  -> nextTime = now + max * interval
```

The delay is linear. Periodic scanning is the correctness fallback; a future
append hint may accelerate recheck but is not required for liveness.

`TASK_DRIVEN` treats repeated confirmed emptiness as automatic completion
evidence. `ITEM_DRIVEN` never closes from emptiness because a later Item may be
appended. An explicit close command can close either Task type at any time.

## Ordinary Dispatch

The immutable Task type selects the internal Worker path:

```text
TASK_DRIVEN
  -> one TaskId-correlated PRECOMPUTED request
  -> Task allocationRule

ITEM_DRIVEN
  -> one messageId-correlated TARGETED request per Item
  -> TaskItem allocationRule
```

Neither path falls back to the other. CandidateId-to-messageId binding is
preserved through exact Item claim; Workers and Items are not flattened and
re-zipped.

Each successful assignment produces one `DeliverSeed` containing:

```text
workerId
opaqueDeliveryItem
opaqueResultContext
taskItemClaimUntilMillis
```

## Failure And Concurrency

- Missing Task, descriptor, Item, Worker, or claim success is a bounded no-op.
- A stale suffix CAS cannot overwrite pause, close, or a newer recheck.
- A pause or close after discovery does not retract evidence already published
  by the bounded round.
- Unused or failed Worker leases expire naturally; this Pacer does not release
  or demote them.
- DeliverSeed append is fail-fast per endpoint queue; earlier queue writes are
  not rolled back.
- Explicit terminal close has precedence. Existing Items, claims, seeds, or
  late results are not rolled back and cannot reopen Task score.

## Guardrails

- Do not interpret suffix as remaining budget.
- Do not dispatch `suffix > 0` Tasks in the same round that resets suffix.
- Do not classify future ACTIVE Items as empty.
- Do not acquire Workers during the empty-recheck lane.
- Do not access CandidateWorker cache or Worker score directly.
- Do not add PRECOMPUTED-miss TARGETED fallback.
- Do not expose empty-recheck constants through TaskDescriptor or public JSON.
- Do not call transport or result-routing directly.
