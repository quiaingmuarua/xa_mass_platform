# Task-Worker Allocation Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskWorkerAllocationPacer` builds bounded Worker candidates for Tasks that are
already in `RUNNING_VISIBLE`:

```text
RUNNING_VISIBLE Task scan
  -> load Task allocation descriptors
  -> observe and exact-lease due HOT Workers
  -> batch match Tasks and Workers
  -> publish expiring CandidateWorkerEntry values
  -> rotate considered RUNNING Task time coordinates
```

It does not decide whether a `PRE_DISPATCH_VISIBLE` Task may enter RUNNING.
That decision belongs to
[Task Running Activation Pacer](task-running-activation-pacer.md).

## Owner Boundary

The Pacer coordinates owner operations but owns no durable truth:

```text
TaskScoreBandCore
  supplies due RUNNING Task ids and same-band time rotation

TaskResourceCatalog
  supplies bounded TaskDescriptor values

WorkerScoreCore
  supplies due HOT observations and exact Worker leases

WorkerCandidateMatcher
  evaluates current bounded Worker facts against Task constraints

AssignmentDispatchRuntime
  stores expiring CandidateWorkerEntry handoff evidence
```

The Pacer must not read `PRE_DISPATCH_VISIBLE`, approve or activate Tasks,
inspect TaskItem truth, claim Items, submit transport work, or classify results.

## Inputs

```text
TaskScoreBandCore.acquire_band_task_candidates(
  RUNNING_VISIBLE,
  beforeTimeMillis,
  taskBatchLimit,
)

TaskResourceCatalog.load_task_allocation_descriptors(taskIds)

AssignmentDispatchRuntime.candidate_worker_counts(taskIds)

WorkerScoreCore.acquire_hot_acquire_candidates(workerGroupId, workerScanLimit)

WorkerScoreCore.acquire_observed_hot_score_leases(
  workerGroupId,
  observedScores,
  leaseUntilMillis,
)

WorkerCandidateMatcher.match_worker_candidates(
  workerGroupId,
  leasedWorkerIds,
  candidateConstraints,
)
```

All reads are bounded. `worker_scan_limit` bounds each WorkerGroup observation,
not the whole round across all groups.

## Constraint Construction

For each RUNNING Task with a valid descriptor:

```text
remainingCandidateTarget =
  maximumCandidateWorkers - currentNonExpiredCandidateCount

WorkerCandidateConstraint
  priority   = int(TaskDescriptor.config["priority"])
  limit      = max(0, remainingCandidateTarget)
  matchRules = TaskDescriptor.allocationRule
```

`maximumCandidateWorkers` is the Task-owned target used before matching. The
candidate runtime stores all supplied matches and does not trim or enforce this
target. Concurrent rounds may temporarily overshoot it; making it a hard cap
would require a separate permit owner.

Priority `100` is highest and `1` is lowest inside one matcher batch. The same
Task priority is also used by the default RUNNING admission system policy. It
is one Task scheduling intent, not two unrelated priority fields.

Missing or corrupt descriptors fail only that Task closed for the current
round. Descriptor loading is batch-only; allocation does not perform N point
reads or create a general Task query API.

## Worker Lease And Match

The Worker flow is fixed:

```text
scan due HOT Workers
  -> exact-score lease each observed Worker in one bounded batch
  -> retain only successful lease scores
  -> pass only Worker ids to the matcher
  -> combine matches with opaque lease scores after matching
```

The matcher has no score or lease dependency. The Pacer holds the
`workerId -> leaseScore` sidecar and creates:

```text
CandidateWorkerEntry
  workerId
  workerGroupId
  endpointManagerId
  workerLeaseScore
```

Every successfully leased Worker has participated in a scheduling round.
Unmatched Workers and publication failures are not immediately released; lease
expiry restores visibility and prevents immediate hot-loop reacquisition.

One WorkerId is one scheduler-visible execution slot. A physical executor with
parallel capacity exposes multiple logical WorkerIds. One CandidateWorkerEntry
therefore continues at most one TaskItem claim.

## Publication

Matched entries are appended to the Task-local candidate ZSET with one batch
expiry equal to the Worker lease deadline:

```text
ad:{prefix}:task:{taskId}:candidate-workers
```

Publication is atomic only for one Task call. A multi-Task round may publish
earlier Tasks before a later append fails. It does not roll back earlier
publication or release unpublished leases; score deadlines provide recovery.

Candidate entries are transient evidence. They do not prove that the Worker is
still HOT, clean, reachable, or constraint-compatible at Item dispatch time.
`TaskItemDispatchPacer` performs the declared exact Worker lease recheck before
claiming an Item.

## Task Score Rotation

After the allocation work, every scanned RUNNING Task is offered one
same-band time rotation:

```text
rewrite_same_band_time_millis(
  taskId,
  expectedBand=RUNNING_VISIBLE,
  targetTimeMillis=now,
)
```

The primitive preserves suffix and rejects a stale or changed band. Rotation is
fairness pacing for this allocation scan; it is not a lock, activation result,
retry-budget mutation, no-work classification, or terminal decision. Published
candidate evidence is not rolled back when rotation loses a stale fence.

## Interface

```python
TaskWorkerAllocationConfig(
    task_batch_limit,
    worker_scan_limit,
    worker_lease_duration_millis,
)

TaskWorkerAllocationPacer.allocate_candidate_workers(
    config=config,
) -> int
```

The return value is the number of Tasks for which at least one candidate batch
was published. It is not a Worker count or activation count.

## Failure And Stale Handling

```text
no RUNNING Task
  -> bounded no-op

missing descriptor or candidate target exhausted
  -> skip that Task; still offer RUNNING same-band rotation

Worker observation loses exact lease CAS
  -> omit that Worker from matching

Worker unmatched
  -> do not publish and do not release; lease expires

candidate append fails
  -> preserve earlier Task publications; remaining leases expire

Task band changes during the round
  -> same-band rotation returns stale; candidate handoff remains disposable
```

No path adds a cross-owner transaction or compensation queue.

## Deferred Policy

- Fairness beyond current Task score order and matcher priority is a replaceable
  system policy, not a kernel invariant.
- Worker scan limits, candidate target, and lease duration remain bounded policy
  values.
- Worker capacity estimates may inform a future admission policy, but must not
  pre-lease Workers while a Task is still `PRE_DISPATCH_VISIBLE`.
- Persisted assignment continuation requires a named owner protocol; candidate
  queues must not be promoted into that role.

## Guardrails

- Scan only `RUNNING_VISIBLE`; never allocate Workers for `PRE_DISPATCH_VISIBLE`.
- Do not hide Task activation inside allocation or candidate publication.
- Do not read TaskItem score or payload during Worker allocation.
- Do not publish a Worker before exact lease success.
- Do not pass opaque Worker scores into matcher rules.
- Do not release unmatched Workers as if no scheduling attempt occurred.
- Do not make candidate count a lifecycle, admission, capacity, or validity
  truth.
- Do not let `AssignmentDispatchRuntime` own Task candidate limits.
- Do not combine Worker allocation and TaskItem dispatch into one pacer.
