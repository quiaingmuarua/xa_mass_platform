# Worker Manager Split Roadmap

Last updated: 2026-05-16

Status: proposed next roadmap after scheduling-kernel convergence. This is a
direction document, not implemented baseline behavior.

## Summary

The scheduling roadmap has pushed the engine toward a clearer kernel:

```text
task lifecycle
  -> assignment orchestration
  -> worker scheduling view
  -> runtime claim / lease / retry
  -> result convergence
  -> terminal policy
```

The next high-ROI boundary is worker management.

The goal is not to create a separate worker-management API server now. The goal
is to move worker registration, capability, lifecycle administration, command
ownership, and state-report ownership out of the engine kernel as an in-process
module boundary first.

Target direction:

```text
server
  -> SDK operations
  -> worker-management component
  -> worker storage / transport presence / worker state projection

engine scheduling
  -> WorkerSchedulingView
  -> WorkerReachabilityView
  -> WorkerLoadView
  -> resource acquisition / release owners
```

Future platform deployment may split worker management into an independent
service, but this roadmap does not require service extraction.

## Position

Worker management should become a sibling owner to the engine kernel, not an
engine sub-feature.

The engine should continue to own:

- task lifecycle and command semantics
- assignment orchestration
- scheduling policy consumption
- runtime work claim, lease, retry, and release interaction
- process-local observed worker load used by scheduling
- result ingest, result runtime commit, and terminal convergence

Worker management should own:

- worker identity and registration
- worker capability declaration
- `eventBindings` normalization and compatibility projections
- worker attributes and scheduling labels supplied by registration/system
  events
- declared worker capacity such as `maxConcurrentWork`
- worker lifecycle administration and read models
- worker command lifecycle for platform/operator initiated commands
- worker state report ingress, normalization, projection, and derived
  scheduling facts
- future device/account/system-event projections

Other task control-plane surfaces can remain engine-owned for now. Splitting
worker management is high ROI because workers are an external resource plane
consumed by scheduling. Splitting all control-plane services now would be
premature.

## Non-Goals

This roadmap does not:

- introduce a worker-management microservice
- move task command/query/control-plane ownership out of engine
- move `TaskWorkRuntime` or `TaskResultRuntime`
- move observed active/reserved scheduling load out of the engine kernel in the
  first phase
- add public scheduling policy configuration
- recreate WorkerContext under another name
- route worker system events through task result convergence
- make engine interpret raw battery, network, device, account, or environment
  facts
- make transport own command meaning or worker-management policy
- implement the unified event envelope before the owner boundaries are stable
- add pass-through bridge/facade layers that only rename existing calls

## Execution Discipline

This roadmap assumes multi-session execution with enough verification budget.
Prefer slow, owner-preserving slices over broad refactors.

This document has three layers:

```text
current boundary truth
  -> what must remain true now

worker-manager split mainline
  -> WM-0 through WM-7

unified event-envelope dependency
  -> separate roadmap, referenced only for future direction
```

The event-envelope north-star must not preempt the worker-manager split
mainline. Detailed event design belongs to
[UNIFIED_EVENT_ENVELOPE_ROADMAP.md](./UNIFIED_EVENT_ENVELOPE_ROADMAP.md).
This roadmap may reference it only to protect worker command/state-report owner
boundaries.

Coding slices must be phase-scoped:

- implement at most one roadmap phase unless explicitly approved
- state the phase, scope, out of scope, owner boundaries touched, forbidden
  cross-phase changes, and tests before changing behavior
- keep task lifecycle, `TaskWorkRuntime`, `TaskResultRuntime`, transport
  reachability, and public result contracts stable unless the selected phase
  explicitly owns them
- prove behavior with tests
- prove runtime path with trace when scheduling, lifecycle, command, or result
  routing changes
- add or update architecture/source guards when retiring a path
- update owner docs in the same change

Phase gating:

- WM-0 / WM-1 / WM-2 must not implement unified event routing
- unified event-envelope phases are tracked in
  `UNIFIED_EVENT_ENVELOPE_ROADMAP.md` and are not part of the worker-manager
  split mainline
- worker command acks and state reports must not route through
  `TaskResultRuntime`
- any slice that needs to change task result, task runtime, transport, or queue
  priority before its phase must stop and update the owning roadmap first

## Target Boundary

### Worker-Management Owner

Worker-management owns durable and administrative worker facts:

```text
WorkerRegistration
  -> Worker identity
  -> eventBindings
  -> supportedProjects / supportedEventCodes compatibility projection
  -> attributes / scheduling labels
  -> maxConcurrentWork declaration
  -> lifecycle status / administrative read model
```

WorkerContext registration/query compatibility has been removed from the
current SDK/server/storage surface. Worker-management must not recreate it under
a new compatibility API; account/device inventory should enter through explicit
worker-management or system-event models when that feature is actually added.

### Engine Kernel Consumer

Engine scheduling should consume only narrow worker facts:

```text
WorkerSchedulingView
  -> scheduling labels
  -> capability fit
  -> declared capacity
  -> current reachability
  -> current observed load

WorkerReachabilityView
  -> transport-owned reachability facts

WorkerLoadView
  -> active leases
  -> reservations
  -> current active worker count per task

WorkerDispatchResourcePolicy / WorkerDispatchResourceReleaser
  -> exclusive-lock usage and cleanup mechanics
```

The engine must not call WorkerContext storage or worker-management CRUD to
derive scheduling truth.

### SDK And Server

Server should keep calling SDK operations. The SDK implementation may stay
in-process:

```text
server controller
  -> WorkerRegistryOperations
  -> WorkerClientOperations
  -> WorkerInspectionOperations
```

Public worker APIs should not directly depend on engine `WorkerManager`.

## Ownership Rules

### Declared Facts Versus Observed Runtime Facts

Declared worker facts belong to worker management:

- worker id
- capability/event bindings
- worker attributes
- declared max concurrent work
- administrative lifecycle status

Observed scheduling facts belong to engine runtime/scheduling:

- active lease count
- reserved count
- per-task active worker count
- dispatch resource lock/release lifecycle
- assignment trace around match/reserve/claim/release

Do not move `WorkerLoadView` into generic worker management in the first split.
It is runtime scheduling state, not worker profile CRUD.

### Reachability

Transport reachability is transport-owned. Worker management may expose it for
operator read models, but engine scheduling must consume it through an explicit
reachability view, not infer it from `Worker.status`.

### Capability

`eventBindings` are the capability truth. `supportedProjects` and
`supportedEventCodes` are compatibility/read-model projections until removed or
re-scoped.

### WorkerContext

WorkerContext is not scheduling truth. WorkerContext CRUD/API/storage surfaces
have been removed from the current SDK/server/storage mainline. New
worker-management APIs must not recreate WorkerContext under a new name.

## Event Boundary Dependency

Detailed event-envelope direction lives in
[UNIFIED_EVENT_ENVELOPE_ROADMAP.md](./UNIFIED_EVENT_ENVELOPE_ROADMAP.md).
This worker-manager roadmap only owns the worker-management boundary.

Worker-management must reserve ownership for worker command and worker state
report lanes, but this roadmap must not implement unified event routing.

### WorkerCommand Owner

`WorkerCommand` is a control or operations request sent to a worker. Examples:

- drain new work
- go offline or resume
- refresh capability
- reload configuration
- collect diagnostics
- rotate credentials
- restart worker agent
- ping / health probe

This is not task dispatch and not task result. Worker-management owns command
meaning and lifecycle. Transport owns delivery.

Minimum lifecycle vocabulary should be reserved from the beginning:

```text
REQUESTED
DELIVERED
ACKED
RUNNING
SUCCEEDED
FAILED
EXPIRED
CANCELLED
```

The first implementation may use only a subset, but the contract must not be
fire-and-forget. A command needs at least a stable command id, target worker,
type, reason, requester, deadline or expiry, status, and result code/message.

### WorkerStateReport Owner

`WorkerStateReport` is a worker/device-originated report about its environment,
health, capability, or runtime condition. Examples:

- network changed from WLAN to cellular
- low battery
- not charging
- thermal pressure
- memory pressure
- proxy degradation
- account health changed
- capability version changed

Worker-management owns report validation, ordering/idempotency, debounce, TTL,
projection, and derived scheduling facts. State reports are not automatically
durable audit events.

The default storage model should be:

```text
current state projection
  + bounded recent history
  + important transition audit
```

Do not write every high-frequency raw state report into trace/audit by default.

### State Model Split

Do not overload `OFFLINE`.

Worker-management should preserve three independent concepts:

```text
transportReachability
  ONLINE / OFFLINE / DEGRADED

dispatchAvailability
  ENABLED / DRAINING / DISABLED

administrativeState
  ACTIVE / PAUSED / DISABLED
```

Examples:

- network disconnected: `transportReachability=OFFLINE`
- low battery but still connected: `dispatchAvailability=DRAINING` or
  `DISABLED`, reason `LOW_BATTERY_NOT_CHARGING`
- operator disabled worker: `administrativeState=DISABLED`

Engine scheduling consumes the combined derived scheduling view. It must not
collapse these states into `Worker.status`.

### Derived Scheduling Facts

Raw device facts must not become engine scheduling truth.

Worker-management may receive:

```text
batteryLevel=12
charging=false
networkType=CELLULAR
```

It should derive facts such as:

```text
dispatchEnabled=false
dispatchDisabledReason=LOW_BATTERY_NOT_CHARGING
networkClass=METERED
powerClass=LOW
schedulingAttributes.powerMode=LOW_POWER
```

Engine scheduling may consume derived scheduling facts through
`WorkerSchedulingView` / reachability views. It must not own the interpretation
of raw battery, network, thermal, account, or device state.

### Transport Boundary

Transport owns command delivery and presence facts:

```text
command delivery
dispatch delivery
result ingress transport normalization
connection presence / reachability
```

Transport must not decide that low battery means a worker should drain, or that
a failed diagnostic command means the worker is disabled. Those are
worker-management decisions.

### Event Owner Separation Rule

Keep these event types separate:

```text
TaskResultReport
  -> engine result convergence

WorkerStateReport
  -> worker-management state projection

WorkerCommandAck / WorkerCommandStatus
  -> worker-management command lifecycle
```

They may originate from the same worker process, but they must not share the
same owner path.

Even if these lanes later share the unified event-envelope shape, owner meaning
must stay separate. In particular:

- `WorkerCommandAck` must not be written to `TaskResultRuntime`
- `WorkerStateReport` must not be treated as transport reachability truth
- `TaskResultReport` must not become a worker-management state update
- task-stage semantics belong to the unified event-envelope roadmap, not to
  this worker-manager split

### Event Dependency Guardrails

- unified event-envelope detail belongs to `UNIFIED_EVENT_ENVELOPE_ROADMAP.md`.
- this roadmap may reserve worker command/state-report ownership only.
- owner paths remain separate even if the envelope shape is shared later.
- `WorkerCommand` is platform/operator to worker and has lifecycle state.
- `WorkerStateReport` is worker/device to platform and updates projection.
- state reports are normalized before scheduling sees them.
- engine consumes derived scheduling facts, not raw device state.
- transport owns delivery, not command meaning.
- task result, worker state, and command ack must stay separate.
- declared capacity belongs to worker-management; observed load belongs to
  engine runtime/scheduling.
- `OFFLINE` must not conflate reachability, admin state, and dispatch
  availability.
- high-frequency state reports require projection/debounce/bounded history, not
  full durable audit by default.

## Phased Plan

### Phase WM-0: Freeze Current Kernel Boundary

Goal: make the post-scheduling roadmap baseline explicit before moving code.

Scope:

- keep `SCHEDULING_KERNEL_GUARDRAILS.md` as the fixed scheduling mainline
- document worker-management split direction in this roadmap
- identify all current engine `WorkerManager` callers by category:
  - worker registration/read
  - remaining WorkerContext deletion guards / legacy payload references
  - worker lock/resource acquisition
  - worker load/reservation
  - worker candidate lookup
  - reachability/capability read
- do not move classes yet

Out of scope:

- no module creation
- no API deletion
- no behavior change

Acceptance:

- inventory separates worker-management facts from engine runtime facts
- next phases can delete one dependency category at a time

Suggested verification:

```powershell
rg -n "WorkerManager|WorkerContext|WorkerRegistration|eventBindings|maxConcurrentWork" xa-mass-engine xa-mass-sdk xa-mass-server platform_infra
```

### Phase WM-1: Define Worker-Management Mainline Interfaces

Goal: introduce real owner surfaces without changing deployment shape.

Candidate mainline surfaces:

```java
interface WorkerRegistryOperations {
    // register/update worker identity and capability
}

interface WorkerInspectionOperations {
    // worker read model, capability, attributes, transport-facing facts
}
```

If these already exist in SDK, this phase should align implementation ownership
rather than introduce duplicates.

Rules:

- do not expose `WorkerContext` through mainline registry or inspection
- do not put worker load/reservation into registry APIs
- do not add a separate server process
- do not add wrappers that forward every old `WorkerManager` method unchanged

Acceptance:

- worker mainline is visible and no WorkerContext compatibility surface is
  exposed
- server worker APIs can be explained without referencing engine `WorkerManager`
- SDK README and API docs show `eventBindings` as capability truth

### Phase WM-2: Move Worker Registration And Capability Ownership

Goal: move durable worker registration/capability owner out of engine.

Scope:

- move or re-home worker registration operations behind worker-management
  component ownership
- make `eventBindings` normalization and compatibility projections belong to
  worker management
- keep `Worker.maxConcurrentWork` as declared capacity owned by worker
  registration
- reserve the event owner dependency:
  - `WorkerCommand` is not task dispatch
  - `WorkerStateReport` is not task result
  - detailed event-envelope metadata belongs to
    `UNIFIED_EVENT_ENVELOPE_ROADMAP.md`
  - raw worker/device state is not engine scheduling truth
- engine consumes declared capacity through scheduling/load view updates
- update SDK/server constructors so worker APIs route to worker-management
  ownership rather than engine `WorkerManager`

Out of scope:

- no WorkerContext physical deletion yet
- no distributed worker registry
- no command bus implementation
- no device-state projection implementation
- no unified event-envelope implementation
- no change to scheduling behavior

Acceptance:

- engine no longer owns worker registration as a primary API surface
- engine still receives enough worker facts to build scheduling views
- `WorkerLoadView` remains engine scheduling state

Suggested verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-sdk,xa-mass-server,xa-mass-engine -am -DskipTests compile
```

### Phase WM-3: Split Worker Scheduling Read Source From Worker CRUD

Goal: make engine scheduling depend on a read source, not a CRUD manager.

Candidate engine-facing surfaces:

```java
interface WorkerSchedulingViewSource {
    List<WorkerSchedulingCandidate> candidatesFor(Task task);
}

interface WorkerCapabilityLookup {
    Optional<Worker> findWorker(String workerId);
}
```

Names are not fixed. The boundary is fixed:

- engine matching reads scheduling candidates/views
- worker management owns how worker registration facts become those views
- engine load and reachability remain explicit inputs, not hidden CRUD lookups

Out of scope:

- no pure rename-only split
- no second matching implementation
- no public SDK scheduling policy surface

Acceptance:

- `RuleBasedTaskWorkerMatchingStrategy` no longer needs broad worker-management
  CRUD access
- scheduling tests can prove candidate source behavior separately from registry
  mutation behavior

### Phase WM-4: Keep WorkerContext Deleted From Worker Management

Scope:

- keep context methods out of engine-facing worker manager/lookup surfaces
- keep WorkerContext registration/query APIs deleted
- update source guards so engine mainline cannot call context CRUD
- update frontend/server docs so worker pages describe worker capability,
  reachability, and runtime load rather than WorkerContext

Out of scope:

- no new context replacement model

Acceptance:

- engine source cannot use WorkerContext storage or compatibility operations
- SDK/server mainline cannot expose WorkerContext registration/query APIs
- remaining `workerContextId` references are limited to runtime/trace/projection
  legacy payloads and explicit architecture guards

### Phase WM-5: Separate Resource Acquisition From Worker Registration

Goal: keep scheduling resource mechanics out of worker registration.

Scope:

- keep or introduce a narrow resource owner for:
  - worker lock
  - reservation confirmation/release
  - active observed load updates
- ensure worker-management registration APIs cannot mutate observed runtime load
- ensure engine release/binder paths continue to use
  `WorkerDispatchResourcePolicy` and `WorkerDispatchResourceReleaser`

Out of scope:

- no Redis/distributed worker capacity owner yet
- no global fairness service

Acceptance:

- declared capacity and observed capacity are separated
- resource cleanup remains engine/runtime-lifecycle driven
- changing registration storage cannot break attempt close or result release

### Phase WM-6: Server/SDK API Ownership Cleanup

Goal: align external and internal APIs with the new owner boundary.

Scope:

- server worker APIs call worker-management SDK surfaces
- task APIs continue to call task/engine SDK surfaces
- worker docs show:
  - registration/capability belongs to worker management
  - scheduling consumes worker views
  - runtime load belongs to engine scheduling
- docs distinguish worker registration, worker command, worker state report,
  and task result surfaces
- OpenAPI/Knife4j descriptions avoid presenting WorkerContext as mainline

Out of scope:

- no unified event-envelope implementation
- no service split

Acceptance:

- server controllers no longer need to know engine `WorkerManager`
- SDK worker API naming matches registry/client/inspection/compatibility split
- WorkerContext APIs, if still present, are deprecated/compatibility-only
- no public or internal docs route worker commands/state reports through task
  result convergence

### Phase WM-7: Optional Service Extraction Readiness

Goal: make future deployment split cheap without doing it now.

Readiness criteria:

- worker-management component has stable in-process interface
- engine consumes only narrow scheduling/reachability/resource views
- SDK/server calls do not reach engine internals for worker CRUD
- WorkerContext compatibility APIs remain deleted; remaining `workerContextId`
  payload references stay limited to runtime/trace/projection residue
- trace and diagnostics do not use WorkerContext as scheduling proof
- worker command lifecycle and worker state report ownership are defined as
  worker-management concerns
- unified event-envelope dimensions are documented as future metadata, while
  current owner paths remain explicit
- transport command delivery is separated from command meaning
- engine consumes only derived scheduling facts from worker state

Only after those are true should an independent worker-management API server be
considered.

Out of scope for this roadmap:

- service discovery
- distributed transaction boundary
- network auth between services
- cross-service OpenAPI/client generation

## Test Strategy

### Contract And Compile

- compile `xa-mass-engine`, `xa-mass-sdk`, `xa-mass-server`, worker pack, and
  storage/runtime modules after each ownership move
- add architecture/source guards for forbidden engine dependencies when a path
  is retired

### Engine

Focus on scheduling behavior that must not change:

- worker eligibility
- allocation gates
- capacity reservation
- foreground/background resource policy
- release/refill
- result-driven redispatch

Representative commands:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=EngineSchedulingCoreArchitectureGuardTest,EngineSchedulingCoreSuite,RuleBasedTaskWorkerMatchingStrategyTest,TaskWorkerAssignListenerTest,TaskResourceReleaseListenerTest" test
```

### SDK / Server

Focus on API ownership and Boot wiring:

```powershell
.\mvnw.cmd -q -pl xa-mass-sdk,xa-mass-server -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=*Worker*,*TaskApi*Worker*,ServerMainSourceArchitectureGuardTest" test
```

### Frontend / Docs

When server worker APIs or docs change:

- worker pages must not present WorkerContext as mainline
- Knife4j/OpenAPI descriptions must identify compatibility routes
- SDK README must use `eventBindings` and worker registration mainline

## Risks

### Risk 1: Fake Split

Moving `WorkerManager` to another package with all old methods intact is not a
real split. It only hides the owner problem.

Mitigation:

- split by caller intent and owner truth
- delete or isolate one real dependency category per phase

### Risk 2: Moving Runtime Load Too Early

`WorkerLoadView` is scheduling runtime state. Moving it into generic worker
management too early can make worker CRUD own attempt lifecycle.

Mitigation:

- declared capacity belongs to worker management
- observed active/reserved load remains engine/runtime-owned until a dedicated
  distributed resource owner is planned

### Risk 3: WorkerContext Recreated Under Another Name

If the new worker-management component introduces account/device slots with
engine-owned status transitions, the WorkerContext retirement failed.

Mitigation:

- account/device lifecycle enters scheduling as read-only attributes or
  execution hints
- account switch failure returns through normal result convergence

### Risk 4: Premature Service Extraction

A new service boundary would add auth, deployment, network failure, generated
clients, and transaction concerns before the owner boundary is stable.

Mitigation:

- keep in-process SDK/server wiring first
- service extraction is a readiness milestone, not a phase-one implementation

### Risk 5: Raw Device State Leaks Into Engine Scheduling

If engine rules start reading raw `batteryLevel`, `charging`, `networkType`, or
account health fields directly, WorkerContext retirement will be replaced by a
new device-context coupling.

Mitigation:

- worker-management owns raw state interpretation
- engine reads derived scheduling facts only
- scheduling attributes should express policy-ready facts such as power class,
  network class, dispatch availability, and disabled reason

### Risk 6: OFFLINE Becomes An Overloaded State

Using one `OFFLINE` status for network disconnect, low-power drain, operator
pause, and administrative disablement makes scheduling, retry, audit, and
operator diagnosis ambiguous.

Mitigation:

- keep reachability, dispatch availability, and administrative state separate
- derive engine-visible dispatch eligibility from the combined state

### Risk 7: Worker Events Pollute Task Result Convergence

Worker command acks and state reports may come from the same worker process as
task results. If they are routed through result ingest, result convergence
becomes an operations event router.

Mitigation:

- `TaskResultReport` remains engine result input
- `WorkerStateReport` belongs to worker-management projection
- `WorkerCommandAck` belongs to worker-management command lifecycle
- transport may normalize multiple event classes at ingress, but it must route
  them to distinct owners
- future shared envelope details must follow
  `UNIFIED_EVENT_ENVELOPE_ROADMAP.md`

## Recommended First Slice

Start with WM-0 and a small part of WM-1:

- record the current `WorkerManager` caller inventory by intent
- define worker-management mainline versus engine-consumed facts
- add WorkerCommand / WorkerStateReport owner boundaries to docs and future
  source guards before implementing either path
- update docs and source guards only
- do not move runtime load or reservation code
- do not implement unified envelope routing in the first slice

The first implementation slice should make the boundary harder to misread. It
should not try to complete the split in one pass.
