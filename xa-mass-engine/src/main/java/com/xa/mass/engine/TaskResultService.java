package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Owns task-message callback handling, retry sequencing, and result-side event ordering.
 */
class TaskResultService {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultService.class);

    private final TaskManager taskManager;
    private final TaskStateResolver stateResolver;

    TaskResultService(TaskManager taskManager, TaskStateResolver stateResolver) {
        this.taskManager = taskManager;
        this.stateResolver = stateResolver;
    }

    boolean expireTaskMessage(String taskId, String msgId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("EXPIRE_TASK_MESSAGE", "TaskManager",
                "taskId", taskId, "msgId", msgId);

        TaskMsg taskMsg = taskManager.getTaskMessage(taskId, msgId);
        if (taskMsg == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message not found", 0);
            return false;
        }
        if (taskMsg.isCompleted()) {
            logger.info("Task message {} of task {} is already in final status {}, skip expiry",
                    msgId, taskId, taskMsg.getStatus());
            return false;
        }
        TaskMsgAttempt activeAttempt = taskManager.getLatestActiveTaskMessageAttempt(taskId, msgId);
        if (activeAttempt != null) {
            if (!TaskMessageAttemptSupport.expireAttempt(activeAttempt, TaskMsgAttemptFinalReason.LEASE_EXPIRED, "task message expired")) {
                LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "attempt could not expire from status "
                        + activeAttempt.getStatus(), 0);
                return false;
            }
            taskManager.updateTaskMessageAttempt(taskId, msgId, activeAttempt);
        }
        TaskMsgStatus fromStatus = taskMsg.getStatus();
        boolean expired = taskMsg.markAsExpired(TaskMsgFinalReason.LEASE_EXPIRED);
        if (!expired) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "task message status " + taskMsg.getStatus() + " cannot expire; only ASSIGNED/RUNNING can expire", 0);
            return false;
        }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                fromStatus,
                taskMsg.getStatus(),
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                "task message expired"
        );
        boolean stored = taskManager.updateTaskMessage(taskId, taskMsg);
        if (!stored) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message persistence failed", 0);
            return false;
        }
        Task freshTask = taskManager.getTask(taskId);
        if (freshTask != null && activeAttempt != null) {
            publishAttemptClosed(freshTask, taskMsg, activeAttempt,
                    "EXPIRE_TASK_MESSAGE", "task message lease expired");
        }
        if (freshTask != null) {
            publishMessageLogicallyFinal(freshTask, taskMsg,
                    "EXPIRE_TASK_MESSAGE", "task message expired");
        }
        LogUtils.logOperationSuccess("task message expired", 0);
        stateResolver.updateTaskProgress(taskId);
        return true;
    }

    boolean handleTaskMessageResult(String taskId, String msgId, boolean success, String detail) {
        return handleTaskMessageResult(taskId, msgId, success, detail, null, null);
    }

    boolean handleTaskMessageResult(String taskId, String msgId, boolean success, String detail, String errorCode) {
        return handleTaskMessageResult(taskId, msgId, success, detail, errorCode, null);
    }

    boolean handleTaskMessageResult(String taskId,
                                    String msgId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            logger.warn("Cannot handle task message result because task {} was not found", taskId);
            return false;
        }

        TaskMsg taskMsg = taskManager.getTaskMessage(taskId, msgId);
        if (taskMsg == null) {
            logger.warn("Cannot handle task message result because msg {} was not found in task {}", msgId, taskId);
            return false;
        }

        synchronized (taskMsg) {
            if (taskMsg.isCompleted()) {
                TraceEventLogger.callbackIgnoredDuplicate(taskMsg,
                        "task message already final in status " + taskMsg.getStatus());
                logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                        msgId, taskId, taskMsg.getStatus());
                stateResolver.updateTaskProgress(taskId);
                return true;
            }

            if (task.getStatus().isFinal()) {
                TraceEventLogger.callbackIgnoredLate(taskMsg,
                        "task already terminal in status " + task.getStatus());
                logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                        taskId, msgId, taskMsg.getStatus());
                return true;
            }

            TaskMsgAttempt activeAttempt = taskManager.getLatestActiveTaskMessageAttempt(taskId, msgId);
            if (activeAttempt == null) {
                TraceEventLogger.callbackRejectedNoActiveAttempt(
                        taskId,
                        msgId,
                        taskMsg.getStatus(),
                        "callback arrived without any active attempt"
                );
                logger.error("Cannot handle task message result because msg {} in task {} has no active attempt", msgId, taskId);
                return false;
            }

            TraceEventLogger.callbackAccepted(taskMsg, success ? "success callback received" : "failure callback received");

            if (!TaskMessageAttemptSupport.advanceAttemptForCallback(activeAttempt)) {
                logger.warn("Cannot advance attempt {} for task message {} from status {}",
                        activeAttempt.getAttemptId(), msgId, activeAttempt.getStatus());
                return false;
            }
            if (!taskManager.updateTaskMessageAttempt(taskId, msgId, activeAttempt)) {
                logger.warn("Failed to persist active attempt {} for task message {}", activeAttempt.getAttemptId(), msgId);
                return false;
            }

            if (success) {
                return handleSuccess(taskId, taskMsg, activeAttempt, detail, output);
            }
            if (taskMsg.getRetryCount() < taskMsg.getMaxRetryCount()) {
                return handleRetryableFailure(taskId, taskMsg, activeAttempt, detail, errorCode, output);
            }
            return handleRetryExhaustedFailure(taskId, taskMsg, activeAttempt, detail, errorCode, output);
        }
    }

    private boolean handleSuccess(String taskId,
                                  TaskMsg taskMsg,
                                  TaskMsgAttempt activeAttempt,
                                  String detail,
                                  Map<String, Object> output) {
        String msgId = taskMsg.getMsgId();
        if (taskMsg.getStatus() == TaskMsgStatus.INIT && !taskMsg.markAsAssigned()) {
            logger.warn("Failed to mark task message {} as ASSIGNED before success completion", msgId);
            return false;
        }
        if (taskMsg.getStatus() == TaskMsgStatus.ASSIGNED) {
            TaskMsgStatus beforeRunningStatus = taskMsg.getStatus();
            if (!taskMsg.markAsRunning()) {
                logger.warn("Failed to mark task message {} as RUNNING before success completion", msgId);
                return false;
            }
            TraceEventLogger.taskMsgStatusTransition(
                    taskMsg,
                    beforeRunningStatus,
                    taskMsg.getStatus(),
                    "HANDLE_TASK_MESSAGE_RESULT",
                    "TaskManager",
                    "task message entered running from callback"
            );
        }
        TaskMsgStatus beforeFinalStatus = taskMsg.getStatus();
        if (!taskMsg.markAsSuccess(detail, TaskMsgFinalReason.BUSINESS_SUCCESS)) {
            logger.warn("Failed to mark task message {} as SUCCESS", msgId);
            return false;
        }
        taskMsg.setOutput(output);
        if (!activeAttempt.markSucceeded()) {
            logger.warn("Failed to mark attempt {} as SUCCEEDED", activeAttempt.getAttemptId());
            return false;
        }
        activeAttempt.setOutput(output);
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                beforeFinalStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked success"
        );
        taskManager.updateTaskMessageAttempt(taskId, msgId, activeAttempt);
        if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist task message {} for task {}", msgId, taskId);
            return false;
        }
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message attempt succeeded");
            publishMessageLogicallyFinal(updatedTask, taskMsg,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message reached stable success");
        }
        taskManager.getScheduler().handleTaskMsgCompletion(taskMsg);
        stateResolver.updateTaskProgress(taskId);
        return true;
    }

    private boolean handleRetryableFailure(String taskId,
                                           TaskMsg taskMsg,
                                           TaskMsgAttempt activeAttempt,
                                           String detail,
                                           String errorCode,
                                           Map<String, Object> output) {
        String msgId = taskMsg.getMsgId();
        if (!activeAttempt.markRevokedForRetry()) {
            logger.warn("Failed to revoke attempt {} for retry", activeAttempt.getAttemptId());
            return false;
        }
        activeAttempt.setErrorCode(errorCode);
        activeAttempt.setOutput(output);
        taskManager.updateTaskMessageAttempt(taskId, msgId, activeAttempt);

        TaskMsgStatus beforeRetryFailureStatus = taskMsg.getStatus();
        if (!taskMsg.markAsFailed(detail, TaskMsgFinalReason.BUSINESS_FAILED)) {
            logger.warn("Failed to mark task message {} as FAILED before retry reset", msgId);
            return false;
        }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                beforeRetryFailureStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked failed before retry reset"
        );
        if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist intermediate failed state for task message {} in task {}", msgId, taskId);
            return false;
        }

        taskMsg.incrementRetryCount();
        taskMsg.resetForRetry();
        TraceEventLogger.taskMsgRetryReset(taskMsg,
                "HANDLE_TASK_MESSAGE_RESULT", "TaskManager", "retry budget allows re-dispatch");
        if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist retry state for task message {} in task {}", msgId, taskId);
            return false;
        }
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "retryable failure closed the current attempt");
        }
        stateResolver.updateTaskProgress(taskId);
        updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
            taskManager.getEventPublisher().publishTaskDispatchRequested(updatedTask);
        }
        return true;
    }

    private boolean handleRetryExhaustedFailure(String taskId,
                                                TaskMsg taskMsg,
                                                TaskMsgAttempt activeAttempt,
                                                String detail,
                                                String errorCode,
                                                Map<String, Object> output) {
        String msgId = taskMsg.getMsgId();
        if (!activeAttempt.markFailed(TaskMsgAttemptFinalReason.BUSINESS_FAILURE, detail, errorCode)) {
            logger.warn("Failed to mark attempt {} as FAILED", activeAttempt.getAttemptId());
            return false;
        }
        activeAttempt.setOutput(output);
        taskManager.updateTaskMessageAttempt(taskId, msgId, activeAttempt);

        if (taskMsg.getStatus() == TaskMsgStatus.INIT && !taskMsg.markAsAssigned()) {
            logger.warn("Failed to mark task message {} as ASSIGNED before failure finalization", msgId);
            return false;
        }
        TaskMsgStatus beforeFinalStatus = taskMsg.getStatus();
        if (!taskMsg.markAsFailed(detail, TaskMsgFinalReason.RETRY_EXHAUSTED)) {
            logger.warn("Failed to mark task message {} as FAILED", msgId);
            return false;
        }
        taskMsg.setErrorCode(errorCode);
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                beforeFinalStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked failure"
        );

        if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist task message {} for task {}", msgId, taskId);
            return false;
        }
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "retry budget exhausted closed the current attempt");
            publishMessageLogicallyFinal(updatedTask, taskMsg,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message reached stable failure");
        }

        taskManager.getScheduler().handleTaskMsgFailure(taskMsg, detail);
        stateResolver.updateTaskProgress(taskId);
        return true;
    }

    private void publishAttemptClosed(Task task,
                                      TaskMsg taskMsg,
                                      TaskMsgAttempt attempt,
                                      String trigger,
                                      String reason) {
        TraceEventLogger.taskMessageAttemptClosed(task, taskMsg, attempt, trigger, "TaskManager", reason);
        taskManager.getEventPublisher().publishTaskMessageAttemptClosed(task, taskMsg, attempt);
    }

    private void publishMessageLogicallyFinal(Task task,
                                              TaskMsg taskMsg,
                                              String trigger,
                                              String reason) {
        TraceEventLogger.taskMessageLogicallyFinal(task, taskMsg, trigger, "TaskManager", reason);
        taskManager.getEventPublisher().publishTaskMessageLogicallyFinal(task, taskMsg);
    }
}
