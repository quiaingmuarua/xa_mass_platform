# Storage Baseline

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

## Current Factory Reality

`TaskStorageFactory` is still the central creation point for storage implementations.

Current behavior:

- `MEMORY` is the only implemented runtime path
- `REDIS` exists as a type, but factory methods fail fast with `UnsupportedOperationException`
- `DATABASE` exists as a type, but factory methods fail fast with `UnsupportedOperationException`

That means the verified mainline is memory-backed storage only.

## TaskStorage

`TaskStorage` owns persisted task state, message projection state, and attempt history.

Main responsibilities:

- save and load `Task`
- update and delete `Task`
- query tasks by status
- store and update `TaskMsg`
- store and update `TaskMsgAttempt`
- aggregate `TaskMsg` statistics for lifecycle convergence

What `TaskStorage` does not own anymore:

- ready-work admission
- in-flight lease ownership
- per-worker active-dispatch truth

Those hot-path concerns belong to `TaskWorkRuntime`, not to `TaskStorage` scans.

Current interface shape:

```java
public interface TaskStorage {
    void saveTask(Task task);
    Optional<Task> getTask(String taskId);
    boolean updateTask(Task task);
    boolean deleteTask(String taskId);
    List<Task> getAllTasks();
    List<Task> getTasksByStatus(String status);
    List<Task> getSchedulableTasks();
    void addTaskMessage(String taskId, TaskMsg taskMsg);
    List<TaskMsg> getTaskMessages(String taskId);
    Optional<TaskMsg> getTaskMessage(String taskId, String messageId);
    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);
    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);
    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId);
    Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId);
    Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId);
    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);
    TaskMessageStats getTaskMessageStats(String taskId);
    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId);
}
```

Important current usage notes:

- task completion is driven from runtime counters plus persisted logical message outcomes, not just task status
- storage must support `taskId + messageId` lookups because result write-back is keyed that way
- `TaskMessageStats` and `TaskMessageAttemptStats` are read-model and audit surfaces, not queue/lease ownership
- `getTaskMessages(...)` and `getTaskMessagesPage(...)` are compatibility/demo reads plus temporary internal cleanup helpers; they are not the future business-detail path
- future task detail should bias toward logs or async write-behind sinks instead of engine-owned full-message query surfaces

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
- worker lock truth lives in `WorkerStorage` and `WorkerManager.isLocked(...)`
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

- `InMemoryTaskStorage`
- `InMemoryWorkerStorage`
- `InMemoryRuleStorage`

Current `InMemoryWorkerStorage` behavior that matters architecturally:

- workers are stored by `workerId`
- worker contexts are grouped under owner worker by `workerId`
- a secondary `workerIdByContextId` map supports direct lookup by `workerContextId`
- deleting a worker also removes owned worker contexts and lock state
- worker locks are runtime-only storage truth, maintained separately from the `Worker` model

## Manager Wiring

The active managers still default to factory-created memory storage:

```java
TaskManager taskManager = new TaskManager(taskScheduler);
WorkerManager workerManager = new WorkerManager();
RuleManager<?> ruleManager = new RuleManager<>();
```

Custom storage wiring is still constructor-based:

```java
TaskStorage taskStorage = TaskStorageFactory.createTaskStorage("memory");
WorkerStorage workerStorage = TaskStorageFactory.createWorkerStorage("memory");
RuleStorage ruleStorage = TaskStorageFactory.createRuleStorage("memory");
```

## Guardrails

When extending storage behavior, keep these rules fixed unless the kernel itself changes:

- do not reintroduce `Device` / `Token` terminology in active storage docs
- do not document unsupported Redis or Database paths as if they were production-ready
- do not add APIs that collapse `WorkerContext` back to `0..1`
- do not duplicate `workerId` ownership across method parameters when the `WorkerContext` already carries it
- do not move online truth or lifecycle truth into `attributes`

## Where To Read Next

- [`../AGENTS.md`](../AGENTS.md)
- [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
- [`src/main/java/com/xa/mass/engine/storage/TaskStorage.java`](src/main/java/com/xa/mass/engine/storage/TaskStorage.java)
- [`src/main/java/com/xa/mass/engine/storage/WorkerStorage.java`](src/main/java/com/xa/mass/engine/storage/WorkerStorage.java)
- [`src/main/java/com/xa/mass/engine/storage/RuleStorage.java`](src/main/java/com/xa/mass/engine/storage/RuleStorage.java)
