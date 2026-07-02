package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.model.*;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskAppendOutcome;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.engine.model.TaskDefinitionPatch;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.policy.AllWorkFinalTaskTerminalPolicy;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.runtime.scheduling.TaskPolicyPresetDefinition;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.kernel.spi.task.TaskShellRuntimeLifecycleQuery;
import com.xa.mass.kernel.spi.task.TaskShellRuntimeStore;
import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;
import com.xa.mass.trace.sink.ExecutionEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Internal engine orchestration facade and composition root for task lifecycle
 * and runtime-bridge wiring.
 *
 * <p>This remains the owner of engine assembly semantics, but it is not the
 * preferred cross-module caller surface for shell, SDK, transport, or testing
 * flows. Downstream callers should prefer {@link TaskCommandPort},
 * {@link TaskQueryPort}, {@link TaskResultIngestFacade},
 * {@link TaskShellLifecycleMaintenancePort}, and {@link TaskEventService}.
 */
public class TaskManager implements TaskShellLifecycleMaintenancePort, TaskStateRuntimePort, TaskQueryPort, TaskCommandPort {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);
    public static final int MAX_INGEST_BATCH_ITEMS = Integer.getInteger("xa.mass.engine.maxIngestBatchItems", 10_000);

    private final TaskShellRuntimeStore taskStorage;
    private final TaskShellRuntimeLifecycleQuery taskShellLifecycleQuery;
    private final TaskTerminalPolicy taskTerminalPolicy;
    private final TaskEventPublisher eventPublisher;
    private final TaskStateResolver stateResolver;
    private final TaskStateValidator stateValidator;
    private final TaskLifecycleService lifecycleService;
    private final SchedulingPlaneResolver schedulingPlaneResolver;
    private final TaskConcurrencyStrategy concurrencyCoordinator;
    private final com.xa.mass.engine.TraceEventLogger traceEventLogger;
    private TaskDispatchWakeupPort dispatchWakeupPort;
    private Consumer<String> taskProgressUpdater;
    private Function<String, TaskStateResolutionResult> taskStateResolutionOwner;
    private TaskRuntimeServingLane taskRuntimeServingLane;
    private long workLeaseSeconds = 300L;

    public TaskManager(TaskShellRuntimeStore taskStorage,
                       ExecutionEventSink executionEventSink) {
        this(taskStorage, requireLifecycleQuery(taskStorage), new ContractAwareTaskTerminalPolicy(), executionEventSink);
    }

    public TaskManager(TaskShellRuntimeStore taskStorage,
                       TaskShellRuntimeLifecycleQuery taskShellLifecycleQuery,
                       TaskTerminalPolicy taskTerminalPolicy,
                       ExecutionEventSink executionEventSink) {
        this.taskStorage = Objects.requireNonNull(taskStorage, "taskStorage");
        this.taskShellLifecycleQuery = Objects.requireNonNull(taskShellLifecycleQuery, "taskShellLifecycleQuery");
        this.taskTerminalPolicy = Objects.requireNonNull(taskTerminalPolicy, "taskTerminalPolicy");
        this.traceEventLogger = new com.xa.mass.engine.TraceEventLogger(executionEventSink);
        this.eventPublisher = new TaskEventPublisher();
        this.stateResolver = new TaskStateResolver(
                this,
                this::persistTaskShell,
                this::publishTaskTerminal,
                traceEventLogger
        );
        this.stateValidator = new TaskStateValidator(
                this,
                traceEventLogger
        );
        this.schedulingPlaneResolver = new DefaultSchedulingPlaneResolver();
        this.concurrencyCoordinator = new LocalTaskConcurrencyCoordinator();
        this.lifecycleService = new TaskLifecycleService(
                this::getTask,
                this::persistTaskShell,
                this::syncRuntimeSchedulerEligibility,
                this::publishTaskReady,
                this::publishTaskTerminal,
                this::getTaskRuntimeProgressSnapshot,
                this::evaluateTerminalPolicy,
                this::addRuntimeIngressItems,
                this::validateRuntimeAppendAdmission,
                this::requestTaskDispatch,
                this::updateTaskProgress,
                this::deleteTaskRecord,
                this::discardTaskRuntime,
                this::discardTaskWork,
                traceEventLogger,
                MAX_INGEST_BATCH_ITEMS
        );
        this.dispatchWakeupPort = new TaskDispatchWakeupPort() {
            @Override
            public boolean hasDispatchReadyWork(String taskId) {
                return requireTaskRuntimeServingLane("hasDispatchReadyWork").hasDispatchReadyWork(taskId);
            }

            @Override
            public void requestTaskDispatch(Task task) {
                requireTaskRuntimeServingLane("requestTaskDispatch").requestTaskDispatch(task);
            }
        };
        this.taskProgressUpdater = stateResolver::updateTaskProgress;
        this.taskStateResolutionOwner = stateResolver::resolveTaskState;
    }

    @Override
    public TaskCommandOutcome createTaskShell(TaskShellCreateRequestDto dto) {
        validateTaskShellCreateRequest(dto);
        long startTime = System.currentTimeMillis();
        LogUtils.logOperationStart("CREATE_TASK_SHELL", "TaskManager",
                "project", dto.getProject(),
                "routingCode", TaskSharedConfig.stringValue(dto.getSharedConfig(), TaskSharedConfig.ROUTING_CODE));

        try {
            Task task = createTaskShellInternal(dto);
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationSuccess("task shell created: taskId=" + task.getTid()
                    + ", contract=" + task.getContract()
                    + ", intakeStatus=" + task.getIntakeStatus(), duration);
            return TaskCommandOutcome.applied(task.getTid(), "TASK_CREATED", "Task shell created");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_CREATE_ERROR", e.getMessage(), duration);
            logger.error("Failed to create task shell", e);
            throw e;
        }
    }

    /**
     * Returns a task by id or {@code null} if it does not exist.
     */
    @Override
    public Task getTask(String taskId) {
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
    boolean persistTaskShell(Task task) {
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

    @Override
    public TaskCommandOutcome patchTaskDefinition(String taskId, TaskDefinitionPatch patch) {
        Objects.requireNonNull(patch, "patch");
        return withTaskLock(taskId, () -> {
            Task task = taskStorage.getTask(taskId).orElse(null);
            if (task == null) {
                return TaskCommandOutcome.notFound(taskId);
            }
            if (patch.project() != null) {
                task.setProject(patch.project());
            }
            if (patch.sharedConfig() != null) {
                task.setSharedConfig(patch.sharedConfig());
            }
            if (patch.userId() != null) {
                task.setUser(UserRef.of(patch.userId()));
            }
            if (persistTaskShell(task)) {
                return TaskCommandOutcome.applied(taskId, "TASK_DEFINITION_PATCHED", "Task definition patched");
            }
            return TaskCommandOutcome.conflict(taskId, "TASK_DEFINITION_PATCH_FAILED",
                    "Task definition patch failed");
        });
    }

    /**
     * Deletes a task if it is still safe to remove.
     */
    @Override
    public TaskCommandOutcome deleteTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.deleteTask(taskId));
    }

    @Override
    public List<Task> pollTasksPastMaxRuntimeDeadline(LocalDateTime now, int limit) {
        return taskShellLifecycleQuery.pollTasksPastMaxRuntimeDeadline(now, limit);
    }

    @Override
    public TaskCommandOutcome approveTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.approveTask(taskId));
    }

    @Override
    public TaskCommandOutcome rejectTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.rejectTask(taskId));
    }

    @Override
    public TaskCommandOutcome blockTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.blockTask(taskId));
    }

    @Override
    public TaskCommandOutcome pauseTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.pauseTask(taskId));
    }

    @Override
    public TaskCommandOutcome resumeTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.resumeTask(taskId));
    }

    /**
     * Manually terminates a non-final task (operator/user-initiated cancellation).
     */
    @Override
    public TaskCommandOutcome cancelTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.cancelTask(taskId));
    }

    /**
     * Policy-driven task termination (e.g. max-runtime exceeded, success-rate reached).
     */
    @Override
    public TaskCommandOutcome terminateTask(String taskId, TaskTerminalReason reason) {
        return withTaskLock(taskId, () -> lifecycleService.terminateTask(taskId, reason));
    }

    /**
     * Appends new work items to a READY or RUNNING task whose intake window is still open.
     */
    @Override
    public TaskAppendOutcome appendTaskItems(String taskId, List<java.util.Map<String, Object>> items) {
        return withTaskLock(taskId, () -> lifecycleService.appendTaskItems(taskId, items));
    }

    /**
     * Closes the current task intake window.
     */
    @Override
    public TaskCommandOutcome sealTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.sealTask(taskId));
    }

    void addRuntimeIngressItems(Task task, List<RuntimeTaskIngressItem> ingressItems) {
        requireTaskRuntimeServingLane("addRuntimeIngressItems").appendRuntimeIngressItems(task, ingressItems);
    }

    public long getWorkLeaseSeconds() {
        return workLeaseSeconds;
    }

    public void setWorkLeaseSeconds(long workLeaseSeconds) {
        if (workLeaseSeconds <= 0) {
            throw new IllegalArgumentException("workLeaseSeconds must be greater than 0");
        }
        this.workLeaseSeconds = workLeaseSeconds;
    }

    public void installTaskRuntimeServingLane(TaskRuntimeServingLane servingLane) {
        Objects.requireNonNull(servingLane, "servingLane");
        this.taskRuntimeServingLane = servingLane;
        this.dispatchWakeupPort = servingLane;
        this.taskProgressUpdater = servingLane::updateTaskProgress;
        this.taskStateResolutionOwner = servingLane::resolveTaskState;
    }

    public TaskRuntimeServingLane createTaskRuntimeServingLane(TaskRuntimeWorkPort workPort,
                                                              TaskRuntimeScorePort scorePort,
                                                              TaskRuntimeConvergencePort convergencePort,
                                                              TaskRuntimeReadPort readPort,
                                                              TaskRuntimeResultWindowReadModel resultWindowReadModel,
                                                              TaskTerminalPolicy terminalPolicy,
                                                              SchedulingPlaneResolver schedulingPlaneResolver,
                                                              TraceEventLogger traceEventLogger,
                                                              long workLeaseSeconds,
                                                              int maxAppendBatchSize,
                                                              long finalResultRetentionMillis) {
        return TaskRuntimeServingLane.forShellHooks(
                workPort,
                scorePort,
                convergencePort,
                readPort,
                resultWindowReadModel,
                this::getTask,
                this::persistTaskShell,
                this::publishTaskDispatchRequested,
                this::publishTaskTerminal,
                this::publishTaskWorkAttemptClosed,
                this::publishTaskWorkLogicallyFinal,
                terminalPolicy,
                schedulingPlaneResolver,
                traceEventLogger,
                workLeaseSeconds,
                maxAppendBatchSize,
                finalResultRetentionMillis,
                System::currentTimeMillis);
    }

    void validateRuntimeAppendAdmission(Task task, int itemCount) {
        if (task == null || itemCount <= 0) {
            return;
        }
        requireTaskRuntimeServingLane("validateRuntimeAppendAdmission").validateRuntimeAppendAdmission(task, itemCount);
    }

    void syncRuntimeSchedulerEligibility(Task task) {
        if (task == null) {
            return;
        }
        requireTaskRuntimeServingLane("syncRuntimeSchedulerEligibility").syncSchedulerEligibility(task);
    }

    @Override
    public TaskRuntimeProgressSnapshot getTaskRuntimeProgressSnapshot(String taskId) {
        return requireTaskRuntimeServingLane("getTaskRuntimeProgressSnapshot").getTaskRuntimeProgressSnapshot(taskId);
    }

    @Override
    public TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskRuntimeProgressSnapshot stats) {
        ResolvedTaskSchedulingPolicy taskPolicy = schedulingPlaneResolver.resolve(task).taskSchedulingPolicy();
        return taskTerminalPolicy.evaluate(task, stats, taskPolicy.idleClosePolicy());
    }

    ResolvedTaskSchedulingPolicy resolveTaskSchedulingPolicy(Task task) {
        return schedulingPlaneResolver.resolve(task).taskSchedulingPolicy();
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
        taskProgressUpdater.accept(taskId);
    }

    /**
     * Resolves task state explicitly from runtime-owned work stats plus the
     * persisted task aggregate.
     */
    @Override
    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return withTaskLock(taskId, () -> taskStateResolutionOwner.apply(taskId));
    }

    /**
     * Audit-only invariant validation. This path is intentionally bounded and
     * should not be treated as a hot-path runtime query surface. This method
     * validates task/runtime aggregates without scanning the full
     * compatibility work projection residue.
     */
    @Override
    public TaskStateValidationResult validateTaskState(String taskId) {
        return withTaskLock(taskId, () -> stateValidator.validateTaskState(taskId));
    }

    /**
     * Returns the in-process runtime event surface for synchronous engine
     * reactions such as assignment submission and resource release.
     */
    TaskEventPublisher events() {
        return eventPublisher;
    }

    public void shutdown() {
        taskRuntimeServingLane = null;
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

    private void reconcileTaskProgress(String taskId) {
        concurrencyCoordinator.reconcileTaskProgress(taskId, () -> resolveTaskProgressUnderTaskLock(taskId));
    }

    private void validateTaskShellCreateRequest(TaskShellCreateRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        ProjectRef.require(dto.getProject());
        UserRef.requireUserId(dto.getUserId());
        if (dto.getTenantId() == null || dto.getTenantId().isBlank()) {
            dto.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
        }
        TaskExecutionSpec normalizedSpec = TaskExecutionSpec.normalized(dto.getExecutionSpec());
        TaskContract contract = resolveShellContract(dto);
        normalizedSpec.setWorkloadClass(resolveWorkloadClass(contract, normalizedSpec));
        dto.setContract(contract);
        dto.setExecutionSpec(normalizedSpec);
    }

    private TaskContract resolveShellContract(TaskShellCreateRequestDto dto) {
        if (dto != null && dto.getContract() != null) {
            return dto.getContract();
        }
        return TaskPolicyPresetDefinition.defaultContract(null);
    }

    private com.xa.mass.base.enums.task.TaskWorkloadClass resolveWorkloadClass(TaskContract contract,
                                                                               TaskExecutionSpec normalizedSpec) {
        if (normalizedSpec != null && normalizedSpec.getWorkloadClass() != null) {
            return normalizedSpec.getWorkloadClass();
        }
        return TaskPolicyPresetDefinition.forContract(contract).defaultRuntimeProfile().workloadClass();
    }

    private String normalizeSourceRef(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return null;
        }
        return sourceRef.trim();
    }

    com.xa.mass.engine.TraceEventLogger traceEvents() {
        return traceEventLogger;
    }

    /**
     * Engine-owned dispatch entry for task-ready and refill-style redispatch.
     *
     * <p>This is a runtime orchestration method, not a public business API
     * contract.
     */
    void requestTaskDispatch(Task task) {
        dispatchWakeupPort.requestTaskDispatch(task);
    }

    void discardTaskRuntime(String taskId) {
        requireTaskRuntimeServingLane("discardTaskRuntime").discardTaskRuntime(taskId, "task deleted");
    }

    void discardTaskWork(String taskId) {
        requireTaskRuntimeServingLane("discardTaskWork").discardTaskWork(taskId, "task terminal cleanup");
    }

    private TaskRuntimeServingLane requireTaskRuntimeServingLane(String operation) {
        if (taskRuntimeServingLane == null) {
            throw new IllegalStateException(operation
                    + " requires TaskRuntimeServingLane; old TaskWorkRuntime/TaskResultRuntime path has been deleted");
        }
        return taskRuntimeServingLane;
    }

    void publishTaskWorkAttemptClosed(Task task, TaskWorkAttemptClosedEvent event) {
        eventPublisher.publishTaskWorkAttemptClosed(task, event);
    }

    void publishTaskWorkLogicallyFinal(Task task, TaskWorkLogicallyFinalEvent event) {
        eventPublisher.publishTaskWorkLogicallyFinal(task, event);
    }

    private Task createTaskShellInternal(TaskShellCreateRequestDto dto) {
        return createTaskRecord(dto, resolveInitialIntakeStatus());
    }

    private Task createTaskRecord(TaskShellCreateRequestDto dto,
                                  TaskIntakeStatus intakeStatus) {
        String tid = java.util.UUID.randomUUID().toString();
        LogUtils.setTaskId(tid);
        UserRef user = UserRef.of(dto.getUserId());
        LogUtils.setUserId(dto.getUserId());

        Task task = new Task(
                tid,
                deriveTaskName(dto, tid),
                dto.getProject(),
                0,
                dto.getSharedConfig() != null ? dto.getSharedConfig() : new java.util.HashMap<>(),
                user
        );
        task.setTenantId(dto.getTenantId());
        task.setContract(dto.getContract());
        task.setExecutionSpec(TaskExecutionSpec.normalized(dto.getExecutionSpec()));
        task.setSourceRef(normalizeSourceRef(dto.getSourceRef()));
        task.setIntakeStatus(intakeStatus);
        taskStorage.saveTask(task);
        eventPublisher.publishTaskCreated(task);
        return task;
    }

    private TaskIntakeStatus resolveInitialIntakeStatus() {
        return TaskIntakeStatus.OPEN;
    }

    private String deriveTaskName(TaskShellCreateRequestDto dto, String taskId) {
        String project = dto.getProject() != null ? dto.getProject().trim() : "task";
        TaskContract contract = TaskPolicyPresetDefinition.defaultContract(dto.getContract());
        String normalizedContract = contract.name().toLowerCase(java.util.Locale.ROOT);
        String profile = dto.getExecutionSpec() != null && dto.getExecutionSpec().getProfile() != null
                ? dto.getExecutionSpec().getProfile().name().toLowerCase(java.util.Locale.ROOT)
                : "standard";
        String sourceRef = normalizeSourceRef(dto.getSourceRef());
        String sourceHint = sourceRef == null ? null : basename(sourceRef);
        String shortTaskId = taskId.length() <= 8 ? taskId : taskId.substring(0, 8);
        if (sourceHint != null && !sourceHint.isBlank()) {
            return project + "-" + normalizedContract + "-" + profile + "-" + sourceHint + "-" + shortTaskId;
        }
        return project + "-" + normalizedContract + "-" + profile + "-" + shortTaskId;
    }

    private String basename(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String sanitized = leaf.replaceAll("[^A-Za-z0-9._-]", "-");
        return sanitized.isBlank() ? null : sanitized;
    }

    private static TaskShellRuntimeLifecycleQuery requireLifecycleQuery(TaskShellRuntimeStore taskStorage) {
        if (taskStorage instanceof TaskShellRuntimeLifecycleQuery lifecycleQuery) {
            return lifecycleQuery;
        }
        throw new IllegalArgumentException("taskStorage must implement TaskShellRuntimeLifecycleQuery");
    }

}
