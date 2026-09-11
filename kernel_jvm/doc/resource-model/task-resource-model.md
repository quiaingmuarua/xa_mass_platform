# Task Resource Model

Status: active Java Kernel Task scheduling metadata contract.

## Owner Boundary

Kernel stores Task and TaskItem state required for scheduling, claim, retry and
finality. It does not store or interpret PRECOMPUTED allocation rules. It does
own ON_DEMAND selector structure, ANY and explicit Worker ID mechanics. Other
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
  workerSelector (one immutable binding-to-parameters Map)
```

`workerAllocationMechanism` selects two deliberately separate inputs:

| Mechanism | Input owner | Kernel workflow |
| --- | --- | --- |
| `PRECOMPUTED_TASK_RULE` | Matching Candidate Rule at `candidateId` | hold due Workers, publish ordered Candidate Demand, consume Candidate Cache |
| `ON_DEMAND_ITEM_RULE` | Kernel structural capture; Matching property interpretation | persist selector unchanged; bounded acquisition and hold before claim |

The mechanism is a fixed scheduling workflow label. It is not a rule parser or
a generic strategy extension point.

## Cross-Owner Creation

Server preserves the public Task API while directing each fact to its owner:

```text
PRECOMPUTED Task creation
  -> create-only Candidate Rule in WorkerMatchingCatalog
  -> create Kernel Task descriptor without Rule

ON_DEMAND Item append or items:call
  -> Kernel captures workerSelector; Matching validates property conditions through Server admission
  -> append Kernel TaskItems with the same selector, without PRECOMPUTED Rule syntax
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
due HOT pool, and offers one Group Demand to `WorkerMatchQueue`. The contract
owns offer, size observation, and consumption together; the current Server
assembly chooses an in-memory implementation without exposing that choice to
Pacer or Matching.
Matching loads Candidate Rules and only the supplied Worker Facts, then
appends matches directly through the Kernel-owned Candidate Cache operation
while carrying held scores opaquely. Kernel later consumes the Candidate
bucket and owns
final exact renewal, round uniqueness, TaskItem claim, Command construction,
retry, and finality.

For ON_DEMAND Items, `TaskItemWorkerSelector` holds one immutable expression:
`{}`, `{"workerId":["id"]}`, `{"workerId":["a","b"]}`, or an
opaque binding such as `{"worker.country":["CN"]}`.
Kernel validates an empty Map or exactly one non-blank binding name mapped to
1..100 string parameters. Parameter order is preserved without coercion,
sorting or deduplication; an empty parameter list is not ANY. Explicit IDs
additionally require unique non-blank identities. IDs are extracted when used,
not stored beside the expression. Both Map and parameter List are copied.

Matching alone validates binding names, parameter counts/meaning and enabled indexes.
Country accepts exactly one parameter; the parameter container does not imply
multi-country support or an operator slot. PRECOMPUTED Rule operators are unchanged.
The selector is never expanded into IDs at submission. PRECOMPUTED Items use an
empty selector internally and reject nonempty conditions; the public finite
append API still requires the field to be omitted.
`WorkerCandidateIndex` receives the same selector for bounded acquisition and
post-hold membership recheck. Kernel alone observes due HOT eligibility, holds,
confirms and claims. No ON_DEMAND Match Demand or Candidate Cache exists.

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

- PRECOMPUTED allocation-rule persistence or validation;
- Worker Properties or constraint evaluation;
- Candidate-rule evaluation or ownership of Candidate Cache state;
- Worker score interpretation or lease policy;
- Adapter delivery, Result routing, or public runtime-view joins.
