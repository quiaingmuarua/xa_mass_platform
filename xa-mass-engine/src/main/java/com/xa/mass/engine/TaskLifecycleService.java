package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
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
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Owns task-level lifecycle transitions that are not callback-result specific.
 */
class TaskLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(TaskLifecycleService.class);

    private final TaskManager taskManager;
    private final TaskStateResolver stateResolver;

    TaskLifecycleService(TaskManager taskManager, TaskStateResolver stateResolver) {
        this.taskManager = taskManager;
        this.stateResolver = stateResolver;
    }

    boolean approveTask(String taskId) {
        long startTime = System.currentTimeMillis();
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("APPROVE_TASK", "TaskManager", "taskId", taskId);

        try {
            Task task = taskManager.getTask(taskId);
            if (task != null
                    && (task.getStatus() == TaskStatus.NEW || task.getStatus() == TaskStatus.BLOCKED)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.READY);
                if (result) {
                    task.setHoldReason(null);
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "APPROVE_TASK", "TaskManager", "task approved");
                    taskManager.updateTask(task);
                    taskManager.getEventPublisher().publishTaskReady(task);
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
            Task task = taskManager.getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.NEW) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionToBlocked(TaskHoldReason.REVIEW_REJECTED);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "REJECT_TASK", "TaskManager", "task rejected");
                    taskManager.updateTask(task);
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
            Task task = taskManager.getTask(taskId);
            if (task != null
                    && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionToBlocked(TaskHoldReason.MANUAL_BLOCKED);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "BLOCK_TASK", "TaskManager", "task blocked");
                    taskManager.updateTask(task);
                    taskManager.getScheduler().pauseTask(taskId);
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
            Task task = taskManager.getTask(taskId);
            if (task != null && (task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.RUNNING)) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.PAUSED);
                if (result) {
                    task.setHoldReason(null);
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "PAUSE_TASK", "TaskManager", "task paused");
                    taskManager.updateTask(task);
                    taskManager.getScheduler().pauseTask(taskId);
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
            Task task = taskManager.getTask(taskId);
            if (task != null && task.getStatus() == TaskStatus.PAUSED) {
                TaskStorage.TaskMessageStats stats = taskManager.getTaskMessageStats(taskId);
                TaskTerminalPolicyDecision decision = taskManager.getTaskTerminalPolicy().evaluate(task, stats);
                if (decision.getOutcome() == TaskTerminalPolicyDecision.Outcome.FINALIZE_TO_TERMINAL) {
                    task.setTaskSuccessNumber((int) stats.getSuccess());
                    TaskTerminalReason terminalReason = decision.getTerminalReason();
                    TaskStatus fromStatus = task.getStatus();
                    boolean result = task.transitionTo(TaskStatus.TERMINAL, terminalReason);
                    if (result) {
                        TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                                "RESUME_TASK", "TaskManager", "task already completed while paused");
                        TraceEventLogger.taskTerminalClosed(taskId, fromStatus, terminalReason,
                                "RESUME_TASK", "TaskManager", "task already completed while paused");
                        taskManager.updateTask(task);
                        taskManager.getEventPublisher().publishTaskTerminal(task);
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
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "RESUME_TASK", "TaskManager", "task resumed to ready");
                    taskManager.updateTask(task);
                    taskManager.getScheduler().resumeTask(taskId);
                    taskManager.getEventPublisher().publishTaskReady(task);
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
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (task.getIntakeStatus() != TaskIntakeStatus.OPEN) {
            throw new IllegalStateException("Task is not open-ended: " + taskId);
        }
        if (!task.getStatus().isActive() && task.getStatus() != TaskStatus.PAUSED) {
            throw new IllegalStateException("Task not active: " + task.getStatus());
        }

        int added = 0;
        for (java.util.Map<String, Object> input : inputs) {
            String messageId = java.util.UUID.randomUUID().toString();
            TaskMsg taskMsg = new TaskMsg(messageId, taskId, input);
            taskManager.addTaskMessage(taskId, taskMsg);
            added++;
        }
        task.setTaskTargetNumber(task.getTaskTargetNumber() + added);
        task.setTaskEligibleNumber(task.getTaskEligibleNumber() + added);
        taskManager.updateTask(task);
        if (task.getStatus().isActive()) {
            taskManager.getEventPublisher().publishTaskDispatchRequested(task);
        }
        logger.info("[appendTaskItems] Added {} items to open-ended task {}", added, taskId);
        return added;
    }

    boolean sealTask(String taskId) {
        Task task = taskManager.getTask(taskId);
        if (task == null || task.getIntakeStatus() != TaskIntakeStatus.OPEN) {
            return false;
        }
        task.setIntakeStatus(TaskIntakeStatus.SEALED);
        taskManager.updateTask(task);
        stateResolver.updateTaskProgress(taskId);
        logger.info("[sealTask] Sealed task {}", taskId);
        return true;
    }

    boolean deleteTask(String taskId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("DELETE_TASK", "TaskManager", "taskId", taskId);

        Task task = taskManager.getTask(taskId);
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

        boolean result = taskManager.getTaskStorage().deleteTask(taskId);
        if (result) {
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
            Task task = taskManager.getTask(taskId);
            if (task != null && !task.getStatus().isFinal()) {
                TaskStatus fromStatus = task.getStatus();
                boolean result = task.transitionTo(TaskStatus.TERMINAL, reason);
                if (result) {
                    TraceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            trigger, "TaskManager", "task terminated: " + reason);
                    TraceEventLogger.taskTerminalClosed(taskId, fromStatus, reason,
                            trigger, "TaskManager", "task terminated: " + reason);
                    taskManager.updateTask(task);
                    cancelPendingMessages(taskId);
                    taskManager.getScheduler().cancelTask(taskId);
                    taskManager.getEventPublisher().publishTaskTerminal(task);
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

    private void cancelPendingMessages(String taskId) {
        List<TaskMsg> messages = taskManager.getTaskMessages(taskId);
        for (TaskMsg msg : messages) {
            if (msg.isCompleted()) {
                continue;
            }

            TaskMsgAttempt activeAttempt = taskManager.getLatestActiveTaskMessageAttempt(taskId, msg.getMessageId());
            boolean attemptClosed = false;
            if (activeAttempt != null) {
                if (TaskMessageAttemptSupport.expireAttempt(activeAttempt, TaskMsgAttemptFinalReason.MANUAL_CANCELLED, "task cancelled")) {
                    taskManager.updateTaskMessageAttempt(taskId, msg.getMessageId(), activeAttempt);
                    attemptClosed = true;
                }
            }

            TaskMsgStatus status = msg.getStatus();
            boolean updated = false;
            if (status == TaskMsgStatus.INIT) {
                updated = msg.cancelBeforeDispatch("task cancelled");
                if (!updated) {
                    continue;
                }
                TraceEventLogger.taskMsgStatusTransition(
                        msg,
                        TaskMsgStatus.INIT,
                        msg.getStatus(),
                        "CANCEL_PENDING_MESSAGES",
                        "TaskManager",
                        "task cancelled before dispatch"
                );
            } else if (status == TaskMsgStatus.ASSIGNED || status == TaskMsgStatus.RUNNING) {
                updated = msg.markAsExpired(TaskMsgFinalReason.MANUAL_CANCELLED);
                if (updated) {
                    TraceEventLogger.taskMsgStatusTransition(
                            msg,
                            status,
                            msg.getStatus(),
                            "CANCEL_PENDING_MESSAGES",
                            "TaskManager",
                            "task cancelled after assignment"
                    );
                }
            }
            if (updated) {
                taskManager.updateTaskMessage(taskId, msg);
                Task task = taskManager.getTask(taskId);
                if (task != null && attemptClosed && activeAttempt != null) {
                    TraceEventLogger.taskMessageAttemptClosed(
                            task,
                            msg,
                            activeAttempt,
                            "CANCEL_PENDING_MESSAGES",
                            "TaskManager",
                            "task termination closed the current attempt"
                    );
                    taskManager.getEventPublisher().publishTaskMessageAttemptClosed(task, msg, activeAttempt);
                }
                if (task != null) {
                    TraceEventLogger.taskMessageLogicallyFinal(
                            task,
                            msg,
                            "CANCEL_PENDING_MESSAGES",
                            "TaskManager",
                            "task termination finalized the logical message"
                    );
                    taskManager.getEventPublisher().publishTaskMessageLogicallyFinal(task, msg);
                }
            }
        }
    }
}
