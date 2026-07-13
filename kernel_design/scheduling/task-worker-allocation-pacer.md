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

TaskWorkRuntime
  bounded claimable-work evidence for RUNNING_VISIBLE classification

WorkerScoreCore
  acquire_hot_acquire_candidates(homeBucketId, limit)

WorkerCandidateMatcher
  match_worker_candidates(workerGroupId, workerIds, candidateConstraints)

AllocationPolicy
  taskBatchLimit
  workerScanLimit
  maximumCandidateWorkers
  no-work / no-worker recheck policy
```

`TaskWorkRuntime` is named here as a required owner surface; its executable
interface is not implemented yet.

## Candidate Bands

Allocation scans due active Tasks oldest-first:

```text
PRE_DISPATCH_VISIBLE
  match Workers to evaluate runningVisibleMinimumCandidateWorkers
  never claim Work or produce DeliverSeed

RUNNING_VISIBLE
  obtain bounded backlog evidence
  classify obvious no-work before expensive Worker matching
  match Workers when allocation demand remains
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
  bounded by allocation policy maximumCandidateWorkers

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

RUNNING Task allocation first reads bounded non-consuming backlog evidence.

```text
no claimable Work
  -> classify no-work
  -> later recheck or terminal according to suffix policy
  -> do not perform Worker matching

claimable Work appears available
  -> include Task in Worker-group match batch
```

This evidence is not a Work claim and cannot promise that Work still exists
when WorkDispatchPacer later consumes a Worker entry.

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

The pacer publishes one bounded replaceable candidate-worker collection after
the Task score rewrite succeeds:

```text
matched entries prepared
  -> rewrite Task score
  -> stale/rejected: discard prepared entries
  -> transitioned: replace Task candidate-worker collection
```

Candidate entry:

```text
workerId
completeObservedWorkerScore
```

The collection is match evidence only. It contains no Worker lease and no Work
claim.

There is no cross-key transaction between Task score and collection. Score
success plus collection-write failure is a lost allocation opportunity; normal
allocation rotation retries later. Lifecycle transition after publication makes
the collection unreachable to Work dispatch and TTL removes residue.

## Conceptual Round

```python
def allocate_once(task_batch_limit, worker_scan_limit):
    task_ids = task_score.acquire_active_task_candidates(
        limit=task_batch_limit,
    )
    score_states = task_score.get_score_states(task_ids=task_ids)
    descriptors = task_catalog.load_task_allocation_descriptors(
        task_ids=task_ids,
    )
    work_evidence = task_work.inspect_claimable_work(task_ids=task_ids)

    accepted = classify_and_group(
        task_ids,
        score_states,
        descriptors,
        work_evidence,
    )

    for worker_group_id, tasks in accepted:
        worker_candidates = worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=resolve_home_bucket(worker_group_id),
            limit=worker_scan_limit,
        )
        observed_scores = dict(worker_candidates)
        matches = worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=list(observed_scores),
            candidate_constraints=build_constraints(tasks),
        )
        classify_rewrite_and_publish(tasks, matches, observed_scores)
```

Names are conceptual and do not freeze Python interfaces.

## Executable-Spec Gap

The current Python package already provides Task score, Task descriptor batch
read, Worker score acquisition, and `WorkerCandidateMatcher`. It does not yet
provide this pacer, `TaskWorkRuntime.inspect_claimable_work`, or candidate-
worker publication. The existing active-task acquire already supplies the base
RUNNING-first band order.

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
  WorkDispatchPacer no longer scans it; collection expires
```

## Guardrails

- Do not claim Work in this pacer.
- Do not acquire or renew Worker score lease in this pacer.
- Do not create DeliverSeed in this pacer.
- Do not call WorkerCandidateMatcher once per Task.
- Do not include `PRE_REVIEW`, paused, future non-due, or terminal Tasks.
- Do not let `PRE_DISPATCH_VISIBLE` enter Work dispatch.
- Do not consume scheduling suffix on successful allocation.
- Do not publish candidate entries after a stale Task-score rewrite.
- Do not append duplicate candidate collections; publish one bounded replaceable
  collection per Task.
- Do not make candidate collection a Task or Worker truth owner.
