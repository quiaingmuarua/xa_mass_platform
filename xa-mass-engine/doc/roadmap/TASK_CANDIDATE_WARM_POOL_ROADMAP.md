# Task Candidate Warm Pool Roadmap

Status: design roadmap; no implementation yet.

This roadmap adds a task-local candidate warm pool to improve repeated
assignment attempts for large worker groups. The warm pool is a priority hint,
not scheduling truth.

## Summary

Current worker matching flow:

```text
Task
  -> workerGroupId(s)
  -> route bucket
  -> bounded random sample
  -> Stage-2 prefilter / rule / rank / reserve
  -> runtime claim / dispatch
```

This is correct and bounded, but repeated assignment attempts discard workers
that already passed Stage-2 but were not used by the current dispatch budget.
For large groups with expensive Stage-2 filtering, that wastes useful evidence.

Target flow:

```text
Task
  -> workerGroupId(s)
  -> warm pool sample first
  -> route bucket random sample fills remaining candidates
  -> Stage-2 full revalidation
  -> rule / rank / reserve
  -> unused Stage-2-passed workers re-enter warm pool
```

The warm pool must never skip Stage-2 validation. It only increases the chance
that previously promising workers are considered again.

## Non-Goals

- Do not make warm pool an eligibility truth.
- Do not make warm pool an occupancy truth.
- Do not bypass `WorkerRegistry.tryReserve(...)`.
- Do not bypass reachability, dispatch gate, attribute, rule, rank, or capacity
  checks.
- Do not add one background coroutine per active task.
- Do not copy rule evaluation into a maintenance thread.
- Do not make worker attributes or eventCode a new candidate-source truth.
- Do not add transport-specific worker state into candidate ownership.

## Terms

`cold candidate`

A worker acquired from the normal group/route bucket path.

`warm candidate`

A worker that passed Stage-2 in a previous assignment attempt for the same task
but was not necessarily dispatched.

`warm pool`

A bounded task-local priority-hint cache of warm candidate worker ids.

`validation`

The normal Stage-2 pipeline: current worker slot, reachability, dispatch gate,
task-specific attribute filters, rule engine, ranking, and reserve/admission.

## Owner Boundaries

| Area | Owner | Must not own |
| --- | --- | --- |
| task-local warm candidate ids | `TaskCandidateWarmPool` | dispatch truth, reserve truth, worker lifecycle truth |
| worker occupancy | `WorkerRegistry` | warm priority, rule outcome cache |
| current eligibility | Stage-2 matching/admission | long-lived cached eligibility |
| worker route/group buckets | `WorkerRegistry` candidate indexes | task-specific warm priority |
| task lifecycle cleanup | assignment/runtime lifecycle owners | worker candidate correctness |

Warm pool stores candidate hints only. Stage-2 remains the only place that can
decide whether a worker is currently usable for a task.

## Hard Rules

1. Warm pool entries are hints, not truth.
2. Every warm candidate must be revalidated before dispatch.
3. A warm candidate must not be treated as reserved or occupied.
4. Only reserved/claimed workers affect occupancy counters.
5. Warm pool must be bounded by task id and size.
6. Warm pool must be clearable on task pause, cancel, terminal, or delete.
7. Maintenance may prune by TTL, size, missing worker slot, and obvious
   lifecycle facts.
8. Maintenance must not run task-specific rule evaluation.
9. Attribute/rule dependency invalidation is optional second-slice behavior.
10. If warm pool state is missing or stale, scheduling must continue through
    the normal cold candidate path.

## Candidate Flow

First-slice candidate acquisition should look like:

```text
maxCandidates = acquisitionLimit(task, requestedMatchCount)

warm = warmPool.sample(taskId, warmLimit)
cold = workerRegistry.acquireCandidates(groupId, adapterNodeId, routeBucketKey,
                                        maxCandidates - warm.size())

candidates = dedupe(warm + cold)
Stage-2 validates candidates
unusedStage2Passed = stage2Passed - dispatchCandidates
warmPool.put(taskId, unusedStage2Passed)
```

Recommended defaults:

- warm sample ratio: 50% of acquisition limit
- max pool size per task: 512 or current Stage-1 sample max
- TTL: 30 seconds to 2 minutes
- cold fill is always enabled to avoid getting stuck on a stale warm set

## Slice 1: Coarse Version Invalidation

Goal: add warm priority without task-specific invalidation complexity.

### Warm Entry Shape

```text
TaskCandidateWarmEntry
  taskId
  workerId
  observedAt
  observedWorkerIdentityVersion
  observedWorkerAttributeVersion
  observedDispatchVersion
  observedReachabilityVersion
```

Version fields may start coarse. If per-worker versions are not available yet,
first implementation may store only `observedAt` and rely on Stage-2
revalidation plus TTL.

### Scope

1. Add `TaskCandidateWarmPool` in engine scheduling/worker internals.
2. Store bounded task-local worker ids with TTL.
3. Sample warm candidates before route-bucket cold candidates.
4. Dedupe warm and cold candidates before Stage-2.
5. Full Stage-2 validation still runs for all candidates.
6. Put Stage-2-passed but unused workers back into the warm pool.
7. Clear warm pool on task pause, cancel, terminal, delete, or explicit
   runtime reset.
8. Add bounded maintenance that prunes:
   - TTL expired entries
   - pool size overflow
   - missing worker slots
   - removing worker slots
   - paused / terminal task pools
9. Do not listen to arbitrary worker attribute changes in this slice.

### Acceptance

1. A task with surplus Stage-2-passed workers stores them as warm candidates.
2. A later assignment for the same task samples warm candidates before cold
   bucket candidates.
3. Warm candidates still run through Stage-2 prefilter, rule, rank, and reserve.
4. A warm candidate that is now offline, dispatch-disabled, removing, or
   capacity full is rejected by normal Stage-2/admission and not dispatched.
5. If the warm pool is empty or stale, assignment still uses the cold
   group/route bucket path.
6. Task pause/cancel/terminal clears that task's warm pool.
7. Maintenance is bounded and does not evaluate rules.
8. Trace or diagnostics can show warm/cold candidate source counts for an
   assignment attempt.

## Slice 2: Dependency-Key Invalidation

Goal: remove warm entries when the facts that made their previous Stage-2 pass
meaningful have changed, without re-running match rules in the background.

### Dependency Model

Stage-2 records which worker facts it actually read while validating a
candidate. Example dependency keys:

```text
worker.identity.groupId
worker.identity.adapterNodeId
worker.attributes.region
worker.attributes.accountId
worker.attributes.devicePool
worker.dispatchGate
worker.reachability
worker.lifecycle.removing
worker.capacityClass
```

Warm pool stores:

```text
TaskCandidateWarmEntry
  taskId
  workerId
  dependencyKeys
  observedAt
```

When worker state/report/update changes facts, the worker owner emits:

```text
WarmPoolInvalidation(workerId, changedKeys)
```

Warm pool removes entries where:

```text
entry.workerId == workerId
and entry.dependencyKeys intersects changedKeys
```

It does not evaluate whether the new value still matches. That decision remains
in Stage-2.

### Scope

1. Add a dependency collector to Stage-2 context construction or matching
   evidence.
2. Record dependency keys for warm entries only after Stage-2 pass.
3. Generate changed keys from worker identity, attributes, dispatch gate,
   reachability, and lifecycle mutations.
4. Add `warmPool.invalidate(workerId, changedKeys)`.
5. Keep invalidation best-effort and bounded.
6. Do not make invalidation failure block worker updates or assignment.

### Acceptance

1. If a worker attribute key used by a warm entry changes, the warm entry is
   removed.
2. If an unrelated worker attribute changes, the warm entry may remain.
3. If worker reachability or dispatch gate changes, dependent warm entries are
   removed.
4. If worker group or adapter node changes, warm entries for that worker are
   removed.
5. Invalidation does not run rule evaluation.
6. Stale warm entries that survive invalidation are still rejected by Stage-2.
7. Scheduling remains correct if invalidation events are dropped.

## Maintenance

Use one shared maintenance loop, not per-task coroutines.

Allowed maintenance:

- TTL prune
- max-size prune
- missing slot prune
- removing slot prune
- paused/terminal/canceled task pool prune
- optional obviously offline prune

Forbidden maintenance:

- task-specific rule evaluation
- task-specific attribute matching
- rank calculation
- reserve/admission
- transport route probing

Reasoning: maintenance keeps the hint cache small. It does not decide whether a
worker matches a task.

## Testing Plan

Engine tests:

- warm candidates are sampled before cold candidates
- cold bucket fills remaining sample budget
- duplicate warm/cold worker ids are deduped
- stale warm candidates are rejected by Stage-2
- task pause/cancel/terminal clears warm pool
- maintenance prunes TTL and max-size overflow
- dropped warm pool state does not change correctness

Concurrency tests:

- concurrent assignment attempts for the same task do not corrupt pool size
- warm pool put/sample/clear remains bounded under concurrent worker updates
- warm pool does not hold worker reservations or exclusive leases

Trace/proof:

- assignment summary includes optional warmCandidateCount and coldCandidateCount
- worker match accepted/rejected still carries normal Stage-2 evidence
- no trace suggests warm pool was final eligibility truth

Architecture guards:

- warm pool package must not call `WorkerRegistry.tryReserve(...)`
- warm pool package must not call rule evaluation
- matching must not skip Stage-2 for warm candidates
- maintenance must not depend on transport delivery internals

## Risks

### Risk 1: Warm Pool Becomes Second Scheduling Truth

Mitigation:

- require Stage-2 revalidation for all warm candidates
- source guards against reserve/rule evaluation inside warm pool
- docs and traces call it a priority hint only

### Risk 2: Cache Invalidation Becomes Too Complex

Mitigation:

- first slice uses TTL and coarse version invalidation only
- dependency-key invalidation is a separate second slice
- correctness must not depend on invalidation

### Risk 3: Warm Pool Starves New Workers

Mitigation:

- always cold-fill part of the acquisition limit
- cap warm sample ratio
- TTL warm entries

### Risk 4: Maintenance Turns Into Reconciliation Owner

Mitigation:

- one shared bounded maintenance loop
- no task-specific rule/attribute evaluation in maintenance
- maintenance failures degrade to cold candidate path

## Recommended First Slice

Implement Slice 1 only:

```text
TaskCandidateWarmPool
  task-local bounded priority hint
  TTL / size cleanup
  sample warm first, cold fill next
  Stage-2 revalidates everything
```

Do not implement dependency-key invalidation until the warm pool has proven
useful and stable in scheduling proof tests.
