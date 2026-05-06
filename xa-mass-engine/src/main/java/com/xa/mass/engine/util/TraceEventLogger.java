package com.xa.mass.engine.util;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
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

    public void taskMsgStatusTransition(TaskMsg taskMsg,
                                        TaskMsgStatus fromStatus,
                                        TaskMsgStatus toStatus,
                                        String trigger,
                                        String source,
                                        String reason) {
        taskMsgStatusTransition(taskMsg, null, fromStatus, toStatus, trigger, source, reason);
    }

    public void taskMsgStatusTransition(TaskMsg taskMsg,
                                        TaskMsgAttempt attempt,
                                        TaskMsgStatus fromStatus,
                                        TaskMsgStatus toStatus,
                                        String trigger,
                                        String source,
                                        String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_STATUS_TRANSITION)
                .identity(identity -> identity
                        .taskId(taskMsg.getTaskId())
                        .messageId(taskMsg.getMessageId())
                        .attemptId(attempt != null ? attempt.getAttemptId() : taskMsg.latestAttemptId())
                        .workerId(latestAttemptWorkerId(taskMsg, attempt))
                        .workerContextId(latestAttemptWorkerContextId(taskMsg, attempt))
                        .leaseToken(null))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "finalReason", enumName(taskMsg.getFinalReason()),
                        "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt)
                ))
                .build());
    }

    public void taskMsgAttemptStatusTransition(TaskMsgAttempt attempt,
                                               TaskMsgAttemptStatus fromStatus,
                                               TaskMsgAttemptStatus toStatus,
                                               String trigger,
                                               String source,
                                               String reason) {
        if (attempt == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_ATTEMPT_STATUS_TRANSITION)
                .identity(identity -> identity
                        .taskId(attempt.getTaskId())
                        .messageId(attempt.getMessageId())
                        .attemptId(attempt.getAttemptId())
                        .workerId(attempt.getWorkerId())
                        .workerContextId(attempt.getWorkerContextId()))
                .transition(enumName(fromStatus), enumName(toStatus), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "attemptNo", attempt.getAttemptNo(),
                        "finalReason", enumName(attempt.getFinalReason()),
                        "batchId", attempt.getBatchId()
                ))
                .build());
    }

    public void taskMsgRetryReset(TaskMsg taskMsg, String trigger, String source, String reason) {
        taskMsgRetryReset(taskMsg, null, null, trigger, source, reason);
    }

    public void taskMsgRetryReset(TaskMsg taskMsg,
                                  TaskMsgAttempt attempt,
                                  Long workRetryDelayMillis,
                                  String trigger,
                                  String source,
                                  String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_RETRY_RESET)
                .identity(identity -> identity
                        .taskId(taskMsg.getTaskId())
                        .messageId(taskMsg.getMessageId())
                        .attemptId(attempt != null ? attempt.getAttemptId() : taskMsg.latestAttemptId())
                        .workerId(latestAttemptWorkerId(taskMsg, attempt))
                        .workerContextId(latestAttemptWorkerContextId(taskMsg, attempt)))
                .transition("FAILED_OR_EXPIRED", TaskMsgStatus.INIT.name(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "retryCount", taskMsg.getRetryCount(),
                        "workRetryDelayMillis", workRetryDelayMillis,
                        "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt)
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

    public void callbackAccepted(TaskMsg taskMsg, String reason) {
        callbackAccepted(taskMsg, null, reason);
    }

    public void callbackAccepted(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(
                ExecutionEventType.CALLBACK_ACCEPTED,
                taskMsg,
                attempt,
                reason,
                "SUCCESS",
                true
        ));
    }

    public void callbackIgnoredDuplicate(TaskMsg taskMsg, String reason) {
        callbackIgnoredDuplicate(taskMsg, null, reason);
    }

    public void callbackIgnoredDuplicate(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(
                ExecutionEventType.CALLBACK_IGNORED_DUPLICATE,
                taskMsg,
                attempt,
                reason,
                "IGNORED",
                true
        ));
    }

    public void callbackIgnoredLate(TaskMsg taskMsg, String reason) {
        callbackIgnoredLate(taskMsg, null, reason);
    }

    public void callbackIgnoredLate(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(
                ExecutionEventType.CALLBACK_IGNORED_LATE,
                taskMsg,
                attempt,
                reason,
                "IGNORED",
                true
        ));
    }

    public void callbackRejectedNoActiveAttempt(String taskId,
                                                String messageId,
                                                TaskMsgStatus taskMsgStatus,
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

    public void callbackRejectedNoActiveLease(TaskMsg taskMsg, String reason) {
        callbackRejectedNoActiveLease(taskMsg, null, reason);
    }

    public void callbackRejectedNoActiveLease(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(
                ExecutionEventType.CALLBACK_REJECTED_NO_ACTIVE_LEASE,
                taskMsg,
                attempt,
                reason,
                "REJECTED",
                false
        ));
    }

    public void callbackRejectedInvalidState(TaskMsg taskMsg, String reason) {
        callbackRejectedInvalidState(taskMsg, null, reason);
    }

    public void callbackRejectedInvalidState(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(callbackEvent(
                ExecutionEventType.CALLBACK_REJECTED_INVALID_STATE,
                taskMsg,
                attempt,
                reason,
                "REJECTED",
                false
        ));
    }

    public void taskMessageAttemptClosed(Task task,
                                         TaskMsg taskMsg,
                                         TaskMsgAttempt attempt,
                                         String trigger,
                                         String source,
                                         String reason) {
        if (task == null || taskMsg == null || attempt == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_ATTEMPT_CLOSED)
                .identity(identity -> identity
                        .taskId(task.getTid())
                        .messageId(taskMsg.getMessageId())
                        .attemptId(attempt.getAttemptId())
                        .workerId(attempt.getWorkerId())
                        .workerContextId(attempt.getWorkerContextId()))
                .outcome(true, null, reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "attemptNo", attempt.getAttemptNo(),
                        "attemptStatus", enumName(attempt.getStatus()),
                        "attemptFinalReason", enumName(attempt.getFinalReason()),
                        "taskMsgStatus", enumName(taskMsg.getStatus()),
                        "taskMsgFinalReason", enumName(taskMsg.getFinalReason()),
                        "batchId", attempt.getBatchId()
                ))
                .build());
    }

    public void taskMessageLogicallyFinal(Task task,
                                          TaskMsg taskMsg,
                                          String trigger,
                                          String source,
                                          String reason) {
        taskMessageLogicallyFinal(task, taskMsg, null, trigger, source, reason);
    }

    public void taskMessageLogicallyFinal(Task task,
                                          TaskMsg taskMsg,
                                          TaskMsgAttempt attempt,
                                          String trigger,
                                          String source,
                                          String reason) {
        if (task == null || taskMsg == null) {
            return;
        }
        emit(event(ExecutionEventType.TASK_MSG_LOGICALLY_FINAL)
                .identity(identity -> identity
                        .taskId(task.getTid())
                        .messageId(taskMsg.getMessageId())
                        .attemptId(attempt != null ? attempt.getAttemptId() : taskMsg.latestAttemptId())
                        .workerId(latestAttemptWorkerId(taskMsg, attempt))
                        .workerContextId(latestAttemptWorkerContextId(taskMsg, attempt)))
                .outcome(true, taskMsg.getErrorCode(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "SUCCESS",
                        "taskMsgStatus", enumName(taskMsg.getStatus()),
                        "taskMsgFinalReason", enumName(taskMsg.getFinalReason()),
                        "retryCount", taskMsg.getRetryCount(),
                        "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt)
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

    public void leaseExpired(TaskMsg taskMsg,
                             TaskMsgAttempt attempt,
                             String trigger,
                             String source,
                             String reason) {
        if (taskMsg == null) {
            return;
        }
        emit(event(ExecutionEventType.LEASE_EXPIRED)
                .identity(identity -> identity
                        .taskId(taskMsg.getTaskId())
                        .messageId(taskMsg.getMessageId())
                        .attemptId(attempt != null ? attempt.getAttemptId() : taskMsg.latestAttemptId())
                        .workerId(latestAttemptWorkerId(taskMsg, attempt))
                        .workerContextId(latestAttemptWorkerContextId(taskMsg, attempt)))
                .transition(enumName(taskMsg.getStatus()), TaskMsgStatus.EXPIRED.name(), reason)
                .outcome(false, taskMsg.getErrorCode(), reason)
                .attrs(attrs(
                        "trigger", trigger,
                        "source", source,
                        "reason", reason,
                        "result", "EXPIRED",
                        "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt)
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
                                         TaskMsg taskMsg,
                                         TaskMsgAttempt attempt,
                                         String reason,
                                         String result,
                                         boolean success) {
        return event(eventType)
                .identity(identity -> identity
                        .taskId(taskMsg.getTaskId())
                        .messageId(taskMsg.getMessageId())
                        .attemptId(attempt != null ? attempt.getAttemptId() : taskMsg.latestAttemptId())
                        .workerId(latestAttemptWorkerId(taskMsg, attempt))
                        .workerContextId(latestAttemptWorkerContextId(taskMsg, attempt)))
                .outcome(success, taskMsg.getErrorCode(), reason)
                .attrs(attrs(
                        "source", "TaskManager",
                        "reason", reason,
                        "result", result,
                        "taskMsgStatus", enumName(taskMsg.getStatus()),
                        "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt)
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
        try {
            sink.emit(event);
            LOG.debug("Trace event emitted: {}", event.getEventType());
        } catch (RuntimeException e) {
            LOG.warn("Failed to emit trace event {}", event != null ? event.getEventType() : null, e);
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

    private static String latestAttemptWorkerId(TaskMsg taskMsg, TaskMsgAttempt attempt) {
        return attempt != null ? attempt.getWorkerId() : taskMsg.getLatestAttemptWorkerId();
    }

    private static String latestAttemptWorkerContextId(TaskMsg taskMsg, TaskMsgAttempt attempt) {
        return attempt != null ? attempt.getWorkerContextId() : taskMsg.getLatestAttemptWorkerContextId();
    }

    private static String latestAttemptBatchId(TaskMsg taskMsg, TaskMsgAttempt attempt) {
        return attempt != null ? attempt.getBatchId() : taskMsg.getLatestAttemptBatchId();
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
}
