# Task Resource Model

Status: active new-kernel resource contract; Python executable spec
implemented; policy coverage partial.

## Core Decision

Task resource metadata explains stable allocation intent. It does not own Task
lifecycle, TaskItem scheduling, Worker lease, candidate handoff, result
classification, or query projection truth.

The first cut has one descriptor:

```text
TaskDescriptor
  taskId
  workerGroupId
  taskType
  allocationRule | null
  config
```

`taskId` is globally unique in the kernel design. One Task chooses exactly one
WorkerGroup for allocation. `taskType` selects one stable scheduling behavior
bundle. It is immutable Task metadata, not a per-dispatch inference or a set
of caller-selected policy flags.

## Descriptor Contract

```python
@dataclass(frozen=True)
class TaskDescriptor:
    task_id: str
    worker_group_id: str
    task_type: TaskType
    allocation_rule: Mapping[str, object] | None
    config: Mapping[str, str]
```

The config keys are exactly:

| Key | Meaning | Validation |
| --- | --- | --- |
| `priority` | Task scheduling priority used by default RUNNING admission and Worker contention | decimal text in `1..100`; `100` highest |
| `maximumCandidateWorkers` | best-effort Task-local candidate target before matching | positive decimal text; retained but unused by `ITEM_DRIVEN` in this slice |
| `maxRetryTimes` | TaskItem retry budget source used when initializing Item scores | decimal text in `0..98` |

All values are strings in the first cut. Supporting two representations for
the same setting adds ambiguity without mechanism value.

`priority` is one Task scheduling intent. The default System Admission Policy
uses it when selecting PRE_DISPATCH Tasks for available RUNNING slots, and the
Worker matcher uses it when multiple RUNNING Tasks contend for Workers. There
must not be a second admission-priority or allocation-priority field.

`config` is not an unchecked extension bag. New keys require a named owner and
consumer. The Task resource model deliberately contains no minimum matching
Worker requirement: PRE_DISPATCH admission must not reserve or count Workers.

## Task Type And Allocation Rule

The public contract supports exactly two Task types:

| Task type | Rule owner | Worker acquisition | Candidate cache | Empty behavior |
| --- | --- | --- | --- | --- |
| `TASK_DRIVEN` | Task | `PRECOMPUTED` | enabled | close after the configured consecutive-empty limit |
| `ITEM_DRIVEN` | TaskItem | `TARGETED` | forbidden | remain RUNNING; an external owner requests close from business evidence |

This table is the canonical external TaskType contract. The rule-shape
constraints are:

```text
TASK_DRIVEN
  TaskDescriptor.allocationRule is a Map; an empty object means no constraint
  TaskItems must not carry allocationRule

ITEM_DRIVEN
  TaskDescriptor.allocationRule is null
  every appended TaskItem carries a non-empty allocationRule
```

Callers do not choose rule owner, cache participation, warmer participation,
acquisition strategy, or empty behavior independently. Scheduling derives the
Worker-acquisition fields in `ResolvedTaskSchedulingProfile` from `TaskType`;
`TaskDispatchPacer` applies the same immutable type's empty behavior. Neither
is persisted as a second Task truth. The two forms cannot be mixed inside one
Task. Append validates this contract once; claim and dispatch trust it rather
than reclassifying every Item.

Both types use periodic RUNNING scans and Task dispatch. Append-trigger
acceleration and deadline-driven close remain deferred policies.

### TaskType Scenario Gate

`TaskType` names one workload scenario that the kernel supports vertically. It
is not a convenient label for a caller-selected combination of rule owner,
cache, acquisition, trigger, or termination policies.

The current scenarios are:

```text
TASK_DRIVEN
  stable Task-level Worker constraints
  stage-oriented and relatively dense Item arrival
  Task-level candidate computation can be prepaid and amortized
  repeated confirmed emptiness is completion evidence

ITEM_DRIVEN
  each Item supplies the bounded Worker target rule
  long-lived or open-ended, with sparse or unpredictable Item arrival
  Item rules may all be equal or may differ
  paying selection cost only for a real Item is cheaper than maintaining
  Task-level candidate leases
  emptiness is not completion evidence
```

A future TaskType is admitted only when a named workload cannot be represented
by either scenario without changing a scheduling invariant. The proposal must
identify the differing rule owner, Worker acquisition, cache authority, empty
or close behavior, and provide a vertical create-to-result/close executable
proof. A different limit, cadence, priority, fairness rule, retry interval, or
other tuning value stays inside the existing type's System Policy.

Tests do not enumerate arbitrary policy combinations. They prove the two
supported TaskType paths end to end and test score, lease, CAS, and owner
primitives independently at their legal boundaries.

`allocationRule` uses the independent constraint DSL and is evaluated by the
bounded Worker matcher. Example:

```json
{
  "attributes.runtime": {"$eq": "python"},
  "dynamic.battery": {"$gte": 20}
}
```

The descriptor stores the rule snapshot. It does not store compiled matcher
state, current dynamic values, candidate Workers, or a policy handler object.
Constraint compilation/validation belongs to `constraint_dsl`; Worker field
resolution belongs to Worker runtime/matcher.

For one `TASK_DRIVEN` precomputation batch, the Pacer derives:

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

## Task Admission Inputs

`TaskDescriptor` is available to Task and System admission policies, but the
default Task policy does not add a descriptor start-condition field. It asks
the Item score owner whether the Task currently has at least one due ACTIVE
Item. The default System policy applies the global RUNNING soft limit and Task
priority.

```text
PRE_DISPATCH_VISIBLE
  -> due Item Task policy
  -> priority + RUNNING soft-limit System policy
  -> PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE
```

Worker estimates, quota, tenant rules, and business start conditions may be
future policy inputs. They are not Task score encoding and must not pre-lease
Workers before RUNNING.

See [Task Running Activation Pacer](../scheduling/task-running-activation-pacer.md).

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

`create_task` initializes a `PRE_REVIEW` score lease before writing descriptor
metadata. The owner-defined initialization suffix is opaque to TaskRuntime.
Score is the Task identity/lifecycle coordination owner; a descriptor HASH
cannot independently create a Task.

`load_task_allocation_descriptors` is batch-only. It supports admission and
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
```

`messageId` is unique only inside a Task. Re-append replaces the canonical
record payload for that id, while Item score initialization remains `ZADD NX`
and never resets its scheduling identity. `maxRetryTimes` is read owner-locally
to initialize Item remaining budget; callers do not pass score fields.

For `ITEM_DRIVEN`, every TaskItem carries the complete Worker allocation rule
for that Item. The rule is not a delta and does not merge with a Task rule,
because no Task rule exists. It does not change `workerGroupId`, which always
comes from `TaskDescriptor`.

Non-atomic broadcast or fan-out is normalized before scheduling into multiple
TaskItems. Each expanded Item has its own `messageId`, complete allocation rule,
claim, retry budget, `DeliverSeed`, and result; each still occupies exactly one
Worker slot. The kernel therefore does not turn one Item into a multi-Worker
assignment.

If a business operation truly requires all `N` Worker slots to be reserved and
started atomically, expansion alone would lose that all-or-none invariant. That
would be a separate gang-reservation mechanism with a bundle identity,
multi-lease commit, and bundle failure semantics. No such requirement is
assumed by the current kernel.

Before application-level append, the selected WorkerGroup must allow every
Item rule field through `itemAllocationFields`. `workerId` supports bounded
`$eq/$in`; dynamic fields require a registered bounded candidate-query handler.
TaskRuntime itself owns only canonical JSON and DSL syntax validation, not
Worker catalog or candidate-index policy.

The success-result HASH is last-success truth. It is separate from TaskItem
score outcome and does not store failure history or provide a general external
result query API.

## Scheduling Read Paths

Activation:

```text
TaskScoreBandCore.acquire_band_task_candidates(PRE_DISPATCH_VISIBLE, now, limit)
  -> TaskResourceCatalog.load_task_allocation_descriptors(taskIds)
  -> Task Admission Policy
  -> System Admission Policy
  -> TaskScoreBandCore.rewrite_score(... RUNNING_VISIBLE ...)
```

Worker allocation:

```text
CandidateWarmupSchedule.acquire_candidate_warmups(now, limit)
  -> TaskScoreBandCore.get_score_states(taskIds)
  -> retain current RUNNING/non-hard-paused Tasks
  -> TaskResourceCatalog.load_task_allocation_descriptors(taskIds)
  -> retain taskType=TASK_DRIVEN
  -> group by workerGroupId
  -> build Task-level WorkerCandidateRequest values
  -> bounded HOT-pool lease/match
  -> append candidate evidence
```

Every admitted Task enters `RUNNING_VISIBLE` with suffix `0`. In that band the
suffix is the consecutive confirmed-empty recheck count: zero selects ordinary
TaskItem dispatch, while a positive value selects low-frequency ACTIVE Item
existence recheck. It is not `maxRetryTimes`; Item execution retry remains
TaskItem-score truth.

`TASK_DRIVEN` closes automatically after the configured number of consecutive
empty observations. `ITEM_DRIVEN` remains RUNNING at the maximum recheck count
until a new ACTIVE Item resets the count or an external owner requests close.
For example, a server may evaluate deadline or business-completion evidence and
call `KernelApplication.close_task`. The kernel validates and applies that
transition; it does not infer ITEM_DRIVEN completion from emptiness. Both Task
types support the same explicit close command.

Task dispatch:

```text
due record-backed Items
  -> TASK_DRIVEN: PRECOMPUTED request using Task rule
  -> ITEM_DRIVEN: messageId-local TARGETED requests
  -> exact claim only after CandidateId-correlated acquisition results
```

`TaskResourceCatalog` does not add status indexes, active-Task discovery,
background scans, or candidate/result reads. Task score remains the sole Task
scheduling-domain index.

## Validation

Descriptor construction and Redis decode enforce one schema:

```text
taskId non-empty
workerGroupId non-empty
taskType is TASK_DRIVEN or ITEM_DRIVEN
TASK_DRIVEN requires a mapping allocationRule; an empty map means no constraint
ITEM_DRIVEN requires null Task allocationRule
config is exactly map<string, string> with the three declared keys
priority is decimal 1..100
maximumCandidateWorkers is positive decimal
maxRetryTimes is decimal 0..98
TASK_DRIVEN forbids TaskItem allocationRule
ITEM_DRIVEN requires a non-empty valid TaskItem allocationRule
```

WorkerGroup existence is a cross-owner command/admission check. The Task
catalog does not own WorkerGroup storage.

## Redis Shape

One Task descriptor is one Redis HASH:

```text
tc:{prefix}:task:{taskId}

workerGroupId       -> plain string
taskType           -> `TASK_DRIVEN` or `ITEM_DRIVEN`
allocationRuleJson  -> JSON object or `null`
configJson          -> JSON object
```

Example:

```json
taskType = "TASK_DRIVEN"

allocationRuleJson = {
  "dynamic.battery": {"$gte": 20}
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
initialize PRE_REVIEW score with a short owner lease
  -> HSET complete descriptor fields
  -> exact observed-score release to current time
```

Known failure release is latency optimization. If release is lost, the score
lease becomes due naturally. Descriptor-only metadata is not scheduling truth;
score-only state requires a later owner completion/recovery path. No Lua spans
score and descriptor keys.

Bounded descriptor reads pipeline one `HMGET` per requested Task key. They do
not use `SCAN`, `HGETALL`, or a second descriptor index.

TaskItem records and success results use Task-scoped HASH keys; Item scheduling
identity remains in the separate TaskItem score ZSET.

Each canonical Item JSON stores `allocationRule` as either an object or null.
It is resource metadata, never encoded into Item score.

## Executable Spec

Contracts:

- [`kernel/task_runtime.py`](../../executable_spec/kernel/task_runtime.py)
- [`kernel/task_score_band.py`](../../executable_spec/kernel/task_score_band.py)
- [`kernel/task_item_score_band.py`](../../executable_spec/kernel/task_item_score_band.py)
- [`scheduling/task_scheduling_profile.py`](../../executable_spec/scheduling/task_scheduling_profile.py)

Redis implementations:

- [`redis_runtime/task_runtime.py`](../../executable_spec/redis_runtime/task_runtime.py)
- [`redis_runtime/task_score_band.py`](../../executable_spec/redis_runtime/task_score_band.py)
- [`redis_runtime/task_item_score_band.py`](../../executable_spec/redis_runtime/task_item_score_band.py)

## Explicit Non-Owners

TaskDescriptor and TaskResourceCatalog do not own:

```text
Task score or lifecycle transition
approval execution
TaskItem score, claim, retry, or finality
Worker descriptor, dynamic value, score, or lease
matched Worker ids or candidate handoff
DeliverSeed or SeedResult queues
transport
result classification, trace, or diagnostics
```

The descriptor explains stable intent. It must never become a snapshot of
current runtime state.
