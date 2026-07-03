# Engine Mainline Convergence Notes

Status: lightweight idea note, not a roadmap.

This note records current-code observations and possible convergence ideas for
`xa-mass-engine`. It is intentionally less formal than a roadmap. Do not treat
any item here as accepted implementation scope until it is promoted into an
owner plan or code change.

## Current Read

The current engine mainline is broadly coherent:

```text
Task shell / append
  -> TaskLifecycleService
  -> TaskWorkRuntime enqueue

runtime-ready / task-ready signal
  -> TaskAssignWorker
  -> TaskWorkerAssignListener

assignment
  -> AssignmentAllocationPolicy
  -> RuleBasedTaskWorkerMatchingStrategy
  -> WorkerCandidateRuntime bounded acquisition
  -> WorkerSchedulingCandidateEnumerator
  -> prefilter / rule evaluation / rank / reserve
  -> SimpleTaskDispatchBinder
  -> TaskWorkRuntime claimReady
  -> transport dispatch batch

result
  -> TaskResultService
  -> TaskWorkRuntime apply result
  -> TaskResultRuntime visible final
  -> task progress reconcile / terminal policy
  -> resource release / refill
```

Important current owner facts:

- `TaskManager` remains the engine assembly facade and implements several
  narrow runtime ports.
- `TaskLifecycleService` owns non-result lifecycle transitions and append
  admission.
- `TaskWorkerAssignListener` owns task-level assignment orchestration.
- `RuleBasedTaskWorkerMatchingStrategy` owns the current default runtime worker
  selection mechanism.
- `SimpleTaskDispatchBinder` owns runtime claim and dispatch binding.
- `TaskResultService` owns result ingest, retry/finality decision flow, lease
  expiry handling, and result-side trace/event ordering.
- `WorkerCandidateRuntime`, `WorkerAdmissionRuntime`, and
  `WorkerSchedulingViewRuntime` come from worker-runtime. Engine consumes these
  seams; it should not re-own worker registry truth. The former warm-hint seam
  has been removed before score-band worker-runtime work.

## What Looks Healthy

- Worker matching is group-selector first and enters through bounded candidate
  acquisition, not event-code or all-worker scans.
- Runtime truth is centered on `TaskWorkRuntime` and `TaskResultRuntime`, not
  compatibility projections.
- Assignment allocation, worker budget, rank, resource usage, refill, and
  terminal behavior already have policy seams.
- `TaskDispatchRequestService` no longer layers batch retry wakeups over
  runtime ready truth; batch redispatch is driven by `RuntimeReadyDispatchPump`.
- Result ingest uses a runtime-first apply context instead of several
  independent hot-path runtime reads.
- Guard tests are extensive and block many old WorkerContext / compatibility /
  all-worker-scan regressions.

## Possible Convergence Ideas

### 1. Make Stage-2 Matching Pipeline More Explicit

Current location:

- `RuleBasedTaskWorkerMatchingStrategy`
- `WorkerSchedulingCandidateEnumerator`
- `DefaultWorkerCandidateRanker`

Observation:

`RuleBasedTaskWorkerMatchingStrategy` currently contains acquisition,
candidate materialization, prefilter, QLExpress rule evaluation, ranking,
reservation, trace, and diagnostics.

This is behaviorally acceptable, but it is difficult to review because several
conceptual steps are hidden inside one method.

Possible low-risk convergence:

- introduce small internal result records such as:
  - `CandidatePrefilterOutcome`
  - `RuleEvaluationOutcome`
  - `RankedCandidate`
  - `ReservedCandidate`
- keep the public strategy interface unchanged
- keep QLExpress and default rank behavior unchanged
- use the records to make tests assert the pipeline boundary instead of
  duplicating end-to-end strategy setup

Do not start by adding a new public strategy facade. The value is readability
inside the existing owner, not a new abstraction layer.

### 2. Reduce Duplicate Policy/Resolver Construction

Current duplicated defaults include:

- `DefaultSchedulingPlaneResolver`
- `DefaultWorkerDispatchResourcePolicy`
- `WorkerDispatchResourceReleaser`
- `TraceEventLogger.noop()` in many tests and constructors

Observation:

The defaulting is not a correctness bug, but it spreads assembly decisions
across listener, strategy, binder, and resource classes.

Possible convergence:

- add a small internal engine assignment assembly value only if it replaces
  repeated constructor defaults with a real owner boundary
- or simply centralize test helper construction first
- avoid pass-through `EngineAssignmentFacade` / `Bridge` objects that only
  forward calls

Decision check:

If this does not reduce constructor duplication or test setup fragility, skip
it.

### 3. Clarify Dispatch Compensation Boundary

Current locations:

- `TaskWorkerAssignListener`
- `SimpleTaskDispatchBinder`
- `WorkerDispatchResourceReleaser`
- `TaskResourceReleaseListener`

Observation:

Reservation, exclusive worker lock release, dispatch submit failure
compensation, no-work release, and refill-trigger release are all correct
concepts, but the cleanup reasons and trigger/source strings are distributed
across multiple call sites.

Possible convergence:

- keep `WorkerDispatchResourceReleaser` as the release owner
- make dispatch failure and no-work cleanup reasons named constants or small
  typed outcomes
- add one focused proof that a failed dispatch submit:
  - releases worker reservation
  - releases exclusive lock when applicable
  - compensates runtime claim
  - leaves ready work redispatchable

Do not move transport delivery into engine. Engine only owns compensation up to
the dispatch batch handoff.

### 4. Split The Safest Part Of TaskResultService Later

Current location:

- `TaskResultService` is large and high risk.

Observation:

It combines result ingest, lease expiry, retry decision, visible-final commit,
barrier cleanup, repair pump, trace publication, and task progress dirty
signals.

Possible low-risk split candidates:

- visible-final commit is already partly isolated in
  `TaskResultVisibleFinalCommitter`
- retry policy decision could become a small internal collaborator
- lease-expiry final/retry decision could be isolated after current tests are
  named around the invariant
- repair scan/pump should stay separate from hot-path result ingest

Do not start with a broad file split. First identify one pure decision seam with
no storage/runtime ownership change.

### 5. Tighten TaskManager Port Vocabulary

Current location:

- `TaskManager implements TaskAssignmentRuntimePort, TaskLeaseMaintenancePort,
  TaskDispatchWakeupPort, TaskShellLifecycleMaintenancePort,
  TaskRuntimeRecoveryPort, TaskStateRuntimePort, TaskQueryPort,
  TaskCommandPort, TaskResultIngestPort`

Observation:

This is acceptable as an owner facade, but it can be hard for new agents to
distinguish stable cross-module ports from engine-internal helper ports.

Possible convergence:

- add a short inventory table to an engine baseline or README section:
  - port name
  - caller family
  - stable cross-module or engine-internal
  - hot path or maintenance path
- do not split `TaskManager` just because it implements many ports
- only split if caller ownership or lifecycle boundary changes

### 6. Keep Runtime-Driven Batch Dispatch Honest

Current locations:

- `RuntimeReadyDispatchPump`
- `TaskDispatchRequestService`
- `PollingIdleAdmissionTracker`

Observation:

Batch dispatch now relies on runtime-ready scan plus idle backoff. This is a
reasonable near-term mechanism while event-driven wakeups mature.

Possible convergence:

- keep the pump mechanism and backoff policy separate
- add or keep proof that worker availability wakeup can break idle delay
- keep scan limit and in-flight task guard explicit
- do not turn the pump into a broad task-storage scanner

## Suggested Order If Promoted

1. Inventory only: current assignment/result/runtime port call graph.
2. Matching pipeline readability: internal outcome records, no behavior change.
3. Dispatch compensation proof and naming cleanup.
4. TaskManager port vocabulary note.
5. One small `TaskResultService` pure-decision extraction.
6. Revisit whether any remaining item deserves a real roadmap.

## Non-Goals For This Note

- No new scheduling behavior.
- No new public API or SDK surface.
- No worker-runtime registry redesign.
- No transport delivery redesign.
- No broad package move.
- No facade/bridge layer unless it changes an actual owner boundary.
- No test coverage chase for its own sake.

## Review Questions

- Is the current matching strategy too large because it owns too much behavior,
  or just because its internal steps are not named?
- Are dispatch compensation invariants already fully proved, or only implied by
  broader scheduling tests?
- Which `TaskManager` ports are real cross-module contracts and which are
  engine-local maintenance seams?
- What is the smallest safe result-service split that makes owner reasoning
  easier without disturbing runtime-first convergence?
