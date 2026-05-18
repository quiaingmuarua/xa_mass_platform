# Event Owner Boundary

Last updated: 2026-05-18

Status: current event-like surface and system-event owner baseline.

This document describes the current owner split. It is not a unified event
runtime design.

## Core Rule

```text
event metadata
  -> describes invocation, response expectation, target scope, and policy hints

owner path
  -> owns lifecycle truth, mutation, repair, and side effects
```

Metadata and system-event ingress are not runtime truth by themselves.

## Current Owner Map

| Surface | Current owner | Owns | Must not own |
| --- | --- | --- | --- |
| `EventDefinition` | SDK/catalog metadata | registration and catalog shape | task lifecycle, dispatch, result finality |
| `CoreEventDescriptor` | core descriptor layer | descriptor fields and handler metadata | queue placement, result convergence |
| `KernelEventHandlerRegistry` | engine route registration | kernel-targeted handler registration for `TASK_ENGINE` / `WORKER_MANAGER` descriptors | task result, worker command, worker state, capability mutation, presence ownership |
| `WorkerCommandRequestEventHandler` | worker command event handler | parse kernel-targeted command requests and delegate to `WorkerCommandLifecycleOwner` | command lifecycle truth, delivery, ack/status ingress, task result |
| `WorkerCommandLifecycleOwner` | worker command lifecycle owner | command request/status truth, owner-decided acknowledgement/status ingest, and read view | task result convergence, task-work dispatch, transport lifecycle state |
| `WorkerCommandDeliveryCoordinator` / `WorkerCommandDeliveryPort` | worker command delivery handoff | command-specific delivery attempt coordination and handoff result mapping back into command lifecycle | task-work dispatch, task result convergence, transport lifecycle state |
| `WorkerCapabilityReportEventHandler` | worker capability event handler | parse kernel-targeted capability reports and delegate to `WorkerManager` / `WorkerCapabilityAuthority` | capability composition, matching, result convergence, presence ownership |
| `WorkerCapabilityAuthority` | worker capability composition owner | report version/idempotency/conflict rules and effective capability composition into immutable `WorkerRegistrySnapshot` | event routing, matching/ranking decisions, transport presence |
| `WorkerGroupRecord` / `EventBinding` / `EventKey` | worker capability line | capability truth and candidate-source inputs | response semantics, result finality |
| task dispatch handoff | assignment/transport boundary | already-bound task work delivery view | worker matching, allocation, finality |
| `TaskResultReport` | task result payload | worker task-result input | worker command ack, worker state report |
| `TransportResultEnvelope` | transport ingress metadata | adapter/route/attempt context around task results | final result classification |
| `WorkerSystemEventChannel` | transport ingress | current worker presence signals | command lifecycle, state projection, capability truth |
| `WorkerReachabilityView` | transport-derived read model | dispatchability evidence | device state, load, command status |
| `WorkerLoadView` | scheduling resource read model | active/reserved task-work capacity | reachability, device state, command lifecycle |
| trace/audit plane | evidence | historical facts | current runtime truth |

## Current Metadata Boundary

Descriptor metadata now exists on `EventDefinition` and `CoreEventDescriptor`:

- `PriorityClass`
- `ResponseMode` as a compatibility response summary
- `DeliveryAcknowledgementMode`
- `EventConvergenceMode`
- `TargetScope`

Those fields are descriptive metadata and policy inputs only:

- `PriorityClass` does not directly alter assignment or transport queue order.
- `ResponseMode` does not choose result writes or task finality; new owner
  designs should prefer the split acknowledgement and convergence fields.
- `DeliveryAcknowledgementMode` does not choose transport acknowledgement,
  command acknowledgement ingress, or command status mutation.
- `EventConvergenceMode` does not choose task-result writes, stage mutation,
  command status, or task finality.
- `TargetScope` does not open worker-command, worker-state, operator, or
  task-engine runtime paths.
- `TargetScope` labels routing domains only. Values such as `WORKER_MANAGER`
  must not be read as a promise that a standalone worker-manager service exists
  or is required.
- `EventCategory` remains deferred unless a later owner plan needs it.

Visible catalog/API surfaces may expose metadata, but read visibility does not
authorize a new owner path.

## Current System-Event Boundary

`WorkerSystemEventChannel` currently means presence ingress only:

- `publishWorkerOnline(...)`
- `publishWorkerOffline(...)`
- optional `publishWorkerHeartbeat(...)`

`TracingWorkerSystemEventChannel` may emit canonical presence evidence, but the
channel itself is not a lifecycle owner.

This is current implementation truth, not a claim that `system event` should
remain a permanent second event family. The active future roadmap treats future
worker-originated kernel events as ordinary events routed to kernel-side
handlers, while keeping lifecycle ownership in concrete owners.

That future direction does not imply one mandatory runtime implementation for
all event ingress. Shared language and shared runtime are separate decisions.

Future paths must have explicit owners before they mutate state:

```text
worker command
  -> WorkerCommandLifecycleOwner

worker state report
  -> WorkerStateProjectionOwner

worker capability self-report
  -> WorkerCapabilityAuthority
  -> WorkerManager / immutable WorkerRegistrySnapshot publication
```

The current kernel-targeted event path is route-only:

```text
CoreEventDescriptor(target=TASK_ENGINE|WORKER_MANAGER)
  -> KernelEventHandlerRegistry
  -> MassEventRuntime handler dispatch
```

That route does not mutate presence, worker command, worker state, capability,
task result, or scheduling lifecycle truth by itself.

## Hard Boundaries

- Worker command ack/status must not enter `TaskResultRuntime`.
- Current worker command request ingress is:

  ```text
  CoreEventRequest(event=kernel.worker.command.request)
    -> KernelEventHandlerRegistry
    -> WorkerCommandRequestEventHandler
    -> WorkerCommandLifecycleOwner
    -> command read view + WORKER_COMMAND_STATUS_TRANSITION trace
  ```

- EWC-4A records request/status truth only. It does not deliver commands to
  workers, does not decide acknowledgement ingress, and does not reuse task-work
  dispatch or task-result convergence.
- Current worker command delivery baseline is command-owner local:

  ```text
  WorkerCommandLifecycleOwner
    -> WorkerCommandDeliveryCoordinator
    -> WorkerCommandDeliveryPort
    -> WorkerCommandLifecycleOwner acknowledgement/status transition
    -> command read view + WORKER_COMMAND_STATUS_TRANSITION trace
  ```

- The first delivery slice does not wire a transport adapter. It proves the
  owner seam and status transitions only.
- Command delivery success moves the command to `DELIVERY_ACCEPTED`.
- Command delivery rejection, worker unavailability, or handoff failure closes
  the command as `FAILED` in the current baseline because retry/expiry
  scheduling is not implemented yet.
- Worker command acknowledgement/status ingress is command-specific
  `WorkerCommandAcknowledgement` input to `WorkerCommandLifecycleOwner`, not a
  task result row.
- Worker state reports must not become transport reachability truth.
- Raw worker state must not enter matching/ranking directly; only bounded
  derived evidence from an approved owner may do so.
- Capability self-report must not bypass the capability authority owner,
  `WorkerManager`, `WorkerRegistrySnapshot`, or `WorkerCandidateIndex`.
- Current effective capability composition flows through
  `WorkerCapabilityAuthority`. In the current EWC-3A baseline it still uses
  worker registration rows and worker-level compatibility fields as migration
  input; future raw reports must enter through the same owner instead of
  writing the snapshot directly.
- `WorkerRegistrySnapshot` remains an immutable point-in-time read view. Future
  capability mutation publishes a new active snapshot reference rather than
  mutating a snapshot in place.
- Raw capability reports must not enter matching, ranking, or candidate-index
  hot paths; only authority-composed effective capability truth may refresh the
  active worker registry snapshot.
- Current capability self-report ingress is:

  ```text
  CoreEventRequest(event=kernel.worker.capability.report)
    -> KernelEventHandlerRegistry
    -> WorkerCapabilityReportEventHandler
    -> WorkerManager.applyWorkerCapabilityReport(...)
    -> WorkerCapabilityAuthority
    -> immutable WorkerRegistrySnapshot publication
  ```

- Capability report application is source-scoped replace:
  - reports replace only the report-owned slice for one worker
  - reports cannot create worker identity, group identity, project bounds, or
    event-binding ceilings
  - event availability is intersected with registration-approved event codes
  - stale, conflicting, and unknown-worker reports are rejected as no-ops
- `WorkerReachabilityView` stays presence evidence, not generic health.
- `WorkerLoadView` stays task-work capacity evidence, not device state.
- `WorkerSystemEventChannel` must not import engine scheduling packages or
  mutate engine lifecycle state.
- A shared runtime envelope remains future-only until concrete owners exist and
  duplicate carrier shape becomes a real problem.
- Event routing must not become lifecycle ownership merely because a future
  kernel-targeted event uses the ordinary event language.
- Kernel-targeted handler registration must reject `WORKER` event targets.

## Proof Surface

Current owner separation is proved by:

- `EngineSchedulingCoreArchitectureGuardTest`
- catalog/API tests for metadata visibility
- source guards keeping descriptor metadata out of kernel/runtime/transport
  owner paths
- transport system-event tests proving current presence behavior

Future behavior phases should add trace-observed scenarios only after they add a
real owner. Trace remains evidence, not runtime truth.
