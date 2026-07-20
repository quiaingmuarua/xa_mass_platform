# Task-Worker Allocation Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskWorkerAllocationPacer` is a bounded cache warmer for stable Task-level
Worker requests:

```text
RUNNING_VISIBLE Task scan
  -> load Task allocation descriptor
  -> measure current CandidateId cache count
  -> realtime acquire the deficit
  -> append expiring candidate evidence
  -> same-band rotate considered RUNNING Tasks
```

It is not the Worker acquisition interface used by TaskItem dispatch. It does
not process PRE_DISPATCH Tasks and does not decide Task activation.

## Dependencies

```text
TaskScoreBandCore
  due RUNNING scan and same-band rotation

TaskResourceCatalog
  bounded Task allocation descriptors

WorkerCandidateAcquirer(strategy=REALTIME)
  due HOT scan, exact lease and match

CandidateWorkerCache
  expiring CandidateId-local evidence
```

The Pacer does not depend directly on `WorkerScoreCore` or
`WorkerCandidateMatcher`; realtime acquisition owns that mechanism.

## Request Construction

For each descriptor-backed RUNNING Task:

```text
candidateId = taskId
requestedCount =
  maximumCandidateWorkers - currentNonExpiredCandidateCount

WorkerCandidateRequest
  priority = int(config["priority"])
  requestedCount = positive deficit
  matchRules = descriptor.allocationRule
```

Tasks with no descriptor or no deficit do not produce a request. There is no
round-global requested count; every Task request owns its own target.
Requests are grouped by `descriptor.workerGroupId` before acquisition.

The current Task-level allocation rule is stable and cacheable. Future
Item-directed rules are not warmed by this Pacer; they use realtime acquisition
at dispatch.

## Publication

The Pacer calls the shared `WorkerCandidateAcquirer` with explicit `REALTIME`
strategy once per WorkerGroup and the bounded group-local request map. No
acquirer call scans or leases across Worker score queues. Results are appended
independently:

```text
ad:{prefix}:candidate:{taskId}:workers
```

Cache expiry equals the Worker allocation lease deadline. Empty result tuples
are not written. Return value is the number of CandidateIds for which at least
one entry was appended.

Every exact-leased Worker is consumed scheduling evidence. Unmatched Workers
and append failures are not released; lease expiry restores HOT visibility.

## Task Score Rotation

Every scanned RUNNING Task is offered one same-band absolute-time rewrite,
including missing descriptors, full caches, empty Worker scans, and no matches.
The rewrite preserves Task band and suffix. A stale rewrite does not roll back
already published candidates.

## Configuration

```python
TaskWorkerAllocationConfig(
    task_batch_limit,
    worker_lease_duration_millis,
)
```

`worker_scan_limit` belongs to `WorkerCandidateAcquirer` construction, not to
each allocation call.

## Guardrails

- Scan only due `RUNNING_VISIBLE` Tasks.
- Use `taskId` as the built-in stable CandidateId; do not make the cache infer
  Task identity.
- Do not consume cache from this Pacer to satisfy its own prefetch request.
- Do not implement a second scan/lease/match flow beside realtime acquisition.
- Do not let CandidateWorkerCache own limits, rules, matching, or Worker truth.
- Do not release unmatched or failed-publication Worker leases.
- Do not change Task band or suffix.
