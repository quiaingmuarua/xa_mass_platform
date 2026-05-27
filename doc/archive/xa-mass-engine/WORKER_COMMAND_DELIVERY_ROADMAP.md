# Worker Command Delivery Roadmap

Status: archived completed mainline roadmap. WCD-0 through WCD-6 first slice
and WCD-7 read-surface slice are implemented for SDK/server command snapshots.
No frontend console work has been added.

## Archive Summary

Completed in this roadmap:

- worker-command request/status truth is owned by
  `WorkerCommandLifecycleOwner`
- automatic delivery handoff goes through `WorkerCommandDeliveryCoordinator`
  and `WorkerCommandDeliveryPort`
- polling workers can pull commands, realtime workers can receive command
  frames, and acknowledgements return through worker-control operations
- first-slice `DRAIN` / `PING` catalog admission, deadline expiry, bounded
  retry, delivery attempt tracking, and SDK/server read snapshots landed
- accepted `DRAIN` updates dispatch availability through the command-owned gate
  path without entering task-work or task-result runtime

Not completed by this roadmap:

- no frontend/operator console was added
- the current worker-facing command frame has not been promoted to a formal
  stable public DTO contract
- sample workers acknowledge commands simply; they are not a full real-world
  `DRAIN` execution reference
- richer retry/backoff and future `RESUME` command semantics remain separate
  owner decisions
- archived phase text below may describe original gaps; verify current behavior
  from code, tests, and baseline docs

This roadmap turns the existing worker-command lifecycle skeleton into a usable
control path. It is engine-owned because command request/status truth already
lives in `WorkerCommandLifecycleOwner`; transport and server are integration
surfaces, not lifecycle owners.

## Summary

Original gap before this roadmap:

```text
command request
  -> WorkerCommandLifecycleOwner records REQUESTED
  -> no automatic production delivery to worker
  -> worker may only ack if an external caller already knows the command
  -> dispatch gate policy reacts to accepted DRAIN lifecycle results
```

Implemented mainline:

```text
command request
  -> WorkerCommandLifecycleOwner
  -> WorkerCommandDeliveryCoordinator
  -> WorkerCommandDeliveryPort
  -> worker-visible command delivery
  -> worker command acknowledgement/status ingress
  -> WorkerCommandLifecycleOwner
  -> optional dispatch-gate effect policy
```

The goal is not to make worker commands into task work. Worker commands remain
their own lifecycle and delivery path.

## Current Facts

Implemented:

- `WorkerCommandRequest`
- `WorkerCommandLifecycleOwner`
- `WorkerCommandDeliveryCoordinator`
- `WorkerCommandDeliveryPort`
- `WorkerCommandRequestEventHandler`
- `WorkerControlService.requestWorkerCommand(...)`
- `WorkerControlService.applyWorkerCommandAcknowledgement(...)`
- SDK/server command request, acknowledgement, and read surfaces
- dispatch gate translation for accepted `DRAIN` command lifecycle results
- command catalog admission for first-slice `DRAIN` and `PING`
- bounded command deadline expiry through engine worker-control maintenance
- optional post-commit command delivery handoff seam in `WorkerControlService`
- polling-worker command pull route under `/worker-api/v1/**`
- realtime command push first slice through existing raw worker route carriers
- sample websocket/socket workers distinguish `type=worker.command` from task
  dispatch and acknowledge through `WorkerControlOperations`
- bounded retry first slice for owner-indexed `REQUESTED` commands through
  worker command maintenance
- command delivery attempt count is tracked on command records
- command read snapshots expose delivery attempt count and last attempt time

Deferred follow-ups:

- formal public worker command frame DTOs if the current JSON shape needs to
  become stable external contract
- richer command execution semantics beyond sample-worker immediate success ack
- richer retry scheduling/backoff beyond bounded maintenance retry
- frontend/operator console rendering, if a UI owner later needs it

Current drain recovery semantics:

- accepted `DRAIN` closes the `WORKER_COMMAND` dispatch gate
- `report-state(AVAILABLE)` only clears `WORKER_STATE`; it does not reopen
  `WORKER_COMMAND`
- recovery after first-slice `DRAIN` requires worker disconnect and
  re-registration
- a future `RESUME` command, if added, must explicitly own the command-gate
  reopening path

## Owner Boundaries

| Area | Owner | Must not own |
| --- | --- | --- |
| command request/status truth | `WorkerCommandLifecycleOwner` | task result rows, task-work leases, transport session lifecycle |
| command delivery attempt | `WorkerCommandDeliveryCoordinator` + `WorkerCommandDeliveryPort` | command lifecycle state beyond reporting handoff result |
| command ack/status ingress | `WorkerCommandLifecycleOwner` via `WorkerControlService` | task result convergence |
| dispatch gate effect | `WorkerDispatchAvailabilityPolicy` + `WorkerRegistry` disabled sources | command status truth |
| transport delivery mechanics | transport runtime/adapters | command lifecycle truth, retry/deadline policy |
| worker-facing command DTOs | `xa-mass-sdk-api` | transport carrier/session ownership, lifecycle truth |
| command transport carrier envelope | `transport_api` | command lifecycle truth, command effect policy |
| HTTP/operator surface | server controllers through SDK operations | engine owner internals |

Hard rules:

- command status must not enter `TaskResultRuntime`
- command delivery must not reuse task-work dispatch as a hidden command path
- command delivery may reuse an existing transport connection or carrier, but
  not task lease/result semantics
- worker command ack is command-specific input, not a task result callback
- server DTOs and frontend actions must not become command lifecycle truth
- `DRAIN` command effects must mutate dispatch gate through
  `DispatchAvailabilitySource.WORKER_COMMAND`, not a side map
- `DRAIN` delivery `FAILED` / `EXPIRED` must not disable dispatch; the worker
  has not accepted the drain command
- public SDK/server command contracts must not expose transport-specific frame
  shapes

## Command Catalog

First supported command catalog should stay deliberately small.

### Phase 1 Catalog

| Command | Meaning | Dispatch gate effect |
| --- | --- | --- |
| `DRAIN` | stop future dispatches to the worker while preserving in-flight work | accepted delivery/execution/success disables `WORKER_COMMAND` source |
| `PING` | ask worker to acknowledge command reachability / liveness | no dispatch gate effect |

`DRAIN` gate effect rule:

```text
DELIVERY_ACCEPTED / EXECUTION_ACCEPTED / SUCCEEDED
  -> disable WORKER_COMMAND dispatch gate source

FAILED / EXPIRED
  -> no dispatch gate mutation
```

Reasoning: a failed or expired delivery means the worker did not accept the
drain request. Closing dispatch in that case would create a platform-side
fiction and could silently remove a healthy worker from scheduling.

Current recovery rule:

```text
DRAIN accepted
  -> closes WORKER_COMMAND dispatch gate

AVAILABLE state report
  -> clears WORKER_STATE only
  -> does not reopen WORKER_COMMAND

worker disconnect + re-registration
  -> current first-slice recovery path

future RESUME command
  -> owns explicit WORKER_COMMAND reopen path
```

Out of first scope:

- `RESUME`
- `RECONFIGURE`
- `SHUTDOWN`

Reasoning:

- current dispatch re-enable rule is explicit worker state report
  `AVAILABLE`; adding `RESUME` now would create a second recovery truth too
  early
- `RECONFIGURE` needs a capability/config owner contract before it should
  mutate scheduling inputs
- shutdown semantics need an interruption policy and in-flight work decision

## Delivery Model

Commands target `workerId`, but delivery is route-based:

```text
workerId
  -> worker registry / route-owner view
  -> adapterNodeId or transport route owner
  -> command delivery carrier
```

The command lifecycle owner must not become an adapter-node owner. The delivery
port resolves a current worker route and hands off a command envelope to the
transport runtime.

Polling workers and realtime workers may use different carriers, but both must
return to the same command acknowledgement surface.

## Phase Plan

### WCD-0: Inventory And Guardrails

Goal: record current gaps and prevent owner regression before behavior changes.

Scope:

- inventory current command request/status/delivery/ack call paths
- identify all production references to `WorkerCommandDeliveryCoordinator`
- document that current request path records `REQUESTED` only
- add or update architecture guard coverage for:
  - worker command owner must not depend on `TaskResultRuntime`
  - worker command owner must not depend on task-work dispatch
  - server controllers must go through SDK `WorkerControlOperations`

Acceptance:

- no runtime behavior change
- current gap is documented from code, not inferred from roadmap text
- no new compatibility facade

### WCD-1: Command Catalog And Effect Policy

Goal: make command type semantics explicit before delivery exists.

Scope:

- introduce a small approved command catalog for `DRAIN` and `PING`
- reject unknown command types at SDK/engine owner boundary
- document command-type effect mapping in `EVENT_OWNER_BOUNDARY.md`
- keep dispatch gate effect policy in `WorkerDispatchAvailabilityPolicy`
- keep `DRAIN` re-enable outside this slice; `AVAILABLE` state report remains
  the explicit recovery path

Acceptance:

- `DRAIN` request is accepted and records `REQUESTED`
- `PING` request is accepted and records `REQUESTED`
- unknown command type is rejected deterministically
- only `DRAIN` can affect `WORKER_COMMAND` dispatch gate source

### WCD-2: Deadline Expiry Owner

Goal: prevent command records from living forever without creating delivery
retry semantics yet.

Scope:

- introduce a bounded `WorkerCommandMaintenanceService` scan owned by engine
  startup/watchdog wiring
- first slice implements expiry only; WCD-6 may extend the same maintenance
  loop with retry logic
- expire command records whose `deadlineEpochMillis` has passed while in:
  - `REQUESTED`
  - `DELIVERY_ACCEPTED`
  - `EXECUTION_ACCEPTED`
- expiry enters `WorkerCommandLifecycleOwner.markExpired(...)`
- trace `WORKER_COMMAND_STATUS_TRANSITION`
- do not retry delivery in this slice

Acceptance:

- command with expired deadline moves to `EXPIRED`
- command without deadline is not expired by the scan
- expired `DRAIN` does not clear an existing dispatch gate disable
- expired or failed `DRAIN` does not create a new dispatch gate disable
- scan is bounded and configurable

### WCD-3: Automatic Request-To-Delivery Handoff

Goal: connect request creation to the existing coordinator seam.

Scope:

- introduce a post-commit async delivery handoff:
  - `WorkerCommandLifecycleOwner` records `REQUESTED`
  - after the command record is accepted, request path enqueues `commandId`
    into a bounded command-delivery executor/inbox
  - the delivery worker invokes
    `WorkerCommandDeliveryCoordinator.deliver(commandId)`
- `requestWorkerCommand(...)` must not wait for real transport delivery
  completion
- enqueue failure must not lose the command record; the maintenance scan can
  later expire it, and WCD-6 can add retry
- keep delivery result semantics current:
  - accepted handoff -> `DELIVERY_ACCEPTED`
  - unavailable/rejected/failed -> current baseline `FAILED`
- do not implement retry yet
- do not install a default unavailable/no-op delivery port in production
  runtime; polling workers must be able to pull `REQUESTED` commands instead
  of having them failed before the worker asks for them
- only configured coordinators participate in request-time handoff

Acceptance:

- production request path can invoke a configured delivery coordinator
- `requestWorkerCommand(...)` returns after command creation and delivery
  handoff submission, not after worker receipt
- `WorkerControlService.requestWorkerCommand(...)` does not directly own
  transport delivery
- delivery handoff failure produces a lifecycle status transition and trace
- command remains independent from task-work dispatch/result

### WCD-4: Polling Worker Command Pull

Goal: make commands visible to polling workers with minimal transport risk.

Scope:

- add worker-facing command pull route under `/worker-api/v1/**`, not
  operator `/api/v1/**`
- return pending commands for the authenticated `workerId`
- command pull is the polling carrier handoff:
  - selecting a command for the polling worker and returning it must atomically
    advance `REQUESTED -> DELIVERY_ACCEPTED` through
    `WorkerCommandLifecycleOwner` / coordinator-owned semantics
  - the response includes enough command identity for later execution ack
  - worker execution ack later advances to `EXECUTION_ACCEPTED`, `SUCCEEDED`,
    or `FAILED`
- if the HTTP response is lost after `DELIVERY_ACCEPTED`, deadline expiry
  closes command status in Phase 1; dispatch recovery still requires an
  explicit worker/operator recovery path and must not silently clear
  `WORKER_COMMAND`
- do not add a second delivery-accepted ack round trip just to hide that window
- add worker-facing command acknowledgement route if existing ack route is not
  sufficient for public polling worker contract
- update external worker quickstart and samples only after API is wired

Out of scope:

- realtime push
- retry policy
- command batching beyond a bounded simple list

Acceptance:

- polling worker can fetch a `DRAIN` or `PING` command
- polling fetch atomically marks returned command as `DELIVERY_ACCEPTED`
- polling worker can acknowledge execution/success/failure
- `DRAIN` delivery acceptance or execution acknowledgement disables future
  dispatch through `WORKER_COMMAND`
- dropped polling responses converge command status by deadline expiry; they do
  not silently clear command-driven dispatch gates
- task result APIs are not involved

### WCD-5: Realtime Command Push

Goal: deliver commands over existing websocket/socket connections without
opening a second connection.

Scope:

- first slice uses an explicit JSON worker frame with `type=worker.command`
  over existing raw worker message carriers
- do not put adapter-specific frame shapes into `transport_api`
- keep the frame distinct from task dispatch and task result frames
- route command envelope to the worker's active route owner
- worker SDK/sample clients distinguish task dispatch from command delivery
- acknowledgements still enter command-specific ack surface
- first-slice sample clients acknowledge `SUCCEEDED` to prove the command frame
  and acknowledgement channel; they are not a full `DRAIN` execution example
  with `EXECUTION_ACCEPTED -> wait in-flight -> SUCCEEDED`
- realtime handoff for a worker without a raw carrier returns `DEFERRED`, not
  `FAILED`, so polling workers can still pull `REQUESTED` commands
- formal DTO/schema promotion is deferred until the frame shape is proven by
  websocket/socket worker clients

Out of scope:

- command payload schema registry
- generic unified event envelope runtime
- task dispatch lease/result reuse
- command execution policy beyond first-slice sample acknowledgement

Acceptance:

- realtime worker receives command without polling
- task dispatch and command delivery frame types are distinguishable
- engine command package does not own transport frame schema
- missing/offline realtime route reports unavailable handoff when the adapter is
  expected to be realtime
- missing raw carrier reports deferred handoff so polling delivery can claim it
- command lifecycle changes are visible through SDK/server read surfaces

### WCD-6: Retry And Unavailable Policy

Goal: add bounded retry after first delivery path works.

Scope:

- first slice treats `WORKER_UNAVAILABLE` and `DEFERRED` delivery outcomes as
  retryable by keeping the command in `REQUESTED`
- `REJECTED`, delivery exceptions, and hard `FAILED` outcomes still close the
  command as `FAILED`
- extend command maintenance from WCD-2 with bounded retry of owner-indexed
  `REQUESTED` commands
- record delivery attempt count on the command record before each handoff
- guard each command with an owner-local in-flight delivery attempt so
  request-time handoff and maintenance retry do not push the same command
  concurrently in the same runtime
- cap attempts and stop at deadline
- keep retry policy explicit and configurable
- apply command-status-to-dispatch-gate policy after delivery coordinator
  transitions, not only after worker ack/polling paths

Acceptance:

- transient unavailable worker can receive command after route becomes
  available
- retry stops at deadline
- retry stops after configured max attempts and closes the command as `FAILED`
- non-retryable rejection closes command as `FAILED`
- retry pump does not scan all workers or task rows
- concurrent delivery attempts for the same command call the delivery port at
  most once while the first attempt is in flight
- accepted realtime `DRAIN` handoff applies `WORKER_COMMAND` dispatch gate
  policy through `WorkerDispatchAvailabilityPolicy`

### WCD-7: Operator Read And Console Polish

Goal: expose command state without changing ownership.

This phase can run after WCD-3, and does not need to wait for WCD-6 retry.
It must remain read/control polish, not a lifecycle owner.

Implemented first slice:

- existing SDK/server list/detail command reads expose command status,
  deadline, worker id, command type, last reason, delivery attempt count, and
  last delivery attempt time
- no frontend console change is included in this roadmap implementation slice

Scope:

- improve existing list/detail command reads if needed
- add frontend views/actions only against server API surfaces
- show command status, deadline, worker id, command type, and last reason
- avoid direct owner internals in controllers/pages

Acceptance:

- operator can see pending/delivered/expired command states
- UI does not call engine internals
- command rows are clearly separate from task results

## Test Plan

Engine tests:

- command catalog accepts `DRAIN` / `PING` and rejects unknown commands
- `DRAIN` accepted ack disables dispatch with `WORKER_COMMAND` source
- `PING` accepted ack does not mutate dispatch gate
- deadline expiry moves pending commands to `EXPIRED`
- automatic handoff calls `WorkerCommandDeliveryCoordinator`
- delivery failure maps to command lifecycle state without touching task result

Transport/server tests:

- polling worker can pull pending command and ack it
- realtime worker receives distinct command frame
- worker ack route rejects wrong worker/command ownership
- command delivery does not produce task result rows

Architecture guards:

- `WorkerCommandLifecycleOwner` must not depend on `TaskResultRuntime`
- `WorkerCommandLifecycleOwner` must not depend on task-work dispatch types
- command delivery port must not mutate worker dispatch gates directly
- server command controllers must call SDK `WorkerControlOperations`

## Risks

### Risk 1: Command Delivery Becomes Hidden Task Dispatch

Mitigation:

- keep separate command envelope and acknowledgement surface
- forbid task lease/result dependencies in command lifecycle owner
- use trace to prove command status transitions, not task finality rows

### Risk 2: Dispatch Gate Gets Two Recovery Truths

Mitigation:

- first catalog does not include `RESUME`
- `report-state(AVAILABLE)` only clears `WORKER_STATE`, not `WORKER_COMMAND`
- first-slice `DRAIN` recovery is disconnect and re-registration
- later `RESUME` requires a separate owner decision for command-gate reopening

### Risk 3: Transport Convenience Pollutes Engine Ownership

Mitigation:

- transport owns delivery mechanics only
- command status mutation always returns through `WorkerCommandLifecycleOwner`
- delivery route resolution stays bounded to worker route-owner/read-view facts

### Risk 4: Retry Pump Becomes A Scan-Heavy Reconciler

Mitigation:

- use bounded deadline/retry indexes or owner-maintained pending sets
- do not scan all workers, tasks, or results
- keep retry policy explicit and measurable

## Recommended First Slice

Start with:

```text
WCD-0 + WCD-1 + WCD-2
```

Reason:

- they close semantic ambiguity without touching transport
- they make `DRAIN` / `PING` safe to reason about
- they prevent permanent pending command residue

Then implement:

```text
WCD-3 + WCD-4
```

Reason:

- polling command pull is the lowest-risk first real worker-visible delivery
- realtime push can follow after command envelope and ack semantics are stable

## Final Target

```text
WorkerCommandLifecycleOwner
  owns command request/status truth

WorkerCommandDeliveryCoordinator
  owns command delivery attempt handoff

WorkerCommandDeliveryPort
  owns protocol-specific delivery seam

WorkerControlService
  owns SDK/server/event-handler entry/read handoff

WorkerDispatchAvailabilityPolicy
  owns command-status-to-dispatch-gate translation

Transport adapters
  own worker-visible carrier mechanics
```

The command path becomes a real worker-control path without becoming task work,
without becoming task result, and without making transport the lifecycle owner.
