# Task Resource Model

Status: active new-kernel resource contract; Python executable spec
implemented; policy coverage partial.

This note fixes the first-cut Task metadata required by phase-one task-worker
allocation. It does not recreate the historical Task shell, storage CRUD, or a
second lifecycle model.

## Core Decision

```text
TaskDescriptor = stable task allocation metadata
TaskScoreBandCore = task scheduling / lifecycle coordinate
TaskRuntime = Task creation, canonical TaskItem records, and last-success result payloads
TaskItemScoreBandCore = Item score initialization, acquire, claim/retry, and outcome movement
AssignmentDispatch = bounded consumer of task and worker owner facts
```

`TaskRuntime` owns score-coordinated Task creation, TaskItem append, bounded
TaskItem record reads, and Task-scoped last-success result payloads. It invokes `TaskScoreBandCore` or
`TaskItemScoreBandCore` through their contracts but does not own, decode, or
write either score axis. `TaskResourceCatalog`
owns only the bounded allocation descriptor load. The catalog does not create
Task identity or decide duplicate-create conflicts. None of these surfaces owns
general Task reads, worker matching, allocation results, or result finality.

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
    maximumCandidateWorkers: positive decimal text
    runningVisibleMinimumCandidateWorkers: positive decimal text
    maxRetryTimes: non-negative decimal text, 0..98
```

The descriptor intentionally contains no generic Task status. Task scheduling
and lifecycle remain encoded and transitioned through task score-band.

The top level separates resource identity, worker-universe identity, the
independently mutable allocation rule, and one bounded configuration bucket.
Other policy fields must not grow as parallel top-level descriptor metadata.

`config` is not an unowned extension bag. Every accepted key must have a named
owner, consumer, required/default behavior, and validation rule. The first cut
accepts only the four required keys defined in this document and supplies no
hidden defaults.

| Config key | Required/default | Validation owner | Consumer | Validation |
| --- | --- | --- | --- | --- |
| `priority` | required, no default | TaskDescriptor contract | assignment-dispatch constraint construction | decimal text in `1..100` |
| `maximumCandidateWorkers` | required, no default | TaskDescriptor contract | best-effort live candidate collection target | positive decimal text |
| `runningVisibleMinimumCandidateWorkers` | required, no default | TaskDescriptor contract | pre-dispatch activation check | decimal text in `1..maximumCandidateWorkers` |
| `maxRetryTimes` | required, no default | TaskDescriptor contract | Task Item score initialization | decimal text in `0..98` |

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

### config["maxRetryTimes"]

`maxRetryTimes` is the number of claims allowed after the first claim fails or
expires. It is Task policy, not a TaskItem field and not a score parameter
accepted from append callers.

`TaskItemScoreBandCore` converts it to the internal ACTIVE suffix budget:

```text
initialClaimBudget = 1 + maxRetryTimes
initialSuffix = encodeRemainingClaimBudget(initialClaimBudget)
```

The `0..98` range keeps the v0 total claim budget in `1..99`, matching the
two-digit internal suffix coordinate. Scheduling callers may observe the
semantic remaining budget returned by acquire, but never decode or construct
the suffix. A different future score encoding may change the internal bound
without changing Item append semantics.

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
independent owner mutation later. The first cut exposes no replacement
operation. A future replacement must be explicit and fenced, but it must not
acquire or rewrite task score merely to update resource metadata. It must not
silently rewrite the whole descriptor or leave an older allocation handoff
valid against a newer rule. Task metadata mutation and scheduling visibility
remain separate owner operations.

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

`maximumCandidateWorkers` is Task-owned allocation configuration. Before one
allocation match, the pacer reads the current non-expired candidate count and
derives the remaining target:

```text
remainingCandidateWorkers =
  max(0, maximumCandidateWorkers - currentLiveCandidateCount)

WorkerCandidateConstraint.limit = remainingCandidateWorkers
```

`AssignmentDispatchRuntime` does not receive, enforce, or reinterpret this value. It
stores every successfully leased candidate entry supplied by the pacer under
one batch expiry. Its batched non-expired candidate count may be read by both
allocation and independent activation/score classification, but it is not
current Worker-validity proof.

This is a best-effort collection target, not a cross-pacer atomic hard cap.
Concurrent allocation rounds can observe the same prior count and temporarily
overshoot. Making it a hard cap would require candidate-slot reservation before
Worker matching or runtime-side post-match rejection; the first adds another
allocation owner and the second discards already leased expensive candidates.
Neither mechanism belongs in the first cut. Descriptor admission still rejects
a minimum above the Task maximum.

The built-in activation check is intentionally unbudgeted. If the minimum is
not satisfied, the Task remains `PRE_DISPATCH_VISIBLE`; activation does not
decrement suffix and does not write an automatic pause. Later bounded rounds
may evaluate the same condition again.

The check is exposed to the pacer as the function strategy
`minimum_candidate_workers_satisfied(TaskDescriptor, candidateWorkerCount)`.
It returns a boolean transition decision. The strategy may be replaced later,
but it does not own descriptor reads, candidate-count reads, or score writes.

## Constraint Construction

The phase-one constraint is built directly from the descriptor:

```text
TaskDescriptor
  config["priority"] --------------------------> constraint.priority
  config["maximumCandidateWorkers"]
    - current live candidate count ------------> constraint.limit
  allocationRule ------------------------------> constraint.match_rules
```

Conceptual result:

```python
WorkerCandidateConstraint(
    priority=int(descriptor.config["priority"]),
    limit=max(
        0,
        int(descriptor.config["maximumCandidateWorkers"])
        - current_live_candidate_count,
    ),
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

## Task Runtime Surfaces

The first mutation and read surfaces are intentionally separate:

```python
create_task(
    descriptor: TaskDescriptor,
    suffix: Suffix,
) -> TaskCreationResult

load_task_allocation_descriptors(
    task_ids: Sequence[TaskId],
) -> Mapping[TaskId, TaskDescriptor | None]
```

`create_task` receives an owner-defined opaque suffix and initializes a
`PRE_REVIEW` score lease before descriptor materialization. Kernel code does not
name or interpret that suffix. The score is the Task identity and lifecycle
coordination owner; the descriptor HASH cannot independently create a Task.
Duplicate create conflicts after score initialization.

`TaskRuntime` depends on the `TaskScoreBandCore` contract, not on a concrete
Redis score implementation. The Redis executable-spec assembly is:

```python
score_band: TaskScoreBandCore

task_runtime = RedisTaskRuntime(
    redis_client,
    score_band,
)
```

`RedisTaskRuntime` owns its descriptor Redis client explicitly. It must not
obtain that client from `score_band`, depend on `RedisTaskScoreBandCore`, or
call score implementation private clock / encoding methods. Score time and
score-coordinate calculation stay inside `TaskScoreBandCore`.

`load_task_allocation_descriptors` is batch-only and assignment-specific. It
avoids an N+1 Task read after score acquisition without creating a general Task
query surface. Missing or invalid rows are represented as `None` and fail closed
for that allocation round.

The catalog does not expose mutation. Neither surface exposes:

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
TaskItem append / claim
result mutation
```

Task Item append resolves `maxRetryTimes` through an owner-local Task metadata
read. It does not add `maxRetryTimes`, tag, timeSlot, or suffix to the append
contract and does not turn `TaskResourceCatalog` into a general Task query API.

## Batch Allocation Read Path

The expected read path is:

```text
task_score.acquire_band_task_candidates(RUNNING_VISIBLE, horizon, limit)
task_score.acquire_band_task_candidates(PRE_DISPATCH_VISIBLE, horizon, remainingLimit)
  -> taskIds + acquisition band context

task_resource_catalog.load_task_allocation_descriptors(taskIds)
  -> taskId -> TaskDescriptor | None

group descriptors by workerGroupId
build TaskDescriptor -> WorkerCandidateConstraint
acquire bounded workers for that worker group
batch match tasks and workers
append candidate evidence and rotate acquired same-band time

independent PRE_DISPATCH_VISIBLE activation round
  -> read descriptor.config["runningVisibleMinimumCandidateWorkers"]
  -> compare candidate-worker count
  -> transition only when the minimum is satisfied
```

Task score remains the task candidate source. `TaskResourceCatalog` must not add
a status index, active-task query, scheduling queue, or background scanner.

## Validation

The TaskDescriptor validation contract is the single owner of descriptor schema
rules. Descriptor construction validates identity and config; Task admission
compiles the DSL before score initialization. Redis decode reconstructs that
same descriptor contract, while the bounded matcher compiles the stored rule
for evaluation and isolates a corrupt rule to its candidate. These checks do
not define a second config schema inside assignment-dispatch.

Validation rejects:

```text
taskId empty
workerGroupId empty
config is not map<string, string>
config contains an undeclared key
config is missing any required key
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
  "maximumCandidateWorkers": "20",
  "runningVisibleMinimumCandidateWorkers": "10"
}
```

`taskId` is derived from the requested key and reconstructed into the returned
descriptor. It is not duplicated as a HASH field.

Creation is score-first:

```text
initialize PRE_REVIEW + ownerSuffix at leaseUntil = now + duration
  -> only one owner can hold that full score in the slot
HSET all descriptor fields in one descriptor-owner command
score-only exact observed-score release to the current 100ms timeSlot
  -> release accepted: CREATED
  -> release stale: descriptor remains provisional and later score owner converges
```

These are three owner-local operations, not one cross-key transaction. If the
owner disappears before descriptor write, the score becomes due after its duration.
That does not permit `initialize_score` again: later completion or recovery must
use a separate owner transition against the existing `PRE_REVIEW` score.
If the owner disappears after HSET but before release, descriptor metadata may
temporarily exist under the future creation lease. Score remains the only
scheduling truth. The descriptor HASH has no standalone create truth and no
independent duplicate-registration truth. A descriptor HASH without a score
member is orphan metadata; it cannot block score-owned creation and may be
overwritten by the successful creation owner.

Known failure release is best-effort and only reduces retry latency. A lost
release does not require reliable event delivery, a retry queue, or a repair
job; the duration-encoded `leaseUntil` makes the score due for later owner
processing. Release writes the current slot but never reopens initialization.

No Lua or transaction spans score and descriptor keys. The creation protocol
therefore does not require Redis key colocation and does not merge score owner
logic with descriptor storage logic.

Bounded batch read uses a pipeline containing one `HMGET` per task key:

```text
HMGET tc:{prefix}:task:{taskId}
  workerGroupId allocationRuleJson configJson
```

This is one client round trip over a bounded task-id batch, not one Redis
command round trip per task. Missing fields, malformed JSON, non-object JSON,
or invalid config values fail that task closed. DSL syntax is validated before
creation and compiled by the bounded matcher; a corrupt rule is isolated to
that candidate instead of aborting its Worker-group batch. The allocation hot
path does not use `HGETALL`, scan descriptor keys, or maintain a second
descriptor index.

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
[`executable_spec/kernel/task_runtime.py`](../../executable_spec/kernel/task_runtime.py). It
contains the descriptor DTO, the Task owner `TaskRuntime`, and the bounded
read-only `TaskResourceCatalog` surface. It now also defines `TaskItem`, append
result DTOs, and the abstract append/bounded Item-read operations. The independent
Item-score interface lives in
[`executable_spec/kernel/task_item_score_band.py`](../../executable_spec/kernel/task_item_score_band.py).
The Redis Task implementation lives in
[`executable_spec/redis_runtime/task_runtime.py`](../../executable_spec/redis_runtime/task_runtime.py)
and implements score-leased Task creation, descriptor HASH batch loading,
latest-write TaskItem HASH append, bounded TaskItem record reads, and
last-success result HASH writes/reads.
[`executable_spec/tests/test_redis_task_runtime_integration.py`](../../executable_spec/tests/test_redis_task_runtime_integration.py)
is the real-Redis proof for one-owner-per-slot creation, stale-owner rejection,
redis-py pipeline compatibility, binary response decoding, and corrupt-row isolation.

Current Python `TaskDescriptor` validates all four required config keys including
`maxRetryTimes`. TaskItem DTOs and `TaskItemScoreBandCore` are executable interface
contracts with model tests. The Redis Item-score owner is implemented in
[`executable_spec/redis_runtime/task_item_score_band.py`](../../executable_spec/redis_runtime/task_item_score_band.py)
with Fake and real-Redis proofs. Redis TaskItem records are written separately
by `TaskRuntime`: HSET replaces the latest record for `messageId`, while ItemScore
initialization remains `ZADD NX` and never resets an existing scheduling identity.

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
Task Item records / Item score
worker descriptors or dynamic values
worker score / lease
matched worker ids
task-to-worker allocation handoff
Item score claim
deliver seed
result finality
trace / diagnostics
```

The descriptor may explain stable allocation intent. It must never become a
snapshot of current runtime state.
