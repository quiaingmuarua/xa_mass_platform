# Worker Scheduling View Baseline

Last updated: 2026-05-16

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
assignment cleanup, and release mechanisms. The legacy WorkerContext runtime
state machine has been removed from the engine mainline: binder and release
paths no longer mutate `WorkerContextStatus` or emit
`WORKER_CONTEXT_STATUS_TRANSITION`. Repeated dispatch cleanup of reservations,
exclusive worker locks, and lock-release trace is owned by
`WorkerDispatchResourceReleaser`, including release-listener
attempt/terminal close lock-release paths and dispatch-submit failure retry
compensation. These owner boundaries are now guarded by
`EngineSchedulingCoreArchitectureGuardTest`. Matching candidate enumeration now
creates one worker-level candidate per worker and no longer reads or expands
WorkerContext storage. `WorkerSchedulingView` and `WorkerMatchContext` no
longer flatten context status/project/routing/attributes into scheduling facts;
the remaining context-facing engine identity is `workerContextId` compatibility
evidence for runtime/trace surfaces.

This document records the current engine scheduling read model after the first
WorkerContext convergence slices. It is intentionally narrow: this path does
not delete WorkerContext or remove legacy trace fields.

## Goal

Engine matching should consume a worker scheduling read view rather than treat
account/context/device lifecycle as an engine-owned scheduling resource.

The immediate convergence target is:

- current behavior stays intact
- `workerContext*` QLExpress variables are retired from the engine scheduling
  rule context
- worker-level scheduling variables are the rule and diagnostic surface
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
- later phases can remove legacy `workerContextId` runtime/trace fields without
  a single destructive rewrite

## Current Hot-Path Inventory

WorkerContext is still live in these engine paths:

- `WorkerSchedulingCandidateEnumerator`
  - creates one worker-level `WorkerSchedulingCandidate` per worker
  - no longer reads `WorkerManager.getWorkerContextsByWorkerIds(...)`
  - no longer expands optional legacy contexts into scheduling candidates
  - builds `WorkerSchedulingView` for prefilter decisions, rule context, and
    diagnostic snapshots
- `RuleBasedTaskWorkerMatchingStrategy`
  - consumes already-enumerated `WorkerSchedulingCandidate` objects
  - does not import `WorkerContext`; it reads scheduling facts from the
    candidate/view and no longer unwraps the candidate's nullable legacy
    payload directly
  - prefilters worker reachability, worker availability, target-worker
    constraints, and worker-level routing through the scheduling view
  - emits match accepted/rejected trace with `workerContextId`
- `AssignmentDiagnosticRecorder`
  - records worker-level matching diagnostics from
    `WorkerSchedulingCandidate`
  - snapshots `WorkerSchedulingView` evidence through
    `WorkerSchedulingSnapshot`
  - keeps legacy `workerContextId` only as payload identity while runtime
    attempts still carry that field
- `WorkerSchedulingCandidate`
  - is the internal handoff type between matching, allocation, listener
    orchestration, and dispatch binding
  - carries `Worker` and `WorkerSchedulingView`
  - does not carry a nullable `WorkerContext` payload; legacy context identity,
    when present, is read from the scheduling view only as compatibility
    evidence
- `WorkerMatchContext`
  - is constructed from `WorkerSchedulingCandidate`
  - owns the rule-evaluation and diagnostic snapshot field map consumed by
    matching prefilter records and QLExpress evaluation
  - exposes flattened `workerScheduling*` variables
  - exposes `isWorkerSchedulingResource*` aliases for resource state checks
  - exposes worker load and reservation fields from `WorkerLoadView`
- `SimpleTaskDispatchBinder`
  - records `workerContextId` on runtime attempts and dispatch trace when
    `WorkerDispatchResourcePolicy` classifies the candidate as a legacy
    `workerContextId` compatibility resource
  - confirms worker reservations to active load when runtime claim succeeds
  - falls back to recording successful runtime claims when a custom strategy
    bypassed reservation
  - asks `WorkerDispatchResourceReleaser` to release worker reservations and
    exclusive locks for skipped, no-message, or failed dispatch slots
  - asks `WorkerDispatchResourceReleaser` to release exclusive worker locks
    after dispatch-submit failure is compensated back to retry
- `TaskResourceReleaseListener`
  - releases observed worker load on attempt-closed and terminal cleanup paths
  - asks `WorkerDispatchResourceReleaser` to release exclusive worker locks
    after attempt or terminal close
- `WorkerDispatchResourceReleaser`
  - owns assignment and binder compensation cleanup for worker reservations,
    conditional exclusive worker unlock, canonical lock-release trace, and
    release-listener attempt/terminal close lock-release paths
  - releases foreground worker locks after dispatch-submit failure compensation
    because that pre-transport failure path does not publish attempt close
- `EngineSchedulingCoreArchitectureGuardTest`
  - prevents listener/binder orchestration from directly calling dispatch
    cleanup primitives that belong to `WorkerDispatchResourceReleaser`
  - prevents WorkerContext runtime state mutation and direct context state CRUD
    from returning to the engine mainline
  - prevents retired context-first matching handoff types from returning to
    engine source or scheduling tests
  - forbids production strategy-package `WorkerContext` imports, direct storage
    reads, and legacy payload unwrapping
  - prevents `RuleBasedTaskWorkerMatchingStrategy` from owning a duplicate
    rule/prefilter snapshot field builder
  - prevents strategy-level tests from registering WorkerContext fixtures
  - prevents `WorkerSchedulingView` from flattening WorkerContext scheduling
    facts and prevents `WorkerMatchContext` from exposing `workerContext*`
    rule fields

WorkerContext is therefore still:

- a legacy compatibility identity that can still appear in attempts and
  trace/runtime compatibility fields

The production matching hot path no longer uses WorkerContext as a scheduling
attribute source, and `WorkerSchedulingCandidate` no longer carries a
`WorkerContext` object. `WorkerSchedulingView` no longer flattens context
project/routing/status/attributes into scheduling evidence, and
`WorkerMatchContext` no longer exposes `workerContext*` rule variables.
Physical model/API deletion is a later phase.

## Transitional View

`WorkerSchedulingView` is the current transitional read model.

It is built from:

- `Worker`
- `WorkerReachabilityState`
- dispatch-enabled flag
- worker lock state
- `WorkerLoadSnapshot`

It exposes worker-level scheduling fields:

- `workerSchedulingResourceId`
  - `workerId`
- `workerSchedulingProject`
  - currently `null`; project eligibility comes from `Worker.supportedProjects`
    and event capability
- `workerSchedulingRoutingTags`
  - worker attribute `routingTag` or comma-separated `routingTags`
- `workerSchedulingAttributes`
  - worker attributes
- `hasWorkerSchedulingResource`
  - true when the view has a worker-level scheduling resource id
- `isWorkerSchedulingResourceAllocatable`
  - true when worker dispatch is enabled
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
the worker-level exclusive lock even when the task declares `foreground=false`.
`WorkerDispatchResourcePolicy` is the single engine-internal owner for this
resource usage classification. It keeps worker-level lock requirements separate
from legacy `workerContextId` compatibility identity, which is necessary before
WorkerContext can be retired without touching every scheduling mechanism again.
`WorkerDispatchResourceReleaser` owns repeated dispatch cleanup for worker
reservations and exclusive worker locks. Assignment orchestration and binder
compensation use it instead of duplicating reservation release, unlock, and
`WORKER_LOCK_RELEASED` trace decisions. Release listeners also use it for
attempt/terminal close lock-release decisions after runtime load and
attempt/terminal close lock-release decisions after runtime load finalization is
complete. Binder compensation also uses it after dispatch-submit failure is
compensated back to retry, so the
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
future code must not reintroduce WorkerContext enumeration in the matching
strategy package, context-first handoff types, hidden WorkerContext state
mutation, or listener-owned resource cleanup.

Legacy rule/read-model fields are retired:

- `workerContextProject`
- `workerContextStatus`
- `workerContextRoutingTags`
- `workerContextAttributes`
- `isWorkerContextAllocatable`
- `isWorkerContextAvailable`
- `isWorkerContextUsable`
- `isWorkerContextReserved`
- `isWorkerContextOccupied`

`workerContextId` remains only as runtime/trace compatibility identity outside
the rule context.

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
consumes `WorkerSchedulingView` for worker reachability, worker availability,
target-worker constraints, event/project capability, and worker-level routing
decisions. The main matching handoff now passes
`WorkerSchedulingCandidate` rather than a context-first matched resource.
Prefilter diagnostics now use `WorkerMatchContext.contextSnapshot(...)`, so rule
evaluation and rejected-candidate records share one scheduling read-model field
owner.

Assignment records now use `WorkerSchedulingSnapshot` as their diagnostic
subject. The retired `WorkerContextSnapshot` shape must not be reintroduced in
engine diagnostics; context identity can remain only as legacy payload evidence
until the runtime contract stops carrying `workerContextId`.
Representative engine and server routing proof now uses stateless worker
registration attributes for the matched/mismatched routing candidates. Legacy
WorkerContext-backed routing remains covered only as transitional lifecycle
coverage until the retirement phases remove it.
`RuleBasedTaskWorkerMatchingStrategyTest` now treats context-backed fixtures as
explicit legacy coverage; normal matching, trace, prefilter, and routing proof
uses stateless workers plus worker scheduling attributes.
Do not add default rules or in-repo routing fixtures that depend on
`workerContext*` variables. `RuleConfigTest` and
`EngineSchedulingCoreArchitectureGuardTest` guard the default rule sets and
the engine rule-context source.

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
  - proves new worker-level scheduling fields are present
  - proves legacy `workerContext*` rule fields are absent
  - proves context attributes are not flattened into scheduling attributes
- `RuleConfigTest`
  - proves default and derived rule sets do not use the legacy
    `workerContext*` rule surface
- `WorkerSchedulingCandidateTest`
  - proves the handoff keeps worker plus scheduling view and reads legacy
    `workerContextId` compatibility identity from the view
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

The cross-task fairness trace scenario is now available as
`cross-task-worker-fairness`; it analyzes `<bulkTaskId>,<interactiveTaskId>`
against canonical assignment rows. A server trace-observed mixed-workload
acceptance now uses that analyzer after real Boot/API/SDK/WebSocket dispatch:
BULK work remains capped by the default budget with ready backlog, and
INTERACTIVE work dispatches to distinct remaining worker capacity. Do not move
budget formulas into `TaskWorkerAssignListener`; the listener should remain
orchestration and trace owner.
Post-release refill is now owned by `AssignmentRefillPolicy`;
`TaskResourceReleaseListener` releases resources and consumes that decision.
Do not add cooldown, debounce, fairness, or budget-aware refill formulas back
into the release listener.

Worker-level cleanup now emits `RESOURCE_RELEASED` alongside
`WORKER_LOCK_RELEASED` when an exclusive worker lock is released. The
`worker-resource-cleanup-without-context` trace analyzer uses this to prove
stateless worker cleanup without `workerContextId` or
`WORKER_CONTEXT_STATUS_TRANSITION` as the success evidence.

WorkerContext physical model/API deletion remains a later, larger phase after
runtime binding and trace compatibility no longer need context-specific fields.
