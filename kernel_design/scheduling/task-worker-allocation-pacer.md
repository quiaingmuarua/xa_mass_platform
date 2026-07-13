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

It does not claim Work, create DeliverSeed, or accept transport delivery. It
reads a bounded Worker score observation batch, matches only those Worker ids,
then attempts an exact-score point lease for each match. The entry
itself has no TTL and is not permanent validity proof. It does not classify or
advance Task lifecycle. After one Task has been considered, it may advance that
Task's current same-band timeSlot for bounded fairness rotation.

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
    -> workerId -> observedScore mapping; read-only
  acquire_observed_hot_score_lease(
    homeBucketId, workerId, observedScore, targetTimeMillis
  )
  release_score_hold(homeBucketId, workerId, observedScore, releaseTimeMillis)
  exact-CAS leases matched Workers and compensates unpublished leases

WorkerCandidateMatcher
  match_worker_candidates(
    workerGroupId,
    workerIds,
    candidateConstraints,
  )
  -> WorkerCandidateMatchResult(matches, unmatchedWorkerIds)

TaskDispatchRuntime
  append_candidate_workers(taskId, entries, expiresAtMillis)
  candidate_worker_counts(taskIds)

TaskWorkerAllocationConfig
  taskBatchLimit
  workerScanLimit
  workerLeaseDurationMillis
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
  -> batch current non-expired candidate counts
  -> partition by workerGroupId
  -> workerGroupId -> taskId -> WorkerCandidateConstraint
  -> bounded due HOT (workerId, observedScore) pairs without mutation
  -> pacer-private workerId -> observedScore sidecar
  -> one WorkerCandidateMatcher call with those Worker ids
  -> matched and unmatched Worker-id partition
  -> exact-score point lease for matched ids only
  -> successful leases become CandidateWorkerEntry values
```

First cut identity is direct:

```text
CandidateId = TaskId
```

Task and Worker scans are bounded independently:

```text
taskBatchLimit
  bounds scheduling demand inspected in one allocation round

workerScanLimit
  bounds Worker score candidates supplied to one Worker-group matcher call
```

## Constraint Construction

Each loaded `TaskDescriptor` with remaining candidate capacity maps to one
`WorkerCandidateConstraint`:

```text
priority
  parsed from TaskDescriptor.config["priority"]

limit
  maximumCandidateWorkers - current non-expired candidate count
  skip the Task when the remaining count is zero

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
observedScoreByWorkerId = workerScore.acquire_hot_acquire_candidates(
  workerGroupId,
  workerScanLimit,
)
workerIds = list(observedScoreByWorkerId)
matchResult = workerCandidateMatcher.match_worker_candidates(
  workerGroupId,
  workerIds,
  ...
)
leaseUntilMillis = nowMillis() + workerLeaseDurationMillis
for matchedWorkerId in matchResult.matches:
  lease = workerScore.acquire_observed_hot_score_lease(
    workerGroupId,
    matchedWorkerId,
    observedScoreByWorkerId[matchedWorkerId],
    leaseUntilMillis,
  )
  if lease succeeds:
    publish CandidateWorkerEntry(matchedWorkerId, lease.score)
```

The allocation pacer resolves `workerGroupId` to the current v0 home bucket and
reads at most `workerScanLimit` due HOT entries as a private
`workerId -> observedScore` sidecar. This query does not mutate score or expose
scan order as a caller contract. The pacer supplies only Worker ids to the
matcher. The matcher considers each Worker at most once according to candidate
priority and candidate limit and returns a complete exclusive partition: each
normalized input Worker is either assigned to one candidate or returned as
unmatched.

Unmatched Workers require no score write. For each matched Worker, the pacer
passes its opaque observation to `acquire_observed_hot_score_lease`. That method
validates due HOT shape outside Lua and uses one generic exact-score CAS to write
the future lease while preserving lane rank and clearing dirty. A stale CAS is
discarded; only a successful result creates `CandidateWorkerEntry`.

The scan, matching, and point leases are deliberately not one atomic operation.
Concurrent rounds may inspect and match the same observation, but only one can
win the exact-score lease CAS. `leaseUntilMillis` is derived immediately before
point leasing from `workerLeaseDurationMillis`; it does not cross the matcher
interface. The same value is supplied to
`TaskDispatchRuntime` as the current batch expiry, so candidate expiry never
outlives the lease that prevented duplicate allocation.

## PRE_DISPATCH_VISIBLE

Allocation does not evaluate the first-running condition. It stores every
matched candidate entry up to the Task's current remaining candidate target:

```text
PRE_DISPATCH_VISIBLE allocation
  -> bounded Worker match
  -> acquire short Worker reservations
  -> append reservations when non-empty
  -> independently advance the current same-band timeSlot
```

An independent score-acquired classification later reads the activation owner
facts, including the configured minimum and stored candidate count, and decides:

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
Worker candidates may be appended before a later pause, close, stale
classification, or failed transition; they remain best-effort intermediate
state.

## RUNNING_VISIBLE

RUNNING Task allocation is independent of backlog inspection in this interface
slice:

```text
due RUNNING_VISIBLE
  -> include Task in Worker-group match batch
  -> publish bounded Worker reservations when matching and lease succeed
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
a future hold or terminal state, the rewrite can fail while candidate evidence
remains as intermediate residue.

The first executable lifecycle classifier is a separate
`TaskRunningActivationPacer` class in the same module. Its
`activate_running_visible_tasks(config)` method:

```text
acquire PRE_DISPATCH_VISIBLE before the current time horizon with limit
    -> read TaskDescriptor minimum and stored candidate count
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

The current executable count is batched raw unique collection size, not a
current Worker-validity count. It is point-in-time allocation evidence and may
change before the Task score transition. If
`runningVisibleMinimumCandidateWorkers` is required to mean guaranteed current
Workers, activation needs a named Worker revalidation/reservation result before
transition. `candidate_worker_counts` must not silently absorb score, dirty,
descriptor, or dynamic-attribute checks.

It does not call `allocate_candidate_workers`, and allocation does not call it.

## Publish Protocol

The pacer appends one ordered candidate sequence directly to one Task queue:

```text
matched entries prepared
    -> append reservation entries to that Task collection
```

Candidate entry:

```text
workerId
workerGroupId
workerLeaseScore
```

The collection contains Worker candidates produced by successful short leases
but no Work claim. `workerLeaseScore` is the exact score written by allocation
and stays opaque inside the ZSET member. The entry has no independent expiry;
the ZSET score is the batch `expiresAtMillis`. Worker lease state, dirty, and
matching attributes are still rechecked by the later dispatch path.

There is no cross-key transaction or commit protocol between Task score and the
collection. A lifecycle transition after publication may make the collection
unreachable to Work dispatch; later consumption or Task physical cleanup owns
that residue.

One call is atomic only for one Task:

```python
task_dispatch_runtime.append_candidate_workers(
    task_id=task_id,
    candidate_workers=entries,
    expires_at_millis=lease_until_millis,
)
```

The pacer may loop or a concrete runtime may pipeline calls for many Tasks, but
cross-Task success is partial and non-atomic. The Task descriptor plus current
live count bounds the matcher output for one allocation batch.
`TaskDispatchRuntime` stores every entry passed to it and owns only batch-expiry
indexing, owner-local expired-member cleanup, batched live count, and bounded
atomic consume.

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
workerLeaseDurationMillis
```

The concrete allocation pacer receives `TaskScoreBandCore`,
`TaskResourceCatalog`, `WorkerScoreCore`, `WorkerCandidateMatcher`, and
`TaskDispatchRuntime` through constructor assembly. `TaskRunningActivationPacer`
additionally receives one `TaskRunningActivationPolicy` function. These pacers
are strategy executors, not ABC owner surfaces.

The config contains round policy only. It does not carry runtime owners,
Redis keys, clocks, score ranges, preloaded Task/Worker observations, or
Task-specific candidate limits. `maximumCandidateWorkers` belongs to the
`TaskDescriptor`; allocation subtracts the current batched live-entry count and
uses the remaining value as `WorkerCandidateConstraint.limit`. It is not an
append argument or a runtime-owned policy decision. Because allocation rounds
do not lock a Task, the resulting collection bound is best-effort under
concurrent pacers rather than a cross-key atomic hard cap.

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
    candidate_counts = task_dispatch_runtime.candidate_worker_counts(
        task_ids=task_ids,
    )

    grouped_constraints = prepare_and_group(
        task_ids,
        descriptors,
        candidate_counts,
    )

    for worker_group_id, constraints in grouped_constraints:
        observed_score_by_worker_id = worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=worker_group_id,
            limit=config.worker_scan_limit,
        )
        match_result = worker_matcher.match_worker_candidates(
            worker_group_id=worker_group_id,
            worker_ids=tuple(observed_score_by_worker_id),
            candidate_constraints=constraints,
        )
        lease_until_millis = (
            now_millis() + config.worker_lease_duration_millis
        )
        leased_entries = lease_matched_workers(
            match_result.matches,
            observed_score_by_worker_id,
            lease_until_millis,
        )
        append_worker_reservations(
            leased_entries,
            expires_at_millis=lease_until_millis,
        )

    for task_id, expected_band in task_band_by_id.items():
        task_score.rewrite_same_band_time_millis(
            task_id=task_id,
            expected_band=expected_band,
            target_time_millis=now_millis(),
        )
```

The direct `workerGroupId == homeBucketId` mapping is the current v0 worker
runtime contract. It is not Task metadata and is not caller-configurable.

## Executable-Spec Status

The current Python package implements `TaskWorkerAllocationPacer`,
`TaskWorkerAllocationConfig`, `TaskRunningActivationPacer`,
`TaskRunningActivationConfig`, the `TaskDispatchRuntime` owner interface, its
Redis ZSET implementation, and the candidate-worker reservation DTO. The Task score core
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
  do not match it; still rotate its acquired same-band time so it cannot remain
  the permanent score head; descriptor owner/recovery handles the missing fact

concurrent allocation round
  rounds may observe and match the same Worker; exact-score point lease permits
  only one successful future-lease write

matcher raises
  no Worker lease has been written; propagate the failure directly

Worker is unmatched or candidate capacity is exhausted
  return it through unmatchedWorkerIds; no score write or release is required

matched Worker score changed before point lease
  exact-score CAS returns STALE; discard that match and publish no entry for it

candidate collection publication fails
  best-effort release every exact Worker lease not yet published by that group,
  then propagate the failure; already-published Task entries remain valid

same-band time rewrite is stale or rejected
  keep already-published candidate evidence; do not roll it back

Task pauses/closes after publication
  WorkDispatchPacer no longer scans it; later Task physical cleanup owns residue
```

## Guardrails

- Do not claim Work in this pacer.
- Do not introduce a Work-availability bridge solely to skip empty matching.
- Do not turn scan -> match -> point lease into one transaction or lock.
- Point lease must use the exact opaque observed score; do not replace it with
  an expected band, time range, or second score read.
- Do not pass opaque lease scores, lease deadlines, or dispatch entries into
  `WorkerCandidateMatcher`.
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
  candidate ZSET is an independent owner boundary.
- Do not make `TaskDispatchRuntime` choose or enforce a Task candidate limit;
  the pacer bounds each matcher result and the runtime stores what it receives.
- Do not make candidate collection a Task or Worker truth owner.
