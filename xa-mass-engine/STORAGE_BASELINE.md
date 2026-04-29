# Storage Baseline

Status: current engine storage baseline.

This document describes the current storage abstraction used by the active engine mainline.

## Scope

The active engine storage layer is split into three interfaces:

- `TaskStorage`
- `WorkerStorage`
- `RuleStorage`

These interfaces are used by:

- `TaskManager`
- `WorkerManager`
- `RuleManager`

Those shared storage contracts now live in
`platform_infra/mass-storage-api`. `platform_infra/mass-storage-memory` now
owns the active in-memory task/worker implementations, while
`xa-mass-engine` still owns `InMemoryRuleStorage`.

The storage boundary now has three distinct roles:

- control-plane storage truth
  - durable `Task` shell truth
  - durable worker / worker-context definition truth
  - durable rule truth
- runtime truth
  - ready work, delayed work, lease ownership, expiry, counters, and
    backpressure owned by `TaskWorkRuntime`
- compatibility projection and audit helpers
  - bounded `TaskMsg` / `TaskMsgAttempt` views still used by engine
    convergence, validation, tests, and shell/demo surfaces while the kernel
    continues to shrink away from full message-owned storage

Do not collapse these back into one "storage owns everything" model.

## Current Wiring Reality

Current behavior:

- engine default constructors wire memory-backed task/worker/rule storage directly
- `xa-mass-server` can opt into JDBC-backed `TaskStorage`, `WorkerStorage`, and `RuleStorage`
  with `mass.storage.mode=jdbc-h2` or `mass.storage.mode=jdbc-postgres`; this is a `platform_infra/mass-storage-jdbc` adapter path, not an engine storage default
- Redis placeholder storage classes still exist under engine legacy storage code, but they are not part of the active wiring path

That means the SDK/engine default mainline is explicit memory-backed storage,
while the server shell has a focused H2 path for local and CI persistence
verification.

## JDBC Adapter Boundary

The JDBC path owned by `platform_infra/mass-storage-jdbc` is for durable control-plane truth. H2 is the
local/CI verification dialect; PostgreSQL is the intended durable mainline. It
is not a task-message analytics backend.

Keep this boundary narrow:

- dialect-specific behavior must stay behind JDBC adapter classes
- upper layers should depend on storage interfaces and JDBC-style semantics, not
  dialect-specific SQL behavior
- the server JDBC adapter persists `Task` truth only; `TaskMsg` and
  `TaskMsgAttempt` remain process-local compatibility projection state
- do not add cross-task message-status search, reporting, or failure-analysis
  queries to engine/server storage APIs
- high-volume message history, status distribution, failure analysis, and
  attempt timelines belong in trace, audit sinks, or downstream analytical
  storage

The migration target should remain low-cost replacement of the JDBC dialect,
not a product dependency on one schema flavor's quirks.

## TaskStorage

`TaskStorage` owns task truth plus the narrow compatibility projection APIs
still needed by engine convergence and result repair.

Shell-facing bounded reads should go through `TaskQueryService`, shell/admin
task mutations should go through `TaskCommandService`, and shell/testing
in-process listener registration should go through `TaskEventService`. Those
services keep query/control/event concerns off the broader `TaskManager`
runtime facade.

Main responsibilities:

- save and load `Task`
- update and delete `Task`
- query tasks by status
- expose narrow `TaskMsg` projection helpers still needed by runtime
  convergence
- expose narrow `TaskMsgAttempt` projection helpers still needed by result
  handling
- expose task-message statistics for lifecycle convergence and audit checks

What `TaskStorage` does not own anymore:

- ready-work admission
- in-flight lease ownership
- per-worker active-dispatch truth
- durable hot-path task-message or attempt history

Those hot-path concerns belong to `TaskWorkRuntime` in `platform_infra/mass-runtime-api`, not to `TaskStorage` scans.

Current interface shape:

```java
public interface TaskStorage {
    void saveTask(Task task);
    Optional<Task> getTask(String taskId);
    boolean updateTask(Task task);
    boolean deleteTask(String taskId);
    List<Task> getAllTasks();
    List<Task> getTasksByStatus(TaskStatus status);
    List<Task> getTasksByProject(String project);
    List<Task> getSchedulableTasks();
    List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit);
    void addTaskMessage(String taskId, TaskMsg taskMsg);
    List<TaskMsg> getTaskMessages(String taskId);
    List<TaskMsg> getTaskMessages(String taskId, int limit);
    List<TaskMsg> getNonFinalTaskMessages(String taskId);
    long countTaskMessages(String taskId);
    Optional<TaskMsg> getTaskMessage(String taskId, String messageId);
    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);
    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);
    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId);
    Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId);
    Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId);
    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId);
    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);
    TaskMessageStats getTaskMessageStats(String taskId);
    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId);
}
```

## Projection Classification

Not every `TaskStorage` message-facing method has the same architectural weight.

Keep these distinctions explicit:

- durable task truth
  - `saveTask`, `getTask`, `updateTask`, `deleteTask`
  - `getTasksByStatus`, `getTasksByProject`, `getSchedulableTasks`
  - `pollExpiredMaxRuntimeTasks`
- runtime convergence helpers that still exist on the storage seam
  - `addTaskMessage`, `updateTaskMessage`
  - `addTaskMessageAttempt`, `updateTaskMessageAttempt`
  - `getNonFinalTaskMessages`
  - `getLatestActiveTaskMessageAttempt`
  - `getTaskMessageStats`
  - `getTaskMessageAttemptStats`
- compatibility/demo/audit reads that must stay bounded and must not drive DB
  expansion
  - `getTaskMessages(...)`
  - `countTaskMessages`
  - `getTaskMessage`
  - `getTaskMessageAttempts`
  - `getLatestTaskMessageAttempt`

The second group is still active engine runtime residue. The third group is
shell/test compatibility residue.

Neither group should be used to justify turning JDBC into a hot-path
`TaskMsg`/`TaskMsgAttempt` store.

## Shortest Convergence Path

From the current codebase to a more realistic runtime mainline, the shortest
storage/query convergence path is:

1. keep runtime-essential projection helpers
   - preserve the narrow helpers still used by result handling, runtime lease
     repair, cleanup, and bounded validation
   - examples: `getNonFinalTaskMessages(...)`,
     `getLatestActiveTaskMessageAttempt(...)`,
     `addTaskMessageAttempt(...)`, `updateTaskMessageAttempt(...)`
2. stop treating shell/debug snapshots as a growth surface
   - `TaskQueryService`, SDK query wrappers, and server task-detail endpoints
     may continue to expose bounded compatibility snapshots for now
   - do not expand those reads into pagination, cross-task search, or durable
     JDBC-backed message history
3. move future detail demand off the engine mainline
   - task-detail reconstruction, attempt timelines, and large-scale analytics
     should come from trace or async audit/export sinks
   - task-level shell reads may keep bounded summary/detail snapshots only as
     an operator validation surface

That means the next convergence target is not "make `TaskStorage` query more
message detail". It is "shrink who depends on compatibility projection reads".

Important current usage notes:

- task completion is driven from runtime counters plus persisted logical message outcomes, not just task status
- storage must support `taskId + messageId` lookups because result write-back is keyed that way
- `TaskMessageStats` and `TaskMessageAttemptStats` are read-model and audit surfaces, not queue/lease ownership
- scan-heavy fallback default methods were intentionally removed from the interface; each backend must now opt into these behaviors explicitly instead of inheriting silent O(n) scans
- `getTaskMessages(...)` is a storage-level compatibility/demo snapshot plus temporary internal cleanup helper; shell-facing bounded reads should flow through a dedicated query/read surface rather than expanding the `TaskManager` runtime facade
- the JDBC adapter intentionally keeps `TaskMsg` and `TaskMsgAttempt`
  process-local; after restart, only `Task` truth is recovered from DB
- runtime cleanup paths that only need pending logical messages should use `getNonFinalTaskMessages(...)` instead of materializing the full task-message snapshot
- bounded runtime validation should stay on task/runtime aggregates; explicit `TaskMsg` projection audits are diagnostic-only and may traverse compatibility snapshots
- the in-memory pending-message index updates on every `TaskMsg` status write, removes entries when a message becomes final, and is dropped wholesale when the owning task is deleted; it is a helper index, not a second lifecycle truth
- future task detail should bias toward logs or async write-behind sinks instead of engine-owned full-message query surfaces
- runtime recovery and redispatch should continue to consume explicit runtime
  indexes plus narrow projection helpers such as
  `getNonFinalTaskMessages(...)`, not full task-message enumeration
- shell/admin/detail reads remain validation surfaces. They are not evidence
  that engine needs a durable cross-task message query model

## WorkerStorage

`WorkerStorage` owns worker records, worker-context records, and runtime worker-lock state.

Main responsibilities:

- save and load `Worker`
- query workers by `workerGroupId`
- manage `0..n` `WorkerContext` rows per worker
- enforce `WorkerContext.workerId` as the single ownership truth
- manage runtime worker locks

Current interface shape:

```java
public interface WorkerStorage {
    void addWorker(Worker worker);
    Optional<Worker> getWorker(String workerId);
    boolean updateWorker(Worker worker);
    boolean deleteWorker(String workerId);
    List<Worker> getWorkersByGroupId(String workerGroupId);
    List<Worker> getWorkersBySupportedProject(String project);
    List<Worker> getWorkersBySupportedEventCode(String eventCode);
    List<Worker> getAllWorkers();

    void addWorkerContext(WorkerContext workerContext);
    List<WorkerContext> getWorkerContexts(String workerId);
    Optional<WorkerContext> getWorkerContextById(String workerContextId);
    boolean updateWorkerContextById(String workerContextId, WorkerContext workerContext);
    boolean deleteWorkerContextById(String workerContextId);
    List<WorkerContext> getAllWorkerContexts();

    boolean tryLockWorker(String workerId);
    void unlockWorker(String workerId);
    boolean isLocked(String workerId);
    List<String> getLockedWorkers();
}
```

Important current usage notes:

- `Worker.status` is the single online truth; do not create a second online registry in storage docs or future APIs
- worker candidate composition is owned by `WorkerManager`, not by `WorkerStorage`; storage only exposes stable indexed lookups such as target-id, project, and event-code reads
- worker lock truth lives in `WorkerStorage` and `WorkerManager.isLocked(...)`; the
  server JDBC adapter keeps that lock truth process-local instead of persisting
  lock churn in the control-plane DB
- active mainline is explicitly `0..n` for `WorkerContext`; do not document single-context helpers by `workerId`
- `addWorkerContext(...)` accepts only the `WorkerContext` object; owner `workerId` is read from `workerContext.getWorkerId()`
- ownership mutation is intentionally constrained; updating a context under a different worker is rejected by the in-memory implementation

## RuleStorage

`RuleStorage` owns rule definitions plus registered evaluators.

Main responsibilities:

- CRUD for `RuleDefinition`
- query rules by `RuleType`
- register and resolve `RuleEvaluator`

Current interface shape:

```java
public interface RuleStorage {
    void addRule(RuleDefinition rule);
    Optional<RuleDefinition> getRule(String ruleId);
    boolean updateRule(RuleDefinition rule);
    boolean deleteRule(String ruleId);
    List<RuleDefinition> getAllRules();
    List<RuleDefinition> getRulesByType(RuleType ruleType);
    void addRules(Collection<RuleDefinition> rules);
    void deleteRules(Collection<String> ruleIds);
    void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator);
    Optional<RuleEvaluator> getEvaluator(RuleType ruleType);
    List<RuleType> getRegisteredEvaluatorTypes();
    boolean removeEvaluator(RuleType ruleType);
    void clear();
}
```

Important current usage notes:

- the active rule surface is worker-oriented, not device/token-oriented
- current matching rules rely on `WorkerMatchContext`
- rule-string contracts must follow the live context keys such as `workerAttributes` and `workerContextAttributes`

## In-Memory Implementation Baseline

The verified mainline implementations are:

- `platform_infra/mass-storage-memory`:
  - `InMemoryTaskStorage`
  - `InMemoryWorkerStorage`
- `xa-mass-engine`:
  - `InMemoryRuleStorage`

Current `InMemoryWorkerStorage` behavior that matters architecturally:

- workers are stored by `workerId`
- project and event-code indexes are stored separately from the `Worker` object so in-place worker mutation does not silently break candidate lookup correctness
- worker contexts are grouped under owner worker by `workerId`
- a secondary `workerIdByContextId` map supports direct lookup by `workerContextId`
- deleting a worker also removes owned worker contexts and lock state
- worker locks are runtime-only storage truth, maintained separately from the `Worker` model

Current `InMemoryTaskStorage` behavior that matters architecturally:

- task status, project, schedulable, and max-runtime-deadline indexes are maintained separately from the `Task` object
- `saveTask(...)` no longer resets existing message or attempt buckets for the same `taskId`
- pending-logical-message and latest-active-attempt helper indexes are explicit implementation details used to avoid full compatibility-snapshot scans on hot runtime paths

## Manager Wiring

The active managers still default to factory-created memory storage:

```java
TaskManager taskManager = new TaskManager(taskScheduler, taskWorkRuntime);
WorkerManager workerManager = new WorkerManager();
RuleManager<?> ruleManager = new RuleManager<>();
```

Custom storage wiring is constructor-based:

```java
TaskStorage taskStorage = new InMemoryTaskStorage();
WorkerStorage workerStorage = new InMemoryWorkerStorage();
RuleStorage ruleStorage = new InMemoryRuleStorage();
```

Shell/debug query wiring is separate:

```java
TaskManager taskManager = new TaskManager(taskScheduler, taskWorkRuntime);
TaskCommandService taskCommands = new TaskCommandService(taskManager);
TaskEventService taskEvents = new TaskEventService(taskManager);
TaskQueryService taskQueries = new TaskQueryService(taskManager);
```

## Guardrails

When extending storage behavior, keep these rules fixed unless the kernel itself changes:

- do not reintroduce `Device` / `Token` terminology in active storage docs
- do not document placeholder Redis storage classes as if they were active engine wiring
- do not add APIs that collapse `WorkerContext` back to `0..1`
- do not duplicate `workerId` ownership across method parameters when the `WorkerContext` already carries it
- do not move online truth or lifecycle truth into `attributes`

## Where To Read Next

- [`../AGENTS.md`](../AGENTS.md)
- [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
- [`../platform_infra/README.md`](../platform_infra/README.md)
- [`../platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskStorage.java`](../platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskStorage.java)
- [`../platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/WorkerStorage.java`](../platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/WorkerStorage.java)
- [`../platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/RuleStorage.java`](../platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/RuleStorage.java)
