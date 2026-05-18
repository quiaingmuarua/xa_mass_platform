# Event Metadata Owner Boundary

Last updated: 2026-05-18

Status: first-wave event-metadata owner boundary baseline. This document was
updated through UE-1 descriptor metadata, UE-2 catalog/API visibility, and UE-3
owner guards. It records current owner paths and first-wave boundaries. It is
not a unified event runtime design.

## Purpose

The first event-metadata wave standardizes how event definitions describe
invocation expectations, target scope, and policy hints. It does not move
lifecycle truth, state mutation, repair, or side effects into a shared event
owner.

```text
event metadata
  -> describes behavior and policy inputs

owner path
  -> owns state, correctness, lifecycle, repair, and side effects
```

## Owner Map

| Surface | Current owner | Owns | Must not own |
| --- | --- | --- | --- |
| `EventDefinition` | SDK/catalog metadata | SDK-visible event registration, catalog shape, handler metadata, project membership metadata | task lifecycle, result finality, dispatch scheduling, worker state, transport delivery |
| `CoreEventDescriptor` | core event runtime descriptor | core runtime/control-plane event descriptor fields and handler registration metadata | task state mutation, queue placement, result convergence, worker reachability |
| `WorkerGroupRecord` / `EventBinding` / `EventKey` | worker capability and candidate-source line | worker capability truth, event binding identity, candidate-index inputs | invocation response semantics, result finality, transport delivery, trace schema |
| `TaskDispatchBatch` / `TaskDispatchBinding` / `TaskDispatchContext` | assignment-to-transport dispatch handoff | already-bound task work delivery view after assignment/resource admission | worker matching, allocation formulas, task lifecycle truth, result finality |
| `TaskDispatchItem` / `TransportDispatchEnvelope` | transport delivery plane | transport-facing dispatch payload, delivery id, adapter/route addressing | scheduling decisions, result convergence, worker capability truth |
| `TaskResultReport` | worker result payload | task work result payload consumed by result convergence | worker command ack, worker state report, operator-control response, trace/audit ownership |
| `TransportResultEnvelope` | transport ingress metadata | adapter/route/attempt metadata around a `TaskResultReport` | final result classification, task state mutation, worker command/status lifecycle |
| `WorkerSystemEventChannel` | transport/system-event ingress channel | worker-side system event delivery into the current transport/runtime integration | task result finality, worker capability truth by itself, reachability truth by itself |
| worker reachability view | transport evidence read model | online/offline reachability evidence used by matching | worker capability, load/capacity, generic state-report projection |
| `WorkerLoadView` | engine scheduling resource read model | active/reserved worker capacity and per-task active worker count | transport reachability, result finality, worker/device management CRUD |
| trace events | trace/audit plane | historical lifecycle and diagnostic evidence | runtime state mutation, repair, scheduling truth by itself |

## First-Wave Metadata Boundary

UE-1 descriptor metadata is currently limited to:

- `PriorityClass`
- `ResponseMode`
- `TargetScope`

Those fields are descriptive metadata and policy inputs only. They must not
change runtime behavior in UE-0 through UE-3.

First-wave rules:

- `PriorityClass` must not directly alter `TaskAssignWorker`, runtime queue, or
  transport queue ordering.
- `ResponseMode` must not choose `TaskResultRuntime` write paths, visible final
  result behavior, or task finality.
- `TargetScope` must not introduce worker-command, worker-state-report,
  operator-control, or task-engine runtime paths.
- `EventCategory` is deferred by default because category is easy to misuse as
  a runtime switch and the trace sink already has a separate category concept.
- No first-wave code should introduce `UnifiedEventService` or a runtime
  `UnifiedEventEnvelope` carrier.

UE-2 exposes this metadata through existing read surfaces only:

- `/api/v1/catalog/events`
- `/api/v1/catalog/events/{eventCode}`
- `/api/v1/projects/{projectCode}/events`
- `/api/v1/catalog/event-capabilities`

Those surfaces are descriptive read views. They do not authorize new owner
paths and do not change dispatch, scheduling, result convergence, or transport
delivery behavior.

UE-3 guards keep the first-wave metadata out of owner paths:

- engine scheduling, resource, runtime, matching, assignment, and worker owners
  must not import descriptor metadata or read descriptor metadata getters
  directly.
- runtime result and transport result-ingest owners must not consume
  `ResponseMode` or worker command/state-report shapes as task result truth.
- transport system-event and worker reachability owners must not consume
  `TargetScope` as a new worker command or worker state path.
- trace/audit category owner paths must stay separate from descriptor metadata.
- production source must not introduce `UnifiedEventService` or a runtime
  `UnifiedEventEnvelope` carrier in the first wave.

## Surface Inventory

Current descriptor and event-like surfaces to inspect before field changes:

- `xa-mass-sdk-api`
  - `EventDefinition`
  - `EventDefinitionRegistry`
  - `ProjectEventCatalogRegistry`
  - `ControlPlaneCatalog`
- `xa-mass-base`
  - `CoreEventDescriptor`
  - `MassEventRuntime`
  - `TaskDispatchBatch`
  - `TaskDispatchBinding`
  - `TaskDispatchContext`
- `xa-mass-engine`
  - `WorkerGroupRecord`
  - `EventBinding`
  - `EventKey`
  - `WorkerCandidateIndex`
  - `TaskAssignWorker`
  - `TaskResultService`
  - `WorkerLoadView`
- `transport`
  - `TaskDispatchItem`
  - `TransportDispatchEnvelope`
  - `TaskResultReport`
  - `TransportResultEnvelope`
  - `WorkerSystemEventChannel`
  - transport queue diagnostics and delivery stores
- `xa-mass-trace` / `mass-trace-sink`
  - canonical trace event rows and trace sink category

Public descriptor/catalog surfaces remain cross-module API. Same-package helper
code may be concentrated only when it removes accidental exposure without
changing SDK/server/transport behavior.

## Future Owner Paths

These future capabilities need separate owner roadmaps before implementation:

- worker command lifecycle
- worker command ack/status
- worker state report projection
- worker capability self-report through system events
- queue placement policy
- task item stage/progress semantics
- unified runtime envelope carrier

System events may carry these facts in the future, but the event channel must
not become the owner of every lifecycle. Each fact still needs a narrow owner
that validates, stores, projects, and repairs it.
