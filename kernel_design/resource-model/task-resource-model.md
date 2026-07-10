# Task Resource Model

Status: new-kernel design note, not current implementation truth and not an
implementation roadmap.

This note fixes the first-cut Task metadata required by phase-one task-worker
allocation. It does not recreate the historical Task shell, storage CRUD, or a
second lifecycle model.

## Core Decision

```text
TaskDescriptor = stable task allocation metadata
TaskScoreBandCore = task scheduling / lifecycle coordinate
TaskWorkRuntime = task backlog and work truth, defined separately
AssignmentDispatch = bounded consumer of task and worker owner facts
```

`TaskResourceCatalog` owns descriptor registration and the bounded allocation
descriptor load. It does not own general Task reads, task scheduling visibility,
task state transitions,
worker matching, allocation results, work items, or finality.

For the first allocation cut, one task belongs to exactly one worker group:

```text
Task -> one workerGroupId -> one bounded worker universe
```

`workerGroupId` is stable for the task. If work must compete in a different
worker group, create another task; do not switch worker groups during an active
allocation or dispatch round.

## TaskDescriptor

```text
TaskDescriptor
  taskId: string
  workerGroupId: string
  allocationRule: match-rule map
  config: map<string, string>
    priority: decimal text, 1..100
    runningVisibleMinimumCandidateWorkers: positive decimal text
```

The descriptor intentionally contains no generic Task status. Task scheduling
and lifecycle remain encoded and transitioned through task score-band.

The top level separates resource identity, worker-universe identity, the
independently mutable allocation rule, and one bounded configuration bucket.
Other policy fields must not grow as parallel top-level descriptor metadata.

`config` is not an unowned extension bag. Every accepted key must have a named
owner, consumer, required/default behavior, and validation rule. The first cut
accepts only the two required keys defined in this document and supplies no
hidden defaults.

| Config key | Required/default | Validation owner | Consumer | Validation |
| --- | --- | --- | --- | --- |
| `priority` | required, no default | TaskDescriptor contract | assignment-dispatch constraint construction | decimal text in `1..100` |
| `runningVisibleMinimumCandidateWorkers` | required, no default | TaskDescriptor contract | pre-dispatch activation check | decimal text in `1..maximumCandidateWorkers` |

### taskId

`taskId` is the stable task resource identity. In the first allocation cut:

```text
WorkerCandidateConstraint.CandidateId = TaskDescriptor.taskId
```

This lets one matcher call receive the complete set of tasks competing for one
worker group without introducing a separate candidate identity model.

`taskId` is globally unique. The first cut has no Project, tenant, or namespace
coordinate in Task identity, catalog keys, scheduling score, or allocation
handoffs.

### workerGroupId

`workerGroupId` selects the logical worker universe. Assignment-dispatch groups
one acquired task-id batch by this field and calls `WorkerCandidateMatcher` once
per worker group.

`homeBucketId` is not a Task field. It is worker score/runtime physical
placement derived internally from `workerGroupId`. Task metadata and allocation
rules must not carry worker score bucket identifiers.

### config["priority"]

`priority` is stored as decimal text and parsed by assignment-dispatch into the
worker-allocation contention priority inside one matcher batch:

```text
1   = lowest allocation priority
100 = highest allocation priority
```

It maps directly to `WorkerCandidateConstraint.priority`. It does not replace
task score ordering:

```text
task score
  decides when a task enters an allocation round

TaskDescriptor.config["priority"]
  decides which task consumes a matching worker first inside that round
```

There must not be an independent second allocation-priority field in
assignment-dispatch.

### allocationRule

The first cut stores the declarative worker constraint inline. It does not store
a named-rule indirection, executable function, registry reference, serialized
class, or policy-owner handle.

`allocationRule` is the `constraint_dsl` match-rule map evaluated against the
worker context. Dynamic read dependencies are derived from its `dynamic.*`
keys; they are not stored as a second caller declaration.

The rule is allocation intent, not executable owner truth. `constraint_dsl`
validates and evaluates it. Task catalog does not interpret the rule, and
worker runtime does not read Task metadata to discover it.

`allocationRule` is separate from `config` because it may be replaced as one
independent owner mutation later. The first cut still exposes create-only
registration. A future replacement must be explicit and fenced; it must not
silently rewrite the whole descriptor or leave an older allocation handoff
valid against a newer rule.

### config["runningVisibleMinimumCandidateWorkers"]

`runningVisibleMinimumCandidateWorkers` is the only built-in Task start
condition in the first cut. It is stored as positive decimal text and parsed by
assignment-dispatch before use. There is no generic condition registry,
condition enum, expression tree, or strategy extension point.

After phase-one batch matching:

```text
matchedWorkerCount >= runningVisibleMinimumCandidateWorkers
  -> RUNNING_VISIBLE condition satisfied

matchedWorkerCount < runningVisibleMinimumCandidateWorkers
  -> RUNNING_VISIBLE condition not satisfied
```

The check applies only before the task first enters running dispatch visibility:

```text
PRE_DISPATCH_VISIBLE + enough matched workers
  -> scheduling policy may transition task score to RUNNING_VISIBLE
```

It is not a continuous running gate:

```text
RUNNING_VISIBLE + worker count later below configured minimum
  != automatic pause
  != automatic terminal
  != automatic move back to PRE_DISPATCH_VISIBLE
```

Running availability, no-work behavior, pause, and terminal policy remain task
score/work scheduling concerns.

The matcher maximum is not Task config. `WorkerCandidateConstraint.limit` comes
from one uniform allocation configuration. The first executable cut fixes it
at:

```text
maximumCandidateWorkers = 100
```

That maximum caps work performed by the matcher. It is not the
`RUNNING_VISIBLE` condition and must not be copied into `TaskDescriptor`.
Descriptor admission must reject a minimum above this maximum; otherwise the
task can never satisfy its activation condition.

## Constraint Construction

The phase-one constraint is built directly from the descriptor and uniform
allocation configuration:

```text
TaskDescriptor
  config["priority"] --------------------------> constraint.priority
  allocationRule ------------------------------> constraint.match_rules

uniform allocation config
  maximumCandidateWorkers ---------------> constraint.limit
```

Conceptual result:

```python
WorkerCandidateConstraint(
    priority=int(descriptor.config["priority"]),
    limit=allocation_config.maximum_candidate_workers,
    match_rules=descriptor.allocation_rule,
)
```

`constraint_dsl` owns rule validation and evaluation. It remains independent of
Task and Worker models. There is no separate allocation-rule runtime or handler
registry in this cut.

Descriptor admission has already validated the accepted keys and their ranges.
Assignment-dispatch converts the validated text for use; it does not define a
second config schema and must not propagate unparsed configuration text into
matcher or score primitives.

Constraint construction is an in-memory transformation over one descriptor
batch. It must not perform one catalog point read or one rule lookup per task.

## TaskResourceCatalog

The first catalog surface is intentionally narrow:

```python
register_task_descriptor(descriptor)

load_task_allocation_descriptors(
    task_ids: Sequence[TaskId],
) -> Mapping[TaskId, TaskDescriptor | None]
```

`register_task_descriptor` is create-only. Duplicate registration conflicts;
it must not silently replace allocation metadata. The first cut does not expose
approval editing, update, replacement, or delete operations.

`load_task_allocation_descriptors` is batch-only and assignment-specific. It
avoids an N+1 Task read after score acquisition without creating a general Task
query surface. Missing or invalid rows are represented as `None` and fail closed
for that allocation round.

The catalog does not expose:

```text
getTask point read as the allocation mainline
updateTask whole-object replacement
deleteTask
listTasks
queryByStatus
queryByScore
task lifecycle mutation
worker matching
allocation result writes
work-item append / claim
result mutation
```

## Batch Allocation Read Path

The expected read path is:

```text
task_score.acquire_active_task_candidates(taskBatchLimit)
  -> taskIds

task_resource_catalog.load_task_allocation_descriptors(taskIds)
  -> taskId -> TaskDescriptor | None

group descriptors by workerGroupId
build TaskDescriptor -> WorkerCandidateConstraint
acquire bounded workers for that worker group
batch match tasks and workers
for PRE_DISPATCH_VISIBLE tasks, evaluate
  descriptor.config["runningVisibleMinimumCandidateWorkers"]
process allocation results
```

Task score remains the task candidate source. `TaskResourceCatalog` must not add
a status index, active-task query, scheduling queue, or background scanner.

## Validation

The TaskDescriptor validation contract is the single owner of descriptor schema
rules. Task admission runs it before registration; catalog registration and
Redis decode reuse the same validation instead of defining copies.
Assignment-dispatch may fail closed on an invalid decoded row, but it does not
redefine these rules.

Validation rejects:

```text
taskId empty
workerGroupId empty
config is not map<string, string>
config contains an undeclared key
config is missing either required key
config["priority"] is not decimal text in 1..100
allocationRule is not a valid constraint_dsl rule
config["runningVisibleMinimumCandidateWorkers"] is not decimal text in
  1..maximumCandidateWorkers
```

Existence of `workerGroupId` is a cross-owner admission check against the worker
group catalog. `TaskResourceCatalog` does not depend on worker storage or become
the WorkerGroup owner.

## Redis First-Cut Shape

One Task descriptor is one Redis `HASH` key:

```text
key
  tc:{prefix}:task:{taskId}

type
  HASH

fields
  workerGroupId       -> plain string
  allocationRuleJson  -> JSON object
  configJson          -> JSON object
```

The JSON fields use the public descriptor names:

```json
allocationRuleJson = {
  "dynamic.battery": {"$gte": 20}
}

configJson = {
  "priority": "80",
  "runningVisibleMinimumCandidateWorkers": "10"
}
```

`taskId` is derived from the requested key and reconstructed into the returned
descriptor. It is not duplicated as a HASH field.

Registration uses one atomic create-only operation:

```text
if key exists -> CONFLICT
else HSET all three fields -> CREATED
```

A small owner-local Lua script or equivalent Redis transaction may protect this
single-key create. It must not introduce update semantics.

Bounded batch read uses a pipeline containing one `HMGET` per task key:

```text
HMGET tc:{prefix}:task:{taskId}
  workerGroupId allocationRuleJson configJson
```

This is one client round trip over a bounded task-id batch, not one Redis
command round trip per task. Missing keys, missing required fields, malformed
integers, or invalid JSON fail that task closed. The allocation hot path does
not use `HGETALL`, scan descriptor keys, or maintain a second descriptor index.

## Minimal Python Shape

```python
@dataclass(frozen=True)
class TaskDescriptor:
    task_id: str
    worker_group_id: str
    allocation_rule: Mapping[str, object]
    config: Mapping[str, str]
```

These are descriptor values, not lifecycle or allocation-result records.

The interface skeleton is implemented in
[`py_example/kernel/task_runtime.py`](../py_example/kernel/task_runtime.py). It
contains only descriptor DTOs, create-only registration result, and the bounded
`TaskResourceCatalog` surface. No storage implementation exists in this slice.

The first cut deliberately uses string-only config values. Supporting both JSON
numbers and strings would add two representations for the same setting without
adding mechanism value. A future configuration center may become the source of
these values without changing the descriptor consumer contract.

In the first cut, `config` is resolved before registration and stored as a Task
descriptor snapshot. Scheduling never queries a configuration center. Running
tasks do not inherit later external changes; future refresh requires an explicit
fenced descriptor mutation.

## Explicit Non-Owners

TaskDescriptor and TaskResourceCatalog do not own:

```text
task score / score band
task lifecycle enum or shell status
approval execution
task backlog / work hashes
worker descriptors or dynamic values
worker score / lease
matched worker ids
task-to-worker allocation handoff
work claim
deliver seed
result finality
trace / diagnostics
```

The descriptor may explain stable allocation intent. It must never become a
snapshot of current runtime state.
