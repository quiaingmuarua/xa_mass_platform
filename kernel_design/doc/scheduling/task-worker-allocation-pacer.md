# Task-Worker Allocation Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskWorkerAllocationPacer` is a bounded cache warmer for stable Task-level
rules. It consumes disposable warmup hints rather than acquiring or leasing a
Task:

```text
due candidate-warmup TaskId
  -> batch-read current Task score state
  -> retain RUNNING_VISIBLE and non-hard-paused Tasks
  -> load Task allocation descriptor
  -> retain taskType=TASK_DRIVEN
  -> measure CandidateId cache count
  -> acquire the deficit from the HOT pool
  -> append expiring candidate evidence
  -> requeue an incomplete warmup hint
```

It uses Task score only as a bounded read-only eligibility truth. It does not
acquire, lease, rotate, or rewrite Task score, process PRE_DISPATCH Tasks,
decide Task activation, or serve Item-directed TARGETED requests.

## Warmup Schedule

`CandidateWarmupSchedule` is owner-local, derived evidence:

```text
schedule_candidate_warmups(taskIds, dueTimeMillis)
consume_due_candidate_warmups(beforeTimeMillis, limit)
```

Redis uses one deduplicating ZSET:

```text
ad:{prefix}:candidate-warmups
  member = taskId
  score  = dueTimeMillis
```

The schedule is not Task truth, dispatch truth, or a replacement ready index.
It may be lost or consumed more than once. Worker exact-score lease acquisition
keeps duplicate warming safe, and later PRECOMPUTED cache consumption emits a
new hint when evidence must be replenished.

Hints are produced by:

- a successful `TASK_DRIVEN` transition into `RUNNING_VISIBLE`;
- a `TASK_DRIVEN` dispatch round after it consumes or misses PRECOMPUTED
  candidates;
- an incomplete warmer round, which requeues the Task for the next configured
  warmer cadence.

`ITEM_DRIVEN` never enters this schedule.

## Dependencies

```text
CandidateWarmupSchedule
  bounded due TaskId hints

TaskResourceCatalog
  bounded Task allocation descriptors

TaskScoreBandCore
  bounded point-state read for RUNNING/non-hard-pause validation only

WorkerCandidateAcquirer.acquire_hot_pool_candidates
  bounded HOT scan, exact lease, and complete Task-rule match

CandidateWorkerCache
  expiring TaskId-local evidence
```

The Pacer does not mutate Task score and does not depend directly on Worker
score or matcher. Candidate acquisition owns Worker mechanisms; cache
publication remains Pacer-owned.

## Request Construction

For each descriptor-backed `taskType=TASK_DRIVEN` hint:

```text
candidateId = taskId
requestedCount =
  maximumCandidateWorkers - currentNonExpiredCandidateCount

WorkerCandidateRequest
  priority = int(config["priority"])
  requestedCount = positive deficit
  allocationRule = descriptor.allocationRule
  targetField = None
```

Tasks with no descriptor, the wrong TaskType, or no deficit do not produce a
request. Requests are grouped by `descriptor.workerGroupId`; one acquisition
call never crosses Worker score queues.

## Publication And Retry

The Pacer appends acquired results under:

```text
ad:{prefix}:candidate:{taskId}:workers
```

Cache expiry equals the Worker allocation lease deadline. Empty result tuples
are not written. Return value is the number of Task CandidateIds for which at
least one entry was appended.

If the acquired count is below the requested deficit, the Pacer writes another
currently-due warmup hint. The background-loop interval supplies retry cadence;
there is no second retry-delay configuration.

Every exact-leased Worker is consumed scheduling evidence. Unmatched Workers
and append failures are not released; lease expiry restores HOT visibility.

## Configuration

```python
TaskWorkerAllocationConfig(
    task_batch_limit,
    worker_lease_duration_millis,
)
```

The Task RUNNING soft limit bounds normal warmup cardinality. `task_batch_limit`
is still a safety bound for one round, not a policy that selects only a subset
of RUNNING Tasks forever. `worker_scan_limit` belongs to
WorkerCandidateAcquirer construction.

## Guardrails

- Use `taskId` as the built-in stable CandidateId.
- Do not use Task score as the warmup cursor. A bounded state read may reject
  stale, non-RUNNING, or hard-paused hints; no Task-score write is allowed.
- Do not consume cache to satisfy the prefetch request.
- Keep HOT scan/lease/match inside `acquire_hot_pool_candidates`.
- Do not let CandidateWorkerCache own limits, rules, matching, or Worker truth.
- Do not warm or cache Item allocation rules.
- Do not reinterpret `TaskType` as independently configurable cache or
  acquisition flags.
- Do not release unmatched or failed-publication Worker leases.
- Do not change Task band or suffix.
