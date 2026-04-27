package com.xa.mass.engine.util;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.*;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.TaskRuntimeProfileResolver;
import com.xa.mass.engine.work.TaskWorkStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits structured transition traces through MDC-backed logs.
 * Keep the public event names and field names stable because tests and docs rely on them.
 */
public final class TraceEventLogger {

    private static final Logger TRACE_LOGGER = LoggerFactory.getLogger(TraceEventLogger.class);
    private static final TaskRuntimeProfileResolver TASK_RUNTIME_PROFILE_RESOLVER = new TaskRuntimeProfileResolver();

    private TraceEventLogger() {
    }

    public static void taskStatusTransition(String taskId,
                                            TaskStatus fromStatus,
                                            TaskStatus toStatus,
                                            String trigger,
                                            String source,
                                            String reason) {
        emit("TASK_STATUS_TRANSITION", fields(
                "entityType", "Task",
                "entityId", taskId,
                "taskId", taskId,
                "fromStatus", enumName(fromStatus),
                "toStatus", enumName(toStatus),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void taskTerminalClosed(String taskId,
                                          TaskStatus fromStatus,
                                          TaskTerminalReason terminalReason,
                                          String trigger,
                                          String source,
                                          String reason) {
        emit("TASK_TERMINAL_CLOSED", fields(
                "entityType", "Task",
                "entityId", taskId,
                "taskId", taskId,
                "fromStatus", enumName(fromStatus),
                "toStatus", TaskStatus.TERMINAL.name(),
                "terminalReason", enumName(terminalReason),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void taskMsgStatusTransition(TaskMsg taskMsg,
                                               TaskMsgStatus fromStatus,
                                               TaskMsgStatus toStatus,
                                               String trigger,
                                               String source,
                                               String reason) {
        taskMsgStatusTransition(taskMsg, null, fromStatus, toStatus, trigger, source, reason);
    }

    public static void taskMsgStatusTransition(TaskMsg taskMsg,
                                               TaskMsgAttempt attempt,
                                               TaskMsgStatus fromStatus,
                                               TaskMsgStatus toStatus,
                                               String trigger,
                                               String source,
                                               String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("TASK_MSG_STATUS_TRANSITION", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMessageId(),
                "taskId", taskMsg.getTaskId(),
                "messageId", taskMsg.getMessageId(),
                "latestAttemptWorkerId", latestAttemptWorkerId(taskMsg, attempt),
                "latestAttemptWorkerContextId", latestAttemptWorkerContextId(taskMsg, attempt),
                "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt),
                "fromStatus", enumName(fromStatus),
                "toStatus", enumName(toStatus),
                "finalReason", enumName(taskMsg.getFinalReason()),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void taskMsgAttemptStatusTransition(TaskMsgAttempt attempt,
                                                      TaskMsgAttemptStatus fromStatus,
                                                      TaskMsgAttemptStatus toStatus,
                                                      String trigger,
                                                      String source,
                                                      String reason) {
        if (attempt == null) {
            return;
        }
        emit("TASK_MSG_ATTEMPT_STATUS_TRANSITION", fields(
                "entityType", "TaskMsgAttempt",
                "entityId", attempt.getAttemptId(),
                "taskId", attempt.getTaskId(),
                "messageId", attempt.getMessageId(),
                "attemptId", attempt.getAttemptId(),
                "attemptNo", String.valueOf(attempt.getAttemptNo()),
                "workerId", attempt.getWorkerId(),
                "workerContextId", attempt.getWorkerContextId(),
                "batchId", attempt.getBatchId(),
                "fromStatus", enumName(fromStatus),
                "toStatus", enumName(toStatus),
                "finalReason", enumName(attempt.getFinalReason()),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void taskMsgRetryReset(TaskMsg taskMsg, String trigger, String source, String reason) {
        taskMsgRetryReset(taskMsg, null, null, trigger, source, reason);
    }

    public static void taskMsgRetryReset(TaskMsg taskMsg,
                                         TaskMsgAttempt attempt,
                                         Long workRetryDelayMillis,
                                         String trigger,
                                         String source,
                                         String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("TASK_MSG_RETRY_RESET", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMessageId(),
                "taskId", taskMsg.getTaskId(),
                "messageId", taskMsg.getMessageId(),
                "latestAttemptWorkerId", latestAttemptWorkerId(taskMsg, attempt),
                "latestAttemptWorkerContextId", latestAttemptWorkerContextId(taskMsg, attempt),
                "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt),
                "fromStatus", "FAILED_OR_EXPIRED",
                "toStatus", TaskMsgStatus.INIT.name(),
                "retryCount", String.valueOf(taskMsg.getRetryCount()),
                "workRetryDelayMillis", stringValue(workRetryDelayMillis),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void workerContextStatusTransition(String taskId,
                                                     WorkerContext workerContext,
                                                     WorkerContextStatus fromStatus,
                                                     WorkerContextStatus toStatus,
                                                     String trigger,
                                                     String source,
                                                     String reason) {
        if (workerContext == null) {
            return;
        }
        emit("WORKER_CONTEXT_STATUS_TRANSITION", fields(
                "entityType", "WorkerContext",
                "entityId", workerContext.getWorkerContextId(),
                "taskId", taskId,
                "workerId", workerContext.getWorkerId(),
                "workerContextId", workerContext.getWorkerContextId(),
                "fromStatus", enumName(fromStatus),
                "toStatus", enumName(toStatus),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void workerLockAcquired(String taskId, String workerId, String trigger, String source, String reason) {
        emit("WORKER_LOCK_ACQUIRED", fields(
                "entityType", "Worker",
                "entityId", workerId,
                "taskId", taskId,
                "workerId", workerId,
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void workerLockReleased(String taskId, String workerId, String trigger, String source, String reason) {
        emit("WORKER_LOCK_RELEASED", fields(
                "entityType", "Worker",
                "entityId", workerId,
                "taskId", taskId,
                "workerId", workerId,
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void workerMatchAccepted(String taskId, Worker worker, WorkerContext workerContext, String reason) {
        if (worker == null) {
            return;
        }
        emit("WORKER_MATCH_ACCEPTED", fields(
                "entityType", "Worker",
                "entityId", worker.getWorkerId(),
                "taskId", taskId,
                "workerId", worker.getWorkerId(),
                "workerContextId", workerContext != null ? workerContext.getWorkerContextId() : null,
                "source", "RuleBasedTaskWorkerMatchingStrategy",
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void workerMatchRejected(String taskId, Worker worker, WorkerContext workerContext, String reason) {
        if (worker == null) {
            return;
        }
        emit("WORKER_MATCH_REJECTED", fields(
                "entityType", "Worker",
                "entityId", worker.getWorkerId(),
                "taskId", taskId,
                "workerId", worker.getWorkerId(),
                "workerContextId", workerContext != null ? workerContext.getWorkerContextId() : null,
                "source", "RuleBasedTaskWorkerMatchingStrategy",
                "reason", reason,
                "result", "REJECTED"
        ));
    }

    public static void dispatchRequested(String taskId,
                                         String trigger,
                                         String source,
                                         String reason) {
        emit("DISPATCH_REQUESTED", fields(
                "entityType", "Task",
                "entityId", taskId,
                "taskId", taskId,
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void dispatchSkipped(String taskId,
                                       String trigger,
                                       String source,
                                       String reason,
                                       Integer requiredMinWorkerCount) {
        emit("DISPATCH_SKIPPED", fields(
                "entityType", "Task",
                "entityId", taskId,
                "taskId", taskId,
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "requiredMinWorkerCount",
                requiredMinWorkerCount != null ? String.valueOf(requiredMinWorkerCount) : null,
                "result", "SKIPPED"
        ));
    }

    public static void dispatchSkipped(Task task,
                                       String trigger,
                                       String source,
                                       String reason,
                                       Integer requiredMinWorkerCount) {
        if (task == null) {
            return;
        }
        Map<String, String> traceFields = fields(
                "entityType", "Task",
                "entityId", task.getTid(),
                "taskId", task.getTid(),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "requiredMinWorkerCount",
                requiredMinWorkerCount != null ? String.valueOf(requiredMinWorkerCount) : null,
                "result", "SKIPPED"
        );
        putTaskRuntimeProfile(traceFields, task);
        emit("DISPATCH_SKIPPED", traceFields);
    }

    public static void assignmentRetryScheduled(String taskId,
                                                TaskStatus currentStatus,
                                                String trigger,
                                                String source,
                                                String reason,
                                                long retryDelayMillis) {
        emit("ASSIGNMENT_RETRY_SCHEDULED", fields(
                "entityType", "Task",
                "entityId", taskId,
                "taskId", taskId,
                "currentStatus", enumName(currentStatus),
                "retryDelayMillis", String.valueOf(retryDelayMillis),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SCHEDULED"
        ));
    }

    public static void callbackAccepted(TaskMsg taskMsg, String reason) {
        callbackAccepted(taskMsg, null, reason);
    }

    public static void callbackAccepted(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("CALLBACK_ACCEPTED", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMessageId(),
                "taskId", taskMsg.getTaskId(),
                "messageId", taskMsg.getMessageId(),
                "latestAttemptWorkerId", latestAttemptWorkerId(taskMsg, attempt),
                "latestAttemptWorkerContextId", latestAttemptWorkerContextId(taskMsg, attempt),
                "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt),
                "source", "TaskManager",
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void dispatchRequested(Task task,
                                         String trigger,
                                         String source,
                                         String reason) {
        if (task == null) {
            return;
        }
        Map<String, String> traceFields = fields(
                "entityType", "Task",
                "entityId", task.getTid(),
                "taskId", task.getTid(),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        );
        putTaskRuntimeProfile(traceFields, task);
        emit("DISPATCH_REQUESTED", traceFields);
    }

    public static void callbackIgnoredDuplicate(TaskMsg taskMsg, String reason) {
        callbackIgnoredDuplicate(taskMsg, null, reason);
    }

    public static void callbackIgnoredDuplicate(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("CALLBACK_IGNORED_DUPLICATE", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMessageId(),
                "taskId", taskMsg.getTaskId(),
                "messageId", taskMsg.getMessageId(),
                "latestAttemptWorkerId", latestAttemptWorkerId(taskMsg, attempt),
                "latestAttemptWorkerContextId", latestAttemptWorkerContextId(taskMsg, attempt),
                "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt),
                "source", "TaskManager",
                "reason", reason,
                "result", "IGNORED"
        ));
    }

    public static void callbackIgnoredLate(TaskMsg taskMsg, String reason) {
        callbackIgnoredLate(taskMsg, null, reason);
    }

    public static void callbackIgnoredLate(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("CALLBACK_IGNORED_LATE", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMessageId(),
                "taskId", taskMsg.getTaskId(),
                "messageId", taskMsg.getMessageId(),
                "latestAttemptWorkerId", latestAttemptWorkerId(taskMsg, attempt),
                "latestAttemptWorkerContextId", latestAttemptWorkerContextId(taskMsg, attempt),
                "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt),
                "source", "TaskManager",
                "reason", reason,
                "result", "IGNORED"
        ));
    }

    public static void callbackRejectedNoActiveAttempt(String taskId,
                                                       String messageId,
                                                       TaskMsgStatus taskMsgStatus,
                                                       String reason) {
        emit("CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT", fields(
                "entityType", "TaskMsg",
                "entityId", messageId,
                "taskId", taskId,
                "messageId", messageId,
                "taskMsgStatus", enumName(taskMsgStatus),
                "source", "TaskManager",
                "reason", reason,
                "result", "REJECTED"
        ));
    }

    public static void callbackRejectedNoActiveLease(TaskMsg taskMsg, String reason) {
        callbackRejectedNoActiveLease(taskMsg, null, reason);
    }

    public static void callbackRejectedNoActiveLease(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("CALLBACK_REJECTED_NO_ACTIVE_LEASE", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMessageId(),
                "taskId", taskMsg.getTaskId(),
                "messageId", taskMsg.getMessageId(),
                "taskMsgStatus", enumName(taskMsg.getStatus()),
                "latestAttemptWorkerId", latestAttemptWorkerId(taskMsg, attempt),
                "latestAttemptWorkerContextId", latestAttemptWorkerContextId(taskMsg, attempt),
                "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt),
                "source", "TaskManager",
                "reason", reason,
                "result", "REJECTED"
        ));
    }

    public static void callbackRejectedInvalidState(TaskMsg taskMsg, String reason) {
        callbackRejectedInvalidState(taskMsg, null, reason);
    }

    public static void callbackRejectedInvalidState(TaskMsg taskMsg, TaskMsgAttempt attempt, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("CALLBACK_REJECTED_INVALID_STATE", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMessageId(),
                "taskId", taskMsg.getTaskId(),
                "messageId", taskMsg.getMessageId(),
                "taskMsgStatus", enumName(taskMsg.getStatus()),
                "latestAttemptWorkerId", latestAttemptWorkerId(taskMsg, attempt),
                "latestAttemptWorkerContextId", latestAttemptWorkerContextId(taskMsg, attempt),
                "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt),
                "source", "TaskManager",
                "reason", reason,
                "result", "REJECTED"
        ));
    }

    public static void taskMessageAttemptClosed(Task task,
                                                TaskMsg taskMsg,
                                                TaskMsgAttempt attempt,
                                                String trigger,
                                                String source,
                                                String reason) {
        if (task == null || taskMsg == null || attempt == null) {
            return;
        }
        emit("TASK_MSG_ATTEMPT_CLOSED", fields(
                "entityType", "TaskMsgAttempt",
                "entityId", attempt.getAttemptId(),
                "taskId", task.getTid(),
                "messageId", taskMsg.getMessageId(),
                "attemptId", attempt.getAttemptId(),
                "attemptNo", String.valueOf(attempt.getAttemptNo()),
                "workerId", attempt.getWorkerId(),
                "workerContextId", attempt.getWorkerContextId(),
                "batchId", attempt.getBatchId(),
                "attemptStatus", enumName(attempt.getStatus()),
                "attemptFinalReason", enumName(attempt.getFinalReason()),
                "taskMsgStatus", enumName(taskMsg.getStatus()),
                "taskMsgFinalReason", enumName(taskMsg.getFinalReason()),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void taskMessageLogicallyFinal(Task task,
                                                 TaskMsg taskMsg,
                                                 String trigger,
                                                 String source,
                                                 String reason) {
        taskMessageLogicallyFinal(task, taskMsg, null, trigger, source, reason);
    }

    public static void taskMessageLogicallyFinal(Task task,
                                                 TaskMsg taskMsg,
                                                 TaskMsgAttempt attempt,
                                                 String trigger,
                                                 String source,
                                                 String reason) {
        if (task == null || taskMsg == null) {
            return;
        }
        emit("TASK_MSG_LOGICALLY_FINAL", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMessageId(),
                "taskId", task.getTid(),
                "messageId", taskMsg.getMessageId(),
                "latestAttemptWorkerId", latestAttemptWorkerId(taskMsg, attempt),
                "latestAttemptWorkerContextId", latestAttemptWorkerContextId(taskMsg, attempt),
                "latestAttemptBatchId", latestAttemptBatchId(taskMsg, attempt),
                "taskMsgStatus", enumName(taskMsg.getStatus()),
                "taskMsgFinalReason", enumName(taskMsg.getFinalReason()),
                "retryCount", String.valueOf(taskMsg.getRetryCount()),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void resourceReleased(String taskId, String workerId, String workerContextId, String reason) {
        emit("RESOURCE_RELEASED", fields(
                "entityType", workerContextId != null ? "WorkerContext" : "Worker",
                "entityId", workerContextId != null ? workerContextId : workerId,
                "taskId", taskId,
                "workerId", workerId,
                "workerContextId", workerContextId,
                "source", "TaskResourceReleaseListener",
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void resourceReleaseFailed(String taskId, String workerId, String workerContextId, String reason) {
        emit("RESOURCE_RELEASE_FAILED", fields(
                "entityType", workerContextId != null ? "WorkerContext" : "Worker",
                "entityId", workerContextId != null ? workerContextId : workerId,
                "taskId", taskId,
                "workerId", workerId,
                "workerContextId", workerContextId,
                "source", "TaskResourceReleaseListener",
                "reason", reason,
                "result", "FAILED"
        ));
    }

    public static void taskProgressSnapshot(Task task,
                                            TaskWorkStats stats,
                                            String resolutionOutcome,
                                            boolean needsTerminalClosure,
                                            String trigger,
                                            String source,
                                            String reason) {
        if (task == null || stats == null) {
            return;
        }
        Map<String, String> traceFields = fields(
                "entityType", "Task",
                "entityId", task.getTid(),
                "taskId", task.getTid(),
                "taskStatus", enumName(task.getStatus()),
                "terminalReason", enumName(task.getTerminalReason()),
                "taskTargetNumber", String.valueOf(task.getTaskTargetNumber()),
                "taskEligibleNumber", String.valueOf(task.getTaskEligibleNumber()),
                "taskSuccessNumber", String.valueOf(task.getTaskSuccessNumber()),
                "taskNonSuccessNumber", String.valueOf(task.getTaskNonSuccessNumber()),
                "peakAssignedWorkerCount", String.valueOf(task.getPeakAssignedWorkerCount()),
                "minRequiredWorkerCount", String.valueOf(task.getMinRequiredWorkerCount()),
                "batchSize", String.valueOf(task.getBatchSize()),
                "intakeStatus", enumName(task.getIntakeStatus()),
                "holdReason", enumName(task.getHoldReason()),
                "schedulable", String.valueOf(task.isSchedulable()),
                "progressPercent", formatDouble(task.getProgressPercentage()),
                "totalMessages", String.valueOf(stats.totalCount()),
                "successMessages", String.valueOf(stats.successCount()),
                "failedMessages", String.valueOf(stats.failedCount()),
                "expiredMessages", String.valueOf(stats.expiredCount()),
                "processingMessages", String.valueOf(stats.processingCount()),
                "finalMessages", String.valueOf(stats.finalCount()),
                "pendingMessages", String.valueOf(stats.pendingCount()),
                "successRate", formatDouble(stats.successRate()),
                "failureRate", formatDouble(stats.failureRate()),
                "resolutionOutcome", resolutionOutcome,
                "needsTerminalClosure", String.valueOf(needsTerminalClosure),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        );
        putTaskRuntimeProfile(traceFields, task);
        emit("TASK_PROGRESS_SNAPSHOT", traceFields);
    }

    public static void assignmentSummary(Task task,
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
        Map<String, String> traceFields = fields(
                "entityType", "Task",
                "entityId", task.getTid(),
                "taskId", task.getTid(),
                "initialStatus", enumName(initialStatus),
                "currentStatus", enumName(currentStatus),
                "pendingDispatchCount", stringValue(pendingDispatchCount),
                "desiredDispatchWorkerCount", stringValue(desiredDispatchWorkerCount),
                "requiredStartWorkerCount", stringValue(requiredStartWorkerCount),
                "requestedMatchCount", stringValue(requestedMatchCount),
                "matchedWorkerCount", stringValue(matchedWorkerCount),
                "dispatchCandidateCount", stringValue(dispatchCandidateCount),
                "dispatchedMessageCount", stringValue(dispatchedMessageCount),
                "usedWorkerCount", stringValue(usedWorkerCount),
                "peakAssignedWorkerCount", stringValue(peakAssignedWorkerCount),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", result
        );
        putTaskRuntimeProfile(traceFields, task);
        emit("ASSIGNMENT_SUMMARY", traceFields);
    }

    public static void dispatchBindingSummary(Task task,
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
        Map<String, String> traceFields = fields(
                "entityType", "Task",
                "entityId", task.getTid(),
                "taskId", task.getTid(),
                "pendingMessageCount", String.valueOf(pendingMessageCount),
                "matchedWorkerCount", String.valueOf(matchedWorkerCount),
                "dispatchSlotCount", String.valueOf(dispatchSlotCount),
                "dispatchedMessageCount", String.valueOf(dispatchedMessageCount),
                "unassignedMessageCount", String.valueOf(Math.max(pendingMessageCount - dispatchedMessageCount, 0)),
                "uniqueWorkerCount", String.valueOf(uniqueWorkerCount),
                "uniqueWorkerContextCount", String.valueOf(uniqueWorkerContextCount),
                "perWorkerBatchLimit", String.valueOf(perWorkerBatchLimit),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", result
        );
        putTaskRuntimeProfile(traceFields, task);
        emit("DISPATCH_BINDING_SUMMARY", traceFields);
    }

    public static void taskStateValidationSummary(String taskId,
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
        long finalMessages = successMessages + failedMessages;
        emit("TASK_STATE_VALIDATION_SUMMARY", fields(
                "entityType", "Task",
                "entityId", taskId,
                "taskId", taskId,
                "taskStatus", enumName(taskStatus),
                "terminalReason", enumName(terminalReason),
                "totalMessages", String.valueOf(totalMessages),
                "successMessages", String.valueOf(successMessages),
                "failedMessages", String.valueOf(failedMessages),
                "processingMessages", String.valueOf(processingMessages),
                "pendingMessages", String.valueOf(Math.max(totalMessages - finalMessages, 0)),
                "valid", String.valueOf(valid),
                "needsResolution", String.valueOf(needsResolution),
                "violationCount", String.valueOf(violationCount),
                "violations", violations,
                "trigger", trigger,
                "source", source,
                "validationScope", validationScope,
                "reason", reason,
                "result", result
        ));
    }

    public static void assignmentQueueSnapshot(Task task,
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
        Map<String, String> traceFields = fields(
                "entityType", "AssignmentQueue",
                "entityId", task != null && task.getTid() != null && !task.getTid().isBlank() ? task.getTid() : "task-assign-queue",
                "taskId", task != null ? task.getTid() : null,
                "taskStatus", enumName(taskStatus),
                "dispatchLane", dispatchLane,
                "queueDepth", String.valueOf(queueDepth),
                "trackedBatchPendingCount", String.valueOf(trackedBatchPendingCount),
                "scheduledRetryCount", String.valueOf(scheduledRetryCount),
                "queueAction", queueAction,
                "retryDelayMillis", stringValue(retryDelayMillis),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", result
        );
        putTaskRuntimeProfile(traceFields, task);
        emit("ASSIGNMENT_QUEUE_SNAPSHOT", traceFields);
    }

    private static void emit(String event, Map<String, String> fields) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("event", event);
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    MDC.put(entry.getKey(), entry.getValue());
                }
            }
            TRACE_LOGGER.info(event);
        } finally {
            MDC.clear();
            if (previous != null) {
                previous.forEach(MDC::put);
            }
        }
    }

    private static Map<String, String> fields(String... entries) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            values.put(entries[i], entries[i + 1]);
        }
        return values;
    }

    private static String enumName(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    private static String stringValue(Integer value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static String stringValue(Long value) {
        return value != null ? String.valueOf(value) : null;
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

    private static void putTaskRuntimeProfile(Map<String, String> fields, Task task) {
        if (task == null) {
            return;
        }
        TaskRuntimeProfile profile = TASK_RUNTIME_PROFILE_RESOLVER.resolve(task);
        fields.put("workloadClass", enumName(task.getWorkloadClass()));
        fields.put("dispatchLane", enumName(profile.dispatchLane()));
        fields.put("dispatchPriority", enumName(profile.dispatchPriority()));
        fields.put("batchPolicy", enumName(profile.batchPolicy()));
        fields.put("leaseProfile", enumName(profile.leaseProfile()));
        fields.put("backpressureClass", enumName(profile.backpressureClass()));
    }
}
