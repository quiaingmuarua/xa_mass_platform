# Event Language And Worker Control Roadmap

Last updated: 2026-05-18

Status: active future roadmap after event-metadata baseline closure.

The first event-metadata wave is complete. The next direction is not to grow a
special `system event` family beside ordinary events. It is to converge on one
event language, reuse the existing event-runtime vocabulary where it fits, keep
owner-specific handlers explicit, and only then add worker control behavior.

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

Implemented first-wave baseline:

- event owner inventory
- `PriorityClass`, `ResponseMode`, and `TargetScope` metadata on descriptor
  models
- catalog/API metadata visibility
- guards that keep metadata out of runtime owner paths
- current presence-only `WorkerSystemEventChannel` behavior

Current truth is recorded in
[`EVENT_OWNER_BOUNDARY.md`](../baseline/EVENT_OWNER_BOUNDARY.md).

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
  -> WorkerCapabilityReportOwner
  -> WorkerManager / WorkerRegistrySnapshot refresh

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
  -> WorkerCapabilityReportOwner

task item stage/progress
  -> future stage owner, not public final result
```

## Event Dimensions

Event behavior should be expressed through orthogonal dimensions, not through a
large category enum that later becomes a hidden owner switch.

### Already Present

Current descriptor metadata already includes:

- `TargetScope`
- `PriorityClass`
- `ResponseMode`

Current first-wave rule still holds:

- metadata is descriptive input only
- metadata does not directly mutate runtime truth
- descriptor metadata does not bypass owner-specific services

### Future Semantic Questions

The next event-language convergence should separate two questions that the
current `ResponseMode` compresses together:

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

The final field names and values are intentionally not fixed yet:

- delivery acknowledgement may need to distinguish transport accepted, handler
  accepted, and business completed rather than collapse them into one `ACK`
  state
- convergence may ultimately live as descriptor default plus invocation/stage
  contract, rather than descriptor-only metadata

`ResponseMode` remains the current implemented metadata field. A future phase
may keep it as a compatibility summary or replace it with a clearer split, but
owner behavior must not be inferred from `ResponseMode` alone.

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
- acknowledgement and convergence are no longer conflated in future design
- immature delivery/convergence choices remain explicit design questions rather
  than accidental implementation commitments
- no existing runtime owner behavior changes

### EWC-2: Kernel-Targeted Event Ingress

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
- the proof remains route-only and does not move current presence ownership
- `WorkerSystemEventChannel` remains the current presence-only ingress seam
  until a later phase has a concrete owner reason to revisit it

### EWC-3: Worker Capability Self-Report

Goal: apply the event-language model to the first real kernel-side owner without
widening scope to the whole worker-control family. This is the first phase where
durable owner-state mutation is part of the acceptance target.

Why this first:

- it proves the whole useful path:
  `event language -> concrete owner -> WorkerManager /
  WorkerRegistrySnapshot / WorkerCandidateIndex`
- it has less lifecycle ambiguity than command delivery / ack / expiry once the
  capability write-truth model is made explicit
- it strengthens scheduling truth without reopening task-result ownership
- it has the highest immediate kernel value, even though it is not necessarily
  the smallest concrete-owner change

Scope:

- define the capability authority model before adding self-report mutation:
  - identify the single capability write truth
  - decide whether reports replace or merge prior capability declarations
  - decide whether reports may create or remove event bindings
  - define version / idempotency / stale-report rejection rules
  - decide whether mutation lands on `WorkerGroupRecord` truth or continues to
    write worker-level compatibility fields during the transition
- kernel-targeted capability report event
- capability-report owner
- validation
- refresh path into `WorkerManager` / `WorkerRegistrySnapshot`
- trace proof

Out of scope:

- no direct capability mutation from `WorkerSystemEventChannel`
- no matching path reading raw report payloads
- no worker command lifecycle

Acceptance:

- the capability authority model is documented before report mutation enters
  the kernel
- exactly one capability write truth is selected for the active path
- report application semantics for replace/merge, binding mutation, versioning,
  idempotency, and stale rejection are explicit and covered
- one concrete owner uses the common event language end to end
- event routing and owner mutation remain separately testable
- capability reports refresh registry truth through the approved owner path
- candidate-source proof remains `WorkerManager` /
  `WorkerRegistrySnapshot` / `WorkerCandidateIndex`
- this slice proves enough value to justify broader owner adoption without
  requiring a shared runtime envelope first

## Planned Owner Lines

These are not optional side quests. They are the next owner lines that may be
added after the core ingress/routing model is proved. Each one must remain a
real owner rather than dissolve into the router.

### EWC-4: Worker Command Lifecycle

Goal: add worker command request/status ownership without routing
acknowledgements through task-result convergence.

Owner shape:

```text
kernel-targeted command request event
  -> WorkerCommandLifecycleOwner
  -> command delivery handoff
  -> owner-decided ack/status ingest
  -> command read view
  -> trace evidence
```

Directional model:

- `commandId`
- `workerId`
- `commandType`
- requester/reason/deadline/idempotency key
- smallest status set that proves request, delivery, terminal result, and expiry

Package direction:

- shared value vocabulary in `xa-mass-base/com.xa.mass.command.core`
- lifecycle owner/store in `xa-mass-engine/com.xa.mass.engine.command`
- no owner/store implementation in the shared core package

Explicit rule:

- command requests may enter through the event language
- command acknowledgement ingress is decided by
  `WorkerCommandLifecycleOwner`
- delivery acknowledgement, worker execution acknowledgement, and terminal
  command status may use different ingress semantics if the command owner
  requires it
- it does not replace `WorkerCommandLifecycleOwner`
- it does not replace the command state machine, repair path, or read model

Out of scope for the first behavior slice:

- no task-result writes
- no task lifecycle mutation
- no generic event owner
- no worker SDK shell until the owner exists

Acceptance:

- command entry can use the event language
- command lifecycle truth remains in the command owner
- command delivery remains owned by the command lifecycle design; it must not
  default to task-work delivery or task-result convergence merely because the
  request entered as an event
- command ack/status never enters `TaskResultRuntime`

### EWC-5: Worker State Projection

Goal: accept worker/device state reports into a bounded owner projection.

Scope:

- kernel-targeted report event handled by a state-projection owner
- validation and idempotency
- TTL/debounce
- bounded recent history
- approved derived scheduling evidence

Out of scope:

- no raw state facts in matching/ranking
- no unbounded durable audit for every high-frequency report by default

Acceptance:

- raw reports do not become hot-path matching input
- only bounded derived evidence is exposed to scheduling

### EWC-6: Task Item Stage Semantics

Goal: support multi-stage task work without polluting public final results.

Rules:

- stage evidence may drive progress or next-stage work
- only approved final convergence semantics may enter public stable-final result
  commit
- `/results` remains stable-final rows only

Out of scope:

- no public result widening
- no attempt to model every stage as a task-final result

Acceptance:

- stage and final paths are independently provable
- public result semantics remain stable

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

- [EVENT_OWNER_BOUNDARY.md](../baseline/EVENT_OWNER_BOUNDARY.md)
- [SCHEDULING_KERNEL_BASELINE.md](../baseline/SCHEDULING_KERNEL_BASELINE.md)
- [../../../transport/TRANSPORT_BOUNDARY_BASELINE.md](../../../transport/TRANSPORT_BOUNDARY_BASELINE.md)
- [../../../doc/RESULT_BOUNDARY_BASELINE.md](../../../doc/RESULT_BOUNDARY_BASELINE.md)
