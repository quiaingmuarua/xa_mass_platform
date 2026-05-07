package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIngestStatus;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns task-level lifecycle transitions that are not callback-result specific.
 */
class TaskLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(TaskLifecycleService.class);

    private final TaskLifecycleRuntimePort lifecycleRuntime;
    private final TaskStateResolver stateResolver;
    private final TraceEventLogger traceEventLogger;

    TaskLifecycleService(TaskLifecycleRuntimePort lifecycleRuntime,
                         TaskStateResolver stateResolver,
                         TraceEventLogger traceEventLogger) {
        this.lifecycleRuntime = lifecycleRuntime;
        this.stateResolver = stateResolver;
        this.traceEventLogger = traceEventLogger;
    }

    boolean approveTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("APPROVE_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = lifecycleRuntime.getTask(taskId);
            if (task != null
                    && (task.getStatus() == TaskStatus.NEW || task.getStatus() == TaskStatus.BLOCKED)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    task.setHoldReason(null);
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "APPROVE_TASK", "TaskManager", "task approved");
                    lifecycleRuntime.updateTask(task);
                    lifecycleRuntime.publishTaskReady(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task approved", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_APPROVE_ERROR", "task status transition failed", duration);
                }
                return result;
            }
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_APPROVE_ERROR", "task not found or status is not approvable", duration);
            return false;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_APPROVE_ERROR", e.getMessage(), duration);
            logger.error("Failed to approve task", e);
            return false;
        }
    }

    boolean rejectTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("REJECT_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = lifecycleRuntime.getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.NEW) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionToBlocked(TaskHoldReason.REVIEW_REJECTED);
                if (result) {
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "REJECT_TASK", "TaskManager", "task rejected");
                    lifecycleRuntime.updateTask(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task rejected and moved to BLOCKED", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_REJECT_ERROR", "task status transition failed", duration);
                }
                return result;
            }
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_REJECT_ERROR", "task not found or status is not rejectable", duration);
            return false;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_REJECT_ERROR", e.getMessage(), duration);
            logger.error("Failed to reject task", e);
            return false;
        }
    }

    boolean blockTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("BLOCK_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = lifecycleRuntime.getTask(taskId);
            if (task != null
                    && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionToBlocked(TaskHoldReason.MANUAL_BLOCKED);
                if (result) {
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "BLOCK_TASK", "TaskManager", "task blocked");
                    lifecycleRuntime.updateTask(task);
                    lifecycleRuntime.pauseTaskScheduling(taskId);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task blocked", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_BLOCK_ERROR", "task status transition failed", duration);
                }
                return result;
            }
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_BLOCK_ERROR", "task not found or status is not blockable", duration);
            return false;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_BLOCK_ERROR", e.getMessage(), duration);
            logger.error("Failed to block task", e);
            return false;
        }
    }

    boolean pauseTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("PAUSE_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = lifecycleRuntime.getTask(taskId);
            if (task != null && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.PAUSED);
                if (result) {
                    task.setHoldReason(null);
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "PAUSE_TASK", "TaskManager", "task paused");
                    lifecycleRuntime.updateTask(task);
                    lifecycleRuntime.pauseTaskScheduling(taskId);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task paused", duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_PAUSE_ERROR", "task status transition failed", duration);
                }
                return result;
            }
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_PAUSE_ERROR", "task not found or status is not pausable", duration);
            return false;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_PAUSE_ERROR", e.getMessage(), duration);
            logger.error("Failed to pause task", e);
            return false;
        }
    }

    TaskResumeResult resumeTaskDetailed(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("RESUME_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = lifecycleRuntime.getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.PAUSED) {
                TaskWorkStats stats = lifecycleRuntime.getTaskWorkStats(taskId);
                TaskTerminalPolicyDecision decision = lifecycleRuntime.evaluateTerminalPolicy(task, stats);
                if (decision.getOutcome() == TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL) {
                    task.setTaskSuccessNumber((int) Math.min(stats.successCount(), Integer.MAX_VALUE));
                    TaskTerminalReason terminalReason = decision.getTerminalReason();
                    TaskStatus fromStatus = task.getStatus();
                    boolean result = task.transitionTo(TaskStatus.TERMINAL, terminalReason);
                    if (result) {
                        traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                                "RESUME_TASK", "TaskManager", "task already completed while paused");
                        traceEventLogger.taskTerminalClosed(taskId, fromStatus, terminalReason,
                                "RESUME_TASK", "TaskManager", "task already completed while paused");
                        lifecycleRuntime.updateTask(task);
                        lifecycleRuntime.publishTaskTerminal(task);
                        long duration = System.currentTimeMillis() - startTime;
                        LogUtils.logOperationSuccess("task completed while paused and closed to TERMINAL", duration);
                        return TaskResumeResult.completedToTerminal(terminalReason);
                    }
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task was complete but terminal closure failed", duration);
                    return TaskResumeResult.rejected();
                }
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    task.setHoldReason(null);
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "RESUME_TASK", "TaskManager", "task resumed to ready");
                    lifecycleRuntime.updateTask(task);
                    lifecycleRuntime.resumeTaskScheduling(taskId);
                    lifecycleRuntime.publishTaskReady(task);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task resumed to READY", duration);
                    return TaskResumeResult.resumedToReady();
                }
                long duration = System.currentTimeMillis() - startTime;
                LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task status transition failed", duration);
                return TaskResumeResult.rejected();
            }
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_RESUME_ERROR", "task not found or status is not resumable", duration);
            return TaskResumeResult.rejected();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure("TASK_RESUME_ERROR", e.getMessage(), duration);
            logger.error("Failed to resume task", e);
            return TaskResumeResult.rejected();
        }
    }

    boolean cancelTask(String taskId) {
        return doTerminateTask(taskId, TaskTerminalReason.MANUAL_CANCELLED, "CANCEL_TASK");
    }

    boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return doTerminateTask(taskId, reason, "TERMINATE_TASK");
    }

    int appendTaskItems(String taskId, List<java.util.Map<String, Object>> inputs) {
        Task task = lifecycleRuntime.getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must be a non-empty list");
        }
        if (inputs.size() > TaskManager.MAX_INGEST_BATCH_ITEMS) {
            throw new IllegalArgumentException("append inputs exceed ingest batch limit: "
                    + inputs.size() + " > " + TaskManager.MAX_INGEST_BATCH_ITEMS);
        }
        if (!canAcceptTaskInputs(task)) {
            throw new IllegalStateException(describeInputAppendRejection(task, taskId));
        }

        int added = 0;
        for (java.util.Map<String, Object> input : inputs) {
            String messageId = java.util.UUID.randomUUID().toString();
            TaskMsg taskMsg = new TaskMsg(messageId, taskId, input);
            lifecycleRuntime.addTaskMessage(taskId, taskMsg);
            added++;
        }
        task.setTaskTargetNumber(task.getTaskTargetNumber() + added);
        task.setTaskEligibleNumber(task.getTaskEligibleNumber() + added);
        task.setIngestStatus(resolvePostAppendIngestStatus(task));
        lifecycleRuntime.updateTask(task);
        if (task.getStatus().isActive()) {
            lifecycleRuntime.requestTaskDispatch(task);
        }
        logger.info("[appendTaskItems] Added {} items to open-ended task {}", added, taskId);
        return added;
    }

    boolean sealTask(String taskId) {
        Task task = lifecycleRuntime.getTask(taskId);
        if (task == null) {
            return false;
        }
        if (task.getSourceType() == TaskSourceType.FILE) {
            if (task.getIngestStatus() == TaskIngestStatus.SEALED
                    || task.getIngestStatus() == TaskIngestStatus.FAILED) {
                return false;
            }
            task.setIngestStatus(TaskIngestStatus.SEALED);
        } else if (task.getIntakeStatus() == TaskIntakeStatus.OPEN) {
            task.setIntakeStatus(TaskIntakeStatus.SEALED);
            task.setIngestStatus(TaskIngestStatus.SEALED);
        } else {
            return false;
        }
        lifecycleRuntime.updateTask(task);
        stateResolver.updateTaskProgress(taskId);
        logger.info("[sealTask] Sealed task {}", taskId);
        return true;
    }

    private boolean canAcceptTaskInputs(Task task) {
        if (task.getStatus() == null || task.getStatus().isFinal()) {
            return false;
        }
        if (task.getSourceType() == TaskSourceType.FILE) {
            return task.getIngestStatus() != TaskIngestStatus.SEALED
                    && task.getIngestStatus() != TaskIngestStatus.FAILED;
        }
        return task.getIntakeStatus() == TaskIntakeStatus.OPEN
                && (task.getStatus().isActive() || task.getStatus() == TaskStatus.PAUSED);
    }

    private String describeInputAppendRejection(Task task, String taskId) {
        if (task.getSourceType() == TaskSourceType.FILE) {
            if (task.getIngestStatus() == TaskIngestStatus.SEALED) {
                return "Task ingest already sealed: " + taskId;
            }
            if (task.getIngestStatus() == TaskIngestStatus.FAILED) {
                return "Task ingest failed and cannot accept more inputs: " + taskId;
            }
            return "Task cannot accept file ingest inputs in status " + task.getStatus();
        }
        if (task.getIntakeStatus() != TaskIntakeStatus.OPEN) {
            return "Task is not open-ended: " + taskId;
        }
        return "Task not active: " + task.getStatus();
    }

    private TaskIngestStatus resolvePostAppendIngestStatus(Task task) {
        if (task.getSourceType() == TaskSourceType.FILE) {
            return TaskIngestStatus.READY;
        }
        if (task.getIntakeStatus() == TaskIntakeStatus.OPEN) {
            return TaskIngestStatus.READY;
        }
        return task.getIngestStatus();
    }

    boolean deleteTask(String taskId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("DELETE_TASK", "TaskManager", "taskId", taskId);

        Task task = lifecycleRuntime.getTask(taskId);
        if (task == null) {
            LogUtils.logOperationFailure("TASK_DELETE_ERROR", "task not found", 0);
            return false;
        }
        TaskStatus status = task.getStatus();
        if (status != TaskStatus.NEW && status != TaskStatus.TERMINAL) {
            logger.warn("Refusing to delete non-terminal task: taskId={}, status={}", taskId, status);
            LogUtils.logOperationFailure("TASK_DELETE_REJECTED",
                    "task status " + status + " is not deletable; terminate it first", 0);
            return false;
        }

        boolean result = lifecycleRuntime.deleteTaskRecord(taskId);
        if (result) {
            lifecycleRuntime.discardTaskRuntime(taskId);
            LogUtils.logOperationSuccess("task deleted", 0);
        } else {
            LogUtils.logOperationFailure("TASK_DELETE_ERROR", "task deletion failed", 0);
        }
        return result;
    }

    private boolean doTerminateTask(String taskId, TaskTerminalReason reason, String trigger) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart(trigger, "TaskManager", "taskId", taskId, "reason", reason.name());

        try {
            Task task = lifecycleRuntime.getTask(taskId);
            if (task != null && !task.getStatus().isFinal()) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.TERMINAL, reason);
                if (result) {
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            trigger, "TaskManager", "task terminated: " + reason);
                    traceEventLogger.taskTerminalClosed(taskId, fromStatus, reason,
                            trigger, "TaskManager", "task terminated: " + reason);
                    lifecycleRuntime.updateTask(task);
                    cancelPendingMessages(task);
                    lifecycleRuntime.cancelTaskScheduling(taskId);
                    lifecycleRuntime.publishTaskTerminal(task);
                    lifecycleRuntime.discardTaskRuntime(taskId);
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationSuccess("task terminated: " + reason, duration);
                } else {
                    long duration = System.currentTimeMillis() - startTime;
                    LogUtils.logOperationFailure(trigger + "_ERROR", "task status transition failed", duration);
                }
                return result;
            }
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure(trigger + "_ERROR", "task not found or already terminal", duration);
            return false;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LogUtils.logOperationFailure(trigger + "_ERROR", e.getMessage(), duration);
            logger.error("Failed to terminate task {}", taskId, e);
            return false;
        }
    }

    private void cancelPendingMessages(Task task) {
        String taskId = task.getTid();
        Map<String, ActiveLeaseRecord> activeLeaseByMessageId = activeLeaseByMessageId(taskId);
        for (TaskMsg msg : lifecycleRuntime.getNonFinalTaskMessages(taskId)) {
            cancelPendingMessage(task, msg, activeLeaseByMessageId.get(msg.getMessageId()));
        }
    }

    private void cancelPendingMessage(Task task, TaskMsg msg, ActiveLeaseRecord activeLease) {
        if (msg == null || msg.isCompleted()) {
            return;
        }

        TaskMsgStatus status = msg.getStatus();
        TaskMsgAttempt activeAttempt = null;
        boolean attemptClosed = false;
        boolean runtimeOwnedMessage = activeLease != null
                || status == TaskMsgStatus.ASSIGNED
                || status == TaskMsgStatus.RUNNING;
        if (activeLease != null) {
            activeAttempt = RuntimeLeaseProjectionSupport.resolveOrRecoverActiveAttempt(
                    lifecycleRuntime,
                    msg,
                    activeLease
            );
            if (!RuntimeLeaseProjectionSupport.synchronizeProjectionFromRuntimeLease(
                    lifecycleRuntime,
                    task.getTid(),
                    msg,
                    activeAttempt,
                    activeLease,
                    traceEventLogger,
                    "CANCEL_PENDING_MESSAGES",
                    "runtime active lease synchronized compatibility projection before terminal cleanup")) {
                logger.warn("Failed to synchronize compatibility projection from runtime lease during terminal cleanup: taskId={}, messageId={}",
                        task.getTid(), msg.getMessageId());
            }
            status = msg.getStatus();
        } else if (runtimeOwnedMessage) {
            activeAttempt = lifecycleRuntime.getLatestActiveTaskMessageAttempt(task.getTid(), msg.getMessageId());
        }
        if (activeAttempt != null) {
            if (TaskMessageAttemptSupport.projectExpired(
                    activeAttempt,
                    TaskMsgAttemptFinalReason.MANUAL_CANCELLED,
                    "task cancelled")) {
                persistAttemptProjectionBestEffort(task.getTid(), msg.getMessageId(), activeAttempt);
                attemptClosed = true;
            }
        }

        boolean updated = false;
        if (activeLease == null && status == TaskMsgStatus.INIT) {
            updated = msg.cancelBeforeDispatch("task cancelled");
            if (!updated) {
                return;
            }
            traceEventLogger.taskMsgStatusTransition(
                    msg,
                    null,
                    TaskMsgStatus.INIT,
                    msg.getStatus(),
                    "CANCEL_PENDING_MESSAGES",
                    "TaskManager",
                    "task cancelled before dispatch"
            );
        } else if (runtimeOwnedMessage) {
            updated = msg.markAsExpired(TaskMsgFinalReason.MANUAL_CANCELLED);
            if (updated) {
                traceEventLogger.taskMsgStatusTransition(
                        msg,
                        activeAttempt,
                        status,
                        msg.getStatus(),
                        "CANCEL_PENDING_MESSAGES",
                        "TaskManager",
                        "task cancelled after assignment"
                );
            }
        } else {
            msg.forceFinalize(TaskMsgStatus.FAILED, TaskMsgFinalReason.MANUAL_CANCELLED, "task cancelled");
            updated = true;
            traceEventLogger.taskMsgStatusTransition(
                    msg,
                    null,
                    status,
                    msg.getStatus(),
                    "CANCEL_PENDING_MESSAGES",
                    "TaskManager",
                    "task cancelled without active runtime lease"
            );
        }
        if (!updated) {
            return;
        }

        lifecycleRuntime.updateTaskMessage(task.getTid(), msg);
        if (attemptClosed && activeAttempt != null) {
            traceEventLogger.taskMessageAttemptClosed(
                    task,
                    msg,
                    activeAttempt,
                    "CANCEL_PENDING_MESSAGES",
                    "TaskManager",
                    "task termination closed the current attempt"
            );
            lifecycleRuntime.publishTaskMessageAttemptClosed(task, msg, activeAttempt);
        }
        traceEventLogger.taskMessageLogicallyFinal(
                task,
                msg,
                activeAttempt,
                "CANCEL_PENDING_MESSAGES",
                "TaskManager",
                "task termination finalized the logical message"
        );
        lifecycleRuntime.publishTaskMessageLogicallyFinal(task, msg);
    }

    private void persistAttemptProjectionBestEffort(String taskId,
                                                    String messageId,
                                                    TaskMsgAttempt attempt) {
        try {
            if (!lifecycleRuntime.updateTaskMessageAttempt(taskId, messageId, attempt)) {
                logger.warn("Failed to update compatibility attempt projection during terminal cleanup: taskId={}, messageId={}, attemptId={}",
                        taskId, messageId, attempt != null ? attempt.getAttemptId() : null);
            }
        } catch (RuntimeException e) {
            logger.warn("Failed to update compatibility attempt projection during terminal cleanup: taskId={}, messageId={}, attemptId={}",
                    taskId, messageId, attempt != null ? attempt.getAttemptId() : null, e);
        }
    }

    private Map<String, ActiveLeaseRecord> activeLeaseByMessageId(String taskId) {
        Map<String, ActiveLeaseRecord> activeLeaseByMessageId = new HashMap<>();
        for (ActiveLeaseRecord lease : lifecycleRuntime.getActiveLeases(taskId)) {
            if (lease == null || lease.messageId() == null || lease.messageId().isBlank()) {
                continue;
            }
            activeLeaseByMessageId.put(lease.messageId(), lease);
        }
        return activeLeaseByMessageId;
    }
}
