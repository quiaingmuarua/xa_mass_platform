# Task Resource Model

Status: active Java Kernel Task resource Owner contract.

## Core Decision

Task resource metadata explains stable allocation intent. It does not own Task
lifecycle, TaskItem scheduling, Worker lease, candidate handoff, result
classification, or query projection truth.

The descriptor persists two orthogonal mechanism choices:

```text
TaskDescriptor
  taskId
  workerGroupId
  workerAllocationMechanism
  idleDisposition
  allocationRule | null
  config
```

`taskId` is globally unique in the Java Kernel. One Task chooses exactly one
WorkerGroup for allocation. Allocation explains where the Worker rule lives;
idle disposition explains what happens after the ACTIVE Item band becomes
empty. Neither value is Task priority or a scheduling-mode state machine.

## Descriptor Contract

```python
@dataclass(frozen=True)
class TaskDescriptor:
    task_id: str
    worker_group_id: str
    worker_allocation_mechanism: WorkerAllocationMechanism
    idle_disposition: TaskIdleDisposition
    allocation_rule: Mapping[str, object] | None
    config: Mapping[str, str]
```

The config keys are exactly:

| Key | Meaning | Validation |
| --- | --- | --- |
| `priority` | Task scheduling priority used by RUNNING INITIAL ordering and Worker contention | decimal text in `0..99`; `0` highest |
| `maximumCandidateWorkers` | best-effort Task-local candidate target before matching | positive decimal text; retained but unused by `ON_DEMAND_ITEM_RULE` in this slice |
| `maxRetryTimes` | TaskItem retry budget source used when initializing Item scores | decimal text in `0..98` |

All values are strings in the first cut. Supporting two representations for
the same setting adds ambiguity without mechanism value.

`priority` is one Task scheduling intent. Task approval maps it to the suffix
inside the fixed RUNNING INITIAL time slot, and the Worker matcher uses it when
multiple NORMAL Tasks contend for Workers. There must not be a second
initialization or allocation-priority field.

`config` is not an unchecked extension bag. New keys require a named owner and
consumer. The Task resource model deliberately contains no minimum matching
Worker requirement: initialization must not reserve or count Workers.

## Worker Allocation

The Kernel contract supports exactly two Worker-allocation mechanisms:

| Mechanism | Rule owner | Fixed workflow | Candidate cache |
| --- | --- | --- | --- |
| `PRECOMPUTED_TASK_RULE` | Task | Task-rule precomputation, then cached candidate renewal | enabled |
| `ON_DEMAND_ITEM_RULE` | TaskItem | Item-rule on-demand acquisition | forbidden |

The rule-shape constraints are:

```text
PRECOMPUTED_TASK_RULE
  TaskDescriptor.allocationRule is a Map; an empty object means no constraint
  TaskItems must not carry allocationRule

ON_DEMAND_ITEM_RULE
  TaskDescriptor.allocationRule is null
  every appended TaskItem carries an allocationRule object
  an empty object means no Worker restriction within the Task WorkerGroup
```

The mechanism is a fixed Producer workflow label. It derives rule location,
allocation participation and candidate-cache participation as one coherent
contract; it is not a Matcher mode:

```text
PRECOMPUTED_TASK_RULE
  every Item inherits one complete Task-level Worker rule
  candidate computation is reusable across Items and dispatch rounds
  precomputation and cache amortize repeated Worker-selection cost

ON_DEMAND_ITEM_RULE
  every Item owns its complete Worker rule; an empty object is unrestricted
  Item rules may all be equal or may differ
  Worker selection is paid only when that Item is actually dispatched
```

The distinction is rule ownership and candidate reuse, not traffic shape. The
workflows are mutually exclusive in the current descriptor shape. They use the
same DSL, canonical Matcher and post-lease rematch, but they do not merge rules
or exchange Candidate Cache entries.
WorkerAllocationMechanism establishes no relative scheduling priority and does not imply a
latency class, synchronous versus asynchronous execution, Worker exclusivity,
or permission to preempt another Task's Worker lease. Task priority and
candidate-request priority remain explicit scheduling inputs and are evaluated
without deriving an ordering from `PRECOMPUTED_TASK_RULE` or `ON_DEMAND_ITEM_RULE`.

## Idle Disposition

`TaskIdleDisposition` is independent of Worker allocation:

```text
CLOSE_WHEN_IDLE
  no ACTIVE Item -> exact-close the observed RUNNING score

PARK_WHEN_IDLE
  no ACTIVE Item -> exact-move the observed RUNNING score to the
                    Kernel-private idle-park coordinate
```

The idle park is `RUNNING_VISIBLE` at `MAX_TIME_SLOT - 1`, suffix `MAX_SUFFIX`.
The low-order value makes the private coordinate the upper raw-score boundary
of that reserved slot; it carries no scheduling policy meaning. It is outside
due scans but remains visible to the RUNNING soft-limit count.
It is distinct from the public pause coordinate at `MAX_TIME_SLOT`; callers
cannot mint, select or release the park through a generic time rewrite.

Ordinary Item append remains a pure Task data write and never wakes a parked
Task. The bounded `TaskCallItemSubmission` command deliberately does not read
this descriptor or interpret either mechanism. It invokes the score owner's
idempotent idle-park release before and after bounded Item append. WorkerGroup
Task Call assembly decides when to use that command. The Kernel
close owner can terminate either idle disposition, but the generic public
Server lifecycle routes expose close only for finite Tasks.

Server exposes the two supported combinations through separate use cases:

| Server use case | Worker allocation | Idle disposition |
| --- | --- | --- |
| Generic public Task create | `PRECOMPUTED_TASK_RULE` | `CLOSE_WHEN_IDLE` |
| WorkerGroup registration-provisioned Task Call | `ON_DEMAND_ITEM_RULE` | `PARK_WHEN_IDLE` |

Generic creation has no profile or mechanism selector. WorkerGroup registration
derives one managed Task coordinate and converges the exact descriptor plus
approval. It is not an arbitrary reusable Task registration surface.
Registration returns that coordinate: Task-ID Call and result load are public,
while lifecycle commands and ordinary Item append reject the managed type.
These Server assembly decisions are not additional Kernel enums or score
states.

`allocationRule` uses one finite structured constraint DSL and is evaluated by
the bounded Worker matcher. Example:

```json
{
  "worker.runtime": {"$eq": "python"},
  "platform.pool": {"$eq": "batch"},
  "worker.battery": {"$range": [50, 100]}
}
```

Each Property/Operator pair is normalized into one immutable
`propertyName + operator + params` condition. All conditions are currently
ANDed; `{}` is unrestricted. The fixed operators are `$eq`/`$equal`, `$ne`,
`$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$exists` and `$range`. `$range` requires
two ordered non-null bounds and includes both endpoints; open or half-open
ranges use the ordinary comparison operators. Matcher roots are exactly
`workerId`, `worker.*` and `platform.*`; the suffix after `worker.` or
`platform.` remains one flat canonical Property name.

The descriptor stores the rule snapshot. It does not store normalized matcher
state, current Worker properties, candidate Workers, or a policy handler object.
`WorkerCandidateMatcher` normalizes one call-local Match Plan and reuses it for
rule-derived Worker identity ranges, canonical matching and post-lease rematch.
That Plan is never written to Kernel or Redis. A missing Property is distinct
from a present null value; incompatible comparisons fail closed.

For one `PRECOMPUTED_TASK_RULE` precomputation batch, the Pacer derives:

```text
WorkerCandidateConstraint
  priority   = int(config["priority"])
  limit      = max(
                 0,
                 int(config["maximumCandidateWorkers"])
                   - currentNonExpiredCandidateCount,
               )
  allocationRule = descriptor allocationRule
```

This is an in-memory bounded transformation, not a stored Task state.

## Task Initialization Input

Approve first reads the complete RUNNING count and returns its existing
retryable result when the soft limit of 100 is already reached. It then uses a
separate exact score transition to move the observed PRE_REVIEW Task into the
fixed RUNNING INITIAL slot. Concurrent approvals may exceed 100; that bounded
drift is not a scheduling-safety failure. Priority determines the
INITIAL-local suffix; no admission policy or recheck time is stored in the
Descriptor.

The one current initialization condition is a due ACTIVE Item:

```text
RUNNING INITIAL
  -> TaskItemScoreBandCore.has_due_active_items
  -> exact promotion to RUNNING NORMAL
```

Worker estimates, quota, tenant rules, and business start conditions remain
outside the current mechanism and must not pre-lease Workers for INITIAL Tasks.
See
[Task Initialization](../../../kernel_pacer_jvm/doc/dispatch/task-initialization-policy.md).

## Task Runtime Surfaces

Mutation and bounded scheduling reads are intentionally separate:

```python
TaskRuntime.create_task(
    descriptor: TaskDescriptor,
    suffix: Suffix,
) -> TaskCreationResult

TaskResourceCatalog.load_task_allocation_descriptors(
    task_ids: Sequence[TaskId],
) -> Mapping[TaskId, TaskDescriptor | None]
```

`create_task` is create-only. It rejects an existing descriptor without
touching score, initializes a `PRE_REVIEW` score lease before creating missing
descriptor metadata, and never overwrites an existing descriptor HASH. The
owner-defined initialization suffix is opaque to TaskRuntime. Score is the
Task identity/lifecycle coordination owner; a descriptor HASH cannot
independently create a Task.

A retry may complete exactly one score-only interruption residue when the
existing score is still `PRE_REVIEW` and the descriptor key is absent. It
creates the descriptor without rewriting or releasing that existing score.

`load_task_allocation_descriptors` is batch-only. It supports initialization and
allocation without creating general `get/list/query/update/delete Task` APIs.
Missing or corrupt descriptor rows map to `None` and fail that Task closed for
the bounded round.

## TaskItem And Success Result

`TaskRuntime` also owns canonical TaskItem records and Task-scoped last-success
payloads:

```text
TaskItem
  messageId
  eventCode
  payload
  priority
  createdAtMillis
  expireAtMillis | null
  allocationRule | null
```

```python
append_items(task_id, items)
load_task_items(task_id, message_ids)
store_task_item_success_results(task_id, results)
load_task_item_success_results(task_id, message_ids)
scan_task_item_success_results(task_id, cursor, count_hint)
```

`messageId` is unique only inside a Task. Re-append replaces the canonical
record payload for that id, while Item score initialization remains `ZADD NX`
and never resets its scheduling identity. `maxRetryTimes` is read owner-locally
to initialize Item remaining budget; callers do not pass score fields.

The scan operation exposes one Task's existing success-result HASH in bounded
owner pages. One call performs one Redis `HSCAN` with a `1..1000` COUNT hint
and returns the next cursor plus opaque `messageId -> payload` entries. COUNT
is not a strict page-size contract, ordering is undefined, and callers must
deduplicate across pages. This supports caller-owned export without adding a
second result store or allowing Server to read Redis directly.

`eventCode` stores the full opaque Event Name and is passed through to the
selected Worker's local handler dispatch. Kernel Task initialization, matching, and
dispatch do not parse it or compare it with the WorkerGroup catalog projection.
Server may use that projection to recommend a WorkerGroup, but its possible
staleness means it must not be promoted into a Kernel initialization or dispatch
guarantee.
`expireAtMillis` is the new-attempt cutoff:
TaskRuntime rejects an already-expired append, while Task dispatch final-fails
an Item that expires after append before acquiring a Worker. Existing claimed
attempts remain governed by their claim lease.

For `ON_DEMAND_ITEM_RULE`, every TaskItem carries the complete Worker allocation rule
for that Item. The rule is not a delta and does not merge with a Task rule,
because no Task rule exists. It does not change `workerGroupId`, which always
comes from `TaskDescriptor`.

Non-atomic broadcast or fan-out is normalized before scheduling into multiple
TaskItems. Each expanded Item has its own `messageId`, complete allocation rule,
claim, retry budget, `DeliveryCommand`, and result; each still occupies exactly one
Worker slot. The kernel therefore does not turn one Item into a multi-Worker
assignment.

If a business operation truly requires all `N` Worker slots to be reserved and
started atomically, expansion alone would lose that all-or-none invariant. That
would be a separate gang-reservation mechanism with a bundle identity,
multi-lease commit, and bundle failure semantics. No such requirement is
assumed by the current kernel.

The public Java TaskData ingress treats an `ON_DEMAND_ITEM_RULE` allocation rule as an
opaque JSON-compatible map. It does not interpret or normalize operators. `{}` uses one
bounded due-HOT Worker Score query within the Task's WorkerGroup; exact score
CAS chooses at most the requested count. Matcher prepares one call-local Plan
and currently derives bounded explicit IDs from `workerId $eq/$equal/$in`. A
different non-empty rule has no current on-demand identity source and fails
closed. The complete
`workerId`, `worker.*` and `platform.*` rule is evaluated only over
Score-eligible bounded candidate IDs. TaskRuntime owns canonical persistence,
while Matcher owns DSL syntax, rule-derived identity range and canonical
evaluation.

The success-result HASH is last-success truth. It is separate from TaskItem
score outcome and does not store failure history. The owner exposes one
bounded, Task-scoped read of requested `messageId` values. One call maps to
one Task result HASH and one `HMGET`; cross-Task batching remains caller
orchestration rather than a TaskRuntime contract. The read returns each opaque
last-success payload or `null` and does not infer pending or failure state.

The Server RPC wait path uses only this result truth. It does not read the
TaskItem record, Item score, or Task score while waiting. A missing success
payload means only "not observed in this wait window"; Worker failure,
FINAL_FAILED, pending execution, and a delayed result intentionally remain
indistinguishable to RPC v1. TaskItem records are not deleted by this path.
RPC v1 observes exactly one TaskItem. Batch append does not create a
multi-Item waiter or completion aggregate; callers use the existing
Task-scoped result read for later polling.

## Scheduling Read Paths

Initialization:

```text
TaskScoreBandCore.acquire_scheduling_tasks(limit)
  -> TaskScoreBandCore.filter_initial_task_scores(taskId -> opaque score)
  -> TaskItemScoreBandCore.has_due_active_items(initial taskIds)
  -> TaskScoreBandCore.promote_observed_initial_tasks(ready exact scores)
```

Worker allocation:

```text
DispatchMainScheduler
  -> Task Score Owner scans taskId -> opaque score once with limit 100
  -> Task Score Owner filters the exact INITIAL subset
  -> load Descriptors once for only the NORMAL complement
  -> carry NORMAL observed scores unchanged beside their Task ids
  -> send the INITIAL taskId -> opaque score map directly to its check
  -> select workerAllocationMechanism=PRECOMPUTED_TASK_RULE as Allocation root input
  -> group by workerGroupId
  -> build Task-level WorkerCandidateRequest values
  -> WorkerCandidateMatcher prepares one call-local Match Plan
  -> observe one shared bounded HOT Worker pool per Group
  -> WorkerCandidateMatcher canonical-matches that pool against all Task rules
  -> WorkerCandidateSelectionPolicy applies priority, deficit and unique-Worker choice
  -> WorkerCandidateSelectionPolicy exact-leases selected Workers through the
     Worker Score Owner
  -> WorkerCandidateMatcher rematches only original successful Candidate pairs
  -> reobserve active leases and append CandidateWorkerEntry evidence
```

Every initialized Task enters NORMAL `RUNNING_VISIBLE` with suffix `0`. When the complete
ACTIVE Item band is empty, Task Dispatch applies the descriptor's idle
disposition immediately. Any ACTIVE Item prevents close or park, and a
post-park check can exact-release a park installed concurrently with Task Call
append. Item execution retry remains TaskItem-score truth. The Kernel close
owner remains valid for either disposition; generic public Server close is
limited to finite Tasks and cannot close the managed Task Call Task.
Initial finite Items may be appended before approval; after a finite Task
reaches NORMAL RUNNING with an empty ACTIVE band it may close immediately.
Task Call submission treats valid PRE_REVIEW and RUNNING INITIAL scores as
score no-ops, so a new due ACTIVE Item can still enter the initialization
path. The private
park is needed only for a later idle-to-active cycle.

Existing candidate cache and Worker lease evidence is not actively deleted
when a Task becomes idle; it expires naturally. `PRECOMPUTED_TASK_RULE` replenishment
continues through later shared RUNNING Task discovery.

Task dispatch:

```text
due record-backed Items
  -> PRECOMPUTED_TASK_RULE: cached candidate renewal using the Task rule
  -> ON_DEMAND_ITEM_RULE: messageId-local on-demand acquisition using Item rules
  -> exact claim only after CandidateId-correlated acquisition results
```

A cached candidate miss does not fall back to HOT acquisition, and item-rule
on-demand acquisition never reads Candidate Cache. Unconsumed Task candidates
remain disposable evidence and expire under the existing cache TTL.

`TaskResourceCatalog` does not add status indexes, active-Task discovery,
background scans, or candidate/result reads. Task score remains the sole Task
scheduling-domain index.

## Validation

Descriptor construction and Redis decode enforce one schema:

```text
taskId non-empty
workerGroupId non-empty
workerAllocationMechanism is PRECOMPUTED_TASK_RULE or ON_DEMAND_ITEM_RULE
idleDisposition is CLOSE_WHEN_IDLE or PARK_WHEN_IDLE
PRECOMPUTED_TASK_RULE requires a mapping allocationRule; an empty map means no constraint
ON_DEMAND_ITEM_RULE requires null Task allocationRule
config is exactly map<string, string> with the three declared keys
priority is decimal 0..99; lower values have higher priority
maximumCandidateWorkers is positive decimal
maxRetryTimes is decimal 0..98
PRECOMPUTED_TASK_RULE forbids TaskItem allocationRule
ON_DEMAND_ITEM_RULE requires a TaskItem allocationRule object; `{}` is unrestricted
Task Owner validates JSON persistability and finite numbers, not Property or Operator semantics
Matcher is the only DSL semantic owner; invalid stored rules fail closed per Candidate
```

WorkerGroup existence is a cross-owner command/admission check. The Task
catalog does not own WorkerGroup storage.

The serialized mechanism enum is a clean cut. There is no compatibility alias
or Redis migration reader; scopes containing descriptors from before this
contract must be cleared or those Tasks must be recreated.

## Redis Shape

One Task descriptor is one Redis HASH:

```text
xa_mass:<scope>:task:<taskId>:descriptor

workerGroupId       -> plain string
workerAllocationMechanism -> `PRECOMPUTED_TASK_RULE` or `ON_DEMAND_ITEM_RULE`
idleDisposition    -> `CLOSE_WHEN_IDLE` or `PARK_WHEN_IDLE`
allocationRuleJson  -> JSON object or `null`
configJson          -> JSON object
```

Example:

```json
workerAllocationMechanism = "PRECOMPUTED_TASK_RULE"
idleDisposition = "CLOSE_WHEN_IDLE"

allocationRuleJson = {
  "worker.region": {"$eq": "cn-east"},
  "platform.pool": {"$in": ["batch", "burst"]}
}

configJson = {
  "priority": "80",
  "maximumCandidateWorkers": "20",
  "maxRetryTimes": "3"
}
```

`taskId` is derived from the key and not duplicated as a HASH field.

Task creation is score-first and intentionally not a cross-key transaction:

```text
reject an existing descriptor
  -> initialize PRE_REVIEW score with a short owner lease
  -> transactionally HSETNX all complete descriptor fields
  -> exact observed-score release to current time
```

Known failure release is latency optimization. If release is lost, the score
lease becomes due naturally. A retry completes a score-only PRE_REVIEW residue
only when the descriptor key is absent, and it preserves the existing score.
Descriptor-only metadata is not scheduling truth and conflicts with create;
existing descriptor fields are never overwritten. No Lua spans score and
descriptor keys.

Bounded descriptor reads pipeline one `HMGET` per requested Task key. They do
not use `SCAN`, `HGETALL`, or a second descriptor index.

TaskItem records and success results use Task-scoped HASH keys; Item scheduling
identity remains in the separate TaskItem score ZSET.

Each canonical Item JSON stores `allocationRule` as either an object or null.
It is resource metadata, never encoded into Item score.

## Java Owner Implementation

The public contracts live in
[`kernel_jvm/task`](../../src/main/java/com/xa/mass/kernel/task),
[`kernel_jvm/score`](../../src/main/java/com/xa/mass/kernel/score), and their
owner-local `redis` packages. Task create, lifecycle, Item append and
last-success reads use the shapes above. Create fixes the owner-internal initial
PRE_REVIEW suffix to `1`; no caller supplies score structure.

## Explicit Non-Owners

TaskDescriptor and TaskResourceCatalog do not own:

```text
Task score or lifecycle transition
approval execution
TaskItem score, claim, retry, or finality
Worker descriptor, dynamic value, score, or lease
matched Worker ids or candidate handoff
DeliveryCommand or DeliveryReport queues
transport
result classification, trace, or diagnostics
```

The descriptor explains stable intent. It must never become a snapshot of
current runtime state.
