package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptFinalReason;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptStatus;
import com.xa.mass.engine.TaskWorkLifecycleState.MessageFinalReason;
import com.xa.mass.engine.TaskWorkLifecycleState.MessageStatus;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.stage.TaskStageEvidenceResult;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.SelectedWorkerTraceEvidence;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.report.WorkerStateProjectionResult;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.trace.sink.NoopExecutionEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical execution-trace emitter for engine lifecycle events.
 *
 * <p>This class owns the mapping from engine business semantics to the shared
 * {@link ExecutionEvent} model. Diagnostic logs may remain elsewhere, but they
 * are not the trace contract.</p>
 */
public final class TraceEventLogger {

    private static final Logger LOG = LoggerFactory.getLogger(TraceEventLogger.class);
    private static final SchedulingPlaneResolver TRACE_SCHEDULING_PLANE_RESOLVER =
            new DefaultSchedulingPlaneResolver();

    private final ExecutionEventSink sink;

    public TraceEventLogger(ExecutionEventSink sink) {
        this.sink = sink == null ? new NoopExecutionEventSink() : sink;
    }

    public static TraceEventLogger noop() {
        return new TraceEventLogger(new NoopExecutionEventSink());
    }

    public void taskStatusTransition(String taskId,
                                     TaskStatus fromStatus,
                                     TaskStatus toStatus,
                                     String trigger,
                                     String source,
                                     String reason) {
        emit(event(ExecutionEventType.TASK_STATUS_TRANSITION)
                .identity(identity -> identity.taskId(taskId))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS"
                ))
                .build());
    }

    public void taskTerminalClosed(String taskId,
                                   TaskStatus fromStatus,
                                   TaskTerminalReason terminalReason,
                                   String trigger,
                                   String source,
                                   String reason) {
        emit(event(ExecutionEventType.TASK_TERMINAL_CLOSED)
                .identity(identity -> identity.taskId(taskId))
                .transition(enumName(fromStatus), TaskStatus.TERMINAL.name(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "terminalReason", enumName(terminalReason)
                ))
                .build());
    }

    public void taskWorkStatusTransition(TaskWorkTraceView workView,
                                        MessageStatus fromStatus,
                                        MessageStatus toStatus,
                                        String trigger,
                                        String source,
                                        String reason) {
        taskWorkStatusTransition(workView, null, null, null, fromStatus, toStatus, trigger, source, reason);
    }

    public void taskWorkStatusTransition(TaskWorkTraceView workView,
                                        String attemptId,
                                        String workerId,
                                        String batchId,
                                        MessageStatus fromStatus,
                                        MessageStatus toStatus,
                                        String trigger,
                                        String source,
                                        String reason) {
        if (workView == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_WORK_STATUS_TRANSITION)
                .identity(identity -> identity
                        .taskId(workView.taskId())
                        .messageId(workView.messageId())
                        .attemptId(attemptId != null ? attemptId : workView.latestAttemptId())
                        .workerId(workerId != null ? workerId : workView.latestAttemptWorkerId())
                        .leaseToken(null))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "finalReason", enumName(workView.finalReason()),
                        "latestAttemptBatchId", batchId != null ? batchId : workView.latestAttemptBatchId()
                ))
                .build());
    }

    public void taskWorkAttemptStatusTransition(String taskId,
                                               String messageId,
                                               String attemptId,
                                               Integer attemptNo,
                                               String workerId,
                                               String batchId,
                                               AttemptFinalReason finalReason,
                                               AttemptStatus fromStatus,
                                               AttemptStatus toStatus,
                                               String trigger,
                                               String source,
                                               String reason) {
        taskWorkAttemptStatusTransition(taskId, messageId, attemptId, attemptNo, workerId, batchId,
                finalReason, fromStatus, toStatus, trigger, source, reason, null);
    }

    public void taskWorkAttemptStatusTransition(String taskId,
                                               String messageId,
                                               String attemptId,
                                               Integer attemptNo,
                                               String workerId,
                                               String batchId,
                                               AttemptFinalReason finalReason,
                                               AttemptStatus fromStatus,
                                               AttemptStatus toStatus,
                                               String trigger,
                                               String source,
                                               String reason,
                                               Map<String, Object> extraAttrs) {
        if (attemptId == null || attemptId.isBlank()) {
            return;
        }
        Map<String, Object> values = attrs(
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS",
                "attemptNo", attemptNo,
                "finalReason", enumName(finalReason),
                "batchId", batchId
        );
        if (extraAttrs != null && !extraAttrs.isEmpty()) {
            values.putAll(extraAttrs);
        }
        emit(event(ExecutionEventType.TASK_WORK_ATTEMPT_STATUS_TRANSITION)
                .identity(identity -> identity
                        .taskId(taskId)
                        .messageId(messageId)
                        .attemptId(attemptId)
                        .workerId(workerId))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .attrs(values)
                .build());
    }

    public void taskWorkRetryReset(TaskWorkTraceView workView,
                                  Long workRetryDelayMillis,
                                  String trigger,
                                  String source,
                                  String reason) {
        taskWorkRetryReset(workView, null, null, null, workRetryDelayMillis, trigger, source, reason);
    }

    public void taskWorkRetryReset(TaskWorkTraceView workView,
                                   String attemptId,
                                   String workerId,
                                   String batchId,
                                   Long workRetryDelayMillis,
                                   String trigger,
                                   String source,
                                   String reason) {
        taskWorkRetryReset(
                workView,
                attemptId,
                workerId,
                batchId,
                null,
                workRetryDelayMillis,
                trigger,
                source,
                reason);
    }

    public void taskWorkRetryReset(TaskWorkTraceView workView,
                                   String attemptId,
                                   String workerId,
                                   String batchId,
                                   MessageStatus fromStatus,
                                   Long workRetryDelayMillis,
                                   String trigger,
                                   String source,
                                   String reason) {
        if (workView == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_WORK_RETRY_RESET)
                .identity(identity -> identity
                        .taskId(workView.taskId())
                        .messageId(workView.messageId())
                        .attemptId(attemptId != null ? attemptId : workView.latestAttemptId())
                        .workerId(workerId != null ? workerId : workView.latestAttemptWorkerId()))
                .transition(retryResetSourceStatus(fromStatus), MessageStatus.INIT.name(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "retryCount", workView.retryCount(),
                        "workRetryDelayMillis", workRetryDelayMillis,
                        "latestAttemptBatchId", batchId != null ? batchId : workView.latestAttemptBatchId()
                ))
                .build());
    }

    public void workerLockAcquired(String taskId, String workerId, String trigger, String source, String reason) {
        emit(event(ExecutionEventType.WORKER_LOCK_ACQUIRED)
                .identity(identity -> identity.taskId(taskId).workerId(workerId))
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS"
                ))
                .build());
    }

    public void workerLockReleased(String taskId, String workerId, String trigger, String source, String reason) {
        emit(event(ExecutionEventType.WORKER_LOCK_RELEASED)
                .identity(identity -> identity.taskId(taskId).workerId(workerId))
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS"
                ))
                .build());
    }

    public void dispatchRequested(String taskId, String trigger, String source, String reason) {
        emit(event(ExecutionEventType.DISPATCH_REQUESTED)
                .identity(identity -> identity.taskId(taskId))
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS"
                ))
                .build());
    }

    public void dispatchRequested(Task task, String trigger, String source, String reason) {
        if (task == null) {
            return;
        }
        Map<String, Object> attrs = attrs(
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        );
        putTaskRuntimeProfile(attrs, task);
        emit(event(ExecutionEventType.DISPATCH_REQUESTED)
                .identity(identity -> identity.taskId(task.getTid()))
                .attrs(attrs)
                .build());
    }

    public void dispatchSkipped(String taskId,
                                String trigger,
                                String source,
                                String reason,
                                Integer requiredMinWorkerCount) {
        emit(event(ExecutionEventType.DISPATCH_SKIPPED)
                .identity(identity -> identity.taskId(taskId))
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SKIPPED",
                        "requiredMinWorkerCount", requiredMinWorkerCount
                ))
                .build());
    }

    public void dispatchSkipped(Task task,
                                String trigger,
                                String source,
                                String reason,
                                Integer requiredMinWorkerCount) {
        if (task == null) {
            return;
        }
        Map<String, Object> attrs = attrs(
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SKIPPED",
                "requiredMinWorkerCount", requiredMinWorkerCount
        );
        putTaskRuntimeProfile(attrs, task);
        emit(event(ExecutionEventType.DISPATCH_SKIPPED)
                .identity(identity -> identity.taskId(task.getTid()))
                .attrs(attrs)
                .build());
    }

    public void assignmentRetryScheduled(String taskId,
                                         TaskStatus currentStatus,
                                         String trigger,
                                         String source,
                                         String reason,
                                         long retryDelayMillis) {
        emit(event(ExecutionEventType.ASSIGNMENT_RETRY_SCHEDULED)
                .identity(identity -> identity.taskId(taskId))
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SCHEDULED",
                        "currentStatus", enumName(currentStatus),
                        "retryDelayMillis", retryDelayMillis
                ))
                .build());
    }

    public void workerMatchAccepted(Task task,
                                    SelectedWorkerHandle selectedWorker,
                                    int candidateRank,
                                    String trigger,
                                    String source,
                                    String reason) {
        if (task == null || selectedWorker == null) {
            return;
        }
        SelectedWorkerTraceEvidence evidence = SelectedWorkerTraceEvidence.from(selectedWorker);
        Map<String, Object> attrs = attrs(
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS",
                "workerGroupId", selectedWorker.workerGroupId(),
                "eventBindingKey", evidence.eventBindingKey(),
                "workerCandidateSource", evidence.workerCandidateSource(),
                "workerSchedulingResourceId", evidence.workerSchedulingResourceId(),
                "workerSchedulingRoutingTags", evidence.workerSchedulingRoutingTags(),
                "workerSchedulingAttributes", evidence.workerSchedulingAttributes(),
                "workerSchedulingMatchesRoutingCode", evidence.workerSchedulingMatchesRoutingCode(),
                "candidateRank", Math.max(candidateRank, 0),
                "candidateScore", evidence.candidateScore(),
                "workerActiveLeaseCount", evidence.workerActiveLeaseCount(),
                "workerReservedCount", evidence.workerReservedCount(),
                "workerDeclaredCapacity", evidence.workerDeclaredCapacity(),
                "workerEstimatedLoadRatio", evidence.workerEstimatedLoadRatio(),
                "exclusiveWorkerLock", selectedWorker.exclusiveWorkerLock()
        );
        putTaskRuntimeProfile(attrs, task);
        emit(event(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                .identity(identity -> identity
                        .taskId(task.getTid())
                        .workerId(selectedWorker.workerId()))
                .attrs(attrs)
                .build());
    }

    public void callbackAccepted(TaskWorkTraceView workView, String reason) {
        if (workView == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_ACCEPTED, workView, reason, "SUCCESS", true));
    }

    public void callbackIgnoredDuplicate(TaskWorkTraceView workView, String reason) {
        if (workView == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_IGNORED_DUPLICATE, workView, reason, "IGNORED", true));
    }

    public void callbackIgnoredLate(TaskWorkTraceView workView, String reason) {
        if (workView == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_IGNORED_LATE, workView, reason, "IGNORED", true));
    }

    public void callbackRejectedNoActiveAttempt(String taskId,
                                                String messageId,
                                                MessageStatus workStatus,
                                                String reason) {
        emit(event(ExecutionEventType.CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT)
                .identity(identity -> identity.taskId(taskId).messageId(messageId))
                .outcome(false, null, reason)
                .attrs(attrs(
                        "source", "TaskManager",
                        "reason", reason,
                        "result", "REJECTED",
                        "workStatus", enumName(workStatus)
                ))
                .build());
    }

    public void callbackRejectedNoActiveLease(TaskWorkTraceView workView, String reason) {
        if (workView == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_REJECTED_NO_ACTIVE_LEASE, workView, reason, "REJECTED", false));
    }

    public void callbackRejectedInvalidState(TaskWorkTraceView workView, String reason) {
        if (workView == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_REJECTED_INVALID_STATE, workView, reason, "REJECTED", false));
    }

    public void taskWorkAttemptClosed(Task task,
                                         TaskWorkTraceView workView,
                                         String attemptId,
                                         Integer attemptNo,
                                         String workerId,
                                         String batchId,
                                         AttemptStatus attemptStatus,
                                         AttemptFinalReason attemptFinalReason,
                                         String trigger,
                                         String source,
                                         String reason) {
        if (task == null || workView == null || attemptId == null || attemptId.isBlank()) {
            return;
        }
        emit(event(ExecutionEventType.TASK_WORK_ATTEMPT_CLOSED)
                .identity(identity -> identity
                        .taskId(task.getTid())
                        .messageId(workView.messageId())
                        .attemptId(attemptId)
                        .workerId(workerId))
                .outcome(true, null, reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "attemptNo", attemptNo,
                        "attemptStatus", enumName(attemptStatus),
                        "attemptFinalReason", enumName(attemptFinalReason),
                        "workStatus", enumName(workView.status()),
                        "workFinalReason", enumName(workView.finalReason()),
                        "batchId", batchId
                ))
                .build());
    }

    public void taskWorkLogicallyFinal(Task task,
                                          TaskWorkTraceView workView,
                                          String attemptId,
                                          String workerId,
                                          String batchId,
                                          String trigger,
                                          String source,
                                          String reason) {
        if (task == null || workView == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_WORK_LOGICALLY_FINAL)
                .identity(identity -> identity
                        .taskId(task.getTid())
                        .messageId(workView.messageId())
                        .attemptId(attemptId != null ? attemptId : workView.latestAttemptId())
                        .workerId(workerId != null ? workerId : workView.latestAttemptWorkerId()))
                .outcome(true, workView.errorCode(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "workStatus", enumName(workView.status()),
                        "workFinalReason", enumName(workView.finalReason()),
                        "retryCount", workView.retryCount(),
                        "latestAttemptBatchId", batchId != null ? batchId : workView.latestAttemptBatchId()
                ))
                .build());
    }

    public void resourceReleased(String taskId, String workerId, String reason) {
        resourceReleased(taskId, workerId, null, "TaskResourceReleaseListener", reason, null);
    }

    public void resourceReleased(String taskId,
                                 String workerId,
                                 String trigger,
                                 String source,
                                 String reason,
                                 String resourceKind) {
        emit(event(ExecutionEventType.RESOURCE_RELEASED)
                .identity(identity -> identity
                        .taskId(taskId)
                        .workerId(workerId))
                .outcome(true, null, reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "resourceKind", resourceKind,
                        "result", "SUCCESS"
                ))
                .build());
    }

    public void resourceReleaseFailed(String taskId, String workerId, String reason) {
        emit(event(ExecutionEventType.RESOURCE_RELEASE_FAILED)
                .identity(identity -> identity
                        .taskId(taskId)
                        .workerId(workerId))
                .outcome(false, null, reason)
                .attrs(attrs(
                        "source", "TaskResourceReleaseListener",
                        "reason", reason,
                        "result", "FAILED"
                ))
                .build());
    }

    public void leaseExpired(TaskWorkTraceView workView,
                             String attemptId,
                             String workerId,
                             String batchId,
                             MessageStatus fromStatus,
                             MessageStatus toStatus,
                             String errorCode,
                             String trigger,
                             String source,
                             String reason) {
        if (workView == null) {
            return;
        }
        emit(event(ExecutionEventType.LEASE_EXPIRED)
                .identity(identity -> identity
                        .taskId(workView.taskId())
                        .messageId(workView.messageId())
                        .attemptId(attemptId != null ? attemptId : workView.latestAttemptId())
                        .workerId(workerId != null ? workerId : workView.latestAttemptWorkerId()))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .outcome(false, errorCode != null ? errorCode : workView.errorCode(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", enumName(toStatus),
                        "latestAttemptBatchId", batchId != null ? batchId : workView.latestAttemptBatchId()
                ))
                .build());
    }

    public void taskProgressSnapshot(Task task,
                                     TaskRuntimeProgressSnapshot stats,
                                     String resolutionOutcome,
                                     boolean needsTerminalClosure,
                                     String trigger,
                                     String source,
                                     String reason) {
        if (task == null || stats == null) {
            return;
        }
        Map<String, Object> attrs = attrs(
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS",
                "taskStatus", enumName(task.getStatus()),
                "terminalReason", enumName(task.getTerminalReason()),
                "taskTargetNumber", task.getTaskTargetNumber(),
                "taskEligibleNumber", task.getTaskEligibleNumber(),
                "taskSuccessNumber", task.getTaskSuccessNumber(),
                "taskNonSuccessNumber", task.getTaskNonSuccessNumber(),
                "peakAssignedWorkerCount", task.getPeakAssignedWorkerCount(),
                "minRequiredWorkerCount", task.getMinRequiredWorkerCount(),
                "batchSize", task.getExecutionSpec().getBatchSize(),
                "intakeStatus", enumName(task.getIntakeStatus()),
                "holdReason", enumName(task.getHoldReason()),
                "schedulable", task.isSchedulable(),
                "progressPercent", formatDouble(task.getProgressPercentage()),
                "totalMessages", stats.totalCount(),
                "successMessages", stats.successCount(),
                "failedMessages", stats.failedCount(),
                "expiredMessages", stats.expiredCount(),
                "processingMessages", stats.processingCount(),
                "finalMessages", stats.finalCount(),
                "pendingMessages", pendingCount(stats),
                "successRate", formatDouble(successRate(stats)),
                "failureRate", formatDouble(failureRate(stats)),
                "resolutionOutcome", resolutionOutcome,
                "needsTerminalClosure", needsTerminalClosure
        );
        putTaskRuntimeProfile(attrs, task);
        emit(event(ExecutionEventType.TASK_PROGRESS_SNAPSHOT)
                .identity(identity -> identity.taskId(task.getTid()))
                .attrs(attrs)
                .build());
    }

    public void assignmentSummary(Task task,
                                  TaskStatus initialStatus,
                                  TaskStatus currentStatus,
                                  Integer pendingDispatchCount,
                                  Integer desiredDispatchWorkerCount,
                                  Integer requiredStartWorkerCount,
                                  Integer requestedMatchCount,
                                  Integer workerBudget,
                                  Integer currentTaskWorkerCount,
                                  Boolean budgetLimited,
                                  Integer matchedWorkerCount,
                                  Integer dispatchCandidateCount,
                                  Integer dispatchedMessageCount,
                                  Integer usedWorkerCount,
                                  Integer peakAssignedWorkerCount,
                                  String trigger,
                                  String source,
                                  String reason,
                                  String result) {
        if (task == null) {
            return;
        }
        Map<String, Object> attrs = attrs(
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", result,
                "initialStatus", enumName(initialStatus),
                "currentStatus", enumName(currentStatus),
                "pendingDispatchCount", pendingDispatchCount,
                "desiredDispatchWorkerCount", desiredDispatchWorkerCount,
                "requiredStartWorkerCount", requiredStartWorkerCount,
                "requestedMatchCount", requestedMatchCount,
                "workerBudget", workerBudget,
                "currentTaskWorkerCount", currentTaskWorkerCount,
                "budgetLimited", budgetLimited,
                "matchedWorkerCount", matchedWorkerCount,
                "dispatchCandidateCount", dispatchCandidateCount,
                "dispatchedMessageCount", dispatchedMessageCount,
                "usedWorkerCount", usedWorkerCount,
                "peakAssignedWorkerCount", peakAssignedWorkerCount
        );
        putTaskRuntimeProfile(attrs, task);
        emit(event(ExecutionEventType.ASSIGNMENT_SUMMARY)
                .identity(identity -> identity.taskId(task.getTid()))
                .attrs(attrs)
                .build());
    }

    public void dispatchBindingSummary(Task task,
                                       int pendingMessageCount,
                                       int matchedWorkerCount,
                                       int dispatchSlotCount,
                                       int dispatchedMessageCount,
                                       int uniqueWorkerCount,
                                       int perWorkerBatchLimit,
                                       String trigger,
                                       String source,
                                       String reason,
                                       String result) {
        if (task == null) {
            return;
        }
        Map<String, Object> attrs = attrs(
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", result,
                "pendingMessageCount", pendingMessageCount,
                "matchedWorkerCount", matchedWorkerCount,
                "dispatchSlotCount", dispatchSlotCount,
                "dispatchedMessageCount", dispatchedMessageCount,
                "unassignedMessageCount", Math.max(pendingMessageCount - dispatchedMessageCount, 0),
                "uniqueWorkerCount", uniqueWorkerCount,
                "perWorkerBatchLimit", perWorkerBatchLimit
        );
        putTaskRuntimeProfile(attrs, task);
        emit(event(ExecutionEventType.DISPATCH_BINDING_SUMMARY)
                .identity(identity -> identity.taskId(task.getTid()))
                .attrs(attrs)
                .build());
    }

    public void taskStateValidationSummary(String taskId,
                                           TaskStatus taskStatus,
                                           TaskTerminalReason terminalReason,
                                           long totalMessages,
                                           long successMessages,
                                           long failedMessages,
                                           long processingMessages,
                                           boolean valid,
                                           boolean needsResolution,
                                           int violationCount,
                                           String violations,
                                           String trigger,
                                           String source,
                                           String validationScope,
                                           String reason,
                                           String result) {
        emit(event(ExecutionEventType.TASK_STATE_VALIDATION_SUMMARY)
                .identity(identity -> identity.taskId(taskId))
                .outcome(valid && !needsResolution, null, reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", result,
                        "taskStatus", enumName(taskStatus),
                        "terminalReason", enumName(terminalReason),
                        "totalMessages", totalMessages,
                        "successMessages", successMessages,
                        "failedMessages", failedMessages,
                        "processingMessages", processingMessages,
                        "pendingMessages", Math.max(totalMessages - (successMessages + failedMessages), 0),
                        "valid", valid,
                        "needsResolution", needsResolution,
                        "violationCount", violationCount,
                        "violations", violations,
                        "validationScope", validationScope
                ))
                .build());
    }

    public void assignmentQueueSnapshot(Task task,
                                        TaskStatus taskStatus,
                                        String dispatchLane,
                                        int queueDepth,
                                        int trackedBatchPendingCount,
                                        int scheduledRetryCount,
                                        String queueAction,
                                        Long retryDelayMillis,
                                        String trigger,
                                        String source,
                                        String reason,
                                        String result) {
        assignmentQueueSnapshot(task,
                taskStatus,
                dispatchLane,
                queueDepth,
                trackedBatchPendingCount,
                scheduledRetryCount,
                queueAction,
                retryDelayMillis,
                null,
                trigger,
                source,
                reason,
                result);
    }

    public void assignmentQueueSnapshot(Task task,
                                        TaskStatus taskStatus,
                                        String dispatchLane,
                                        int queueDepth,
                                        int trackedBatchPendingCount,
                                        int scheduledRetryCount,
                                        String queueAction,
                                        Long retryDelayMillis,
                                        Long assignmentDurationMillis,
                                        String trigger,
                                        String source,
                                        String reason,
                                        String result) {
        Map<String, Object> attrs = attrs(
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", result,
                "taskStatus", enumName(taskStatus),
                "dispatchLane", dispatchLane,
                "queueDepth", queueDepth,
                "trackedBatchPendingCount", trackedBatchPendingCount,
                "scheduledRetryCount", scheduledRetryCount,
                "queueAction", queueAction,
                "retryDelayMillis", retryDelayMillis,
                "assignmentDurationMillis", assignmentDurationMillis
        );
        putTaskRuntimeProfile(attrs, task);
        emit(event(ExecutionEventType.ASSIGNMENT_QUEUE_SNAPSHOT)
                .identity(identity -> identity.taskId(task != null ? task.getTid() : null))
                .attrs(attrs)
                .build());
    }

    public void workerCapabilityReportApplied(WorkerCapabilityReportResult result) {
        if (result == null) {
            return;
        }
        emit(event(ExecutionEventType.WORKER_CAPABILITY_REPORT_APPLIED)
                .identity(identity -> identity.workerId(result.workerId()))
                .outcome(result.success(), result.success() ? null : result.status().name(), result.reason())
                .attrs(attrs(
                        "source", "WorkerCapabilityAuthority",
                        "reason", result.reason(),
                        "result", result.status().name(),
                        "capabilityVersion", result.capabilityVersion(),
                        "snapshotChanged", result.snapshotChanged()
                ))
                .build());
    }

    public void workerCommandStatusTransition(WorkerCommandLifecycleResult result) {
        if (result == null || result.record() == null) {
            return;
        }
        emit(event(ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION)
                .identity(identity -> identity.workerId(result.record().workerId()))
                .transition(enumName(result.previousStatus()), enumName(result.currentStatus()), result.reason())
                .outcome(result.success(), result.success() ? null : result.code().name(), result.reason())
                .attrs(attrs(
                        "source", "WorkerCommandLifecycleOwner",
                        "reason", result.reason(),
                        "result", result.code().name(),
                        "commandId", result.record().commandId(),
                        "commandType", result.record().commandType(),
                        "commandStatus", enumName(result.record().status()),
                        "requester", result.record().requester(),
                        "idempotencyKey", result.record().idempotencyKey(),
                        "deadlineEpochMillis", result.record().deadlineEpochMillis()
                ))
                .build());
    }

    public void workerStateReportApplied(WorkerStateProjectionResult result) {
        if (result == null) {
            return;
        }
        emit(event(ExecutionEventType.WORKER_STATE_REPORT_APPLIED)
                .identity(identity -> identity.workerId(result.workerId()))
                .outcome(result.success(), result.success() ? null : result.status().name(), result.reason())
                .attrs(attrs(
                        "source", "WorkerStateProjectionOwner",
                        "reason", result.reason(),
                        "result", result.status().name(),
                        "stateVersion", result.stateVersion(),
                        "projectionChanged", result.projectionChanged(),
                        "workerState", result.projection() != null ? result.projection().state() : null,
                        "observedAt", result.projection() != null ? result.projection().observedAt() : null,
                        "recentReportCount", result.projection() != null ? result.projection().recentReports().size() : null
                ))
                .build());
    }

    public void taskStageEvidenceApplied(TaskStageEvidenceResult result) {
        if (result == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_STAGE_EVIDENCE_APPLIED)
                .identity(identity -> identity
                        .taskId(result.taskId())
                        .messageId(result.messageId()))
                .outcome(result.success(), result.success() ? null : result.status().name(), result.reason())
                .attrs(attrs(
                        "source", "TaskStageEvidenceOwner",
                        "reason", result.reason(),
                        "result", result.status().name(),
                        "stageName", result.stageName(),
                        "stageVersion", result.stageVersion(),
                        "stageStatus", result.projection() != null ? result.projection().stageStatus() : null,
                        "projectionChanged", result.projectionChanged(),
                        "observedAt", result.projection() != null ? result.projection().observedAt() : null,
                        "recentEvidenceCount", result.projection() != null ? result.projection().recentEvidence().size() : null,
                        "stableFinalResult", false
                ))
                .build());
    }

    private ExecutionEvent callbackEvent(ExecutionEventType eventType,
                                         TaskWorkTraceView workView,
                                         String reason,
                                         String result,
                                         boolean success) {
        return event(eventType)
                .identity(identity -> identity
                        .taskId(workView.taskId())
                        .messageId(workView.messageId())
                        .attemptId(workView.latestAttemptId())
                        .workerId(workView.latestAttemptWorkerId()))
                .outcome(success, workView.errorCode(), reason)
                .attrs(attrs(
                        "source", "TaskManager",
                        "reason", reason,
                        "result", result,
                        "workStatus", enumName(workView.status()),
                        "latestAttemptBatchId", workView.latestAttemptBatchId()
                ))
                .build();
    }

    private ExecutionEvent.Builder event(ExecutionEventType eventType) {
        ExecutionEvent.Builder builder = ExecutionEvent.builder().eventType(eventType);
        String traceId = MDC.get(LogUtils.TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            builder.traceId(traceId);
        }
        return builder;
    }

    private void emit(ExecutionEvent event) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            sink.emit(event);
            Map<String, String> flattened = flatten(event);
            MDC.put("event", event.getEventType().name());
            for (Map.Entry<String, String> entry : flattened.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    MDC.put(entry.getKey(), entry.getValue());
                }
            }
            LOG.info(event.getEventType().name());
        } catch (RuntimeException e) {
            LOG.warn("Failed to emit trace event {}", event != null ? event.getEventType() : null, e);
        } finally {
            MDC.clear();
            if (previous != null) {
                previous.forEach(MDC::put);
            }
        }
    }

    private static Map<String, Object> attrs(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            values.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return values;
    }

    private static String enumName(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    private static String retryResetSourceStatus(MessageStatus fromStatus) {
        return fromStatus != null ? fromStatus.name() : "FAILED_OR_EXPIRED";
    }

    private static String formatDouble(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static long pendingCount(TaskRuntimeProgressSnapshot stats) {
        return Math.max(stats.totalCount() - stats.finalCount(), 0L);
    }

    private static double successRate(TaskRuntimeProgressSnapshot stats) {
        return stats.totalCount() == 0 ? 0.0 : (double) stats.successCount() / stats.totalCount() * 100.0;
    }

    private static double failureRate(TaskRuntimeProgressSnapshot stats) {
        return stats.totalCount() == 0
                ? 0.0
                : (double) (stats.failedCount() + stats.expiredCount()) / stats.totalCount() * 100.0;
    }

    private static void putTaskRuntimeProfile(Map<String, Object> attrs, Task task) {
        if (task == null) {
            return;
        }
        ResolvedTaskSchedulingPolicy policy =
                TRACE_SCHEDULING_PLANE_RESOLVER.resolve(task).taskSchedulingPolicy();
        attrs.put("taskPolicyPreset", policy.taskPolicyPreset());
        attrs.put("workloadClass", enumName(policy.workloadClass()));
        attrs.put("foreground", task.getExecutionSpec().isForeground());
        attrs.put("dispatchLane", enumName(policy.dispatchLane()));
        attrs.put("dispatchPriority", enumName(policy.dispatchPriority()));
        attrs.put("batchPolicy", enumName(policy.batchPolicy()));
        attrs.put("leaseProfile", enumName(policy.leaseProfile()));
        attrs.put("backpressureClass", enumName(policy.backpressureClass()));
        attrs.put("dispatchCadence", enumName(policy.dispatchCadence()));
        attrs.put("workerResourceMode", enumName(policy.workerResourceMode()));
        attrs.put("idleCloseEnabled", policy.idleClosePolicy().enabled());
        attrs.put("idleCloseRequiresSealed", policy.idleClosePolicy().requireIntakeSealed());
        attrs.put("resultExpiredLeaseRetryFromAnyActiveState",
                policy.resultFinalityPolicy().retryExpiredLeaseFromAnyActiveState());
        attrs.put("resultExpiredLeaseFinalizesAsFailure",
                policy.resultFinalityPolicy().expiredLeaseFinalizesAsFailure());
        attrs.put("claimSmallPerWorkerCapacityLimit", policy.claimPolicy().smallPerWorkerCapacityLimit());
        attrs.put("claimShortLeaseSeconds", policy.claimPolicy().shortLeaseSeconds());
        attrs.put("retryWorkloadClass", enumName(policy.retryPolicy().workloadClass()));
        attrs.put("interactiveAssignmentRetryDelayMillis",
                policy.retryPolicy().interactiveAssignmentRetryDelayMillis());
        attrs.put("interactiveWorkRetryDelayMillis", policy.retryPolicy().interactiveWorkRetryDelayMillis());
        attrs.put("bulkWorkRetryDelayMillis", policy.retryPolicy().bulkWorkRetryDelayMillis());
        attrs.put("backpressureMaxReadyItemsPerTask", policy.backpressurePolicy().maxReadyItemsPerTask());
    }

    private static Map<String, String> flatten(ExecutionEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (event == null) {
            return fields;
        }
        put(fields, "traceId", event.getTraceId());
        if (event.getIdentity() != null) {
            put(fields, "taskId", event.getIdentity().taskId());
            put(fields, "messageId", event.getIdentity().messageId());
            put(fields, "attemptId", event.getIdentity().attemptId());
            put(fields, "workerId", event.getIdentity().workerId());
            put(fields, "leaseToken", event.getIdentity().leaseToken());
        }
        if (event.getTransition() != null) {
            put(fields, "fromStatus", event.getTransition().src());
            put(fields, "toStatus", event.getTransition().dst());
        }
        if (event.getAttrs() != null) {
            for (Map.Entry<String, Object> entry : event.getAttrs().entrySet()) {
                put(fields, entry.getKey(), stringify(entry.getValue()));
            }
        }
        return fields;
    }

    private static void put(Map<String, String> fields, String key, String value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record TaskWorkTraceView(
            String taskId,
            String messageId,
            String latestAttemptId,
            String latestAttemptWorkerId,
            String latestAttemptBatchId,
            MessageStatus status,
            MessageFinalReason finalReason,
            int retryCount,
            String errorCode
    ) {
    }
}
