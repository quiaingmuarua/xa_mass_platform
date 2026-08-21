# Task Resource Model

Status: active new-kernel resource contract; Python executable spec
implemented; policy coverage partial.

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

`taskId` is globally unique in the kernel design. One Task chooses exactly one
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
| `priority` | Task scheduling priority used by RUNNING admission and Worker contention | decimal text in `0..99`; `0` highest |
| `maximumCandidateWorkers` | best-effort Task-local candidate target before matching | positive decimal text; retained but unused by `DIRECT_ITEM_RULE` in this slice |
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

## Worker Allocation

The Kernel contract supports exactly two Worker-allocation mechanisms:

| Mechanism | Rule owner | Worker acquisition | Candidate cache |
| --- | --- | --- | --- |
| `PRECOMPUTED_TASK_RULE` | Task | `PRECOMPUTED` | enabled |
| `DIRECT_ITEM_RULE` | TaskItem | `DIRECT` | forbidden |

The rule-shape constraints are:

```text
PRECOMPUTED_TASK_RULE
  TaskDescriptor.allocationRule is a Map; an empty object means no constraint
  TaskItems must not carry allocationRule

DIRECT_ITEM_RULE
  TaskDescriptor.allocationRule is null
  every appended TaskItem carries an allocationRule object
  an empty object means no Worker restriction within the Task WorkerGroup
```

The mechanism derives rule owner, acquisition strategy, warmer participation
and candidate-cache participation as one coherent allocation contract:

```text
PRECOMPUTED_TASK_RULE
  every Item inherits one complete Task-level Worker rule
  candidate computation is reusable across Items and dispatch rounds
  precomputation and cache amortize repeated Worker-selection cost

DIRECT_ITEM_RULE
  every Item owns its complete Worker rule; an empty object is unrestricted
  Item rules may all be equal or may differ
  Worker selection is paid only when that Item is actually dispatched
```

The distinction is rule ownership and candidate reuse, not traffic shape.
WorkerAllocationMechanism establishes no relative scheduling priority and does not imply a
latency class, synchronous versus asynchronous execution, Worker exclusivity,
or permission to preempt another Task's Worker lease. Task priority and
candidate-request priority remain explicit scheduling inputs and are evaluated
without deriving an ordering from `PRECOMPUTED_TASK_RULE` or `DIRECT_ITEM_RULE`.

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
of that reserved slot; it carries no scheduling policy meaning. It is
outside due scans and excluded from the RUNNING admission soft-limit count.
It is distinct from the public pause coordinate at `MAX_TIME_SLOT`; callers
cannot mint, select or release the park through a generic time rewrite.

Ordinary Item append remains a pure Task data write and never wakes a parked
Task. The bounded `TaskCallItemSubmission` command deliberately does not read
this descriptor or interpret either mechanism. It invokes the score owner's
idempotent idle-park release before and after bounded Item append. Server RPC
and Task Batch assembly decide when to use that command; explicit Task close
can terminate either idle disposition at any time.

Server exposes only two finite assembly profiles:

| Server profile | Worker allocation | Idle disposition |
| --- | --- | --- |
| `FINITE_PRECOMPUTED` | `PRECOMPUTED_TASK_RULE` | `CLOSE_WHEN_IDLE` |
| `REUSABLE_DIRECT` | `DIRECT_ITEM_RULE` | `PARK_WHEN_IDLE` |

Those names are Server API assembly choices; they are not additional Kernel
enums or score states.

`allocationRule` uses the independent constraint DSL and is evaluated by the
bounded Worker matcher. Example:

```json
{
  "worker.runtime": {"$eq": "python"},
  "platform.pool": {"$eq": "batch"}
}
```

The descriptor stores the rule snapshot. It does not store compiled matcher
state, current Worker properties, candidate Workers, or a policy handler object.
Constraint compilation/validation belongs to `constraint_dsl`; Worker field
resolution belongs to Worker runtime/matcher.

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

`eventCode` stores the full opaque Event Name and is passed through to the
selected Worker's local handler dispatch. Kernel Task admission, matching, and
dispatch do not parse it or compare it with the WorkerGroup catalog projection.
Server may use that projection to recommend a WorkerGroup, but its possible
staleness means it must not be promoted into a Kernel admission or dispatch
guarantee.
`expireAtMillis` is the new-attempt cutoff:
TaskRuntime rejects an already-expired append, while Task dispatch final-fails
an Item that expires after append before acquiring a Worker. Existing claimed
attempts remain governed by their claim lease.

For `DIRECT_ITEM_RULE`, every TaskItem carries the complete Worker allocation rule
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

The public Java TaskData ingress treats an `DIRECT_ITEM_RULE` allocation rule as an
opaque JSON-compatible map. It does not compile operators. `{}` uses one
bounded due-HOT Worker Score query within the Task's WorkerGroup; exact score
CAS chooses at most the requested count. A non-empty rule currently derives
request-local candidates only from bounded `workerId $eq/$equal/$in`; a
non-empty rule without that source fails closed. The remaining `worker.*`,
`platform.*`, and explicit `index.*` conditions form the complete rule.
Descriptor and `index.*` data are point-loaded only for the bounded candidate
IDs. They do not discover candidates or execute operators.
TaskRuntime owns canonical persistence, while the matcher owns DSL syntax and
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
  -> retain workerAllocationMechanism=PRECOMPUTED_TASK_RULE
  -> group by workerGroupId
  -> build Task-level WorkerCandidateRequest values
  -> bounded HOT-pool lease/match
  -> append candidate evidence
```

Every admitted Task enters `RUNNING_VISIBLE` with suffix `0`. When the complete
ACTIVE Item band is empty, Task Dispatch applies the descriptor's idle
disposition immediately. Any ACTIVE Item prevents close or park, and a
post-park check can exact-release a park installed concurrently with Task Call
append. Item execution retry remains TaskItem-score truth. A server may still
close the Task explicitly at any time.
Initial finite Items may be appended before approval; after a finite Task
reaches RUNNING with an empty ACTIVE band it may close immediately. Task Call
submission treats valid PRE_REVIEW and ADMISSION scores as score no-ops, so a
new due ACTIVE Item can still enter the ordinary activation path. The private
park is needed only for a later idle-to-active cycle.

Existing candidate cache and Worker lease evidence is not actively deleted
when a Task becomes idle; it expires naturally. `PRECOMPUTED_TASK_RULE` replenishment
continues through its existing dispatch and incomplete-warmup hints.

Task dispatch:

```text
due record-backed Items
  -> PRECOMPUTED_TASK_RULE: PRECOMPUTED request using Task rule
  -> DIRECT_ITEM_RULE: messageId-local DIRECT requests
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
workerAllocationMechanism is PRECOMPUTED_TASK_RULE or DIRECT_ITEM_RULE
idleDisposition is CLOSE_WHEN_IDLE or PARK_WHEN_IDLE
PRECOMPUTED_TASK_RULE requires a mapping allocationRule; an empty map means no constraint
DIRECT_ITEM_RULE requires null Task allocationRule
config is exactly map<string, string> with the three declared keys
priority is decimal 0..99; lower values have higher priority
maximumCandidateWorkers is positive decimal
maxRetryTimes is decimal 0..98
PRECOMPUTED_TASK_RULE forbids TaskItem allocationRule
DIRECT_ITEM_RULE requires a valid TaskItem allocationRule object; `{}` is unrestricted
```

WorkerGroup existence is a cross-owner command/admission check. The Task
catalog does not own WorkerGroup storage.

## Redis Shape

One Task descriptor is one Redis HASH:

```text
tc:{prefix}:task:{taskId}

workerGroupId       -> plain string
workerAllocationMechanism -> `PRECOMPUTED_TASK_RULE` or `DIRECT_ITEM_RULE`
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

## Executable Spec

Contracts:

- [`kernel/task_runtime.py`](../../executable_spec/kernel/task_runtime.py)
- [`kernel/task_score_band.py`](../../executable_spec/kernel/task_score_band.py)
- [`kernel/task_item_score_band.py`](../../executable_spec/kernel/task_item_score_band.py)
- [`scheduling/task_call_submission.py`](../../executable_spec/scheduling/task_call_submission.py)

Redis implementations:

- [`redis_runtime/task_runtime.py`](../../executable_spec/redis_runtime/task_runtime.py)
- [`redis_runtime/task_score_band.py`](../../executable_spec/redis_runtime/task_score_band.py)
- [`redis_runtime/task_item_score_band.py`](../../executable_spec/redis_runtime/task_item_score_band.py)
- [`kernel_jvm/task/redis`](../../../kernel_jvm/src/main/java/com/xa/mass/kernel/task/redis)
  implements create, Item append and last-success read operations against the
  same Redis shape. Java create fixes the owner-internal initial PRE_REVIEW
  suffix to `1`; no caller supplies score structure.

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
