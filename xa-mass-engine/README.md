# xa-mass-engine

Status: current engine owner README.

This module owns kernel orchestration semantics: lifecycle, worker selection,
assignment, result handling, and terminal convergence. It does not own runtime
implementation modules or storage implementations.

For current test-layer truth, minimum verification, and CI gate truth, start
with [`../doc/TESTING_INDEX.md`](../doc/TESTING_INDEX.md). This README only
covers engine-owned assets and when to use them.

## Role

`engine` is a runtime kernel, not a CRUD backend module.

- task lifecycle transitions and terminal convergence
- task-level worker selection and assignment orchestration
- result ingest application and retry/finality decisions
- engine-local policy ownership across worker selection, assignment, attempt, release,
  refill, intake, and terminal decisions

Assignment allocation is engine-internal policy ownership:

- `AssignmentAllocationPolicy` owns this round's allocation decision: requested
  selection count, minimum-worker start gate, and the selected worker handles that
  may enter dispatch.
- `TaskWorkerAssignListener` owns orchestration around that decision: invoking
  worker-runtime selection, unlocking skipped/surplus workers, task status
  transition, assignment trace, task update, and assignment event publication.
- `SimpleTaskDispatchBinder` owns runtime claim and dispatch binding only; it is
  not the assignment allocation policy owner.

Task item dispatch output:

- `xa-mass-task-runtime` owns accepted ready backlog, claim, active lease,
  retry/finality, progress, and final-result rows before/after dispatch.
- Scheduling Plane / engine worker selection owns the concrete worker decision.
  Once selected, `TaskDispatchBinding.workerId()` is the assigned execution
  identity for the item.
- Engine/starter assembly may resolve transport metadata needed to hand the
  already assigned item to transport, but transport does not get to select a
  replacement worker. If delivery to the selected worker is infeasible, retry or
  compensation remains engine-owned.
- `routeKey` is not an engine worker-selection result. It is opaque
  connection/domain metadata used by transport and assembly. Do not mint worker
  identity into routeKey to compensate for a missing selected-worker delivery
  constraint.
- `deliveryQueueKey` is transport queue partitioning. Engine code must not use
  it as scheduling, admission, or lifecycle truth.

## Fixed Scheduling Mainlines

These are the current engine scheduling mainlines. Treat them as owner
boundaries, not as a promise that the current package layout is final.

```text
Assignment signal admission
  -> TaskAssignWorker

Task-level assignment orchestration
  -> TaskWorkerAssignListener
  -> AssignmentAllocationPolicy
  -> WorkerSelectionRuntime.selectAndReserve(...)
  -> SimpleTaskDispatchBinder

Worker-side runtime selection
  -> WorkerSelectionRequest
  -> SelectedWorkerHandle
  -> worker-runtime candidate/evidence/admission internals

Allocation and budget
  -> AssignmentAllocationPolicy
  -> WorkerBudgetPolicy

Resource usage and cleanup
  -> WorkerDispatchResourcePolicy
  -> WorkerDispatchResourceReleaser
  -> AssignmentRefillPolicy

Runtime and result truth
  -> TaskRuntimeServingLane
  -> xa-mass-task-runtime ports
```

Owner-backed worker-control and stage entry surfaces:

- `WorkerControlService` is the engine caller surface for worker command
  request/ack read views, worker capability self-report, and bounded worker
  state projection. It emits canonical trace and delegates truth to
  `WorkerCommandLifecycleOwner`, `WorkerCapabilityAuthority` via
  `WorkerManager`, and `WorkerStateProjectionOwner`. Any translation from
  worker-control truth into scheduling dispatch eligibility belongs to the
  worker-runtime-owned `WorkerDispatchEligibilityRuntime`, not to transport
  shells, engine-control policy, or matching mainline code.
- `TaskStageEvidenceService` is the engine caller surface for task item stage
  evidence and bounded stage projection. It emits canonical trace and delegates
  stage truth to `TaskStageEvidenceOwner`.
- Event handlers parse kernel-targeted event payloads and call these services.
  SDK/server integration should call the services through SDK-facing request
  models, not reach into event handlers or mutate owner internals directly.

Current default runtime worker selection is delegated to
`WorkerSelectionRuntime`. Engine resolves task-side policy intent and requested
worker count, then consumes selected worker handles. Worker-runtime owns
worker-fact predicate composition, ranking mechanics, reservation, and selected
worker accounting behind that minimal contract. Future selection work may add
worker intrinsic metrics, task-type affinity, historical performance, or
domain-specific scoring as worker-runtime selection mechanics or explicit
task-side policy intent; do not reintroduce engine-visible worker metadata as a
shortcut.

Policy abstraction boundary:

- Scheduling Plane ownership is split into three first-class owners:
  `TaskSchedulingPolicy`, `WorkerSchedulingPolicy`, and
  `RuntimeWorkerSelection`. Project / workload binding chooses allowed/default
  task and worker policies plus scoped configuration. Current engine-facing
  value contracts exist for `TaskDispatchIntent`,
  `ResolvedTaskSchedulingPolicy`, and `ResolvedWorkerSchedulingPolicy`; a
  catalog/binding/configurable policy path is not implemented yet.
- current implementation: policy remains distributed across task runtime
  profile, explicit group selectors, assignment allocation, runtime
  backpressure, worker-runtime selection, and admission behavior.
- task-level dispatch intent narrows the target: project, `workerGroupId(s)` or
  selector, `routingCode`, route attributes, optional `targetWorkerId`, and
  optional constrained target worker attributes.
- WorkerGroup owns capability boundary. Worker rows are runtime execution slots
  and evidence carriers, not project/event capability truth.
- item `eventCode` is handler/capability identity. It validates against the
  selected WorkerGroup event binding and tells the worker which local handler to
  invoke. It must not be interpreted as a worker selector or as a reason to
  scan all workers.

WorkerContext is not scheduling truth in the engine hot path. Runtime,
transport, projection, SDK/API, server payloads, and canonical trace identity
are worker-level.

Current WorkerGroup / group-selector scheduling baseline:

- WorkerGroup candidate-source convergence is closed; ordinary scheduling uses
  explicit task `workerGroupId` / `workerGroupIds` selectors as worker-universe
  intent before asking worker-runtime for selected handles
- low-level worker registry primitives live in
  `platform_infra/mass-runtime-api`; resource contracts and higher-level worker
  runtime contracts live in `xa-mass-worker-runtime`
- `EventKey` remains a low-level project-scoped worker capability key.
  `EventBinding`, `WorkerGroupRecord`, and resource DTOs are worker-runtime
  resource contracts; `WorkerRegistrySnapshot` is worker-runtime package-local
  implementation evidence, not an engine public surface
- `WorkerSelectionRuntime` consumes explicit group selectors and narrows
  `workerGroupId(s) -> worker-runtime selection -> SelectedWorkerHandle`.
  Engine does not acquire candidate rows, join scheduling views, or rank workers
  directly.
- `WorkerManager` lives in `xa-mass-worker-runtime` as private runtime assembly.
  SDK/server registration crosses narrow worker-runtime declaration and query
  ports; accepted worker declarations refresh derived registry projections.
- `targetWorkerId` is only a debug/manual narrowing shortcut inside an
  explicit group selector; it cannot bypass group, reachability, dispatch gate,
  load, lock, or rule checks
- event-code-only and project-only tasks do not match workers in the kernel;
  SDK/intake may resolve event metadata to `workerGroupId(s)` before assignment
  by validating project/event bindings against WorkerGroup capability
- `WorkerRegistrySnapshot` may retain WorkerGroup `EventBinding` read caches
  for catalog/report-ceiling flows, but event bindings are not the scheduling
  candidate-source key
- Worker-runtime materializes WorkerGroup capability evidence from
  `WorkerGroupRecord`; group-level capability remains the boundary for
  supported event handlers and projects. AdapterNode/NodeGroupBinding metadata
  is topology/admin evidence, not a scheduling selector or worker-registration
  prerequisite.
- the old unused `WorkerSelector` / `DefaultWorkerSelector` path is removed so
  worker selection has one active mainline: task-side intent ->
  worker-runtime selected handles -> allocation/bind/release
- canonical assignment trace and diagnostics may include `workerGroupId` and
  bounded selection-summary counts. They must not reconstruct worker scheduling
  views or candidate rows in engine code.

## Scheduling Core Test Intent

Read this before interpreting engine tests.

- `engine` is the primary proof surface for scheduling correctness
- scheduling correctness is organized by invariant in
  [`SCHEDULING_CORRECTNESS_MATRIX.md`](./doc/baseline/SCHEDULING_CORRECTNESS_MATRIX.md);
  use that matrix before adding another scheduling test class
- project-level authoritative-vs-representative proof ownership lives in
  [../doc/PROOF_REGISTRY.md](../doc/PROOF_REGISTRY.md); use it when the
  question is which engine proof class is primary and which integrated trace
  scenario is only representative
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

## Kernel Convergence Test Intent

- lifecycle/result convergence is organized by invariant in
  [`KERNEL_CONVERGENCE_MATRIX.md`](./doc/baseline/KERNEL_CONVERGENCE_MATRIX.md)
- project-level proof pairing lives in
  [../doc/PROOF_REGISTRY.md](../doc/PROOF_REGISTRY.md)
- `EngineKernelConvergenceSuite` is the runtime-first gate for deterministic
  lifecycle and convergence facts
- legacy projection helper classes and secondary support suites have been
  retired; current convergence proof should stay runtime/result-first
- do not recreate projection-aware mixed classes to recover old assertions

## Start Here

Start with these classes before changing behavior:

- `src/main/java/com/xa/mass/engine/TaskManager.java`
- `src/main/java/com/xa/mass/engine/TaskConcurrencyStrategy.java` (interface) / `LocalTaskConcurrencyCoordinator.java` (default impl)
- `src/main/java/com/xa/mass/engine/TaskCommandService.java`
- `src/main/java/com/xa/mass/engine/TaskQueryService.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerManager.java`
  only when you are intentionally working on worker-runtime owner assembly
- `src/main/java/com/xa/mass/engine/rules/MatchingRuleSetProvider.java`
- `src/main/java/com/xa/mass/engine/rules/MatchingRuleEvaluator.java`

Runtime-facing glue should prefer narrow engine ports and facades such as:

- `TaskResultIngestFacade`
- `TaskAssignmentRuntimePort`
- `TaskLeaseMaintenancePort`
- `TaskDispatchWakeupPort`
- `TaskShellLifecycleMaintenancePort`
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
  - `xa-mass-task-runtime`
- task classification is now explicit-policy driven, not ingress-shaped:
  - `Task.contract`: current public/runtime preset input
  - `Task.workloadClass`: runtime tuning input and read evidence
  - `ResolvedTaskSchedulingPolicy`: engine-consumed terminal, dispatch cadence,
    retry/finality, claim/backpressure, and resource-mode inputs
  - ingress form such as inline append, file import, or streaming source is not a persisted kernel type axis
- current mainstream combinations are:
  - `SESSION + INTERACTIVE`
  - `BATCH + BULK`
- `sealTask(...)` is a contract-neutral intake close action:
  `OPEN -> SEALED` applies to both `SESSION` and `BATCH`; automatic all-final
  terminal closure is driven by resolved idle-close policy
- batch-style lease expiry is attempt loss, not a stable per-item timeout contract:
  runtime retry budget decides `retry reset` vs `FAILED + RETRY_EXHAUSTED`
- `Task.workloadClass` is a workload tuning input; scheduling semantics must
  consume resolved task policy and must not drift back into free-form
  `sharedConfig`
- worker selection is task-level orchestration; do not fall back to per-message
  matching on the hot path
- fixed scheduling mainlines are documented in
  `doc/baseline/SCHEDULING_KERNEL_BASELINE.md`; scheduling changes must preserve those
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
- `TaskManager` reaches task item runtime through `TaskRuntimeServingLane` and
  `xa-mass-task-runtime` ports for append, scheduler discovery, claim, lease,
  retry/finality, final-result reads, and progress; do not reintroduce old
  infra runtime stores or pass-through bridges
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
- `WorkerSelectionRuntime` owns worker-fact eligibility and ordering before
  selected handles are returned; engine does not rank worker rows or worker
  scheduling views.
- `WorkerRegistry` owns worker slot capacity, reservation, active lease, and
  exclusive execution-lane evidence. Worker selection reserves one unit of
  worker-declared capacity before exclusive lease acquisition, and dispatch
  binding confirms or releases that reservation around runtime claim outcomes.
- `WorkerBudgetPolicy` is the internal owner for task-level worker budget
  decisions consumed by `AssignmentAllocationPolicy`; the current default uses
  conservative workload-class caps without exposing a public scheduling option,
  and emits budget evidence in assignment trace
- `AssignmentRefillPolicy` owns whether a released worker slot should trigger
  another assignment attempt; `TaskResourceReleaseListener` releases resources
  and consumes that decision instead of owning refill formulas
- `WorkerDispatchResourcePolicy` owns dispatch resource usage semantics:
  whether a task/selected worker uses the long-lived worker-level exclusive lock.
  Worker selection, assignment listener cleanup, binder compensation, and
  resource release consume this decision instead of each re-deriving foreground
  behavior. WorkerContext identity is no longer a resource policy input; only
  resolved `WorkerResourceMode.EXCLUSIVE` keeps the long-lived lock, while
  `WorkerResourceMode.CAPACITY` relies on capacity reservation.
- `WorkerDispatchResourceReleaser` owns the repeated dispatch cleanup mechanism:
  releasing worker reservations, conditionally unlocking exclusive worker
  locks, and emitting `WORKER_LOCK_RELEASED` trace for assignment cleanup and
  binder compensation paths, dispatch-submit failure retry compensation, plus
  release-listener attempt/terminal close lock-release paths. Candidate cleanup
  paths resolve lock release through `usageForCandidate(...)`; attempt cleanup
  paths resolve it through `usageForAttempt(...)`.
- `EngineSchedulingCoreArchitectureGuardTest` is an executable owner-boundary
  residue guard for the scheduling kernel, but it is not scheduling runtime
  proof and is not selected by `EngineSchedulingCoreSuite`. It can still be run
  directly to check projection-proof leakage, dispatch cleanup primitive
  residue, WorkerContext runtime state mutation residue, and retired matching
  handoff types.
- `ExecutionSpec.foreground` is currently a public/read preset input carried
  through task model/API/trace surfaces; resolved `WorkerResourceMode` is the
  engine resource-mode truth
- worker match trace rows include reservation-time load snapshots so canonical
  assignment trace can prove the current process-local capacity guard
- `xa-mass-task-runtime` owns ready backlog, active lease, retry/finality,
  final-result rows, progress, and queue/backpressure truth
- batch/bulk redispatch is runtime-driven from task-runtime scheduler discovery
  through starter/engine recovery wiring; task-signal queues are not the only
  batch redispatch owner anymore
- bounded work/message compatibility residue is not the hot-path runtime
  owner
- `TaskQueryService` is the default task aggregate/state query surface; do not
  grow message/attempt residue reads back into it
- `TaskStateValidator` owns runtime aggregate validation only; scan-heavy
  compatibility projection audit is no longer part of the engine kernel
  diagnostic surface
- legacy projection helpers, storage projection row types, and `TaskDetailStore`
  have been retired from engine/runtime ownership
- public result reads must use `xa-mass-task-runtime` final-result rows;
  server-local review rows
  and retired projection rows must not source `/results`, SDK result query, or
  archive generation
- runtime ingest must stay correct when server-local review materialization
  fails or lags; accepted runtime truth lives in `xa-mass-task-runtime`, and
  review writes are best-effort read-model materialization
- assignment diagnostics are append-only bounded residue; matching and dispatch
  mainline should depend on a write-only recorder, not on report/history APIs
- dispatch submit failure after claim/attempt creation must compensate inline
  through runtime retry re-entry plus worker resource release; lease expiry
  repair is a fallback, not the mainline
- engine-provided bounded reads stop at task aggregate/state inspection;
  message/attempt review and export reads are server-local read-model concerns
- cross-module item-history reads should stay explicit about intent and should
  not be routed back through engine query surfaces
- cross-module callers that only need worker registration or current worker
  views should depend on worker-runtime query contracts rather than carrying
  private `WorkerManager` assembly
- cross-module callers that only need rule definitions should depend on
  `RuleStorage`; matching consumes `MatchingRuleSetProvider` and
  `MatchingRuleEvaluator` rather than a CRUD-shaped manager

Repo-level mainline surfaces:

- shell/admin mutation flows use `TaskCommandService`
- bounded inspection flows use `TaskQueryService`
- production engine mainline does not carry a message/attempt projection query
  owner; review/export read models stay server-local
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
- worker-runtime reachability diagnostics are point reads from the embedded
  presence projection; dispatch eligibility must not read transport endpoint
  leases as worker lifecycle truth
- task-create input consumed by `TaskCommandService` now lives in the neutral
  base model layer; cross-module create flows should not import engine-owned
  DTO packages just to submit tasks
- listeners, watchdogs, and startup recovery should depend on narrow ports, not
  on `TaskManager` plus reach-through getters

Infra ownership:

- kernel-facing task/rule contracts live in `../xa-mass-kernel-spi`
- persistence/control-plane storage contracts live in
  `../platform_infra/mass-storage-api`; engine production must not depend on
  that module
- task runtime queue/lease/result contracts live in `../xa-mass-task-runtime`
- transport adapter contracts live outside engine; engine must not take a direct
  dependency on `../transport/transport_api`
- SDK/server bootstrap owns concrete wiring
- primary SDK/server builders should wire storage implementations into kernel
  SPI task-shell ports, task-runtime starter handles, `WorkerDeclarationStore`,
  worker runtime contracts, and `RuleStorage`; server
  review materialization is wired through server-local review stores, not
  engine or shared storage projection contracts
- starter assembly should treat private worker-runtime `WorkerManager` assembly
  as derived over storage/runtime contracts, and should assemble rule matching
  from `RuleStorage` plus `RuleEvaluatorRegistry`, not from a broad rule
  manager

## Worker Selection Mechanism

Runtime worker selection is a Scheduling Plane concern with a narrow engine
contract. The engine resolves task-side policy intent and passes a
`WorkerSelectionRequest` to worker-runtime. Worker-runtime returns
`SelectedWorkerHandle` values that carry only selected identity and accounting
capability needed by the binder/release path.

Current owner types:

- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection/WorkerSelectionRuntime.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection/WorkerSelectionRequest.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection/WorkerSelectionIntent.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection/WorkerSelectionResult.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection/SelectedWorkerHandle.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection/SelectedWorkerEvidence.java`
- `src/main/java/com/xa/mass/engine/listener/TaskWorkerAssignListener.java`
- `src/main/java/com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java`
- `src/main/java/com/xa/mass/engine/resource/WorkerDispatchResourceReleaser.java`

Worker selection boundaries:

- Engine does not own worker candidate rows, worker scheduling views, worker
  match contexts, worker-fact rule evaluation, or worker-load ranking.
- Engine may decide requested worker count, minimum start gate, allocation
  budget, task-side worker universe intent, and retry/terminal behavior.
- Worker-runtime owns worker-fact predicate composition, routing/attribute
  matching mechanics, reachability/dispatch/lock/load/capability checks,
  ranking mechanics, reservation, exclusive lease acquisition, selected claim
  authorization, and selected-worker accounting.
- `SelectedWorkerHandle` is the hot-path handoff from worker-runtime to engine.
  Engine may read `workerId`, `workerGroupId`, and selected accounting/claim
  operations; it must not read worker attributes, live load, capability lists,
  reachability, dispatch gate state, candidate bucket ids, or transport
  topology ids.
- `SelectedWorkerEvidence` is the recovery/release shape for paths that only
  have persisted task binding evidence. It carries selected identity and
  selection scope, not worker metadata.
- Assignment diagnostics may record selected handles and task-level
  `WorkerSelectionResult` reason counts. They must not reconstruct
  `WorkerSchedulingView`, `WorkerSchedulingCandidate`, or `WorkerMatchContext`
  snapshots inside engine.
- Routing remains task-side intent currently resolved from
  `Task.sharedConfig["routingCode"]`; worker-runtime interprets it against
  worker facts after the request crosses the selection boundary.

If worker-selection semantics change, update the worker-runtime selection
contract, engine trace/diagnostic evidence, and representative routing or
contention coverage together.

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

- `EngineKernelConvergenceArchitectureGuardTest`
- `TaskKernelLifecycleTest`
- `TaskResultRuntimeConvergenceTest`
- `TaskResultConcurrencyConvergenceTest`
- `EngineSchedulingCoreArchitectureGuardTest` (support-only residue guard; not
  selected by `EngineSchedulingCoreSuite`)
- `TaskIdleClosePolicyBehaviorTest`
- `TaskPolicySchedulingOutcomeTest`
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
- `SimpleTaskDispatchBinderTest`
- `WorkerDispatchResourceReleaserTest`

Current scheduling-matrix scenarios include:

- multi-task contention on worker resources and across a worker pool
- worker eligibility rejection for unreachable, locked, capacity-exhausted,
  routing-mismatch, and target-attribute mismatch candidates
- active contention after worker-runtime reachability drops, with backup-worker dispatch
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

## Read Map

Engine-local owner docs:

- [`doc/README.md`](./doc/README.md):
  engine-local documentation index. Current truth lives in `doc/baseline/`,
  measurement context lives in `doc/measurements/`, direction notes live in
  `doc/roadmap/`, and historical plans stay outside the active engine read map.

- [`doc/baseline/SCHEDULING_CORRECTNESS_MATRIX.md`](./doc/baseline/SCHEDULING_CORRECTNESS_MATRIX.md):
  current invariant-to-test map for scheduling correctness, proof surfaces, and
  coverage placement notes
- [`doc/baseline/KERNEL_CONVERGENCE_MATRIX.md`](./doc/baseline/KERNEL_CONVERGENCE_MATRIX.md):
  current invariant-to-test map for lifecycle/result convergence, proof
  surfaces, and mixed legacy test extraction notes
- [`doc/baseline/SCHEDULING_KERNEL_BASELINE.md`](./doc/baseline/SCHEDULING_KERNEL_BASELINE.md):
  current scheduling mainline, worker scheduling surface, policy ownership,
  precedence, boundaries, and proof map
- [`doc/measurements/MATCH_THROUGHPUT_NOTE.md`](./doc/measurements/MATCH_THROUGHPUT_NOTE.md):
  current match-throughput instrumentation baseline and local validation
  record; this is measurement context, not a throughput guarantee
- [`doc/baseline/RUNTIME_BOUNDARY_BASELINE.md`](./doc/baseline/RUNTIME_BOUNDARY_BASELINE.md):
  current runtime cutover, recovery, and truth-layer boundary
- [`doc/baseline/STORAGE_BASELINE.md`](./doc/baseline/STORAGE_BASELINE.md):
  current engine-facing storage/runtime boundary
- [`doc/roadmap/TASK_RUNTIME_PROFILE_DESIGN.md`](./doc/roadmap/TASK_RUNTIME_PROFILE_DESIGN.md):
  design/refactor note for workload-profile boundaries
- [`doc/roadmap/PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md`](./doc/roadmap/PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md):
  non-baseline production scheduling improvement notes. This is not current
  behavior or an implementation plan.
- [`doc/baseline/EVENT_OWNER_BOUNDARY.md`](./doc/baseline/EVENT_OWNER_BOUNDARY.md):
  current owner map for descriptor metadata, task-result input, worker
  presence ingress, and command/state/capability owner slots

Global baselines:

- [`../AGENTS.md`](../AGENTS.md)
- [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
- [`../doc/TASK_LIFECYCLE_BASELINE.md`](../doc/TASK_LIFECYCLE_BASELINE.md)
- [`../doc/TRACE_CONTRACT.md`](../doc/TRACE_CONTRACT.md)
- [`../doc/TESTING_INDEX.md`](../doc/TESTING_INDEX.md)
