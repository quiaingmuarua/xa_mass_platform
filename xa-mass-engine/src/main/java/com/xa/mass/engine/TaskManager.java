package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.base.model.*;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskAppendReceipt;
import com.xa.mass.engine.model.TaskDefinitionPatch;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.policy.AllWorkFinalTaskTerminalPolicy;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.engine.policy.TaskPolicyPresetSemantics;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeEnqueueOptionsResolver;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.kernel.spi.task.TaskShellRuntimeLifecycleQuery;
import com.xa.mass.kernel.spi.task.TaskShellRuntimeStore;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.BarrierClaimStatus;
import com.xa.mass.runtime.api.BarrierMarkResult;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.RecentFinalWorkReceipt;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.RuntimeResultApplyContext;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultWindow;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
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
 * Internal engine orchestration facade and composition root for task lifecycle
 * and runtime-bridge wiring.
 *
 * <p>This remains the owner of engine assembly semantics, but it is not the
 * preferred cross-module caller surface for shell, SDK, transport, or testing
 * flows. Downstream callers should prefer {@link TaskCommandService},
 * {@link TaskQueryService}, {@link TaskResultIngestFacade},
 * {@link TaskAssignmentRuntimePort}, {@link TaskLeaseMaintenancePort},
 * {@link TaskDispatchWakeupPort}, {@link TaskShellLifecycleMaintenancePort},
 * {@link TaskRuntimeRecoveryPort}, and {@link TaskEventService}.
 */
public class TaskManager implements TaskAssignmentRuntimePort, TaskLeaseMaintenancePort, TaskDispatchWakeupPort, TaskShellLifecycleMaintenancePort, TaskRuntimeRecoveryPort, TaskStateRuntimePort, TaskQueryPort, TaskCommandPort, TaskResultIngestPort {

    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);
    static final int MAX_INGEST_BATCH_ITEMS = Integer.getInteger("xa.mass.engine.maxIngestBatchItems", 10_000);

    private final TaskShellRuntimeStore taskStorage;
    private final TaskShellRuntimeLifecycleQuery taskShellLifecycleQuery;
    private final TaskTerminalPolicy taskTerminalPolicy;
    private final TaskEventPublisher eventPublisher;
    private final TaskStateResolver stateResolver;
    private final TaskStateValidator stateValidator;
    private final TaskDispatchRequestService dispatchRequestService;
    private final TaskLifecycleService lifecycleService;
    private final TaskResultService resultService;
    private final TaskWorkRuntime taskWorkRuntime;
    private final TaskResultRuntime taskResultRuntime;
    private final TaskRuntimeEnqueueOptionsResolver enqueueOptionsResolver;
    private final SchedulingPlaneResolver schedulingPlaneResolver;
    private final TaskConcurrencyStrategy concurrencyCoordinator;
    private final VirtualThreadRuntimeTaskExecutor retryWakeupExecutor;
    private final com.xa.mass.engine.TraceEventLogger traceEventLogger;
    private long workLeaseSeconds = 300L;

    public TaskManager(TaskShellRuntimeStore taskStorage,
                       TaskWorkRuntime taskWorkRuntime,
                       TaskResultRuntime taskResultRuntime,
                       ExecutionEventSink executionEventSink) {
        this(taskStorage, requireLifecycleQuery(taskStorage), new ContractAwareTaskTerminalPolicy(), taskWorkRuntime,
                taskResultRuntime, executionEventSink);
    }

    public TaskManager(TaskShellRuntimeStore taskStorage,
                       TaskShellRuntimeLifecycleQuery taskShellLifecycleQuery,
                       TaskTerminalPolicy taskTerminalPolicy,
                       TaskWorkRuntime taskWorkRuntime,
                       TaskResultRuntime taskResultRuntime,
                       ExecutionEventSink executionEventSink) {
        this.taskStorage = Objects.requireNonNull(taskStorage, "taskStorage");
        this.taskShellLifecycleQuery = Objects.requireNonNull(taskShellLifecycleQuery, "taskShellLifecycleQuery");
        TaskWorkRuntime requiredTaskWorkRuntime = Objects.requireNonNull(taskWorkRuntime, "taskWorkRuntime");
        TaskResultRuntime requiredTaskResultRuntime = Objects.requireNonNull(taskResultRuntime, "taskResultRuntime");
        this.taskTerminalPolicy = Objects.requireNonNull(taskTerminalPolicy, "taskTerminalPolicy");
        this.traceEventLogger = new com.xa.mass.engine.TraceEventLogger(executionEventSink);
        this.eventPublisher = new TaskEventPublisher();
        this.stateResolver = new TaskStateResolver(
                this,
                traceEventLogger
        );
        this.stateValidator = new TaskStateValidator(
                this,
                traceEventLogger
        );
        this.taskWorkRuntime = requiredTaskWorkRuntime;
        this.taskResultRuntime = requiredTaskResultRuntime;
        this.enqueueOptionsResolver = new TaskRuntimeEnqueueOptionsResolver();
        this.schedulingPlaneResolver = new DefaultSchedulingPlaneResolver();
        this.concurrencyCoordinator = new LocalTaskConcurrencyCoordinator();
        this.retryWakeupExecutor = new VirtualThreadRuntimeTaskExecutor(
                "engine-retry-wakeup-",
                Integer.getInteger("xa.mass.engine.retryWakeupMaxPendingTasks", 10_000)
        );
        this.dispatchRequestService = new TaskDispatchRequestService(
                this,
                retryWakeupExecutor,
                new LocalDelayedDispatchSchedule(),
                schedulingPlaneResolver
        );
        this.lifecycleService = new TaskLifecycleService(
                this,
                stateResolver,
                traceEventLogger
        );
        this.resultService = new TaskResultService(
                this,
                requiredTaskResultRuntime,
                new TaskRuntimeRetryPolicyResolver(),
                traceEventLogger
        );
    }

    @Override
    public Task createTaskShell(TaskShellCreateRequestDto dto) {
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
            return task;
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

    @Override
    public boolean patchTaskDefinition(String taskId, TaskDefinitionPatch patch) {
        Objects.requireNonNull(patch, "patch");
        return withTaskLock(taskId, () -> {
            Task task = taskStorage.getTask(taskId).orElse(null);
            if (task == null) {
                return false;
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
            return updateTask(task);
        });
    }

    /**
     * Deletes a task if it is still safe to remove.
     */
    @Override
    public boolean deleteTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.deleteTask(taskId));
    }

    @Override
    public List<Task> getRuntimeDispatchableTasks(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return taskWorkRuntime.readyTaskIds(limit).stream()
                .map(taskId -> taskStorage.getTask(taskId).orElse(null))
                .filter(task -> task != null)
                .toList();
    }

    @Override
    public List<Task> pollTasksPastMaxRuntimeDeadline(LocalDateTime now, int limit) {
        return taskShellLifecycleQuery.pollTasksPastMaxRuntimeDeadline(now, limit);
    }

    @Override
    public boolean approveTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.approveTask(taskId));
    }

    @Override
    public boolean rejectTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.rejectTask(taskId));
    }

    @Override
    public boolean blockTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.blockTask(taskId));
    }

    @Override
    public boolean pauseTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.pauseTask(taskId));
    }

    @Override
    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.resumeTaskDetailed(taskId));
    }

    @Override
    public boolean resumeTask(String taskId) {
        return resumeTaskDetailed(taskId).isSuccess();
    }

    /**
     * Manually terminates a non-final task (operator/user-initiated cancellation).
     */
    @Override
    public boolean cancelTask(String taskId) {
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
     * Appends new work items to a READY or RUNNING task whose intake window is still open.
     */
    @Override
    public TaskAppendReceipt appendTaskItemsWithReceipt(String taskId, List<java.util.Map<String, Object>> items) {
        return withTaskLock(taskId, () -> lifecycleService.appendTaskItemsWithReceipt(taskId, items));
    }

    /**
     * Appends new work items to a READY or RUNNING task whose intake window is still open.
     */
    @Override
    public int appendTaskItems(String taskId, List<java.util.Map<String, Object>> items) {
        return appendTaskItemsWithReceipt(taskId, items).added();
    }

    /**
     * Closes the current task intake window.
     */
    @Override
    public boolean sealTask(String taskId) {
        return withTaskLock(taskId, () -> lifecycleService.sealTask(taskId));
    }

    /**
     * Runtime-first ingest for one logical work item.
     */
    void ingestRuntimeInput(String taskId,
                      String messageId,
                      java.util.Map<String, Object> input,
                      int maxRetryCount) {
        addRuntimeIngressItem(RuntimeTaskIngressItem.fromInput(taskId, messageId, input, maxRetryCount));
    }

    void ingestRuntimePayloadRef(String taskId,
                           String messageId,
                           String payloadRef,
                           int maxRetryCount) {
        addRuntimeIngressItem(new RuntimeTaskIngressItem(
                taskId,
                messageId,
                null,
                Map.of(),
                payloadRef,
                0,
                maxRetryCount
        ));
    }

    void addRuntimeIngressItems(Task task, List<RuntimeTaskIngressItem> ingressItems) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        if (ingressItems == null || ingressItems.isEmpty()) {
            throw new IllegalArgumentException("ingressItems must be a non-empty list");
        }
        LogUtils.setTaskId(task.getTid());
        LogUtils.logOperationStart("ADD_RUNTIME_WORK_BATCH", "TaskManager",
                "taskId", task.getTid(),
                "messageCount", String.valueOf(ingressItems.size()));
        for (RuntimeTaskIngressItem ingressItem : ingressItems) {
            if (ingressItem == null) {
                throw new IllegalArgumentException("ingressItems must not contain null");
            }
            if (!task.getTid().equals(ingressItem.taskId())) {
                throw new IllegalArgumentException("ingress item taskId mismatch: expected "
                        + task.getTid() + " but was " + ingressItem.taskId());
            }
            WorkEnqueueOutcome outcome = enqueueTaskWork(task, ingressItem);
            if (outcome != null && outcome.status() != WorkEnqueueStatus.ENQUEUED) {
                throw new IllegalStateException("task work enqueue failed: status="
                        + outcome.status() + ", reason=" + outcome.reason());
            }
        }
        LogUtils.logOperationSuccess("runtime work batch added: count=" + ingressItems.size(), 0);
    }

    private void addRuntimeIngressItem(RuntimeTaskIngressItem ingressItem) {
        if (ingressItem == null) {
            throw new IllegalArgumentException("ingressItem is required");
        }
        Task task = taskStorage.getTask(ingressItem.taskId()).orElse(null);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + ingressItem.taskId());
        }
        addRuntimeIngressItems(task, List.of(ingressItem));
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

    /**
     * Expires a single in-flight leased work item and recalculates task convergence.
     */
    @Override
    public boolean expireLeasedWork(String taskId, String messageId) {
        TaskResultService.ResultMutationOutcome outcome = withTaskWorkReadLock(taskId, messageId,
                () -> resultService.expireLeasedWork(taskId, messageId));
        if (outcome.progressDirty()) {
            applyResultProgress(outcome, taskId);
        }
        return outcome.accepted();
    }

    @Override
    public int countDispatchReadyWork(String taskId) {
        long readyCount = taskWorkRuntime.stats(taskId).readyCount();
        return readyCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) readyCount;
    }

    @Override
    public boolean hasDispatchReadyWork(String taskId) {
        return countDispatchReadyWork(taskId) > 0;
    }

    @Override
    public boolean hasActiveWorkForWorker(String taskId, String workerId) {
        return taskWorkRuntime.hasActiveLeaseForWorker(taskId, workerId);
    }

    @Override
    public TaskWorkStats getTaskWorkStats(String taskId) {
        return taskWorkRuntime.stats(taskId);
    }

    @Override
    public TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats) {
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
        stateResolver.updateTaskProgress(taskId);
    }

    /**
     * Resolves task state explicitly from runtime-owned work stats plus the
     * persisted task aggregate.
     */
    @Override
    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return withTaskLock(taskId, () -> stateResolver.resolveTaskState(taskId));
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
        dispatchRequestService.shutdown();
        resultService.shutdown();
        retryWakeupExecutor.shutdown();
        taskResultRuntime.shutdown();
        taskWorkRuntime.shutdown();
    }

    boolean ingestTaskResult(String taskId, String messageId, boolean success, String detail) {
        TaskResultService.ResultMutationOutcome outcome = withTaskWorkReadLock(taskId, messageId,
                () -> resultService.ingestTaskResult(taskId, messageId, success, detail));
        if (outcome.progressDirty()) {
            applyResultProgress(outcome, taskId);
        }
        return outcome.accepted();
    }

    boolean ingestTaskResult(String taskId, String messageId, boolean success, String detail, String errorCode) {
        TaskResultService.ResultMutationOutcome outcome = withTaskWorkReadLock(taskId, messageId,
                () -> resultService.ingestTaskResult(taskId, messageId, success, detail, errorCode));
        if (outcome.progressDirty()) {
            applyResultProgress(outcome, taskId);
        }
        return outcome.accepted();
    }

    @Override
    public boolean ingestTaskResult(String taskId,
                                    String messageId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output) {
        TaskResultService.ResultMutationOutcome outcome = withTaskWorkReadLock(taskId, messageId,
                () -> resultService.ingestTaskResult(taskId, messageId, success, detail, errorCode, output));
        if (outcome.progressDirty()) {
            applyResultProgress(outcome, taskId);
        }
        return outcome.accepted();
    }

    @Override
    public TaskResultCorrelation getResultCorrelation(String taskId, String messageId) {
        return TaskResultCorrelationSupport.fromRuntimeState(
                taskId,
                messageId,
                null,
                getActiveLease(taskId, messageId).orElse(null)
        );
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

    private <T> T withTaskWorkReadLock(String taskId, String messageId, Supplier<T> action) {
        return concurrencyCoordinator.withTaskWorkReadLock(taskId, messageId, action);
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
        return TaskPolicyPresetSemantics.defaultContract(null);
    }

    private com.xa.mass.base.enums.task.TaskWorkloadClass resolveWorkloadClass(TaskContract contract,
                                                                               TaskExecutionSpec normalizedSpec) {
        return TaskPolicyPresetSemantics.defaultWorkloadClassFor(contract, normalizedSpec);
    }

    private String normalizeSourceRef(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return null;
        }
        return sourceRef.trim();
    }

    TaskWorkRuntime getTaskWorkRuntime() {
        return taskWorkRuntime;
    }

    TaskRuntimeEnqueueOptionsResolver enqueueOptionsResolver() {
        return enqueueOptionsResolver;
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
        return taskWorkRuntime.claimReady(taskId, claimTargets, claimOptions);
    }

    java.util.Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
        return taskWorkRuntime.getActiveLease(taskId, messageId);
    }

    java.util.Optional<TaskWorkEnvelope> getTaskWork(String taskId, String messageId) {
        return taskWorkRuntime.getWork(taskId, messageId);
    }

    java.util.Optional<RecentFinalWorkReceipt> getRecentFinalReceipt(String taskId, String messageId) {
        return taskWorkRuntime.getRecentFinalReceipt(taskId, messageId);
    }

    public TaskResultWindow readTaskResultWindow(String taskId, long afterSeq, int limit) {
        return taskResultRuntime.readWindow(taskId, afterSeq, limit);
    }

    public long countTaskResults(String taskId) {
        return taskResultRuntime.countVisibleResults(taskId);
    }

    @Override
    public List<ActiveLeaseRecord> getActiveLeases(String taskId) {
        return taskWorkRuntime.activeLeases(taskId);
    }

    @Override
    public List<ActiveLeaseRecord> pollExpiredLeases(int limit, java.time.Instant now) {
        return taskWorkRuntime.pollExpiredLeases(limit, now);
    }

    void discardTaskRuntime(String taskId) {
        taskWorkRuntime.discardTask(taskId);
        taskResultRuntime.discardTask(taskId);
    }

    ResultApplyOutcome applyTaskWorkResult(TaskWorkResult result) {
        return taskWorkRuntime.applyResult(result);
    }

    /**
     * Single-call atomic equivalent of three separate runtime round-trips
     * ({@code getActiveLease} + {@code getWork} + {@code applyResult}).
     *
     * <p>Returns the pre-apply lease and work snapshot together with the outcome
     * so the engine callback path does not need additional runtime reads after
     * the mutation.</p>
     */
    RuntimeResultApplyContext applyTaskWorkResultWithContext(TaskWorkResult result) {
        return taskWorkRuntime.applyResultWithContext(result);
    }

    TaskResultRuntime getTaskResultRuntime() {
        return taskResultRuntime;
    }

    void applyTaskResultProgressOnce(String taskId, String messageId, long finalSeq) {
        BarrierClaim claim = taskResultRuntime.claimProgressApply(taskId, messageId, finalSeq);
        if (!claim.claimedByCaller()) {
            if (claim.alreadyDoneByAnotherCaller()) {
                cleanupResultStageIfConverged(taskId, messageId);
                return;
            }
            if (claim.status() == BarrierClaimStatus.BUSY) {
                updateTaskProgress(taskId);
            }
            return;
        }
        updateTaskProgress(taskId);
        BarrierMarkResult markResult = taskResultRuntime.markProgressApplied(
                taskId, messageId, finalSeq, claim.claimToken());
        if (!markResult.completed()) {
            logger.warn("Task result progress barrier mark did not complete for taskId={}, messageId={}, seq={}, status={}, reason={}",
                    taskId, messageId, finalSeq, markResult.status(), markResult.reason());
            return;
        }
        cleanupResultStageIfConverged(taskId, messageId);
    }

    private void cleanupResultStageIfConverged(String taskId, String messageId) {
        taskResultRuntime.getVisibleByMessageId(taskId, messageId)
                .filter(row -> row.attemptClosedPublished() && row.logicalFinalPublished() && row.progressApplied())
                .ifPresent(row -> taskResultRuntime.discardStagedCallbacksForMessage(taskId, messageId));
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
            TaskResultService.ResultMutationOutcome outcome = withTaskWorkReadLock(task.getTid(), messageId,
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

    private void applyResultProgress(TaskResultService.ResultMutationOutcome outcome, String taskId) {
        if (outcome.hasProgressBarrier()) {
            applyTaskResultProgressOnce(outcome.progressTaskId(), outcome.progressMessageId(), outcome.progressSeq());
            return;
        }
        updateTaskProgress(taskId);
    }

    void publishTaskWorkAttemptClosed(Task task, TaskWorkAttemptClosedEvent event) {
        eventPublisher.publishTaskWorkAttemptClosed(task, event);
    }

    void publishTaskWorkLogicallyFinal(Task task, TaskWorkLogicallyFinalEvent event) {
        eventPublisher.publishTaskWorkLogicallyFinal(task, event);
    }

    private WorkEnqueueOutcome enqueueTaskWork(Task task, RuntimeTaskIngressItem ingressItem) {
        if (ingressItem == null || ingressItem.messageId() == null || ingressItem.messageId().isBlank()) {
            return null;
        }
        TaskWorkEnvelope item = new TaskWorkEnvelope(
                ingressItem.taskId(),
                ingressItem.messageId(),
                ingressItem.eventCode() != null ? ingressItem.eventCode()
                        : task != null ? TaskSharedConfig.sdkEventCode(task) : null,
                ingressItem.inlinePayload(),
                ingressItem.payloadRef(),
                ingressItem.retryCount(),
                ingressItem.maxRetryCount(),
                null,
                null,
                java.time.Instant.now()
        );
        return taskWorkRuntime.enqueue(item, enqueueOptionsResolver.resolve(resolveTaskSchedulingPolicy(task)));
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
        TaskContract contract = TaskPolicyPresetSemantics.defaultContract(dto.getContract());
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
