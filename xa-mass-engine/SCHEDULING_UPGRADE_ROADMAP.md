# Scheduling Upgrade Roadmap

Last updated: 2026-05-16

Status: proposed long-range roadmap. This is not a current engine baseline and
must not be read as implemented behavior, except for progress notes explicitly
marked as implemented.

Progress:

- 2026-05-15: Step 1 was implemented. The inert `TaskScheduler` /
  `SimpleTaskScheduler` SPI and no-op scheduler builder/config wiring were
  removed from in-repo production and test code.
- 2026-05-15: Step 2 started. A transitional `WorkerSchedulingView` was added
  and `WorkerMatchContext` now exposes worker-level `workerScheduling*` fields
  beside legacy `workerContext*` rule variables.
- 2026-05-15: Step 2 continued. Default routing rules and representative
  routing tests now read `workerScheduling*` fields while legacy
  `workerContext*` variables remained available until WC-3E.
- 2026-05-15: Step 2 continued. Matching prefilter decisions and diagnostic
  snapshots now consume `WorkerSchedulingView` for context allocatability,
  project, and routing checks while preserving legacy trace/record fields.
- 2026-05-15: Step 2 candidate handoff convergence was implemented. Matching,
  allocation, listener orchestration, and dispatch binding now exchange
  `WorkerSchedulingCandidate` instead of a context-first matched resource type.
- 2026-05-15: Step 2 rule surface convergence was implemented. Default and
  derived rule sets now use `workerScheduling*` / `isWorkerScheduling*`
  variables. Legacy `workerContext*` transition variables were removed from
  `WorkerMatchContext` later in WC-3E.
- 2026-05-15: Step 4 observational foundation was implemented early. A
  process-local `WorkerLoadView` now tracks runtime claim/final callbacks and
  exposes load fields through `WorkerSchedulingView` / `WorkerMatchContext`.
  It started as observational and was later extended by Step 7A reservation.
- 2026-05-15: Step 7A reservation foundation was implemented without adding
  foreground/background or worker-declared public capacity fields. Matching
  now reserves default worker capacity before lock acquisition; dispatch
  binding confirms or releases that reservation around runtime claim outcomes.
- 2026-05-15: Step 7B trace-observed reservation proof was implemented.
  Worker match trace now records reservation-time load snapshots, and
  `xa-mass-trace` includes the `capacity-reservation-under-concurrency`
  scenario analyzer over canonical assignment rows.
- 2026-05-15: Step 7C worker-declared capacity read model was implemented.
  `Worker.maxConcurrentWork` now defaults to `1`, SDK worker registration can
  declare it, and `WorkerManager` synchronizes the declaration into
  `WorkerLoadView` snapshots. Shared/background execution is still not enabled;
  the existing worker lock remains the dispatch guard.
- 2026-05-15: Step 7D foreground scheduling mode read surface was implemented.
  `ExecutionSpec.foreground` now defaults to `true` and is carried through SDK
  task options, server task execution responses, assignment trace attrs, and
  `xa-mass-trace assignment` rows. Shared/background dispatch behavior is still
  not enabled; the current worker lock remains exclusive.
- 2026-05-15: Step 7E foreground/background lock behavior was implemented for
  the current stateless-worker path. `foreground=true` keeps the existing
  exclusive worker lock; `foreground=false` skips the long-lived worker lock and
  relies on `WorkerLoadView` capacity reservation. WC-3F later collapsed
  WorkerContext identity out of this resource policy.
- 2026-05-15: Step 7F server trace-observed wiring proof was implemented.
  `TaskApiBackgroundWorkerSharingTraceObservedIntegrationTest` creates
  `foreground=false` tasks through the real Boot/API/SDK/transport path, keeps
  one stateless worker lease active, dispatches a second task to the same
  capacity-2 worker, and validates canonical JSONL with the
  `background-worker-sharing` analyzer.
- 2026-05-15: Step 8A worker budget foundation was implemented without
  changing default scheduling behavior. `WorkerBudgetPolicy` now feeds
  `DefaultAssignmentAllocationPolicy`, `WorkerLoadView` exposes current active
  worker count per task, and assignment trace can emit `workerBudget`,
  `currentTaskWorkerCount`, and `budgetLimited`.
- 2026-05-15: Step 8B bounded default budget was implemented. The default
  `WorkerBudgetPolicy` now applies internal workload-class caps
  (`INTERACTIVE=5`, `BULK=20`), `AssignmentAllocationPolicy` emits an explicit
  `BUDGET_EXHAUSTED` decision when a task has no remaining worker budget, and
  assignment trace records the cap through the existing budget fields.
- 2026-05-15: Step 8C refill owner convergence was implemented.
  `AssignmentRefillPolicy` now owns whether a released worker slot should
  request another assignment attempt. `TaskResourceReleaseListener` remains the
  resource release mechanism and consumes the refill decision.
- 2026-05-15: Step 8D dispatch resource owner convergence was implemented.
  `WorkerDispatchResourcePolicy` now owns worker-level exclusive lock semantics
  and legacy WorkerContext lifecycle classification across matching, assignment
  cleanup, dispatch binding, and resource release.
- 2026-05-15: WorkerContext retirement WC-1 started. Representative engine and
  server routing proof now uses stateless worker attributes (`routingTag` /
  `routingTags`, `country`) instead of `WorkerContextRegistration` attributes.
  `WorkerContext` storage/API/runtime lifecycle remains in place for later
  retirement phases.
- 2026-05-15: WorkerContext retirement WC-2 started. Legacy WorkerContext
  candidate expansion moved behind `WorkerSchedulingCandidateEnumerator`, and
  `RuleBasedTaskWorkerMatchingStrategy` now consumes scheduling candidates
  without directly reading WorkerContext storage. This transitional candidate
  payload was removed later in WC-3D.
- 2026-05-15: WorkerContext retirement WC-2B test proof convergence was
  implemented. Strategy-level matching, trace, and prefilter proof now uses
  stateless worker scheduling attributes by default; remaining context-backed
  strategy fixtures are explicitly named `legacyContext*` and guarded as
  transitional coverage.
- 2026-05-15: WorkerContext retirement WC-2C strategy import convergence was
  implemented. `RuleBasedTaskWorkerMatchingStrategy` no longer imports
  `WorkerContext`; the production strategy package's only direct WorkerContext
  import/storage read is the transitional `WorkerSchedulingCandidateEnumerator`.
- 2026-05-15: WorkerContext retirement WC-2D diagnostic handoff convergence
  was implemented. Worker-level assignment diagnostics now consume
  `WorkerSchedulingCandidate`; matching strategy code no longer unwraps
  WorkerContext payloads and leaves legacy identity extraction inside
  trace/diagnostic owners.
- 2026-05-15: WorkerContext retirement WC-2E rule snapshot owner convergence
  was implemented. `WorkerMatchContext` now owns the rule and diagnostic
  snapshot field map, and `RuleBasedTaskWorkerMatchingStrategy` consumes that
  read model for prefilter records instead of maintaining a duplicate
  `workerScheduling*` / `workerContext*` snapshot builder.
- 2026-05-15: WorkerContext retirement WC-2F proof replacement baseline was
  implemented. Worker match trace now carries worker scheduling evidence, and
  `worker-attribute-routing-without-context` proves stateless worker attribute
  routing through canonical JSONL without using `workerContextId` as the
  scheduling success proof.
- 2026-05-15: Step 8E legacy WorkerContext lifecycle owner convergence was
  implemented. `LegacyWorkerContextResourceLifecycle` now owns the transitional
  `IDLE -> RESERVED -> OCCUPIED -> IDLE` WorkerContext mutation and trace path
  consumed by dispatch binding and resource release mechanisms. This was later
  retired by WC-3A.
- 2026-05-15: Step 8F dispatch resource cleanup owner convergence was
  implemented. `WorkerDispatchResourceReleaser` now owns the repeated
  reservation release plus conditional exclusive worker unlock mechanism and
  canonical `WORKER_LOCK_RELEASED` trace used by assignment cleanup, binder
  compensation, dispatch-submit failure retry compensation, and
  release-listener attempt/terminal close paths.
- 2026-05-15: Step 8G scheduling owner architecture guards were implemented.
  `EngineSchedulingCoreArchitectureGuardTest` now prevents listener/binder
  orchestration from calling dispatch cleanup primitives directly, initially
  kept transitional WorkerContext state mutation isolated, and keeps retired
  context-first matching handoff types removed from engine source and
  scheduling tests. WC-3A later changed this guard to forbid WorkerContext
  runtime state mutation in the engine mainline.
- 2026-05-15: Step 8H dispatch resource policy consistency was implemented.
  Legacy WorkerContext-backed candidates and attempts now keep the exclusive
  worker lock even for `foreground=false` tasks, so only stateless background
  candidates can share worker capacity. Candidate cleanup uses
  `usageForCandidate(...)`, and attempt/terminal cleanup uses
  `usageForAttempt(...)`, matching the acquisition path's policy granularity.
  This transitional context-exclusive behavior was removed by WC-3F.
- 2026-05-15: Step 8I cross-task fairness trace proof was implemented.
  `xa-mass-trace` now includes the `cross-task-worker-fairness` analyzer. The
  scenario reads canonical assignment rows for `<bulkTaskId>,<interactiveTaskId>`
  and proves that budget-limited BULK backlog pressure still leaves distinct
  worker capacity for successful INTERACTIVE dispatch.
- 2026-05-15: Step 8I server trace-observed wiring proof was implemented.
  `TaskApiCrossTaskWorkerFairnessTraceObservedIntegrationTest` creates real
  Boot/API/SDK/WebSocket work for concurrent BULK and INTERACTIVE tasks,
  verifies the BULK task remains capped by the default worker budget with ready
  backlog, verifies the INTERACTIVE task dispatches to a distinct worker, and
  then proves the behavior through canonical JSONL using
  `cross-task-worker-fairness`. `WorkerLoadView` reservation confirmation now
  also contributes to per-task active worker count so budget decisions see
  confirmed runtime claims.
- 2026-05-15: WorkerContext retirement WC-2G boundary inventory was recorded.
  The plan now separates kernel-facing worker scheduling views, reachability,
  load/reservation, dispatch events, result convergence, and trace evidence
  from worker-management/system-event ownership for device/account CRUD,
  account switching, capability refresh, and operator administration.
- 2026-05-16: WorkerContext retirement WC-2H worker cleanup proof replacement
  was implemented. `WorkerDispatchResourceReleaser` now emits worker-level
  `RESOURCE_RELEASED` evidence when an exclusive worker lock is released, and
  `xa-mass-trace` includes `worker-resource-cleanup-without-context` to prove
  stateless worker cleanup without relying on `workerContextId` or
  `WORKER_CONTEXT_STATUS_TRANSITION`.
- 2026-05-16: WorkerContext retirement WC-3A runtime lifecycle removal was
  implemented. `LegacyWorkerContextResourceLifecycle` was deleted, binder and
  release-listener paths no longer mutate `WorkerContextStatus`, and engine
  scheduling tests now prove context-backed resource occupancy with active
  runtime leases plus worker locks rather than `OCCUPIED` context state.
- 2026-05-16: WorkerContext retirement WC-3B diagnostic snapshot convergence
  was implemented. Assignment diagnostics now snapshot
  `WorkerSchedulingView` evidence through `WorkerSchedulingSnapshot`; the
  engine-owned `WorkerContextSnapshot` diagnostic model was deleted, and
  `workerContextId` remains only as legacy payload identity.
- 2026-05-16: WorkerContext retirement WC-3C matching context expansion removal
  was implemented. `WorkerSchedulingCandidateEnumerator` now creates one
  worker-level candidate per worker and no longer reads WorkerContext storage;
  context-backed matching fixtures were removed from the strategy proof
  surface and source guards now forbid WorkerContext storage/payload usage in
  the matching strategy package.
- 2026-05-16: WorkerContext retirement WC-4B physical model/API deletion was
  implemented. `WorkerContext` and `WorkerContextStatus` were deleted from
  base, WorkerContext CRUD was removed from storage, SDK/server context
  registration/query endpoints were deleted, frontend/operator resource pages
  were removed, and test-only worker-context fixture inputs were retired in
  favor of worker attributes. Runtime/transport/trace may still carry
  `workerContextId` string residue until a dedicated payload cleanup phase.
- 2026-05-16: WorkerContext retirement WC-3D candidate payload removal was
  implemented. `WorkerSchedulingCandidate` now carries only `Worker` plus
  `WorkerSchedulingView`; the handoff no longer carries a nullable
  `WorkerContext` object.
- 2026-05-16: WorkerContext retirement WC-3E rule/read-model compatibility
  shrink was implemented. `WorkerSchedulingView` no longer flattens
  WorkerContext status/project/routing/attributes into scheduling facts,
  `WorkerMatchContext` no longer exposes `workerContext*` rule variables, and
  matching prefilter now uses only worker-level scheduling evidence.
- 2026-05-16: WorkerContext retirement WC-3F runtime/resource compatibility
  identity narrowing was implemented. `WorkerDispatchResourceUsage` now models
  only worker-level exclusive lock usage, `WorkerDispatchResourcePolicy` no
  longer derives resource semantics from WorkerContext identity, and the default
  dispatch binder no longer passes candidate `workerContextId` into runtime
  claim targets.
- 2026-05-16: WorkerContext retirement WC-4A scheduling view/model dependency
  removal was implemented. `WorkerSchedulingView` no longer imports, accepts,
  or exposes `WorkerContext`; scheduling-core harness workers are registered
  through worker attributes instead of WorkerContext fixtures.
- 2026-05-16: WorkerContext retirement runtime proof convergence continued.
  Runtime work-contract, in-memory runtime, and Redis runtime tests now use
  `WorkerClaimTarget.workerLevel(...)` for normal claim/lease proof. Direct
  context-backed claim targets remain only in explicit compatibility or
  historical trace cases, and an architecture guard prevents scattered direct
  `TaskResultCorrelation` construction outside its named factories.

This document records the intended path for a long-running engine scheduling
upgrade. The work is deliberately split into small, independently verifiable
steps because this module is the kernel for assignment, runtime dispatch, and
result convergence.

Read this roadmap with [`SCHEDULING_KERNEL_GUARDRAILS.md`](./SCHEDULING_KERNEL_GUARDRAILS.md).
The roadmap describes upgrade order and future slices; the guardrails define the
current rule that mechanism owners must stay stable while policy quality evolves.

## 1. Goal

Move scheduling from "binary eligibility plus storage-order iteration" toward a
load-aware, priority-aware, trace-verifiable scheduling system while first
converging the engine model boundaries.

The long-term target is:

- engine matching consumes a read-only worker scheduling view
- worker/device/account lifecycle management lives outside the engine hot path
- task allocation policy owns requested worker counts and dispatch limits
- matching rules remain the correctness gate
- ranking, priority, capacity, and fairness add scheduling quality above that
  gate
- canonical JSONL trace can reconstruct each scheduling decision chain

## 2. Non-Negotiable Execution Rules

Each phase must be small enough to merge and verify on its own.

- Do not combine model retirement, new capacity semantics, public API changes,
  and trace analyzer changes in one large step.
- Do not introduce parallel old and new live paths. When a phase replaces an
  internal path, update in-repo callers and remove the replaced path.
- Do not let trace requirements reverse-drive runtime ownership. Add trace attrs
  only after the owner decision is clear.
- Do not reassign allocation formulas back to `TaskWorkerAssignListener`.
  `AssignmentAllocationPolicy` owns desired/requested worker counts, minimum
  start gates, and dispatch candidate limits.
- Keep `SimpleTaskDispatchBinder` as runtime claim/bind owner. It must not become
  an allocation policy or matching policy.
- Keep `TaskWorkerAssignListener` as orchestration owner: invoke matching,
  unlock or release skipped resources, update task state, emit assignment trace,
  and publish assignment events.

## 3. Target Owner Boundaries

The main boundary change is not simply "delete WorkerContext". The deeper goal
is to remove worker context, account, or device lifecycle management from the
engine scheduling hot path.

| Area | Owner | Engine role | Engine must not own |
| --- | --- | --- | --- |
| Worker/device/account registration and refresh | Worker management / system event layer | Consume a read-only scheduling view | Device CRUD, account slot lifecycle, account availability state machine |
| Worker scheduling attributes | Worker management view, populated from registration, heartbeat, or system events | Read attributes for matching | Treat attributes as engine-managed resources |
| Worker reachability | Transport presence view | Read reachability at match time | Poll transport or infer device lifecycle directly |
| Worker load | Engine runtime/load view | Read load snapshot and update from lease/attempt lifecycle | Query runtime per worker inside matching loops |
| Assignment allocation | `AssignmentAllocationPolicy` | Decide requested match count, gates, and dispatch candidate limits | Runtime claim, task state mutation, result finality |
| Matching | `TaskWorkerMatchingStrategy` and rule/ranker components | Decide which workers match and their preference order | Task terminal policy or runtime claim/bind |
| Runtime claim/bind | `SimpleTaskDispatchBinder` | Claim work, create attempts, emit dispatch binding trace | Worker allocation policy |
| Account switching during execution | Worker / adapter / execution side | Optionally pass execution hints and process normal results | Account switch protocol ownership |

## 4. Mechanism And Policy Separation

The kernel may start with fixed default strategies. It must not mix strategy
logic back into orchestration or runtime mechanisms, because that makes later
upgrades destructive.

Default policy is acceptable. Hidden policy inside mechanism is not.

### Rule

- Mechanisms execute lifecycle work: queueing, locking, claiming, attempt
  mutation, task status transition, event publication, compensation, and trace.
- Policies decide among valid choices: how many workers to request, whether a
  start gate is satisfied, how eligible candidates are ordered, whether a budget
  caps this round, and which capacity decision applies.
- A policy returns a plan, decision, score, or reason. It must not mutate task
  state, claim runtime work, unlock workers, publish events, or decide terminal
  result semantics.
- A mechanism consumes the policy decision and performs the mutation or side
  effect.
- A default policy can be the only production implementation for a long time.
  That default must still live behind an engine-internal owner boundary when the
  decision is a real policy.
- Do not expose a policy through SDK/server configuration until there is a real
  external use case. Internal constructor injection for tests is enough during
  the convergence phases.
- Do not add a seam merely to forward calls. A seam is justified only when it
  changes who owns a decision, protects a lifecycle/protocol boundary, or makes
  a future upgrade non-destructive.

### Current and planned policy owners

| Policy owner | Status | Owns | Must not own |
| --- | --- | --- | --- |
| `AssignmentAllocationPolicy` | Current internal seam | desired worker count, required start count, requested match count, dispatch candidate limit | matching, runtime claim, task state mutation |
| Matching rules | Current fixed/rule-driven policy | eligibility of a candidate worker view | ranking, task terminal, runtime claim |
| `WorkerCandidateRanker` | Current internal seam | preference order among rule-passed candidates | eligibility correctness, locking/reservation |
| `WorkerBudgetPolicy` | Current internal seam | per-task worker budget ceiling | task state mutation, runtime claim |
| `AssignmentRefillPolicy` | Current internal seam | post-release assignment refill decision | resource release, worker unlock, runtime claim |
| `WorkerDispatchResourcePolicy` | Current internal seam | worker-level exclusive lock requirement and legacy WorkerContext resource classification | matching eligibility, runtime claim, task state mutation |
| Capacity/reservation policy | Partial internal foundation | default single-capacity reservation handoff; foreground/background capacity decision remains planned | attempt lifecycle finality |
| Runtime profile resolver | Current fixed policy | workload class to lane/priority/profile mapping | queue mechanism, worker selection |

### Current and planned mechanisms

| Mechanism | Owns | Consumes policy from |
| --- | --- | --- |
| `TaskAssignWorker` | assignment signal queueing, lane workers, retry/defer, queue snapshots | runtime profile / dispatch priority |
| `TaskWorkerAssignListener` | assignment orchestration, status transition, dispatch resource cleanup, assignment trace, task update, assignment event publication | `AssignmentAllocationPolicy`, matching strategy, `WorkerDispatchResourcePolicy`, `WorkerDispatchResourceReleaser` |
| `WorkerSchedulingCandidateEnumerator` | one worker read-model row to one scheduling candidate | worker storage/read views, reachability, load snapshots |
| `RuleBasedTaskWorkerMatchingStrategy` | scheduling-candidate consumption, rule evaluation execution, lock/reservation attempt, match trace | matching rules, `WorkerCandidateRanker`, `WorkerDispatchResourcePolicy`, load/reachability views, candidate enumerator |
| `SimpleTaskDispatchBinder` | runtime claim, attempt creation, dispatch binding, handoff compensation, binding trace | allocation/matching output, `WorkerDispatchResourcePolicy`, `WorkerDispatchResourceReleaser` |
| `TaskResourceReleaseListener` | worker load finalization and worker lock release orchestration after attempt/terminal close | `AssignmentRefillPolicy`, `WorkerDispatchResourcePolicy`, `WorkerDispatchResourceReleaser` |
| `WorkerDispatchResourceReleaser` | release worker reservations, conditionally unlock exclusive worker locks, emit lock-release trace for assignment cleanup, binder compensation, dispatch-submit failure compensation, and attempt/terminal close paths | `WorkerDispatchResourcePolicy` |
| `WorkerLoadView` | push-updated load snapshot and process-local reservation accounting | runtime/attempt lifecycle events, matching reservation handoff |
| `WorkerReachabilityView` | push-updated transport reachability snapshot | transport presence events |

### Acceptance check

Whenever a scheduling change adds or changes a decision, the implementation must
state:

- which policy owns the decision
- which mechanism consumes it
- what evidence appears in canonical trace
- which focused tests prove the default policy
- which orchestration tests prove the mechanism consumes the policy correctly

## 5. WorkerContext Direction

Current `WorkerContext` usage has two meanings mixed together:

- matching attributes such as routing tags and worker-context attributes
- a schedulable resource slot with `IDLE -> RESERVED -> OCCUPIED` style state

The target direction is to remove the second meaning from engine scheduling. The
engine should match against a worker-level scheduling view. Context, account, or
device details may still exist in worker management, but they are presented to
the engine as scheduling attributes and system-event-updated read models.

This means the first convergence work should be phrased as:

> Engine scheduling moves from worker-context resource allocation to worker
> scheduling view matching.

Physical deletion of `WorkerContext` types, storage APIs, and HTTP endpoints is a
later cleanup step after the hot path no longer depends on them.

## 6. Proof Surface

Every phase needs at least one proof surface. High-risk phases need all three.

- Engine: `EngineSchedulingCoreSuite` plus focused unit tests.
- Server: `ServerSchedulingE2eSuite` plus representative focused E2E tests.
- Trace: canonical JSONL analyzed by `xa-mass-trace`; never MDC logs,
  compatibility projection, or database reads as the proof.

Recommended baseline commands:

```powershell
.\mvnw.cmd -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=EngineSchedulingCoreSuite" test
```

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=ServerSchedulingE2eSuite" test
```

```powershell
.\mvnw.cmd -pl xa-mass-trace -am test
```

Phase-specific trace scenarios should also be verified through
`TraceOperatorService.analyze(...)` or the `xa-mass-trace analyze` CLI against
canonical JSONL produced by the relevant integration/E2E fixture.

## 7. Roadmap Overview

This roadmap is intentionally more granular than the original proposal. Several
items can be reordered, but each step must preserve the owner boundaries above.

| Step | Name | Behavioral risk | Primary owner touched | Status |
| --- | --- | --- | --- | --- |
| 0 | Roadmap and guardrails | None | Docs | Proposed |
| 1 | Retire inert `TaskScheduler` SPI | Low to medium | Engine/SDK wiring | Implemented 2026-05-15 |
| 2 | Worker scheduling view convergence | Medium | Matching and worker read model | Implemented through default rule surface and candidate handoff; API cleanup proposed |
| 3 | WorkerContext public/storage cleanup | High | Server/API/storage/tests | Proposed |
| 4 | WorkerLoadView foundation | Medium | Runtime/attempt/load view | Implemented; extended by Step 7A reservation |
| 5 | Dispatch priority within lanes | Medium | `TaskAssignWorker` | Implemented 2026-05-15 |
| 6 | Worker candidate ranker | Medium | Matching strategy | Implemented 2026-05-15 |
| 7 | Capacity reservation and foreground/background | High | Matching/runtime/allocation | Reservation foundation and trace analyzer implemented; foreground/background proposed |
| 8 | Cross-task worker budget, fairness, refill owner, and resource usage owner | High | Allocation/release/resource policy/load view | Bounded default budget, refill policy, dispatch resource usage policy, legacy WorkerContext lifecycle owner, dispatch cleanup owner, architecture guards, fairness analyzer, and server trace-observed proof implemented |
| 9 | System-event worker management boundary | High | Worker management integration | Proposed |

## 8. Step 1: Retire Inert TaskScheduler SPI

Status: implemented on 2026-05-15.

### Goal

Remove a documented-but-dead scheduling seam before adding real scheduling
capability.

### Current issue

`TaskScheduler` and `SimpleTaskScheduler` are wired into `TaskManager`,
`EngineConfig`, and builders, but the mainline dispatch loop does not call them.
They imply a parallel scheduling path that does not exist.

### Scope

- Delete `TaskScheduler`.
- Delete `SimpleTaskScheduler`.
- Remove scheduler fields, constructors, and builder options.
- Update tests and harnesses to construct `TaskManager` without scheduler noise.
- Remove or rewrite examples that instantiate the no-op scheduler.

### Out of scope

- No changes to assignment behavior.
- No new scheduler replacement.
- No event-loop or lane model changes.

### Verification

- Reactor compile.
- No references to deleted scheduler types.
- `EngineSchedulingCoreSuite`.
- Existing server scheduling suite.

### Outcome

The scheduler SPI was removed rather than replaced with another wrapper. This
keeps dispatch ownership explicit: assignment enters through `TaskAssignWorker`
and `TaskWorkerAssignListener`, not through a parallel external scheduler path.

### Risk

This touched SDK-facing builder methods. The API decision for this repository
was to remove the dead path directly and update in-repo callers, rather than
preserve a no-op compatibility track.

## 9. Step 2: Worker Scheduling View Convergence

Status: implemented through default rule surface and candidate handoff.
`WorkerSchedulingView` is the matching read model, default rules consume
`workerScheduling*` / `isWorkerScheduling*` variables, and
`WorkerSchedulingCandidate` is the internal handoff between matching,
allocation, listener orchestration, and dispatch binding. Remaining cleanup is
eventual public/storage WorkerContext retirement, not a parallel matching path.

### Goal

Move engine matching toward a single worker-level scheduling view and stop
treating `WorkerContext` as a schedulable engine resource.

### Target shape

Introduce or converge on a read model concept such as `WorkerSchedulingView`
without necessarily adding a new public type immediately.

The view contains scheduling-read data:

- `workerId`
- project and event capabilities
- routing tags or routing attributes
- worker attributes
- reachability
- later: load snapshot
- later: declared capacity

`WorkerContext` still exists as a storage/API input during this step, but the
engine matching hot path consumes flattened worker scheduling attributes and
hands off `WorkerSchedulingCandidate`.

### Scope

- Inventory all `WorkerContext` hot-path reads.
- Define the worker-level scheduling attribute map.
- Update `WorkerMatchContext` so matching rules can read worker-level attributes
  without context-specific variables.
- Add tests proving worker-level attributes can express current routing and
  attribute matching scenarios.
- Replace the internal matching handoff with `WorkerSchedulingCandidate` while
  preserving runtime binding behavior and trace `workerContextId`.
- Migrate default and derived rule sets to worker scheduling variables.
- Keep current behavior until the view is proven.

### Out of scope

- Do not delete context storage or HTTP endpoints yet.
- Do not add `foreground`, `background`, or `maxConcurrentWork`.
- Do not add account switching.
- Do not add capacity scheduling.

### Verification

- Focused `WorkerMatchContext` tests.
- Focused `WorkerSchedulingCandidate` tests.
- Focused `RuleConfig` tests guarding default rule surface.
- `RuleBasedTaskWorkerMatchingStrategyTest`.
- Existing routing E2E tests, with representative routing proof using worker
  registration attributes rather than WorkerContext registration attributes.
- `assignment-success-binding` trace scenario remains valid.

### Trace

If existing canonical events rely on `workerContextId`, keep the field nullable
or transitional until downstream analyzers can validate worker-level events. Do
not remove trace fields before analyzers are updated.

## 10. Step 3: WorkerContext Public/Storage Cleanup

### Goal

Physically retire `WorkerContext` artifacts after engine hot-path matching no
longer depends on them.

Status: physical model, storage CRUD, SDK API, server endpoints,
frontend/operator resource pages, and test-only context fixture inputs are
removed. Remaining work is runtime/transport/trace `workerContextId` payload
residue. The current worker-level dispatch path no longer passes a context
identity into runtime claim targets, creates runtime claim targets through
`WorkerClaimTarget.workerLevel(...)`, generates worker-level attempt ids without
a legacy context placeholder, builds worker-level result-correlation snapshots
for null-context leases, omits the legacy payload field when null, and release
policy no longer accepts `workerContextId` as an input.

### Scope

Completed removals:

- `WorkerContext` model.
- `WorkerContextStatus`.
- context storage APIs.
- worker context registration request/response DTOs.
- context CRUD endpoints.
- SDK context registration/snapshot contracts.

Remaining follow-up candidates:

- context-specific assignment trace requirements.

### Out of scope

- No capacity scheduling.
- No account switch protocol.
- No worker management module implementation unless needed to replace a deleted
  context input.

### Verification

- Reactor compile.
- Source guard: no engine mainline references to `WorkerContext`.
- Server focused routing tests updated to worker attributes.
- `ServerSchedulingE2eSuite`.
- Trace analyzers updated to accept worker-level matching evidence.

### Risk

This is a public/server/API cleanup. It should be broken further if the diff
crosses too many modules at once.

## 11. Step 4: WorkerLoadView Foundation

Status: implemented as a load snapshot plus worker-declared capacity
reservation foundation. Foreground/background semantics remain future phases.

### Goal

Create a push-updated worker load snapshot that matching can read in O(1) per
worker without runtime queries in the hot path.

### Implemented API

The API should include `taskId` from the beginning so later task budgets do not
require another rewrite.

```java
interface WorkerLoadView {
    int getActiveLeaseCount(String workerId);
    int getReservedCount(String workerId);
    double getEstimatedLoadRatio(String workerId);
    WorkerLoadSnapshot snapshot(String workerId);

    boolean tryReserveCapacity(String workerId, String taskId);
    boolean confirmReservation(String workerId, String taskId);
    void releaseReservation(String workerId, String taskId);

    void recordWorkClaimed(String workerId, String taskId);
    void recordWorkFinal(String workerId, String taskId);
}
```

The default implementation is in-memory and process-local. The API includes
`taskId` in lifecycle updates so later task-budget accounting can extend the
same owner without moving claim/final hooks again.

### Owner rule

Load view updates must follow runtime lease/attempt lifecycle, not only result
success paths. Release must happen for:

- successful final result
- failed final result
- retry reset
- lease expiry
- dispatch handoff failure compensation
- manual terminal/cancel cleanup
- any active attempt close path

### Scope

- Add `WorkerLoadView`.
- Add `InMemoryWorkerLoadView`.
- Wire default implementation through engine config/builders.
- Expose load values in `WorkerMatchContext`.
- Record successful runtime claims in `SimpleTaskDispatchBinder`.
- Release observed load from attempt-closed and terminal cleanup in
  `TaskResourceReleaseListener`.
- Keep task/dispatch semantics unchanged; reservation only closes the gap
  between ranked match selection and runtime claim confirmation for the current
  default exclusive worker model.

### Out of scope

- No candidate ranking yet.
- No foreground/background behavior yet unless separately approved.
- No public worker capacity or foreground/background model yet.
- No Redis/distributed load view.

### Verification

- Unit: concurrent claim/release accounting.
- Unit: release path coverage.
- Focused dispatch binder claim/compensation coverage.
- Engine scheduling suite.
- Existing trace scenarios unchanged.

## 12. Step 5: Dispatch Priority Within Lanes

Status: implemented on 2026-05-15 as lane-local queue mechanism.

### Goal

Make `TaskRuntimeProfile.DispatchPriority` affect order inside each existing
lane while preserving lane separation.

### Current issue

`TaskAssignWorker` used FIFO queues per lane. `HIGH` and `NORMAL` priority
were declared but did not affect runtime ordering.

### Implemented scope

- Stamp assignment signals with priority ordinal.
- Add a monotonic sequence number to preserve same-priority FIFO.
- Replace the lane queue with bounded priority semantics using a
  `PriorityBlockingQueue` plus a semaphore-backed capacity guard.
- Preserve queue capacity and `trackedTaskIds` deduplication.
- Keep `ASSIGNMENT_QUEUE_SNAPSHOT` profile evidence queryable through
  `xa-mass-trace assignment`, including `dispatchPriority`.

### Out of scope

- No cross-lane preemption.
- No user-defined priority beyond existing runtime profile resolution.
- No candidate ranking or worker load changes.
- No new trace scenario yet. The current fixed runtime profile maps
  `INTERACTIVE` to `INTERACTIVE/HIGH` and `BULK` to `BULK/NORMAL`, so real
  same-lane HIGH/NORMAL evidence needs a later profile-policy slice or a
  canonical queue-order attribute before `priority-lane-ordering` becomes a
  meaningful analyzer.

### Important design point

Do not directly replace bounded `LinkedBlockingQueue` with unbounded
`PriorityBlockingQueue` unless capacity is preserved elsewhere.

### Verification

- Unit: high priority drains before normal within the same lane.
- Unit: same-priority FIFO order.
- Unit: queue capacity still applies.
- Engine scheduling suite.
- Trace/operator: assignment rows expose `dispatchPriority` from canonical
  JSONL. The dedicated `priority-lane-ordering` analyzer remains future work.

## 13. Step 6: WorkerCandidateRanker

Status: implemented on 2026-05-15 as an engine-internal ranker seam.

### Goal

Rank rule-passed candidates before lock/reservation so worker selection is not
storage-order biased.

### Implemented SPI

```java
interface WorkerCandidateRanker {
    List<WorkerMatchContext> rank(List<WorkerMatchContext> candidates, Task task);
}
```

The ranker must not mutate its input and must not replace eligibility rules.

### Pipeline

The intended pipeline is:

```text
storage candidates
-> cheap prefilter
-> build WorkerMatchContext
-> QLExpress eligibility rules
-> collect passed candidates
-> ranker.rank(...)
-> lock/reserve in ranked order
-> return matched workers
```

Ranker placement after QLExpress keeps rules as the correctness gate and avoids
ranking candidates that would be rejected anyway.

### Default scoring direction

Lower score is better:

```text
score = loadWeight * loadRatio
      + affinityWeight * (1 - affinityScore)
      + availabilityWeight * availabilityPenalty
```

Weights should be configured through engine config first. System properties can
be a fallback, not the main owner.

### Scope

- Add ranker interface and default implementation.
- Inject ranker into `RuleBasedTaskWorkerMatchingStrategy`.
- Add canonical rank/load evidence to worker match trace attrs:
  `candidateRank`, `candidateScore`, `workerActiveLeaseCount`,
  `workerReservedCount`, `workerDeclaredCapacity`,
  `workerEstimatedLoadRatio`.
- Register `load-aware-worker-selection` analyzer over canonical assignment
  trace rows.
- Keep `TaskWorkerMatchingStrategy.matchWorkers(Task, int)` unchanged.

### Out of scope

- No external ranker plugin API.
- No capacity reservation.
- No performance-history scoring.

### Verification

- Unit: lower-load worker ranks before higher-load equivalent.
- Unit: affinity tie-break.
- Matching strategy test: equivalent workers select lower-load first.
- Server routing E2E remains green.
- Trace scenario: `load-aware-worker-selection`.

## 14. Step 7: Capacity Reservation And Foreground/Background

Status: reservation foundation, trace-observed analyzer, worker-declared
capacity read model, foreground scheduling mode read surface, initial
foreground/background lock behavior, and server trace-observed wiring proof
implemented on 2026-05-15.
`Worker.maxConcurrentWork` is available as a declaration consumed by
`WorkerLoadView`, and `ExecutionSpec.foreground` controls whether matching
requires the long-lived worker lock. `foreground=true` remains the compatible
exclusive default. `foreground=false` can share stateless workers up to declared
capacity. As of WC-3F, WorkerContext identity no longer changes resource policy
or runtime claim targets.

### Goal

Prevent over-commitment across concurrent assignment waves and define exclusive
versus shared worker usage.

### Implemented model declarations

The persisted task model now has:

```text
ExecutionSpec.foreground: boolean
```

The worker model has:

```text
Worker.maxConcurrentWork: int
```

The field defaults to `1` and invalid values are normalized to `1`. SDK worker
registration and worker snapshots expose it as a capability declaration. The
current engine uses it for process-local reservation/load snapshots only; the
long-lived worker lock still prevents shared foreground dispatch.

`ExecutionSpec.foreground` defaults to `true` and is exposed through SDK task
options, server task execution read models, and canonical assignment trace. A
`false` value now changes worker-lock behavior: matching still reserves worker
capacity, but it does not acquire the long-lived exclusive worker lock. This
enables worker sharing up to `Worker.maxConcurrentWork`; WorkerContext identity
is not part of the sharing decision.

### Proposed semantics

- Foreground task: reserves full worker capacity and may use an exclusive worker
  lock for compatibility with current behavior.
- Background task: reserves one capacity unit and must not hold a long-lived
  exclusive worker lock.
- Reservation bridges the gap between match selection and runtime claim.
- Runtime claim confirmation converts reserved capacity to active capacity.
- Dispatch or claim failure releases the reservation.

### Proposed WorkerLoadView additions

```java
boolean tryReserveCapacity(String workerId, String taskId);
boolean confirmReservation(String workerId, String taskId);
void releaseReservation(String workerId, String taskId);
```

The current implemented API is intentionally smaller than the eventual
foreground/background shape. It reserves one capacity unit against the worker's
declared capacity and lets `SimpleTaskDispatchBinder` fall back to
`recordWorkClaimed(...)` when tests or custom matching strategies bypass
reservation.

Worker match accepted/rejected trace rows read the current load snapshot at the
reservation decision point. This makes `workerReservedCount`,
`workerDeclaredCapacity`, and `workerEstimatedLoadRatio` usable as canonical
evidence for the current process-local reservation behavior.

### Scope

- Add reservation accounting.
- Integrate reservation with matching acceptance.
- Integrate confirmation with runtime claim.
- Integrate release with all dispatch failure and task-status failure paths.
- Keep existing rank/load trace attrs (`workerReservedCount`,
  `workerEstimatedLoadRatio`) queryable and register the
  `capacity-reservation-under-concurrency` analyzer. Explicit reservation
  lifecycle event types remain future work.

### Out of scope

- No distributed/Redis-backed capacity reservation.
- No project/operator quota.
- No dynamic runtime priority rebalancing.

### Risk

Current `tryLockWorker` is worker-level exclusive. If it is used unchanged for
background tasks, background sharing will not work. This step must explicitly
define how exclusive locks and capacity reservations interact.

### Verification

- Unit: concurrent reservations respect capacity.
- Unit: failure paths release reservations.
- Engine contention and redispatch tests extended for concurrent waves.
- Trace scenario: `capacity-reservation-under-concurrency`.

## 15. Step 8: Cross-Task Worker Budget And Fairness

### Goal

Prevent a large task, especially BULK work, from consuming the whole worker pool
and starving concurrent higher-value work.

### Owner rule

Budget applies inside `AssignmentAllocationPolicy`, not directly in
`TaskWorkerAssignListener`.

Status: bounded internal defaults implemented. `WorkerBudgetPolicy` now applies
workload-class caps inside the engine (`INTERACTIVE=5`, `BULK=20`) and
`AssignmentAllocationPolicy` produces an explicit `BUDGET_EXHAUSTED` decision
when current active workers for a task consume the whole budget. Public
task-level overrides remain proposed.

### Proposed model

Candidate task field:

```text
ExecutionSpec.maxConcurrentWorkers: int
```

`0` means use policy defaults.

Implemented foundation:

```java
interface WorkerBudgetPolicy {
    WorkerBudgetDecision resolve(Task task, int desiredDispatchWorkerCount, int currentTaskWorkerCount);
}
```

The default implementation returns a finite workload-class budget. The formula
stays inside policy ownership instead of being added to
`TaskWorkerAssignListener`.

Implemented bounded default direction:

- INTERACTIVE: cap desired count to a small default.
- BULK: cap desired count to a larger but bounded default.

Proposed future override:

- explicit `ExecutionSpec.maxConcurrentWorkers` overrides policy default.

### Integration point

`DefaultAssignmentAllocationPolicy.plan(...)` now:

- compute raw desired worker count
- reads current active worker count for the task from request
  inputs
- applies the budget ceiling when the budget policy returns a finite budget
- produce final `desiredDispatchWorkerCount`, `requestedMatchCount`, and
  `dispatchCandidateLimit`
- produce `BUDGET_EXHAUSTED` when no worker budget remains for the task

`TaskWorkerAssignListener` only passes inputs and emits trace from the plan.

### Refill owner

`AssignmentRefillPolicy` now owns the post-release refill decision. The default
policy preserves the existing behavior:

```text
RUNNING task + runtime-ready work -> request assignment dispatch
otherwise -> skip refill
```

`TaskResourceReleaseListener` remains the mechanism owner for worker load
finalization and WorkerContext release orchestration. Assignment cleanup, binder
compensation, and attempt/terminal close paths use
`WorkerDispatchResourceReleaser` for reservation release where applicable plus
conditional exclusive lock release. Dispatch-submit failure compensation also
uses the releaser after runtime retry compensation and observed-load release,
because no attempt-closed event is emitted for that pre-transport handoff
failure path. The listener supplies bounded runtime facts to the refill policy
and consumes the returned decision.

### Trace

Implemented assignment summary attrs:

- `workerBudget`
- `currentTaskWorkerCount`
- `budgetLimited`

### Verification

- Unit: default workload-class budgets cap large assignment plans.
- Unit: exhausted budget produces explicit allocation decision and trace skip.
- Unit: default refill policy preserves `RUNNING && ready-work` behavior and
  skips without reading ready-work for non-running tasks.
- Unit: injected refill policy can suppress refill without blocking resource
  release.
- Unit: `WorkerLoadView` tracks distinct active workers per task.
- Engine: mixed workload contention test proves a large BULK task is capped and
  leaves workers for an INTERACTIVE task.
- Source guard: listener/binder orchestration cannot bypass dispatch cleanup
  owners, and WorkerContext state mutation cannot leak outside the transitional
  lifecycle owner.
- Trace: `cross-task-worker-fairness` proves the mixed workload budget behavior
  from canonical assignment rows.

## 16. Step 9: Worker Management/System Event Boundary

### Goal

Move real worker/device/account management toward a system-event-driven
control-plane owner while keeping engine scheduling dependent only on readable
views.

### Direction

The engine may emit or consume bounded system events, but it must not become the
device/account manager.

Candidate boundaries:

- `WorkerSchedulingViewStore`: engine read-side view for scheduling.
- `WorkerSystemEventSink`: engine publishes worker-related lifecycle needs or
  observations.
- Worker management layer: owns device/account state, registration refresh, and
  scheduling attributes.

### Account execution hint

If scheduling attributes imply a target execution account, dispatch may include
an optional execution hint. This hint is not an engine-managed account lease.

Candidate payload shape:

```text
dispatchInstruction.targetAccount
```

Account switch failure should converge through normal result handling with a
distinct reason such as:

```text
ACCOUNT_SWITCH_FAILED
```

This is a transport/result contract addition and should not be hidden inside
WorkerContext retirement.

### Out of scope

- No distributed worker management implementation unless separately planned.
- No account-slot lease protocol inside engine assignment.

### Verification

- Contract tests for dispatch payload compatibility.
- Result convergence tests for account-switch failure reason.
- Trace scenario: `account-switch-failure`.

## 17. Trace Scenario Roadmap

| Scenario | First step that needs it | Validates |
| --- | --- | --- |
| `worker-scheduling-view-routing` | Step 2 or 3 | Worker-level scheduling attributes preserve current routing behavior |
| `priority-lane-ordering` | Step 5 | HIGH priority is processed before NORMAL in the same lane |
| `load-aware-worker-selection` | Step 6 | Lower-load equivalent worker is selected first |
| `capacity-reservation-under-concurrency` | Step 7 | Process-local capacity reservation is visible and accepted matches are not over committed |
| `background-worker-sharing` | Step 7 | A background task reserves shared stateless worker capacity without long-lived worker lock evidence |
| `worker-attribute-routing-without-context` | WorkerContext retirement WC-2F | Worker-level scheduling attributes route work without WorkerContext evidence |
| `cross-task-worker-fairness` | Step 8 | Large BULK work does not starve concurrent INTERACTIVE work |
| `account-switch-failure` | Step 9 | Account execution failure converges through result handling and trace |

Each analyzer must read canonical sink output through `xa-mass-trace`. It must
not read MDC logs, compatibility projection, or the database.

## 18. Open Questions Before Implementation

These questions should be answered before their corresponding step starts.

1. Is the long-term product decision that account/device/context lifecycle is
   outside engine and system-event-managed?
2. Should `WorkerSchedulingView` be a concrete engine type or only an internal
   concept implemented through existing worker lookup APIs first?
3. During WorkerContext retirement, do we physically delete context APIs in one
   step or first stop using them in the engine hot path?
4. What exact runtime behavior should `foreground=false` enable once the
   current read surface is wired: shared worker dispatch only, or also a
   different result/lease profile?
5. What is the exact unit of worker capacity: worker, active lease, active
   attempt, dispatched message, or assignment binding?
6. How should stateless workers, multi-account workers, and future device pools
   express capability without engine-managed context slots?
7. Which trace attrs are stable enough to add to `TRACE_CONTRACT.md` at each
   phase?
8. Do server E2E fixtures need to generate canonical JSONL for every new trace
   scenario, or can some trace scenarios stay fixture-level inside
   `xa-mass-trace` until server wiring changes?

## 19. Acceptance Rule For Each Step

A step is not done until:

- the owner boundary touched by the step is documented
- old in-repo paths replaced by the step are removed or explicitly still needed
- focused unit tests cover the new owner behavior
- `EngineSchedulingCoreSuite` passes when scheduling correctness is touched
- `ServerSchedulingE2eSuite` passes when Boot/SDK/transport wiring is touched
- canonical trace proof exists when observability or scheduling decisions change
- mechanism and policy ownership are separate; fixed default policies are fine,
  but new policy decisions are not hidden inside orchestration or runtime
  mutation code
- `git diff --check` is clean

## 20. Current Recommended Next Step

Do not jump directly to public WorkerContext API/storage deletion.

Matching context expansion is now removed from the production strategy package.
The candidate handoff no longer carries a nullable `WorkerContext` object. The
rule/read-model compatibility surface has also been reduced: `WorkerSchedulingView`
does not flatten WorkerContext scheduling facts and `WorkerMatchContext` does
not expose `workerContext*` rule variables. The next step should target the
remaining runtime/trace compatibility identity: `workerContextId` can still
appear on attempts, dispatch bindings, assignment snapshots, and public/storage
surfaces.

Full WorkerContext model/API deletion should still wait until engine runtime
payloads, trace analyzers, SDK/server calls, and storage tests no longer need
context-specific fields.
