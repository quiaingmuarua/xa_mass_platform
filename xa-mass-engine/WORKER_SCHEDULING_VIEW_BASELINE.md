# Worker Scheduling View Baseline

Last updated: 2026-05-15

Status: current transitional baseline for the WorkerContext convergence path,
including scheduling-candidate handoff, default rule surface convergence,
worker load view wiring, load-aware ranking, and default-capacity reservation.
Worker-declared capacity is now present as a read-model input to reservation
and trace. `ExecutionSpec.foreground` is now present as a task scheduling-mode
declaration in model/API/trace surfaces and controls long-lived worker lock
usage. Stateless background tasks can share workers up to declared capacity;
legacy WorkerContext-backed resources remain context-exclusive. The
foreground/context resource decision is now owned by
`WorkerDispatchResourcePolicy` and consumed by matching, dispatch binding,
assignment cleanup, and release mechanisms. The legacy WorkerContext state
mutation itself is owned by `LegacyWorkerContextResourceLifecycle`. Repeated
dispatch cleanup of reservations, exclusive worker locks, and lock-release trace
is owned by `WorkerDispatchResourceReleaser`, including release-listener
attempt/terminal close lock-release paths and dispatch-submit failure retry
compensation. These owner boundaries are now guarded by
`EngineSchedulingCoreArchitectureGuardTest`.

This document records the current engine scheduling read model after the first
WorkerContext convergence slices. It is intentionally narrow: this path does
not delete WorkerContext or remove legacy trace fields.

## Goal

Engine matching should consume a worker scheduling read view rather than treat
account/context/device lifecycle as an engine-owned scheduling resource.

The immediate convergence target is:

- current behavior stays intact
- existing `workerContext*` QLExpress variables remain available
- new worker-level scheduling variables are exposed beside them
- matching/allocation/dispatch handoff uses a worker scheduling candidate
- default rules use the worker scheduling surface rather than `workerContext*`
- matching can observe process-local worker load without runtime hot-path
  queries
- matching ranks rule-passed candidates with worker load and routing affinity
- matching reserves default worker capacity before lock acquisition, and binder
  confirms or releases that reservation around runtime claim
- worker match accepted/rejected trace rows include reservation-time load
  snapshots for canonical schedule analysis
- task assignment trace includes `foreground` so future behavior changes can be
  proved against the declared task scheduling mode
- later phases can remove legacy `workerContext*` rule variables
  without a single destructive rewrite

## Current Hot-Path Inventory

WorkerContext is still live in these engine paths:

- `WorkerSchedulingCandidateEnumerator`
  - is the only matching-side owner that still reads
    `WorkerManager.getWorkerContextsByWorkerIds(...)`
  - expands each worker plus optional legacy context into
    `WorkerSchedulingCandidate`
  - builds `WorkerSchedulingView` for prefilter decisions, rule context, and
    diagnostic snapshots
- `RuleBasedTaskWorkerMatchingStrategy`
  - consumes already-enumerated `WorkerSchedulingCandidate` objects
  - does not import `WorkerContext`; it reads scheduling facts from the
    candidate/view and no longer unwraps the candidate's nullable legacy
    payload directly
  - prefilters context allocatability, project, and routing tags through the
    scheduling view while preserving legacy reasons
  - emits match accepted/rejected trace with `workerContextId`
- `AssignmentDiagnosticRecorder`
  - records worker-level matching diagnostics from
    `WorkerSchedulingCandidate`
  - keeps legacy WorkerContext snapshot extraction inside the diagnostic owner
    while message-level assignment diagnostics still accept the current runtime
    `WorkerContext` payload
- `WorkerSchedulingCandidate`
  - is the internal handoff type between matching, allocation, listener
    orchestration, and dispatch binding
  - carries `Worker`, nullable legacy `WorkerContext`, and
    `WorkerSchedulingView`
  - keeps `WorkerContext` as runtime binding payload only, not the matching
    subject
- `WorkerMatchContext`
  - is constructed from `WorkerSchedulingCandidate`
  - owns the rule-evaluation and diagnostic snapshot field map consumed by
    matching prefilter records and QLExpress evaluation
  - exposes legacy `workerContext*` variables to QLExpress rules
  - exposes flattened `workerScheduling*` variables
  - exposes `isWorkerSchedulingResource*` aliases for resource state checks
  - exposes worker load and reservation fields from `WorkerLoadView`
- `SimpleTaskDispatchBinder`
  - asks `LegacyWorkerContextResourceLifecycle` to reserve/occupy WorkerContext
    during runtime claim and dispatch binding when
    `WorkerDispatchResourcePolicy` classifies the candidate as a legacy
    WorkerContext resource
  - records `workerContextId` on runtime attempts and dispatch trace
  - confirms worker reservations to active load when runtime claim succeeds
  - falls back to recording successful runtime claims when a custom strategy
    bypassed reservation
  - asks `WorkerDispatchResourceReleaser` to release worker reservations and
    exclusive locks for skipped, no-message, or failed dispatch slots
  - asks `WorkerDispatchResourceReleaser` to release exclusive worker locks
    after dispatch-submit failure is compensated back to retry
- `TaskResourceReleaseListener`
  - asks `LegacyWorkerContextResourceLifecycle` to release WorkerContext after
    final result, lease expiry, or cleanup paths when the dispatch resource
    policy classifies the attempt as a legacy WorkerContext resource
  - releases observed worker load on attempt-closed and terminal cleanup paths
  - asks `WorkerDispatchResourceReleaser` to release exclusive worker locks
    after attempt or terminal close
- `LegacyWorkerContextResourceLifecycle`
  - owns the transitional WorkerContext `IDLE -> RESERVED -> OCCUPIED -> IDLE`
    mutation and trace path while WorkerContext remains a runtime binding
    payload
- `WorkerDispatchResourceReleaser`
  - owns assignment and binder compensation cleanup for worker reservations,
    conditional exclusive worker unlock, canonical lock-release trace, and
    release-listener attempt/terminal close lock-release paths
  - releases foreground worker locks after dispatch-submit failure compensation
    because that pre-transport failure path does not publish attempt close
- `EngineSchedulingCoreArchitectureGuardTest`
  - prevents listener/binder orchestration from directly calling dispatch
    cleanup primitives that belong to `WorkerDispatchResourceReleaser`
  - prevents WorkerContext state mutation and direct context state CRUD from
    leaking outside `LegacyWorkerContextResourceLifecycle`
  - prevents retired context-first matching handoff types from returning to
    engine source or scheduling tests
  - keeps production strategy-package `WorkerContext` imports and direct
    storage reads isolated to `WorkerSchedulingCandidateEnumerator`
  - prevents production strategy code from unwrapping
    `candidate.getWorkerContext()` directly
  - prevents `RuleBasedTaskWorkerMatchingStrategy` from owning a duplicate
    rule/prefilter snapshot field builder
  - keeps strategy-level WorkerContext registration fixtures explicitly named
    as `legacyContext*` transitional coverage

WorkerContext is therefore still both:

- a matching-attribute source
- a runtime resource slot with lifecycle state

The scheduling upgrade target is to retire the second meaning from engine
matching first. Physical model/API deletion is a later phase.

## Transitional View

`WorkerSchedulingView` is the current transitional read model.

It is built from:

- `Worker`
- optional `WorkerContext`
- `WorkerReachabilityState`
- dispatch-enabled flag
- worker lock state
- `WorkerLoadSnapshot`

It exposes worker-level scheduling fields:

- `workerSchedulingResourceId`
  - `workerContextId` when a context exists
  - otherwise `workerId`
- `workerSchedulingProject`
  - current context project, if any
- `workerSchedulingRoutingTags`
  - current context routing tags, if any
  - otherwise worker attribute `routingTag` or comma-separated `routingTags`
- `workerSchedulingAttributes`
  - worker attributes merged with current context attributes
  - context attributes win on key conflict during the transition
- `hasWorkerSchedulingResource`
  - true when the view was built from a WorkerContext
- `isWorkerSchedulingResourceAllocatable`
  - true for stateless worker candidates or allocatable context-backed
    scheduling resources
- `isWorkerSchedulingResourceAvailable`
- `isWorkerSchedulingResourceUsable`
- `isWorkerSchedulingResourceReserved`
- `isWorkerSchedulingResourceOccupied`
- `workerSchedulingProjectMatchesTaskProject`
  - true when the scheduling project is present and matches the task project
- `workerSchedulingMatchesRoutingCode`
  - true when the task has a routing requirement and the scheduling routing tags
    contain that routing code
- `workerActiveLeaseCount`
- `workerReservedCount`
- `workerDeclaredCapacity`
- `workerEstimatedLoadRatio`
- `currentActiveLeaseCount`
- `estimatedLoadRatio`

The load fields are scheduling evidence in this baseline. Rule contexts and
diagnostic snapshots can read them, the default ranker uses load ratio for
preference ordering, and matching uses the reservation count as a process-local
capacity guard. This is still a process-local capacity execution model: default
declared capacity is `1`, and `Worker.maxConcurrentWork` can raise the
reservation ceiling for stateless background workers.
`ExecutionSpec.foreground` currently defaults to `true` and is carried through
SDK/server read models plus canonical assignment trace. `foreground=true` keeps
the existing long-lived worker lock. `foreground=false` still reserves capacity
but skips that long-lived lock, so multiple background tasks may share a
stateless worker until `active + reserved >= declaredCapacity`.
WorkerContext-backed resources are not shared by this slice; they still follow
their current context reservation/occupation lifecycle and still require the
long-lived worker lock even when the task declares `foreground=false`.
`WorkerDispatchResourcePolicy` is the single engine-internal owner for this
resource usage classification. It keeps worker-level lock requirements separate
from legacy WorkerContext lifecycle handling, which is necessary before
WorkerContext can be retired without touching every scheduling mechanism again.
`LegacyWorkerContextResourceLifecycle` is the single owner for the remaining
legacy WorkerContext state mutation. This keeps binder and release listeners
focused on runtime claim/bind and release orchestration rather than duplicating
the context state machine.
`WorkerDispatchResourceReleaser` owns repeated dispatch cleanup for worker
reservations and exclusive worker locks. Assignment orchestration and binder
compensation use it instead of duplicating reservation release, unlock, and
`WORKER_LOCK_RELEASED` trace decisions. Release listeners also use it for
attempt/terminal close lock-release decisions after runtime load and
WorkerContext release orchestration is complete. Binder compensation also uses
it after dispatch-submit failure is compensated back to retry, so the
foreground worker lock is not left waiting for an attempt-close event that will
not be published on that path.
Candidate cleanup paths resolve lock release with the same
`usageForCandidate(...)` policy used by matching acquisition. Attempt and
terminal cleanup paths resolve lock release with `usageForAttempt(...)`. This
keeps stateless background sharing separate from transitional context-backed
exclusive resources.
Accepted and rejected worker-match trace events read the current load snapshot
at the reservation decision point, so `workerReservedCount` can prove pending
reservation evidence in canonical JSONL.
Worker-match trace also carries worker scheduling evidence:
`workerSchedulingResourceId`, `workerSchedulingRoutingTags`,
`workerSchedulingAttributes`, and `workerSchedulingMatchesRoutingCode`. New
scheduling proof must prefer these fields over `workerContextId`;
`workerContextId` remains legacy runtime payload evidence while the binding and
release compatibility path still exists.
`WorkerLoadView` also exposes the current active worker count per task to
allocation policy. `WorkerBudgetPolicy` applies conservative internal
workload-class caps and assignment summaries emit `workerBudget`,
`currentTaskWorkerCount`, and `budgetLimited` as policy evidence. These caps are
not a public scheduling configuration surface yet.
The current guard for this transitional slice is intentionally source-level:
future code may still enumerate legacy contexts to build
`WorkerSchedulingCandidate`, but it must not reintroduce context-first handoff
types, hidden WorkerContext state mutation, or listener-owned resource cleanup.

Legacy fields remain available:

- `workerContextId`
- `workerContextProject`
- `workerContextStatus`
- `workerContextRoutingTags`
- `workerContextAttributes`
- `isWorkerContextAllocatable`
- `isWorkerContextAvailable`
- `isWorkerContextUsable`
- `isWorkerContextReserved`
- `isWorkerContextOccupied`

## Current Rule Guidance

New or migrated matching rules should prefer:

- `workerSchedulingAttributes`
- `workerSchedulingRoutingTags`
- `workerSchedulingProject`
- `workerSchedulingMatchesRoutingCode`
- `workerSchedulingProjectMatchesTaskProject`
- `isWorkerSchedulingResourceAllocatable`

Default routing rules and representative routing tests now read the
`workerScheduling*` / `isWorkerScheduling*` view. The matching prefilter also
consumes `WorkerSchedulingView` for resource allocatability, project, and
routing decisions. The main matching handoff now passes
`WorkerSchedulingCandidate` rather than a context-first matched resource.
Prefilter diagnostics now use `WorkerMatchContext.contextSnapshot(...)`, so rule
evaluation and rejected-candidate records share one scheduling read-model field
owner.
Representative engine and server routing proof now uses stateless worker
registration attributes for the matched/mismatched routing candidates. Legacy
WorkerContext-backed routing remains covered only as transitional lifecycle
coverage until the retirement phases remove it.
`RuleBasedTaskWorkerMatchingStrategyTest` now treats context-backed fixtures as
explicit legacy coverage; normal matching, trace, prefilter, and routing proof
uses stateless workers plus worker scheduling attributes.
Existing rules may continue using `workerContext*` variables until their owning
fixtures are migrated.

Do not add new default rules or new in-repo routing fixtures that depend on
`workerContext*` variables. `RuleConfigTest` guards the default rule sets.

Do not add new engine behavior that depends on WorkerContext as an account or
device lifecycle owner. If account/device state is needed, it should enter the
engine as system-event-updated worker scheduling attributes.

## Out Of Scope For This Step

- no WorkerContext model deletion
- no WorkerContext storage/API deletion
- no shared WorkerContext-backed dispatch behavior
- no account-switch dispatch protocol
- no trace field removal
- no public resource policy configuration

## Verification

Focused proof for this step:

- `WorkerMatchContextTest`
  - proves legacy context fields still exist
  - proves new worker-level scheduling fields are present
  - proves context attributes are flattened into scheduling attributes
- `RuleConfigTest`
  - proves default and derived rule sets do not use the legacy
    `workerContext*` rule surface
- `WorkerSchedulingCandidateTest`
  - proves the handoff keeps worker, nullable legacy context, scheduling view,
    and `workerContextId`
- `InMemoryWorkerLoadViewTest`
  - proves claim/final accounting, reservation accounting, and concurrent
    update safety
- `RuleBasedTaskWorkerMatchingStrategyTest`
  - protects current matching behavior
- `capacity-reservation-under-concurrency`
  - proves process-local reservation evidence through `xa-mass-trace`
- `background-worker-sharing`
  - proves a background stateless assignment can reserve worker capacity with
    existing active load and without long-lived worker lock evidence
  - is also covered by `TaskApiBackgroundWorkerSharingTraceObservedIntegrationTest`,
    which creates two `foreground=false` tasks through the real
    Boot/API/SDK/transport path against one capacity-2 stateless worker and
    analyzes canonical JSONL for the second task
- `worker-attribute-routing-without-context`
  - proves worker-level scheduling attributes and routing tags can satisfy
    routing without `workerContextId`
  - is also covered by `TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest`,
    which registers stateless workers through the real Boot/API/SDK/transport
    path and analyzes canonical JSONL for worker attribute routing
- `EngineSchedulingCoreSuite`
  - protects assignment behavior
- `EngineSchedulingCoreArchitectureGuardTest`
  - protects the scheduling-owner boundaries described above

Server and trace suites remain useful because no public runtime model or trace
schema changed in this slice.

## Next Cut

Lane-local dispatch priority is now implemented in `TaskAssignWorker`: signals
are ordered by resolved `DispatchPriority` inside each lane, and same-priority
signals retain FIFO order. Load-aware candidate ranking is implemented:
rule-passed `WorkerMatchContext` candidates are ranked before reservation and
lock acquisition using observed worker load and routing affinity. Reservation
and stateless background sharing are implemented and covered by engine tests,
trace analyzer tests, and one server trace-observed wiring proof.

The next narrow cut can add a trace scenario or mixed-workload acceptance proof
for cross-task fairness. Do not move budget formulas into
`TaskWorkerAssignListener`; the listener should remain orchestration and trace
owner.
Post-release refill is now owned by `AssignmentRefillPolicy`;
`TaskResourceReleaseListener` releases resources and consumes that decision.
Do not add cooldown, debounce, fairness, or budget-aware refill formulas back
into the release listener.

WorkerContext physical model/API deletion remains a later, larger phase after
runtime binding and trace compatibility no longer need context-specific fields.
