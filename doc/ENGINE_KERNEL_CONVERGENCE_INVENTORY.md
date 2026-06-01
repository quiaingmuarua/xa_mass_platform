# Engine Kernel Convergence Inventory

Status: EKC-0 inventory for
[`ENGINE_KERNEL_CONVERGENCE_ROADMAP.md`](./ENGINE_KERNEL_CONVERGENCE_ROADMAP.md).

This inventory records code ownership and caller shape as EKC moves or narrows
runtime-kernel surfaces. EKC-0 captured the baseline; later notes mark landed
convergence work.

## Owner Principle

Large internal orchestrators are acceptable when they keep owner boundaries,
mainline flow, and scheduling entry points visible. Size alone is not evidence
of a god class. EKC should not add wrappers, facades, or pass-through adapters
just to reduce line count or hide imports.

The convergence target is visibility:

- cross-module callers use stable runtime ports, command services, and explicit
  assembly contracts
- engine-internal orchestrators may stay large when they own one clear runtime
  mainline
- implementation listeners, watchdogs, strategies, and utilities should not
  become SDK/server API by accident

## Cross-Module Engine Import Classification

Current production cross-module imports from `com.xa.mass.engine.*` are in
`xa-mass-sdk`. Server production has no direct engine implementation imports.
`platform_infra/mass-runtime-redis` imports engine types only from tests.

Update after EKC-1A/EKC-1B:

- `MassEngine` no longer imports engine listener, watchdog, strategy, service,
  or util implementation packages. Default runtime scheduling assembly now
  enters through engine-owned `EngineRuntimeKernel`.
- SDK main code no longer imports engine listener, watchdog, or util
  implementation packages. `PollingIdleBackoffPolicy`,
  `ExponentialPollingIdleBackoffPolicy`, and `PollingResourceKey` moved to the
  engine root as assembly/config contracts.
- `TraceEventLogger` moved from `com.xa.mass.engine.util` to the engine root.
- EKC-2 added `TaskDefinitionPatch` and
  `TaskCommandService.patchTaskDefinition(...)`; SDK task-definition updates no
  longer use mutable aggregate overwrite.
- EKC-3A extracted result repair-pump scheduling and shutdown into
  `TaskResultRepairPump`. `TaskResultService` still owns repair business
  handling and result convergence.
- EKC-3B renamed result-service runtime attempt/view helpers away from
  projection-era names (`AttemptProjectionView` -> `RuntimeAttemptView`,
  active projection -> active runtime view).
- EKC-3C moved visible-final commit and staged callback cleanup into
  `TaskResultVisibleFinalCommitter`. This is a commit lifecycle split, not a
  result-truth move; `TaskResultRuntime` remains the visible final source.
- During EKC-3C, `TaskManager.applyTaskResultProgressOnce(...)` was adjusted so
  a repair-pump BUSY progress barrier still allows the synchronous caller to
  refresh task progress from runtime truth. The barrier still owns staged
  cleanup; the progress update is idempotent.
- EKC-4 moved worker command lifecycle truth and value/port contracts to
  `com.xa.mass.worker.runtime.command`. Engine still owns worker-control entry,
  delivery coordination, trace emission, maintenance scan, and dispatch-gate
  side effects.
- `TraceEventLogger` is not a generic utility; it is the canonical lifecycle
  trace emitter used by engine assembly and runtime components.
- `MassEngineAssemblyBoundaryTest` now enforces both forbidden implementation
  package references and an explicit allowlist for classified SDK main imports
  from `com.xa.mass.engine.*`.

### Approved Runtime/Assembly Surface

These imports are intentional runtime-kernel ports or assembly surfaces for now.
They may still be narrowed later, but they are not accidental implementation
leaks in EKC-0.

| Caller | Engine symbols | Classification |
| --- | --- | --- |
| `xa-mass-sdk/.../RuntimeEventBusEngineBridge.java` | `TaskEventListenerRegistrar` | Approved event-listener registration seam. |
| `xa-mass-sdk/.../EngineRuntimeBridge.java` | `TaskEventListenerRegistrar` | Approved event-listener registration seam. |
| `xa-mass-sdk/.../MassEngine.java` | `TaskAssignmentRuntimePort`, `TaskCommandService`, `TaskDispatchWakeupPort`, `TaskDispatchWakeupBridge`, `TaskEventListenerRegistrar`, `TaskEventService`, `TaskLeaseMaintenancePort`, `TaskRuntimeRecoveryPort`, `TaskShellLifecycleMaintenancePort` | Approved SDK assembly/runtime ports. |
| `xa-mass-sdk/.../config/EngineConfig.java` | `TaskManager`, `TaskCommandService`, `TaskEventService`, `TaskAssignmentRuntimePort`, `TaskDispatchWakeupPort`, `TaskLeaseMaintenancePort`, `TaskQueryService`, `TaskManagerResultIngestFacade`, `TaskRuntimeRecoveryPort`, `TaskShellLifecycleMaintenancePort`, `WorkerControlRuntime` | Approved SDK assembly surface. `TaskManager` remains the composition root, not an external app contract. |
| `xa-mass-sdk/.../DefaultTaskDiagnosticOperations.java` | `TaskQueryService`, task diagnostic result models | Approved diagnostic read surface. |
| `xa-mass-sdk/.../TaskDiagnosticOperations.java` | task diagnostic result models | Approved SDK diagnostic API until EKC-1 decides whether these models need a public SDK copy. |
| `xa-mass-sdk/.../MassSdkApplication.java` | `TaskQueryService`, `TaskCommandService`, `TaskEventService`, `WorkerControlRuntime` | Approved SDK runtime application surface. |

### Implementation Imports To Converge In EKC-1

These are engine implementation details currently imported by SDK assembly or
SDK builders. They should either become explicit assembly contracts with a real
owner-boundary reason or stop being cross-module imports.

| Caller | Engine symbols | Target classification |
| --- | --- | --- |
| `xa-mass-sdk/.../MassEngine.java` | `SimpleTaskDispatchBinder`, `TaskAssignWorker`, `TaskResourceReleaseListener`, `TaskWorkerAssignListener` | Fixed in EKC-1A. Runtime scheduling implementation is assembled by `EngineRuntimeKernel`. |
| `xa-mass-sdk/.../MassEngine.java` | `LeaseExpireWatchdog`, `RuntimeReadyDispatchPump`, `WorkerCommandMaintenanceWatchdog` | Fixed in EKC-1A. Maintenance implementation is assembled by `EngineRuntimeKernel`. |
| `xa-mass-sdk/.../MassEngine.java` | `RuleBasedTaskWorkerMatchingStrategy` | Fixed in EKC-1A. Default matching strategy is selected inside `EngineRuntimeKernel`; custom strategy remains a config extension point for now. |
| `xa-mass-sdk/.../MassEngine.java`, `EngineConfig.java`, `MassEngineBuilder.java` | `TaskWorkerMatchingStrategy` | `MassEngine` fixed in EKC-1A. `EngineConfig`/builder remain extension-point candidates; EKC-1C should decide whether this stays an approved assembly contract or moves to a narrower public package. |
| `xa-mass-sdk/.../MassEngine.java`, `MassApplication.java` | `LogUtils`, `TraceEventLogger` | `MassEngine` fixed in EKC-1A. `MassApplication` no longer imports `LogUtils` after EKC-1B. `TraceEventLogger` moved to the engine root in EKC-1C as an explicit lifecycle-trace contract. |
| `xa-mass-sdk/.../config/EngineConfig.java` | `DefaultWorkerDispatchAvailabilityPolicy`, `WorkerControlService`, `WorkerDispatchAvailabilityPolicy` | Worker dispatch-control implementation/extension mix. Target: classify policy as extension surface or keep worker control assembly internal. |
| `xa-mass-sdk/.../config/EngineConfig.java` | `TaskStageEvidenceOwner`, `TaskStageEvidenceService` | Stage-evidence assembly implementation. Target: approved runtime port only if cross-module caller needs direct stage operations. |
| `xa-mass-sdk/.../config/EngineConfig.java` | `RegistryBackedMatchingRuleEvaluator`, `RuleConfig`, `RuleEvaluatorRegistry`, `RuleEvaluatorRegistries`, `MatchingRuleEvaluator`, `MatchingRuleSetProvider` | Rule/matching assembly contracts. They belong to kernel matching assembly, not general SDK API. Keep only explicit provider/evaluator contracts visible. |
| `xa-mass-sdk/.../config/EngineConfig.java`, `MassEngineBuilder.java` | `AssignmentDiagnosticRecorder`, `AssignmentRecordService` | Diagnostic implementation. Target: explicit diagnostics config surface or engine-internal default. |
| `xa-mass-sdk/.../config/EngineConfig.java`, `MassApplicationBuilder.java`, `MassSdk.java` | `PollingIdleBackoffPolicy`, `ExponentialPollingIdleBackoffPolicy` | Configuration extension candidate. Keep only if SDK intentionally exposes idle-backoff tuning. |

### Public SDK Operation Models Still From Engine

These imports are not assembly implementation classes, but they expose engine
model packages directly through SDK APIs. EKC should not fix all of them unless
the slice owns the public contract, but they remain visible residue.

| Caller | Engine symbols | Target classification |
| --- | --- | --- |
| `MassApplication.java`, `MassSdkApplication.java` | worker command records/results/requests/status/acknowledgement | Fixed in EKC-4. SDK now imports command lifecycle/value contracts from `com.xa.mass.worker.runtime.command`. |
| `MassSdkApplication.java`, `TaskDiagnosticOperations.java`, `DefaultTaskDiagnosticOperations.java` | `TaskResumeResult`, `TaskStateResolutionResult`, `TaskStateValidationResult` | SDK-facing result models. May remain if engine owns diagnostic/read command semantics. |
| `MassSdkApplication.java` | `TaskStageEvidenceResult`, `TaskStageEvidenceService`, `TaskStageProjection` | Stage evidence API. Needs separate owner check if stage evidence becomes public SDK contract. |

### Tests

`platform_infra/mass-runtime-redis/src/test` imports `TaskCommandService`,
`TaskLeaseMaintenancePort`, `TaskManager`, `TaskManagerResultIngestFacade`, and
`TaskQueryService` for trace/runtime integration proof. These are test-scope
fixtures and are not production dependency evidence.

## Task Command Port Classification

`TaskCommandPort` is the preferred command entry surface, but it currently
mixes intent commands with aggregate CRUD.

| Method | Current callers | Classification | EKC target |
| --- | --- | --- | --- |
| `createTaskShell(TaskShellCreateRequestDto)` | SDK/server task create path through `TaskCommandService` | External command | Keep. |
| `patchTaskDefinition(String, TaskDefinitionPatch)` | SDK task-definition update and SDK event-backed worker-group selector persistence | External definition patch command | Added in EKC-2. Keep as the normal SDK/API definition update path. |
| `updateTask(Task)` | Engine lifecycle/assignment/state resolver internals; no SDK main callers after EKC-2 | Engine-internal aggregate owner handoff | Keep only for engine-internal aggregate persistence. SDK/server production `.updateTask(...)` call patterns are guarded. |
| `deleteTask(String)` | Command service/API surface | Admin/control-plane command | Keep only if status/ownership semantics are explicit; not a runtime scheduling primitive. |
| `approveTask(String)` | SDK/server task command path | External task lifecycle command | Keep. |
| `rejectTask(String)` | SDK/server task command path | External task lifecycle command | Keep. |
| `blockTask(String)` | SDK/server task command path | External task lifecycle command | Keep. |
| `pauseTask(String)` | SDK/server task command path | External task lifecycle command | Keep. |
| `resumeTaskDetailed(String)` | SDK task command path | External task lifecycle command with detail | Keep. |
| `resumeTask(String)` | SDK/server task command path | External task lifecycle command | Keep as convenience or delegate to detailed result. |
| `cancelTask(String)` | SDK/server task command path | External task lifecycle command | Keep. |
| `terminateTask(String, TaskTerminalReason)` | SDK/server task command path | External task terminal command | Keep. |
| `appendTaskItemsWithReceipt(String, List<Map<String,Object>>)` | SDK/server item append path | External intake command | Keep. |
| `appendTaskItems(String, List<Map<String,Object>>)` | SDK/server convenience path | External intake convenience | Keep if it delegates to receipt-producing command. |
| `sealTask(String)` | SDK/server task command path | External intake-window command | Keep. |

Generic-update call sites retired in EKC-2:

- `MassSdkApplication.updateTaskDefinition(...)` reads a `Task`, mutates
  `project`, `sharedConfig`, and `user`, then called
  `requireStartedTaskCommands().updateTask(task)`. It now calls
  `patchTaskDefinition(...)` with `TaskDefinitionPatch`.
- `MassSdkApplication.resolveWorkerGroupSelectorForAppend(...)` mutates
  `sharedConfig` after event-backed worker-group resolution, then called
  `config.getTaskCommandService().updateTask(task)`. It now persists the
  resolved selector through `patchTaskDefinition(...)`.

EKC-2 added a source guard that scans SDK/server production `.updateTask(...)`
call patterns outside engine-internal allowlists. The guard is intentionally
pattern-based and path-scoped rather than only checking the
`TaskCommandService` class name.

## Task Result Service Method Groups

`TaskResultService` is a result-convergence owner, not a projection owner. Its
size is a risk only where multiple result responsibilities become hard to see.

| Group | Current methods / evidence | Owner meaning | EKC target |
| --- | --- | --- | --- |
| Lease expiry entry | `expireLeasedWork(...)` | Runtime lease result mutation | Keep as result-entry responsibility; may delegate to convergence owner. |
| Result ingest entry | `TaskManager.ingestTaskResult(...)` delegates into result service | Runtime result ingest | Keep entry narrow via `TaskResultIngestPort` / facade. |
| Visible final commit | `TaskResultVisibleFinalCommitter.commitVisibleFinal(...)` and staged callback cleanup after EKC-3C | Runtime result truth commit | Split landed. Keep commit/staged cleanup lifecycle separate without changing `TaskResultRuntime` as truth source. |
| Attempt closed publish | `publishWorkAttemptClosed(...)`, `publishWorkAttemptClosedOnce(...)`, repair variants | Runtime event publication after convergence | Keep event emission after runtime state is committed; do not route through review DB. |
| Logical final publish | repair methods for missing logical-final publish | Runtime event repair | Keep as repair/replay concern. |
| Repair pump lifecycle | `TaskResultRepairPump` owns scheduled executor, interval/batch config, exception isolation, and shutdown after EKC-3A | Best-effort runtime-result repair scheduling | Split landed. Keep lifecycle separate from convergence logic. |
| Repair business handling | `repairResultRuntimeCandidates(...)`, specific repair handlers in `TaskResultService` | Runtime-result repair execution | Still owned by `TaskResultService`; split only when a convergence owner boundary is proven. |
| Progress application | progress repair/application methods; `TaskManager.applyTaskResultProgressOnce(...)` refreshes task progress even when another caller holds the progress barrier | Runtime progress/state application | Keep through existing runtime/task ports; avoid DB read-model dependency. |
| Projection-era wording residue | `AttemptProjectionView` and active projection helper names in `TaskResultService` before EKC-3B | Naming residue | EKC-3B renamed these to runtime-view terms. Continue avoiding broad churn outside hot-path meaning. |

The split should be responsibility-oriented, not line-count-oriented.

## Worker Command Lifecycle Classification

Current state after EKC-4:

- `WorkerCommandLifecycleOwner` is in `xa-mass-worker-runtime` under
  `com.xa.mass.worker.runtime.command`.
- `EngineConfig` constructs it by default.
- `WorkerControlService` uses it for request, acknowledgement, status query,
  and trace emission.
- `WorkerCommandDeliveryCoordinator` uses it for claim/delivery lifecycle.
- `WorkerCommandMaintenanceWatchdog` expires due commands through the owner.
- SDK operations expose command request/status/query APIs through
  `MassSdkApplication` and `MassApplication`.

Evidence for worker-runtime ownership:

- The owner stores worker-scoped command records and indexes by `workerId`.
- Command lifecycle is independent from task result convergence and scheduling
  assignment.
- Worker capability authority and worker resource/state projection already live
  in `xa-mass-worker-runtime`.

Evidence for engine ownership:

- Current delivery handoff is coupled to engine trace logger and
  `WorkerControlService`.
- Dispatch availability side effects remain engine dispatch-control policy.
- The maintenance watchdog is currently assembled with engine startup.

EKC-4 decision:

`WorkerCommandLifecycleOwner` state is worker-runtime lifecycle truth. Command
records, request/acknowledgement/result/status types, and command delivery port
contracts moved with the owner. Engine keeps dispatch-control side effects,
delivery coordination, trace emission, and maintenance scanning as explicit
runtime assembly.

## Review Materialization Trigger Classification

Server review materialization is server-owned and best-effort. It must not feed
engine runtime decisions.

| Trigger | Current location | Current profile | EKC classification |
| --- | --- | --- | --- |
| `recordItemsAccepted(...)` | `TaskApiController` after item append receipt | API path calls writer; writer records in `TERMINAL`/`DIAGNOSTIC` | Default/terminal-review fact. Keep as accepted item summary materialization, subject to task policy. |
| `recordWorkFinal(...)` | `XaMassServerApplication.taskReviewReadModelFinalityListener`, `dev` profile | Listener calls writer; writer records in `TERMINAL`/`DIAGNOSTIC` | Default review fact. Production-oriented `TERMINAL` mode keeps this row. |
| `recordAttemptClosed(...)` | `XaMassServerApplication.taskReviewReadModelAttemptClosedListener`, `dev` profile | Listener calls writer; writer records only in `DIAGNOSTIC` | Diagnostic materialization. Default `TERMINAL` skips it with queue stats in debug logs. |

Proposed modes:

- `OFF`: no server review rows for the task
- `TERMINAL`: accepted item summary and terminal work rows
- `DIAGNOSTIC`: accepted, attempt/intermediate, and terminal rows

`TERMINAL` should be the production-oriented default. Dev/test may default to
`DIAGNOSTIC` when proof or debugging value is higher than write cost.

## Task-Level Review Policy Placement

Current facts after EKC-5:

- Server review materialization owns `TaskReviewMaterializationPolicy` and
  `TaskReviewMaterializationMode`.
- `TaskSharedConfig` remains free of review materialization policy constants;
  server mainline checkstyle also forbids direct base-model imports here.
- `TaskSharedConfig` has `_sdk.eventCode` for SDK metadata.
- The task-level override is the top-level conventional shared-config key
  `reviewMaterializationMode`, interpreted only by server review
  materialization.
- Server review materialization is outside engine and is not a transport
  protocol field.

EKC-5 decision:

Use top-level `reviewMaterializationMode` as a server-owned task policy
override. It does not live under `_sdk`, because the policy is a
platform/server read-model choice, not SDK metadata. A dedicated DB task-policy
field is premature until there is a persistence/query requirement beyond
task-local policy override.

Parsing now lives at the server review writer boundary, not in engine result
convergence. `TERMINAL` is the default. `DIAGNOSTIC` enables attempt/intermediate
materialization. `OFF` disables review-row materialization for that task.

## EKC-0 Residue And Follow-Up

- EKC-1 should focus on SDK assembly imports. Server production is already
  clean. Remaining EKC-1 work is `EngineConfig`/builder extension contracts:
  matching strategy, assignment diagnostics, worker control, stage evidence,
  and rule provider/evaluator assembly. Any new SDK main import from engine
  must be added to the guard allowlist with an inventory classification.
- EKC-2 has converged the SDK task-definition path onto
  `patchTaskDefinition(...)` and guards `.updateTask(...)` call patterns
  outside engine-internal allowlists.
- EKC-3 should split `TaskResultService` only along real result lifecycle
  responsibilities.
- EKC-4 should execute the worker-command owner decision after confirming that
  scheduler correctness does not depend on command lifecycle state living in
  engine.
- EKC-5 made review materialization policy configurable and defaulted to
  terminal-oriented materialization outside diagnostic profiles.
- EKC-6 moved `InMemoryTaskShellStore` implementation/index tests to
  `mass-storage-memory`, replaced engine test usage of storage-memory
  task-shell, worker-declaration, and rule-definition implementations with
  engine-owned fixtures, and removed the engine test dependency on
  `mass-storage-memory`.
