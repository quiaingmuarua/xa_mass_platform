package com.xa.mass.engine.util;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
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
        if (taskMsg == null) {
            return;
        }
        emit("TASK_MSG_STATUS_TRANSITION", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMsgId(),
                "taskId", taskMsg.getTaskId(),
                "msgId", taskMsg.getMsgId(),
                "workerId", taskMsg.getWorkerId(),
                "workerContextId", taskMsg.getWorkerContextId(),
                "batchId", taskMsg.getBatchId(),
                "fromStatus", enumName(fromStatus),
                "toStatus", enumName(toStatus),
                "trigger", trigger,
                "source", source,
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void taskMsgRetryReset(TaskMsg taskMsg, String trigger, String source, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("TASK_MSG_RETRY_RESET", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMsgId(),
                "taskId", taskMsg.getTaskId(),
                "msgId", taskMsg.getMsgId(),
                "workerId", taskMsg.getWorkerId(),
                "workerContextId", taskMsg.getWorkerContextId(),
                "batchId", taskMsg.getBatchId(),
                "fromStatus", "FAILED_OR_EXPIRED",
                "toStatus", TaskMsgStatus.INIT.name(),
                "retryCount", String.valueOf(taskMsg.getRetryCount()),
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
        if (taskMsg == null) {
            return;
        }
        emit("CALLBACK_ACCEPTED", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMsgId(),
                "taskId", taskMsg.getTaskId(),
                "msgId", taskMsg.getMsgId(),
                "workerId", taskMsg.getWorkerId(),
                "workerContextId", taskMsg.getWorkerContextId(),
                "source", "TaskManager",
                "reason", reason,
                "result", "SUCCESS"
        ));
    }

    public static void callbackIgnoredDuplicate(TaskMsg taskMsg, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("CALLBACK_IGNORED_DUPLICATE", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMsgId(),
                "taskId", taskMsg.getTaskId(),
                "msgId", taskMsg.getMsgId(),
                "workerId", taskMsg.getWorkerId(),
                "workerContextId", taskMsg.getWorkerContextId(),
                "source", "TaskManager",
                "reason", reason,
                "result", "IGNORED"
        ));
    }

    public static void callbackIgnoredLate(TaskMsg taskMsg, String reason) {
        if (taskMsg == null) {
            return;
        }
        emit("CALLBACK_IGNORED_LATE", fields(
                "entityType", "TaskMsg",
                "entityId", taskMsg.getMsgId(),
                "taskId", taskMsg.getTaskId(),
                "msgId", taskMsg.getMsgId(),
                "workerId", taskMsg.getWorkerId(),
                "workerContextId", taskMsg.getWorkerContextId(),
                "source", "TaskManager",
                "reason", reason,
                "result", "IGNORED"
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
}
