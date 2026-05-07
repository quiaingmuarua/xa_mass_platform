package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskIngestStatus;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.base.model.*;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.policy.AllWorkFinalTaskTerminalPolicy;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkEnqueueStatus;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.trace.sink.ExecutionEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Internal engine orchestration facade and composition root for task lifecycle,
 * compatibility projection, and runtime-bridge wiring.
 *
 * <p>This remains the owner of engine assembly semantics, but it is not the
 * preferred cross-module caller surface for shell, SDK, transport, or testing
 * flows. Downstream callers should prefer {@link TaskCommandService},
 * {@link TaskQueryService}, {@link TaskResultIngestFacade},
 * {@link TaskAssignmentRuntimePort}, {@link TaskRuntimeMaintenancePort},
 * {@link TaskRuntimeRecoveryPort}, and {@link TaskEventService}.
 */
public class TaskManager implements TaskAssignmentRuntimePort, TaskRuntimeMaintenancePort, TaskRuntimeRecoveryPort {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);
    static final int MAX_INITIAL_INLINE_INPUTS = Integer.getInteger("xa.mass.engine.maxInitialInlineInputs", 10_000);
    static final int MAX_INGEST_BATCH_ITEMS = Integer.getInteger("xa.mass.engine.maxIngestBatchItems", 10_000);

    private final TaskStorage taskStorage;
    private final TaskDetailStore taskDetailStore;
    private final TaskScheduler taskScheduler;
    private final TaskTerminalPolicy taskTerminalPolicy;
    private final TaskEventPublisher eventPublisher;
    private final TaskStateResolver stateResolver;
    private final TaskStateValidator stateValidator;
    private final TaskDispatchRequestService dispatchRequestService;
    private final TaskLifecycleService lifecycleService;
    private final TaskResultService resultService;
    private final TaskRuntimeBridge taskRuntimeBridge;
    private final TaskConcurrencyStrategy concurrencyCoordinator;
    private final VirtualThreadRuntimeTaskExecutor retryWakeupExecutor;
    private final com.xa.mass.engine.util.TraceEventLogger traceEventLogger;
    private long taskMessageLeaseSeconds = 300L;

    public TaskManager(TaskScheduler taskScheduler,
                       TaskStorage taskStorage,
                       TaskDetailStore taskDetailStore,
                       TaskWorkRuntime taskWorkRuntime) {
        this(taskScheduler, taskStorage, taskDetailStore, new AllWorkFinalTaskTerminalPolicy(), taskWorkRuntime, null);
    }

    public TaskManager(TaskScheduler taskScheduler,
                       TaskStorage taskStorage,
                       TaskDetailStore taskDetailStore,
                       TaskTerminalPolicy taskTerminalPolicy,
                       TaskWorkRuntime taskWorkRuntime) {
        this(taskScheduler, taskStorage, taskDetailStore, taskTerminalPolicy, taskWorkRuntime, null);
    }

    public TaskManager(TaskScheduler taskScheduler,
                       TaskStorage taskStorage,
                       TaskDetailStore taskDetailStore,
                       TaskWorkRuntime taskWorkRuntime,
                       ExecutionEventSink executionEventSink) {
        this(taskScheduler, taskStorage, taskDetailStore, new AllWorkFinalTaskTerminalPolicy(), taskWorkRuntime, executionEventSink);
    }

    public TaskManager(TaskScheduler taskScheduler,
                       TaskStorage taskStorage,
                       TaskDetailStore taskDetailStore,
                       TaskTerminalPolicy taskTerminalPolicy,
                       TaskWorkRuntime taskWorkRuntime,
                       ExecutionEventSink executionEventSink) {
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
        this.taskStorage = Objects.requireNonNull(taskStorage, "taskStorage");
        this.taskDetailStore = Objects.requireNonNull(taskDetailStore, "taskDetailStore");
        TaskWorkRuntime requiredTaskWorkRuntime = Objects.requireNonNull(taskWorkRuntime, "taskWorkRuntime");
        this.taskTerminalPolicy = Objects.requireNonNull(taskTerminalPolicy, "taskTerminalPolicy");
        this.traceEventLogger = new com.xa.mass.engine.util.TraceEventLogger(executionEventSink);
        this.eventPublisher = new TaskEventPublisher();
        this.stateResolver = new TaskStateResolver(
                this,
                traceEventLogger
        );
        this.stateValidator = new TaskStateValidator(
                this,
                traceEventLogger
        );
        this.taskRuntimeBridge = new TaskRuntimeBridge(
                taskStorage,
                requiredTaskWorkRuntime,
                new com.xa.mass.engine.runtime.TaskRuntimeEnqueueOptionsResolver()
        );
        this.concurrencyCoordinator = new LocalTaskConcurrencyCoordinator();
        this.retryWakeupExecutor = new VirtualThreadRuntimeTaskExecutor(
                "engine-retry-wakeup-",
                Integer.getInteger("xa.mass.engine.retryWakeupMaxPendingTasks", 10_000)
        );
        this.dispatchRequestService = new TaskDispatchRequestService(
                this,
                retryWakeupExecutor,
                new LocalDelayedDispatchSchedule()
        );
        this.lifecycleService = new TaskLifecycleService(
                this,
                stateResolver,
                traceEventLogger
        );
        this.resultService = new TaskResultService(
                this,
                new TaskRuntimeRetryPolicyResolver(),
                traceEventLogger
        );
    }

    /**
     * Creates the task shell, initializes intake/source/runtime truth, ingests
     * initial inputs, enqueues runtime work, and best-effort writes bounded
     * compatibility {@link TaskMsg} projection rows.
     *
     * <p>This path is intentionally kept stable in this round. It is the next
     * internal split candidate, but should not accumulate more unrelated
     * responsibilities.
     */
    Task createTask(TaskCreateRequestDto dto) {
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
    @Override
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
    boolean deleteTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.deleteTask(taskId));
    }

    List<Task> listTasksPaged(int offset, int limit) {
        return taskStorage.listTasksPaged(offset, limit);
    }

    @Override
    public List<Task> getRuntimeDispatchableTasks(int limit) {
        return taskRuntimeBridge.getRuntimeDispatchableTasks(limit);
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

    @Override
    public List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit) {
        return taskStorage.pollExpiredMaxRuntimeTasks(now, limit);
    }

    boolean approveTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.approveTask(taskId));
    }

    boolean rejectTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.rejectTask(taskId));
    }

    boolean blockTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.blockTask(taskId));
    }

    boolean pauseTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.pauseTask(taskId));
    }

    TaskResumeResult resumeTaskDetailed(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.resumeTaskDetailed(taskId));
    }

    boolean resumeTask(String taskId) {
        return resumeTaskDetailed(taskId).isSuccess();
    }

    /**
     * Manually terminates a non-final task (operator/user-initiated cancellation).
     */
    boolean cancelTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.cancelTask(taskId));
    }

    /**
     * Policy-driven task termination (e.g. max-runtime exceeded, success-rate reached).
     */
    @Override
    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return withTaskLock(taskId, () -> lifecycleService.terminateTask(taskId, reason));
    }

    /**
     * Appends new work items to a READY or RUNNING open-ended task.
     */
    int appendTaskItems(String taskId, List<java.util.Map<String, Object>> inputs) {
        return withTaskLock(taskId, () -> lifecycleService.appendTaskItems(taskId, inputs));
    }

    /**
     * Seals an open-ended task.
     */
    boolean sealTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.sealTask(taskId));
    }

    /**
     * Runtime-first ingest for one logical work item.
     *
     * <p>Runtime admission happens before compatibility projection write so
     * {@link TaskMsg} is not the canonical ingest product for execution
     * correctness.</p>
     */
    void addTaskInput(String taskId,
                      String messageId,
                      java.util.Map<String, Object> input,
                      int maxRetryCount) {
        addRuntimeIngressItem(RuntimeTaskIngressItem.fromInput(taskId, messageId, input, maxRetryCount));
    }

    void addTaskPayloadRef(String taskId,
                           String messageId,
                           String payloadRef,
                           int maxRetryCount) {
        addRuntimeIngressItem(new RuntimeTaskIngressItem(
                taskId,
                messageId,
                Map.of(),
                payloadRef,
                0,
                maxRetryCount
        ));
    }

    private void addRuntimeIngressItem(RuntimeTaskIngressItem ingressItem) {
        String taskId = ingressItem.taskId();
        String messageId = ingressItem.messageId();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("ADD_TASK_MESSAGE", "TaskManager",
                "taskId", taskId,
                "messageId", messageId);

        WorkEnqueueOutcome outcome = enqueueTaskWork(ingressItem);
        if (!taskRuntimeBridge.isTaskWorkEnqueueAccepted(outcome)) {
            throw new IllegalStateException("task work enqueue failed: status="
                    + outcome.status() + ", reason=" + outcome.reason());
        }
        try {
            addTaskMessageProjection(taskId, ingressItem.toCompatibilityProjection());
        } catch (RuntimeException e) {
            logger.warn("Runtime ingress accepted for taskId={}, messageId={} but compatibility TaskMsg projection write failed",
                    taskId, messageId, e);
        }

        LogUtils.logOperationSuccess("task message added", 0);
    }

    /**
     * Package-local compatibility read surface for demo/tests and explicit
     * projection audit. Do not treat full task-message reads as a future
     * business-detail path.
     */
    List<TaskMsg> getTaskMessages(String taskId) {
        return taskDetailStore.getTaskMessages(taskId);
    }

    /**
     * Bounded compatibility read for UI/debug snapshots. Not a pagination or
     * analysis contract.
     */
    List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return taskDetailStore.getTaskMessages(taskId, limit);
    }

    TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        int boundedLimit = Math.max(0, limit);
        if (boundedLimit == 0) {
            return new TaskMessageSnapshot(List.of(), 0, false);
        }
        Task task = getTask(taskId);
        List<TaskMsg> fetched = taskDetailStore.getTaskMessages(taskId, Math.addExact(boundedLimit, 1));
        boolean truncated = fetched.size() > boundedLimit;
        List<TaskMsg> snapshot = truncated ? fetched.subList(0, boundedLimit) : fetched;
        List<TaskMsg> runtimeAwareSnapshot = task != null && (task.getStatus() == null || !task.getStatus().isFinal())
                ? CompatibilityProjectionSupport.overlayActiveLeaseView(snapshot, getActiveLeases(taskId), taskId)
                : snapshot;
        return new TaskMessageSnapshot(
                CompatibilityProjectionSupport.overlayTerminalTaskView(task, runtimeAwareSnapshot),
                boundedLimit,
                truncated
        );
    }

    /**
     * @deprecated compatibility residue read only; cross-module callers should
     * use {@link TaskQueryService} if they still need bounded projection reads
     * during migration.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    TaskMsg getTaskMessageProjection(String taskId, String messageId) {
        Task task = getTask(taskId);
        TaskMsg projection = getStoredTaskMessageProjection(taskId, messageId);
        if (task != null && (task.getStatus() == null || !task.getStatus().isFinal())) {
            projection = CompatibilityProjectionSupport.overlayActiveLeaseView(
                    projection,
                    getActiveLease(taskId, messageId).orElse(null),
                    taskId,
                    messageId
            );
        }
        return CompatibilityProjectionSupport.overlayTerminalTaskView(task, projection);
    }

    TaskMsg getStoredTaskMessageProjection(String taskId, String messageId) {
        return taskDetailStore.getTaskMessage(taskId, messageId).orElse(null);
    }

    @Deprecated
    @CompatibilityProjectionOnly
    void addTaskMessageProjection(String taskId, TaskMsg taskMsg) {
        taskDetailStore.addTaskMessage(taskId, taskMsg);
    }

    @Deprecated
    @CompatibilityProjectionOnly
    boolean updateTaskMessageProjection(String taskId, TaskMsg taskMsg) {
        return taskDetailStore.updateTaskMessage(taskId, taskMsg);
    }

    @Override
    @Deprecated
    @CompatibilityProjectionOnly
    public void addTaskMessageAttemptAuditProjection(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskDetailStore.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Deprecated
    @CompatibilityProjectionOnly
    List<TaskMsgAttempt> getTaskMessageAttemptAuditTrail(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttempts(taskId, messageId);
    }

    @Deprecated
    @CompatibilityProjectionOnly
    TaskMsgAttempt getLatestTaskMessageAttemptAuditView(String taskId, String messageId) {
        return taskDetailStore.getLatestTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    @Deprecated
    @CompatibilityProjectionOnly
    TaskMsgAttempt getLatestActiveAttemptProjection(String taskId, String messageId) {
        Task task = getTask(taskId);
        if (task != null && task.getStatus() != null && task.getStatus().isFinal()) {
            return null;
        }
        return taskDetailStore.getLatestActiveTaskMessageAttempt(taskId, messageId).orElse(null);
    }

    boolean updateTaskMessageAttemptAuditProjection(String taskId, String messageId, TaskMsgAttempt attempt) {
        return taskDetailStore.updateTaskMessageAttempt(taskId, messageId, attempt);
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
    @Override
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
    TaskDetailStore.TaskMessageStats getTaskMessageStats(String taskId) {
        return taskDetailStore.getTaskMessageStats(taskId);
    }

    @Override
    public int countPendingDispatchableMessages(String taskId) {
        return taskRuntimeBridge.countPendingDispatchableMessages(taskId);
    }

    @Override
    public boolean hasPendingDispatchableMessages(String taskId) {
        return taskRuntimeBridge.hasPendingDispatchableMessages(taskId);
    }

    @Override
    public boolean hasProcessingMessagesForWorker(String taskId, String workerId) {
        return taskRuntimeBridge.hasProcessingMessagesForWorker(taskId, workerId);
    }

    TaskWorkStats getTaskWorkStats(String taskId) {
        return taskRuntimeBridge.getTaskWorkStats(taskId);
    }

    TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats) {
        return taskTerminalPolicy.evaluate(task, stats);
    }

    void publishTaskTerminal(Task task) {
        eventPublisher.publishTaskTerminal(task);
    }

    void publishTaskReady(Task task) {
        eventPublisher.publishTaskReady(task);
    }

    void publishTaskDispatchRequested(Task task) {
        eventPublisher.publishTaskDispatchRequested(task);
    }

    TaskDetailStore.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return taskDetailStore.getTaskMessageAttemptStats(taskId, messageId);
    }

    boolean deleteTaskRecord(String taskId) {
        return taskStorage.deleteTask(taskId);
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

    TaskScheduler getScheduler() {
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
        taskRuntimeBridge.shutdown();
    }

    boolean handleTaskMessageResult(String taskId, String messageId, boolean success, String detail) {
        TaskResultService.TaskMessageMutationOutcome outcome = withTaskMessageReadLock(taskId, messageId,
                () -> resultService.handleTaskMessageResult(taskId, messageId, success, detail));
        if (outcome.progressDirty()) {
            updateTaskProgress(taskId);
        }
        return outcome.accepted();
    }

    boolean handleTaskMessageResult(String taskId, String messageId, boolean success, String detail, String errorCode) {
        TaskResultService.TaskMessageMutationOutcome outcome = withTaskMessageReadLock(taskId, messageId,
                () -> resultService.handleTaskMessageResult(taskId, messageId, success, detail, errorCode));
        if (outcome.progressDirty()) {
            updateTaskProgress(taskId);
        }
        return outcome.accepted();
    }

    boolean handleTaskMessageResult(String taskId,
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
        return concurrencyCoordinator.withTaskWriteLock(taskId, action);
    }

    void withTaskLock(String taskId, Runnable action) {
        withTaskLock(taskId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T withTaskMessageReadLock(String taskId, String messageId, Supplier<T> action) {
        return concurrencyCoordinator.withTaskMessageReadLock(taskId, messageId, action);
    }

    private void reconcileTaskProgress(String taskId) {
        concurrencyCoordinator.reconcileTaskProgress(taskId, () -> resolveTaskProgressUnderTaskLock(taskId));
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

    TaskWorkRuntime getTaskWorkRuntime() {
        return taskRuntimeBridge.runtime();
    }

    com.xa.mass.engine.util.TraceEventLogger traceEvents() {
        return traceEventLogger;
    }

    /**
     * Engine-owned dispatch entry for task-ready and refill-style redispatch.
     *
     * <p>This is a runtime orchestration method, not a public business API
     * contract.
     */
    @Override
    public void requestTaskDispatch(Task task) {
        dispatchRequestService.requestImmediate(task);
    }

    void requestTaskRetryDispatch(Task task, long delayMillis) {
        dispatchRequestService.requestDelayed(task, delayMillis);
    }

    @Override
    public List<ClaimedTaskWork> claimReady(String taskId,
                                            List<WorkerClaimTarget> claimTargets,
                                            TaskWorkClaimOptions claimOptions) {
        return taskRuntimeBridge.claimReady(taskId, claimTargets, claimOptions);
    }

    java.util.Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
        return taskRuntimeBridge.getActiveLease(taskId, messageId);
    }

    @Override
    public List<ActiveLeaseRecord> getActiveLeases(String taskId) {
        return taskRuntimeBridge.getActiveLeases(taskId);
    }

    @Override
    public List<ActiveLeaseRecord> pollExpiredLeases(int limit, java.time.Instant now) {
        return taskRuntimeBridge.pollExpiredLeases(limit, now);
    }

    void discardTaskRuntime(String taskId) {
        taskRuntimeBridge.discardTaskRuntime(taskId);
    }

    ResultApplyOutcome applyTaskWorkResult(TaskWorkResult result) {
        return taskRuntimeBridge.applyTaskWorkResult(result);
    }

    @Override
    public boolean compensateDispatchSubmitFailure(Task task,
                                                   List<TaskDispatchBinding> dispatchBindings,
                                                   String detail) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return true;
        }
        boolean progressDirty = false;
        for (TaskDispatchBinding dispatchBinding : dispatchBindings) {
            if (dispatchBinding == null || dispatchBinding.messageId() == null) {
                continue;
            }
            String messageId = dispatchBinding.messageId();
            TaskResultService.TaskMessageMutationOutcome outcome = withTaskMessageReadLock(task.getTid(), messageId,
                    () -> resultService.compensateDispatchSubmitFailure(task, dispatchBinding, detail));
            if (!outcome.accepted()) {
                return false;
            }
            progressDirty |= outcome.progressDirty();
        }
        if (progressDirty) {
            updateTaskProgress(task.getTid());
        }
        return true;
    }

    void publishTaskMessageAttemptClosed(Task task, TaskMessageAttemptClosedEvent event) {
        eventPublisher.publishTaskMessageAttemptClosed(task, event);
    }

    void publishTaskMessageLogicallyFinal(Task task, TaskMessageLogicallyFinalEvent event) {
        eventPublisher.publishTaskMessageLogicallyFinal(task, event);
    }

    private void ingestInitialInputs(String taskId, TaskCreateRequestDto dto, List<Map<String, Object>> inputs) {
        for (Map<String, Object> input : inputs) {
            String messageId = java.util.UUID.randomUUID().toString();
            addTaskInput(taskId, messageId, input, dto.getDefaultMsgMaxRetryCount());
        }
    }

    private WorkEnqueueOutcome enqueueTaskWork(RuntimeTaskIngressItem ingressItem) {
        return taskRuntimeBridge.enqueueTaskWork(ingressItem);
    }
}



