# Task Resource Model

Status: active Java Kernel Task scheduling metadata contract.

## Owner Boundary

Kernel stores Task and TaskItem state required for scheduling, claim, retry and
finality. It does not store or interpret Rules or Task-to-Rule bindings. It does
own selector structure, ANY and explicit Worker ID mechanics. Other
selector meaning belongs to Matching; the expression is captured and passed unchanged.

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
  workerSelector (one immutable query Map)
```

`workerAllocationMechanism` selects two deliberately separate inputs:

| Mechanism | Input owner | Kernel workflow |
| --- | --- | --- |
| `PRECOMPUTED_TASK_RULE` | Matching resolves its Task binding to a shared Rule | hold due Workers, publish ordered Candidate Demand, consume Task-scoped Candidate Cache |
| `INDEXED_TASK` | Matching named Rule binding | bounded index query, HOT observation, initial hold, membership recheck and exact claim |
| `ON_DEMAND_ITEM_RULE` | Kernel structural capture; Matching property interpretation | persist selector unchanged; bounded acquisition and hold before claim |

The mechanism is a fixed scheduling workflow label. It is not a rule parser or
a generic strategy extension point.

## Cross-Owner Creation

Server preserves the public Task API while directing each fact to its owner:

```text
Named Rule or DSL Task creation
  -> create-only Task binding to a shared Rule in WorkerMatchingCatalog
  -> create Kernel Task descriptor without Rule

Non-DSL Item append or managed items:call
  -> Kernel captures workerSelector; Matching validates property conditions through Server admission
  -> append Kernel TaskItems with the same selector, without PRECOMPUTED Rule syntax
```

Equivalent Task binding writes are idempotent; rebinding conflicts. The
Task creation cross-owner writes are not transactional. A binding without a Kernel
Task may remain inert after failed creation. Matching does not discover or repair
Tasks. Kernel stores no Rule ID; Pacer submits Task IDs and Matching resolves its
own associations. Shared Rules do not couple Task lifecycle or Candidate Cache.

## Config

Task config remains a finite map of string values:

| Key | Meaning |
| --- | --- |
| `priority` | scheduling priority, `0` highest |
| `maximumCandidateWorkers` | Task-local Candidate Cache target for PRECOMPUTED |
| `maxRetryTimes` | initial TaskItem retry budget |

PRECOMPUTED config contains all three fields. INDEXED and ON_DEMAND config contain
exactly priority and maxRetryTimes, without a synthetic cache capacity.

Adding a config key requires a named scheduling consumer. Config must not be
used to smuggle rule syntax or Worker facts back into Kernel.

## Scheduling Handoffs

For PRECOMPUTED Tasks, Kernel orders current deficits, exact-holds a bounded
due HOT pool, and offers one Group Demand to `WorkerMatchQueue`. The contract
owns offer, size observation, and consumption together; the current Server
assembly chooses an in-memory implementation without exposing that choice to
Pacer or Matching.
Matching resolves Task bindings to Rules and loads only the supplied Worker Facts, then
appends matches directly through the Kernel-owned Candidate Cache operation
while carrying held scores opaquely. Kernel later consumes the Candidate
bucket and owns
final exact renewal, round uniqueness, TaskItem claim, Command construction,
retry, and finality.

For non-DSL Items, TaskItemWorkerSelector captures one immutable expression:
ANY `{}`, explicit IDs `{"workerId":["a","b"]}`, or a query such as
`{"worker.country":{"op":"in","values":["CN","US"]}}`. Matching owns operation
and value validation. Kernel owns generic bounded JSON capture and explicit-ID
validation. PRECOMPUTED Items continue to require an empty stored selector.

INDEXED_TASK contains no Rule ID or index key. Matching resolves taskId and
supplies bounded identities. A dispatch-local WorkerCandidateIndex.TaskQuery
reuses one binding for take and post-hold retain. Kernel owns HOT, hold, dirty,
exact confirmation and claim. There is no Match Demand or Candidate Cache in
this path and missing index evidence never becomes ANY.

Finite lifecycle is independent of allocation mechanism: CLOSE_WHEN_IDLE applies
to DSL, indexed and unbound finite Tasks; managed Calls retain PARK_WHEN_IDLE.

## Redis Shape

Kernel Task descriptors and TaskItems use exact JSON field sets matching the
records above. The stored `workerSelector` is the Map itself, not a value-object
envelope. Empty, explicit-ID and property selectors all use this one field.

This cutover uses a new scope: old `targetWorkerIds`/`indexQuery` records,
array selectors (including empty arrays), missing/null selectors and wrapper
objects are rejected, never interpreted as ANY.
There is no dual-read, migration or cleanup of old scopes.
PRECOMPUTED Rule maps remain rejected at the Kernel Redis boundary; Rules use
the independent Matching keyspace documented by
[`worker_matching_jvm`](../../../worker_matching_jvm/README.md).

## Non-Owners

Task resource code does not own:

- Rule or Task-to-Rule binding persistence and validation;
- Worker Properties or constraint evaluation;
- Candidate-rule evaluation or ownership of Candidate Cache state;
- Worker score interpretation or lease policy;
- Adapter delivery, Result routing, or public runtime-view joins.
