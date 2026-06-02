# Task Worker Runtime History Boundary Inventory

Status: TWH-0 committed inventory.

Parent roadmap: [TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_ROADMAP.md](./TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_ROADMAP.md)

This inventory classifies current callers before implementation slices rename
storage contracts or move runtime-shaped methods. It is intentionally about
current code, not target-state claims.

## Summary Decision

Target names for the first convergence pass:

| Current name | Target name | Owner meaning |
|---|---|---|
| `TaskStorage` | `TaskShellStore` | stable task shell/control-plane truth |
| `WorkerStorage` | `WorkerDeclarationStore` | stable worker declaration/control-plane truth |
| `InMemoryTaskStorage` | `InMemoryTaskShellStore` | in-memory task shell store, still also a compatibility `TaskDetailStore` |
| `JdbcTaskStorage` | `JdbcTaskShellStore` | JDBC task shell store, still also a compatibility `TaskDetailStore` |
| `InMemoryWorkerStorage` | `InMemoryWorkerDeclarationStore` | in-memory worker declaration store |

`TaskStorage.getSchedulableTasks()` is runtime-shaped residue and must not
remain on the renamed task shell contract.

`TaskStorage.pollExpiredMaxRuntimeTasks(...)` is a current task-shell lifecycle
query. It may remain backed by shell storage, but it must move behind a
lifecycle-specific owner/port name rather than being presented as runtime queue
or lease truth.

Current JDBC storage provides task shell persistence and JDBC-local
compatibility task-detail projections only. No JDBC worker runtime/history
storage implementation exists in this inventory.

## Task Storage Type Callers

| Caller group | Files | Classification | Disposition |
|---|---|---|---|
| Contract | `platform_infra/mass-storage-api/.../TaskStorage.java` | task shell store plus runtime-shaped residue | Rename to `TaskShellStore`; remove schedulable admission from contract |
| Memory implementation | `platform_infra/mass-storage-memory/.../InMemoryTaskStorage.java` | task shell store plus compatibility detail store | Rename to `InMemoryTaskShellStore`; retain explicit `TaskDetailStore` implementation |
| JDBC implementation | `platform_infra/mass-storage-jdbc/.../JdbcTaskStorage.java`, `JdbcStorageRuntime.java` | task shell store plus compatibility detail store | Rename to `JdbcTaskShellStore`; no worker runtime/history expansion |
| Engine owner | `xa-mass-engine/.../TaskManager.java` | task shell commands/queries, runtime recovery, lifecycle maintenance | Keep shell reads/writes through renamed shell store; move runtime-shaped recovery to runtime-first path |
| Engine tests | `xa-mass-engine/src/test/...` files importing `TaskStorage` or `InMemoryTaskStorage` | test fixtures and boundary proof | Rename with production surfaces; tests must not preserve old compatibility names |
| Storage contract tests | `platform_infra/mass-storage-api/src/test/.../TaskStorageContractTest.java` and memory/JDBC subclasses | shell-store contract proof plus residue tests | Rename to task-shell contract; remove schedulable contract test when TWH-2 lands |
| SDK assembly | `MassSdk`, `EngineConfig`, `MassApplicationBuilder`, `MassEngineBuilder` | embedding configuration surface | Rename API in TWH-1B; preserve explicit task shell/detail-store alias break behavior |
| Server assembly | `XaMassServerApplication`, server E2E support | backend assembly and fixtures | Rename bean/config types with production surfaces |
| Testing utilities | `xa-mass-testing/...`, transport runtime tests | local fixture assembly | Rename with target store names; no runtime decision through shell storage |

## TaskStorage Method Classification

| Method | Current owner | Classification | TWH disposition |
|---|---|---|---|
| `saveTask(Task)` | `TaskManager.createTaskShellInternal(...)` and tests | task shell command | Keep on `TaskShellStore` |
| `getTask(String)` | task query, runtime recovery bounded lookup, tests | current task shell lookup | Keep on `TaskShellStore`; allowed after runtime ids are selected |
| `updateTask(Task)` | lifecycle state transitions and tests | task shell command | Keep on `TaskShellStore` |
| `deleteTask(String)` | lifecycle delete and tests | task shell command | Keep on `TaskShellStore` |
| `listTasksPaged(int,int)` | task query/read model and tests | current shell/support view | Keep as shell query; not history/analytics |
| `getTasksByStatus(TaskStatus)` | task query/read model and tests | current shell/support view | Keep as shell query; not history/analytics |
| `getTasksByProject(String)` | task query/read model and tests | current shell/support view | Keep as shell query; not history/analytics |
| `getSchedulableTasks()` | storage implementations, storage tests, old `TaskManager.getSchedulableTasks()` | runtime scheduling-admission residue | Remove from storage contract in TWH-2; runtime dispatch recovery must start from `TaskWorkRuntime.readyTaskIds(limit)` |
| `pollExpiredMaxRuntimeTasks(LocalDateTime,int)` | watchdog through `TaskManager`, storage implementations/tests | current task-shell lifecycle maintenance | Move behind lifecycle-specific owner/port; not queue/lease runtime truth and not history |

## Worker Storage Type Callers

| Caller group | Files | Classification | Disposition |
|---|---|---|---|
| Contract | `platform_infra/mass-storage-api/.../WorkerStorage.java` | worker declaration/control-plane row store | Rename to `WorkerDeclarationStore` |
| Memory implementation | `platform_infra/mass-storage-memory/.../InMemoryWorkerStorage.java` | in-memory worker declaration store | Rename to `InMemoryWorkerDeclarationStore` |
| Worker runtime owner | `WorkerResourceOwner`, `WorkerManager` | declaration rows plus current registry projection | Rename dependency; TWH-3 splits declaration shape from runtime/current projection |
| SDK assembly | `MassSdk`, `EngineConfig`, starter builders | embedding configuration surface | Rename API in TWH-1B |
| Worker-runtime tests | `WorkerManagerTest` | fixture and current-state proof | Rename with production surfaces |
| Engine/transport tests | matching, control, transport routing tests | fixture setup for worker runtime | Rename fixture types; do not make worker declaration store scheduling truth |
| Storage contract tests | `WorkerStorageContractTest`, memory subclass | worker declaration contract proof | Rename to declaration-store contract |
| Testing runners | perf/load runners | local fixture assembly | Rename with target declaration-store names |

## WorkerStorage Method Classification

| Method | Current owner | Classification | TWH disposition |
|---|---|---|---|
| `addWorker(Worker)` | `WorkerResourceOwner.addWorker(...)`, tests | worker declaration command plus current registry projection trigger | Rename on declaration store; TWH-3 changes input to declaration-shaped record |
| `getWorker(String)` | worker query/runtime owner | declaration lookup used to assemble current-state views | Rename on declaration store; must not carry active runtime truth after TWH-3 |
| `updateWorker(Worker)` | worker update/runtime owner | worker declaration command plus current registry projection trigger | Rename on declaration store; TWH-3 changes input to declaration-shaped record |
| `deleteWorker(String)` | worker delete/runtime owner | worker declaration command plus registry slot removal trigger | Rename on declaration store |
| `getWorkersByGroupId(String)` | declaration query/tests | worker declaration query | Rename on declaration store |
| `getAllWorkers()` | `WorkerResourceOwner.getAllWorkers()`, `WorkerManager.workers()`, tests | declaration scan used to assemble current-state/support views | Rename on declaration store; no hot-path scheduling caller |

## TaskRuntimeMaintenancePort Classification

| Method | Current interface | Classification | Target direction |
|---|---|---|---|
| `getActiveLeases(String)` | `TaskRuntimeMaintenancePort` | lease runtime maintenance | `TaskLeaseMaintenancePort` |
| `pollExpiredLeases(int, Instant)` | `TaskRuntimeMaintenancePort` | lease runtime maintenance | `TaskLeaseMaintenancePort` |
| `expireLeasedWork(String,String)` | `TaskRuntimeMaintenancePort` | lease runtime maintenance/result convergence trigger | `TaskLeaseMaintenancePort` |
| `hasDispatchReadyWork(String)` | `TaskRuntimeMaintenancePort` | dispatch readiness/current runtime state | `TaskDispatchWakeupPort` |
| `hasActiveWorkForWorker(String,String)` | `TaskRuntimeMaintenancePort` | runtime active-work state | lease/runtime state owner |
| `requestTaskDispatch(Task)` | `TaskRuntimeMaintenancePort` | dispatch wakeup/request | `TaskDispatchWakeupPort` |
| `pollExpiredMaxRuntimeTasks(LocalDateTime,int)` | `TaskRuntimeMaintenancePort` | current task-shell lifecycle maintenance | `TaskShellLifecycleMaintenancePort` |
| `terminateTask(String, TaskTerminalReason)` | `TaskRuntimeMaintenancePort` | current task-shell lifecycle command | `TaskShellLifecycleMaintenancePort` or existing command/lifecycle port |

## Task Field Classification

| Field group | Classification | Notes |
|---|---|---|
| `tid`, project, event code, contract, intake status, shared config, create/update timestamps | stable task shell/control-plane truth | Owned by task shell store and lifecycle services |
| runtime item payloads and payload refs | runtime/input boundary | Ingested explicitly through runtime item APIs |
| ready/delayed/lease/counter state | runtime current state | Owned by `TaskWorkRuntime` |
| result apply window/final receipts | runtime result state | Owned by `TaskResultRuntime`; review/export materialization is not result truth |
| cross-task timelines, attempts, dispatch history, analytics | history/read model | Trace -> queue -> archive, not shell storage |

## Worker Field Classification

| Field group | Classification | Notes |
|---|---|---|
| `workerId`, `workerGroupId`, `adapterNodeId`, `adapterId`, online strategy/transport hint, static attributes, declared max concurrency, create/update timestamps | stable worker declaration candidates | Target declaration-store truth |
| `status`, `lastHeartbeat`, active reachability, active dispatch gate, active reservation/load | runtime/current state | Must not be declaration-store truth after TWH-3 |
| supported project/event hints on worker row | compatibility projection/read hint | WorkerGroup remains capability truth |
| connection timeline, heartbeat stream, dispatch/result/candidate history | history/read model | Trace -> queue -> archive |

## Server And SDK Read Models

| Surface | Current classification | TWH disposition |
|---|---|---|
| task list/status/project reads | current shell/support read model | Keep current-state wording; do not describe as durable history |
| task result window/detail projections | task-local result/debug read model with compatibility residue | Keep separate from runtime decisions |
| worker registration/query helpers | declaration plus current-state composite | TWH-3 labels declaration/runtime/transport fields explicitly |
| worker state/capability reports | bounded current diagnostic evidence | Not durable worker history/analytics |
| worker command/status endpoints | runtime command/current-state surface | Do not route through declaration store as scheduling truth |

## Deferred Or Non-Implementation Items

- No task shell query in this inventory is classified as history-shaped. If
  later review changes that classification, the method must be deferred to
  archive/read-model ownership instead of moved into another DB query surface.
- No current JDBC worker runtime/history store exists.
- TWH-5 trace/archive work remains a design checkpoint and must not be used to
  justify broadening task shell or worker declaration storage.
