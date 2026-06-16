# Event Owner Boundary

Last updated: 2026-06-13

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
| `WorkerControlService` | owner-backed worker-control service surface | direct entry/read handoff for worker command, capability report, and worker state projection plus canonical trace emission | command/state/capability lifecycle truth, event payload parsing, transport delivery |
| `WorkerCommandRequestEventHandler` | worker command event handler | parse kernel-targeted command requests and delegate to `WorkerControlService` | command lifecycle truth, delivery, ack/status ingress, task result |
| `WorkerCommandLifecycleOwner` | worker command lifecycle owner | command request/status truth, approved command catalog admission, deadline expiry, owner-decided acknowledgement/status ingest, and read view | task result convergence, task-work dispatch, transport lifecycle state |
| `WorkerCommandDeliveryCoordinator` / `WorkerCommandDeliveryPort` | worker command delivery handoff | command-specific delivery attempt coordination and handoff result mapping back into command lifecycle | task-work dispatch, task result convergence, transport lifecycle state |
| `WorkerCapabilityReportEventHandler` | worker capability event handler | parse kernel-targeted capability reports and delegate to `WorkerControlService` | capability composition, matching, result convergence, presence ownership |
| `WorkerCapabilityAuthority` | worker capability composition owner | report version/idempotency/conflict rules and effective capability composition into immutable `WorkerRegistrySnapshot` | event routing, matching/ranking decisions, transport presence |
| `WorkerStateReportEventHandler` | worker state event handler | parse kernel-targeted state reports and delegate to `WorkerControlService` | state projection truth, reachability, load, matching, result convergence |
| `WorkerStateProjectionOwner` | worker state projection owner | bounded per-worker state projection, version/idempotency/conflict rules, and recent diagnostic history | transport presence, worker capability truth, load, matching/ranking, task result |
| `TaskStageEvidenceService` | owner-backed task stage evidence service surface | direct entry/read handoff for task stage evidence plus canonical trace emission | stage projection truth, public result rows, final convergence, task lifecycle |
| `TaskStageEvidenceEventHandler` | task stage event handler | parse kernel-targeted task stage evidence and delegate to `TaskStageEvidenceService` | stage projection truth, public result rows, final convergence, task lifecycle |
| `TaskStageEvidenceOwner` | task stage evidence owner | bounded per-task/message/stage projection, version/idempotency/conflict rules, and recent diagnostic history | public result rows, final convergence, task-work queues, scheduling, dispatch |
| `WorkerGroupRecord` / `EventBinding` / `EventKey` | worker capability line | capability truth and candidate-source inputs | response semantics, result finality |
| task dispatch handoff | assignment/transport boundary | already-bound task work delivery view | worker matching, allocation, finality |
| `TaskResultReport` | task result payload | worker task-result input | worker command ack, worker state report |
| `TransportResultEnvelope` | transport ingress metadata | adapter/route/attempt context around task results | final result classification |
| `WorkerPresenceIngress` | transport session-presence ingress | worker session connect/heartbeat/disconnect observations projected into worker-runtime reachability, plus connected/heartbeat refresh of registry-owned slot heartbeat freshness | endpoint-lease delivery feasibility, command lifecycle, state projection, capability truth, worker resource status |
| `WorkerReachabilityView` | worker-runtime read model | dispatchability evidence | transport endpoint leases, device state, load, command status |
| `WorkerRegistry` / `WorkerSlot` | scheduling resource owner | active/reserved task-work capacity and exclusive execution-lane evidence | reachability, device state, command lifecycle |
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

## Current Presence-Ingress Boundary

`WorkerPresenceIngress` currently means session-presence ingress only:

- `sessionConnected(...)`
- `sessionDisconnected(...)`
- `sessionHeartbeat(...)`

`WorkerRuntimePresenceIngress` projects these observations into the
worker-runtime presence owner and may emit canonical online/offline trace
evidence when reachability actually changes. The ingress itself is not a worker
state, command, capability, or endpoint-lease lifecycle owner.
Connected and heartbeat observations may refresh registry-owned slot heartbeat
freshness so Stage-1 slot lifecycle eligibility has current evidence, but they
must not write worker resource status or dispatch gates.

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

task item stage evidence
  -> TaskStageEvidenceOwner
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
- Worker command catalog truth is deliberately small in the current baseline:
  only `DRAIN` and `PING` command requests are accepted by
  `WorkerCommandLifecycleOwner`.
- `DRAIN` command effects are policy-derived from command lifecycle status:
  `DELIVERY_ACCEPTED`, `EXECUTION_ACCEPTED`, and `SUCCEEDED` disable
  `DispatchAvailabilitySource.WORKER_COMMAND`; `FAILED` and `EXPIRED` do not
  create or clear a dispatch gate.
- Current `DRAIN` recovery is intentionally not `AVAILABLE` state report.
  `AVAILABLE` only clears `DispatchAvailabilitySource.WORKER_STATE`; it does
  not reopen `DispatchAvailabilitySource.WORKER_COMMAND`. A drain-accepted
  worker is expected to disconnect and re-register for the current first slice.
  A future `RESUME` command must own any explicit command-gate reopen path.
- Current worker command request ingress is:

  ```text
  CoreEventRequest(event=kernel.worker.command.request)
    -> KernelEventHandlerRegistry
    -> WorkerCommandRequestEventHandler
    -> WorkerControlService
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

- The first request-time delivery handoff slice is optional/configured. The
  runtime must not install a default unavailable delivery port that fails
  polling-worker commands before workers can pull them.
- Polling worker command delivery is worker-facing and command-specific:

  ```text
  POST /worker-api/v1/workers/{workerId}/commands:poll
    -> WorkerControlOperations.pullWorkerCommands(...)
    -> WorkerControlService
    -> WorkerCommandLifecycleOwner claim
    -> REQUESTED -> DELIVERY_ACCEPTED
  ```

- Polling command pull is not task dispatch and does not use task-work leases
  or task-result convergence.
- Realtime worker command delivery is worker-facing and command-specific:

  ```text
  WorkerCommandDeliveryPort
    -> current worker route owner / raw worker carrier
    -> type=worker.command frame
    -> worker command-specific acknowledgement surface
    -> WorkerCommandLifecycleOwner
  ```

- Realtime command frames are not task dispatch frames and do not create
  task-work leases or task-result rows.
- A realtime handoff without a raw carrier is deferred, not failed, so polling
  workers can still claim the same `REQUESTED` command through the polling
  command route.
- `WORKER_UNAVAILABLE` realtime delivery is retryable in the current baseline:
  the command remains `REQUESTED`, delivery attempt count is recorded on the
  command record, and bounded worker-command maintenance may retry it until
  deadline or max attempts.
- Command delivery success moves the command to `DELIVERY_ACCEPTED`.
- Command delivery rejection, hard failure, or delivery-port exception closes
  the command as `FAILED` when a configured delivery coordinator reports that
  outcome.
- Worker command deadline expiry is a bounded maintenance path through
  `WorkerControlService.expireDueWorkerCommands(...)` and
  `WorkerCommandLifecycleOwner.markExpired(...)`; it does not use task-work
  lease expiry or task-result convergence.
- Worker command deadline expiry closes command status only. It must not
  silently clear `WORKER_COMMAND` dispatch gates for a `DRAIN` command that was
  previously delivery/execution accepted; recovery requires an explicit
  worker/operator path.
- Worker command acknowledgement/status ingress is command-specific
  `WorkerCommandAcknowledgement` input to `WorkerCommandLifecycleOwner`, not a
  task result row.
- Worker state reports must not become transport reachability truth.
- Raw worker state must not enter matching/ranking directly; only bounded
  derived evidence from an approved owner may do so.
- Current worker state report ingress is:

  ```text
  CoreEventRequest(event=kernel.worker.state.report)
    -> KernelEventHandlerRegistry
    -> WorkerStateReportEventHandler
    -> WorkerControlService
    -> WorkerStateProjectionOwner
    -> bounded projection read view + WORKER_STATE_REPORT_APPLIED trace
  ```

- Worker state projection is versioned per worker. Stale and conflicting reports
  are rejected as no-ops, idempotent reports are accepted without projection
  change, and recent raw report evidence is bounded per worker.
- Worker state projection must not mutate `WorkerReachabilityView`,
  `WorkerRegistry`, `WorkerRegistrySnapshot`, `WorkerCandidateIndex`,
  matching, or task-result convergence.
- Task item stage evidence must not become public final result truth.
- Current task item stage evidence ingress is:

  ```text
  CoreEventRequest(event=kernel.task.stage.evidence)
    -> KernelEventHandlerRegistry
    -> TaskStageEvidenceEventHandler
    -> TaskStageEvidenceService
    -> TaskStageEvidenceOwner
    -> bounded projection read view + TASK_STAGE_EVIDENCE_APPLIED trace
  ```

- Task stage projection is versioned by `taskId + messageId + stageName`.
  Stale and conflicting evidence is rejected as a no-op, idempotent evidence is
  accepted without projection change, and recent raw evidence is bounded per
  stage key.
- `TASK_STAGE_EVIDENCE_APPLIED` trace is evidence only and explicitly records
  `stableFinalResult=false`.
- Task stage evidence must not mutate public result rows, final result
  convergence, task finality, task-work runtime queues, scheduling, dispatch
  binding, reachability, or load.
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
    -> WorkerControlService
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
- `WorkerReachabilityView` stays worker-runtime reachability evidence, not
  transport endpoint-lease truth or generic health.
- `WorkerRegistry` stays task-work capacity and exclusive execution-lane
  evidence, not device state.
- `WorkerPresenceIngress` must not import engine scheduling packages, mutate
  engine lifecycle state, write worker resource status, or derive presence or
  slot heartbeat from endpoint-lease currentness.
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
