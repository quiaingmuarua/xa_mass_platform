# Task Worker Allocation Policy

Status: active Kernel mechanism contract.

## Purpose

`TaskWorkerAllocationPolicy` fills disposable Candidate evidence for a bounded,
already-verified due `RUNNING_VISIBLE` Task batch:

```text
DueTaskObservation[]
  -> retain PRECOMPUTED_TASK_RULE Tasks
  -> read current Candidate cache counts
  -> compute each Task deficit
  -> group requests by workerGroupId
  -> acquire and exactly lease bounded HOT Workers
  -> append expiring CandidateWorker entries
```

The policy does not discover Tasks, read or rewrite Task score, activate Tasks,
dispatch Items, or maintain a retry/warmup queue. `DIRECT_ITEM_RULE` Tasks are
ignored because they acquire Workers only while dispatching their Items.

## Input Boundary

`TaskSchedulingBatchSource` owns Task discovery and validation. It supplies an
immutable `DueTaskObservation` containing:

```text
taskId
verified RUNNING score state with suffix 0
TaskDescriptor
```

The observation is best-effort round evidence, not a lease. Candidate
acquisition still uses exact Worker Score operations, and later Task Dispatch
uses exact Item claims.

## Candidate Request

For each PRECOMPUTED Task:

```text
candidateId = taskId
requestedCount = maximumCandidateWorkers - cachedCandidateCount
priority = int(descriptor.config["priority"])
allocationRule = descriptor.allocationRule
```

Zero deficits and missing rules produce no request. Requests never cross a
WorkerGroup. The policy publishes only non-empty acquisition results, and the
Candidate expiry equals the Worker lease deadline.

Redis remains:

```text
xa_mass:<scope>:dispatch:candidate:<taskId>:workers
```

There is no Candidate scheduling index. If a Task needs more candidates, its
unchanged due RUNNING score causes a later shared Source round to expose it
again. If Dispatch advances or parks/closes the Task first, stale Candidate
evidence simply expires.

## Configuration

```python
TaskWorkerAllocationConfig(
    worker_lease_duration_millis,
)
```

The shared RUNNING Source owns the fixed batch limit of 100. Worker scan bounds
belong to `WorkerCandidateAcquirer` construction.

## Guardrails

- Use `taskId` as the stable PRECOMPUTED CandidateId.
- Do not rediscover or revalidate Tasks inside this policy.
- Do not mutate Task score or introduce a Candidate retry index.
- Keep HOT observation, exact lease and complete match inside the candidate
  acquirer.
- Keep Candidate Cache disposable; it owns no Task or Worker truth.
- Do not release unmatched or publication-failed Worker leases; expiry is the
  recovery mechanism.
