package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskIngestStatus;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.base.model.*;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.policy.AllWorkFinalTaskTerminalPolicy;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeEnqueueOptionsResolver;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Facade over task CRUD, task-message persistence, lifecycle convergence, and result handling.
 */
public class TaskManager {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);
    static final int MAX_INITIAL_INLINE_INPUTS = Integer.getInteger("xa.mass.engine.maxInitialInlineInputs", 10_000);
    static final int MAX_INGEST_BATCH_ITEMS = Integer.getInteger("xa.mass.engine.maxIngestBatchItems", 10_000);

    private final TaskStorage taskStorage;
    private final TaskWorkRuntime taskWorkRuntime;
    private final TaskScheduler taskScheduler;
    private final TaskTerminalPolicy taskTerminalPolicy;
    private final TaskEventPublisher eventPublisher;
    private final TaskStateResolver stateResolver;
    private final TaskStateValidator stateValidator;
    private final TaskDispatchRequestService dispatchRequestService;
    private final TaskLifecycleService lifecycleService;
    private final TaskResultService resultService;
    private final TaskRuntimeEnqueueOptionsResolver taskRuntimeEnqueueOptionsResolver;
    private final VirtualThreadRuntimeTaskExecutor retryWakeupExecutor;
    private final Map<String, TaskLockHandle> taskLocks = new ConcurrentHashMap<>();
    private final Map<String, MessageLockHandle> taskMessageLocks = new ConcurrentHashMap<>();
    private final Map<String, TaskProgressReconcileHandle> taskProgressReconcileHandles = new ConcurrentHashMap<>();
    private long taskMessageLeaseSeconds = 300L;

    public TaskManager(TaskScheduler taskScheduler, TaskWorkRuntime taskWorkRuntime) {
        this(taskScheduler, TaskStorageFactory.createDefaultTaskStorage(), new AllWorkFinalTaskTerminalPolicy(), taskWorkRuntime);
    }

    public TaskManager(TaskScheduler taskScheduler, TaskStorage taskStorage, TaskWorkRuntime taskWorkRuntime) {
        this(taskScheduler, taskStorage, new AllWorkFinalTaskTerminalPolicy(), taskWorkRuntime);
    }

    public TaskManager(TaskScheduler taskScheduler,
                       TaskStorage taskStorage,
                       TaskTerminalPolicy taskTerminalPolicy,
                       TaskWorkRuntime taskWorkRuntime) {
        this.taskScheduler = taskScheduler;
        this.taskStorage = taskStorage;
        this.taskWorkRuntime = Objects.requireNonNull(taskWorkRuntime, "taskWorkRuntime");
        this.taskTerminalPolicy = Objects.requireNonNull(taskTerminalPolicy, "taskTerminalPolicy");
        this.eventPublisher = new TaskEventPublisher();
        this.stateResolver = new TaskStateResolver(new TaskManagerStateRuntimePort(this));
        this.stateValidator = new TaskStateValidator(new TaskManagerStateRuntimePort(this));
        this.retryWakeupExecutor = new VirtualThreadRuntimeTaskExecutor(
                "engine-retry-wakeup-",
                Integer.getInteger("xa.mass.engine.retryWakeupMaxPendingTasks", 10_000)
        );
        this.dispatchRequestService = new TaskDispatchRequestService(
                new TaskManagerDispatchRequestRuntimePort(this),
                retryWakeupExecutor
        );
        this.lifecycleService = new TaskLifecycleService(
                new TaskManagerLifecycleRuntimePort(this),
                stateResolver
        );
        this.resultService = new TaskResultService(
                new TaskManagerResultRuntimePort(this),
                new TaskRuntimeRetryPolicyResolver()
        );
        this.taskRuntimeEnqueueOptionsResolver = new TaskRuntimeEnqueueOptionsResolver();
    }

    /**
     * Creates a task plus one persisted {@link TaskMsg} per work-item input.
     */
    public Task createTask(TaskCreateRequestDto dto) {
        validateCreateRequest(dto);
        long startTime = System.currentTimeMillis();
        LogUtils.logOperationStart("CREATE_TASK", "TaskManager",
                "taskName", dto.getTaskName(),
                "project", dto.getProject(),
                "routingCode", TaskSharedConfig.stringValue(dto.getSharedConfig(), TaskSharedConfig.ROUTING_CODE));

        try {
            String tid = java.util.UUID.randomUUID().toString();
            LogUtils.setTaskId(tid);

            UserRef user = UserRef.of(dto.getUserId());
            LogUtils.setUserId(dto.getUserId());

            List<Map<String, Object>> inputs = dto.getInputs() == null ? List.of() : dto.getInputs();
            TaskSourceType sourceType = resolveSourceType(dto);
            if (inputs.isEmpty() && !sourceType.allowsEmptyInitialInputs()) {
                throw new IllegalArgumentException("inputs must contain at least one work item");
            }
            int initNumber = inputs.size();

            Task task = new Task(
                    tid,
                    dto.getTaskName(),
                    dto.getProject(),
                    initNumber,
                    dto.getSharedConfig() != null ? dto.getSharedConfig() : new java.util.HashMap<>(),
                    user
            );
            task.setSourceType(sourceType);
            task.setWorkloadClass(dto.getWorkloadClass());
            task.setSourceRef(normalizeSourceRef(dto.getSourceRef()));
            task.setIngestStatus(resolveInitialIngestStatus(sourceType, dto.isOpenEnded(), initNumber));
            task.setBatchSize(dto.getBatchSize());
            // intakeStatus is the runtime truth; openEnded remains a compatibility projection.
            task.setIntakeStatus(dto.isOpenEnded() ? TaskIntakeStatus.OPEN : TaskIntakeStatus.SEALED);
            task.setMaxRuntimeSeconds(dto.getMaxRuntimeSeconds());
            taskStorage.saveTask(task);
            ingestInitialInputs(tid, dto, inputs);

            eventPublisher.publishTaskCreated(task);
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationSuccess("task created: taskId=" + tid
                    + ", sourceType=" + sourceType
                    + ", initialMessageCount=" + initNumber
                    + ", ingestStatus=" + task.getIngestStatus(), duration);
            return task;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_CREATE_ERROR", e.getMessage(), duration);
            logger.error("Failed to create task", e);
            throw e;
        }
    }

    /**
     * Returns a task by id or {@code null} if it does not exist.
     */
    Task getTask(String taskId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("GET_TASK", "TaskManager", "taskId", taskId);

        Task task = taskStorage.getTask(taskId).orElse(null);

        if (task != null) {
            LogUtils.logOperationSuccess("task loaded", 0);
        } else {
            LogUtils.logOperationFailure("TASK_NOT_FOUND", "task not found", 0);
        }

        return task;
    }

    /**
     * Persists a task update.
     */
    public boolean updateTask(Task task) {
        LogUtils.setTaskId(task.getTid());
        LogUtils.logOperationStart("UPDATE_TASK", "TaskManager", "taskId", task.getTid());

        boolean result = taskStorage.updateTask(task);

        if (result) {
            LogUtils.logOperationSuccess("task updated", 0);
        } else {
            LogUtils.logOperationFailure("TASK_UPDATE_ERROR", "task update failed", 0);
        }

        return result;
    }

    /**
     * Deletes a task if it is still safe to remove.
     */
    public boolean deleteTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.deleteTask(taskId));
    }

    /**
     * Returns all persisted tasks.
     */
    List<Task> getAllTasks() {
        LogUtils.logOperationStart("GET_ALL_TASKS", "TaskManager");

        List<Task> tasks = taskStorage.getAllTasks();

        LogUtils.logOperationSuccess("loaded all tasks: count=" + tasks.size(), 0);
        return tasks;
    }

    List<Task> getRuntimeDispatchableTasks(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return taskWorkRuntime.readyTaskIds(limit).stream()
                .map(this::getTask)
                .filter(task -> task != null)
                .toList();
    }

    /**
     * Returns tasks currently in the given status.
     */
    List<Task> getTasksByStatus(TaskStatus status) {
        LogUtils.logOperationStart("GET_TASKS_BY_STATUS", "TaskManager", "status", status.name());

        List<Task> tasks = taskStorage.getTasksByStatus(status);

        LogUtils.logOperationSuccess("loaded tasks by status: status=" + status + ", count=" + tasks.size(), 0);
        return tasks;
    }

    /**
     * Returns tasks that the scheduler currently considers schedulable.
     */
    List<Task> getSchedulableTasks() {
        LogUtils.logOperationStart("GET_SCHEDULABLE_TASKS", "TaskManager");

        List<Task> tasks = taskStorage.getSchedulableTasks();

        LogUtils.logOperationSuccess("loaded schedulable tasks: count=" + tasks.size(), 0);
        return tasks;
    }

    List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit) {
        return taskStorage.pollExpiredMaxRuntimeTasks(now, limit);
    }

    public boolean approveTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.approveTask(taskId));
    }

    public boolean rejectTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.rejectTask(taskId));
    }

    public boolean blockTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.blockTask(taskId));
    }

    public boolean pauseTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.pauseTask(taskId));
    }

    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.resumeTaskDetailed(taskId));
    }

    public boolean resumeTask(String taskId) {
        return resumeTaskDetailed(taskId).isSuccess();
    }

    /**
     * Manually terminates a non-final task (operator/user-initiated cancellation).
     */
    public boolean cancelTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.cancelTask(taskId));
    }

    /**
     * Policy-driven task termination (e.g. max-runtime exceeded, success-rate reached).
     */
    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return withTaskLock(taskId, () -> lifecycleService.terminateTask(taskId, reason));
    }

    /**
     * Appends new work items to a READY or RUNNING open-ended task.
     */
    public int appendTaskItems(String taskId, List<java.util.Map<String, Object>> inputs) {
        return withTaskLock(taskId, () -> lifecycleService.appendTaskItems(taskId, inputs));
    }

    /**
     * Seals an open-ended task.
     */
    public boolean sealTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.sealTask(taskId));
    }

    /**
     * Persists a task message under the owning task.
     */
    public void addTaskMessage(String taskId, TaskMsg taskMsg) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("ADD_TASK_MESSAGE", "TaskManager",
                "taskId", taskId,
                "messageId", taskMsg.getMessageId());

        WorkEnqueueOutcome outcome = enqueueTaskWork(taskId, taskMsg);
        if (outcome != null && outcome.status() != WorkEnqueueStatus.ENQUEUED) {
            throw new IllegalStateException("task work enqueue failed: status="
                    + outcome.status() + ", reason=" + outcome.reason());
        }
        taskStorage.addTaskMessage(taskId, taskMsg);

        LogUtils.logOperationSuccess("task message added", 0);
    }

    /**
     * Package-local compatibility read surface for demo/tests and explicit
     * projection audit. Do not treat full task-message reads as a future
     * business-detail path.
     */
    List<TaskMsg> getTaskMessages(String taskId) {
        return taskStorage.getTaskMessages(taskId);
    }

    /**
     * Bounded compatibility read for UI/debug snapshots. Not a pagination or
     * analysis contract.
     */
    List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return taskStorage.getTaskMessages(taskId, limit);
    }

    long countTaskMessages(String taskId) {
        return taskStorage.countTaskMessages(taskId);
    }

    TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskStorage.getTaskMessage(taskId, messageId).orElse(null);
    }

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return taskStorage.updateTaskMessage(taskId, taskMsg);
    }

    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskStorage.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        return taskStorage.getTaskMessageAttempts(taskId, messageId);
    }

    TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskStorage.getLatestTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskStorage.getLatestActiveTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        return taskStorage.updateTaskMessageAttempt(taskId, messageId, attempt);
    }

    public long getTaskMessageLeaseSeconds() {
        return taskMessageLeaseSeconds;
    }

    public void setTaskMessageLeaseSeconds(long taskMessageLeaseSeconds) {
        if (taskMessageLeaseSeconds <= 0) {
            throw new IllegalArgumentException("taskMessageLeaseSeconds must be greater than 0");
        }
        this.taskMessageLeaseSeconds = taskMessageLeaseSeconds;
    }

    /**
     * Expires a single in-flight task message and recalculates task convergence.
     */
    public boolean expireTaskMessage(String taskId, String messageId) {
        TaskResultService.TaskMessageMutationOutcome outcome = withTaskMessageReadLock(taskId, messageId,
                () -> resultService.expireTaskMessage(taskId, messageId));
        if (outcome.progressDirty()) {
            updateTaskProgress(taskId);
        }
        return outcome.accepted();
    }

    /**
     * Returns the current persisted task-message aggregate for a task.
     */
    TaskStorage.TaskMessageStats getTaskMessageStats(String taskId) {
        return taskStorage.getTaskMessageStats(taskId);
    }

    int countPendingDispatchableMessages(String taskId) {
        long readyCount = taskWorkRuntime.stats(taskId).readyCount();
        return readyCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) readyCount;
    }

    boolean hasPendingDispatchableMessages(String taskId) {
        return countPendingDispatchableMessages(taskId) > 0;
    }

    boolean hasProcessingMessagesForWorker(String taskId, String workerId) {
        return taskWorkRuntime.hasActiveLeaseForWorker(taskId, workerId);
    }

    TaskWorkStats getTaskWorkStats(String taskId) {
        return taskWorkRuntime.stats(taskId);
    }

    TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats) {
        return taskTerminalPolicy.evaluate(task, stats);
    }

    void publishTaskTerminal(Task task) {
        eventPublisher.publishTaskTerminal(task);
    }

    TaskStorage.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return taskStorage.getTaskMessageAttemptStats(taskId, messageId);
    }

    /**
     * Recomputes task-level convergence from runtime-owned work stats plus the
     * persisted task aggregate.
     */
    void updateTaskProgress(String taskId) {
        reconcileTaskProgress(taskId);
    }

    void resolveTaskProgressUnderTaskLock(String taskId) {
        stateResolver.updateTaskProgress(taskId);
    }

    /**
     * Resolves task state explicitly from runtime-owned work stats plus the
     * persisted task aggregate.
     */
    TaskStateResolutionResult resolveTaskState(String taskId) {
        return withTaskLock(taskId, () -> stateResolver.resolveTaskState(taskId));
    }

    /**
     * Audit-only invariant validation. This path is intentionally bounded and
     * should not be treated as a hot-path runtime query surface. This method
     * validates task/runtime aggregates without scanning the full TaskMsg
     * compatibility projection.
     */
    TaskStateValidationResult validateTaskState(String taskId) {
        return withTaskLock(taskId, () -> stateValidator.validateTaskState(taskId));
    }

    /**
     * Explicit deep audit of the persisted TaskMsg projection plus attempt
     * aggregates. This is diagnostic-only and may require a full task-message
     * snapshot.
     */
    TaskStateValidationResult auditTaskProjectionState(String taskId) {
        return withTaskLock(taskId, () -> stateValidator.auditTaskProjectionState(taskId));
    }

    public TaskScheduler getScheduler() {
        return this.taskScheduler;
    }

    /**
     * Returns the in-process runtime event surface for synchronous engine
     * reactions such as assignment submission and resource release.
     */
    TaskEventPublisher events() {
        return eventPublisher;
    }

    public void shutdown() {
        dispatchRequestService.shutdown();
        resultService.shutdown();
        retryWakeupExecutor.shutdown();
        taskWorkRuntime.shutdown();
    }

    public boolean handleTaskMessageResult(String taskId, String messageId, boolean success, String detail) {
        TaskResultService.TaskMessageMutationOutcome outcome = withTaskMessageReadLock(taskId, messageId,
                () -> resultService.handleTaskMessageResult(taskId, messageId, success, detail));
        if (outcome.progressDirty()) {
            updateTaskProgress(taskId);
        }
        return outcome.accepted();
    }

    public boolean handleTaskMessageResult(String taskId, String messageId, boolean success, String detail, String errorCode) {
        TaskResultService.TaskMessageMutationOutcome outcome = withTaskMessageReadLock(taskId, messageId,
                () -> resultService.handleTaskMessageResult(taskId, messageId, success, detail, errorCode));
        if (outcome.progressDirty()) {
            updateTaskProgress(taskId);
        }
        return outcome.accepted();
    }

    public boolean handleTaskMessageResult(String taskId,
                                           String messageId,
                                           boolean success,
                                           String detail,
                                           String errorCode,
                                           Map<String, Object> output) {
        TaskResultService.TaskMessageMutationOutcome outcome = withTaskMessageReadLock(taskId, messageId,
                () -> resultService.handleTaskMessageResult(taskId, messageId, success, detail, errorCode, output));
        if (outcome.progressDirty()) {
            updateTaskProgress(taskId);
        }
        return outcome.accepted();
    }

    <T> T withTaskLock(String taskId, Supplier<T> action) {
        return withTaskWriteLock(taskId, action);
    }

    private <T> T withTaskWriteLock(String taskId, Supplier<T> action) {
        if (taskId == null || taskId.isBlank()) {
            return action.get();
        }
        TaskLockHandle lockHandle = acquireTaskLockHandle(taskId);
        lockHandle.lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lockHandle.lock.writeLock().unlock();
            releaseTaskLockHandle(taskId, lockHandle);
        }
    }

    void withTaskLock(String taskId, Runnable action) {
        withTaskLock(taskId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T withTaskReadLock(String taskId, Supplier<T> action) {
        if (taskId == null || taskId.isBlank()) {
            return action.get();
        }
        TaskLockHandle lockHandle = acquireTaskLockHandle(taskId);
        lockHandle.lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lockHandle.lock.readLock().unlock();
            releaseTaskLockHandle(taskId, lockHandle);
        }
    }

    private <T> T withTaskMessageReadLock(String taskId, String messageId, Supplier<T> action) {
        if (messageId == null || messageId.isBlank()) {
            return withTaskReadLock(taskId, action);
        }
        return withTaskReadLock(taskId, () -> withMessageLock(taskId, messageId, action));
    }

    private <T> T withMessageLock(String taskId, String messageId, Supplier<T> action) {
        String lockKey = taskId + "|" + messageId;
        MessageLockHandle lockHandle = acquireMessageLockHandle(lockKey);
        lockHandle.lock.lock();
        try {
            return action.get();
        } finally {
            lockHandle.lock.unlock();
            releaseMessageLockHandle(lockKey, lockHandle);
        }
    }

    private void reconcileTaskProgress(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            withTaskLock(taskId, () -> resolveTaskProgressUnderTaskLock(taskId));
            return;
        }
        TaskProgressReconcileHandle handle = acquireTaskProgressReconcileHandle(taskId);
        try {
            long requestedVersion;
            boolean leader;
            handle.lock.lock();
            try {
                requestedVersion = ++handle.requestedVersion;
                if (!handle.running) {
                    handle.running = true;
                    leader = true;
                } else {
                    leader = false;
                }
            } finally {
                handle.lock.unlock();
            }
            if (!leader) {
                awaitTaskProgressReconcile(handle, requestedVersion);
                return;
            }
            runTaskProgressReconcileLoop(taskId, handle);
        } finally {
            releaseTaskProgressReconcileHandle(taskId, handle);
        }
    }

    private void awaitTaskProgressReconcile(TaskProgressReconcileHandle handle, long requestedVersion) {
        handle.lock.lock();
        try {
            while (handle.running && handle.completedVersion < requestedVersion) {
                handle.idle.awaitUninterruptibly();
            }
        } finally {
            handle.lock.unlock();
        }
    }

    private void runTaskProgressReconcileLoop(String taskId, TaskProgressReconcileHandle handle) {
        try {
            while (true) {
                long targetVersion;
                handle.lock.lock();
                try {
                    targetVersion = handle.requestedVersion;
                } finally {
                    handle.lock.unlock();
                }

                withTaskLock(taskId, () -> resolveTaskProgressUnderTaskLock(taskId));

                boolean done;
                handle.lock.lock();
                try {
                    handle.completedVersion = Math.max(handle.completedVersion, targetVersion);
                    done = handle.requestedVersion <= handle.completedVersion;
                    if (done) {
                        handle.running = false;
                    }
                    handle.idle.signalAll();
                } finally {
                    handle.lock.unlock();
                }
                if (done) {
                    return;
                }
            }
        } catch (RuntimeException | Error ex) {
            handle.lock.lock();
            try {
                handle.running = false;
                handle.idle.signalAll();
            } finally {
                handle.lock.unlock();
            }
            throw ex;
        }
    }

    private void validateCreateRequest(TaskCreateRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        ProjectRef.require(dto.getProject());
        UserRef.requireUserId(dto.getUserId());
        TaskSourceType sourceType = resolveSourceType(dto);
        List<Map<String, Object>> inputs = dto.getInputs() == null ? List.of() : dto.getInputs();
        if (sourceType == TaskSourceType.FILE
                && (dto.getSourceRef() == null || dto.getSourceRef().isBlank())) {
            throw new IllegalArgumentException("sourceRef is required for FILE task sources");
        }
        if (sourceType == TaskSourceType.FILE && !inputs.isEmpty()) {
            throw new IllegalArgumentException("FILE task sources must be created as a sourceRef shell; ingest work items in batches");
        }
        if (sourceType == TaskSourceType.BATCH && inputs.size() > MAX_INITIAL_INLINE_INPUTS) {
            throw new IllegalArgumentException("BATCH task initial inputs exceed inline create limit: "
                    + inputs.size() + " > " + MAX_INITIAL_INLINE_INPUTS);
        }
        if (sourceType == TaskSourceType.STREAM && inputs.size() > MAX_INGEST_BATCH_ITEMS) {
            throw new IllegalArgumentException("STREAM task initial inputs exceed ingest batch limit: "
                    + inputs.size() + " > " + MAX_INGEST_BATCH_ITEMS);
        }
    }

    private TaskSourceType resolveSourceType(TaskCreateRequestDto dto) {
        if (dto.getSourceType() != null) {
            return dto.getSourceType();
        }
        return dto.isOpenEnded() ? TaskSourceType.STREAM : TaskSourceType.BATCH;
    }

    private TaskIngestStatus resolveInitialIngestStatus(TaskSourceType sourceType,
                                                        boolean openEnded,
                                                        int initialMessageCount) {
        if (sourceType == TaskSourceType.FILE) {
            return initialMessageCount > 0 ? TaskIngestStatus.READY : TaskIngestStatus.PENDING;
        }
        if (openEnded || sourceType == TaskSourceType.STREAM) {
            return TaskIngestStatus.READY;
        }
        return TaskIngestStatus.SEALED;
    }

    private String normalizeSourceRef(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return null;
        }
        return sourceRef.trim();
    }

    TaskStorage getTaskStorage() {
        return taskStorage;
    }

    public TaskWorkRuntime getTaskWorkRuntime() {
        return taskWorkRuntime;
    }

    TaskTerminalPolicy getTaskTerminalPolicy() {
        return taskTerminalPolicy;
    }

    TaskEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    /**
     * Engine-owned dispatch entry for task-ready and refill-style redispatch.
     *
     * <p>This is a runtime orchestration method, not a public business API
     * contract.
     */
    void requestTaskDispatch(Task task) {
        dispatchRequestService.requestImmediate(task);
    }

    void requestTaskRetryDispatch(Task task, long delayMillis) {
        dispatchRequestService.requestDelayed(task, delayMillis);
    }

    java.util.Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
        return taskWorkRuntime.getActiveLease(taskId, messageId);
    }

    ResultApplyOutcome applyTaskWorkResult(TaskWorkResult result) {
        return taskWorkRuntime.applyResult(result);
    }

    void publishTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt) {
        eventPublisher.publishTaskMessageAttemptClosed(task, taskMsg, attempt);
    }

    void publishTaskMessageLogicallyFinal(Task task, TaskMsg taskMsg) {
        eventPublisher.publishTaskMessageLogicallyFinal(task, taskMsg);
    }

    void handleTaskMsgCompletion(TaskMsg taskMsg) {
        taskScheduler.handleTaskMsgCompletion(taskMsg);
    }

    void handleTaskMsgFailure(TaskMsg taskMsg, String detail) {
        taskScheduler.handleTaskMsgFailure(taskMsg, detail);
    }

    private void ingestInitialInputs(String taskId, TaskCreateRequestDto dto, List<Map<String, Object>> inputs) {
        for (Map<String, Object> input : inputs) {
            String messageId = java.util.UUID.randomUUID().toString();
            TaskMsg taskMsg = new TaskMsg(messageId, taskId, input);
            taskMsg.setMaxRetryCount(dto.getDefaultMsgMaxRetryCount());
            addTaskMessage(taskId, taskMsg);
        }
    }

    private WorkEnqueueOutcome enqueueTaskWork(String taskId, TaskMsg taskMsg) {
        if (taskMsg == null || taskMsg.getStatus() != com.xa.mass.base.enums.taskmsg.TaskMsgStatus.INIT) {
            return null;
        }
        Task task = taskStorage.getTask(taskId).orElse(null);
        TaskWorkEnvelope item = new TaskWorkEnvelope(
                taskId,
                taskMsg.getMessageId(),
                task != null ? TaskSharedConfig.sdkEventCode(task) : null,
                taskMsg.getInput(),
                null,
                taskMsg.getRetryCount(),
                taskMsg.getMaxRetryCount(),
                null,
                null,
                java.time.Instant.now()
        );
        return taskWorkRuntime.enqueue(
                item,
                taskRuntimeEnqueueOptionsResolver.resolve(task)
        );
    }

    private TaskLockHandle acquireTaskLockHandle(String taskId) {
        return taskLocks.compute(taskId, (ignored, existing) -> {
            TaskLockHandle handle = existing == null ? new TaskLockHandle() : existing;
            handle.referenceCount++;
            return handle;
        });
    }

    private void releaseTaskLockHandle(String taskId, TaskLockHandle lockHandle) {
        taskLocks.computeIfPresent(taskId, (ignored, existing) -> {
            if (existing != lockHandle) {
                return existing;
            }
            existing.referenceCount--;
            if (existing.referenceCount == 0
                    && existing.lock.getReadLockCount() == 0
                    && !existing.lock.isWriteLocked()
                    && !existing.lock.hasQueuedThreads()) {
                return null;
            }
            return existing;
        });
    }

    private MessageLockHandle acquireMessageLockHandle(String lockKey) {
        return taskMessageLocks.compute(lockKey, (ignored, existing) -> {
            MessageLockHandle handle = existing == null ? new MessageLockHandle() : existing;
            handle.referenceCount++;
            return handle;
        });
    }

    private TaskProgressReconcileHandle acquireTaskProgressReconcileHandle(String taskId) {
        return taskProgressReconcileHandles.compute(taskId, (ignored, existing) -> {
            TaskProgressReconcileHandle handle = existing == null ? new TaskProgressReconcileHandle() : existing;
            handle.referenceCount++;
            return handle;
        });
    }

    private void releaseMessageLockHandle(String lockKey, MessageLockHandle lockHandle) {
        taskMessageLocks.computeIfPresent(lockKey, (ignored, existing) -> {
            if (existing != lockHandle) {
                return existing;
            }
            existing.referenceCount--;
            if (existing.referenceCount == 0
                    && !existing.lock.isLocked()
                    && !existing.lock.hasQueuedThreads()) {
                return null;
            }
            return existing;
        });
    }

    private void releaseTaskProgressReconcileHandle(String taskId, TaskProgressReconcileHandle lockHandle) {
        taskProgressReconcileHandles.computeIfPresent(taskId, (ignored, existing) -> {
            if (existing != lockHandle) {
                return existing;
            }
            existing.referenceCount--;
            if (existing.referenceCount == 0
                    && !existing.running
                    && !existing.lock.hasQueuedThreads()) {
                return null;
            }
            return existing;
        });
    }

    private static final class TaskLockHandle {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private int referenceCount;
    }

    private static final class MessageLockHandle {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int referenceCount;
    }

    private static final class TaskProgressReconcileHandle {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition idle = lock.newCondition();
        private long requestedVersion;
        private long completedVersion;
        private boolean running;
        private int referenceCount;
    }
}


