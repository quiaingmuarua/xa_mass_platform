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
  emptyCloseAtMillis
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
    empty_close_at_millis: TimeMillis | None = None
```

`emptyCloseAtMillis` is a shared empty-close threshold, not a TaskType flag or
hard Task deadline. External create commands may omit it. `KernelApplication`
materializes the omitted value before TaskRuntime persistence:

```text
TASK_DRIVEN -> 0
ITEM_DRIVEN -> creationTimeMillis + 3 days
```

An explicit non-negative absolute millisecond value overrides either default.
The persisted descriptor always contains a resolved value.

The config keys are exactly:

| Key | Meaning | Validation |
| --- | --- | --- |
| `priority` | Task scheduling priority used by RUNNING admission and Worker contention | decimal text in `0..99`; `0` highest |
| `maximumCandidateWorkers` | best-effort Task-local candidate target before matching | positive decimal text; retained but unused by `ITEM_DRIVEN` in this slice |
| `maxRetryTimes` | TaskItem retry budget source used when initializing Item scores | decimal text in `0..98` |

All values are strings in the first cut. Supporting two representations for
the same setting adds ambiguity without mechanism value.

`priority` is one Task scheduling intent. Task approval stores it as the
ADMISSION score suffix, and the
Worker matcher uses it when multiple RUNNING Tasks contend for Workers. There
must not be a second admission-priority or allocation-priority field.

`config` is not an unchecked extension bag. New keys require a named owner and
consumer. The Task resource model deliberately contains no minimum matching
Worker requirement: ADMISSION policy must not reserve or count Workers.

## Task Type And Allocation Rule

The public contract supports exactly two Task types:

| Task type | Rule owner | Worker acquisition | Candidate cache |
| --- | --- | --- | --- |
| `TASK_DRIVEN` | Task | `PRECOMPUTED` | enabled |
| `ITEM_DRIVEN` | TaskItem | `TARGETED` | forbidden |

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

Callers do not choose rule owner, cache participation, warmer participation, or
acquisition strategy independently. Scheduling derives only those
Worker-acquisition fields in `ResolvedTaskSchedulingProfile` from `TaskType`.
Empty close is a shared Task lifecycle policy and is not part of that profile.
The two rule forms cannot be mixed inside one Task. Append validates this
contract once; claim and dispatch trust it rather than reclassifying every
Item.

Both types use periodic RUNNING scans, Task dispatch, shared empty recheck, and
the explicit close command. Append-trigger acceleration and a separate hard
deadline scanner remain deferred policies.

### TaskType Scenario Gate

`TaskType` names one workload scenario that the kernel supports vertically. It
is not a convenient label for a caller-selected combination of rule owner,
cache, acquisition, trigger, or termination policies.

The current scenarios are:

```text
TASK_DRIVEN
  every Item inherits one complete Task-level Worker rule
  candidate computation is reusable across Items and dispatch rounds
  precomputation and cache amortize repeated Worker-selection cost

ITEM_DRIVEN
  every Item owns its complete bounded Worker target rule
  Item rules may all be equal or may differ
  Worker selection is paid only when that Item is actually dispatched
```

The hard distinction is rule ownership and candidate reuse, not traffic shape.
Either type may serve RPC-style or batch-oriented callers, and either may see
dense or sparse Item arrival. A `TASK_DRIVEN` Task may carry different payload
parameters while all Items reuse one Worker rule. An `ITEM_DRIVEN` Task remains
Item-driven even when every Item happens to carry the same rule, because the
Item is still the rule owner and the kernel does not maintain Task-level
candidate evidence.

TaskType establishes no relative scheduling priority. It also does not imply a
latency class, synchronous versus asynchronous execution, Worker exclusivity,
or permission to preempt another Task's Worker lease. Task priority and
candidate-request priority remain explicit scheduling inputs and are evaluated
without deriving an ordering from `TASK_DRIVEN` or `ITEM_DRIVEN`.

A future TaskType is admitted only when a named workload cannot be represented
by either scenario without changing a scheduling invariant. The proposal must
identify the differing rule owner, Worker acquisition, cache authority, or
another acquisition invariant, and provide a vertical create-to-result/close
executable proof. A different close threshold, limit, cadence, priority,
fairness rule, retry interval, or other tuning value stays inside the existing
type's System Policy.

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
Item. ADMISSION timeSlot selects bounded observation-window membership; Task
priority orders members inside that window. The default System policy only
applies the global RUNNING soft limit. Every observed Task that does not enter
RUNNING is moved to its next priority-bucket recheck time.

```text
ADMISSION_VISIBLE
  -> due Item Task policy
  -> score-ordered priority + RUNNING soft-limit System policy
  -> ADMISSION_VISIBLE -> RUNNING_VISIBLE
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

`create_task` is create-only. It rejects an existing descriptor without
touching score, initializes a `PRE_REVIEW` score lease before creating missing
descriptor metadata, and never overwrites an existing descriptor HASH. The
owner-defined initialization suffix is opaque to TaskRuntime. Score is the
Task identity/lifecycle coordination owner; a descriptor HASH cannot
independently create a Task.

A retry may complete exactly one score-only interruption residue when the
existing score is still `PRE_REVIEW` and the descriptor key is absent. It
creates the descriptor without rewriting or releasing that existing score.

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

`eventCode` is stored and passed through to the selected Worker's local handler
dispatch. Kernel Task admission, matching, and dispatch do not compare it with
the WorkerGroup capability declaration. A Server may perform that semantic
validation before invoking append; it is not a Kernel scheduling gate.
`expireAtMillis` is the new-attempt cutoff:
TaskRuntime rejects an already-expired append, while Task dispatch final-fails
an Item that expires after append before acquiring a Worker. Existing claimed
attempts remain governed by their claim lease.

For `ITEM_DRIVEN`, every TaskItem carries the complete Worker allocation rule
for that Item. The rule is not a delta and does not merge with a Task rule,
because no Task rule exists. It does not change `workerGroupId`, which always
comes from `TaskDescriptor`.

Non-atomic broadcast or fan-out is normalized before scheduling into multiple
TaskItems. Each expanded Item has its own `messageId`, complete allocation rule,
claim, retry budget, `WorkerCommand`, and result; each still occupies exactly one
Worker slot. The kernel therefore does not turn one Item into a multi-Worker
assignment.

If a business operation truly requires all `N` Worker slots to be reserved and
started atomically, expansion alone would lose that all-or-none invariant. That
would be a separate gang-reservation mechanism with a bundle identity,
multi-lease commit, and bundle failure semantics. No such requirement is
assumed by the current kernel.

At the public Java TaskData ingress, the selected WorkerGroup must allow the
Item rule field through `itemAllocationFields`. The first Java cutover supports
only bounded `workerId $eq/$in`; dynamic fields return `INVALID`. The Python
mechanism oracle retains injectable bounded dynamic candidate-query coverage,
but that capability is not advertised by the current external append API.
TaskRuntime itself owns only canonical JSON and DSL syntax validation, not
Worker catalog or candidate-index policy.

The success-result HASH is last-success truth. It is separate from TaskItem
score outcome and does not store failure history. Java exposes a bounded
Task-scoped read of requested `messageId` values. It returns each opaque
last-success payload or `null` and does not infer pending or failure state.

## Scheduling Read Paths

Activation:

```text
TaskScoreBandCore.acquire_band_task_candidates(ADMISSION_VISIBLE, now, limit)
  -> TaskResourceCatalog.load_task_allocation_descriptors(taskIds)
  -> Task Admission Policy
  -> System Admission Policy
  -> TaskScoreBandCore.rewrite_score(... RUNNING_VISIBLE ...)
```

Worker allocation:

```text
CandidateWarmupSchedule.acquire_candidate_warmups(now, limit)
  -> TaskScoreBandCore.get_score_states(taskIds)
  -> retain current RUNNING/non-hard-paused Tasks with suffix 0
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

Both Task types use the same empty-close rule. At the maximum consecutive-empty
count, Task dispatch closes the Task only when `now >= emptyCloseAtMillis`.
Before that threshold it retains the maximum suffix and continues bounded
low-frequency checks. Any ACTIVE Item prevents close and resets a positive
suffix to zero. A server may still submit stronger deadline or business
evidence through `KernelApplication.close_task` at any time.

While suffix is positive, candidate warming must not acquire or renew Worker
leases. Existing cache and lease evidence is not actively deleted; it expires
naturally. A successful positive-to-zero reset emits a best-effort warmup hint
only for `TASK_DRIVEN`.

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
priority is decimal 0..99; lower values have higher priority
maximumCandidateWorkers is positive decimal
maxRetryTimes is decimal 0..98
emptyCloseAtMillis is a resolved non-negative absolute millisecond value
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
emptyCloseAtMillis  -> non-negative decimal absolute milliseconds
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

emptyCloseAtMillis = "0"
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
- [`kernel_jvm/task/redis`](../../../kernel_jvm/src/main/java/com/xa/mass/kernel/task/redis)
  implements the current public JVM `TaskRuntime` Item append and last-success
  read operations against the same Redis shape.

## Explicit Non-Owners

TaskDescriptor and TaskResourceCatalog do not own:

```text
Task score or lifecycle transition
approval execution
TaskItem score, claim, retry, or finality
Worker descriptor, dynamic value, score, or lease
matched Worker ids or candidate handoff
WorkerCommand or WorkerResult queues
transport
result classification, trace, or diagnostics
```

The descriptor explains stable intent. It must never become a snapshot of
current runtime state.
