package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchDeliveryFailure;
import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.runtime.TaskRuntimePolicySnapshotMapper;
import com.xa.mass.engine.runtime.TaskRuntimeResultCommandMapper;
import com.xa.mass.engine.runtime.TaskRuntimeResultDecision;
import com.xa.mass.engine.runtime.TaskRuntimeResultDecisionMapper;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ActiveTaskWorkQuery;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.DiscardTaskRuntimeCommand;
import com.xa.mass.task.runtime.DiscardTaskWorkCommand;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.ResultApplyCommand;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.SchedulerDiscoveryCommand;
import com.xa.mass.task.runtime.SchedulerEligibilityPolicy;
import com.xa.mass.task.runtime.TaskRuntimeAppendPort;
import com.xa.mass.task.runtime.TaskRuntimeClaimPort;
import com.xa.mass.task.runtime.TaskRuntimeDiscardPort;
import com.xa.mass.task.runtime.TaskRuntimeProgressPort;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeRepairPort;
import com.xa.mass.task.runtime.TaskRuntimeResultPort;
import com.xa.mass.task.runtime.TaskRuntimeSchedulerPort;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.UpdateSchedulerEligibilityCommand;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Engine-facing serving lane for the new task-runtime owner.
 *
 * <p>This class intentionally implements the existing engine hot-path ports so
 * production callers can be moved by port owner replacement instead of by
 * running a second scheduler/result path beside the old runtime.</p>
 */
public final class TaskRuntimeServingLane implements TaskAssignmentRuntimePort,
        TaskLeaseMaintenancePort,
        TaskDispatchWakeupPort,
        TaskRuntimeRecoveryPort,
        TaskResultIngestPort,
        TaskStateRuntimePort {

    private static final int ACTIVE_QUERY_LIMIT =
            Integer.getInteger("xa.mass.taskRuntime.activeQueryLimit", 100_000);
    private static final long DEFAULT_FINAL_RESULT_RETENTION_MILLIS =
            Long.getLong("xa.mass.taskRuntime.finalResultRetentionMillis", 86_400_000L);

    private final TaskRuntimeAppendPort appendPort;
    private final TaskRuntimeSchedulerPort schedulerPort;
    private final TaskRuntimeClaimPort claimPort;
    private final TaskRuntimeResultPort resultPort;
    private final TaskRuntimeRepairPort repairPort;
    private final TaskRuntimeProgressPort progressPort;
    private final TaskRuntimeReadPort readPort;
    private final TaskRuntimeDiscardPort discardPort;
    private final TaskQueryService taskQueries;
    private final TaskCommandService taskCommands;
    private final TaskEventService taskEvents;
    private final TaskTerminalPolicy terminalPolicy;
    private final SchedulingPlaneResolver schedulingPlaneResolver;
    private final TraceEventLogger traceEventLogger;
    private final TaskStateResolver stateResolver;
    private final long workLeaseSeconds;
    private final int maxAppendBatchSize;
    private final long finalResultRetentionMillis;

    public TaskRuntimeServingLane(TaskRuntimeAppendPort appendPort,
                                  TaskRuntimeSchedulerPort schedulerPort,
                                  TaskRuntimeClaimPort claimPort,
                                  TaskRuntimeResultPort resultPort,
                                  TaskRuntimeRepairPort repairPort,
                                  TaskRuntimeProgressPort progressPort,
                                  TaskQueryService taskQueries,
                                  TaskCommandService taskCommands,
                                  TaskEventService taskEvents,
                                  long workLeaseSeconds,
                                  int maxAppendBatchSize,
                                  long finalResultRetentionMillis) {
        this(appendPort,
                schedulerPort,
                claimPort,
                resultPort,
                repairPort,
                progressPort,
                requireReadPort(progressPort),
                requireDiscardPort(progressPort),
                taskQueries,
                taskCommands,
                taskEvents,
                new ContractAwareTaskTerminalPolicy(),
                new DefaultSchedulingPlaneResolver(),
                TraceEventLogger.noop(),
                workLeaseSeconds,
                maxAppendBatchSize,
                finalResultRetentionMillis);
    }

    public TaskRuntimeServingLane(TaskRuntimeAppendPort appendPort,
                                  TaskRuntimeSchedulerPort schedulerPort,
                                  TaskRuntimeClaimPort claimPort,
                                  TaskRuntimeResultPort resultPort,
                                  TaskRuntimeRepairPort repairPort,
                                  TaskRuntimeProgressPort progressPort,
                                  TaskRuntimeReadPort readPort,
                                  TaskRuntimeDiscardPort discardPort,
                                  TaskQueryService taskQueries,
                                  TaskCommandService taskCommands,
                                  TaskEventService taskEvents,
                                  TaskTerminalPolicy terminalPolicy,
                                  SchedulingPlaneResolver schedulingPlaneResolver,
                                  TraceEventLogger traceEventLogger,
                                  long workLeaseSeconds,
                                  int maxAppendBatchSize,
                                  long finalResultRetentionMillis) {
        this.appendPort = Objects.requireNonNull(appendPort, "appendPort");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
        this.claimPort = Objects.requireNonNull(claimPort, "claimPort");
        this.resultPort = Objects.requireNonNull(resultPort, "resultPort");
        this.repairPort = Objects.requireNonNull(repairPort, "repairPort");
        this.progressPort = Objects.requireNonNull(progressPort, "progressPort");
        this.readPort = Objects.requireNonNull(readPort, "readPort");
        this.discardPort = Objects.requireNonNull(discardPort, "discardPort");
        this.taskQueries = Objects.requireNonNull(taskQueries, "taskQueries");
        this.taskCommands = Objects.requireNonNull(taskCommands, "taskCommands");
        this.taskEvents = Objects.requireNonNull(taskEvents, "taskEvents");
        this.terminalPolicy = terminalPolicy == null ? new ContractAwareTaskTerminalPolicy() : terminalPolicy;
        this.schedulingPlaneResolver = schedulingPlaneResolver == null
                ? new DefaultSchedulingPlaneResolver()
                : schedulingPlaneResolver;
        this.traceEventLogger = traceEventLogger == null ? TraceEventLogger.noop() : traceEventLogger;
        this.workLeaseSeconds = Math.max(1L, workLeaseSeconds);
        this.maxAppendBatchSize = Math.max(1, maxAppendBatchSize);
        this.finalResultRetentionMillis = Math.max(0L,
                finalResultRetentionMillis > 0L ? finalResultRetentionMillis : DEFAULT_FINAL_RESULT_RETENTION_MILLIS);
        this.stateResolver = new TaskStateResolver(
                this,
                this.taskCommands::updateTask,
                this.taskEvents::publishTaskTerminal,
                this.traceEventLogger);
    }

    void appendRuntimeIngressItems(Task task, List<RuntimeTaskIngressItem> ingressItems) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        if (ingressItems == null || ingressItems.isEmpty()) {
            throw new IllegalArgumentException("ingressItems must be a non-empty list");
        }
        var policy = taskPolicy(task);
        var epoch = epoch(task.getTid());
        var outcome = appendPort.appendBatch(new AppendBatchCommand(
                task.getTid(),
                TaskRuntimeAppendItemMapper.toAppendItems(ingressItems),
                TaskRuntimePolicySnapshotMapper.toAppendAdmissionPolicy(policy, maxAppendBatchSize),
                epoch));
        if (outcome.status() != AppendBatchStatus.ALL_ACCEPTED) {
            throw new IllegalStateException("task-runtime append failed: status="
                    + outcome.status() + ", reason=" + outcome.reason());
        }
        updateSchedulerEligibility(task, epoch);
    }

    void updateTaskProgress(String taskId) {
        stateResolver.updateTaskProgress(taskId);
    }

    void syncSchedulerEligibility(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return;
        }
        updateSchedulerEligibility(task, epoch(task.getTid()));
    }

    TaskStateResolutionResult resolveTaskState(String taskId) {
        return stateResolver.resolveTaskState(taskId);
    }

    @Override
    public int countDispatchReadyWork(String taskId) {
        long readyCount = progressPort.progressSnapshot(taskId).readyCount();
        return readyCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) readyCount;
    }

    @Override
    public int countActiveDispatchWorkers(String taskId) {
        return (int) repairPort.getActiveWorkForTask(new ActiveTaskWorkQuery(taskId, ACTIVE_QUERY_LIMIT))
                .activeItems()
                .stream()
                .map(ActiveLeaseRepairCandidate::workerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .count();
    }

    public long getWorkLeaseSeconds() {
        return workLeaseSeconds;
    }

    @Override
    public boolean updateTask(Task task) {
        return taskCommands.updateTask(task);
    }

    @Override
    public ClaimReadyOutcome claimReady(ClaimReadyCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("claim command is required");
        }
        Task task = taskQueries.getTask(command.taskId());
        if (task == null) {
            return new ClaimReadyOutcome(command.taskId(), List.of(), "task shell not found");
        }
        return claimPort.claimReady(command);
    }

    @Override
    public boolean compensateDispatchSubmitFailure(Task task,
                                                   List<TaskDispatchBinding> dispatchBindings,
                                                   String detail) {
        if (dispatchBindings == null || dispatchBindings.isEmpty()) {
            return true;
        }
        boolean accepted = true;
        for (TaskDispatchBinding binding : dispatchBindings) {
            var outcome = applyResult(task, TaskRuntimeResultCommandMapper.fromDispatchSubmitFailure(
                    binding,
                    taskPolicy(task),
                    epoch(binding.taskId()),
                    System.currentTimeMillis(),
                    detail,
                    1L,
                    finalResultRetentionMillis));
            accepted &= outcome.accepted();
        }
        return accepted;
    }

    @Override
    public boolean compensateDispatchDeliveryFailure(Task task, List<TaskDispatchDeliveryFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return true;
        }
        boolean accepted = true;
        for (TaskDispatchDeliveryFailure failure : failures) {
            ResultCorrelationSnapshot correlation = resultPort.getResultCorrelation(failure.taskId(), failure.messageId());
            if (!correlation.present()) {
                accepted = false;
                continue;
            }
            var outcome = applyResult(task, new ResultApplyCommand(
                    failure.taskId(),
                    failure.messageId(),
                    correlation.leaseToken(),
                    correlation.workerId(),
                    failure.attemptNo(),
                    ResultApplySource.DISPATCH_DELIVERY_FAILURE,
                    false,
                    Map.of(),
                    failure.detail(),
                    TaskRuntimePolicySnapshotMapper.toRetryPolicySnapshot(taskPolicy(task), -1, 1L),
                    TaskRuntimePolicySnapshotMapper.toResultFinalityPolicySnapshot(taskPolicy(task), finalResultRetentionMillis),
                    epoch(failure.taskId()),
                    System.currentTimeMillis()));
            accepted &= outcome.accepted();
        }
        return accepted;
    }

    @Override
    public List<ActiveLeaseRepairCandidate> getActiveLeaseCandidates(String taskId) {
        return List.copyOf(repairPort.getActiveWorkForTask(new ActiveTaskWorkQuery(taskId, ACTIVE_QUERY_LIMIT))
                .activeItems());
    }

    @Override
    public List<ActiveLeaseRepairCandidate> pollExpiredLeaseCandidates(int limit, Instant now) {
        return List.copyOf(repairPort.pollExpiredActiveLeases(new com.xa.mass.task.runtime.PollActiveLeaseRepairCommand(
                        limit,
                        now == null ? System.currentTimeMillis() : now.toEpochMilli()))
                .candidates());
    }

    public List<ActiveLeaseRepairCandidate> getActiveLeases(String taskId) {
        return getActiveLeaseCandidates(taskId);
    }

    public List<ActiveLeaseRepairCandidate> pollExpiredLeases(int limit, Instant now) {
        return pollExpiredLeaseCandidates(limit, now);
    }

    @Override
    public boolean hasActiveWorkForWorker(String taskId, String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return false;
        }
        return repairPort.getActiveWorkForWorker(new com.xa.mass.task.runtime.ActiveWorkQuery(workerId, ACTIVE_QUERY_LIMIT))
                .activeItems()
                .stream()
                .anyMatch(candidate -> taskId.equals(candidate.taskId()));
    }

    @Override
    public boolean expireLeasedWork(String taskId, String messageId) {
        var candidate = findActiveCandidate(taskId, messageId);
        if (candidate == null) {
            return false;
        }
        Task task = taskQueries.getTask(taskId);
        var outcome = applyResult(task, TaskRuntimeResultCommandMapper.fromLeaseTimeout(
                candidate,
                taskPolicy(task),
                epoch(taskId),
                System.currentTimeMillis(),
                "lease expired",
                1L,
                finalResultRetentionMillis));
        return outcome.accepted();
    }

    @Override
    public boolean hasDispatchReadyWork(String taskId) {
        return countDispatchReadyWork(taskId) > 0;
    }

    @Override
    public void requestTaskDispatch(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return;
        }
        updateSchedulerEligibility(task, epoch(task.getTid()));
        if (task.getStatus() != null && task.getStatus().isActive()) {
            taskEvents.publishTaskDispatchRequested(task);
        }
    }

    @Override
    public List<Task> getRuntimeDispatchableTasks(int limit) {
        var candidates = schedulerPort.discoverEligibleTasks(new SchedulerDiscoveryCommand(
                Math.max(1, limit),
                System.currentTimeMillis()));
        List<Task> tasks = new ArrayList<>();
        for (var candidate : candidates.candidates()) {
            Task task = taskQueries.getTask(candidate.taskId());
            if (task != null) {
                tasks.add(task);
            }
        }
        return List.copyOf(tasks);
    }

    @Override
    public boolean ingestTaskResult(String taskId,
                                    String messageId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output) {
        Task task = taskQueries.getTask(taskId);
        var correlation = resultPort.getResultCorrelation(taskId, messageId);
        if (!correlation.present()) {
            return getVisibleTaskResultByMessageId(taskId, messageId).isPresent();
        }
        Map<String, Object> payload = output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output);
        if (errorCode != null && !errorCode.isBlank()) {
            payload.putIfAbsent("errorCode", errorCode.trim());
        }
        var outcome = applyResult(task, new ResultApplyCommand(
                taskId,
                messageId,
                correlation.leaseToken(),
                correlation.workerId(),
                correlation.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                success,
                payload,
                detail,
                TaskRuntimePolicySnapshotMapper.toRetryPolicySnapshot(taskPolicy(task), -1, 1L),
                TaskRuntimePolicySnapshotMapper.toResultFinalityPolicySnapshot(taskPolicy(task), finalResultRetentionMillis),
                epoch(taskId),
                System.currentTimeMillis()));
        return outcome.accepted();
    }

    @Override
    public TaskResultCorrelation getResultCorrelation(String taskId, String messageId) {
        ResultCorrelationSnapshot correlation = resultPort.getResultCorrelation(taskId, messageId);
        if (!correlation.present()) {
            return TaskResultCorrelation.noActiveLease(taskId, messageId);
        }
        ActiveLeaseRepairCandidate candidate = findActiveCandidate(taskId, messageId);
        String batchId = candidate != null ? candidate.batchId() : null;
        String attemptId = TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                messageId,
                correlation.attemptNo(),
                correlation.workerId(),
                batchId);
        return TaskResultCorrelation.workerLevel(
                taskId,
                messageId,
                attemptId,
                correlation.leaseToken(),
                correlation.workerId(),
                batchId);
    }

    @Override
    public Task getTask(String taskId) {
        return taskQueries.getTask(taskId);
    }

    @Override
    public TaskRuntimeProgressSnapshot getTaskRuntimeProgressSnapshot(String taskId) {
        return progressPort.progressSnapshot(taskId);
    }

    public FinalResultWindow readTaskResults(String taskId, long afterSeq, int limit) {
        return readPort.readFinalResults(new FinalResultReadRequest(taskId, afterSeq, limit));
    }

    public Optional<FinalResultRow> getVisibleTaskResultByMessageId(String taskId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return readPort.getFinalResultByMessageId(taskId, messageId);
    }

    public long countVisibleTaskResults(String taskId) {
        return progressPort.progressSnapshot(taskId).finalCount();
    }

    public void discardTaskRuntime(String taskId, String reason) {
        discardPort.discardTaskRuntime(new DiscardTaskRuntimeCommand(taskId, epoch(taskId), reason));
    }

    public void discardTaskWork(String taskId, String reason) {
        discardPort.discardTaskWork(new DiscardTaskWorkCommand(taskId, epoch(taskId), reason));
    }

    @Override
    public TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskRuntimeProgressSnapshot stats) {
        return terminalPolicy.evaluate(task, stats, taskPolicy(task).idleClosePolicy());
    }

    private TaskRuntimeResultDecision applyResult(Task task, ResultApplyCommand command) {
        ActiveLeaseRepairCandidate active = findActiveCandidate(command.taskId(), command.messageId());
        var outcome = resultPort.applyResult(command);
        var decision = TaskRuntimeResultDecisionMapper.toEngineDecision(outcome);
        if (decision.accepted() && active != null
                && (decision.status() == MessageFinalityStatus.LOGICAL_FINAL
                || decision.status() == MessageFinalityStatus.RETRY_SCHEDULED)) {
            publishLeaseExpiredTrace(command, active, decision);
            publishAttemptClosed(task, command, active, decision);
        }
        if (decision.accepted() && decision.status() == MessageFinalityStatus.LOGICAL_FINAL) {
            publishLogicalFinal(task, command, decision);
        }
        if (decision.progressDirty()) {
            stateResolver.updateTaskProgress(command.taskId());
        }
        if (decision.retryScheduled()) {
            schedulerPort.markTaskDirty(command.taskId());
            if (task != null && decision.retryAtMillis() <= System.currentTimeMillis()) {
                requestTaskDispatch(task);
            }
        }
        return decision;
    }

    private void publishAttemptClosed(Task task,
                                      ResultApplyCommand command,
                                      ActiveLeaseRepairCandidate active,
                                      TaskRuntimeResultDecision decision) {
        if (task == null) {
            return;
        }
        boolean retry = decision.status() == MessageFinalityStatus.RETRY_SCHEDULED;
        TaskWorkLifecycleState.AttemptStatus status = retry
                ? TaskWorkLifecycleState.AttemptStatus.REVOKED
                : command.success()
                ? TaskWorkLifecycleState.AttemptStatus.SUCCEEDED
                : command.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.AttemptStatus.EXPIRED
                : TaskWorkLifecycleState.AttemptStatus.FAILED;
        TaskWorkLifecycleState.AttemptFinalReason reason = retry
                ? TaskWorkLifecycleState.AttemptFinalReason.REVOKED_FOR_RETRY
                : command.success()
                ? TaskWorkLifecycleState.AttemptFinalReason.SUCCESS
                : command.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.AttemptFinalReason.LEASE_EXPIRED
                : TaskWorkLifecycleState.AttemptFinalReason.BUSINESS_FAILURE;
        String attemptId = TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                command.messageId(),
                command.attemptNo(),
                command.workerId(),
                active.batchId());
        TaskWorkAttemptClosedEvent event = TaskWorkAttemptClosedEvent.from(
                command.taskId(),
                command.messageId(),
                attemptId,
                command.attemptNo(),
                command.workerId(),
                active.workerGroupId(),
                active.batchId(),
                active.workerReservationToken(),
                active.scoreBandClaimScore(),
                status,
                reason);
        taskEvents.publishTaskWorkAttemptClosed(task, event);
        traceEventLogger.taskWorkAttemptClosed(
                task,
                traceView(command, active, decision),
                attemptId,
                command.attemptNo(),
                command.workerId(),
                active.batchId(),
                status,
                reason,
                "APPLY_RESULT",
                "TaskRuntimeServingLane",
                decision.reason());
    }

    private void publishLogicalFinal(Task task, ResultApplyCommand command, TaskRuntimeResultDecision decision) {
        if (task == null) {
            return;
        }
        TaskWorkLifecycleState.MessageStatus status = command.success()
                ? TaskWorkLifecycleState.MessageStatus.SUCCESS
                : command.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.MessageStatus.EXPIRED
                : TaskWorkLifecycleState.MessageStatus.FAILED;
        TaskWorkLifecycleState.MessageFinalReason reason = command.success()
                ? TaskWorkLifecycleState.MessageFinalReason.BUSINESS_SUCCESS
                : command.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.MessageFinalReason.LEASE_EXPIRED
                : TaskWorkLifecycleState.MessageFinalReason.BUSINESS_FAILED;
        taskEvents.publishTaskWorkLogicallyFinal(
                task,
                TaskWorkLogicallyFinalEvent.from(
                        command.taskId(),
                        command.messageId(),
                        status,
                        reason,
                        Math.max(0, command.attemptNo() - 1),
                        null,
                        decision.reason(),
                        null,
                        command.resultPayloadJson()));
    }

    private void publishLeaseExpiredTrace(ResultApplyCommand command,
                                          ActiveLeaseRepairCandidate active,
                                          TaskRuntimeResultDecision decision) {
        if (command.source() != ResultApplySource.LEASE_TIMEOUT) {
            return;
        }
        traceEventLogger.leaseExpired(
                traceView(command, active, decision),
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        command.messageId(),
                        command.attemptNo(),
                        command.workerId(),
                        active.batchId()),
                command.workerId(),
                active.batchId(),
                TaskWorkLifecycleState.MessageStatus.RUNNING,
                decision.retryScheduled()
                        ? TaskWorkLifecycleState.MessageStatus.INIT
                        : TaskWorkLifecycleState.MessageStatus.EXPIRED,
                "LEASE_EXPIRED",
                "LEASE_TIMEOUT",
                "TaskRuntimeServingLane",
                decision.reason());
    }

    private TraceEventLogger.TaskWorkTraceView traceView(ResultApplyCommand command,
                                                         ActiveLeaseRepairCandidate active,
                                                         TaskRuntimeResultDecision decision) {
        return new TraceEventLogger.TaskWorkTraceView(
                command.taskId(),
                command.messageId(),
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        command.messageId(),
                        command.attemptNo(),
                        command.workerId(),
                        active.batchId()),
                command.workerId(),
                active.batchId(),
                traceMessageStatus(command, decision),
                traceMessageFinalReason(command, decision),
                Math.max(0, command.attemptNo() - 1),
                null);
    }

    private TaskWorkLifecycleState.MessageStatus traceMessageStatus(ResultApplyCommand command,
                                                                    TaskRuntimeResultDecision decision) {
        if (decision.retryScheduled()) {
            return TaskWorkLifecycleState.MessageStatus.INIT;
        }
        if (command.success()) {
            return TaskWorkLifecycleState.MessageStatus.SUCCESS;
        }
        if (command.source() == ResultApplySource.LEASE_TIMEOUT) {
            return TaskWorkLifecycleState.MessageStatus.EXPIRED;
        }
        return TaskWorkLifecycleState.MessageStatus.FAILED;
    }

    private TaskWorkLifecycleState.MessageFinalReason traceMessageFinalReason(ResultApplyCommand command,
                                                                              TaskRuntimeResultDecision decision) {
        if (decision.retryScheduled()) {
            return null;
        }
        if (command.success()) {
            return TaskWorkLifecycleState.MessageFinalReason.BUSINESS_SUCCESS;
        }
        if (command.source() == ResultApplySource.LEASE_TIMEOUT) {
            return TaskWorkLifecycleState.MessageFinalReason.LEASE_EXPIRED;
        }
        return TaskWorkLifecycleState.MessageFinalReason.BUSINESS_FAILED;
    }

    private void updateSchedulerEligibility(Task task, RuntimeEpoch epoch) {
        schedulerPort.updateTaskEligibility(new UpdateSchedulerEligibilityCommand(
                task.getTid(),
                new SchedulerEligibilityPolicy(
                        runtimeGate(task),
                        "default",
                        0L,
                        0L,
                        0L,
                        0L),
                epoch));
    }

    private RuntimeGate runtimeGate(Task task) {
        TaskStatus status = task != null ? task.getStatus() : null;
        if (status == null) {
            return RuntimeGate.BLOCKED;
        }
        if (status.isFinal()) {
            return RuntimeGate.TERMINAL;
        }
        return switch (status) {
            case READY, RUNNING -> RuntimeGate.OPEN;
            case PAUSED -> RuntimeGate.PAUSED;
            case BLOCKED, NEW -> RuntimeGate.BLOCKED;
            default -> RuntimeGate.BLOCKED;
        };
    }

    private ResolvedTaskSchedulingPolicy taskPolicy(Task task) {
        return schedulingPlaneResolver.resolve(task).taskSchedulingPolicy();
    }

    private RuntimeEpoch epoch(String taskId) {
        return RuntimeEpoch.of(taskId, 1L);
    }

    private static TaskRuntimeReadPort requireReadPort(TaskRuntimeProgressPort progressPort) {
        if (progressPort instanceof TaskRuntimeReadPort readPort) {
            return readPort;
        }
        throw new IllegalArgumentException("readPort is required for task-runtime serving lane result queries");
    }

    private static TaskRuntimeDiscardPort requireDiscardPort(TaskRuntimeProgressPort progressPort) {
        if (progressPort instanceof TaskRuntimeDiscardPort discardPort) {
            return discardPort;
        }
        throw new IllegalArgumentException("discardPort is required for task-runtime serving lane cleanup");
    }

    private ActiveLeaseRepairCandidate findActiveCandidate(String taskId, String messageId) {
        if (taskId == null || taskId.isBlank() || messageId == null || messageId.isBlank()) {
            return null;
        }
        return repairPort.getActiveWorkForTask(new ActiveTaskWorkQuery(taskId, ACTIVE_QUERY_LIMIT))
                .activeItems()
                .stream()
                .filter(candidate -> messageId.equals(candidate.messageId()))
                .findFirst()
                .orElse(null);
    }


}
