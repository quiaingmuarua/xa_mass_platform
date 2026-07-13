# Task-Worker Allocation Pacer

Status: new-kernel mechanism note. This document defines the first mandatory
assignment-dispatch pacer. It is not current implementation truth and not an
implementation roadmap.

Parent contract: [Assignment-Dispatch Scheduling](assignment-dispatch-scheduling.md).

## Purpose

`TaskWorkerAllocationPacer` performs one bounded batch join between due Task
allocation demand and current Worker candidates.

It answers:

```text
which Tasks currently have bounded candidate Workers?
which PRE_DISPATCH_VISIBLE Tasks satisfy their first-running condition?
which Task score should move to the current allocation fairness timeSlot?
```

It does not claim Work, lease Workers, create DeliverSeed, or accept transport
delivery.

## Why It Is Independent

Allocation is descriptor- and query-heavy:

```text
Task score acquisition
Task descriptor batch read
partition by workerGroupId
Worker score acquisition
Worker descriptor batch read
dynamic attribute batch read
constraint DSL evaluation
priority and limit consumption
PRE_DISPATCH_VISIBLE condition evaluation
candidate collection publication
```

Its cadence and batch limits must be tunable independently from Work dispatch.
Merging it with dispatch would turn matching, activation, Worker lease, Work
claim, and delivery into one large policy extension point.

## Inputs

```text
TaskScoreBandCore
  acquire_active_task_candidates(limit)
  get_score_states(taskIds)
  Task score rewrite primitives

TaskResourceCatalog
  load_task_allocation_descriptors(taskIds)

WorkerScoreCore
  acquire_hot_acquire_candidates(homeBucketId, limit)

WorkerCandidateMatcher
  match_worker_candidates(workerGroupId, workerIds, candidateConstraints)

TaskWorkerAllocationConfig
  taskBatchLimit
  workerScanLimit
  candidateTtlMillis
  noCandidateRecheckDelayMillis
```

## Candidate Bands

Allocation scans due active Tasks oldest-first:

```text
PRE_DISPATCH_VISIBLE
  match Workers to evaluate runningVisibleMinimumCandidateWorkers
  never claim Work or produce DeliverSeed

RUNNING_VISIBLE
  match bounded Workers for the current running Task
  do not inspect or claim Work in this pacer
```

`PRE_REVIEW`, hard-paused positive scores, future non-due scores, and `TERMINAL`
must not enter this pacer.

### Band Order

Allocation is strictly consumption-first:

```text
1. scan due RUNNING_VISIBLE up to taskBatchLimit
2. only remaining batch capacity scans due PRE_DISPATCH_VISIBLE
```

The kernel does not guarantee anti-starvation capacity for
`PRE_DISPATCH_VISIBLE`. Operators control how many Tasks are approved/opened
relative to productive RUNNING capacity. A future policy may reserve or weight
band capacity, but that is an explicit allocation policy and must not be
embedded in the base acquire mechanism.

The current Redis executable method already follows this RUNNING-first band
order.

## Batch Shape

The primary efficiency rule is one matcher call per Worker group, not one call
per Task:

```text
bounded Task ids
  -> batch TaskDescriptor read
  -> partition by workerGroupId
  -> taskId -> WorkerCandidateConstraint
  -> bounded Worker score candidates for that group
  -> one WorkerCandidateMatcher call
  -> taskId -> matched Worker ids
```

First cut identity is direct:

```text
CandidateId = TaskId
```

Task and Worker limits are independent:

```text
taskBatchLimit
  bounds scheduling demand inspected in one allocation round

workerScanLimit
  bounds Worker universe read for one workerGroupId partition
```

## Constraint Construction

Each loaded `TaskDescriptor` maps directly to one
`WorkerCandidateConstraint`:

```text
priority
  parsed from TaskDescriptor.config["priority"]

limit
  parsed from TaskDescriptor.config["maximumCandidateWorkers"]

match_rules
  TaskDescriptor.allocationRule
```

`constraint_dsl` owns syntax validation and operator evaluation.
TaskWorkerAllocationPacer maps values but does not implement DSL parsing.

`eventCode` is not a Worker match field. Task creation fixes exactly one
`workerGroupId`; allocation must not scan or fall back across Worker groups.

## Worker Matching

For each Worker-group partition:

```text
homeBucketId = worker-runtime resolution from workerGroupId
workerCandidates = workerScore.acquire_hot_acquire_candidates(limit)
observedWorkerScoreByWorkerId = complete returned score sidecar
matches = workerCandidateMatcher.match_worker_candidates(...)
```

The matcher consumes each bounded Worker at most once in one batch according to
candidate priority and candidate limit. It returns Worker ids only.
TaskWorkerAllocationPacer joins the complete observed Worker score from its
sidecar when constructing candidate entries.

Allocation must not decode observed Worker score and must not acquire a Worker
lease. Worker evidence may become stale before dispatch; exact CAS in
WorkDispatchPacer is the correctness fence.

## PRE_DISPATCH_VISIBLE

The first built-in activation condition is:

```text
matchedWorkerCount >= runningVisibleMinimumCandidateWorkers
```

If satisfied:

```text
PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE at current allocation timeSlot
publish bounded candidate-worker collection
```

If not satisfied and budget remains:

```text
stay PRE_DISPATCH_VISIBLE
write later recheck timeSlot
consume one scheduling suffix unit
do not publish candidate collection
```

If exhausted, apply the configured PRE_DISPATCH hold/closure policy. This pacer
never lets PRE_DISPATCH_VISIBLE continue directly into Work claim.

## RUNNING_VISIBLE

RUNNING Task allocation is independent of backlog inspection in this interface
slice:

```text
due RUNNING_VISIBLE
  -> include Task in Worker-group match batch
  -> publish bounded candidate evidence when matching succeeds
```

Work may be absent when WorkDispatchPacer later consumes a candidate. That is a
bounded stale/empty dispatch outcome, not an allocation correctness failure.
The Work/backlog owner and no-work closure mechanism remain intentionally
unfrozen until Work runtime is designed as a complete owner. This pacer must not
introduce a read-only bridge merely to avoid empty matching cost.

## Allocation Fairness

Allocation scans the oldest due active scores first. Successful allocation
rewrites the Task to the current scheduling time while preserving same-band
suffix:

```text
old timeSlot
  -> bounded allocation
  -> current timeSlot
```

This moves processed Tasks behind older due Tasks for subsequent allocation
rounds. Successful allocation does not consume retry/no-work suffix budget.

Negative classifications own different writes:

```text
NO_WORK
NO_WORKER
ACTIVATION_FALSE
```

They apply their policy-specific later timeSlot, suffix consumption, hold, or
terminal action.

## Publish Protocol

The pacer appends one ordered candidate sequence to one Task queue after the
Task score rewrite succeeds:

```text
matched entries prepared
  -> rewrite Task score
  -> stale/rejected: discard prepared entries
  -> transitioned: append candidate entries to that Task queue
```

Candidate entry:

```text
workerId
workerGroupId
completeObservedWorkerScore
expiresAtMillis
```

The collection is match evidence only. It contains no Worker lease and no Work
claim.

There is no cross-key transaction between Task score and collection. Score
success plus collection-write failure is a lost allocation opportunity; normal
allocation rotation retries later. Lifecycle transition after publication makes
the collection unreachable to Work dispatch; later Task physical cleanup owns
that residue.

One call is atomic only for one Task:

```python
task_dispatch_runtime.append_candidate_workers(
    task_id=task_id,
    candidate_workers=entries,
)
```

The pacer may loop or a concrete runtime may pipeline calls for many Tasks, but
cross-Task success is partial and non-atomic. The Task descriptor bounds the
matcher output for one allocation batch. `TaskDispatchRuntime` appends every
entry passed to it and owns only queue append, atomic consume, size observation,
and consume-time expiry validation.

## Interface

The stable scheduling entry receives round policy per invocation:

```python
allocate_candidate_workers(
    config: TaskWorkerAllocationConfig,
) -> int
```

The return value is the number of Tasks whose candidate entries were published
after an accepted Task-score rewrite. It is not an assignment count or Work
claim count.

`TaskWorkerAllocationConfig` is not constructor state:

```text
taskBatchLimit
workerScanLimit
candidateTtlMillis
noCandidateRecheckDelayMillis
```

The concrete pacer receives `TaskScoreBandCore`, `TaskResourceCatalog`,
`WorkerScoreCore`, `WorkerCandidateMatcher`, and `TaskDispatchRuntime` through
constructor assembly. It is a strategy executor, not an ABC owner surface.

The config contains round policy only. It does not carry runtime owners,
Redis keys, clocks, score ranges, preloaded Task/Worker observations, or
Task-specific candidate limits. `maximumCandidateWorkers` belongs to the
`TaskDescriptor` and becomes `WorkerCandidateConstraint.limit` for that Task.
It is not a `TaskDispatchRuntime` argument.

## Conceptual Round

```python
def allocate_candidate_workers(config):
    task_ids = task_score.acquire_active_task_candidates(
        limit=config.task_batch_limit,
    )
    score_states = task_score.get_score_states(task_ids=task_ids)
    descriptors = task_catalog.load_task_allocation_descriptors(
        task_ids=task_ids,
    )

    accepted = classify_and_group(
        task_ids,
        score_states,
        descriptors,
    )

    for worker_group_id, tasks in accepted:
        worker_candidates = worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=worker_group_id,
            limit=config.worker_scan_limit,
        )
        observed_scores = dict(worker_candidates)
        matches = worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=list(observed_scores),
            candidate_constraints=build_constraints(tasks),
        )
        classify_rewrite_and_publish(tasks, matches, observed_scores, config)
```

The direct `workerGroupId == homeBucketId` mapping is the current v0 worker
runtime contract. It is not Task metadata and is not caller-configurable.

## Executable-Spec Status

The current Python package implements `TaskWorkerAllocationPacer`,
`TaskWorkerAllocationConfig`, the `TaskDispatchRuntime` owner interface, its
Redis LIST implementation, and the candidate-worker DTO. Work/backlog APIs
remain deliberately absent until that owner is designed completely. The
existing active-task acquire supplies the base RUNNING-first band order.

Interface sources:

```text
py_example/kernel/assignment_dispatch.py
py_example/kernel/task_dispatch_runtime.py
```

## Failure And Stale Handling

```text
Task descriptor missing/corrupt
  fail closed for this Task and apply bounded score-owner policy

Task score changed before rewrite
  stale; publish nothing

Worker score changed after matching
  allowed; WorkDispatchPacer exact CAS rejects stale entry

candidate collection publication fails
  no rollback of Task score; later allocation rotation retries

Task pauses/closes after publication
  WorkDispatchPacer no longer scans it; later Task physical cleanup owns residue
```

## Guardrails

- Do not claim Work in this pacer.
- Do not introduce a Work-availability bridge solely to skip empty matching.
- Do not acquire or renew Worker score lease in this pacer.
- Do not create DeliverSeed in this pacer.
- Do not call WorkerCandidateMatcher once per Task.
- Do not include `PRE_REVIEW`, paused, future non-due, or terminal Tasks.
- Do not let `PRE_DISPATCH_VISIBLE` enter Work dispatch.
- Do not consume scheduling suffix on successful allocation.
- Do not publish candidate entries after a stale Task-score rewrite.
- Do not expose cross-Task append as one atomic runtime operation; each Task
  candidate LIST is an independent owner boundary.
- Do not make `TaskDispatchRuntime` choose or enforce a Task candidate limit;
  the pacer bounds each matcher result and the runtime stores what it receives.
- Do not make candidate collection a Task or Worker truth owner.
