# Unified Event Envelope Roadmap

Last updated: 2026-05-18

Status: proposed event-metadata and owner-boundary roadmap. This is not an
implemented baseline. The first wave must not implement a unified event
runtime, worker command lifecycle, worker state report projection, task item
stage semantics, or queue-priority behavior.

## Summary

The long-term direction is still useful: task work, task results, worker
commands, worker state reports, operator control, diagnostics, and future staged
task work should be able to share event metadata language without sharing owner
paths.

The next implementation wave is narrower:

```text
event-like surface inventory
  -> EventDefinition / CoreEventDescriptor metadata baseline
  -> catalog/API/documentation visibility
  -> owner guards
```

This roadmap does not create a new module, microservice, event bus, or
`UnifiedEventService`. It does not replace task lifecycle, result convergence,
transport delivery, worker capability, scheduling, or trace ownership.

The near-term goal is to standardize event metadata and prevent future event
features from creating unrelated protocol shapes or polluted owner paths.

## Core Rule

```text
event metadata
  -> describes invocation, response expectation, target scope, and policy hints

owner path
  -> owns lifecycle truth, state mutation, repair, and side effects
```

Event metadata is not lifecycle truth. It must not directly own:

- task status
- task result finality
- worker reachability
- worker dispatch availability
- runtime queue correctness
- trace schema
- operator command lifecycle

Owner truth remains:

```text
task lifecycle
  -> engine task owner

task final result
  -> TaskResultRuntime / TaskResultService

worker capability and candidate source
  -> WorkerGroup / WorkerCandidateIndex core line

transport delivery and reachability evidence
  -> transport plane

trace and diagnostics
  -> operator/trace plane
```

Future worker command and worker state-report owners may be added later, but
they are not part of the core line of this roadmap.

## Execution Shape

This roadmap has two layers:

```text
Core line
  -> UE-0 through UE-3
  -> inventory, metadata baseline, visibility, owner guards

Future extensions
  -> queue placement policy seam, worker command lifecycle, worker state report,
     task item stage semantics, unified runtime envelope, richer priority
```

Only UE-0 through UE-3 are first-wave scope. Future extensions require separate
approval and must not be bundled into the core line.

Each phase must be independently shippable:

- inventory phases must not change behavior
- metadata phases must preserve current runtime behavior
- API/documentation phases must not alter dispatch, scheduling, result, or
  transport semantics
- guard phases must prevent owner pollution without introducing new runtime
  paths
- no phase should require a later phase to restore correctness

## Non-Goals

This roadmap does not:

- create a new Maven module
- create an event microservice
- introduce a `UnifiedEventService`
- implement a runtime `UnifiedEventEnvelope` carrier in the core line
- refactor task dispatch, result ingest, or transport frames in one pass
- route task results, worker command acks, and worker state reports through one
  owner
- implement worker command lifecycle
- implement worker state report projection
- implement task item stage semantics
- change queue placement behavior
- implement fairness, aging, deadline, quota, or per-worker budget policy
- make `EventCategory` drive kernel behavior

## Current Surfaces To Respect

The current codebase already has concrete event-like surfaces. UE-0 must map
these before behavior changes:

```text
EventDefinition
  -> SDK-visible event registration and catalog shape

CoreEventDescriptor
  -> core runtime/control-plane descriptor shape

WorkerGroup EventBinding / EventKey
  -> worker capability and Stage-1 candidate source

Task dispatch handoff
  -> transport delivery owner after assignment

TaskResultReport
  -> worker payload for engine result convergence

TransportResultEnvelope
  -> transport ingress metadata around task results

Trace events
  -> lifecycle and diagnostics evidence

Transport queue diagnostics
  -> transport queue/read diagnostics only
```

Do not infer from this roadmap that those paths are already unified or should be
unified in one implementation slice.

## Metadata Dimensions

Core metadata may be added to `EventDefinition` and `CoreEventDescriptor`.
These fields are descriptive and policy-input metadata only.

### EventCategory

Directional values:

```java
enum EventCategory {
    TASK_WORK,
    DIAGNOSTIC,
    OPERATOR_CONTROL,
    WORKER_COMMAND,
    WORKER_STATE_REPORT
}
```

`EventCategory` is for docs, UI grouping, permission hints, and operator
filtering. Kernel behavior must not branch on category when a narrower owner or
policy dimension exists.

Core-line default:

```text
existing task/catalog events -> TASK_WORK
diagnostic-only events       -> DIAGNOSTIC when explicitly known
otherwise                    -> TASK_WORK until a future owner is introduced
```

### PriorityClass

Directional values:

```java
enum PriorityClass {
    CONTROL,
    INTERACTIVE,
    STANDARD,
    BULK
}
```

Core-line rule:

- store and expose metadata only
- do not change runtime queue placement
- default existing events to `STANDARD` unless a definition explicitly declares
  another value

Queue behavior belongs to a future queue placement policy seam.

### ResponseMode

Directional values:

```java
enum ResponseMode {
    NONE,
    ACK,
    FINAL_RESULT,
    STREAM
}
```

Core-line rule:

- use as caller expectation and documentation metadata
- do not use it to create new ack/result/stream runtime paths
- default existing task work that completes through task result convergence to
  `FINAL_RESULT`

### TargetScope

Directional values:

```java
enum TargetScope {
    WORKER,
    TASK_ENGINE,
    OPERATOR,
    WORKER_MANAGER
}
```

Core-line rule:

- use as owner/routing hint metadata
- do not let it bypass current owner paths
- default current worker-dispatched task work to `WORKER`

### Future Metadata

These are useful but not first-wave implementation requirements:

- `ConvergenceMode`
- `authScope`
- `idempotencyKey`
- `deadline` / expiry
- `correlationId`
- `parentEventId`
- `stageId`

`ConvergenceMode` in particular touches result finality and task stage
semantics. Keep it out of UE-1 unless a separate result/stage plan is approved.

## Directional Envelope Shape

This record is future-only. Do not implement it in UE-0 through UE-3.

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
        ResponseMode responseMode,
        TargetScope targetScope,
        Map<String, Object> payload,
        Map<String, Object> headers
) {}
```

The future purpose is to keep new event classes from creating unrelated
dispatch metadata shapes. It is not a replacement for task lifecycle,
`TaskResultRuntime`, WorkerGroup capability, transport delivery, or trace
ownership.

## Owner Separation Rules

Keep these owner paths separate even if future payloads share metadata shape:

```text
TaskResultReport
  -> TaskResultService / TaskResultRuntime

WorkerCommandAck / WorkerCommandStatus
  -> future worker command lifecycle owner

WorkerStateReport
  -> future worker state projection owner

Task item stage event
  -> future stage/progress owner, not public final result
```

Core-line guards must protect:

- `TaskResultReport` remains engine result input
- worker command ack must not be written to `TaskResultRuntime`
- worker state report must not be treated as transport reachability truth
- task stage event must not commit visible final result rows
- event category must not directly mutate task status, worker state, or result
  finality
- priority metadata must not directly change queue order without a queue
  placement policy seam

## Core Phase Plan

### Phase UE-0: Event Surface Inventory And Owner Map

Goal: no behavior change. Map current event-like surfaces and owner truth before
adding metadata fields.

Scope:

- inventory `EventDefinition` and `CoreEventDescriptor` fields and callers
- inventory worker capability `EventBinding` / `EventKey` usage
- inventory task dispatch handoff and transport delivery metadata
- inventory `TaskResultReport` and `TransportResultEnvelope`
- inventory trace events and transport queue diagnostics that mention
  event/queue metadata
- map each surface to its owner, payload shape, response expectation, and
  lifecycle truth

Out of scope:

- no new metadata fields
- no runtime behavior change
- no queue placement policy
- no worker command/state report implementation
- no unified envelope record

Acceptance:

- owner map lists each event-like surface and current owner
- owner map marks which paths must never share lifecycle ownership
- no code behavior changes
- next phase can add metadata to descriptor models without guessing owners

Suggested scan:

```powershell
rg -n "EventDefinition|CoreEventDescriptor|EventBinding|EventKey|TaskResultReport|TransportResultEnvelope|eventCode|queue|priority" xa-mass-sdk-api xa-mass-base xa-mass-engine transport xa-mass-sdk xa-mass-server
```

### Phase UE-1: EventDefinition Metadata Baseline

Goal: add minimal event metadata to existing descriptor models without changing
runtime behavior.

Scope:

- add metadata to `EventDefinition` and builder:
  - `EventCategory`
  - `PriorityClass`
  - `ResponseMode`
  - `TargetScope`
- add equivalent metadata to `CoreEventDescriptor`
- update conversions between `EventDefinition` and `CoreEventDescriptor`
- provide conservative defaults for existing definitions
- preserve current event registration and catalog behavior

Out of scope:

- no `UnifiedEventEnvelope`
- no queue behavior change
- no task item stage semantics
- no worker command/state report runtime
- no auth/idempotency/deadline implementation

Acceptance:

- existing event definitions continue to register
- metadata defaults are deterministic
- SDK and core descriptor conversions preserve metadata
- no dispatch, scheduling, result, transport, or queue behavior changes
- tests cover default metadata and explicit metadata round-trip

### Phase UE-2: Metadata Visibility And Documentation

Goal: expose metadata through existing catalog/API/doc surfaces so API consumers
can understand event behavior without changing runtime paths.

Scope:

- update catalog list/detail surfaces that already return `EventDefinition`
- update project event read surfaces if they expose event definitions
- update Knife4j/OpenAPI descriptions for event metadata
- update SDK/server README snippets if needed
- document defaults and non-behavioral nature of metadata fields

Out of scope:

- no new event endpoint family
- no worker command/state report endpoint
- no transport protocol migration
- no queue placement policy

Acceptance:

- API/catalog docs show metadata fields
- existing catalog tests remain green or are updated for typed metadata
- metadata visibility does not imply runtime behavior changes

### Phase UE-3: Owner Guards And Trace-Proof Boundary

Goal: add targeted guards that prevent event metadata from becoming lifecycle
truth or a hidden router.

Scope:

- source guard: `EventCategory` must not directly drive task lifecycle, result
  finality, or worker availability
- source guard: `PriorityClass` must not directly change queue placement without
  an explicit queue placement policy owner
- source guard: `TaskResultRuntime` / `TaskResultService` must not accept worker
  command ack or worker state report shapes
- source guard: transport result ingress must keep `TaskResultReport` as result
  payload owner
- docs: event metadata is policy input / description, not owner truth

Out of scope:

- no behavior-changing queue policy
- no unified runtime envelope
- no new worker control-plane owner

Acceptance:

- architecture guards cover the owner pollution risks
- no runtime behavior change
- future behavior-changing roadmap phases have explicit guardrails

## Future Extensions

Future extensions require separate approval and must not be bundled into UE-0
through UE-3.

### Future UE-F1: Queue Placement Policy Seam

Goal: introduce a policy seam before any priority-driven queue behavior.

Possible scope:

- add `QueuePlacementPolicy`
- policy input may include `PriorityClass`
- first default policy should preserve current behavior unless explicitly
  approved otherwise

Out of scope:

- no fairness, aging, deadline, quota, or budget policy in the first seam
- no category-driven queue behavior

### Future UE-F2: Worker Command Lifecycle

Goal: add worker command request/status ownership without routing acks through
task result convergence.

Possible scope:

- command id, target worker, type, deadline, requester, reason
- status vocabulary such as requested/delivered/acked/succeeded/failed/expired
- delivery through transport owner

Out of scope:

- no `TaskResultRuntime` write for command ack/status
- no task lifecycle mutation by worker command metadata

### Future UE-F3: Worker State Report Projection

Goal: accept worker/device health reports into a worker-state projection owner.

Possible scope:

- state report validation and idempotency
- debounce / TTL / bounded recent history
- derived scheduling facts

Out of scope:

- no raw device facts in engine scheduling
- no full durable audit for every high-frequency report by default

### Future UE-F4: Task Item Stage Semantics

Goal: define multi-stage task item execution without polluting public final
results.

Rules:

- stage evidence may produce trace/progress/next-stage work
- only an approved final-result path may commit visible final result rows
- public `/results` remains stable-final rows only

### Future UE-F5: Unified Runtime Envelope

Goal: introduce a shared runtime carrier only after metadata and owner guards
are stable.

Out of scope for first implementation:

- replacing all transport frames
- replacing `TaskResultReport`
- replacing task dispatch handoff
- replacing trace event schema

### Future UE-F6: Richer Priority Strategy

Goal: evolve queue placement policy after measured need.

Possible policy dimensions:

- deadline
- aging
- weighted fairness
- task quota
- per-worker budget

These are strategy additions, not event metadata ownership changes.

## Risks

### Risk 1: Envelope Becomes Lifecycle Owner

If metadata starts directly mutating task status, worker availability, or result
finality, the roadmap failed.

Mitigation:

- keep owner paths explicit
- use metadata as description / policy input only
- require guards before behavior-changing phases

### Risk 2: EventCategory Drives Kernel Behavior

If runtime code branches on category instead of owner-specific policy seams,
future categories will create policy drift.

Mitigation:

- category is descriptive
- policy seams consume narrower fields
- owner paths remain explicit

### Risk 3: Worker Events Pollute Task Result Convergence

Worker command acks, state reports, task stage events, and task final results
may eventually share metadata shape. If everything is sent through result
ingest, result convergence becomes an operations event router.

Mitigation:

- `TaskResultReport` remains engine result input
- worker command ack belongs to future worker command lifecycle
- worker state report belongs to future state projection owner
- task item stage must not commit public final result rows

### Risk 4: Queue Priority Becomes Hardcoded

Directly pushing categories or event codes to the front of a queue may work
early, but it makes later fairness, aging, deadline, or quota policies
difficult.

Mitigation:

- add a queue placement policy seam before behavior changes
- default first seam should preserve current behavior
- never hard-code category-to-front/back behavior in runtime queue code

## Related Roadmaps

- [WORKER_GROUP_CAPABILITY_ROADMAP.md](./WORKER_GROUP_CAPABILITY_ROADMAP.md)
- [SCHEDULING_KERNEL_GUARDRAILS.md](./SCHEDULING_KERNEL_GUARDRAILS.md)
- [../doc/RESULT_BOUNDARY_BASELINE.md](../doc/RESULT_BOUNDARY_BASELINE.md)
- [../transport/TRANSPORT_BOUNDARY_BASELINE.md](../transport/TRANSPORT_BOUNDARY_BASELINE.md)
