# Worker Scheduling View Baseline

Last updated: 2026-05-15

Status: current transitional baseline for the WorkerContext convergence path,
including scheduling-candidate handoff, default rule surface convergence,
worker load view wiring, load-aware ranking, and default-capacity reservation.
Worker-declared capacity is now present as a read-model input to reservation
and trace; shared foreground/background execution is still not enabled.

This document records the current engine scheduling read model after the first
WorkerContext convergence slices. It is intentionally narrow: this path does
not delete WorkerContext, add foreground/background semantics, or remove legacy
trace fields.

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
- later phases can remove legacy `workerContext*` rule variables
  without a single destructive rewrite

## Current Hot-Path Inventory

WorkerContext is still live in these engine paths:

- `RuleBasedTaskWorkerMatchingStrategy`
  - loads contexts with `WorkerManager.getWorkerContextsByWorkerIds(...)`
  - enumerates each worker plus optional legacy context as a
    `WorkerSchedulingCandidate`
  - builds `WorkerSchedulingView` for prefilter decisions, rule context, and
    diagnostic snapshots
  - prefilters context allocatability, project, and routing tags through the
    scheduling view while preserving legacy reasons
  - emits match accepted/rejected trace with `workerContextId`
- `WorkerSchedulingCandidate`
  - is the internal handoff type between matching, allocation, listener
    orchestration, and dispatch binding
  - carries `Worker`, nullable legacy `WorkerContext`, and
    `WorkerSchedulingView`
  - keeps `WorkerContext` as runtime binding payload only, not the matching
    subject
- `WorkerMatchContext`
  - is constructed from `WorkerSchedulingCandidate`
  - exposes legacy `workerContext*` variables to QLExpress rules
  - exposes flattened `workerScheduling*` variables
  - exposes `isWorkerSchedulingResource*` aliases for resource state checks
  - exposes worker load and reservation fields from `WorkerLoadView`
- `SimpleTaskDispatchBinder`
  - reserves/occupies WorkerContext during runtime claim and dispatch binding
  - records `workerContextId` on runtime attempts and dispatch trace
  - confirms worker reservations to active load when runtime claim succeeds
  - falls back to recording successful runtime claims when a custom strategy
    bypassed reservation
  - releases worker reservations for skipped, no-message, or failed dispatch
    slots
- `TaskResourceReleaseListener`
  - releases WorkerContext after final result, lease expiry, or cleanup paths
  - releases observed worker load on attempt-closed and terminal cleanup paths

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
capacity guard. This is still not a shared capacity execution model: default
declared capacity is `1`, `Worker.maxConcurrentWork` can raise the
process-local reservation ceiling, and foreground/background shared execution
is not implemented.
Accepted and rejected worker-match trace events read the current load snapshot
at the reservation decision point, so `workerReservedCount` can prove pending
reservation evidence in canonical JSONL.

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
- no foreground/background task semantics
- no shared background execution
- no account-switch dispatch protocol
- no trace field removal

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
- `EngineSchedulingCoreSuite`
  - protects assignment behavior

Server and trace suites remain useful because no public runtime model or trace
schema changed in this slice.

## Next Cut

Lane-local dispatch priority is now implemented in `TaskAssignWorker`: signals
are ordered by resolved `DispatchPriority` inside each lane, and same-priority
signals retain FIFO order. Load-aware candidate ranking is implemented:
rule-passed `WorkerMatchContext` candidates are ranked before reservation and
lock acquisition using observed worker load and routing affinity. Reservation
foundation is also implemented for the current default exclusive worker model.

WorkerContext physical model/API deletion remains a later, larger phase after
runtime binding and trace compatibility no longer need context-specific fields.
