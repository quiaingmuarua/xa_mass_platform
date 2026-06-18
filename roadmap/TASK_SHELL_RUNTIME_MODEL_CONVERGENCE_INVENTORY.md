# Task Shell Runtime Model Convergence Inventory

Status: initial code-facing inventory for
`TASK_SHELL_RUNTIME_MODEL_CONVERGENCE_ROADMAP.md`.

This inventory classifies the current `base.model.Task` usage before any
contract move. It exists because the current `Task` model is not one fact: it
is a task shell row, lifecycle mutation object, runtime aggregate cache,
scheduling input carrier, result-convergence context, and public embedded-SDK
return value in one mutable class.

## Current Implementation Notes

- `com.xa.mass.base.model.Task` still exists as the shared task aggregate.
- `TaskShellRuntimeStore` in `xa-mass-kernel-spi` and `TaskShellStore` in
  `platform_infra/mass-storage-api` both accept and return `Task`.
- `TaskWorkRuntime` owns runtime work queue, delayed visibility, active leases,
  result apply, retry, expiry, and runtime stats.
- `TaskResultRuntime` owns staged callbacks, visible final rows, result
  windows, and result-side publish/progress barriers.
- `RuntimeTaskIngressItem` already strips runtime payload from compatibility
  projection shape, so the runtime-work direction is narrower than the current
  task shell model.
- Worker model convergence has already moved away from `base.model.Worker`:
  worker-runtime declaration stores use `WorkerDeclarationRecord`, and guards
  reject returning or accepting `base.model.Worker`.

## Field Disposition Inventory

| `Task` member | Current Meaning | Target Owner | Target Writer | Current Main Readers | Migration Slice |
| --- | --- | --- | --- | --- | --- |
| `tid` | Task identity | task shell record | task shell create command | engine, storage, SDK/server snapshots, trace | TMC-1/TMC-2 |
| `tenantId` | Tenant/control-plane partition evidence | task shell record | task shell create/stamping owner | storage, server/SDK read models | TMC-1/TMC-2/TMC-5 |
| `taskName` | Human/control-plane task label | task shell record | task shell create/update command if kept mutable | storage, SDK/server read models | TMC-1/TMC-2/TMC-5 |
| `contract` | Public preset/read-evidence shape | task shell record as persisted preset input; scheduling boundary resolves it | task shell create command | policy resolver, SDK/server snapshots | TMC-1/TMC-4/TMC-5 |
| `project` / `ProjectRef` | Project/workload owner and capability binding input | task shell record | task shell create command | policy resolver, worker scheduling policy, SDK/server snapshots | TMC-1/TMC-2/TMC-4 |
| `status` | Shell lifecycle state | engine lifecycle owner, persisted on shell record | `TaskLifecycleService` / `TaskStateResolver` / assignment owner | engine gates, SDK/server snapshots, storage queries | TMC-2/TMC-3 |
| `taskTargetNumber` | Initial expected item count | shell aggregate read evidence | task shell create or append/intake owner | progress/read models, tests | TMC-0 decision, TMC-3 |
| `taskEligibleNumber` | Current eligible aggregate count | shell aggregate read evidence or derived ingest evidence | append/intake owner or runtime-derived reconciler | progress/read models, terminal policy context | TMC-0 decision, TMC-3 |
| `taskSuccessNumber` | Progress cache from runtime final results | derived shell read evidence | `TaskStateResolver` from `TaskWorkRuntime.stats(taskId)` | progress/read models, terminal policy context | TMC-3 |
| `taskNonSuccessNumber` | Derived non-success cache | derived shell read evidence | recomputed by engine progress owner | schedulability/progress/read models | TMC-3 |
| `minRequiredWorkerCount` | Current scheduling/assignment input | resolved task scheduling policy input or removal candidate | task shell create/policy resolver only if retained | `ResolvedTaskSchedulingPolicy`, worker budget/assignment | TMC-0 decision, TMC-4 |
| `peakAssignedWorkerCount` | Assignment diagnostic/read evidence | assignment diagnostics or bounded shell read evidence | assignment owner | SDK/server snapshots, trace/tests | TMC-0 decision, TMC-3/TMC-5 |
| `sharedConfig` | Generic payload/config plus legacy routing keys | task shell record stores raw config; scheduling boundary extracts resolved intent/policy | task shell create command | `TaskDispatchIntent`, worker scheduling, SDK/server snapshots | TMC-1/TMC-4 |
| `holdReason` | Blocked-state reason | engine lifecycle owner, persisted on shell record if retained | `TaskLifecycleService` | lifecycle/read models | TMC-3/TMC-5 |
| `executionSpec` | Runtime/policy preset input such as batch/retry/foreground | task shell record as input; scheduling boundary resolves it | task shell create/update command | `TaskPolicyPresetResolver`, runtime enqueue/claim/retry policy | TMC-1/TMC-4 |
| `sourceRef` | External/control-plane source evidence | task shell record | task create/stamping owner | server/SDK read models, import/bootstrap | TMC-1/TMC-2/TMC-5 |
| `intakeStatus` | Append-window truth | engine lifecycle/intake owner, persisted on shell record | `TaskLifecycleService` | append gate, idle close/terminal policy, read models | TMC-3 |
| `user` / `UserRef` | Submitter/control-plane ownership evidence | task shell record | server/SDK task create stamping owner | server auth/view, SDK snapshots | TMC-1/TMC-5 |
| `createTime` | Shell creation timestamp | task shell record | storage/create command | read models, ordering/listing | TMC-1/TMC-2 |
| `updateTime` | Shell mutation timestamp | task shell record | storage adapter or engine mutation owner by explicit decision | read models, ordering/listing | TMC-1/TMC-2/TMC-3 |
| `startTime` | First RUNNING timestamp | engine lifecycle/assignment evidence on shell record | lifecycle/assignment owner | read models, trace | TMC-3 |
| `endTime` | Terminal timestamp | engine terminal owner on shell record | terminal convergence owner | read models, trace | TMC-3 |
| `terminalReason` | Terminal policy result | engine terminal owner on shell record | `TaskStateResolver` / cancel/reject owner | read models, trace | TMC-3 |
| `transitionTo(...)` / `transitionToBlocked(...)` | Lifecycle state-machine mutation embedded in base model | engine lifecycle mutation helper/service | engine owner only | engine lifecycle/assignment/state resolver | TMC-3 |
| `sealIntake()` / `setIntakeStatus(...)` | Intake-window mutation embedded in base model | engine intake/lifecycle owner | `TaskLifecycleService` | append/seal/idle-close paths | TMC-3 |
| `isSchedulable()` / `isCompleted()` / `getProgressPercentage()` | Derived convenience methods on fat model | engine/read-model derived helpers | no persistent writer | scheduling gates, snapshots/tests | TMC-3/TMC-5 |

## Symbol Inventory

| Symbol | Current Owner | Current Usage | Classification | Target |
| --- | --- | --- | --- | --- |
| `com.xa.mass.base.model.Task` | base shared model | Storage row, engine mutable shell, lifecycle state machine, aggregate counters, SDK/starter return value, trace/event context | fat composite model | TMC-1/TMC-2/TMC-3/TMC-5/TMC-6 |
| `Task#transitionTo(...)` | base model method | Engine lifecycle, state resolver, assignment listener | engine lifecycle mutation embedded in shared model | TMC-3 |
| `Task#sealIntake()` / `Task#intakeStatus` | base model method/field | Append-window truth and terminal close behavior | task shell field with engine-owned mutation | TMC-1/TMC-3 |
| `Task#taskTargetNumber` / `taskEligibleNumber` | base model fields | Append/create shell counters | shell aggregate read evidence, not runtime queue truth | TMC-1/TMC-3 |
| `Task#taskSuccessNumber` / `taskNonSuccessNumber` | base model fields | Progress cache updated from runtime stats | derived shell read evidence | TMC-1/TMC-3 |
| `Task#minRequiredWorkerCount` | base model field | Historical assignment/scheduling input | likely scheduling-policy residue | TMC-0/TMC-3 |
| `Task#peakAssignedWorkerCount` | base model field | Assignment diagnostic read evidence | bounded assignment read evidence | TMC-3 |
| `Task#executionSpec` | base model field | Runtime profile, retry defaults, scheduling policy resolution | shell scheduling input/read evidence | TMC-1/TMC-3 |
| `Task#contract` | base model field | Public preset/read evidence and scheduling-policy input | public preset/read evidence | TMC-1/TMC-5 |
| `Task#sharedConfig` | base model field | Generic task config and legacy scheduling inputs | shell config with resolved-policy owner outside the record | TMC-1/TMC-3 |
| `TaskShellRuntimeStore` | kernel SPI | Runtime-kernel shell CRUD by id | correct port name, wrong fat model payload | TMC-2 |
| `TaskShellRuntimeLifecycleQuery` | kernel SPI | Bounded max-runtime shell scan | correct bounded shell query, wrong fat model payload | TMC-2 |
| `TaskShellStore` | storage API | Storage CRUD/list for task shell rows | storage adapter contract using fat domain model | TMC-2 |
| `TaskCommandPort` | engine public port | `createTaskShell(...)` returns `Task`; `updateTask(Task)` writes whole model | command surface leaks fat model | TMC-3/TMC-5 |
| `TaskQueryPort` | engine public port | `getTask(taskId)` returns `Task` | query surface leaks fat model | TMC-5 |
| `TaskStateRuntimePort` | engine internal port | `getTask(taskId)` and terminal policy evaluation consume `Task` | state/terminal owner depends on fat model | TMC-3 |
| `TaskRuntimeRecoveryPort` | engine internal port | `getRuntimeDispatchableTasks(limit)` returns `List<Task>` | recovery/dispatch scan leaks fat model | TMC-4 |
| `TaskAssignmentRuntimePort` | engine internal port | assignment update and compensation accept `Task` | assignment owner depends on whole shell | TMC-3/TMC-4 |
| `TaskDispatchWakeupPort` | engine internal port | wakeup accepts `Task` | dispatch signal can be task id/resolved cadence | TMC-4 |
| `TaskWorkRuntime` | runtime API | Ready work, active leases, retry, result apply, stats | runtime truth | Allow; keep independent of shell record |
| `TaskResultRuntime` | runtime API | Visible final result rows, callback staging, barriers | result runtime truth | Allow; keep independent of shell record |
| `TaskManager` | engine | Composition root and many task ports | owner assembly plus over-wide mutable model traffic | TMC-3/TMC-4 |
| `TaskLifecycleService` | engine | Approval/block/pause/resume/cancel/append/seal | engine lifecycle owner using fat task model | TMC-3 |
| `TaskStateResolver` | engine | Runtime stats -> shell progress and terminal convergence | engine progress/terminal owner | TMC-3 |
| `TaskWorkerAssignListener` | engine | READY->RUNNING and peak assigned worker count | assignment mutation owner | TMC-3 |
| `TaskResultService.RuntimeWorkSummary` | engine | Rebuilds runtime work/lease/final receipt into message/attempt view | result event/read-model projection residue | TMC-4 |
| `TaskWorkLifecycleState` | engine | Message/attempt status vocabulary for trace/events | event/trace vocabulary, not runtime truth | TMC-4 |
| `TaskCommandService#createTaskShell` | engine public surface | Returns `Task` | embedded engine control surface leaks fat model | TMC-5 |
| `TaskQueryService#getTask` | engine public surface | Returns `Task` | embedded engine query surface leaks fat model | TMC-5 |
| `SchedulingPlaneResolver#resolve(Task)` | engine scheduling boundary | Accepts whole `Task` and returns resolved views | boundary parser is correct owner, payload is too broad | TMC-4 |
| `TaskPolicyPresetResolver` | engine scheduling strategy | Reads `Task.contract` / `executionSpec` / runtime profile input | scheduling preset resolver | TMC-4 |
| `TaskDispatchIntent#fromTask` | engine scheduling value factory | Extracts `taskId`, project, event/routing/target fields from `Task.sharedConfig` | boundary parser from shell config | TMC-4 |
| `ResolvedTaskSchedulingPolicy#from(Task, ...)` | engine scheduling value factory | Reads `taskId`, preset resolution, execution spec, min worker count | resolved policy factory still coupled to fat shell | TMC-4 |
| `ResolvedWorkerSchedulingPolicy` | engine scheduling value | Already consumes `TaskDispatchIntent` instead of whole `Task` | target worker-universe value | Allow; keep as resolved value |
| `MassEngine#createTaskShell` | embedded SDK starter | Returns `Task` | public/starter leak of internal shell model | TMC-5 |
| SDK task snapshots (`TaskShellSnapshot`, `TaskDetailSnapshot`, `TaskSummarySnapshot`) | SDK/API read models | Server/API response assembly and tests | target public read-model family | Allow; retarget callers here |
| Server `ApiTask*` records | server API | HTTP response contracts | public API DTOs | Allow; must not consume runtime truth directly |

## Engine Port Inventory

| Port | Current Fat Shape | Target Shape | Notes |
| --- | --- | --- | --- |
| `TaskCommandPort` | returns `Task`; accepts `Task` for generic update | create command returns shell record/snapshot/outcome; updates are command-specific or shell-record writes owned by engine | Do not keep `updateTask(Task)` as the replacement escape hatch. |
| `TaskQueryPort` | returns `Task` | return shell record for internal callers or snapshot for public/starter callers | Public SDK/server should not depend on mutable shell record. |
| `TaskStateRuntimePort` | returns `Task`; terminal policy accepts `Task` | terminal policy consumes shell lifecycle fields plus `TaskWorkStats` | Progress and terminal truth still come from runtime stats. |
| `TaskRuntimeRecoveryPort` | returns `List<Task>` | return task ids, bounded dispatch signals, or shell records plus pre-resolved policy inputs only where needed | Recovery should not become a scan-heavy fat-model reader. |
| `TaskAssignmentRuntimePort` | assignment update/compensation accepts `Task` | assignment owner consumes task id, selected worker/binding evidence, and resolved policy/context | Assignment writes such as RUNNING/start/peak counters stay owner-explicit. |
| `TaskDispatchWakeupPort` | accepts `Task` | accept task id plus dispatch cadence/intent only if needed | Wakeup is a signal, not a shell ownership boundary. |

## Storage And Kernel Shell Boundary

| Contract | Current Owner Role | Current Shape | Target Decision |
| --- | --- | --- | --- |
| `TaskShellRuntimeStore` | kernel runtime port consumed by engine/runtime assembly | CRUD by id using `base.model.Task` | Retarget to the same narrow shell record as `TaskShellStore` in TMC-2. |
| `TaskShellRuntimeLifecycleQuery` | kernel runtime bounded lifecycle query | lifecycle scan returns `Task` | Retarget to narrow shell record or bounded shell dispatch references in TMC-2/TMC-4. |
| `TaskShellStore` | storage adapter surface for control-plane task shell rows | CRUD/list/status/project using `base.model.Task` | Retarget in the same TMC-2 slice; storage is adapter, not a second shell truth owner. |
| Memory/JDBC implementations | adapters that currently implement both runtime SPI and storage API | one class maps and persists `Task` | Implement one shell-record mapping; do not leave runtime SPI and storage API on different task shapes. |

## SDK And Server Surface Inventory

| Surface | Current Shape | Classification | Target |
| --- | --- | --- | --- |
| `MassSdkApplication` public task create/query | public reads are largely snapshot-shaped, but implementation still reads storage task rows | public snapshot surface with internal fat-model dependency | Keep snapshots; retarget internals after shell store moves. |
| `MassEngine#createTaskShell` / engine starter command path | returns `Task` | starter/internal fat return | Return shell snapshot/record/outcome depending on caller ownership in TMC-5. |
| `MassApplication` assembly | reads `TaskShellStore` directly for internal conversion paths | assembly compile surface | Retarget to shell record and snapshot mapper. |
| `xa-mass-server` API controllers | mostly consume SDK operations and `ApiTask*` DTOs | server API/read-model consumer | Keep API DTOs stable unless a slice explicitly changes HTTP contracts. |
| `XaMassServerApplication` storage wiring | injects/constructs `TaskShellStore` | server assembly/storage wiring | Update with the storage contract in TMC-2; prove startup if Spring assembly changes. |
| Tests using `new Task()` or direct store mutation | fixture and support coverage | test residue | Retarget after production surfaces move; do not let tests preserve old API. |

## Dependency Inventory

| Module | Current Dependency/Shape | Reason | Classification | Target |
| --- | --- | --- | --- | --- |
| `xa-mass-base` | owns `Task` | Historical shared model | fat base model | remove or legacy-only after TMC-6 |
| `xa-mass-kernel-spi` | imports `base.model.Task` | Shell runtime store/query payload | kernel SPI leak of fat model | define/import narrow shell record |
| `platform_infra/mass-storage-api` | imports `base.model.Task` | Storage shell store payload | storage adapter leak of fat model | use shell record contract |
| `platform_infra/mass-storage-memory` | persists `Task` | In-memory shell store | adapter implementation | retarget to shell record |
| `platform_infra/mass-storage-jdbc` | persists `Task` | JDBC shell store | adapter implementation | retarget to shell record and SQL mapper |
| `xa-mass-engine` | imports `Task` broadly | lifecycle, assignment, result, trace, policy | engine mutation/runtime context | retarget hot paths to task id, shell record, resolved policies, runtime values |
| `sdk/xa-mass-embedded-sdk` | imports `Task` in starter/API tests and some public paths | embedded engine task commands/query | public surface leak | use SDK task snapshots and intent-shaped commands |
| `xa-mass-server` | mostly uses SDK snapshots/API DTOs; tests sometimes access storage task rows | API assembly and E2E support | read-model consumer plus test fixture residue | keep API DTOs; retarget fixtures after storage move |

## Guard Inventory

| Guard | Current Coverage | Gap | Target |
| --- | --- | --- | --- |
| `ModelMutationGuardTest` | Blocks direct `Task#setStatus`, terminal reason setters, some lifecycle field writes, and legacy `TaskMsg` dependencies | Still allows fat `Task` through shell ports and many production callers | Extend in TMC-3/TMC-6 |
| `EngineProofOwnershipGuardTest` | Prevents compatibility projection writes and storage imports in engine | Does not ban `base.model.Task` because it is current mainline | Add after shell record retarget |
| `StorageBoundaryGuardTest` | Prevents storage projection/runtime/history methods | Does not prevent `TaskShellStore` from carrying fat `Task` | Add shape guard after TMC-2 |
| `WorkerDeclarationBoundaryGuardTest` | Prevents `base.model.Worker` on worker declaration store and runtime/history fields in worker records | Worker-only precedent | Mirror for task shell store after TMC-2 |
| SDK worker shape guards | Assert old `base.model.Worker` / `WorkerContext` are absent | Worker-only precedent | Add SDK task shape guard after TMC-5 |

## Decisions

- Treat `Task` as a legacy fat model, not the target task-runtime contract.
- Do not replace one fat model with another under an engine package name.
- The target storage/control-plane payload is a narrow task shell record.
- `TaskShellRuntimeStore` is the engine/kernel runtime port and
  `TaskShellStore` is the storage adapter surface; TMC-2 must retarget both to
  the same shell record in one slice to avoid dual shell truth.
- Runtime work and result truth stay in `TaskWorkRuntime` and
  `TaskResultRuntime`; they must not be folded into a task shell record.
- Engine owns lifecycle mutation and terminal/progress convergence; those
  rules should live in engine services or engine-local mutation helpers, not in
  a shared base model.
- Scheduling policy parsing is a boundary operation. `TaskContract`,
  `executionSpec`, and `sharedConfig` may be persisted shell inputs, but hot
  paths should consume `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`,
  and `ResolvedWorkerSchedulingPolicy` instead of re-reading a whole `Task`.
- Public SDK/server callers should consume intent-shaped commands and snapshot
  read models, not mutable engine/base aggregates.
