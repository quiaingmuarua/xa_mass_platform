# Scheduling Upgrade Roadmap

Last updated: 2026-05-15

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
  `workerContext*` variables remain available.
- 2026-05-15: Step 2 continued. Matching prefilter decisions and diagnostic
  snapshots now consume `WorkerSchedulingView` for context allocatability,
  project, and routing checks while preserving legacy trace/record fields.
- 2026-05-15: Step 2 candidate handoff convergence was implemented. Matching,
  allocation, listener orchestration, and dispatch binding now exchange
  `WorkerSchedulingCandidate` instead of a context-first matched resource type.
- 2026-05-15: Step 2 rule surface convergence was implemented. Default and
  derived rule sets now use `workerScheduling*` / `isWorkerScheduling*`
  variables, while legacy `workerContext*` variables remain available for
  transition compatibility.
- 2026-05-15: Step 4 observational foundation was implemented early. A
  process-local `WorkerLoadView` now tracks runtime claim/final callbacks and
  exposes load fields through `WorkerSchedulingView` / `WorkerMatchContext`,
  but does not influence matching, allocation, locking, or capacity.

This document records the intended path for a long-running engine scheduling
upgrade. The work is deliberately split into small, independently verifiable
steps because this module is the kernel for assignment, runtime dispatch, and
result convergence.

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
| `WorkerBudgetPolicy` | Planned | per-task worker budget ceiling | task state mutation, runtime claim |
| Capacity/reservation policy | Planned | capacity units and foreground/background capacity decision | attempt lifecycle finality |
| Runtime profile resolver | Current fixed policy | workload class to lane/priority/profile mapping | queue mechanism, worker selection |

### Current and planned mechanisms

| Mechanism | Owns | Consumes policy from |
| --- | --- | --- |
| `TaskAssignWorker` | assignment signal queueing, lane workers, retry/defer, queue snapshots | runtime profile / dispatch priority |
| `TaskWorkerAssignListener` | assignment orchestration, status transition, unlock/release, assignment trace, task update, assignment event publication | `AssignmentAllocationPolicy`, matching strategy |
| `RuleBasedTaskWorkerMatchingStrategy` | candidate enumeration, context construction, rule evaluation execution, lock/reservation attempt, match trace | matching rules, `WorkerCandidateRanker`, load/reachability views |
| `SimpleTaskDispatchBinder` | runtime claim, attempt creation, dispatch binding, handoff compensation, binding trace | allocation/matching output only |
| `WorkerLoadView` | push-updated load snapshot | runtime/attempt lifecycle events |
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
| 4 | WorkerLoadView foundation | Medium | Runtime/attempt/load view | Implemented observational mode 2026-05-15 |
| 5 | Dispatch priority within lanes | Medium | `TaskAssignWorker` | Implemented 2026-05-15 |
| 6 | Worker candidate ranker | Medium | Matching strategy | Implemented 2026-05-15 |
| 7 | Capacity reservation and foreground/background | High | Matching/runtime/allocation | Proposed |
| 8 | Cross-task worker budget and fairness | High | Allocation policy/load view | Proposed |
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
- Existing routing E2E tests.
- `assignment-success-binding` trace scenario remains valid.

### Trace

If existing canonical events rely on `workerContextId`, keep the field nullable
or transitional until downstream analyzers can validate worker-level events. Do
not remove trace fields before analyzers are updated.

## 10. Step 3: WorkerContext Public/Storage Cleanup

### Goal

Physically retire `WorkerContext` artifacts after engine hot-path matching no
longer depends on them.

### Scope

Candidate removals after Step 2 is proven:

- `WorkerContext` model.
- `WorkerContextStatus`.
- context storage APIs.
- worker context registration request/response DTOs.
- context CRUD endpoints.
- context fixtures and snapshots.
- context-specific QLExpress variables.
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

Status: implemented in observational mode. Capacity enforcement, reservation,
foreground/background semantics, and load-aware ranking remain future phases.

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
- Keep behavior unchanged; load is only observed.

### Out of scope

- No candidate ranking yet.
- No capacity reservation yet.
- No foreground/background behavior yet unless separately approved.
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

### Goal

Prevent over-commitment across concurrent assignment waves and define exclusive
versus shared worker usage.

### Open model question

This step should not begin until the team has decided whether the platform needs
foreground/background task semantics in the persisted task model.

Candidate task field:

```text
ExecutionSpec.foreground: boolean
```

Candidate worker field:

```text
Worker.maxConcurrentWork: int
```

These are model/API/storage changes and must be treated as such.

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
boolean tryReserveCapacity(String taskId, String workerId, boolean foreground, int capacityUnits);
void confirmReservation(String taskId, String workerId, int capacityUnits);
void releaseReservation(String taskId, String workerId, String reason);
```

### Scope

- Add reservation accounting.
- Integrate reservation with matching acceptance.
- Integrate confirmation with runtime claim.
- Integrate release with all dispatch failure and task-status failure paths.
- Update trace attrs for reservation, confirmation, and release evidence.

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

### Proposed model

Candidate task field:

```text
ExecutionSpec.maxConcurrentWorkers: int
```

`0` means use policy defaults.

Candidate policy:

```java
interface WorkerBudgetPolicy {
    int resolveMaxConcurrentWorkers(Task task, int desiredCount);
}
```

Default direction:

- INTERACTIVE: cap desired count to a small default.
- BULK: cap desired count to a larger but bounded default.
- explicit `ExecutionSpec.maxConcurrentWorkers` overrides policy default.

### Integration point

`DefaultAssignmentAllocationPolicy.plan(...)` should:

- compute raw desired worker count
- read current active worker count for the task from `WorkerLoadView` or request
  inputs
- apply budget ceiling
- produce final `desiredDispatchWorkerCount`, `requestedMatchCount`, and
  `dispatchCandidateLimit`

`TaskWorkerAssignListener` should only pass inputs and emit trace from the plan.

### Trace

Add assignment summary attrs if needed:

- `workerBudget`
- `currentTaskWorkerCount`
- `budgetLimited`

### Verification

- Unit: budget policy caps large BULK desired worker count.
- Unit: INTERACTIVE still dispatches while BULK is active.
- Engine mixed workload contention test.
- Server representative E2E.
- Trace scenario: `cross-task-worker-fairness`.

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
| `capacity-reservation-under-concurrency` | Step 7 | Capacity is not over-committed across concurrent assignment waves |
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
4. Should `foreground/background` be a persisted task field, a runtime profile
   derivation, or both?
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

Do not start with full WorkerContext deletion.

The next recommended implementation step is capacity reservation and the
foreground/background model decision. That step should not begin until the
team is ready to make a behavior-level capacity commitment; the current ranker
still uses the existing worker lock as the dispatch guard.

Full WorkerContext deletion should wait until runtime binding, trace analyzers,
and server routing E2E no longer need context-specific fields as proof.
