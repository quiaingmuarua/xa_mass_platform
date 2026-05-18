# Worker Scheduling View Baseline

Last updated: 2026-05-16

Status: current baseline for the WorkerContext convergence path,
including scheduling-candidate handoff, default rule surface convergence,
worker load view wiring, load-aware ranking, and default-capacity reservation.
WorkerContext physical model/storage/API surfaces have been deleted. Runtime,
transport, projection, SDK/API, and server E2E payloads are worker-level. The
remaining WorkerContext references are source guards and documentation that
prevent regression.
Worker-declared capacity is now present as a read-model input to reservation
and trace. `ExecutionSpec.foreground` is now present as a task scheduling-mode
declaration in model/API/trace surfaces and controls long-lived worker lock
usage. Background tasks can share workers up to declared capacity without
consulting WorkerContext identity in the scheduling read model. The foreground
resource decision is now owned by `WorkerDispatchResourcePolicy` and consumed by matching, dispatch binding,
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
longer flatten context status/project/routing/attributes into scheduling facts.
`WorkerSchedulingView` no longer accepts or exposes WorkerContext identity.

This document records the current engine scheduling read model after the
WorkerContext convergence slices. It is intentionally narrow: this path records
the scheduling kernel state and does not remove the nullable historical trace
schema field.

## Fixed Mainline Relationship

This document covers only the worker scheduling read model. It must be read
inside the fixed scheduling mainlines from
[`SCHEDULING_KERNEL_GUARDRAILS.md`](./SCHEDULING_KERNEL_GUARDRAILS.md):

```text
WorkerSchedulingCandidateEnumerator
  -> WorkerSchedulingView
  -> WorkerMatchContext
  -> prefilter + QLExpress rules
  -> WorkerCandidateRanker
  -> capacity reservation + optional worker lock
```

The last step is intentionally described as acquisition, not pure matching.
Current code performs reservation and optional exclusive worker-lock acquisition
inside `RuleBasedTaskWorkerMatchingStrategy`. This is an acceptable transitional
shape while the engine is still converging WorkerContext and worker-management
boundaries. It must not be used as permission to hide unrelated lifecycle
mechanisms inside matching code.

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
- current runtime, transport, projection, SDK/API, and server proof surfaces
  stay worker-level

## Current Hot-Path Inventory

WorkerContext has been retired from these engine hot-path surfaces:

- `WorkerSchedulingCandidateEnumerator`
  - creates one worker-level `WorkerSchedulingCandidate` per worker
  - no longer reads `WorkerManager.getWorkerContextsByWorkerIds(...)`
  - no longer expands optional legacy contexts into scheduling candidates
  - builds `WorkerSchedulingView` for prefilter decisions, rule context, and
    diagnostic snapshots
- `RuleBasedTaskWorkerMatchingStrategy`
  - consumes already-enumerated `WorkerSchedulingCandidate` objects
  - does not import `WorkerContext`; it reads scheduling facts from the
    candidate/view
  - prefilters worker reachability, worker availability, target-worker
    constraints, and worker-level routing through the scheduling view
  - currently ranks rule-passed candidates and attempts capacity reservation
    plus optional exclusive worker-lock acquisition in ranked order
  - emits match accepted/rejected trace with worker-level identity
- `AssignmentDiagnosticRecorder`
  - records worker-level matching diagnostics from
    `WorkerSchedulingCandidate`
  - snapshots `WorkerSchedulingView` evidence through
    `WorkerSchedulingSnapshot`
  - records no legacy context identity on worker scheduling snapshots
- `WorkerSchedulingCandidate`
  - is the internal handoff type between matching, allocation, listener
    orchestration, and dispatch binding
  - carries `Worker` and `WorkerSchedulingView`
  - does not carry a nullable `WorkerContext` payload or context-id accessor
- `WorkerMatchContext`
  - is constructed from `WorkerSchedulingCandidate`
  - owns the rule-evaluation and diagnostic snapshot field map consumed by
    matching prefilter records and QLExpress evaluation
  - exposes flattened `workerScheduling*` variables
  - exposes `isWorkerSchedulingResource*` aliases for resource state checks
  - exposes worker load and reservation fields from `WorkerLoadView`
- `SimpleTaskDispatchBinder`
  - uses `WorkerClaimTarget.workerLevel(...)` when claiming runtime work for
    scheduling candidates
  - generates current attempt ids with worker-level identity
  - produces worker-level dispatch bindings and transport payloads
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
  - uses task/worker resource usage as release-policy input
- `TaskResultCorrelationSupport`
  - creates worker-level result-correlation snapshots from runtime leases
  - no longer carries WorkerContext identity; `workerId`, `batchId`, and
    `attemptId` are the execution identity
- result runtime drafts
  - use worker-level result writes only
  - expose worker-level result identity
- runtime contract tests
  - use `WorkerClaimTarget.workerLevel(...)` for normal claim/lease proof
  - use worker-level result draft factories for normal visible result rows
- SDK/API result rows
  - expose worker-level result identity
  - prove worker-level result reads use `workerId`, `batchId`, and
    worker-level attempt ids
- dispatch bindings
  - use worker-level binding output
  - expose and decode worker-level binding output
- `WorkerDispatchResourceReleaser`
  - owns assignment and binder compensation cleanup for worker reservations,
    conditional exclusive worker unlock, canonical lock-release trace, and
    release-listener attempt/terminal close lock-release paths
  - keeps lock-release policy worker/task based
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
  - prevents `WorkerSchedulingView` from reading WorkerContext identity or
    lifecycle facts and prevents `WorkerMatchContext` from exposing
    `workerContext*` rule fields

The production matching hot path no longer uses WorkerContext as a scheduling
attribute source, and `WorkerSchedulingCandidate` no longer carries a
`WorkerContext` object. `WorkerSchedulingView` no longer imports or accepts the
WorkerContext model, and `WorkerMatchContext` no longer exposes
`workerContext*` rule variables. Runtime, transport, projection, SDK/API, and
server result surfaces use worker-level identity.

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
worker until `active + reserved >= declaredCapacity`.
`WorkerDispatchResourcePolicy` is the single engine-internal owner for this
resource usage classification. It is worker/load based; account-slot identity
does not affect exclusive-lock decisions or runtime claim targets.
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
terminal cleanup paths resolve lock release with `usageForAttempt(...)`. These
paths are governed by task foreground/background semantics, not context
identity.
Accepted and rejected worker-match trace events read the current load snapshot
at the reservation decision point, so `workerReservedCount` can prove pending
reservation evidence in canonical JSONL.
Worker-match trace also carries worker scheduling evidence:
`workerSchedulingResourceId`, `workerSchedulingRoutingTags`,
`workerSchedulingAttributes`, and `workerSchedulingMatchesRoutingCode`.
Scheduling proof must use these fields rather than account-slot identity.
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

No context-id rule or trace-query field remains in the current scheduling proof
surface.

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
engine diagnostics.
Representative engine and server routing proof now uses stateless worker
registration attributes for the matched/mismatched routing candidates. Normal
matching, trace, prefilter, and routing proof uses stateless workers plus worker
scheduling attributes.
Do not add default rules or in-repo routing fixtures that depend on
`workerContext*` variables. `RuleConfigTest` and
`EngineSchedulingCoreArchitectureGuardTest` guard the default rule sets and
the engine rule-context source.

Do not add new engine behavior that depends on WorkerContext as an account or
device lifecycle owner. If account/device state is needed, it should enter the
engine as system-event-updated worker scheduling attributes.

## Out Of Scope For This Step

- no account-switch dispatch protocol
- no canonical trace schema field removal
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
  - proves the handoff keeps worker plus scheduling view without WorkerContext
    identity
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
    routing without account-slot evidence
  - is also covered by `TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest`,
    which registers stateless workers through the real Boot/API/SDK/transport
    path and analyzes canonical JSONL for worker attribute routing
- `EngineSchedulingCoreSuite`
  - protects assignment behavior
- `EngineSchedulingCoreArchitectureGuardTest`
  - protects the scheduling-owner boundaries described above

Server and trace suites remain useful because scheduling proof still depends on
real Boot/API/SDK/transport wiring plus canonical JSONL, not projection or MDC
logs.

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
stateless worker cleanup without account-slot identity or
`WORKER_CONTEXT_STATUS_TRANSITION` as the success evidence.

WorkerContext physical model/API/runtime/transport/projection payload deletion
is complete, including canonical trace identity.
