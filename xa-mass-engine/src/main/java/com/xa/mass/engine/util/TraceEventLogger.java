package com.xa.mass.engine.util;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskMessageCompatibilityState.AttemptFinalReason;
import com.xa.mass.engine.TaskMessageCompatibilityState.AttemptStatus;
import com.xa.mass.engine.TaskMessageCompatibilityState.MessageFinalReason;
import com.xa.mass.engine.TaskMessageCompatibilityState.MessageStatus;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;
import com.xa.mass.runtime.api.TaskWorkStats;
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
    private static final TaskRuntimeProfileResolver TASK_RUNTIME_PROFILE_RESOLVER = new TaskRuntimeProfileResolver();

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

    public void taskMsgStatusTransition(TaskMessageTraceView taskMsg,
                                        MessageStatus fromStatus,
                                        MessageStatus toStatus,
                                        String trigger,
                                        String source,
                                        String reason) {
        taskMsgStatusTransition(taskMsg, null, null, null, null, fromStatus, toStatus, trigger, source, reason);
    }

    public void taskMsgStatusTransition(TaskMessageTraceView taskMsg,
                                        String attemptId,
                                        String workerId,
                                        String workerContextId,
                                        String batchId,
                                        MessageStatus fromStatus,
                                        MessageStatus toStatus,
                                        String trigger,
                                        String source,
                                        String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_STATUS_TRANSITION)
                .identity(identity -> identity
                        .taskId(taskMsg.taskId())
                        .messageId(taskMsg.messageId())
                        .attemptId(attemptId != null ? attemptId : taskMsg.latestAttemptId())
                        .workerId(workerId != null ? workerId : taskMsg.latestAttemptWorkerId())
                        .workerContextId(workerContextId != null ? workerContextId : taskMsg.latestAttemptWorkerContextId())
                        .leaseToken(null))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "finalReason", enumName(taskMsg.finalReason()),
                        "latestAttemptBatchId", batchId != null ? batchId : taskMsg.latestAttemptBatchId()
                ))
                .build());
    }

    public void taskMsgAttemptStatusTransition(String taskId,
                                               String messageId,
                                               String attemptId,
                                               Integer attemptNo,
                                               String workerId,
                                               String workerContextId,
                                               String batchId,
                                               AttemptFinalReason finalReason,
                                               AttemptStatus fromStatus,
                                               AttemptStatus toStatus,
                                               String trigger,
                                               String source,
                                               String reason) {
        if (attemptId == null || attemptId.isBlank()) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_ATTEMPT_STATUS_TRANSITION)
                .identity(identity -> identity
                        .taskId(taskId)
                        .messageId(messageId)
                        .attemptId(attemptId)
                        .workerId(workerId)
                        .workerContextId(workerContextId))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "attemptNo", attemptNo,
                        "finalReason", enumName(finalReason),
                        "batchId", batchId
                ))
                .build());
    }

    public void taskMsgRetryReset(TaskMessageTraceView taskMsg,
                                  Long workRetryDelayMillis,
                                  String trigger,
                                  String source,
                                  String reason) {
        taskMsgRetryReset(taskMsg, null, null, null, null, workRetryDelayMillis, trigger, source, reason);
    }

    public void taskMsgRetryReset(TaskMessageTraceView taskMsg,
                                  String attemptId,
                                  String workerId,
                                  String workerContextId,
                                  String batchId,
                                  Long workRetryDelayMillis,
                                  String trigger,
                                  String source,
                                  String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_RETRY_RESET)
                .identity(identity -> identity
                        .taskId(taskMsg.taskId())
                        .messageId(taskMsg.messageId())
                        .attemptId(attemptId != null ? attemptId : taskMsg.latestAttemptId())
                        .workerId(workerId != null ? workerId : taskMsg.latestAttemptWorkerId())
                        .workerContextId(workerContextId != null ? workerContextId : taskMsg.latestAttemptWorkerContextId()))
                .transition("FAILED_OR_EXPIRED", MessageStatus.INIT.name(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "retryCount", taskMsg.retryCount(),
                        "workRetryDelayMillis", workRetryDelayMillis,
                        "latestAttemptBatchId", batchId != null ? batchId : taskMsg.latestAttemptBatchId()
                ))
                .build());
    }

    public void workerContextStatusTransition(String taskId,
                                              WorkerContext workerContext,
                                              WorkerContextStatus fromStatus,
                                              WorkerContextStatus toStatus,
                                              String trigger,
                                              String source,
                                              String reason) {
        if (workerContext == null) {
            return;
        }
        emit(event(ExecutionEventType.WORKER_CONTEXT_STATUS_TRANSITION)
                .identity(identity -> identity
                        .taskId(taskId)
                        .workerId(workerContext.getWorkerId())
                        .workerContextId(workerContext.getWorkerContextId()))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS"
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

    public void workerMatchAccepted(String taskId, Worker worker, WorkerContext workerContext, String reason) {
        if (worker == null) {
            return;
        }
        emit(event(ExecutionEventType.WORKER_MATCH_ACCEPTED)
                .identity(identity -> identity
                        .taskId(taskId)
                        .workerId(worker.getWorkerId())
                        .workerContextId(workerContext != null ? workerContext.getWorkerContextId() : null))
                .outcome(true, null, reason)
                .attrs(attrs(
                        "source", "RuleBasedTaskWorkerMatchingStrategy",
                        "reason", reason,
                        "result", "SUCCESS"
                ))
                .build());
    }

    public void workerMatchRejected(String taskId, Worker worker, WorkerContext workerContext, String reason) {
        if (worker == null) {
            return;
        }
        emit(event(ExecutionEventType.WORKER_MATCH_REJECTED)
                .identity(identity -> identity
                        .taskId(taskId)
                        .workerId(worker.getWorkerId())
                        .workerContextId(workerContext != null ? workerContext.getWorkerContextId() : null))
                .outcome(false, null, reason)
                .attrs(attrs(
                        "source", "RuleBasedTaskWorkerMatchingStrategy",
                        "reason", reason,
                        "result", "REJECTED"
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

    public void callbackAccepted(TaskMessageTraceView taskMsg, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_ACCEPTED, taskMsg, reason, "SUCCESS", true));
    }

    public void callbackIgnoredDuplicate(TaskMessageTraceView taskMsg, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_IGNORED_DUPLICATE, taskMsg, reason, "IGNORED", true));
    }

    public void callbackIgnoredLate(TaskMessageTraceView taskMsg, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_IGNORED_LATE, taskMsg, reason, "IGNORED", true));
    }

    public void callbackRejectedNoActiveAttempt(String taskId,
                                                String messageId,
                                                MessageStatus taskMsgStatus,
                                                String reason) {
        emit(event(ExecutionEventType.CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT)
                .identity(identity -> identity.taskId(taskId).messageId(messageId))
                .outcome(false, null, reason)
                .attrs(attrs(
                        "source", "TaskManager",
                        "reason", reason,
                        "result", "REJECTED",
                        "taskMsgStatus", enumName(taskMsgStatus)
                ))
                .build());
    }

    public void callbackRejectedNoActiveLease(TaskMessageTraceView taskMsg, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_REJECTED_NO_ACTIVE_LEASE, taskMsg, reason, "REJECTED", false));
    }

    public void callbackRejectedInvalidState(TaskMessageTraceView taskMsg, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(ExecutionEventType.CALLBACK_REJECTED_INVALID_STATE, taskMsg, reason, "REJECTED", false));
    }

    public void taskMessageAttemptClosed(Task task,
                                         TaskMessageTraceView taskMsg,
                                         String attemptId,
                                         Integer attemptNo,
                                         String workerId,
                                         String workerContextId,
                                         String batchId,
                                         AttemptStatus attemptStatus,
                                         AttemptFinalReason attemptFinalReason,
                                         String trigger,
                                         String source,
                                         String reason) {
        if (task == null || taskMsg == null || attemptId == null || attemptId.isBlank()) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_ATTEMPT_CLOSED)
                .identity(identity -> identity
                        .taskId(task.getTid())
                        .messageId(taskMsg.messageId())
                        .attemptId(attemptId)
                        .workerId(workerId)
                        .workerContextId(workerContextId))
                .outcome(true, null, reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "attemptNo", attemptNo,
                        "attemptStatus", enumName(attemptStatus),
                        "attemptFinalReason", enumName(attemptFinalReason),
                        "taskMsgStatus", enumName(taskMsg.status()),
                        "taskMsgFinalReason", enumName(taskMsg.finalReason()),
                        "batchId", batchId
                ))
                .build());
    }

    public void taskMessageLogicallyFinal(Task task,
                                          TaskMessageTraceView taskMsg,
                                          String attemptId,
                                          String workerId,
                                          String workerContextId,
                                          String batchId,
                                          String trigger,
                                          String source,
                                          String reason) {
        if (task == null || taskMsg == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_LOGICALLY_FINAL)
                .identity(identity -> identity
                        .taskId(task.getTid())
                        .messageId(taskMsg.messageId())
                        .attemptId(attemptId != null ? attemptId : taskMsg.latestAttemptId())
                        .workerId(workerId != null ? workerId : taskMsg.latestAttemptWorkerId())
                        .workerContextId(workerContextId != null ? workerContextId : taskMsg.latestAttemptWorkerContextId()))
                .outcome(true, taskMsg.errorCode(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "taskMsgStatus", enumName(taskMsg.status()),
                        "taskMsgFinalReason", enumName(taskMsg.finalReason()),
                        "retryCount", taskMsg.retryCount(),
                        "latestAttemptBatchId", batchId != null ? batchId : taskMsg.latestAttemptBatchId()
                ))
                .build());
    }

    public void resourceReleased(String taskId, String workerId, String workerContextId, String reason) {
        emit(event(ExecutionEventType.RESOURCE_RELEASED)
                .identity(identity -> identity
                        .taskId(taskId)
                        .workerId(workerId)
                        .workerContextId(workerContextId))
                .outcome(true, null, reason)
                .attrs(attrs(
                        "source", "TaskResourceReleaseListener",
                        "reason", reason,
                        "result", "SUCCESS"
                ))
                .build());
    }

    public void resourceReleaseFailed(String taskId, String workerId, String workerContextId, String reason) {
        emit(event(ExecutionEventType.RESOURCE_RELEASE_FAILED)
                .identity(identity -> identity
                        .taskId(taskId)
                        .workerId(workerId)
                        .workerContextId(workerContextId))
                .outcome(false, null, reason)
                .attrs(attrs(
                        "source", "TaskResourceReleaseListener",
                        "reason", reason,
                        "result", "FAILED"
                ))
                .build());
    }

    public void leaseExpired(TaskMessageTraceView taskMsg,
                             String attemptId,
                             String workerId,
                             String workerContextId,
                             String batchId,
                             String trigger,
                             String source,
                             String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(event(ExecutionEventType.LEASE_EXPIRED)
                .identity(identity -> identity
                        .taskId(taskMsg.taskId())
                        .messageId(taskMsg.messageId())
                        .attemptId(attemptId != null ? attemptId : taskMsg.latestAttemptId())
                        .workerId(workerId != null ? workerId : taskMsg.latestAttemptWorkerId())
                        .workerContextId(workerContextId != null ? workerContextId : taskMsg.latestAttemptWorkerContextId()))
                .transition(enumName(taskMsg.status()), MessageStatus.EXPIRED.name(), reason)
                .outcome(false, taskMsg.errorCode(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "EXPIRED",
                        "latestAttemptBatchId", batchId != null ? batchId : taskMsg.latestAttemptBatchId()
                ))
                .build());
    }

    public void taskProgressSnapshot(Task task,
                                     TaskWorkStats stats,
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
                "batchSize", task.getBatchSize(),
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
                "pendingMessages", stats.pendingCount(),
                "successRate", formatDouble(stats.successRate()),
                "failureRate", formatDouble(stats.failureRate()),
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
                                       int uniqueWorkerContextCount,
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
                "uniqueWorkerContextCount", uniqueWorkerContextCount,
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
                "retryDelayMillis", retryDelayMillis
        );
        putTaskRuntimeProfile(attrs, task);
        emit(event(ExecutionEventType.ASSIGNMENT_QUEUE_SNAPSHOT)
                .identity(identity -> identity.taskId(task != null ? task.getTid() : null))
                .attrs(attrs)
                .build());
    }

    private ExecutionEvent callbackEvent(ExecutionEventType eventType,
                                         TaskMessageTraceView taskMsg,
                                         String reason,
                                         String result,
                                         boolean success) {
        return event(eventType)
                .identity(identity -> identity
                        .taskId(taskMsg.taskId())
                        .messageId(taskMsg.messageId())
                        .attemptId(taskMsg.latestAttemptId())
                        .workerId(taskMsg.latestAttemptWorkerId())
                        .workerContextId(taskMsg.latestAttemptWorkerContextId()))
                .outcome(success, taskMsg.errorCode(), reason)
                .attrs(attrs(
                        "source", "TaskManager",
                        "reason", reason,
                        "result", result,
                        "taskMsgStatus", enumName(taskMsg.status()),
                        "latestAttemptBatchId", taskMsg.latestAttemptBatchId()
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

    private static String formatDouble(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static void putTaskRuntimeProfile(Map<String, Object> attrs, Task task) {
        if (task == null) {
            return;
        }
        TaskRuntimeProfile profile = TASK_RUNTIME_PROFILE_RESOLVER.resolve(task);
        attrs.put("workloadClass", enumName(task.getWorkloadClass()));
        attrs.put("dispatchLane", enumName(profile.dispatchLane()));
        attrs.put("dispatchPriority", enumName(profile.dispatchPriority()));
        attrs.put("batchPolicy", enumName(profile.batchPolicy()));
        attrs.put("leaseProfile", enumName(profile.leaseProfile()));
        attrs.put("backpressureClass", enumName(profile.backpressureClass()));
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
            put(fields, "workerContextId", event.getIdentity().workerContextId());
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

    public record TaskMessageTraceView(
            String taskId,
            String messageId,
            String latestAttemptId,
            String latestAttemptWorkerId,
            String latestAttemptWorkerContextId,
            String latestAttemptBatchId,
            MessageStatus status,
            MessageFinalReason finalReason,
            int retryCount,
            String errorCode
    ) {
    }
}
