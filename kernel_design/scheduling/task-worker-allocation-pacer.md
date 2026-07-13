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
```

It does not claim Work, lease Workers, create DeliverSeed, or accept transport
delivery. It does not classify or advance Task lifecycle. After one Task has
been considered, it may advance that Task's current same-band timeSlot for
bounded fairness rotation.

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
candidate collection publication
```

Its cadence and batch limits must be tunable independently from Work dispatch.
Merging it with dispatch would turn matching, activation, Worker lease, Work
claim, and delivery into one large policy extension point.

## Inputs

```text
TaskScoreBandCore
  acquire_band_task_candidates(band, beforeTimeMillis, limit)
  rewrite_same_band_time_millis(taskId, expectedBand, targetTimeMillis)

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
```

## Candidate Bands

Allocation scans due active Tasks oldest-first:

```text
PRE_DISPATCH_VISIBLE
  publish bounded candidate evidence for a later activation classification
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
  -> workerGroupId -> taskId -> WorkerCandidateConstraint
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
  TaskDescriptor.config["maximumCandidateWorkers"]

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

Allocation does not evaluate the first-running condition. It appends every
matched candidate entry up to the Task's single-round matcher limit:

```text
PRE_DISPATCH_VISIBLE allocation
  -> bounded Worker match
  -> append candidate evidence when non-empty
  -> independently advance the current same-band timeSlot
```

An independent score-acquired classification later reads the activation owner
facts, including the configured minimum and candidate evidence, and decides:

```text
PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE
PRE_DISPATCH_VISIBLE -> unchanged when activation is still waiting
```

The current activation policy has no PRE_DISPATCH retry budget. A false
activation check does not decrement suffix and does not write an automatic
pause/hold. A later bounded activation round may evaluate the Task again.
Allocation's independent same-band time rotation preserves suffix and provides
fairness pacing; it is not activation-failure accounting.

That classification is not a commit stage inside `allocate_candidate_workers`.
Candidate evidence may be appended before a later pause, close, stale
classification, or failed transition; it remains expiring best-effort evidence.

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

## Lifecycle Activation Is Separate

Allocation may rewrite only the acquired band's timeSlot through
`rewrite_same_band_time_millis`. The pacer carries `expectedBand` directly from
the band-specific acquisition range; it does not read, decode, infer, or
revalidate Task score state. The score core range check preserves the stored
tag and suffix. This write rotates the Task after one bounded allocation
attempt; it is not an activation, retry-budget, hold, or terminal decision.

Candidate publication and same-band time rotation are independent best-effort
effects. Publication is not gated on rewrite success. If the score changed to
a future hold or terminal state, the rewrite can fail while the expiring
candidate evidence remains valid as intermediate residue.

The first executable lifecycle classifier is a separate
`TaskRunningActivationPacer` class in the same module. Its
`activate_running_visible_tasks(config)` method:

```text
acquire PRE_DISPATCH_VISIBLE before the current time horizon with limit
  -> read TaskDescriptor minimum and candidate queue count
  -> if minimum is satisfied, request RUNNING_VISIBLE
  -> initialize RUNNING_VISIBLE suffix to configured N
  -> count only TaskScoreBandCore TRANSITIONED results
```

The transition decision is delegated to a function strategy:

```python
TaskRunningActivationPolicy = Callable[[TaskDescriptor, int], bool]
```

The integer is the current candidate-worker count. The built-in
`minimum_candidate_workers_satisfied` policy compares it with
`TaskDescriptor.config["runningVisibleMinimumCandidateWorkers"]`. The policy
returns only whether activation is allowed; it does not read or write Task
score, candidate queues, Redis, or lifecycle state. This keeps condition
extension separate from pacer mechanics without adding an ABC or bridge.

It does not call `allocate_candidate_workers`, and allocation does not call it.

## Publish Protocol

The pacer appends one ordered candidate sequence directly to one Task queue:

```text
matched entries prepared
  -> append candidate entries to that Task queue
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

There is no cross-key transaction or commit protocol between Task score and the
collection. A lifecycle transition after publication may make the collection
unreachable to Work dispatch; entry expiry and later Task physical cleanup own
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

The return value is the number of Tasks whose candidate entries were published.
It is not an assignment count, score-transition count, or Work claim count.

`TaskWorkerAllocationConfig` is not constructor state:

```text
taskBatchLimit
workerScanLimit
candidateTtlMillis
```

The concrete allocation pacer receives `TaskScoreBandCore`,
`TaskResourceCatalog`, `WorkerScoreCore`, `WorkerCandidateMatcher`, and
`TaskDispatchRuntime` through constructor assembly. `TaskRunningActivationPacer`
additionally receives one `TaskRunningActivationPolicy` function. These pacers
are strategy executors, not ABC owner surfaces.

The config contains round policy only. It does not carry runtime owners,
Redis keys, clocks, score ranges, preloaded Task/Worker observations, or
Task-specific candidate limits. `maximumCandidateWorkers` belongs to the
`TaskDescriptor` and becomes the single-round `WorkerCandidateConstraint.limit`.
It is not a `TaskDispatchRuntime` argument or persistent queue-cap contract.

## Conceptual Round

```python
def allocate_candidate_workers(config):
    task_band_by_id = acquire_running_then_pre_dispatch(
        task_batch_limit=config.task_batch_limit,
    )
    task_ids = list(task_band_by_id)
    descriptors = task_catalog.load_task_allocation_descriptors(
        task_ids=task_ids,
    )

    grouped_constraints = prepare_and_group(
        task_ids,
        descriptors,
    )

    for worker_group_id, constraints in grouped_constraints:
        worker_candidates = worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=worker_group_id,
            limit=config.worker_scan_limit,
        )
        observed_scores = dict(worker_candidates)
        matches = worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=list(observed_scores),
            candidate_constraints=constraints,
        )
        append_matched_evidence(matches, observed_scores, config)
        for task_id in constraints:
            task_score.rewrite_same_band_time_millis(
                task_id=task_id,
                expected_band=task_band_by_id[task_id],
                target_time_millis=allocation_millis,
            )
```

The direct `workerGroupId == homeBucketId` mapping is the current v0 worker
runtime contract. It is not Task metadata and is not caller-configurable.

## Executable-Spec Status

The current Python package implements `TaskWorkerAllocationPacer`,
`TaskWorkerAllocationConfig`, `TaskRunningActivationPacer`,
`TaskRunningActivationConfig`, the `TaskDispatchRuntime` owner interface, its
Redis LIST implementation, and the candidate-worker DTO. The Task score core
also implements exact single-band bounded acquisition. Work/backlog APIs remain
deliberately absent until that owner is designed completely. The existing
active-task acquire supplies the base RUNNING-first band order.

Interface sources:

```text
py_example/kernel/task_worker_allocation.py
py_example/kernel/task_dispatch_runtime.py
```

## Failure And Stale Handling

```text
Task descriptor missing/corrupt
  skip this Task; descriptor owner/recovery handles the missing fact

Worker score changed after matching
  allowed; WorkDispatchPacer exact CAS rejects stale entry

candidate collection publication fails
  this Task's later same-band time rewrite is not attempted

same-band time rewrite is stale or rejected
  keep already-published expiring candidate evidence; do not roll it back

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
- Do not read or decode Task score state inside `allocate_candidate_workers`.
- Obtain `expectedBand` only from the band-specific acquisition context; do not
  infer it through a second Task-score read or business-state check.
- Do not perform lifecycle, suffix-budget, hold, or terminal rewrites inside
  `allocate_candidate_workers`.
- Do not gate candidate publication on a Task score transition.
- Do not evaluate activation minimum, fairness rotation, suffix consumption,
  no-worker backoff, hold, or terminal policy inside candidate allocation.
- Do not expose cross-Task append as one atomic runtime operation; each Task
  candidate LIST is an independent owner boundary.
- Do not make `TaskDispatchRuntime` choose or enforce a Task candidate limit;
  the pacer bounds each matcher result and the runtime stores what it receives.
- Do not make candidate collection a Task or Worker truth owner.
