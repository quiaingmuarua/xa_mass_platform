# Task Running Activation Policy

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

`TaskRunningActivationPolicy` is the explicit admission boundary between an
approved Task and ordinary Worker allocation:

```text
ADMISSION_VISIBLE Task state
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

`RunningSoftLimitSystemAdmissionPolicy` calculates:

```text
availableSlots = max(
  0,
  runningTaskSoftLimit - countRunningCapacityTasks,
)
```

`count_running_capacity_tasks()` counts `RUNNING_VISIBLE` members except the
exact Kernel-private idle park. Public pause and other future holds remain
counted. Existing RUNNING Tasks are not demoted when the observed count exceeds
the soft limit.

The score owner first selects a bounded due observation window in ascending
`timeSlot` order. It then orders only those observed members by:

```text
priority ascending, 0 first and 99 last
same priority: timeSlot ascending, then taskId ascending
```

Priority does not change which Tasks enter the bounded observation window and
does not promise a global strict-priority scan.

It returns at most `availableSlots` Tasks. The limit is deliberately soft:
concurrent policy rounds may observe the same count and temporarily exceed
it. A hard cap would require an atomic permit owner, which is not part of this
mechanism.

## Round Flow

```text
1. consume one immutable ADMISSION `DueTaskObservation` batch from the shared
   `TaskSchedulingBatchSource`
2. apply TaskAdmissionPolicy to descriptor-backed Tasks
3. validate ordered subset
4. apply SystemAdmissionPolicy
5. validate ordered subset
6. request ADMISSION_VISIBLE -> RUNNING_VISIBLE for selected Tasks
7. reschedule every observed Task that did not transition
8. count only TRANSITIONED results
```

The transition uses the round timestamp and always initializes RUNNING suffix
to `0`, the ordinary Task dispatch lane. `STALE`, `NOOP`, and `INVALID` are not
counted.

Normal rejection reasons do not create separate pacing branches. Every
observed Task that did not successfully leave ADMISSION keeps its suffix and
receives:

```text
priorityBucket = admissionSuffix // 10
nextTime = roundNow
         + TaskScoreBandCore.SLOT_MILLIS
         + priorityBucket * priorityRecheckStepMillis
```

The built-in step is `1000ms`, producing ten monotonic cadence buckets over
Task priority `0..99`. Missing descriptors, Task-policy rejection,
System-policy rejection, and stale transition attempts all use this same
best-effort same-band rewrite. Concurrent close, band transition, or newer
recheck evidence wins through the existing score guard. Policy exceptions and
invalid policy output remain fail-fast contract errors and do not execute the
normal recheck phase.

No Worker is required for activation. A Task may enter RUNNING before any
Worker is registered. Worker capacity affects later allocation throughput, not
the lifecycle admission mechanism.

## Interface

```python
TaskRunningActivationConfig(
    priority_recheck_step_millis=1000,
)

TaskRunningActivationPolicy(
    task_score,
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

The public JSON field is optional, defaults to `100`, and must be a positive
integer. `priority_recheck_step_millis` is internal System Policy configuration
installed by assembly; it is not a Task field or public JSON option.

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
or compose the System policy only for a named supported workload.
Task-specific business start conditions follow the same rule. This is not an
open policy-combination surface: every added decision needs a concrete caller,
evidence, cutpoint, and vertical scenario proof. It must remain bounded over
owner facts and must not create a second transition path.

## Guardrails

- Do not pre-lease or match Workers for `ADMISSION_VISIBLE` Tasks.
- Do not use candidate-worker count as activation truth.
- Do not mutate Item score while checking due Item existence.
- Do not put fairness, quota, or resource-estimate semantics into Task score.
- Do not make the soft limit an implicit hard-cap promise.
- Do not leave observed rejected Tasks at the current due head indefinitely.
- Do not interpret `priority // 10` as changing the full `0..99` ordering value.
- Do not move existing RUNNING Tasks backward when the limit is exceeded.
- Do not let a policy return Tasks outside the exact bounded input batch.
