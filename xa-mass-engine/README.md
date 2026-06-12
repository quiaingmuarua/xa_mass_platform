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
  match count, minimum-worker start gate, and the matched worker candidates that
  may enter dispatch.
- `TaskWorkerAssignListener` owns orchestration around that decision: invoking
  matching, unlocking skipped/surplus workers, task status transition, assignment
  trace, task update, and assignment event publication.
- `SimpleTaskDispatchBinder` owns runtime claim and dispatch binding only; it is
  not the assignment allocation policy owner.

Task item dispatch output:

- `TaskWorkRuntime` owns ready membership, claim, active lease, retry timing,
  and runtime counters before dispatch.
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
  -> matching/acquisition path
  -> SimpleTaskDispatchBinder

Worker scheduling read model
  -> WorkerSchedulingCandidateEnumerator
  -> WorkerSchedulingView
  -> WorkerMatchContext

Eligibility and preference
  -> worker prefilter + current default QLExpress rule evaluation
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

Owner-backed worker-control and stage entry surfaces:

- `WorkerControlService` is the engine caller surface for worker command
  request/ack read views, worker capability self-report, and bounded worker
  state projection. It emits canonical trace and delegates truth to
  `WorkerCommandLifecycleOwner`, `WorkerCapabilityAuthority` via
  `WorkerManager`, and `WorkerStateProjectionOwner`. Any translation from
  worker-control truth into scheduling dispatch gate truth belongs to the
  pluggable `WorkerDispatchAvailabilityPolicy`, not to transport shells or
  matching mainline code.
- `TaskStageEvidenceService` is the engine caller surface for task item stage
  evidence and bounded stage projection. It emits canonical trace and delegates
  stage truth to `TaskStageEvidenceOwner`.
- Event handlers parse kernel-targeted event payloads and call these services.
  SDK/server integration should call the services through SDK-facing request
  models, not reach into event handlers or mutate owner internals directly.

The current `RuleBasedTaskWorkerMatchingStrategy` combines group-first candidate
acquisition, worker scheduling evidence, QLExpress-backed eligibility rules,
ranking, capacity reservation, and optional worker-lock acquisition. That is
the current default worker-selection mechanism, not the strategic endpoint of
the platform and not a top-level policy owner. Future selection work may add
worker intrinsic metrics, task-type affinity, historical performance, or
domain-specific scoring. Add those as explicit worker scheduling policy inputs,
runtime worker selection evidence, or rule-backed components; do not hide
mechanism growth behind pass-through wrappers.

Policy abstraction boundary:

- Scheduling Plane ownership is split into three first-class owners:
  `TaskSchedulingPolicy`, `WorkerSchedulingPolicy`, and
  `RuntimeWorkerSelection`. Project / workload binding chooses allowed/default
  task and worker policies plus scoped configuration. Current engine-facing
  value contracts exist for `TaskDispatchIntent`,
  `ResolvedTaskSchedulingPolicy`, and `ResolvedWorkerSchedulingPolicy`; a
  catalog/binding/configurable policy path is not implemented yet.
- current implementation: policy remains distributed across task runtime
  profile, explicit group selectors, matching rule sets, assignment allocation,
  runtime backpressure, and admission behavior.
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
  explicit task `workerGroupId` / `workerGroupIds` selectors before worker rows
  are acquired
- low-level worker registry primitives live in
  `platform_infra/mass-runtime-api`; resource contracts and higher-level worker
  runtime contracts live in `xa-mass-worker-runtime`
- `EventKey` remains a low-level project-scoped worker capability key.
  `EventBinding`, `WorkerGroupRecord`, and resource DTOs are worker-runtime
  resource contracts; `WorkerRegistrySnapshot` is worker-runtime package-local
  implementation evidence, not an engine public surface
- `WorkerCandidateIndex` consumes explicit group selectors and narrows
  `workerGroupId(s) -> route/node bucket -> workerIds`; it does not derive
  candidate groups from task eventCode/project and does not own reachability,
  load, reservation, or resource policy
- `WorkerManager` lives in `xa-mass-worker-runtime` as private runtime assembly.
  SDK/server registration crosses `WorkerResourceRuntime`; accepted resource
  mutations refresh the derived runtime projection.
- candidate source enters through `WorkerCandidateRuntime.findWorkerCandidateBatch(...)`
  and is materialized by the strategy-package
  `WorkerSchedulingCandidateEnumerator`
- `targetWorkerId` is only a debug/manual narrowing shortcut inside an
  explicit group selector; it cannot bypass group, reachability, dispatch gate,
  load, lock, or rule checks
- event-code-only and project-only tasks do not match workers in the kernel;
  SDK/intake may resolve event metadata to `workerGroupId(s)` before assignment
  by validating project/event bindings against WorkerGroup capability
- `WorkerRegistrySnapshot` may retain WorkerGroup `EventBinding` read caches
  for catalog/report-ceiling flows, but event bindings are not the scheduling
  candidate-source key
- Stage 2 scheduling capability evidence is materialized from
  `WorkerGroupRecord`; group-level capability remains the boundary for
  supported event handlers and projects, and explicit AdapterNode/NodeGroupBinding registration
  is required before adapter-node scoped worker registration
- `WorkerSchedulingCandidateEnumerator` is a strategy-package implementation
  detail, not a public extension point
- the old unused `WorkerSelector` / `DefaultWorkerSelector` path is removed so
  worker selection has one active mainline: candidate source -> rule/rank ->
  allocation/resource admission
- `RuleBasedTaskWorkerMatchingStrategy` must consume the centralized candidate
  source and must not reintroduce direct all-worker scans
- canonical assignment trace includes `workerGroupId`, `eventBindingKey`, and
  `workerCandidateSource` on worker match rows; the
  `group-capability-routing` trace scenario is the representative proof that
  SDK event routing uses the group-indexed candidate source through real server
  wiring

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
  - `TaskWorkRuntime`
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
  whether a task/candidate uses the long-lived worker-level exclusive lock.
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
  compatibility projection audit is no longer part of the engine kernel
  diagnostic surface
- legacy projection helpers, storage projection row types, and `TaskDetailStore`
  have been retired from engine/runtime ownership
- public result reads must use `TaskResultRuntime`; server-local review rows
  and retired projection rows must not source `/results`, SDK result query, or
  archive generation
- runtime ingest must stay correct when server-local review materialization
  fails or lags; enqueue truth lives in `TaskWorkRuntime`, and review writes
  are best-effort read-model materialization
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
- worker-runtime reachability is read through `WorkerReachabilityView`;
  dispatch eligibility must not read transport route-owner leases as worker
  lifecycle truth
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
- runtime queue/lease contracts live in `../platform_infra/mass-runtime-api`
- transport adapter contracts live outside engine; engine must not take a direct
  dependency on `../transport/transport_api`
- SDK/server bootstrap owns concrete wiring
- primary SDK/server builders should wire storage implementations into kernel
  SPI task-shell ports, `TaskWorkRuntime`, `TaskResultRuntime`,
  `WorkerDeclarationStore`, worker runtime contracts, and `RuleStorage`; server
  review materialization is wired through server-local review stores, not
  engine or shared storage projection contracts
- starter assembly should treat private worker-runtime `WorkerManager` assembly
  as derived over storage/runtime contracts, and should assemble rule matching
  from `RuleStorage` plus `RuleEvaluatorRegistry`, not from a broad rule
  manager

## Worker Selection Mechanism

Matching is current worker-selection mechanism vocabulary, not a top-level
policy owner. The current default implementation enumerates
`WorkerSchedulingCandidate` values, reads `WorkerSchedulingView` evidence,
evaluates declarative eligibility through `WorkerMatchContext#getRuleContext()`
and QLExpress rules, ranks accepted candidates, and reserves/adopts worker
admission evidence. The full `WorkerMatchContext` snapshot remains diagnostic
evidence for records and traces. QLExpress rules are one eligibility component,
not the final shape of worker scheduling policy or runtime worker selection.

Current owner types:

- `src/main/java/com/xa/mass/engine/model/WorkerSchedulingCandidate.java`
- `src/main/java/com/xa/mass/engine/model/WorkerSchedulingView.java`
- `src/main/java/com/xa/mass/engine/model/WorkerMatchContext.java`
- `src/main/java/com/xa/mass/engine/strategy/WorkerSchedulingCandidateEnumerator.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/candidate/WorkerCandidateRuntime.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerSchedulingViewRuntime.java`
- `../xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/admission/WorkerAdmissionRuntime.java`
- `src/main/java/com/xa/mass/engine/rules/MatchingRuleSetProvider.java`
- `src/main/java/com/xa/mass/engine/rules/MatchingRuleEvaluator.java`
- `src/main/java/com/xa/mass/engine/rules/RegistryBackedMatchingRuleEvaluator.java`
- `src/main/java/com/xa/mass/engine/rules/StorageBackedMatchingRuleSetProvider.java`
- `src/main/java/com/xa/mass/engine/rules/RuleConfig.java`

Current default bootstrap rule set:

- `basic_worker_check`
- `worker_scheduling_resource_check`
- `routing_code_match`
- `worker_load_check`

Worker selection boundaries:

- `WorkerSchedulingCandidate` is the engine-internal handoff between worker
  selection, allocation, listener orchestration, and dispatch binding
- `WorkerSchedulingCandidateEnumerator` creates one scheduling candidate per
  worker from the worker read model; worker selection no longer expands legacy
  `WorkerContext` storage into candidates
- worker-level assignment diagnostics consume `WorkerSchedulingCandidate`;
  candidate handoff no longer carries a nullable `WorkerContext` payload
- `WorkerMatchContext` owns both the full diagnostic snapshot and the narrower
  declarative rule context; `RuleBasedTaskWorkerMatchingStrategy` evaluates
  rules against the declarative rule context and keeps the full snapshot for
  prefilter, assignment records, and trace diagnostics
- assignment records snapshot `WorkerSchedulingView` evidence through
  `WorkerSchedulingSnapshot`; account-slot identity is not the diagnostic
  subject
- `WorkerSchedulingView` is the scheduling read surface; new matching code
  should read the view rather than treating `WorkerContext` as the worker
  selection subject
- `WorkerRegistry` is the worker runtime slot owner for reservation and active
  lease counters; `WorkerLoadSnapshot` is a read-side value derived from the
  current slot, not a separate mutable owner
- `Worker.status` and worker lock state are typed truth, not attributes
- `Worker.status` is worker-runtime lifecycle truth, not transport route-owner
  truth
- dispatch eligibility must read worker-runtime reachability from
  `WorkerReachabilityView`, not transport route-owner expiry or local
  heartbeat-expiry heuristics
- `workerSchedulingAttributes` is one worker-selection evidence family for labels,
  fingerprints, and routing hints; it is not the whole policy model
- default rules must use declarative task intent, worker capability, and static
  worker metadata variables; legacy `workerContext*` variables and live runtime
  evidence such as availability, lock, admission, reserve, and load are not part
  of the engine rule surface
- worker load variables such as `workerActiveLeaseCount`,
  `workerReservedCount`, and `workerEstimatedLoadRatio` are scheduling
  diagnostics and ranking evidence; current capacity semantics are
  worker-declared process-local reservation plus the existing worker lock, not
  distributed capacity correctness or shared background execution
- worker-selection inputs must stay explicit scheduling evidence rather than
  being smuggled into worker-resource ownership or worker scheduling policy
- routing is a task-owned hint currently resolved from
  `Task.sharedConfig["routingCode"]`
- once a task requires routing, the candidate must expose worker-selection
  `workerSchedulingRoutingTags`; in the current scheduling hot path those tags
  come from worker attributes such as `routingTag` / `routingTags`

If worker-selection semantics change, update the policy contract,
`WorkerMatchContext` when rule evaluation is affected, trace evidence, and the
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
- `RuleBasedTaskWorkerMatchingStrategyTest`
- `WorkerMatchContextTest`

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
