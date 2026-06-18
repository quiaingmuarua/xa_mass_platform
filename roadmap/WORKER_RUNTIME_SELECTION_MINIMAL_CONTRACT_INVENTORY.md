# Worker Runtime Selection Minimal Contract Inventory

Status: current code inventory for
`WORKER_RUNTIME_SELECTION_MINIMAL_CONTRACT_ROADMAP.md`.

This inventory records the current engine and worker-runtime worker-selection
call surface. It is not target-state proof.

## Symbols

| Symbol | Current Owner | Current Caller | Classification | Target |
| --- | --- | --- | --- | --- |
| `EngineRuntimeKernelConfig#getWorkerCandidateRuntime()` | engine assembly | `EngineRuntimeKernel` | engine sees worker candidate-source port | Replace with approved worker selection atomic stage contract(s). |
| `EngineRuntimeKernelConfig#getWorkerSchedulingViewRuntime()` | engine assembly | `RuleBasedTaskWorkerMatchingStrategy` / `WorkerSchedulingCandidateEnumerator` | engine sees live worker evidence read port | Remove from engine assembly; worker-runtime composes evidence inside atomic stages. |
| `EngineRuntimeKernelConfig#getWorkerAdmissionRuntime()` | engine assembly | matching, binder, release, resource releaser, allocation policy | engine owns reserve/lock/final calls | Replace reservation/accounting lifecycle with selected-handle atomic commands. |
| `WorkerCandidateRuntime#findWorkerCandidateBatch(...)` | worker-runtime | `RuleBasedTaskWorkerMatchingStrategy` | stage-1 worker row acquisition exposed to engine | Internal candidate-acquisition stage mechanism. |
| `WorkerSchedulingViewRuntime` | worker-runtime | `WorkerSchedulingCandidateEnumerator` | live reachability/load/group read evidence exposed to engine | Internal worker-fact evidence for atomic stages. Server/diagnostics use separate inspection view. |
| `WorkerAdmissionRuntime` | worker-runtime | `RuleBasedTaskWorkerMatchingStrategy`, `SimpleTaskDispatchBinder`, `TaskResourceReleaseListener`, `WorkerDispatchResourceReleaser` | reserve/lease/final surface exposed across engine hot path | Replace with selected-handle reservation/accounting atomic commands. |
| `WorkerTaskSelectorFactory` | engine | `RuleBasedTaskWorkerMatchingStrategy`, `TaskWorkerAssignListener` | adapter from resolved policy to worker-runtime candidate selector | Replace with minimal stage request factory that carries task-side worker-universe intent only. |
| `WorkerCandidateRow` | worker-runtime | engine model, match context, trace, assignment diagnostics | slim row but still engine-visible worker metadata | Internal to worker-runtime, or diagnostic-only outside hot path. |
| `WorkerSchedulingView` | engine | `WorkerSchedulingCandidate`, `WorkerMatchContext`, diagnostics, ranker, binder | engine-owned composite worker view | Remove from scheduling hot path. If retained, diagnostic-only and not rule/rank/bind input. |
| `WorkerSchedulingCandidate` | engine | assignment allocation, binder, trace, diagnostics, resource release | composite selected candidate | Replace with minimal selected worker handle. |
| `WorkerMatchContext` | engine | `RuleBasedTaskWorkerMatchingStrategy`, rule evaluator, diagnostics | engine materializes worker metadata and live evidence | Remove worker metadata from engine rule context; route worker-side predicates through worker-runtime atomic stages. |
| `DefaultWorkerCandidateRanker` | engine | `RuleBasedTaskWorkerMatchingStrategy` | engine ranks by load, routing affinity, worker attributes | Move worker-fact ranking mechanics inside worker-runtime atomic stages; policy choice stays in Scheduling Plane. |
| `RuleBasedTaskWorkerMatchingStrategy` | engine | `EngineRuntimeKernel` default strategy | engine performs candidate enumeration, prefilter, rule evaluation, ranking, reserve | Replace with engine orchestration of minimal worker-runtime atomic stages. Do not move this class wholesale. |
| `TaskWorkerMatchingStrategy` | engine | `TaskWorkerAssignListener` | extension seam returns `WorkerSchedulingCandidate` | Replace or narrow so extensions choose policy/stage strategy or return minimal selected handles, not worker views. |
| `EngineConfig#setMatchingStrategy(...)` / `MassEngineBuilder#matchingStrategy(...)` | SDK assembly | embedded SDK startup/builders | public-ish assembly seam injects old `TaskWorkerMatchingStrategy` returning `WorkerSchedulingCandidate` | Remove or retarget to an approved selected-handle/stage-policy extension in the same slice that narrows `TaskWorkerMatchingStrategy`. |
| `SimpleTaskDispatchBinder` | engine | `TaskWorkerAssignListener` | binds `WorkerSchedulingCandidate` and confirms admission | Bind minimal selected worker handles; no worker attributes/capabilities. Claim/accounting uses selected-handle commands. |
| `WorkerDispatchResourceReleaser` | engine | binder, assignment listener, terminal/release paths | releases by `WorkerSchedulingCandidate` and admission target | Release by selected worker handle or persisted `(taskId, workerGroupId, workerId, selectionToken)` evidence. |
| `AssignmentAllocationPolicy` / `AssignmentAllocationDecision` | engine | `TaskWorkerAssignListener` | allocation policy carries `List<WorkerSchedulingCandidate>` | Carry counts and selected handles only. |
| `AssignmentRecordService` | engine diagnostics | matching and binder | records worker scheduling snapshots from engine view | Use bounded selection diagnostics supplied by worker-runtime; no engine metadata processing. |
| `TraceEventLogger` worker-selection methods | engine trace | matching, binder, release | logs full worker scheduling view/rank attrs | Log selected handle plus bounded summary/reason codes. |
| `WorkerClaimTarget#supportedEventCodes` use | runtime API / engine binder | `SimpleTaskDispatchBinder` | engine passes per-worker event capability into work claim | Replace direct scheduling-view reads with a selected-handle claim constraint or opaque claim token produced by worker-runtime. Binder may translate to current `WorkerClaimTarget` only as a bridge. |
| `TaskDispatchIntent` / `ResolvedWorkerSchedulingPolicy` | engine | `RuleBasedTaskWorkerMatchingStrategy`, `WorkerTaskSelectorFactory` | engine-owned task-side worker-universe intent | Translate to API-owned `WorkerSelectionIntent`; worker-runtime must not import engine DTOs. |
| `EngineSchedulingCoreArchitectureGuardTest` old worker-view guards | engine tests | engine architecture proof | current guard partially protects `WorkerSchedulingView` model | Migrate in WSM-2 to selected-handle and worker-selection contract guards. |
| `xa-mass-worker-runtime/CONTRACTS.md` worker ranking wording | worker-runtime owner docs | agents/implementers | current contract says worker-runtime does not own rule evaluation or worker ranking | Update with README when selection contracts land: worker-runtime may own worker-fact predicate/ranking mechanics, but not task-side policy or lifecycle semantics. |
| `WorkerControlRuntime` | engine control facade | SDK/server/event handlers/watchdog | worker-control command/report surface in engine | Out of this roadmap unless it leaks into selection. Consider separate worker-control owner migration later. |
| `WorkerDispatchGateRuntime` in control policy | worker-runtime port consumed by engine control policy | `DefaultWorkerDispatchAvailabilityPolicy` | dispatch gate mutation from worker state/command | Out of this roadmap except selection must not read gate directly from engine. |

## Current Dependency Shape

| Module | Dependency | Scope | Reason | Target |
| --- | --- | --- | --- | --- |
| `xa-mass-engine` | `xa-mass-worker-runtime` | main | selection, admission, worker evidence, control DTOs | Keep dependency only for minimal atomic selection/control contracts, not candidate/view metadata. |
| `xa-mass-engine` | `xa-mass-kernel-spi` | main | task shell SPI and rule SPI | Rule SPI must not receive worker metadata in engine hot path. |
| `xa-mass-worker-runtime` | `xa-mass-engine` | none | no reverse dependency | Keep no reverse dependency; selection intent/handle contracts must live in `mass-runtime-api` or worker-runtime API, not engine packages. |
| `sdk/xa-mass-embedded-sdk` | `xa-mass-engine` + `xa-mass-worker-runtime` | main | assembly of engine and worker runtime | Provide the approved worker selection atomic stage contracts to engine config. |

## Decisions Captured By This Inventory

- The problem is not only fat DTO shape. Current engine code actively
  interprets worker metadata and live evidence.
- `WorkerSchedulingView`, `WorkerSchedulingCandidate`, and
  `WorkerMatchContext` are the main worker-fact carriers to retire from the
  scheduling hot path.
- Worker attributes, WorkerGroup capability projections, worker load details,
  reachability, dispatch gate, locks, reservations, and warm-candidate evidence
  belong inside worker-runtime atomic mechanisms.
- Worker-runtime may own worker-side predicate/ranking mechanics once engine
  has resolved task-side policy intent. This must be reflected in
  `xa-mass-worker-runtime/README.md` and
  `xa-mass-worker-runtime/CONTRACTS.md`; it does not mean worker-runtime owns
  task lifecycle policy or public scheduling policy.
- The first contract slice must choose the selected-handle claim bridge before
  removing `WorkerSchedulingView` from `SimpleTaskDispatchBinder`. The binder
  must not read `supportedEventCodes` or WorkerGroup capability lists directly.
- Worker-runtime must not understand task lifecycle fields. It may carry opaque
  selection, reservation, claim, accounting, or prewarm ids only when a concrete
  mechanism needs them for correlation and idempotency. Those ids are not
  `taskId` / `messageId` semantics and must not expose retry, result, or
  terminal policy.
- Engine may decide coarse worker-universe slicing, such as a bucket policy or
  bucket hint, when that is part of task-side worker-universe intent. Engine
  must not decide worker-runtime final source keys, cache keys, storage keys, or
  prewarm keys.
- Worker-runtime must not invent its own selection-visible shard, bucket, or
  partition policy. Private lookup/index keys are allowed only as hidden storage
  mechanics derived from engine-provided worker-universe intent/hints and
  canonical worker facts; they must not change the worker universe, ranking
  domain, or assignment correctness.
- `WorkerSelectionIntent` must be API-owned or worker-runtime-owned in a way
  that preserves the current no reverse dependency from worker-runtime to
  engine.
- Existing architecture guards that protect the old worker view model are part
  of the WSM-2 migration, not a final cleanup task.
- The SDK `matchingStrategy` assembly seam is part of WSM-2. Leaving it on the
  old `TaskWorkerMatchingStrategy` contract would preserve a second production
  path for `WorkerSchedulingCandidate`.
- Engine may keep task-side scheduling policy, allocation budget, work claim,
  dispatch binding, result convergence, and terminal policy.
- The target is not moving the current fat engine strategy class into
  worker-runtime. Engine should orchestrate minimal stage calls; worker-runtime
  should execute worker-fact mechanisms behind minimal handles and outcomes.
- Server/SDK diagnostic views may expose worker metadata through explicit
  inspection APIs, but those views must not be imported by engine scheduling
  packages.
