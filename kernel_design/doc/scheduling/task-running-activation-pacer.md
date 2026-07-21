# Task Running Activation Pacer

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskRunningActivationPacer` is the explicit admission boundary between an
approved Task and ordinary Worker allocation:

```text
PRE_DISPATCH_VISIBLE Task state
  -> Task Admission Policy
  -> System Admission Policy
  -> TaskScoreBandCore transition
  -> RUNNING_VISIBLE
```

Task score identifies the scheduling domain and supplies its ordered
coordinate. It does not encode why a Task may run. Policies observe owner truth
and decide; the score owner validates and applies the transition mechanism.

## Contracts

```python
class TaskAdmissionPolicy(Protocol):
    def filter_tasks(
        *,
        ordered_task_ids: Sequence[TaskId],
        descriptors: Mapping[TaskId, TaskDescriptor],
    ) -> tuple[TaskId, ...]: ...

class SystemAdmissionPolicy(Protocol):
    def select_tasks(
        *,
        ordered_task_ids: Sequence[TaskId],
        descriptors: Mapping[TaskId, TaskDescriptor],
    ) -> tuple[TaskId, ...]: ...
```

Policies receive only the bounded Task batch and descriptors. They return an
ordered subset with no duplicates. Returning an unknown or duplicate TaskId is
a contract failure; no transition from that invalid layer is attempted.

Policies do not write Task score, reserve Workers, publish candidate entries,
or mutate Task/TaskItem metadata.

## Default Task Policy

`DueTaskItemAdmissionPolicy` keeps Tasks for which:

```text
TaskItemScoreBandCore.has_due_active_items(taskIds)[taskId] == true
```

This is a bounded read-only Item-score query. It checks for one currently due
`ACTIVE` member per Task. It does not claim an Item or load payload.

A future Item does not consume a RUNNING slot early. Appending a due Item does
not directly move Task score; a later activation round observes it.

## Default System Policy

`PrioritySoftLimitSystemAdmissionPolicy` calculates:

```text
availableSlots = max(
  0,
  runningTaskSoftLimit - countRunningVisibleTasks,
)
```

`count_running_visible_tasks()` counts the complete `RUNNING_VISIBLE` band,
including future hold/pause coordinates. Existing RUNNING Tasks are not
demoted when the observed count exceeds the soft limit.

The policy orders the admitted batch by:

```text
priority descending: 100 first, 1 last
same priority: preserve PRE_DISPATCH scan order
```

It returns at most `availableSlots` Tasks. The limit is deliberately soft:
concurrent pacer instances may observe the same count and temporarily exceed
it. A hard cap would require an atomic permit owner, which is not part of this
mechanism.

## Round Flow

```text
1. scan due PRE_DISPATCH_VISIBLE ids with range + limit
2. batch load TaskDescriptor values; skip missing/corrupt rows
3. apply TaskAdmissionPolicy
4. validate ordered subset
5. apply SystemAdmissionPolicy
6. validate ordered subset
7. request PRE_DISPATCH_VISIBLE -> RUNNING_VISIBLE for selected Tasks
8. count only TRANSITIONED results
```

The transition uses the round timestamp and always initializes RUNNING suffix
to `0`, the ordinary Task dispatch lane. `STALE`, `NOOP`, and `INVALID` are not
counted; later bounded rounds may observe the Task again when appropriate.

No Worker is required for activation. A Task may enter RUNNING before any
Worker is registered. Worker capacity affects later allocation throughput, not
the lifecycle admission mechanism.

## Interface

```python
TaskRunningActivationConfig(
    task_batch_limit,
)

TaskRunningActivationPacer(
    task_score,
    task_catalog,
    task_admission_policy,
    system_admission_policy,
)
```

Zero-config assembly installs the two default policies and exposes only:

```json
{
  "systemPolicy": {
    "runningTaskSoftLimit": 100
  }
}
```

The field is optional, defaults to `100`, and must be a positive integer.

## Scenario-Bound Policy

The current chain is intentionally explicit:

```text
Task state
  -> Task policy
  -> System policy
  -> kernel transition
  -> observe again
```

Future quota, tenant, environment, or resource-estimate decisions may replace
or compose the System policy only for a named supported TaskType scenario.
Task-specific business start conditions follow the same rule. This is not an
open policy-combination surface: every added decision needs a concrete caller,
evidence, cutpoint, and vertical scenario proof. It must remain bounded over
owner facts and must not create a second transition path.

## Guardrails

- Do not pre-lease or match Workers for `PRE_DISPATCH_VISIBLE` Tasks.
- Do not use candidate-worker count as activation truth.
- Do not mutate Item score while checking due Item existence.
- Do not put fairness, quota, or resource-estimate semantics into Task score.
- Do not make the soft limit an implicit hard-cap promise.
- Do not move existing RUNNING Tasks backward when the limit is exceeded.
- Do not let a policy return Tasks outside the exact bounded input batch.
