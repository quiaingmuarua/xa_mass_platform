package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.TaskWorkStats;
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
    private final TraceEventLogger traceEventLogger;

    TaskLifecycleService(TaskManager taskManager,
                         TaskStateResolver stateResolver,
                         TraceEventLogger traceEventLogger) {
        this.taskManager = taskManager;
        this.stateResolver = stateResolver;
        this.traceEventLogger = traceEventLogger;
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
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            "APPROVE_TASK", "TaskManager", "task approved");
                    taskManager.updateTask(task);
                    taskManager.publishTaskReady(task);
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
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
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
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
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
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
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
                TaskWorkStats stats = taskManager.getTaskWorkStats(taskId);
                TaskTerminalPolicyDecision decision = taskManager.evaluateTerminalPolicy(task, stats);
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
                        taskManager.updateTask(task);
                        taskManager.publishTaskTerminal(task);
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
                    taskManager.updateTask(task);
                    taskManager.getScheduler().resumeTask(taskId);
                    taskManager.publishTaskReady(task);
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

        List<RuntimeTaskIngressItem> ingressItems = new java.util.ArrayList<>(inputs.size());
        for (java.util.Map<String, Object> input : inputs) {
            ingressItems.add(RuntimeTaskIngressItem.fromInput(
                    taskId,
                    java.util.UUID.randomUUID().toString(),
                    input,
                    task.getExecutionSpec().getDefaultMaxRetryCount()
            ));
        }
        taskManager.addRuntimeIngressItems(task, ingressItems);
        int added = ingressItems.size();
        task.setTaskTargetNumber(task.getTaskTargetNumber() + added);
        task.setTaskEligibleNumber(task.getTaskEligibleNumber() + added);
        taskManager.updateTask(task);
        if (task.getStatus().isActive()) {
            taskManager.requestTaskDispatch(task);
        }
        logger.info("[appendTaskItems] Added {} items to task {}", added, taskId);
        return added;
    }

    boolean sealTask(String taskId) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            return false;
        }
        if (task.getIntakeStatus() != TaskIntakeStatus.OPEN) {
            return false;
        }
        task.setIntakeStatus(TaskIntakeStatus.SEALED);
        taskManager.updateTask(task);
        stateResolver.updateTaskProgress(taskId);
        logger.info("[sealTask] Sealed task {}", taskId);
        return true;
    }

    private boolean canAcceptTaskInputs(Task task) {
        return task.getStatus() != null
                && !task.getStatus().isFinal()
                && task.getIntakeStatus() == TaskIntakeStatus.OPEN;
    }

    private String describeInputAppendRejection(Task task, String taskId) {
        if (task.getIntakeStatus() != TaskIntakeStatus.OPEN) {
            return "Task intake is sealed: " + taskId;
        }
        return "Task intake is closed or task is terminal: " + task.getStatus();
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

        boolean result = taskManager.deleteTaskRecord(taskId);
        if (result) {
            taskManager.discardTaskRuntime(taskId);
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
                    closeTaskIntakeOnTerminal(task);
                    traceEventLogger.taskStatusTransition(taskId, fromStatus, task.getStatus(),
                            trigger, "TaskManager", "task terminated: " + reason);
                    traceEventLogger.taskTerminalClosed(taskId, fromStatus, reason,
                            trigger, "TaskManager", "task terminated: " + reason);
                    taskManager.updateTask(task);
                    taskManager.getScheduler().cancelTask(taskId);
                    taskManager.publishTaskTerminal(task);
                    taskManager.discardTaskRuntime(taskId);
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

    private void closeTaskIntakeOnTerminal(Task task) {
        if (task == null) {
            return;
        }
        task.setIntakeStatus(TaskIntakeStatus.SEALED);
    }
}

