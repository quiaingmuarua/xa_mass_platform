# TaskItem Dispatch Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskItemDispatchPacer` composes already-RUNNING Task evidence into assigned
delivery:

```text
RUNNING_VISIBLE Task
  -> observe due TaskItems
  -> resolve Worker candidate acquisition policy
  -> acquire already-leased Worker candidates
  -> exact claim TaskItems
  -> build and append DeliverSeeds
```

It does not discover Workers, read candidate cache, call Worker score, activate
Tasks, invoke transport, or classify results.

## Contracts

```python
TaskItemDispatchConfig(
    task_batch_limit,
    per_task_dispatch_limit,
    item_claim_lease_duration_millis,
)
```

The Pacer depends on:

```text
TaskScoreBandCore             dispatch-visible RUNNING Task ids
TaskResourceCatalog           bounded allocation descriptors
TaskItemScoreBandCore         Item observation and exact claim
TaskRuntime                   canonical Item records
WorkerCandidateAcquirerResolver
DeliverSeedRuntime
```

Resolver input is the current `TaskDescriptor` and bounded existing
`TaskItem` records. It returns one `WorkerCandidateAcquirer` implementation.
The Pacer invokes exactly that implementation; it has no fallback path.

## Dispatch Round

One round computes `nowMillis` and `taskItemClaimUntilMillis` once, then:

1. Acquire a bounded RUNNING Task batch.
2. Batch-load Task descriptors; skip missing descriptors.
3. Observe bounded due Item scores.
4. Promote exhausted-budget Items to `FINAL_FAILED`.
5. Load canonical records for positive-budget observations; skip missing rows.
6. Build the current Task-level request:

   ```text
   CandidateId = taskId
   priority = descriptor priority
   requestedCount = record-backed claimable Item count
   matchRules = descriptor allocationRule
   ```

7. Resolve and call one candidate acquirer with
   `workerGroupId = descriptor.workerGroupId` and the Item claim deadline as
   the required Worker lease deadline.
8. Exact-claim at most the number of returned Workers, consuming one retry
   budget unit per attempted Item claim.
9. Pair successful claims and Workers in their stable returned order.
10. Encode one DeliverSeed per pair and append by `endpointManagerId`.

Item observation deliberately precedes Worker acquisition. Observation does
not mutate the Item lease; if no Worker is returned, no Item is claimed.

## Candidate Acquisition

Zero-config assembly resolves the current stable Task-level request to
`CachedWorkerCandidateAcquirer`. That implementation consumes the TaskId cache,
exact-validates or renews Worker lease evidence, and rematches current rules.

An Item-directed policy may later resolve to
`RealtimeWorkerCandidateAcquirer` and submit multiple CandidateIds. The
acquisition contract already supports that shape, but Item-level request
construction and binding policy are deferred.

Cache miss is a bounded no-op. TaskItem dispatch never retries through another
acquirer implementation in the same round.

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
- Stale/dirty/offline/expired Worker evidence is filtered by the selected
  acquirer before Item claim.
- A failed Item claim does not restore or release its paired Worker lease.
- DeliverSeed append is fail-fast per endpoint queue; previous queue appends
  are not rolled back.
- Item claim and Worker lease deadlines provide recovery.
- A Task pause or terminal transition after discovery does not retract the
  bounded round already in progress.

## Guardrails

- Do not access `CandidateWorkerCache` or `WorkerScoreCore` from this Pacer.
- Do not claim Items before candidate acquisition succeeds.
- Do not add cache-miss realtime fallback.
- Do not introduce a global requested count across CandidateIds.
- Do not interpret OR relationships between requests.
- Do not rewrite Task score.
- Do not release or demote Worker leases; result-routing owns evidence-based
  disposition.
- Do not call transport directly.
