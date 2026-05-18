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
| `WorkerGroupRecord` / `EventBinding` / `EventKey` | worker capability line | capability truth and candidate-source inputs | response semantics, result finality |
| task dispatch handoff | assignment/transport boundary | already-bound task work delivery view | worker matching, allocation, finality |
| `TaskResultReport` | task result payload | worker task-result input | worker command ack, worker state report |
| `TransportResultEnvelope` | transport ingress metadata | adapter/route/attempt context around task results | final result classification |
| `WorkerSystemEventChannel` | transport ingress | current worker presence signals | command lifecycle, state projection, capability truth |
| `WorkerReachabilityView` | transport-derived read model | dispatchability evidence | device state, load, command status |
| `WorkerLoadView` | scheduling resource read model | active/reserved task-work capacity | reachability, device state, command lifecycle |
| trace/audit plane | evidence | historical facts | current runtime truth |

## Current Metadata Boundary

First-wave metadata now exists on `EventDefinition` and `CoreEventDescriptor`:

- `PriorityClass`
- `ResponseMode`
- `TargetScope`

Those fields are descriptive metadata and policy inputs only:

- `PriorityClass` does not directly alter assignment or transport queue order.
- `ResponseMode` does not choose result writes or task finality.
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
  -> WorkerCapabilityReportOwner
  -> WorkerManager / WorkerRegistrySnapshot refresh
```

## Hard Boundaries

- Worker command ack/status must not enter `TaskResultRuntime`.
- Worker state reports must not become transport reachability truth.
- Raw worker state must not enter matching/ranking directly; only bounded
  derived evidence from an approved owner may do so.
- Capability self-report must not bypass `WorkerManager`,
  `WorkerRegistrySnapshot`, or `WorkerCandidateIndex`.
- `WorkerReachabilityView` stays presence evidence, not generic health.
- `WorkerLoadView` stays task-work capacity evidence, not device state.
- `WorkerSystemEventChannel` must not import engine scheduling packages or
  mutate engine lifecycle state.
- A shared runtime envelope remains future-only until concrete owners exist and
  duplicate carrier shape becomes a real problem.
- Event routing must not become lifecycle ownership merely because a future
  kernel-targeted event uses the ordinary event language.

## Proof Surface

Current owner separation is proved by:

- `EngineSchedulingCoreArchitectureGuardTest`
- catalog/API tests for metadata visibility
- source guards keeping descriptor metadata out of kernel/runtime/transport
  owner paths
- transport system-event tests proving current presence behavior

Future behavior phases should add trace-observed scenarios only after they add a
real owner. Trace remains evidence, not runtime truth.
