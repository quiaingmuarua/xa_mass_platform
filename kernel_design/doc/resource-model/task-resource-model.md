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
  allocationRule
  config
```

`taskId` is globally unique in the kernel design. One Task chooses exactly one
WorkerGroup for allocation. `allocationRule` constrains Workers inside that
group; it cannot broaden WorkerGroup capability.

## Descriptor Contract

```python
@dataclass(frozen=True)
class TaskDescriptor:
    task_id: str
    worker_group_id: str
    allocation_rule: Mapping[str, object]
    config: Mapping[str, str]
```

The config keys are exactly:

| Key | Meaning | Validation |
| --- | --- | --- |
| `priority` | Task scheduling priority used by default RUNNING admission and Worker contention | decimal text in `1..100`; `100` highest |
| `maximumCandidateWorkers` | best-effort Task-local candidate target before matching | positive decimal text |
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

## Allocation Rule

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

For one allocation batch, the Pacer derives:

```text
WorkerCandidateConstraint
  priority   = int(config["priority"])
  limit      = max(
                 0,
                 int(config["maximumCandidateWorkers"])
                   - currentNonExpiredCandidateCount,
               )
  matchRules = allocationRule
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
TaskScoreBandCore.acquire_band_task_candidates(RUNNING_VISIBLE, now, limit)
  -> TaskResourceCatalog.load_task_allocation_descriptors(taskIds)
  -> group by workerGroupId
  -> build WorkerCandidateConstraint values
  -> lease/match Workers
  -> append candidate evidence
```

`TaskResourceCatalog` does not add status indexes, active-Task discovery,
background scans, or candidate/result reads. Task score remains the sole Task
scheduling-domain index.

## Validation

Descriptor construction and Redis decode enforce one schema:

```text
taskId non-empty
workerGroupId non-empty
allocationRule is a mapping and valid constraint DSL
config is exactly map<string, string> with the three declared keys
priority is decimal 1..100
maximumCandidateWorkers is positive decimal
maxRetryTimes is decimal 0..98
```

WorkerGroup existence is a cross-owner command/admission check. The Task
catalog does not own WorkerGroup storage.

## Redis Shape

One Task descriptor is one Redis HASH:

```text
tc:{prefix}:task:{taskId}

workerGroupId       -> plain string
allocationRuleJson  -> JSON object
configJson          -> JSON object
```

Example:

```json
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

## Executable Spec

Contracts:

- [`kernel/task_runtime.py`](../../executable_spec/kernel/task_runtime.py)
- [`kernel/task_score_band.py`](../../executable_spec/kernel/task_score_band.py)
- [`kernel/task_item_score_band.py`](../../executable_spec/kernel/task_item_score_band.py)

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
