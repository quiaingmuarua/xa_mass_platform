# TaskItem Dispatch Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskItemDispatchPacer` composes already-RUNNING Task evidence into assigned
delivery:

```text
RUNNING_VISIBLE Task
  -> observe due TaskItems
  -> acquire a Worker for each dispatchable Item policy
  -> exact claim Worker-backed Items
  -> build and append DeliverSeeds
```

It does not read candidate cache, call Worker score, activate Tasks, invoke
transport, or classify results. Those details remain behind the selected
`WorkerCandidateAcquirer` strategy.

It is the routine same-band time owner for considered `RUNNING_VISIBLE` Tasks.
Candidate warming never advances this coordinate.

## Contracts

```python
TaskItemDispatchConfig(
    task_batch_limit,
    per_task_dispatch_limit,
    item_claim_lease_duration_millis,
)
```

Dependencies:

```text
TaskScoreBandCore       dispatch-visible RUNNING Task ids
TaskResourceCatalog     bounded Task allocation descriptors
TaskItemScoreBandCore   Item observation and exact claim
TaskRuntime             canonical Item records
WorkerCandidateAcquirer PRECOMPUTED and TARGETED candidate acquisition
CandidateWarmupSchedule derived TASK_DRIVEN cache-replenishment hints
DeliverSeedRuntime      endpoint-manager handoff queues
```

There is no resolver callback. The Task descriptor fixes the rule owner and
therefore the acquisition strategy:

```text
taskType=TASK_DRIVEN
  -> PRECOMPUTED using Task allocationRule

taskType=ITEM_DRIVEN
  -> TARGETED using each TaskItem allocationRule
```

One Task cannot mix both paths. `workerGroupId` always comes from the Task
descriptor.

## Dispatch Round

One round computes `nowMillis` and `taskItemClaimUntilMillis` once:

1. Acquire a bounded RUNNING Task batch.
2. Batch-load descriptors in score-scan order; skip missing rows.
3. Observe bounded due Item scores.
4. Promote exhausted-budget Items to `FINAL_FAILED`.
5. Load canonical records for positive-budget observations; skip missing rows.
6. Select one Task-level path:

   ```text
   taskType=TASK_DRIVEN
     one PRECOMPUTED request
     CandidateId = taskId
     requestedCount = number of record-backed Items
     allocationRule = TaskDescriptor allocationRule

   taskType=ITEM_DRIVEN
     one TARGETED request per Item
     CandidateId = messageId
     requestedCount = 1
     allocationRule = TaskItem allocationRule
     targetField = workerId, otherwise first stable dynamic field
   ```

7. Call exactly the selected acquisition path. Neither path falls back to the
   other.
8. Preserve the returned `CandidateId -> messageId` binding. Do not flatten all
   Workers and Items into unrelated lists.
9. Exact-claim only Items that have a bound Worker, consuming one retry budget
   unit per attempted claim.
10. Build one DeliverSeed per successful `(Worker, Item)` assignment and append
    by `endpointManagerId`.
11. In a `finally` boundary, request one `RUNNING_VISIBLE` same-band absolute
    time rewrite for every descriptor-backed Task considered by the round.

Observation deliberately precedes Worker acquisition. Worker acquisition
deliberately precedes Item claim.

Every TASK_DRIVEN PRECOMPUTED acquisition schedules a candidate warmup hint,
including cache miss and partial results. Cache consumption itself creates a
future deficit, so replenishment is independent of whether this dispatch found
enough Workers. ITEM_DRIVEN never emits a warmup hint.

## Append-Time Rule Validation

`KernelApplication.append_task_items` validates an Item rule before TaskRuntime
writes it:

- `TASK_DRIVEN` forbids Item rules;
- `ITEM_DRIVEN` requires an Item rule;
- the rule is non-empty and valid constraint DSL;
- every field is declared by the selected WorkerGroup's
  `itemAllocationFields`;
- `workerId` uses only `$eq` or `$in` with non-empty string values;
- every `dynamic.<name>` field has a candidate-query handler supporting its
  operator rule.

TaskRuntime still validates JSON shape and DSL syntax without depending on the
Worker catalog. Zero-config assembly has no dynamic candidate index, so only
declared `workerId` Item rules are available by default.

## DeliverSeed Cutpoint

Each seed carries only:

```text
workerId
opaqueDeliveryItem
opaqueResultContext
taskItemClaimUntilMillis
```

`opaqueResultContext` preserves Task/Item correlation and the exact Worker
lease fence. `endpointManagerId` partitions the queue but is not duplicated in
the seed.

## Failure Semantics

- No Task, descriptor, Item, Worker, or claim success is a bounded no-op.
- PRECOMPUTED miss never triggers TARGETED acquisition.
- Missing index rows, stale/dirty/unavailable/expired Worker evidence, and
  rematch failure are filtered before Item claim.
- A failed Item claim does not restore or release its paired Worker lease.
- DeliverSeed append is fail-fast per endpoint queue; previous appends are not
  rolled back.
- Item claim and Worker lease deadlines provide recovery.
- A Task pause or terminal transition after discovery does not retract the
  bounded round already in progress.
- A stale Task same-band rewrite loses to the newer Task score and does not
  roll back Item/Worker/DeliverSeed work already completed.

## Guardrails

- Do not access `CandidateWorkerCache` or `WorkerScoreCore` from this Pacer.
- Do not claim Items before candidate acquisition succeeds.
- Do not infer Task type from each Item or mix Task and Item rules.
- Do not accept acquisition strategy, rule owner, or cache mode from callers.
- Do not expose candidate-source topology to the matcher; bind Items from the
  matcher result by CandidateId.
- Do not add PRECOMPUTED-miss TARGETED fallback or TARGETED cache writes.
- Do not write Task lifecycle bands or suffix. This Pacer may only request the
  declared `RUNNING_VISIBLE` same-band absolute-time rewrite after considering
  a Task.
- Do not release or demote Worker leases; result-routing owns evidence-based
  disposition.
- Do not call transport directly.
