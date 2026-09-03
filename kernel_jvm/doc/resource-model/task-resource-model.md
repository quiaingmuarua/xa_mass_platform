# Task Resource Model

Status: active Java Kernel Task scheduling metadata contract.

## Owner Boundary

Kernel stores Task and TaskItem state required for scheduling, claim, retry and
finality. It does not store or interpret PRECOMPUTED allocation rules. It does
own the closed ON_DEMAND Worker Selector syntax and normalized Worker IDs.

```text
TaskDescriptor
  taskId
  workerGroupId
  workerAllocationMechanism
  idleDisposition
  config

TaskItem
  messageId
  eventCode
  createdAtMillis
  payload
  priority
  expireAtMillis
  targetWorkerIds
```

`workerAllocationMechanism` selects two deliberately separate inputs:

| Mechanism | Input owner | Kernel workflow |
| --- | --- | --- |
| `PRECOMPUTED_TASK_RULE` | Matching Candidate Rule at `candidateId` | hold due Workers, publish ordered Candidate Demand, consume Candidate Cache |
| `ON_DEMAND_ITEM_RULE` | Kernel finite `workerSelector` parser | persist normalized explicit Worker IDs or ANY, then acquire directly before claim |

The mechanism is a fixed scheduling workflow label. It is not a rule parser or
a generic strategy extension point.

## Cross-Owner Creation

Server preserves the public Task API while directing each fact to its owner:

```text
PRECOMPUTED Task creation
  -> create-only Candidate Rule in WorkerMatchingCatalog
  -> create Kernel Task descriptor without Rule

ON_DEMAND Item append or items:call
  -> Kernel validates workerSelector and returns normalized target Worker IDs
  -> append Kernel TaskItems without Rule syntax
```

Equivalent Candidate Rule writes are idempotent; conflicting content is
rejected. The PRECOMPUTED cross-owner writes are not transactional. A
persisted Candidate Rule without a matching Kernel Task is inert and may
remain as a lazy orphan. Kernel cannot discover it without a bounded
scheduling demand for the corresponding address.

## Config

Task config remains a finite map of string values:

| Key | Meaning |
| --- | --- |
| `priority` | scheduling priority, `0` highest |
| `maximumCandidateWorkers` | Task-local Candidate Cache target for PRECOMPUTED |
| `maxRetryTimes` | initial TaskItem retry budget |

Adding a config key requires a named scheduling consumer. Config must not be
used to smuggle rule syntax or Worker facts back into Kernel.

## Scheduling Handoffs

For PRECOMPUTED Tasks, Kernel orders current deficits, exact-holds a bounded
due HOT pool, and publishes one Group Demand through `WorkerMatchRuntime`.
Matching loads Candidate Rules and only the supplied Worker Facts, then
appends matches directly through the Kernel-owned Candidate Cache operation
while carrying held scores opaquely. Kernel later consumes the Candidate
bucket and owns
final exact renewal, round uniqueness, TaskItem claim, Command construction,
retry, and finality.

For ON_DEMAND Items, Kernel accepts only `[]`, `workerId/$eq`, and
`workerId/$in` Selector arrays. `TaskItem` stores the normalized identity list,
not the raw Selector; an empty list means ANY. Kernel directly observes and
exact-holds eligible Workers when dispatching the Item. No Item Demand, Match
Evidence, or Matching persistence exists.

## Redis Shape

Kernel Task descriptors and TaskItems use exact JSON field sets matching the
records above. Rule maps remain rejected at the Kernel Redis boundary. Rules
use the independent Matching keyspace documented by
[`worker_matching_jvm`](../../../worker_matching_jvm/README.md).

## Non-Owners

Task resource code does not own:

- PRECOMPUTED allocation-rule persistence or validation;
- Worker Properties or constraint evaluation;
- Candidate-rule evaluation or ownership of Candidate Cache state;
- Worker score interpretation or lease policy;
- Adapter delivery, Result routing, or public runtime-view joins.
