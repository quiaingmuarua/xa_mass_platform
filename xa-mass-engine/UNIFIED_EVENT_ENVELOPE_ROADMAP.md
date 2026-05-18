# Unified Event Envelope Roadmap

Last updated: 2026-05-16

Status: north-star roadmap only. This is not implemented baseline behavior.

## Summary

The long-term event kernel should not be split first by `task event` versus
`system event`. It should use one request/delivery envelope with policy
metadata, then route to the correct owner.

The goal is to let task work, worker commands, worker state reports, operator
control, and future task-item stage events share envelope shape and dispatch
metadata without sharing lifecycle ownership.

Current implementation note:

- task dispatch, result convergence, worker presence, and worker diagnostics
  still use their existing owner paths
- this roadmap does not grant permission to refactor those paths in one slice
- WorkerGroup capability and candidate-index ownership should be stabilized
  before behavior-changing event envelope work starts

## Core Rule

```text
unified event envelope
  -> common dispatch/request metadata
  -> owner-specific handling
```

The envelope is not lifecycle truth. It does not own task status, worker
availability, result finality, queue correctness, or trace schema.

Owner truth remains:

```text
task lifecycle
  -> engine

task final result
  -> TaskResultRuntime / TaskResultService

worker capability and administrative state
  -> worker-management

worker state report projection and derived scheduling facts
  -> worker-management

transport reachability
  -> transport plane

trace / diagnostics
  -> operator plane
```

## Event Dimensions

Event category may exist for docs, UI grouping, default permission hints, and
operator filtering:

```java
enum EventCategory {
    TASK_WORK,
    WORKER_COMMAND,
    WORKER_STATE_REPORT,
    OPERATOR_CONTROL,
    DIAGNOSTIC
}
```

Kernel behavior must not branch on category when a narrower policy dimension
exists. The behavior-driving dimensions are:

```java
enum PriorityClass {
    CONTROL,
    INTERACTIVE,
    STANDARD,
    BULK
}

enum ConvergenceMode {
    NONE,
    TASK_ITEM_STAGE,
    TASK_ITEM_FINAL
}

enum ResponseMode {
    NONE,
    ACK,
    FINAL_RESULT,
    STREAM
}

enum TargetScope {
    WORKER,
    WORKER_MANAGER,
    TASK_ENGINE,
    OPERATOR
}
```

Additional stable dimensions:

- `authScope`
- `idempotencyKey`
- `trace category`
- `deadline` / expiry
- optional `correlationId` and `parentEventId`

## Directional Envelope Shape

This is a direction model only. Do not implement this record prematurely if the
current owner paths do not need it.

```java
record UnifiedEventEnvelope(
        String eventCode,
        String eventId,
        String correlationId,
        String parentEventId,
        String taskId,
        String messageId,
        String stageId,
        String workerId,
        EventCategory category,
        PriorityClass priorityClass,
        ConvergenceMode convergenceMode,
        ResponseMode responseMode,
        TargetScope targetScope,
        String authScope,
        String idempotencyKey,
        Map<String, Object> payload,
        Map<String, Object> headers
) {}
```

The purpose is to keep future event additions from creating unrelated dispatch
protocols. It is not a replacement for task lifecycle, worker-management, or
transport ownership.

## Queue Placement Direction

Priority is input to queue placement policy. The first implementation may be
simple:

```text
CONTROL / INTERACTIVE
  -> enqueue front

STANDARD / BULK
  -> enqueue back
```

This must remain a policy choice, not a hard-coded event kernel rule. Future
policies may use aging, deadline, weighted fairness, per-worker budget, or
per-task quota without changing the envelope shape.

Do not add queue-priority behavior before there is a queue placement policy
seam. Directly hard-coding category or event code inside runtime queue logic is
not acceptable.

## Task Item Stage Direction

Task item execution may become multi-stage:

```text
logical messageId
  -> stage event
  -> stage event
  -> final event
```

Only `ConvergenceMode.TASK_ITEM_FINAL` may commit a visible final result row in
`TaskResultRuntime` and participate in task progress / terminal convergence.

`ConvergenceMode.TASK_ITEM_STAGE` may:

- emit trace/progress evidence
- write bounded stage residue if a later owner introduces it
- trigger a next event or task item

It must not close the logical item or appear in public `/results` as a final
result.

## Worker Command Defaults

`WorkerCommand` is a control or operations request sent to a worker. Examples:

- drain new work
- go offline or resume
- refresh capability
- reload configuration
- collect diagnostics
- rotate credentials
- restart worker agent
- ping / health probe

This is not task dispatch and not task result. Worker-management owns command
meaning and lifecycle. Transport owns delivery.

Directional envelope defaults:

```text
category=WORKER_COMMAND
targetScope=WORKER
convergenceMode=NONE
responseMode=ACK
priorityClass=CONTROL
```

Minimum lifecycle vocabulary should be reserved from the beginning:

```text
REQUESTED
DELIVERED
ACKED
RUNNING
SUCCEEDED
FAILED
EXPIRED
CANCELLED
```

The first implementation may use only a subset, but the contract must not be
fire-and-forget. A command needs at least a stable command id, target worker,
type, reason, requester, deadline or expiry, status, and result code/message.

## Worker State Report Defaults

`WorkerStateReport` is a worker/device-originated report about its environment,
health, capability, or runtime condition. Examples:

- network changed from WLAN to cellular
- low battery
- not charging
- thermal pressure
- memory pressure
- proxy degradation
- account health changed
- capability version changed

Worker-management owns report validation, ordering/idempotency, debounce, TTL,
projection, and derived scheduling facts. State reports are not automatically
durable audit events.

Directional envelope defaults:

```text
category=WORKER_STATE_REPORT
targetScope=WORKER_MANAGER
convergenceMode=NONE
responseMode=NONE or ACK
priorityClass=STANDARD or CONTROL
```

The default storage model should be:

```text
current state projection
  + bounded recent history
  + important transition audit
```

Do not write every high-frequency raw state report into trace/audit by default.

## Operator Control Defaults

Operator control events may eventually use the same envelope shape, but they
must not become task commands by default.

Directional defaults:

```text
category=OPERATOR_CONTROL
targetScope=OPERATOR or WORKER_MANAGER
convergenceMode=NONE
responseMode=ACK
priorityClass=CONTROL
```

Operator control may mutate worker-management, diagnostics, or future operator
planes according to explicit owner rules. It must not bypass task lifecycle,
worker-management command lifecycle, or transport delivery ownership.

## Worker State Model Split

Do not overload `OFFLINE`.

Worker-management should preserve three independent concepts:

```text
transportReachability
  ONLINE / OFFLINE / DEGRADED

dispatchAvailability
  ENABLED / DRAINING / DISABLED

administrativeState
  ACTIVE / PAUSED / DISABLED
```

Examples:

- network disconnected: `transportReachability=OFFLINE`
- low battery but still connected: `dispatchAvailability=DRAINING` or
  `DISABLED`, reason `LOW_BATTERY_NOT_CHARGING`
- operator disabled worker: `administrativeState=DISABLED`

Engine scheduling consumes the combined derived scheduling view. It must not
collapse these states into `Worker.status`.

## Derived Scheduling Facts

Raw device facts must not become engine scheduling truth.

Worker-management may receive:

```text
batteryLevel=12
charging=false
networkType=CELLULAR
```

It should derive facts such as:

```text
dispatchEnabled=false
dispatchDisabledReason=LOW_BATTERY_NOT_CHARGING
networkClass=METERED
powerClass=LOW
schedulingAttributes.powerMode=LOW_POWER
```

Engine scheduling may consume derived scheduling facts through
`WorkerSchedulingView` / reachability views. It must not own the interpretation
of raw battery, network, thermal, account, or device state.

## Transport Boundary

Transport owns delivery and presence facts:

```text
command delivery
dispatch delivery
result ingress transport normalization
connection presence / reachability
```

Transport must not decide that low battery means a worker should drain, or that
a failed diagnostic command means the worker is disabled. Those are
worker-management decisions.

## Owner Separation Rule

Keep these owner paths separate:

```text
TaskResultReport
  -> engine result convergence

WorkerStateReport
  -> worker-management state projection

WorkerCommandAck / WorkerCommandStatus
  -> worker-management command lifecycle
```

They may originate from the same worker process, and they may eventually share
an envelope shape, but they must not share the same owner path.

The unification target is envelope shape and dispatch metadata, not owner
meaning. In particular:

- `WorkerCommandAck` must not be written to `TaskResultRuntime`
- `WorkerStateReport` must not be treated as transport reachability truth
- `TaskResultReport` must not become a worker-management state update
- `TASK_ITEM_STAGE` must not be projected as a public final result

## Execution Gates

Do not start implementation before:

- worker-manager ownership boundary is stable
- `TaskResultRuntime` finality and archive behavior are stable
- transport reachability owner is stable
- architecture guards prevent WorkerContext resurrection
- server/SDK worker surfaces no longer depend on engine `WorkerManager`

Behavior-changing slices must state:

- selected phase
- owner paths touched
- forbidden cross-phase changes
- trace proof when lifecycle, scheduling, or result routing changes
- tests that prove no task-result or worker-management owner pollution occurred

## Phased Plan

```text
WM-E0: document unified event-envelope direction
  -> no code change

WM-E1: expand EventDefinition metadata
  -> priorityClass
  -> convergenceMode
  -> responseMode
  -> targetScope
  -> authScope / idempotency defaults
  -> no queue behavior change

WM-E2: introduce queue placement policy seam
  -> initial policy may map CONTROL/INTERACTIVE to FRONT
  -> initial policy may map STANDARD/BULK to BACK
  -> no fairness implementation required

WM-E3: add worker command lane
  -> command uses envelope metadata
  -> ack/status belongs to worker-management
  -> no TaskResultRuntime write

WM-E4: add worker state report lane
  -> worker reports device/account/health facts
  -> worker-management derives scheduling facts
  -> engine consumes derived facts only

WM-E5: add task item stage semantics
  -> TASK_ITEM_STAGE can produce trace/progress/next-stage work
  -> TASK_ITEM_FINAL remains the only public result/convergence commit

WM-E6: expand placement policy
  -> aging / deadline / weighted fairness / task quota / per-worker budget
  -> no envelope shape change
```

Do not start WM-E2 or later until current task-result convergence and
WorkerGroup capability / candidate-index boundaries remain green under
architecture guards.

## Risks

### Risk 1: Envelope Becomes Lifecycle Owner

If envelope metadata starts directly mutating task status, worker availability,
or result finality, the roadmap failed.

Mitigation:

- keep owner paths explicit
- use envelope fields only as policy/routing metadata
- require trace/test proof for lifecycle-affecting changes

### Risk 2: Event Category Drives Kernel Behavior

If runtime code branches on `TASK_WORK` versus `WORKER_COMMAND` rather than
narrow policy dimensions, future categories will create policy drift.

Mitigation:

- category is descriptive only
- priority, convergence, response, target, auth, and idempotency drive behavior

### Risk 3: Worker Events Pollute Task Result Convergence

Worker command acks, state reports, task stage events, and task final results
may eventually share an envelope shape. If owner routing is inferred from
category alone or everything is sent through result ingest, result convergence
becomes an operations event router.

Mitigation:

- `TaskResultReport` remains engine result input
- `WorkerStateReport` belongs to worker-management projection
- `WorkerCommandAck` belongs to worker-management command lifecycle
- `TASK_ITEM_STAGE` may produce trace/progress/next-stage work, but must not
  commit visible final result rows
- only `TASK_ITEM_FINAL` may write public result rows and drive task
  convergence
- transport may normalize multiple event classes at ingress, but it must route
  them to distinct owners

### Risk 4: Queue Priority Becomes Hardcoded

Directly pushing some event categories to the front of the queue may work early,
but it makes later fairness, aging, deadline, or quota policies difficult.

Mitigation:

- add a queue placement policy seam before behavior changes
- keep front/back as initial policy output only
- test that priority behavior does not bypass resource ownership

## Related Roadmaps

- [WORKER_GROUP_CAPABILITY_ROADMAP.md](./WORKER_GROUP_CAPABILITY_ROADMAP.md)
- [SCHEDULING_KERNEL_GUARDRAILS.md](./SCHEDULING_KERNEL_GUARDRAILS.md)
- [WORKER_CONTEXT_RETIREMENT_PLAN.md](./WORKER_CONTEXT_RETIREMENT_PLAN.md)
- [../doc/RESULT_BOUNDARY_BASELINE.md](../doc/RESULT_BOUNDARY_BASELINE.md)
