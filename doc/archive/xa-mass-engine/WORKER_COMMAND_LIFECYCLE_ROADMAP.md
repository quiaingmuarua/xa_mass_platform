# Worker Command Lifecycle Roadmap

Last updated: 2026-05-18

Status: SE-1 direction document. WCMD-0 owner baseline is documented; WCMD-1
implementation is deferred. No worker command runtime behavior is implemented
by this document.

## Purpose

Worker commands are operational requests addressed to workers, such as asking a
worker to reload local configuration, run a diagnostic, switch an execution
mode, or report a bounded device-side status. They are not task work items and
their acknowledgements are not task results.

The goal of this roadmap is to add a future command lifecycle owner without
polluting task result convergence, worker reachability, worker load, or the
scheduling hot path.

```text
operator / system request
  -> WorkerCommandLifecycleOwner
  -> command delivery handoff
  -> worker command ack/status ingest
  -> command status read view
  -> trace evidence
```

## Core Boundary

Worker command lifecycle ownership must stay separate from existing task
lifecycle ownership:

| Surface | Owns | Must not own |
| --- | --- | --- |
| `TaskResultService` / `TaskResultRuntime` | task work result convergence | worker command ack/status |
| `TaskDispatchHandoff` / transport delivery | task dispatch delivery after assignment | command lifecycle status or retries |
| `WorkerSystemEventChannel` | current worker presence ingress | command request/status lifecycle |
| `WorkerReachabilityView` | dispatchability evidence from presence | command success, health, or state truth |
| `WorkerLoadView` | active/reserved task work capacity | command backlog or command execution load |
| trace/audit plane | historical evidence | current command truth or repair |

The command owner may use transport for delivery, but transport must remain the
data plane. Command state, retries, expiry, status transitions, and read models
belong to the command owner.

## Proposed Owner Model

Directional command identity:

```text
commandId
workerId
commandType
requesterId
reason
deadlineAt
idempotencyKey
payload
```

Directional status vocabulary:

```text
REQUESTED
DELIVERY_PENDING
DELIVERED
ACKED
SUCCEEDED
FAILED
EXPIRED
CANCELLED
```

These names are directional until implemented. The first implementation should
choose the smallest status set that can prove request, delivery, terminal
success/failure, and expiry without forcing task-result semantics.

## Package And Ownership Placement

When implementation starts, split shared model from lifecycle owner:

```text
xa-mass-base
  com.xa.mass.command.core
    -> WorkerCommandRequest / WorkerCommandStatus / WorkerCommandRecord
       or their final chosen names

xa-mass-engine
  com.xa.mass.engine.command
    -> WorkerCommandLifecycleOwner / WorkerCommandStore /
       InMemoryWorkerCommandStore / lifecycle policies
```

Reasoning:

- command request/status values are cross-boundary vocabulary that server, SDK,
  worker-side shells, and engine owner may all need to reference
- lifecycle mutation, store semantics, retry/expiry, and read-model ownership
  are engine owner concerns until a separate command-owner module exists
- do not put owner/store implementations in `com.xa.mass.command.core`
- do not put worker command model values in `transport_api`; transport may
  carry command delivery later, but it must not define the command lifecycle
  language
- do not reuse task command types such as task `APPROVE` / `PAUSE` / `SEAL`;
  task commands mutate task lifecycle, worker commands target worker operation

## Phase Plan

### WCMD-0: Owner Baseline And Guardrails

Status: current document only.

Scope:

- document worker command ownership and non-ownership rules
- keep `WorkerSystemEventChannel` presence-only
- keep task result ingest free of command ack/status shapes
- keep command/state/capability names out of scheduling hot-path packages

Out of scope:

- no Java command model
- no command store
- no command transport packet
- no server endpoint
- no worker SDK command handler

Verification:

- `EngineSchedulingCoreArchitectureGuardTest`
- static scan for command/status names in task result, reachability, load,
  scheduling, and system-event channel sources

### WCMD-1: Command Request Model And Store

Status: deferred. Do not implement until this roadmap is explicitly accepted
for the next behavior phase.

Goal: introduce the command owner data model without delivery behavior.

Scope:

- shared command request/status/record values in
  `xa-mass-base/com.xa.mass.command.core`
- engine-owned command store interface and in-memory implementation in
  `xa-mass-engine/com.xa.mass.engine.command`
- idempotent create/update semantics
- expiry timestamp recorded but not yet driven by a scheduler
- unit tests for idempotency and status transition validation

Out of scope:

- no transport delivery
- no worker ack ingest
- no server public API unless needed for test setup
- no task lifecycle mutation

Acceptance:

- no command owner/store implementation exists outside engine
- command status cannot be written through `TaskResultRuntime`
- duplicate command create with the same idempotency key returns the same owner
  record
- illegal terminal-to-active transitions are rejected

### WCMD-2: Command Delivery Handoff

Goal: deliver command requests to workers through a command-specific handoff
without reusing task dispatch handoff as a second command lifecycle owner.

Scope:

- command delivery envelope or handoff owned by command lifecycle
- transport route resolution by `adapterId + routeKey`
- delivery outcome recorded as command status evidence
- transport does not decide terminal command status

Out of scope:

- no task dispatch attempt creation
- no `TaskMsgAttempt`
- no worker load or scheduling budget changes

Acceptance:

- command delivery failure updates command status, not task status
- missing/offline route follows command retry/expiry policy, not task
  redispatch policy

### WCMD-3: Command Ack / Status Ingest

Goal: accept worker command ack/status responses into the command owner.

Scope:

- command ack/status payload
- idempotent status apply by `commandId`
- stale or unknown command handling
- terminal success/failure status
- trace evidence for command lifecycle

Out of scope:

- no task result row
- no task finality
- no worker reachability mutation from command status

Acceptance:

- ack/status cannot enter `TaskResultService`
- stale ack cannot reopen a terminal command
- trace-observed scenario proves request -> delivery -> ack/status through
  command events, not task result rows

### WCMD-4: Server / SDK Shell

Goal: expose a narrow operator/control-plane shell after the owner exists.

Scope:

- server endpoint or SDK operation for command create/read
- authorization and requester evidence
- read-only command status view
- no frontend dependency

Out of scope:

- no generic event bus endpoint
- no unified envelope runtime
- no task command endpoint reuse

Acceptance:

- server tests prove API wiring only
- owner tests remain the source of lifecycle correctness

### WCMD-5: Distributed Runtime

Goal: make command delivery safe across split engine/transport deployments.

Scope:

- Redis or equivalent command handoff when needed
- bounded queue/admission behavior
- retry and expiry owner loop
- trace/operator diagnosis

Out of scope:

- no full-history hot-path scans
- no command state stored in worker presence records

Acceptance:

- duplicate delivery and duplicate ack are idempotent
- command expiry is owner-driven
- transport restarts do not mutate task lifecycle

## Trace Proof

Future command trace scenarios should prove command lifecycle through canonical
command events, for example:

```text
worker-command-request-deliver-ack
worker-command-delivery-expiry
worker-command-stale-ack-noop
```

Trace must remain evidence. It must not become command runtime truth.

## Risks

### Risk 1: Command Ack Reuses Task Result

This would make result convergence a generic operations event router.

Mitigation:

- keep command ack/status payloads out of `TaskResultReport`
- keep guards against command names in result owners
- add command-owner tests before any worker-facing command response path

### Risk 2: System Event Channel Becomes Generic Control Plane

This would hide lifecycle ownership inside transport ingress.

Mitigation:

- keep `WorkerSystemEventChannel` presence-only
- add command request/status owner before adding command transport payloads

### Risk 3: Command Status Becomes Worker Health Truth

Command failure may indicate a worker-side issue, but it is not itself
reachability or scheduling load truth.

Mitigation:

- command status may feed diagnostics
- only a future state projection owner may produce bounded scheduling evidence

## Relationship To Other Roadmaps

- `../baseline/SYSTEM_EVENT_OWNER_BASELINE.md` freezes the current
  presence-only system event boundary.
- `UNIFIED_EVENT_ENVELOPE_ROADMAP.md` keeps shared event metadata future-only
  until concrete owners exist.
- Worker state report and capability self-report need separate owner roadmaps.
- Queue placement and priority strategy are scheduling/transport policy work,
  not command lifecycle ownership.
