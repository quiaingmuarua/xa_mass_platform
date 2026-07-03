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
import com.xa.mass.engine.runtime.TaskRuntimeResultDecision;
import com.xa.mass.engine.runtime.TaskRuntimeResultDecisionMapper;
import com.xa.mass.engine.runtime.TaskRuntimeResultFactMapper;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.ClaimLeasePolicy;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;
import com.xa.mass.task.runtime.TaskRuntimeResultPolicyV1;
import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

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

    private final TaskRuntimeWorkPort workPort;
    private final TaskRuntimeScorePort scorePort;
    private final TaskRuntimeConvergencePort convergencePort;
    private final TaskRuntimeReadPort readPort;
    private final TaskRuntimeResultWindowReadModel resultWindowReadModel;
    private final Function<String, Task> taskReader;
    private final Predicate<Task> taskWriter;
    private final Consumer<Task> taskDispatchRequestedPublisher;
    private final Consumer<Task> taskTerminalPublisher;
    private final BiConsumer<Task, TaskWorkAttemptClosedEvent> taskWorkAttemptClosedPublisher;
    private final BiConsumer<Task, TaskWorkLogicallyFinalEvent> taskWorkLogicallyFinalPublisher;
    private final TaskTerminalPolicy terminalPolicy;
    private final SchedulingPlaneResolver schedulingPlaneResolver;
    private final TraceEventLogger traceEventLogger;
    private final TaskStateResolver stateResolver;
    private final long workLeaseSeconds;
    private final int maxAppendBatchSize;
    private final long finalResultRetentionMillis;
    private final LongSupplier clock;

    public static TaskRuntimeServingLane forShellHooks(TaskRuntimeWorkPort workPort,
                                                       TaskRuntimeScorePort scorePort,
                                                       TaskRuntimeConvergencePort convergencePort,
                                                       TaskRuntimeReadPort readPort,
                                                       TaskRuntimeResultWindowReadModel resultWindowReadModel,
                                                       Function<String, Task> taskReader,
                                                       Predicate<Task> taskWriter,
                                                       Consumer<Task> taskDispatchRequestedPublisher,
                                                       Consumer<Task> taskTerminalPublisher,
                                                       BiConsumer<Task, TaskWorkAttemptClosedEvent> taskWorkAttemptClosedPublisher,
                                                       BiConsumer<Task, TaskWorkLogicallyFinalEvent> taskWorkLogicallyFinalPublisher,
                                                       TaskTerminalPolicy terminalPolicy,
                                                       SchedulingPlaneResolver schedulingPlaneResolver,
                                                       TraceEventLogger traceEventLogger,
                                                       long workLeaseSeconds,
                                                       int maxAppendBatchSize,
                                                       long finalResultRetentionMillis,
                                                       LongSupplier clock) {
        return new TaskRuntimeServingLane(
                workPort,
                scorePort,
                convergencePort,
                readPort,
                resultWindowReadModel,
                taskReader,
                taskWriter,
                taskDispatchRequestedPublisher,
                taskTerminalPublisher,
                taskWorkAttemptClosedPublisher,
                taskWorkLogicallyFinalPublisher,
                terminalPolicy,
                schedulingPlaneResolver,
                traceEventLogger,
                workLeaseSeconds,
                maxAppendBatchSize,
                finalResultRetentionMillis,
                clock);
    }

    private TaskRuntimeServingLane(TaskRuntimeWorkPort workPort,
                                   TaskRuntimeScorePort scorePort,
                                   TaskRuntimeConvergencePort convergencePort,
                                   TaskRuntimeReadPort readPort,
                                   TaskRuntimeResultWindowReadModel resultWindowReadModel,
                                   Function<String, Task> taskReader,
                                   Predicate<Task> taskWriter,
                                   Consumer<Task> taskDispatchRequestedPublisher,
                                   Consumer<Task> taskTerminalPublisher,
                                   BiConsumer<Task, TaskWorkAttemptClosedEvent> taskWorkAttemptClosedPublisher,
                                   BiConsumer<Task, TaskWorkLogicallyFinalEvent> taskWorkLogicallyFinalPublisher,
                                   TaskTerminalPolicy terminalPolicy,
                                   SchedulingPlaneResolver schedulingPlaneResolver,
                                   TraceEventLogger traceEventLogger,
                                   long workLeaseSeconds,
                                   int maxAppendBatchSize,
                                   long finalResultRetentionMillis,
                                   LongSupplier clock) {
        this.workPort = Objects.requireNonNull(workPort, "workPort");
        this.scorePort = Objects.requireNonNull(scorePort, "scorePort");
        this.convergencePort = Objects.requireNonNull(convergencePort, "convergencePort");
        this.readPort = Objects.requireNonNull(readPort, "readPort");
        this.resultWindowReadModel = Objects.requireNonNull(resultWindowReadModel, "resultWindowReadModel");
        this.taskReader = Objects.requireNonNull(taskReader, "taskReader");
        this.taskWriter = Objects.requireNonNull(taskWriter, "taskWriter");
        this.taskDispatchRequestedPublisher = Objects.requireNonNull(
                taskDispatchRequestedPublisher, "taskDispatchRequestedPublisher");
        this.taskTerminalPublisher = Objects.requireNonNull(taskTerminalPublisher, "taskTerminalPublisher");
        this.taskWorkAttemptClosedPublisher = Objects.requireNonNull(
                taskWorkAttemptClosedPublisher, "taskWorkAttemptClosedPublisher");
        this.taskWorkLogicallyFinalPublisher = Objects.requireNonNull(
                taskWorkLogicallyFinalPublisher, "taskWorkLogicallyFinalPublisher");
        this.terminalPolicy = terminalPolicy == null ? new ContractAwareTaskTerminalPolicy() : terminalPolicy;
        this.schedulingPlaneResolver = schedulingPlaneResolver == null
                ? new DefaultSchedulingPlaneResolver()
                : schedulingPlaneResolver;
        this.traceEventLogger = traceEventLogger == null ? TraceEventLogger.noop() : traceEventLogger;
        this.workLeaseSeconds = Math.max(1L, workLeaseSeconds);
        this.maxAppendBatchSize = Math.max(1, maxAppendBatchSize);
        this.finalResultRetentionMillis = Math.max(0L,
                finalResultRetentionMillis > 0L ? finalResultRetentionMillis : DEFAULT_FINAL_RESULT_RETENTION_MILLIS);
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.stateResolver = new TaskStateResolver(
                this,
                this.taskWriter::test,
                this::publishTaskTerminal,
                this.traceEventLogger);
    }

    void appendRuntimeIngressItems(Task task, List<RuntimeTaskIngressItem> ingressItems) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        if (ingressItems == null || ingressItems.isEmpty()) {
            throw new IllegalArgumentException("ingressItems must be a non-empty list");
        }
        validateRuntimeAppendAdmission(task, ingressItems.size());
        var outcome = workPort.appendBacklog(
                task.getTid(),
                TaskRuntimeAppendItemMapper.toAppendItems(ingressItems),
                maxAppendBatchSize);
        if (outcome.status() != AppendBatchStatus.ALL_ACCEPTED) {
            throw new IllegalStateException("task-runtime append failed: status="
                    + outcome.status() + ", reason=" + outcome.reason());
        }
    }

    void validateRuntimeAppendAdmission(Task task, int itemCount) {
        if (task == null || itemCount <= 0) {
            return;
        }
        var maxReadyItems = taskPolicy(task).backpressurePolicy().maxReadyItemsPerTask();
        if (maxReadyItems <= 0) {
            return;
        }
        var readyCount = readPort.progressSnapshot(task.getTid()).readyCount();
        if (readyCount + itemCount > maxReadyItems) {
            throw new IllegalStateException("task-runtime append failed: status="
                    + AppendBatchStatus.REJECTED_BEFORE_RUNTIME
                    + ", reason=ready backlog is full");
        }
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
        long readyCount = readPort.progressSnapshot(taskId).readyCount();
        return readyCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) readyCount;
    }

    @Override
    public int countActiveDispatchWorkers(String taskId) {
        return (int) readPort.activeWorkForTask(taskId, ACTIVE_QUERY_LIMIT)
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
    public boolean persistAssignmentState(Task task) {
        return taskWriter.test(task);
    }

    private Task loadTask(String taskId) {
        return taskReader.apply(taskId);
    }

    private void publishTaskTerminal(Task task) {
        taskTerminalPublisher.accept(task);
    }

    @Override
    public ClaimReadyOutcome claimReady(String taskId,
                                        List<WorkerReservationEvidence> workerReservations,
                                        ClaimLeasePolicy leasePolicy) {
        if (leasePolicy == null) {
            throw new IllegalArgumentException("leasePolicy is required");
        }
        Task task = loadTask(taskId);
        if (task == null) {
            return new ClaimReadyOutcome(taskId, List.of(), "task shell not found");
        }
        var candidate = scorePort.scoreCandidate(taskId, "default")
                .orElse(null);
        if (candidate == null) {
            return new ClaimReadyOutcome(taskId, List.of(), "task is not score-visible");
        }
        return workPort.claimBacklog(
                candidate,
                workerReservations,
                leasePolicy.maxItems(),
                leasePolicy.leaseMillis(),
                nowMillis());
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
            var outcome = applyResult(task, TaskRuntimeResultFactMapper.fromDispatchSubmitFailure(
                    binding,
                    epoch(binding.taskId()),
                    nowMillis(),
                    detail));
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
            ResultCorrelationSnapshot correlation = readPort.resultCorrelation(failure.taskId(), failure.messageId());
            if (!correlation.present()) {
                accepted = false;
                continue;
            }
            var outcome = applyResult(task, new RuntimeResultFact(
                    failure.taskId(),
                    failure.messageId(),
                    correlation.leaseToken(),
                    correlation.workerId(),
                    failure.attemptNo(),
                    ResultApplySource.DISPATCH_DELIVERY_FAILURE,
                    false,
                    Map.of(),
                    failure.detail(),
                    epoch(failure.taskId()),
                    nowMillis()));
            accepted &= outcome.accepted();
        }
        return accepted;
    }

    @Override
    public List<ActiveLeaseRepairCandidate> getActiveLeaseCandidates(String taskId) {
        return List.copyOf(readPort.activeWorkForTask(taskId, ACTIVE_QUERY_LIMIT)
                .activeItems());
    }

    @Override
    public List<ActiveLeaseRepairCandidate> pollExpiredLeaseCandidates(int limit, Instant now) {
        return List.copyOf(convergencePort.scanExpiredLeases(
                        "default",
                        now == null ? nowMillis() : now.toEpochMilli(),
                        Math.max(1, limit),
                        Math.max(1, limit)));
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
        return readPort.activeWorkForTask(taskId, ACTIVE_QUERY_LIMIT)
                .activeItems()
                .stream()
                .anyMatch(candidate -> workerId.equals(candidate.workerId()));
    }

    @Override
    public boolean expireLeasedWork(String taskId, String messageId) {
        var candidate = findActiveCandidate(taskId, messageId);
        if (candidate == null) {
            return false;
        }
        Task task = loadTask(taskId);
        var outcome = applyResult(task, TaskRuntimeResultFactMapper.fromLeaseTimeout(
                candidate,
                epoch(taskId),
                nowMillis(),
                "lease expired"));
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
        if (task.getStatus() == null || !task.getStatus().isFinal()) {
            taskDispatchRequestedPublisher.accept(task);
        }
    }

    @Override
    public List<Task> getRuntimeDispatchableTasks(int limit) {
        var candidates = scorePort.discoverSchedulable("default", nowMillis(), Math.max(1, limit));
        List<Task> tasks = new ArrayList<>();
        for (var candidate : candidates.candidates()) {
            Task task = loadTask(candidate.taskId());
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
        Task task = loadTask(taskId);
        var correlation = readPort.resultCorrelation(taskId, messageId);
        if (!correlation.present()) {
            Optional<FinalResultRow> finalResult = getVisibleTaskResultByMessageId(taskId, messageId);
            finalResult.ifPresent(row -> publishDuplicateCallbackTrace(row, "already final"));
            return finalResult.isPresent();
        }
        Map<String, Object> payload = output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output);
        if (errorCode != null && !errorCode.isBlank()) {
            payload.putIfAbsent("errorCode", errorCode.trim());
        }
        var outcome = applyResult(task, new RuntimeResultFact(
                taskId,
                messageId,
                correlation.leaseToken(),
                correlation.workerId(),
                correlation.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                success,
                payload,
                detail,
                epoch(taskId),
                nowMillis()));
        return outcome.accepted();
    }

    @Override
    public TaskResultCorrelation getResultCorrelation(String taskId, String messageId) {
        ResultCorrelationSnapshot correlation = readPort.resultCorrelation(taskId, messageId);
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
        return loadTask(taskId);
    }

    @Override
    public TaskRuntimeProgressSnapshot getTaskRuntimeProgressSnapshot(String taskId) {
        return readPort.progressSnapshot(taskId);
    }

    public FinalResultWindow readTaskResults(String taskId, long afterSeq, int limit) {
        return resultWindowReadModel.readFinalResults(new FinalResultReadRequest(taskId, afterSeq, limit));
    }

    public Optional<FinalResultRow> getVisibleTaskResultByMessageId(String taskId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return readPort.getFinalResultByMessageId(taskId, messageId);
    }

    public long countVisibleTaskResults(String taskId) {
        return readPort.progressSnapshot(taskId).finalCount();
    }

    public void discardTaskRuntime(String taskId, String reason) {
        convergencePort.discardRuntime(taskId, "default", epoch(taskId), reason);
    }

    public void discardTaskWork(String taskId, String reason) {
        convergencePort.discardWork(taskId, epoch(taskId), reason);
    }

    @Override
    public TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskRuntimeProgressSnapshot stats) {
        return terminalPolicy.evaluate(task, stats, taskPolicy(task).idleClosePolicy());
    }

    private TaskRuntimeResultDecision applyResult(Task task, RuntimeResultFact fact) {
        ActiveLeaseRepairCandidate active = findActiveCandidate(fact.taskId(), fact.messageId());
        var outcome = convergencePort.applyResult(fact);
        var decision = TaskRuntimeResultDecisionMapper.toEngineDecision(outcome);
        if (fact.source() == ResultApplySource.WORKER_RESULT) {
            publishCallbackTrace(fact, active, decision);
        }
        if (decision.accepted() && active != null
                && (decision.status() == MessageFinalityStatus.LOGICAL_FINAL
                || decision.status() == MessageFinalityStatus.RETRY_SCHEDULED)) {
            publishLeaseExpiredTrace(fact, active, decision);
            publishAttemptClosed(task, fact, active, decision);
        }
        if (decision.accepted() && decision.retryScheduled()) {
            publishRetryResetTrace(fact, active, decision);
        }
        boolean logicalFinal = decision.accepted() && decision.status() == MessageFinalityStatus.LOGICAL_FINAL;
        if (logicalFinal) {
            publishLogicalFinal(task, fact, active, decision);
        }
        TaskStateResolutionResult resolution = null;
        if (decision.progressDirty()) {
            resolution = stateResolver.resolveTaskState(fact.taskId());
        }
        if (logicalFinal && isTerminalResolution(resolution)) {
            convergencePort.closeIfDrained(fact.taskId(), "default", fact.runtimeEpoch());
        }
        if (decision.retryScheduled()) {
            rescoreTaskForRetry(fact.taskId(), fact.runtimeEpoch(), decision.retryAtMillis());
            if (task != null && decision.retryAtMillis() <= nowMillis()) {
                requestTaskDispatch(task);
            }
        }
        return decision;
    }

    private boolean isTerminalResolution(TaskStateResolutionResult resolution) {
        return resolution != null
                && (resolution.getOutcome() == TaskStateResolutionResult.Outcome.FINALIZED_TO_TERMINAL
                || resolution.getOutcome() == TaskStateResolutionResult.Outcome.ALREADY_FINAL);
    }

    private void publishAttemptClosed(Task task,
                                      RuntimeResultFact fact,
                                      ActiveLeaseRepairCandidate active,
                                      TaskRuntimeResultDecision decision) {
        if (task == null) {
            return;
        }
        TaskWorkLifecycleState.AttemptStatus status = attemptStatus(fact, decision);
        TaskWorkLifecycleState.AttemptFinalReason reason = attemptFinalReason(fact, decision);
        String attemptId = TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                fact.messageId(),
                fact.attemptNo(),
                fact.workerId(),
                active.batchId());
        TaskWorkAttemptClosedEvent event = TaskWorkAttemptClosedEvent.from(
                fact.taskId(),
                fact.messageId(),
                attemptId,
                fact.attemptNo(),
                fact.workerId(),
                active.workerGroupId(),
                active.batchId(),
                active.workerReservationToken(),
                active.scoreBandClaimScore(),
                status,
                reason);
        taskWorkAttemptClosedPublisher.accept(task, event);
        traceEventLogger.taskWorkAttemptClosed(
                task,
                traceView(fact, active, decision),
                attemptId,
                fact.attemptNo(),
                fact.workerId(),
                active.batchId(),
                status,
                reason,
                "APPLY_RESULT",
                "TaskRuntimeServingLane",
                decision.reason());
    }

    private TaskWorkLifecycleState.AttemptStatus attemptStatus(RuntimeResultFact fact,
                                                               TaskRuntimeResultDecision decision) {
        if (fact.success()) {
            return TaskWorkLifecycleState.AttemptStatus.SUCCEEDED;
        }
        if (fact.source() == ResultApplySource.LEASE_TIMEOUT) {
            return TaskWorkLifecycleState.AttemptStatus.EXPIRED;
        }
        if (decision.retryScheduled() && fact.source() != ResultApplySource.WORKER_RESULT) {
            return TaskWorkLifecycleState.AttemptStatus.REVOKED;
        }
        return TaskWorkLifecycleState.AttemptStatus.FAILED;
    }

    private TaskWorkLifecycleState.AttemptFinalReason attemptFinalReason(RuntimeResultFact fact,
                                                                         TaskRuntimeResultDecision decision) {
        if (fact.success()) {
            return TaskWorkLifecycleState.AttemptFinalReason.SUCCESS;
        }
        if (fact.source() == ResultApplySource.LEASE_TIMEOUT) {
            return TaskWorkLifecycleState.AttemptFinalReason.LEASE_EXPIRED;
        }
        if (decision.retryScheduled() && fact.source() != ResultApplySource.WORKER_RESULT) {
            return TaskWorkLifecycleState.AttemptFinalReason.REVOKED_FOR_RETRY;
        }
        return TaskWorkLifecycleState.AttemptFinalReason.BUSINESS_FAILURE;
    }

    private void publishLogicalFinal(Task task,
                                     RuntimeResultFact fact,
                                     ActiveLeaseRepairCandidate active,
                                     TaskRuntimeResultDecision decision) {
        if (task == null) {
            return;
        }
        TaskWorkLifecycleState.MessageStatus status = fact.success()
                ? TaskWorkLifecycleState.MessageStatus.SUCCESS
                : fact.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.MessageStatus.EXPIRED
                : TaskWorkLifecycleState.MessageStatus.FAILED;
        TaskWorkLifecycleState.MessageFinalReason reason = fact.success()
                ? TaskWorkLifecycleState.MessageFinalReason.BUSINESS_SUCCESS
                : fact.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.MessageFinalReason.LEASE_EXPIRED
                : TaskWorkLifecycleState.MessageFinalReason.BUSINESS_FAILED;
        String traceReason = logicalFinalTraceReason(fact);
        taskWorkLogicallyFinalPublisher.accept(
                task,
                TaskWorkLogicallyFinalEvent.from(
                        fact.taskId(),
                        fact.messageId(),
                        status,
                        reason,
                        Math.max(0, fact.attemptNo() - 1),
                        null,
                        traceReason,
                        null,
                        fact.resultPayloadJson()));
        traceEventLogger.taskWorkLogicallyFinal(
                task,
                traceView(fact, active, decision),
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        fact.messageId(),
                        fact.attemptNo(),
                        fact.workerId(),
                        active != null ? active.batchId() : null),
                fact.workerId(),
                active != null ? active.batchId() : null,
                "APPLY_RESULT",
                "TaskRuntimeServingLane",
                traceReason);
    }

    private void publishCallbackTrace(RuntimeResultFact fact,
                                      ActiveLeaseRepairCandidate active,
                                      TaskRuntimeResultDecision decision) {
        if (active == null) {
            return;
        }
        var view = traceView(fact, active, decision);
        if (decision.status() == MessageFinalityStatus.DUPLICATE_OR_LATE) {
            traceEventLogger.callbackIgnoredDuplicate(view, decision.reason());
        } else if (decision.accepted()) {
            traceEventLogger.callbackAccepted(view, decision.reason());
        }
    }

    private void publishDuplicateCallbackTrace(FinalResultRow row, String reason) {
        traceEventLogger.callbackIgnoredDuplicate(traceView(row), reason);
    }

    private String logicalFinalTraceReason(RuntimeResultFact fact) {
        return fact.success()
                ? "work item reached stable success"
                : "work item reached stable failure";
    }

    private void publishLeaseExpiredTrace(RuntimeResultFact fact,
                                          ActiveLeaseRepairCandidate active,
                                          TaskRuntimeResultDecision decision) {
        if (fact.source() != ResultApplySource.LEASE_TIMEOUT) {
            return;
        }
        traceEventLogger.leaseExpired(
                traceView(fact, active, decision),
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        fact.messageId(),
                        fact.attemptNo(),
                        fact.workerId(),
                        active.batchId()),
                fact.workerId(),
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

    private void publishRetryResetTrace(RuntimeResultFact fact,
                                        ActiveLeaseRepairCandidate active,
                                        TaskRuntimeResultDecision decision) {
        if (!decision.retryScheduled()) {
            return;
        }
        String attemptId = TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                fact.messageId(),
                fact.attemptNo(),
                fact.workerId(),
                active != null ? active.batchId() : null);
        traceEventLogger.taskWorkRetryReset(
                traceView(fact, active, decision),
                attemptId,
                fact.workerId(),
                active != null ? active.batchId() : null,
                retryResetSourceStatus(fact),
                retryResetDelayMillis(fact, decision),
                retryResetTrigger(fact),
                "TaskRuntimeServingLane",
                retryResetReason(fact, decision));
    }

    private TraceEventLogger.TaskWorkTraceView traceView(RuntimeResultFact fact,
                                                         ActiveLeaseRepairCandidate active,
                                                         TaskRuntimeResultDecision decision) {
        return new TraceEventLogger.TaskWorkTraceView(
                fact.taskId(),
                fact.messageId(),
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        fact.messageId(),
                        fact.attemptNo(),
                        fact.workerId(),
                        active != null ? active.batchId() : null),
                fact.workerId(),
                active != null ? active.batchId() : null,
                traceMessageStatus(fact, decision),
                traceMessageFinalReason(fact, decision),
                Math.max(0, fact.attemptNo() - 1),
                null);
    }

    private TraceEventLogger.TaskWorkTraceView traceView(FinalResultRow row) {
        TaskWorkLifecycleState.MessageStatus status = row.success()
                ? TaskWorkLifecycleState.MessageStatus.SUCCESS
                : row.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.MessageStatus.EXPIRED
                : TaskWorkLifecycleState.MessageStatus.FAILED;
        TaskWorkLifecycleState.MessageFinalReason reason = row.success()
                ? TaskWorkLifecycleState.MessageFinalReason.BUSINESS_SUCCESS
                : row.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.MessageFinalReason.LEASE_EXPIRED
                : TaskWorkLifecycleState.MessageFinalReason.BUSINESS_FAILED;
        return new TraceEventLogger.TaskWorkTraceView(
                row.taskId(),
                row.messageId(),
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        row.messageId(),
                        row.attemptNo(),
                        row.workerId(),
                        row.batchId()),
                row.workerId(),
                row.batchId(),
                status,
                reason,
                Math.max(0, row.attemptNo() - 1),
                null);
    }

    private TaskWorkLifecycleState.MessageStatus traceMessageStatus(RuntimeResultFact fact,
                                                                    TaskRuntimeResultDecision decision) {
        if (decision.retryScheduled()) {
            return TaskWorkLifecycleState.MessageStatus.INIT;
        }
        if (fact.success()) {
            return TaskWorkLifecycleState.MessageStatus.SUCCESS;
        }
        if (fact.source() == ResultApplySource.LEASE_TIMEOUT) {
            return TaskWorkLifecycleState.MessageStatus.EXPIRED;
        }
        return TaskWorkLifecycleState.MessageStatus.FAILED;
    }

    private TaskWorkLifecycleState.MessageFinalReason traceMessageFinalReason(RuntimeResultFact fact,
                                                                              TaskRuntimeResultDecision decision) {
        if (decision.retryScheduled()) {
            return null;
        }
        if (fact.success()) {
            return TaskWorkLifecycleState.MessageFinalReason.BUSINESS_SUCCESS;
        }
        if (fact.source() == ResultApplySource.LEASE_TIMEOUT) {
            return TaskWorkLifecycleState.MessageFinalReason.LEASE_EXPIRED;
        }
        return TaskWorkLifecycleState.MessageFinalReason.BUSINESS_FAILED;
    }

    private void updateSchedulerEligibility(Task task, RuntimeEpoch epoch) {
        RuntimeGate gate = runtimeGate(task);
        String taskId = task.getTid();
        String laneKey = "default";
        var policy = taskPolicy(task);
        var resultPolicy = TaskRuntimeResultPolicyV1.from(
                TaskRuntimePolicySnapshotMapper.toRetryPolicySnapshot(policy, -1, 1L),
                TaskRuntimePolicySnapshotMapper.toResultFinalityPolicySnapshot(policy, finalResultRetentionMillis));
        scorePort.putRuntimeMeta(new TaskRuntimeMetaV1(
                taskId,
                laneKey,
                gate,
                epoch,
                0L,
                0L,
                0L,
                0L,
                resultPolicy));
        switch (gate) {
            case OPEN -> scorePort.setTaskScore(
                    taskId,
                    laneKey,
                    epoch,
                    TaskScoreV1.dueAt(nowMillis()));
            case PAUSED -> scorePort.setTaskScore(
                    taskId,
                    laneKey,
                    epoch,
                    TaskScoreV1.manualBlocked());
            case BLOCKED -> scorePort.setTaskScore(
                    taskId,
                    laneKey,
                    epoch,
                    TaskScoreV1.manualBlocked());
            case TERMINAL, DISCARDED -> scorePort.removeTaskScore(taskId, laneKey, epoch);
        }
    }

    private void rescoreTaskForRetry(String taskId, RuntimeEpoch epoch, long retryAtMillis) {
        scorePort.setTaskScore(taskId, "default", epoch, TaskScoreV1.dueAt(retryAtMillis));
    }

    private long nowMillis() {
        return clock.getAsLong();
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

    private ActiveLeaseRepairCandidate findActiveCandidate(String taskId, String messageId) {
        if (taskId == null || taskId.isBlank() || messageId == null || messageId.isBlank()) {
            return null;
        }
        return readPort.activeWorkForTask(taskId, ACTIVE_QUERY_LIMIT)
                .activeItems()
                .stream()
                .filter(candidate -> messageId.equals(candidate.messageId()))
                .findFirst()
                .orElse(null);
    }

    private TaskWorkLifecycleState.MessageStatus retryResetSourceStatus(RuntimeResultFact fact) {
        return fact.source() == ResultApplySource.LEASE_TIMEOUT
                ? TaskWorkLifecycleState.MessageStatus.EXPIRED
                : TaskWorkLifecycleState.MessageStatus.FAILED;
    }

    private long retryResetDelayMillis(RuntimeResultFact fact, TaskRuntimeResultDecision decision) {
        return Math.max(0L, decision.retryAtMillis() - fact.observedAtMillis());
    }

    private String retryResetTrigger(RuntimeResultFact fact) {
        return fact.source() == ResultApplySource.LEASE_TIMEOUT
                ? "LEASE_TIMEOUT"
                : "RESULT_APPLY";
    }

    private String retryResetReason(RuntimeResultFact fact, TaskRuntimeResultDecision decision) {
        String reason = decision.reason();
        if (reason == null || reason.isBlank()) {
            reason = fact.failureReason();
        }
        if (reason == null || reason.isBlank()) {
            reason = fact.source() == ResultApplySource.LEASE_TIMEOUT
                    ? "retry reset after lease expiry"
                    : "retry reset after failed attempt";
        }
        return reason;
    }


}
