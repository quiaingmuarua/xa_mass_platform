# Event Language And Worker Control Roadmap

Last updated: 2026-05-18

Status: completed owner-baseline roadmap; deferred runtime/policy extensions
remain future work only when concrete pressure appears.

The first event-metadata and owner-adoption waves are complete. The direction is
not to grow a special `system event` family beside ordinary events. It is to
converge on one event language, reuse the existing event-runtime vocabulary
where it fits, keep owner-specific handlers explicit, and add worker control
behavior only through concrete owners.

This roadmap intentionally targets one event language, not one mandatory event
runtime. Worker-originated ingress, direct control-plane invocation, and future
delivery paths may share vocabulary without sharing the same execution model.
It also intentionally separates:

```text
event-language convergence
  -> routing and handler registration
  -> concrete lifecycle owners
  -> optional shared runtime optimization
```

## Completed Baseline

Implemented baseline:

- event owner inventory
- `PriorityClass`, `ResponseMode`, and `TargetScope` metadata on descriptor
  models
- catalog/API metadata visibility
- guards that keep metadata out of runtime owner paths
- current presence-only `WorkerSystemEventChannel` behavior
- split `DeliveryAcknowledgementMode` and `EventConvergenceMode` descriptor
  semantics
- route-only `KernelEventHandlerRegistry` for `TASK_ENGINE` /
  `WORKER_MANAGER` handlers
- `WorkerCapabilityAuthority` and worker capability self-report
- `WorkerCommandLifecycleOwner`, delivery handoff, and owner-decided
  acknowledgement/status ingest
- `WorkerStateProjectionOwner`
- `TaskStageEvidenceOwner`

Current truth is recorded in
[`EVENT_OWNER_BOUNDARY.md`](../../../xa-mass-engine/doc/baseline/EVENT_OWNER_BOUNDARY.md).

## North Star

```text
one event language
  -> not necessarily one event runtime
  -> target-specific handler
  -> owner-specific lifecycle
```

Examples:

```text
worker-targeted task work
  -> worker handler
  -> TaskResultService / TaskResultRuntime when convergence requires it

kernel-targeted worker heartbeat
  -> kernel handler
  -> reachability / presence owner

kernel-targeted worker capability report
  -> kernel handler
  -> capability authority owner
  -> WorkerManager / immutable WorkerRegistrySnapshot publication

kernel-targeted worker command request
  -> kernel handler
  -> WorkerCommandLifecycleOwner
```

`system event` is therefore not intended to become a permanent second event
species. The current `WorkerSystemEventChannel` is a narrow presence-only
implementation seam, not the final event taxonomy.

## Core Rule

```text
event language
  -> owns vocabulary, metadata, routing, and handler invocation

owner service
  -> owns state, lifecycle truth, repair, and side effects
```

Unifying event vocabulary must not unify lifecycle ownership.

Future paths may share event descriptors and request shape, but they must keep
their owners separate:

```text
task final result
  -> TaskResultService / TaskResultRuntime

worker command
  -> WorkerCommandLifecycleOwner

worker state report
  -> WorkerStateProjectionOwner

worker capability self-report
  -> capability authority owner

task item stage/progress
  -> TaskStageEvidenceOwner, not public final result
```

## Event Dimensions

Event behavior should be expressed through orthogonal dimensions, not through a
large category enum that later becomes a hidden owner switch.

### Already Present

Current descriptor metadata already includes:

- `TargetScope`
- `PriorityClass`
- `ResponseMode` as a compatibility response summary
- `DeliveryAcknowledgementMode`
- `EventConvergenceMode`

Current first-wave rule still holds:

- metadata is descriptive input only
- metadata does not directly mutate runtime truth
- descriptor metadata does not bypass owner-specific services

### Implemented Semantic Split

`EWC-1` separates two questions that `ResponseMode` previously compressed
together:

```text
delivery acknowledgement semantics
convergence semantics
```

Rationale:

- acknowledgement and result convergence are different questions
- a command may require ack without writing a public task result
- a stage event may advance item workflow without becoming a stable-final row
- a final task-work event may require both delivery acknowledgement and final
  convergence

Current implemented fields:

- `DeliveryAcknowledgementMode`
- `EventConvergenceMode`

`ResponseMode` remains catalog-visible as a compatibility response summary.
Owner behavior must not be inferred from `ResponseMode` alone. Future owner
phases may still add invocation- or stage-level contracts when descriptor
metadata is not enough to express concrete lifecycle semantics.

### Policy Boundary

These dimensions have narrow consumers:

| Dimension | Owner that may interpret it | Must not decide |
| --- | --- | --- |
| `TargetScope` | event routing / handler registration | lifecycle mutation by itself |
| `PriorityClass` | future queue-placement policy | hard-coded queue behavior in runtime mechanisms |
| delivery acknowledgement semantics | delivery / caller-response owner | task finality |
| convergence semantics | result or stage owner | transport reachability, worker command status |

Do not use a broad `EventCategory` switch where one of these narrower
dimensions or an owner-specific service is the real decision input.

## Target Model

The intended target split is:

```text
target = WORKER
  -> scheduled or delivered to a worker
  -> may converge through task result lifecycle

target = TASK_ENGINE / future WORKER_MANAGER
  -> dispatched to a kernel-side handler
  -> handler delegates into a concrete owner

target = OPERATOR
  -> reserved for operator-facing control paths when a real owner exists
```

This does not require one runtime owner. It only means that worker-targeted and
kernel-targeted events speak the same language instead of inventing unrelated
protocol families.

`TargetScope` labels routing domains, not future module or service boundaries.
In particular, current `WORKER_MANAGER` metadata must not be read as a promise
that a standalone worker-manager service already exists or is required.

## Active Core Line

### EWC-1: Event Semantic Model Convergence

Status: completed baseline.

Goal: freeze the event-language dimensions before adding more event families.

Scope:

- decide whether `ResponseMode` remains a compatibility summary or is split
  into clearer delivery-acknowledgement and convergence semantics
- define the minimum vocabulary required to keep acknowledgement and
  convergence separate without prematurely fixing every final enum
- document whether convergence semantics belong only to descriptors or may
  require invocation/stage-level contract input
- keep `PriorityClass` as policy input only
- keep `TargetScope` as routing metadata only
- document that `TargetScope` names routing domains rather than future service
  boundaries
- keep owner guards against descriptor-driven lifecycle mutation

Out of scope:

- no queue behavior change
- no worker command lifecycle
- no shared runtime envelope

Acceptance:

- one documented event-language model exists
- acknowledgement and convergence are no longer conflated in the descriptor
  model
- `ResponseMode` remains a compatibility summary, not owner behavior input
- no existing runtime owner behavior changes

### EWC-2: Kernel-Targeted Event Ingress

Status: completed route-only baseline.

Goal: prove that kernel-targeted events can use the ordinary event language
without creating a generic lifecycle owner or forcing every ingress path onto
one runtime implementation.

Scope:

- define the kernel-targeted handler registration pattern on top of the existing
  `MassEventRuntime` / `CoreEventDescriptor` concepts
- prove handler routing and registration only; this phase must not become the
  first lifecycle-owner migration
- use a deliberately narrow route-only probe as the first bridge from worker
  ingress into a kernel handler
- keep the concrete owner behind the handler explicit

Out of scope:

- no generic `SystemEventService`
- no command/state/capability lifecycle mutation in this phase
- no durable owner-state mutation as the acceptance target of this phase
- no presence-owner migration
- no `WorkerSystemEventChannel` retirement decision
- no transport-wide protocol rewrite

Acceptance:

- a kernel-targeted event reaches a kernel handler through the common event
  language
- handler routing is separate from lifecycle owner mutation
- `KernelEventHandlerRegistry` registers only `TASK_ENGINE` / `WORKER_MANAGER`
  targets and rejects `WORKER` targets
- the proof remains route-only and does not move current presence ownership
- `WorkerSystemEventChannel` remains the current presence-only ingress seam
  until a later phase has a concrete owner reason to revisit it

### EWC-3A: Capability Authority Model

Status: completed baseline.

Goal: define the single capability write authority before worker self-report
events are allowed to mutate scheduling truth.

Why this first:

- current WorkerGroup candidate source reads an active
  `WorkerRegistrySnapshot`, but that snapshot is still built from worker-level
  compatibility fields through `WorkerGroupCompatibilityProjection`
- direct self-report mutation would create multiple capability write truths:
  registration fields, report payloads, and WorkerGroup snapshot state
- the authority model must decide how registration truth and report truth are
  composed before any durable report ingestion exists

Required decisions:

- select the single active capability write authority
- define which fields are registration-owned, report-owned, and derived
- define source-scoped replace semantics:
  - a report replaces only the report-owned capability slice for that worker
  - it must not silently delete administratively approved registration bounds
  - owner-level composition produces the effective WorkerGroup capability view
- decide whether first self-report behavior may create or remove event bindings;
  if allowed, define which source owns those bindings
- define version, idempotency, and stale rejection rules
- define conflict handling for same worker/version with different payload
- define how `WorkerGroupCompatibilityProjection` becomes migration input only
- define snapshot publication:
  - `WorkerRegistrySnapshot` remains immutable
  - `WorkerManager` keeps publishing a volatile active snapshot reference
  - matching and candidate indexing consume a point-in-time snapshot
  - raw report storage never enters the matching hot path

Suggested first-version rules:

```text
registration-owned slice:
  worker identity, group identity, administrative project bounds,
  approved event-binding ceilings, static transport/adapter facts

report-owned slice:
  currently advertised event availability within approved bounds,
  runtime-advertised scheduling attributes, agent version,
  dynamic capacity only if explicitly allowed by policy

composition:
  source-scoped replace for report-owned facts
  authority-owner composition into effective WorkerGroupRecord values
  atomic publish of a new immutable WorkerRegistrySnapshot
```

Implemented EWC-3A baseline:

```text
worker registration rows / compatibility fields
  -> WorkerCapabilityAuthority
  -> immutable WorkerRegistrySnapshot
  -> WorkerManager volatile active snapshot publication
  -> WorkerCandidateIndex
```

Current behavior is intentionally unchanged. `WorkerCapabilityAuthority` still
uses `WorkerGroupCompatibilityProjection` as migration input, but
`WorkerManager` no longer calls the projection directly. This establishes the
single active composition and publication path that `EWC-3B` must reuse when
worker-originated capability reports are introduced.

Suggested report ordering:

```text
same workerId + same capabilityVersion + same payload:
  idempotent accept/no-op

same workerId + same capabilityVersion + different payload:
  conflict reject/no-op

lower capabilityVersion:
  stale reject/no-op

higher capabilityVersion:
  accept, compose effective capability, publish new snapshot
```

Out of scope:

- no worker-originated report ingress yet
- no command lifecycle
- no worker state projection
- no matching path reading report payloads

Acceptance:

- one documented capability authority model exists
- exactly one active path can publish effective capability truth into
  `WorkerRegistrySnapshot`
- `WorkerRegistrySnapshot` remains immutable and is swapped by reference, not
  mutated in place
- report-owned and registration-owned fields are separated
- replace/merge behavior is no longer an implementation-time decision
- version / idempotency / stale rejection / conflict behavior is explicit
- raw report payloads are excluded from matching and candidate-index hot paths

### EWC-3B: Worker Capability Self-Report Adoption

Status: completed baseline.

Goal: apply the event-language model to the first real kernel-side owner without
widening scope to the whole worker-control family. This is the first phase where
worker-originated capability events mutate effective scheduling truth, and it
must follow `EWC-3A`.

Why this first:

- it proves the whole useful path:
  `event language -> capability authority owner -> WorkerManager /
  WorkerRegistrySnapshot / WorkerCandidateIndex`
- it has less lifecycle ambiguity than command delivery / ack / expiry once the
  capability write-truth model is made explicit
- it strengthens scheduling truth without reopening task-result ownership
- it has the highest immediate kernel value, even though it is not necessarily
  the smallest concrete-owner change

Scope:

- kernel-targeted capability report event
- capability authority owner implementation from `EWC-3A`
- validation
- refresh path into `WorkerManager` / `WorkerRegistrySnapshot`
- trace proof

Implemented behavior:

```text
CoreEventRequest(event=kernel.worker.capability.report)
  -> KernelEventHandlerRegistry
  -> WorkerCapabilityReportEventHandler
  -> WorkerManager.applyWorkerCapabilityReport(...)
  -> WorkerCapabilityAuthority
  -> immutable WorkerRegistrySnapshot publication
  -> WorkerCandidateIndex candidate-source refresh
```

Implemented report authority rules:

- reports are scoped by `workerId`
- `capabilityVersion` orders reports for one worker
- same version + same payload is idempotent
- same version + different payload is rejected as conflict
- lower version is rejected as stale
- unknown worker reports are rejected
- reports replace only report-owned availability/attribute facts
- event availability is intersected with registration-approved event codes
- reports do not create worker identity, group identity, project bounds, or
  event-binding ceilings
- `WORKER_CAPABILITY_REPORT_APPLIED` trace evidence records accepted,
  idempotent, stale, conflict, and unknown-worker outcomes

Out of scope:

- no direct capability mutation from `WorkerSystemEventChannel`
- no matching path reading raw report payloads
- no worker command lifecycle

Acceptance:

- one concrete owner uses the common event language end to end
- event routing and owner mutation remain separately testable
- capability reports refresh registry truth through the approved owner path
- candidate-source proof remains `WorkerManager` /
  `WorkerRegistrySnapshot` / `WorkerCandidateIndex`
- report application follows the `EWC-3A` authority, versioning, idempotency,
  stale rejection, and source-scoped replace rules
- this slice proves enough value to justify broader owner adoption without
  requiring a shared runtime envelope first

## Planned Owner Lines

These are not optional side quests. They are the next owner lines that may be
added after the core ingress/routing model is proved. Each one must remain a
real owner rather than dissolve into the router.

### EWC-4A: Worker Command Lifecycle Owner Baseline

Status: completed baseline.

Goal: add worker command request/status ownership without routing
acknowledgements through task-result convergence.

Owner shape:

```text
kernel-targeted command request event
  -> WorkerCommandLifecycleOwner
  -> command read view
  -> trace evidence
```

Implemented behavior:

```text
CoreEventRequest(event=kernel.worker.command.request)
  -> KernelEventHandlerRegistry
  -> WorkerCommandRequestEventHandler
  -> WorkerCommandLifecycleOwner
  -> command read view
  -> WORKER_COMMAND_STATUS_TRANSITION trace evidence
```

Implemented status model:

```text
REQUESTED
  -> DELIVERY_ACCEPTED
  -> EXECUTION_ACCEPTED
  -> SUCCEEDED | FAILED | EXPIRED
```

`REQUESTED` may also move directly to `FAILED` or `EXPIRED`, and
`DELIVERY_ACCEPTED` may move directly to `SUCCEEDED`, `FAILED`, or `EXPIRED`
for future delivery implementations that do not split delivery and execution
acknowledgement.

Implemented request rules:

- `commandId` is the command identity
- duplicate same `commandId` and same payload is idempotent
- duplicate same `commandId` with different payload is conflict/no-op
- command request event payload is parsed by `WorkerCommandRequestEventHandler`
- lifecycle truth is stored and queried from `WorkerCommandLifecycleOwner`
- trace emits `WORKER_COMMAND_STATUS_TRANSITION`

Out of scope for this completed baseline:

- no worker delivery channel
- no command acknowledgement ingress implementation
- no retry or expiry scheduler
- no SDK worker shell
- no task result writes
- no task lifecycle mutation
- no generic event owner

Acceptance:

- command entry can use the event language
- command lifecycle truth remains in the command owner
- command request/status trace is visible without task-result rows
- command owner does not depend on task-result convergence, task-work dispatch,
  transport delivery, reachability, or load owners

### EWC-4B: Worker Command Delivery And Acknowledgement

Status: completed first behavior baseline.

Goal: extend the command owner with delivery handoff and owner-decided
ack/status ingress without routing acknowledgements through task-result
convergence.

Owner shape:

```text
WorkerCommandLifecycleOwner
  -> WorkerCommandDeliveryCoordinator
  -> WorkerCommandDeliveryPort
  -> WorkerCommandAcknowledgement / owner status ingest
  -> command read view
  -> trace evidence
```

Implemented behavior:

```text
command request already recorded in WorkerCommandLifecycleOwner
  -> WorkerCommandDeliveryCoordinator.deliver(commandId)
  -> WorkerCommandDeliveryPort.deliver(record)
  -> WorkerCommandLifecycleOwner.markDeliveryAccepted(...) on accepted handoff
  -> WorkerCommandLifecycleOwner.markFailed(...) on rejected/unavailable/failed handoff
  -> WORKER_COMMAND_STATUS_TRANSITION trace evidence
```

Owner-decided acknowledgement/status ingest is represented by
`WorkerCommandAcknowledgement` and `WorkerCommandLifecycleOwner.applyAcknowledgement(...)`.
The command owner, not task result convergence, decides whether an
acknowledgement can advance:

```text
DELIVERY_ACCEPTED
EXECUTION_ACCEPTED
SUCCEEDED | FAILED | EXPIRED
```

First-slice delivery failure rule:

- accepted handoff moves `REQUESTED -> DELIVERY_ACCEPTED`
- rejected, unavailable, or failed handoff moves `REQUESTED -> FAILED`
- retry and expiry scheduling are not implicit in the delivery port
- a future retry owner must be added explicitly if command delivery retry is
  needed

Package direction:

- shared value vocabulary in `xa-mass-base/com.xa.mass.command.core`
- lifecycle owner/store in `xa-mass-engine/com.xa.mass.engine.command`
- no owner/store implementation in the shared core package

Current implementation keeps the first behavior slice in
`com.xa.mass.engine.command`. The shared core package remains deferred until a
cross-module caller or worker SDK shell needs the vocabulary as a real public
boundary.

Explicit rule:

- command requests may enter through the event language
- command acknowledgement ingress is decided by
  `WorkerCommandLifecycleOwner`
- delivery acknowledgement, worker execution acknowledgement, and terminal
  command status may use different ingress semantics if the command owner
  requires it
- it does not replace `WorkerCommandLifecycleOwner`
- it does not replace the command state machine, repair path, or read model

Out of scope for this completed first behavior slice:

- no task-result writes
- no task lifecycle mutation
- no generic event owner
- no transport adapter delivery implementation
- no `WorkerSystemEventChannel` command delivery or ack path
- no task-work dispatch reuse
- no retry or expiry scheduler
- no worker SDK shell until the owner exists

Acceptance:

- command delivery remains owned by the command lifecycle design
- command lifecycle truth remains in the command owner
- command delivery must not
  default to task-work delivery or task-result convergence merely because the
  request entered as an event
- command ack/status never enters `TaskResultRuntime`
- command-specific delivery and acknowledgement are independently testable
  without transport or task-result dependencies

### EWC-5: Worker State Projection

Status: completed bounded projection baseline.

Goal: accept worker/device state reports into a bounded owner projection.

Scope:

- kernel-targeted report event handled by a state-projection owner
- validation and idempotency
- TTL/debounce
- bounded recent history
- approved derived scheduling evidence only when a later scheduling policy owner
  explicitly adopts it

Implemented behavior:

```text
CoreEventRequest(event=kernel.worker.state.report)
  -> KernelEventHandlerRegistry
  -> WorkerStateReportEventHandler
  -> WorkerStateProjectionOwner
  -> bounded per-worker projection/read view
  -> WORKER_STATE_REPORT_APPLIED trace evidence
```

Implemented projection rules:

- reports are scoped by `workerId`
- `stateVersion` orders reports for one worker
- same version + same payload is idempotent
- same version + different payload is rejected as conflict
- lower version is rejected as stale
- higher version replaces the latest projected state
- raw reports stay in a bounded per-worker recent-history window
- state reports do not create worker identity, mutate reachability, mutate load,
  or refresh matching/candidate-index truth
- no scheduling policy consumes state projection in this baseline

Out of scope:

- no raw state facts in matching/ranking
- no unbounded durable audit for every high-frequency report by default
- no transport presence mutation
- no worker capability mutation
- no task-result writes

Acceptance:

- raw reports do not become hot-path matching input
- no state evidence is exposed to scheduling in this baseline; a later policy
  owner may expose bounded derived evidence explicitly
- state projection owner is independently testable and guarded from
  reachability, load, scheduling, result, runtime, and transport owners

### EWC-6: Task Item Stage Semantics

Status: completed first owner baseline.

Goal: support multi-stage task work without polluting public final results.

Implemented behavior:

```text
CoreEventRequest(event=kernel.task.stage.evidence)
  -> KernelEventHandlerRegistry
  -> TaskStageEvidenceEventHandler
  -> TaskStageEvidenceOwner
  -> bounded per-task/message/stage projection
  -> TASK_STAGE_EVIDENCE_APPLIED trace evidence
```

Implemented owner rules:

- stage evidence is keyed by `taskId + messageId + stageName`
- `stageVersion` orders evidence for one stage key
- same version + same payload is idempotent
- same version + different payload is rejected as conflict
- lower version is rejected as stale
- higher version replaces the latest projected stage state
- raw evidence stays in a bounded per-stage recent-history window
- trace evidence includes `stableFinalResult=false`
- stage evidence does not write public result rows, final convergence,
  task finality, runtime work queues, scheduling, or dispatch state
- `/results` remains stable-final rows only

Out of scope:

- no public result widening
- no attempt to model every stage as a task-final result
- no progress-driven next-stage enqueue in this first owner baseline
- no task lifecycle mutation
- no result-runtime or task-work-runtime write

Acceptance:

- stage and final paths are independently provable
- public result semantics remain stable
- `TaskStageEvidenceOwner` is independently testable and guarded from public
  result convergence, work-runtime queues, scheduling, and dispatch owners

## Deferred Runtime And Policy Extensions

These become worthwhile only after the owner lines above expose real pressure.
They are not prerequisites for one event language.

### EWC-F1: Queue Placement Policy

Goal: introduce a real queue-placement policy seam before any priority-driven
queue behavior.

Scope:

- policy input may include `PriorityClass`
- first default must preserve current behavior

Out of scope:

- no category-driven front/back queue rule
- no fairness, aging, deadline, quota, or budget expansion in the first seam

Acceptance:

- queue placement is policy-owned before `PriorityClass` changes runtime order
- queue mechanisms remain free of hard-coded event-class branching

### EWC-F2: Shared Runtime Envelope Review

Goal: evaluate a shared runtime carrier only after concrete owners exist.

Do this only if command/state/stage paths expose real duplicate carrier cost.
Do not use a shared envelope to erase lifecycle owner boundaries.

## Proof Rule

Each behavior phase must add owner-local proof plus canonical trace evidence:

- kernel-targeted ingress path:
  event -> handler -> owner, with no hidden lifecycle owner in the router
- routing-only phase:
  event -> handler registration, with no durable owner mutation hidden inside
  the router
- command path:
  request -> delivery -> owner-decided ack/status ingest, not task-result rows
- state path:
  report -> bounded projection -> derived evidence, not raw hot-path matching
- capability path:
  report -> registry refresh -> candidate-source proof
- stage path:
  stage evidence and final-result convergence remain distinct

## Non-Goals

- no event microservice
- no `UnifiedEventService`
- no assumption that one shared runtime implementation is required by one event
  language
- no shared envelope before owner need is proven
- no task result / worker control / worker state multiplexing through one owner
- no queue behavior change directly from descriptor metadata
- no permanent special `system event` taxonomy beside the ordinary event
  language
- no assumption that command acknowledgement ingress must use the same event
  path as command request ingress

## Related Current Docs

- [EVENT_OWNER_BOUNDARY.md](../../../xa-mass-engine/doc/baseline/EVENT_OWNER_BOUNDARY.md)
- [SCHEDULING_KERNEL_BASELINE.md](../../../xa-mass-engine/doc/baseline/SCHEDULING_KERNEL_BASELINE.md)
- [../../../transport/TRANSPORT_BOUNDARY_BASELINE.md](../../../transport/TRANSPORT_BOUNDARY_BASELINE.md)
- [../../../doc/RESULT_BOUNDARY_BASELINE.md](../../../doc/RESULT_BOUNDARY_BASELINE.md)
