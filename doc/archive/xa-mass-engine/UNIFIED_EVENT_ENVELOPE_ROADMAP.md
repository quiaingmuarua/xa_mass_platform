# Event Metadata And Owner Boundary Roadmap

Last updated: 2026-05-18

Status: first-wave baseline closed for event-metadata and owner-boundary
convergence. UE-0 through UE-3 are implemented as the current baseline. Guards
may be tightened as new event surfaces appear. This roadmap still must not
implement a unified event runtime, worker command lifecycle, worker state report
projection, task item stage semantics, or queue-priority behavior.

Progress:

- 2026-05-18: UE-0 owner-boundary baseline started. The event-like surface owner
  map is recorded in `EVENT_METADATA_OWNER_BOUNDARY.md`, and source guards
  prevent first-wave work from introducing a unified event runtime owner or
  letting event metadata drive queue, result-finality, worker-control, or
  reachability owners directly.
- 2026-05-18: UE-1 descriptor metadata baseline started. Shared
  `PriorityClass`, `ResponseMode`, and `TargetScope` metadata are available on
  `EventDefinition` and `CoreEventDescriptor` with conservative defaults and
  SDK/core descriptor round-trip coverage. `EventCategory` remains deferred.
  No dispatch, scheduling, result, transport, or queue behavior is changed.
- 2026-05-18: UE-2 catalog/API visibility started. Existing catalog event,
  project-event, and event-capability read surfaces expose `priorityClass`,
  `responseMode`, and `targetScope`. Console/UI rendering is a convenience read
  view, not the proof surface. The server event-capability response is typed as
  `EventCapabilityView` for OpenAPI/Knife4j visibility while preserving the
  JSON shape. No new endpoint family or runtime behavior was introduced.
- 2026-05-18: UE-3 owner guards closed for the first wave. Architecture guards
  prevent first-wave descriptor metadata imports and getter use from entering
  engine scheduling/resource/runtime owners, runtime result finality, transport
  delivery/result-ingest owners, trace category owners, or worker-control/state
  paths. `UnifiedEventService` and runtime `UnifiedEventEnvelope` remain
  forbidden in production source.

File-name note: this document keeps the historical
`UNIFIED_EVENT_ENVELOPE_ROADMAP.md` path so existing references stay stable.
The current first-wave roadmap is event metadata and owner-boundary convergence,
not unified runtime-envelope implementation.

## Summary

The long-term direction is still useful: task work, task results, worker
commands, worker state reports, operator control, diagnostics, and future staged
task work should be able to share event metadata language without sharing owner
paths.

The next implementation wave is narrower and should be named by the metadata
and owner-boundary work it actually performs, not by the future unified
envelope direction:

```text
UE-0 event descriptor owner map and surface inventory
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
  -> code concentration, inventory, metadata baseline, visibility, owner guards

Future extensions
  -> queue placement policy seam, worker command lifecycle, worker state report,
     task item stage semantics, unified runtime envelope, richer priority
```

Only UE-0 through UE-3 are first-wave scope. Future extensions require separate
approval and must not be bundled into the core line. Do not rename the first
wave to "UnifiedEventEnvelope"; that name belongs to the future runtime carrier
direction, not the current owner-boundary work.

Each phase must be independently shippable:

- code-convergence and inventory phases must not change behavior
- metadata phases must preserve current runtime behavior
- API/documentation phases must not alter dispatch, scheduling, result, or
  transport semantics
- guard phases must prevent owner pollution without introducing new runtime
  paths
- no phase should require a later phase to restore correctness

## First-Wave Contract

UE-0 through UE-3 may add descriptor metadata and documentation visibility, but
they must preserve the current owner paths:

- `EventDefinition` remains SDK/catalog metadata.
- `CoreEventDescriptor` remains the core event-runtime descriptor.
- `WorkerGroup` / `EventBinding` / `EventKey` remain worker capability and
  candidate-source truth.
- task dispatch handoff remains dispatch/transport delivery input.
- `TaskResultReport` remains the result-convergence payload.
- `TransportResultEnvelope` remains transport ingress metadata around task
  results.
- trace events remain historical/audit evidence.

The first wave must not introduce a shared runtime event owner. If a proposed
change needs to mutate task state, write final results, drive queue order,
deliver worker commands, or project worker state, it is outside UE-0 through
UE-3 and needs a separate roadmap.

## Value Loop

The first wave must produce a concrete but low-risk value loop:

```text
event definitions
  -> expose stable response / target / priority metadata
  -> catalog and API docs can explain event behavior
  -> owner guards prevent metadata from becoming runtime truth
```

This is intentionally smaller than queue priority, worker command, worker state
report, or task-stage behavior. If the metadata is not visible through catalog
or documentation and not protected by owner guards, the first wave is just field
churn and should not be considered complete.

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

Core-line status:

- optional descriptive metadata
- default first-wave recommendation: defer from UE-1
- reason: category is easy to misuse as a runtime switch and there is already a
  trace-sink `EventCategory` with a different owner meaning
- if implemented in UE-1, owner guards against category-driven lifecycle,
  result-finality, worker-state, or queue behavior must be added no later than
  UE-3

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
- do not directly map `PriorityClass` to `TaskRuntimeProfile.DispatchPriority`
  or `TaskAssignWorker` ordering
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
- do not let it decide `TaskResultRuntime` writes or task finality
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
- do not let `WORKER_MANAGER`, `OPERATOR`, or `TASK_ENGINE` scopes create
  command/state/control-plane runtime behavior in UE-0 through UE-3
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

### Phase UE-0: Descriptor Code Concentration, Visibility, And Owner Map

Status: implemented.

Goal: no behavior change. Concentrate descriptor/catalog/event-metadata related
code within current modules, shorten accidental visibility where possible, and
map current event-like surfaces and owner truth before adding metadata fields.

Scope:

- identify the current package/caller spread of `EventDefinition`,
  `CoreEventDescriptor`, catalog projections, and descriptor conversions
- concentrate descriptor conversion helpers in their owning module/package
  where this can be done without public API churn
- reduce accidental public visibility for same-package implementation helpers
  when callers do not cross real owner boundaries
- inventory `EventDefinition` and `CoreEventDescriptor` fields and callers
- inventory worker capability `EventBinding` / `EventKey` usage
- inventory task dispatch handoff and transport delivery metadata
- inventory `TaskResultReport` and `TransportResultEnvelope`
- inventory trace events and transport queue diagnostics that mention
  event/queue metadata
- map each surface to its owner, payload shape, response expectation, and
  lifecycle truth
- record the owner map in the owning engine documentation before adding
  metadata fields
- identify the public surfaces that must remain cross-module API and the helper
  surfaces that should stay package-local

Out of scope:

- no new metadata fields
- no runtime behavior change
- no queue placement policy
- no worker command/state report implementation
- no unified envelope record
- no module split
- no SDK/server/transport API behavior change

Acceptance:

- a documented owner map exists for event-like surfaces and explicitly states
  what each surface must not own
- descriptor/catalog helper code has a smaller and intentional package/caller
  surface where safe to change without API churn
- owner map lists each event-like surface and current owner
- owner map marks which paths must never share lifecycle ownership
- public descriptor/catalog surfaces that must remain cross-module are
  identified explicitly
- no code behavior changes
- next phase can add metadata to descriptor models without guessing owners

Suggested scan:

```powershell
rg -n "EventDefinition|CoreEventDescriptor|EventBinding|EventKey|TaskResultReport|TransportResultEnvelope|eventCode|queue|priority" xa-mass-sdk-api xa-mass-base xa-mass-engine transport xa-mass-sdk xa-mass-server
```

### Phase UE-1: EventDefinition Metadata Baseline

Status: implemented.

Goal: add minimal event metadata to existing descriptor models without changing
runtime behavior.

Scope:

- add metadata to `EventDefinition` and builder:
  - `PriorityClass`
  - `ResponseMode`
  - `TargetScope`
- defer descriptive `EventCategory` by default; add it only if UE-1 can keep it
  non-behavioral, avoid confusion with trace-sink category, and cover it with
  owner guards
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
- `PriorityClass` is not used to change `TaskAssignWorker`, runtime queue, or
  transport queue ordering
- `ResponseMode` is not used to choose result-convergence or task-finality
  behavior
- `TargetScope` is not used to route through new worker-management,
  operator-control, or task-engine owner paths
- `EventCategory`, if added, is not used as a runtime behavior switch and is
  clearly distinct from trace/audit category
- no dispatch, scheduling, result, transport, or queue behavior changes
- tests cover default metadata and explicit metadata round-trip

### Phase UE-2: Metadata Visibility And Documentation

Status: implemented.

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

Status: implemented for the first-wave guard baseline.

Goal: add targeted guards that prevent event metadata from becoming lifecycle
truth or a hidden router.

Scope:

- source guard: `EventCategory` must not directly drive task lifecycle, result
  finality, or worker availability
- source guard: `PriorityClass` must not directly change queue placement without
  an explicit queue placement policy owner
- source guard: `PriorityClass` must not directly map into
  `TaskRuntimeProfile.DispatchPriority` or lane ordering
- source guard: `ResponseMode` must not choose `TaskResultRuntime` write paths
  or visible final result behavior
- source guard: `TargetScope` must not introduce worker-command,
  worker-state-report, operator-control, or task-engine runtime paths
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

## Recommended First Slice

Start with UE-0 only. Do not add metadata fields in the first slice.

UE-0 deliverables:

- owner map for event-like surfaces and their lifecycle truth, recorded in
  [`EVENT_METADATA_OWNER_BOUNDARY.md`](./EVENT_METADATA_OWNER_BOUNDARY.md)
- inventory of `EventDefinition`, `CoreEventDescriptor`, `EventBinding`,
  `EventKey`, task dispatch handoff, `TaskResultReport`,
  `TransportResultEnvelope`, trace events, and transport queue diagnostics
- package/caller visibility cleanup only where it removes accidental exposure
  without API churn
- source guards against `UnifiedEventService` and runtime
  `UnifiedEventEnvelope` introduction in the first wave
- explicit list of public descriptor/catalog surfaces that must remain
  cross-module API

UE-0 success means UE-1 can add descriptor metadata without guessing owner
boundaries. It is not a failure if UE-0 changes mostly documentation and guard
tests; this phase is intentionally a convergence baseline before field churn.

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

Current direction is tracked in
[`WORKER_COMMAND_LIFECYCLE_ROADMAP.md`](./WORKER_COMMAND_LIFECYCLE_ROADMAP.md).

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

- [WORKER_GROUP_CAPABILITY_ROADMAP.md](../../../doc/archive/xa-mass-engine/WORKER_GROUP_CAPABILITY_ROADMAP.md)
- [SCHEDULING_KERNEL_GUARDRAILS.md](./SCHEDULING_KERNEL_GUARDRAILS.md)
- [SYSTEM_EVENT_OWNER_BASELINE.md](./SYSTEM_EVENT_OWNER_BASELINE.md)
- [WORKER_COMMAND_LIFECYCLE_ROADMAP.md](./WORKER_COMMAND_LIFECYCLE_ROADMAP.md)
- [../../../doc/RESULT_BOUNDARY_BASELINE.md](../../../doc/RESULT_BOUNDARY_BASELINE.md)
- [../../../transport/TRANSPORT_BOUNDARY_BASELINE.md](../../../transport/TRANSPORT_BOUNDARY_BASELINE.md)
