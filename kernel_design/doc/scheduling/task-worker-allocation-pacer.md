# Task-Worker Allocation Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskWorkerAllocationPacer` is a bounded cache warmer for stable Task-level
rules:

```text
RUNNING_VISIBLE Task scan
  -> load Task allocation descriptor
  -> retain taskType=TASK_DRIVEN
  -> measure CandidateId cache count
  -> acquire the deficit from the HOT pool
  -> append expiring candidate evidence
  -> same-band rotate considered RUNNING Tasks
```

It does not process PRE_DISPATCH Tasks, decide Task activation, or serve
Item-directed TARGETED requests.

## Dependencies

```text
TaskScoreBandCore
  due RUNNING scan and same-band rotation

TaskResourceCatalog
  bounded Task allocation descriptors

WorkerCandidateAcquirer.acquire_hot_pool_candidates
  bounded HOT scan, exact lease, and complete Task-rule match

CandidateWorkerCache
  expiring TaskId-local evidence
```

The Pacer does not depend directly on Worker score or matcher. Candidate
acquisition owns those mechanisms; cache publication remains Pacer-owned.

## Request Construction

For each descriptor-backed `taskType=TASK_DRIVEN` RUNNING Task:

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

Tasks with no descriptor or no deficit do not produce a request. Requests are
grouped by `descriptor.workerGroupId`; one acquisition call never crosses
Worker score queues.

`ITEM_DRIVEN` Tasks are excluded before cache count and request construction.
Item-directed rules are never warmed by this Pacer.

## Publication

The Pacer invokes the explicit HOT-pool acquisition once per WorkerGroup and
appends results independently:

```text
ad:{prefix}:candidate:{taskId}:workers
```

Cache expiry equals the Worker allocation lease deadline. Empty result tuples
are not written. Return value is the number of Task CandidateIds for which at
least one entry was appended.

Every exact-leased Worker is consumed scheduling evidence. Unmatched Workers
and append failures are not released; lease expiry restores HOT visibility.

## Task Score Rotation

Every scanned RUNNING Task is offered one same-band absolute-time rewrite,
including missing descriptors, `ITEM_DRIVEN` Tasks, full caches, empty Worker
scans, and no matches. Only `TASK_DRIVEN` can produce precomputed candidates;
rotation of the complete bounded scan prevents filtered rows from permanently
occupying the oldest window. The rewrite preserves Task band and suffix. A
stale rewrite does not roll back already published candidates.

## Configuration

```python
TaskWorkerAllocationConfig(
    task_batch_limit,
    worker_lease_duration_millis,
)
```

`worker_scan_limit` belongs to WorkerCandidateAcquirer construction, not to an
acquisition request.

## Guardrails

- Scan only due `RUNNING_VISIBLE` Tasks.
- Use `taskId` as the built-in stable CandidateId.
- Do not consume cache to satisfy the prefetch request.
- Keep HOT scan/lease/match inside `acquire_hot_pool_candidates`.
- Do not let CandidateWorkerCache own limits, rules, matching, or Worker truth.
- Do not warm or cache Item allocation rules.
- Do not reinterpret `TaskType` as independently configurable cache or
  acquisition flags.
- Do not release unmatched or failed-publication Worker leases.
- Do not change Task band or suffix.
