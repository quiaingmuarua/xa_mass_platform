# Task Resource Model

Status: active Java Kernel Task scheduling metadata contract.

## Owner Boundary

Kernel stores Task and TaskItem state required for scheduling, claim, retry and
finality. It does not store or interpret allocation rules.

```text
TaskDescriptor
  taskId
  workerGroupId
  workerAllocationMechanism
  idleDisposition
  config

TaskItem
  taskId
  messageId
  eventCode
  payload
  forward
  expireAtMillis
```

Rule ownership is selected by `workerAllocationMechanism` but the rule itself
lives in `worker_matching_jvm`:

| Mechanism | Matching rule key | Kernel workflow |
| --- | --- | --- |
| `PRECOMPUTED_TASK_RULE` | `taskId` | publish Task Match Demand, lease evidence, fill Candidate Cache |
| `ON_DEMAND_ITEM_RULE` | `taskId + messageId` | publish Item Match Demand, lease evidence immediately before claim |

The mechanism is a fixed scheduling workflow label. It is not a rule parser or
a generic strategy extension point.

## Cross-Owner Creation

Server preserves the public Task API while directing each fact to its owner:

```text
PRECOMPUTED Task creation
  -> create-only Task Rule in WorkerMatchingCatalog
  -> create Kernel Task descriptor without Rule

ON_DEMAND Item append or items:call
  -> create-only Item Rules in WorkerMatchingCatalog
  -> append Kernel TaskItems without Rule
```

Equivalent rule writes are idempotent; conflicting content is rejected. The
two owner writes are not transactional. A persisted Rule without a matching
Kernel Task or Item is inert and may remain as a lazy orphan. Kernel cannot
discover it without a bounded scheduling demand for the corresponding
identity.

## Config

Task config remains a finite map of string values:

| Key | Meaning |
| --- | --- |
| `priority` | scheduling priority, `0` highest |
| `maximumCandidateWorkers` | Task-local Candidate Cache target for PRECOMPUTED |
| `maxRetryTimes` | initial TaskItem retry budget |

Adding a config key requires a named scheduling consumer. Config must not be
used to smuggle rule syntax or Worker facts back into Kernel.

## Scheduling Evidence

Kernel publishes identity-only, bounded demands through `WorkerMatchRuntime`.
After Demand admission Kernel exact-holds the supplied due HOT pool. Matching
loads its Rule and only those Worker Facts, interprets constraints, and returns
bounded WorkerId evidence. Kernel then remains responsible for exact hold
confirmation, priority, cross-Task uniqueness, cached renewal, TaskItem claim,
command construction, retry and finality.

Evidence is a short-lived facts snapshot associated with a Kernel hold, not a
scheduling decision. Properties may change after matching; a stale or
no-longer-held Worker fails exact hold confirmation. Consumed or expired
evidence causes a later Pacer round to publish another demand.

## Redis Shape

Kernel Task descriptors and TaskItems use exact JSON field sets matching the
records above. Legacy `allocationRule` fields are rejected at the Kernel Redis
boundary. Rules use the independent Matching keyspace documented by
[`worker_matching_jvm`](../../../worker_matching_jvm/README.md).

## Non-Owners

Task resource code does not own:

- allocation-rule persistence or validation;
- Worker Properties or constraint evaluation;
- candidate enumeration or match-result caching;
- Worker score interpretation or lease policy;
- Adapter delivery, Result routing, or public runtime-view joins.
