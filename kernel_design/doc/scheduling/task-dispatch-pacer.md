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
TaskDispatchPacer
  TaskScoreBandCore       RUNNING discovery, suffix CAS, pacing, and close
  TaskResourceCatalog     bounded Task allocation descriptors
  TaskItemScoreBandCore   ACTIVE existence query for empty recheck
  CandidateWarmupSchedule TASK_DRIVEN reset hints
  TaskItemDispatcher      one suffix-zero Task's Item dispatch
  WorkerCommandRuntime    round-level Adapter mailbox publication

TaskItemDispatcher
  TaskItemScoreBandCore   due Item observation, expiry/finality, exact claim
  TaskRuntime             canonical Item records
  WorkerCandidateAcquirer PRECOMPUTED or DIRECT Worker acquisition
  CandidateWarmupSchedule TASK_DRIVEN replenishment hints
  delivery item encoder   opaque Worker command payload
```

`TaskItemDispatcher` has no background lifecycle and does not scan Tasks,
rewrite Task score, publish mailboxes, or perform empty recheck. The Pacer does
not read CandidateWorker cache or Worker score directly. Those details remain
behind `WorkerCandidateAcquirer`.

## Round Flow

One round computes its dispatch time and Item claim deadline once:

1. Acquire a bounded due `RUNNING_VISIBLE` Task batch.
2. Batch-load current score states and Task descriptors.
3. For `suffix > 0`, skip Worker acquisition and Item claim and add the Task to
   the activity-check batch.
4. For `suffix = 0`, ask `TaskItemDispatcher` to observe record-backed due
   ACTIVE Items.
5. Promote observed zero-budget Items and Items whose persisted
   `expireAtMillis <= roundNowMillis` to `FINAL_FAILED`.
6. If no dispatchable Item remains, add the Task to the activity-check batch.
7. Otherwise `TaskItemDispatcher` acquires Workers, exact-claims only
   Worker-backed Items, constructs one DeliveryCommand per assignment, and
   groups commands by the CandidateWorker route snapshot.
8. The Pacer merges all returned groups and publishes once per endpoint manager
   sparse mailbox while preserving suffix zero and advancing ordinary dispatch
   time.
   `APPENDED` and `REPLACED` both count as publication. `REPLACED` means the
   caller's lease-backed Seed replaced an existing mailbox field; it does not
   prove that the replaced field came from an older lease.
9. Call `has_active_items` once for the activity-check batch and apply the
   recheck transition rules below.

Observation precedes Worker acquisition. Worker acquisition precedes Item
claim. Item claim precedes command identity generation and mailbox publication.
Expiry classification therefore cannot acquire a Worker, consume retry budget,
or emit a Worker command.
An already-claimed attempt is not revoked when its Item later expires.

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
  -> if TASK_DRIVEN reset transitions, emit a best-effort warmup hint

no ACTIVE, suffix = 0
  -> suffix = 1
  -> nextTime = now + 1 * interval

no ACTIVE, 0 < suffix < max
  -> suffix = suffix + 1
  -> nextTime = now + suffix * interval

no ACTIVE, suffix = max
  -> now >= emptyCloseAtMillis: close Task to TERMINAL
  -> otherwise remain RUNNING at max suffix
  -> nextTime = min(emptyCloseAtMillis, now + max * interval)
```

The delay is linear. Periodic scanning is the correctness fallback; a future
append hint may accelerate recheck but is not required for liveness.

`emptyCloseAtMillis` is a shared empty-close threshold, not a hard deadline.
Task dispatch consults it only after the complete ACTIVE band is empty and the
maximum consecutive-empty count has been reached. `TASK_DRIVEN` defaults to
zero; `ITEM_DRIVEN` defaults to Task creation time plus three days. Either type
may override the threshold, and an external owner may submit stronger business
evidence through the explicit close command at any time.

## Ordinary Dispatch

The immutable Task type selects the internal Worker path:

```text
TASK_DRIVEN
  -> one TaskId-correlated PRECOMPUTED request
  -> Task allocationRule

ITEM_DRIVEN
  -> one messageId-correlated DIRECT request per Item
  -> TaskItem allocationRule
```

Neither path falls back to the other. CandidateId-to-messageId binding is
preserved through exact Item claim; Workers and Items are not flattened and
re-zipped.

Each successful assignment produces one `DeliveryCommand`:

```text
messageId             canonical UUID generated after exact Item claim
src                   TASK
dst                   WORKER
messageType           TaskItem.eventCode
executeBeforeMillis   the same Item claim deadline used by this round
payload               deterministic TaskItem payload JSON
forward               encoded ResultContext
```

`workerId` is the mailbox field and transport route; it is not duplicated in
the command. `endpointManagerId` selects the mailbox bucket and is not part of
the command.

Task Dispatch ends when each Adapter mailbox append result is handled. It does
not consume the mailbox, call a Worker, decode a Worker result, or append a
`DeliveryReport`; those operations belong to Worker Delivery Dispatch.

## Failure And Concurrency

- Missing Task, descriptor, Item, Worker, or claim success is a bounded no-op.
- A stale suffix CAS cannot overwrite pause, close, or a newer recheck.
- A pause or close after discovery does not retract evidence already published
  by the bounded round.
- Unused or failed Worker leases expire naturally; this Pacer does not release
  or demote them.
- The round sends one `workerId -> DeliveryCommand` Map per endpoint
  manager to `WorkerCommandRuntime`. One WorkerId may appear only once in the
  entire round.
  `APPENDED` and `REPLACED` count as publication. Replacement is a best-effort
  last mailbox write, not an attempt-order fence; stale delivery remains
  bounded by `executeBeforeMillis` and score-owner fences.
- Cross-Adapter mailbox writes are not atomic. A runtime failure does not roll
  back Adapter buckets whose append already completed.
- Explicit terminal close has precedence. Existing Items, claims, seeds, or
  late results are not rolled back and cannot reopen Task score.

## Guardrails

- Do not interpret suffix as remaining budget.
- Do not dispatch `suffix > 0` Tasks in the same round that resets suffix.
- Do not classify future ACTIVE Items as empty.
- Do not acquire Workers during the empty-recheck lane.
- Do not interpret TaskType as an empty-close policy.
- Do not access CandidateWorker cache or Worker score directly.
- Do not add PRECOMPUTED-miss DIRECT fallback.
- Do not expose the max empty count or recheck interval through TaskDescriptor.
- Do not call Worker Delivery Dispatch or Result Routing directly.
- Group only by the endpointManagerId snapshot in `CandidateWorkerEntry`; do
  not read live Adapter, connection, or session state.
