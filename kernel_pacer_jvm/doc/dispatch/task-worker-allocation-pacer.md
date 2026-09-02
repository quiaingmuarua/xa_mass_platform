# Task Worker Allocation Policy

Status: active Kernel PRECOMPUTED allocation contract.

## Purpose

`TaskWorkerAllocationPolicy` fills Candidate Cache deficits for a bounded,
Main-selected set of `PRECOMPUTED_TASK_RULE` Tasks. It neither discovers Tasks
nor interprets their allocation rules.

```text
Due PRECOMPUTED Tasks
  -> observe Candidate Cache counts
  -> consume Task Match Evidence
  -> confirm exact active holds and schedule matched Worker IDs
  -> append selected hold evidence to Candidate Cache
  -> exact-hold a bounded Worker pool for remaining deficits
  -> publish new Match Demands for successfully held Worker IDs
```

## Input

Each `DueTaskObservation` contains the Task ID, an opaque observed Task score
and a minimal `TaskDescriptor`. The descriptor has no Rule. It supplies the
fixed WorkerGroup, priority and `maximumCandidateWorkers`.

Receiving an ON_DEMAND Task is a caller error.

## One Round

1. Read Candidate Cache counts for all supplied Task IDs.
2. Take each available Task Evidence exactly once.
3. Reject expired evidence or a WorkerGroup mismatch.
4. Group usable evidence by WorkerGroup.
5. Build requests from Task priority and current deficit.
6. Confirm each matched Worker still has the exact clean HOT hold named by its
   Evidence.
7. Let Kernel selection enforce priority, requested count and cross-Task
   Worker uniqueness.
8. Load minimal Worker delivery descriptors and append Candidate entries with
   the observed hold score.
9. Observe one bounded due HOT pool per WorkerGroup for remaining deficits.
10. Exact-hold the observed pool until the shared deadline and offer Task Match
    Demands containing only the successfully held Worker IDs.

The first round for a new deficit normally publishes Demand. A later round
consumes Evidence. Neither policy waits for Matching.

## Demand

```text
TaskRuleMatchDemand
  taskId
  workerGroupId
  heldWorkerIds
  holdUntilMillis
```

Kernel obtains one bounded due HOT Worker set for each WorkerGroup and
exact-holds it. It places only successfully held Worker IDs in each Task Demand
for that group. Worker Matching may only filter these IDs using its persistent
Task Rule and Worker facts. Evidence carries the same hold deadline, and Kernel
accepts a match only while that exact hold remains active.

Demand and Worker pools are capped at 100. Requested count remains in Kernel's
Task deficit and selection policy; it is not a Matching input. Queue capacity,
missing evidence, empty matches and hold contention leave the deficit visible
for a later round. Unmatched or unselected holds are not compensated; they
expire naturally and their newer score position lets later due Workers enter
subsequent bounded pools.

## Candidate Cache

The cache stores only:

```text
workerId
workerGroupId
exact workerLeaseScore
```

Entries expire with the Worker hold. It stores no Rule, Properties or
endpoint. Dispatch renewal resolves the current minimal Worker descriptor after
the exact score fence succeeds.

## Ownership

Allocation owns deficit computation and the timing of Task Demand publication.
Kernel selection owns priority, unique selection and lease. Matching owns Rule
and Properties interpretation. No part of this policy owns Task score
transition, Item claim, Delivery Command or matching persistence.
