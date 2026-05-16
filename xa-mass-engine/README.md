# xa-mass-engine

Status: current engine owner README.

This module owns kernel orchestration semantics: lifecycle, matching,
assignment, result handling, and terminal convergence. It does not own runtime
implementation modules or storage implementations.

For current test-layer truth, minimum verification, and CI gate truth, start
with [`../doc/TESTING_INDEX.md`](../doc/TESTING_INDEX.md). This README only
covers engine-owned assets and when to use them.

## Role

`engine` is a runtime kernel, not a CRUD backend module.

- task lifecycle transitions and terminal convergence
- task-level worker matching and assignment orchestration
- result ingest application and retry/finality decisions
- engine-local policy ownership across matching, assignment, attempt, release,
  refill, intake, and terminal decisions

Assignment allocation is engine-internal policy ownership:

- `AssignmentAllocationPolicy` owns this round's allocation decision: requested
  match count, minimum-worker start gate, and the matched worker candidates that
  may enter dispatch.
- `TaskWorkerAssignListener` owns orchestration around that decision: invoking
  matching, unlocking skipped/surplus workers, task status transition, assignment
  trace, task update, and assignment event publication.
- `SimpleTaskDispatchBinder` owns runtime claim and dispatch binding only; it is
  not the assignment allocation policy owner.

## Fixed Scheduling Mainlines

These are the current engine scheduling mainlines. Treat them as owner
boundaries, not as a promise that the current package layout is final.

```text
Assignment signal admission
  -> TaskAssignWorker

Task-level assignment orchestration
  -> TaskWorkerAssignListener
  -> AssignmentAllocationPolicy
  -> matching/acquisition path
  -> SimpleTaskDispatchBinder

Worker scheduling read model
  -> WorkerSchedulingCandidateEnumerator
  -> WorkerSchedulingView
  -> WorkerMatchContext

Eligibility and preference
  -> worker prefilter + QLExpress rules
  -> WorkerCandidateRanker

Allocation and budget
  -> AssignmentAllocationPolicy
  -> WorkerBudgetPolicy

Resource usage and cleanup
  -> WorkerDispatchResourcePolicy
  -> WorkerDispatchResourceReleaser
  -> AssignmentRefillPolicy

Runtime and result truth
  -> TaskWorkRuntime
  -> TaskResultRuntime
```

The current `RuleBasedTaskWorkerMatchingStrategy` still combines rule-based
eligibility, ranking, capacity reservation, and optional worker-lock acquisition.
That is the current acquisition path, not a pure policy seam. Do not add more
mechanism work to it casually; if future distributed capacity or worker-manager
separation requires a split, introduce a real acquisition owner rather than a
pass-through wrapper.

WorkerContext is not scheduling truth in the engine hot path. Remaining
`workerContextId` fields are compatibility/runtime/trace residue until the
lower-level contracts are retired.

## Scheduling Core Test Intent

Read this before interpreting engine tests.

- `engine` is the primary proof surface for scheduling correctness
- `TaskSchedulingTestHarness` is a test substrate for the scheduling matrix, not
  a second implementation world
- keep these tests local when the real question is:
  - which worker scheduling candidate is eligible
  - whether contention produces a conflict, retry, or redispatch
  - whether a task re-enters competition correctly after delay, retry, or lease expiry
  - whether contract-aware scheduling/finality rules stay correct
- move proof upward when the real question is:
  - host HTTP wiring
  - adapter/language parity
  - disconnect/reconnect/replay under real runtime edges
- do not add new engine tests that use compatibility projection as immediate
  runtime truth

## Start Here

Start with these classes before changing behavior:

- `src/main/java/com/xa/mass/engine/TaskManager.java`
- `src/main/java/com/xa/mass/engine/TaskConcurrencyStrategy.java` (interface) / `LocalTaskConcurrencyCoordinator.java` (default impl)
- `src/main/java/com/xa/mass/engine/TaskCommandService.java`
- `src/main/java/com/xa/mass/engine/TaskQueryService.java`
- `src/main/java/com/xa/mass/engine/TaskProjectionStateAuditor.java`
  only when you are intentionally working on explicit full-scan compatibility
  projection diagnostics
- `src/test/java/com/xa/mass/engine/TaskCompatibilityProjectionAccess.java`
  only when you are intentionally working on test-only bounded projection
  overlays or residue audit helpers
- `src/main/java/com/xa/mass/engine/WorkerManager.java`
- `src/main/java/com/xa/mass/engine/rules/RuleManager.java`

Runtime-facing glue should prefer narrow engine ports and facades such as:

- `TaskResultIngestFacade`
- `TaskAssignmentRuntimePort`
- `TaskRuntimeMaintenancePort`
- `TaskRuntimeRecoveryPort`
- `TaskEventListenerRegistrar`
- `TaskEventService`

Keep these seams only when they carry a real cross-module or runtime boundary.
Internal orchestrator size alone is not a refactor trigger.

Do not default new cross-module callers to the full `TaskManager` facade.
When a seam is transport-neutral and cross-module by nature, prefer a small
shared runtime contract in a neutral module over making transport depend on
engine-internal listener packages.

## Mainline Truth

Keep these facts fixed unless the owning global baselines change:

- kernel truth is the explicit triad:
  - `Task.contract`
  - `Task.intakeStatus`
  - `TaskWorkRuntime`
- task classification is now two-axis, not ingress-shaped:
  - `Task.contract`: kernel lifecycle/dispatch/terminal contract
  - `Task.workloadClass`: runtime tuning intent only
  - ingress form such as inline append, file import, or streaming source is not a persisted kernel type axis
- current mainstream combinations are:
  - `SESSION + INTERACTIVE`
  - `BATCH + BULK`
- `sealTask(...)` is a contract-neutral intake close action:
  `OPEN -> SEALED` applies to both `SESSION` and `BATCH`, but only `BATCH`
  uses `SEALED + all final` as an automatic terminal condition
- `BATCH` lease expiry is attempt loss, not a stable per-item timeout contract:
  runtime retry budget decides `retry reset` vs `FAILED + RETRY_EXHAUSTED`
- `Task.workloadClass` is the explicit workload tuning input; scheduling
  semantics must not drift back into free-form `sharedConfig`
- worker matching is task-level orchestration; do not fall back to per-message
  matching on the hot path
- fixed scheduling mainlines are documented in
  `SCHEDULING_KERNEL_GUARDRAILS.md`; scheduling changes must preserve those
  owner boundaries or update the owning baseline in the same change
- `AssignmentAllocationPolicy` owns allocation shape for a task-level assignment
  attempt; `TaskWorkerAssignListener` keeps cross-aggregate orchestration and
  trace ownership around that policy decision
- `TaskManager` is the engine orchestration entry, not the place to keep raw
  lock bookkeeping or compatibility CRUD owner behavior
- `TaskManager` remains the engine-internal orchestration facade and
  composition root; cross-module callers should not treat it as the default
  engine API
- `TaskConcurrencyStrategy` / `LocalTaskConcurrencyCoordinator` owns task/message locking plus coalesced progress
  reconciliation
- `TaskManager` now reaches `TaskWorkRuntime` directly for enqueue, claim,
  lease, retry, and result application; do not reintroduce a pass-through
  bridge unless a real protocol boundary appears
- `TaskCommandPort` and `TaskQueryPort` are the narrow backing seams for the
  shell-facing command/query services; keep those services off raw
  `TaskManager` growth
- `TaskResultIngestPort` is the narrow backing seam for transport-facing
  result ingress; keep callback acceptance off the raw `TaskManager` facade
- `TaskAssignWorker` owns session/interactive assignment-signal admission;
  queue-full pressure must converge through internal retry/defer behavior
  instead of silently dropping `READY` / redispatch signals
- `TaskAssignWorker` orders assignment signals by `DispatchPriority` inside
  each existing lane; `DispatchLane` remains the primary isolation boundary and
  same-priority signals remain FIFO
- `WorkerCandidateRanker` orders rule-passed scheduling candidates before lock
  acquisition; rules remain the eligibility gate, and the default ranker uses
  observed load plus routing affinity as preference signals
- `WorkerLoadView` owns process-local load and reservation accounting:
  matching reserves one unit of worker-declared capacity before lock
  acquisition, and dispatch binding confirms or releases that reservation
  around runtime claim outcomes
- `WorkerBudgetPolicy` is the internal owner for task-level worker budget
  decisions consumed by `AssignmentAllocationPolicy`; the current default uses
  conservative workload-class caps without exposing a public scheduling option,
  and emits budget evidence in assignment trace
- `AssignmentRefillPolicy` owns whether a released worker slot should trigger
  another assignment attempt; `TaskResourceReleaseListener` releases resources
  and consumes that decision instead of owning refill formulas
- `WorkerDispatchResourcePolicy` owns dispatch resource usage semantics:
  whether a task/candidate uses the long-lived worker-level exclusive lock.
  Matching, assignment listener cleanup, binder compensation, and resource
  release consume this decision instead of each re-deriving foreground
  behavior. WorkerContext identity is no longer a resource policy input; only
  `foreground=true` keeps the long-lived lock, while `foreground=false` relies
  on capacity reservation.
- `WorkerDispatchResourceReleaser` owns the repeated dispatch cleanup mechanism:
  releasing worker reservations, conditionally unlocking exclusive worker
  locks, and emitting `WORKER_LOCK_RELEASED` trace for assignment cleanup and
  binder compensation paths, dispatch-submit failure retry compensation, plus
  release-listener attempt/terminal close lock-release paths. Candidate cleanup
  paths resolve lock release through `usageForCandidate(...)`; attempt cleanup
  paths resolve it through `usageForAttempt(...)`.
- `EngineSchedulingCoreArchitectureGuardTest` is an executable owner-boundary
  guard for the scheduling kernel. It keeps scheduling-core tests off
  compatibility projection proof helpers, prevents listener/binder
  orchestration from calling dispatch cleanup primitives directly, prevents
  WorkerContext runtime state mutation from returning to the engine mainline,
  and prevents the retired context-first matching handoff types from returning.
- `ExecutionSpec.foreground` is currently a scheduling-mode declaration carried
  through task model/API/trace surfaces; `foreground=true` is the default
  exclusive worker-lock path, while `foreground=false` skips the long-lived
  worker lock and relies on capacity reservation for stateless workers
- worker match trace rows include reservation-time load snapshots so canonical
  assignment trace can prove the current process-local capacity guard
- `TaskWorkRuntime` owns ready work, active lease, retry scheduling, expiry, and
  queue/backpressure truth
- `TaskResultRuntime` owns stable-final public result rows, task-local result
  sequence, staged callback repair anchors, and result-side event/progress
  barriers
- batch/bulk redispatch is runtime-driven from `TaskWorkRuntime.readyTaskIds`
  through starter-owned recovery/pump wiring; task-signal queues are not the
  only batch redispatch owner anymore
- bounded work/message compatibility residue is not the hot-path runtime
  owner
- `TaskQueryService` is the default task aggregate/state query surface; do not
  grow message/attempt residue reads back into it
- `TaskStateValidator` owns runtime aggregate validation only; scan-heavy
  compatibility projection audit belongs to `TaskProjectionStateAuditor`
- `TaskWorkProjectionState` is the engine-owned work/attempt residue
  state owner; do not let storage-edge projection enums leak back into
  runtime/result/trace code as native engine state
- `TaskCompatibilityProjectionStore` is the engine-internal storage-edge owner
  for bounded projection residue; `TaskManager` and result/dispatch hot paths
  should not each reconstruct `TaskDetailStore` records on their own
- `TaskDetailStore.TaskMessageProjection` and
  `TaskDetailStore.TaskMessageAttemptProjection` are storage-edge residue
  shapes; production engine services should translate them inside the
  engine boundary instead of returning them as engine-facing API results
- public result reads must use `TaskResultRuntime`; projection residue must not
  source `/results`, SDK result query, or archive generation
- runtime ingest must stay correct when compatibility message-projection writes
  fail or lag; enqueue truth lives in `TaskWorkRuntime`, and projection writes
  are best-effort residue
- assignment diagnostics are append-only bounded residue; matching and dispatch
  mainline should depend on a write-only recorder, not on report/history APIs
- dispatch submit failure after claim/attempt creation must compensate inline
  through runtime retry re-entry plus projection reset; lease expiry repair is a
  fallback, not the mainline
- engine-provided message reads are compatibility helpers, not the future
  business-detail query model
- cross-module message reads should stay explicit about intent:
  projection-style reads for bounded logical message views and audit-style
  reads for attempt timelines
- cross-module callers that only need worker registration lookup should depend
  on storage lookup contracts rather than carrying `WorkerManager`
- cross-module callers that only need rule definition/evaluator registration
  should depend on `RuleStorage`; keep `RuleManager` scoped to engine matching
  and rule-evaluation orchestration

Repo-level mainline surfaces:

- shell/admin mutation flows use `TaskCommandService`
- bounded inspection flows use `TaskQueryService`
- production engine mainline does not carry a compatibility projection query
  owner; bounded residue overlays stay in test/internal harnesses only
- transport/runtime result ingress uses `TaskResultIngestFacade`
- dispatch-ready bindings and result-ingest seams used across engine, SDK,
  transport runtime, and tests now live in shared base runtime contracts rather
  than engine-owned package paths
- process-local EventBus forwarding is optional shell wiring outside the engine
  kernel; default engine startup should not imply a distributed event
  propagation contract
- engine emits dispatch-ready bindings into a neutral handoff/listener seam; it
  must not grow a direct dependency on transport routing/runtime classes, and
  transport-side consumption now happens through the batch handoff seam rather
  than a direct dispatch-listener callback
- transport reachability is read through `WorkerReachabilityView`; dispatch
  eligibility online truth belongs to transport presence rather than engine
  heartbeat folding on `Worker.status`
- task-create input consumed by `TaskCommandService` now lives in the neutral
  base model layer; cross-module create flows should not import engine-owned
  DTO packages just to submit tasks
- listeners, watchdogs, and startup recovery should depend on narrow ports, not
  on `TaskManager` plus reach-through getters

Infra ownership:

- storage contracts live in `../platform_infra/mass-storage-api`
- runtime queue/lease contracts live in `../platform_infra/mass-runtime-api`
- transport adapter contracts live outside engine; engine must not take a direct
  dependency on `../transport/transport_api`
- SDK/server bootstrap owns concrete wiring
- primary SDK/server builders should wire `TaskStorage`, `TaskDetailStore`,
  `TaskWorkRuntime`, `WorkerStorage`, and `RuleStorage` rather than
  constructing `TaskManager` / `WorkerManager` in outer modules
- starter assembly should treat `WorkerManager` and `RuleManager` as derived
  helpers over storage contracts, not as parallel config truth carried beside
  `WorkerStorage` / `RuleStorage`

## Rule-Matching Surface

Matching enumerates `WorkerSchedulingCandidate` values and evaluates their
`WorkerSchedulingView` through `WorkerMatchContext` and QLExpress rules.

Current owner types:

- `src/main/java/com/xa/mass/engine/model/WorkerSchedulingCandidate.java`
- `src/main/java/com/xa/mass/engine/model/WorkerSchedulingView.java`
- `src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`
- `src/main/java/com/xa/mass/engine/strategy/WorkerSchedulingCandidateEnumerator.java`
- `src/main/java/com/xa/mass/engine/load/WorkerLoadView.java`
- `src/main/java/com/xa/mass/engine/rules/RuleConfig.java`

Current default rule set:

- `basic_worker_check`
- `worker_scheduling_resource_check`
- `routing_code_match`
- `worker_capability_check`
- `worker_load_check`

Matching boundaries:

- `WorkerSchedulingCandidate` is the engine-internal handoff between matching,
  allocation, listener orchestration, and dispatch binding
- `WorkerSchedulingCandidateEnumerator` creates one scheduling candidate per
  worker from the worker read model; matching no longer expands legacy
  `WorkerContext` storage into candidates
- worker-level assignment diagnostics consume `WorkerSchedulingCandidate`;
  candidate handoff no longer carries a nullable `WorkerContext` payload
- `WorkerMatchContext` owns the rule and diagnostic snapshot field map;
  `RuleBasedTaskWorkerMatchingStrategy` consumes that read model for prefilter
  records instead of maintaining a duplicate snapshot builder
- assignment records snapshot `WorkerSchedulingView` evidence through
  `WorkerSchedulingSnapshot`; `workerContextId` is legacy payload identity, not
  the diagnostic subject
- `WorkerSchedulingView` is the scheduling read surface; new matching code
  should read the view rather than treating `WorkerContext` as the matching
  subject
- `WorkerLoadView` is a push-updated read view and process-local reservation
  owner sourced from runtime claim/final lifecycle callbacks plus matching
  reservation handoff
- `Worker.status` and worker lock state are typed truth, not attributes
- `Worker.status` is control-plane lifecycle truth, not transport reachability
- dispatch eligibility must read transport reachability from
  `WorkerReachabilityView`, not local heartbeat-expiry heuristics
- `workerSchedulingAttributes` is the preferred matching label map for new or
  migrated rules; legacy `workerContextAttributes` is retired from the engine
  scheduling rule context
- default rules must use `workerScheduling*` / `isWorkerScheduling*` variables;
  legacy `workerContext*` variables are no longer part of the engine rule
  surface
- worker load variables such as `workerActiveLeaseCount`,
  `workerReservedCount`, and `workerEstimatedLoadRatio` are scheduling
  evidence; current capacity semantics are worker-declared process-local
  reservation plus the existing worker lock, not distributed capacity
  correctness or shared background execution
- routing is a task-owned hint currently resolved from
  `Task.sharedConfig["routingCode"]`
- once a task requires routing, the candidate must expose matching
  `workerSchedulingRoutingTags`; in the current scheduling hot path those tags
  come from worker attributes such as `routingTag` / `routingTags`

If matching semantics change, update `RuleConfig`, `WorkerMatchContext`, and the
relevant routing/integration coverage together.

## Acceptance Focus

Core acceptance for this module stays:

- `scheduling correctness`: engine-owned proof for matching, contention,
  redispatch, gating, and contract-aware convergence
- `concurrency`: engine-owned correctness under callback/expiry/retry/release
  races
- `perf`: mainly in `xa-mass-testing`, but engine changes must preserve the
  task-level, queue-first runtime shape
- `Boot-shell E2E`: mainly in `xa-mass-server`, used to verify representative
  lifecycle and assignment wiring end to end

What engine tests prove:

- the platform's primary scheduling-value matrix
- kernel lifecycle, retry, expiry, release, and convergence invariants
- race-sensitive behavior that is cheaper to make deterministic inside the
  engine boundary

What engine tests do not replace:

- `project / submitter / worker` host-boundary proof
- transport adapter routing and result-ingest boundary proof
- Boot-shell E2E for the real host wiring

Useful starting tests:

- `TaskKernelLifecycleTest`
- `EngineSchedulingCoreArchitectureGuardTest`
- `TaskContractTerminalBehaviorTest`
- `TaskContractSchedulingBehaviorTest`
- `TaskSchedulingContentionTest`
- `TaskWorkerEligibilityTest`
- `TaskRedispatchCompetitionTest`
- `TaskSchedulingGateAndTargetingTest`
- `TaskDelayedAvailabilitySchedulingTest`
- `TaskRuntimeRecoveryPortTest`
- `WorkerManagerTest`
- `TaskResourceReleaseListenerTest`
- `TaskAssignWorkerTest`
- `TaskWorkerAssignListenerTest`
- `RuleBasedTaskWorkerMatchingStrategyTest`
- `WorkerMatchContextTest`

Current scheduling-matrix scenarios include:

- multi-task contention on worker resources and across a worker pool
- worker eligibility rejection for unreachable, locked, capacity-exhausted,
  routing-mismatch, and target-attribute mismatch candidates
- active contention after transport reachability drops, with backup-worker dispatch
- reachability-aware minimum-worker gates that avoid half-dispatch when an eligible worker drops
- retry expiry re-entering the competition pool when retry budget remains
- retry-exhausted batch finality releasing resources for waiting work
- delayed worker availability moving READY work into dispatch
- paused waiting tasks staying out of competition until explicit resume
- `BATCH` drain-to-terminal and `SESSION` queue-drain without auto-terminal
- minimum-worker gate, target worker id, and targeted worker attributes under contention
- executable source guards that fail if scheduling-core mainline tests use
  compatibility projection proof helpers, listener/binder orchestration
  bypasses dispatch resource cleanup owners, WorkerContext state mutation leaks
  outside its transitional lifecycle owner, or retired context-first matching
  handoff types return

Explicit secondary residue/audit tests:

- `EngineProjectionResidueSuite`
  - `TaskManagerLifecycleTest`
  - `TaskConcurrencyAcceptanceTest`
  - `SimpleTaskDispatchBinderTest`
- `EngineProjectionAuditSuite`
  - `TaskStateValidatorBoundaryTest`

## Read Map

Engine-local owner docs:

- [`SCHEDULING_KERNEL_GUARDRAILS.md`](./SCHEDULING_KERNEL_GUARDRAILS.md):
  short kernel guardrails for policy-vs-mechanism separation and future
  scheduling evolution
- [`POLICY_INTERACTION_BASELINE.md`](./POLICY_INTERACTION_BASELINE.md):
  current policy ownership and precedence
- [`RUNTIME_BOUNDARY_BASELINE.md`](./RUNTIME_BOUNDARY_BASELINE.md):
  current runtime cutover, recovery, and truth-layer boundary
- [`STORAGE_BASELINE.md`](./STORAGE_BASELINE.md):
  current engine-facing storage/runtime boundary
- [`TASK_RUNTIME_PROFILE_DESIGN.md`](./TASK_RUNTIME_PROFILE_DESIGN.md):
  design/refactor note for the remaining workload-profile evolution only
- [`SCHEDULING_UPGRADE_ROADMAP.md`](./SCHEDULING_UPGRADE_ROADMAP.md):
  proposed long-range scheduling upgrade roadmap; planning material only, not
  implemented baseline behavior
- [`WORKER_SCHEDULING_VIEW_BASELINE.md`](./WORKER_SCHEDULING_VIEW_BASELINE.md):
  current transitional baseline for WorkerContext hot-path convergence
- [`WORKER_CONTEXT_RETIREMENT_PLAN.md`](./WORKER_CONTEXT_RETIREMENT_PLAN.md):
  proposed plan for retiring WorkerContext from the scheduling kernel; planning
  material only, not implemented baseline behavior
- [`WORKER_MANAGER_SPLIT_ROADMAP.md`](./WORKER_MANAGER_SPLIT_ROADMAP.md):
  proposed next roadmap for moving worker registration, capability, lifecycle
  administration, and worker command/state-report ownership out of the engine
  kernel while keeping the first split in-process through SDK/server wiring.
  Direction material only.
- [`UNIFIED_EVENT_ENVELOPE_ROADMAP.md`](./UNIFIED_EVENT_ENVELOPE_ROADMAP.md):
  separate north-star roadmap for future event envelope metadata, priority,
  response, convergence, target-scope, queue-placement, and task-stage
  direction. It is not implemented baseline behavior and must not preempt the
  worker-manager split.

Global baselines:

- [`../AGENTS.md`](../AGENTS.md)
- [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
- [`../doc/STATE_MACHINE_BASELINE.md`](../doc/STATE_MACHINE_BASELINE.md)
- [`../doc/TRACE_CONTRACT.md`](../doc/TRACE_CONTRACT.md)
- [`../doc/TESTING_INDEX.md`](../doc/TESTING_INDEX.md)
- [`../doc/TESTING_BASELINE.md`](../doc/TESTING_BASELINE.md)
