# Worker Scheduling View Baseline

Last updated: 2026-05-15

Status: current transitional baseline for the WorkerContext convergence path.

This document records the current engine scheduling read model after the first
WorkerContext convergence slice. It is intentionally narrow: this step does not
delete WorkerContext, change assignment behavior, introduce capacity scheduling,
or change trace contracts.

## Goal

Engine matching should consume a worker scheduling read view rather than treat
account/context/device lifecycle as an engine-owned scheduling resource.

The immediate convergence target is:

- current behavior stays intact
- existing `workerContext*` QLExpress variables remain available
- new worker-level scheduling variables are exposed beside them
- later phases can migrate rules and matching code away from WorkerContext
  without a single destructive rewrite

## Current Hot-Path Inventory

WorkerContext is still live in these engine paths:

- `RuleBasedTaskWorkerMatchingStrategy`
  - loads contexts with `WorkerManager.getWorkerContextsByWorkerIds(...)`
  - iterates each `(worker, workerContext)` pair
  - prefilters context allocatability, project, and routing tags
  - emits match accepted/rejected trace with `workerContextId`
- `WorkerMatchContext`
  - exposes legacy `workerContext*` variables to QLExpress rules
  - now also exposes flattened `workerScheduling*` variables
- `SimpleTaskDispatchBinder`
  - reserves/occupies WorkerContext during runtime claim and dispatch binding
  - records `workerContextId` on runtime attempts and dispatch trace
- `TaskResourceReleaseListener`
  - releases WorkerContext after final result, lease expiry, or cleanup paths
- `MatchedWorkerContext`
  - remains the handoff type between matching, allocation, and dispatch binding

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
- `workerSchedulingProjectMatchesTaskProject`
  - true when the scheduling project is present and matches the task project
- `workerSchedulingMatchesRoutingCode`
  - true when the task has a routing requirement and the scheduling routing tags
    contain that routing code

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

Default routing rules and representative routing tests now read the
`workerScheduling*` view. Existing rules may continue using `workerContext*`
variables until their owning fixtures are migrated.

Do not add new engine behavior that depends on WorkerContext as an account or
device lifecycle owner. If account/device state is needed, it should enter the
engine as system-event-updated worker scheduling attributes.

## Out Of Scope For This Step

- no WorkerContext model deletion
- no WorkerContext storage/API deletion
- no foreground/background task semantics
- no worker capacity or load view
- no account-switch dispatch protocol
- no trace field removal

## Verification

Focused proof for this step:

- `WorkerMatchContextTest`
  - proves legacy context fields still exist
  - proves new worker-level scheduling fields are present
  - proves context attributes are flattened into scheduling attributes
- `RuleBasedTaskWorkerMatchingStrategyTest`
  - protects current matching behavior
- `EngineSchedulingCoreSuite`
  - protects assignment behavior

Server and trace suites remain useful because no runtime behavior or trace schema
changed in this slice.

## Next Cut

The next small implementation step should reduce duplicated snapshot assembly
and move more prefilter decisions to consume `WorkerSchedulingView` directly
while keeping WorkerContext storage, runtime binding, and trace fields intact.
Only after that is proven should the matching strategy stop treating
WorkerContext as the primary scheduling resource.
