package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.engine.work.ActiveLeaseRecord;
import com.xa.mass.engine.work.ResultApplyOutcome;
import com.xa.mass.engine.work.ResultApplyStatus;
import com.xa.mass.engine.work.TaskWorkResult;
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
    private final TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver;

    TaskResultService(TaskManager taskManager,
                      TaskStateResolver stateResolver,
                      TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver) {
        this.taskManager = taskManager;
        this.stateResolver = stateResolver;
        this.taskRuntimeRetryPolicyResolver = taskRuntimeRetryPolicyResolver;
    }

    boolean expireTaskMessage(String taskId, String messageId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("EXPIRE_TASK_MESSAGE", "TaskManager",
                "taskId", taskId, "messageId", messageId);

        TaskMsg taskMsg = taskManager.getTaskMessage(taskId, messageId);
        if (taskMsg == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message not found", 0);
            return false;
        }
        Task task = taskManager.getTask(taskId);
        if (taskMsg.isCompleted()) {
            logger.info("Task message {} of task {} is already in final status {}, skip expiry",
                    messageId, taskId, taskMsg.getStatus());
            return false;
        }
        TaskMsgAttempt activeAttempt = taskManager.getLatestActiveTaskMessageAttempt(taskId, messageId);
        if (activeAttempt != null) {
            if (!TaskMessageAttemptSupport.expireAttempt(activeAttempt, TaskMsgAttemptFinalReason.LEASE_EXPIRED, "task message expired")) {
                LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "attempt could not expire from status "
                        + activeAttempt.getStatus(), 0);
                return false;
            }
            taskManager.updateTaskMessageAttempt(taskId, messageId, activeAttempt);
        }
        TaskMsgStatus fromStatus = taskMsg.getStatus();
        // Once a worker has started executing the message, lease expiry must
        // converge to one final outcome instead of re-queueing the same work.
        boolean retryableExpiry = fromStatus == TaskMsgStatus.ASSIGNED
                && taskMsg.getRetryCount() < taskMsg.getMaxRetryCount();
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, retryableExpiry);
        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, false, "task message expired",
                null, null, retryableExpiry, true);
        if (workOutcome.status() == ResultApplyStatus.STALE_LEASE) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "stale work lease", 0);
            return false;
        }
        boolean expired = taskMsg.markAsExpired(TaskMsgFinalReason.LEASE_EXPIRED);
        if (!expired) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "task message status " + taskMsg.getStatus() + " cannot expire; only ASSIGNED/RUNNING can expire", 0);
            return false;
        }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                activeAttempt,
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
        if (retryableExpiry) {
            taskMsg.incrementRetryCount();
            taskMsg.resetForRetry();
            TraceEventLogger.taskMsgRetryReset(taskMsg,
                    activeAttempt,
                    workRetryDelayMillis,
                    "EXPIRE_TASK_MESSAGE", "TaskManager", "lease expired but retry budget allows re-dispatch");
            if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
                LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message retry reset persistence failed", 0);
                return false;
            }
        }
        if (freshTask != null && activeAttempt != null) {
            publishAttemptClosed(freshTask, taskMsg, activeAttempt,
                    "EXPIRE_TASK_MESSAGE", retryableExpiry
                            ? "lease expiry closed the current attempt before re-dispatch"
                            : "task message lease expired");
        }
        if (!retryableExpiry && freshTask != null) {
            publishMessageLogicallyFinal(freshTask, taskMsg, activeAttempt,
                    "EXPIRE_TASK_MESSAGE", "task message expired");
        }
        LogUtils.logOperationSuccess("task message expired", 0);
        stateResolver.updateTaskProgress(taskId);
        if (retryableExpiry) {
            Task updatedTask = taskManager.getTask(taskId);
            if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
                requestRetryDispatch(updatedTask, workRetryDelayMillis);
            }
        }
        return true;
    }

    boolean handleTaskMessageResult(String taskId, String messageId, boolean success, String detail) {
        return handleTaskMessageResult(taskId, messageId, success, detail, null, null);
    }

    boolean handleTaskMessageResult(String taskId, String messageId, boolean success, String detail, String errorCode) {
        return handleTaskMessageResult(taskId, messageId, success, detail, errorCode, null);
    }

    boolean handleTaskMessageResult(String taskId,
                                    String messageId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            logger.warn("Cannot handle task message result because task {} was not found", taskId);
            return false;
        }

        TaskMsg taskMsg = taskManager.getTaskMessage(taskId, messageId);
        if (taskMsg == null) {
            logger.warn("Cannot handle task message result because msg {} was not found in task {}", messageId, taskId);
            return false;
        }

        synchronized (taskMsg) {
            if (taskMsg.isCompleted()) {
                TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttempt(taskId, messageId);
                TraceEventLogger.callbackIgnoredDuplicate(taskMsg,
                        latestAttempt,
                        "task message already final in status " + taskMsg.getStatus());
                logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                        messageId, taskId, taskMsg.getStatus());
                stateResolver.updateTaskProgress(taskId);
                return true;
            }

            if (task.getStatus().isFinal()) {
                TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttempt(taskId, messageId);
                TraceEventLogger.callbackIgnoredLate(taskMsg,
                        latestAttempt,
                        "task already terminal in status " + task.getStatus());
                logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                        taskId, messageId, taskMsg.getStatus());
                return true;
            }

            TaskMsgAttempt activeAttempt = taskManager.getLatestActiveTaskMessageAttempt(taskId, messageId);
            if (activeAttempt == null) {
                TraceEventLogger.callbackRejectedNoActiveAttempt(
                        taskId,
                        messageId,
                        taskMsg.getStatus(),
                        "callback arrived without any active attempt"
                );
                logger.error("Cannot handle task message result because msg {} in task {} has no active attempt", messageId, taskId);
                return false;
            }
            if (!isCallbackAcceptableMessageState(taskMsg)) {
                TraceEventLogger.callbackRejectedInvalidState(taskMsg,
                        activeAttempt,
                        "callback arrived while message status is " + taskMsg.getStatus());
                logger.error("Cannot handle task message result because msg {} in task {} is in invalid callback state {}",
                        messageId, taskId, taskMsg.getStatus());
                return false;
            }

            TraceEventLogger.callbackAccepted(
                    taskMsg,
                    activeAttempt,
                    success ? "success callback received" : "failure callback received");

            if (!TaskMessageAttemptSupport.advanceAttemptForCallback(
                    activeAttempt,
                    taskManager.getTaskMessageLeaseSeconds())) {
                logger.warn("Cannot advance attempt {} for task message {} from status {}",
                        activeAttempt.getAttemptId(), messageId, activeAttempt.getStatus());
                return false;
            }
            if (!taskManager.updateTaskMessageAttempt(taskId, messageId, activeAttempt)) {
                logger.warn("Failed to persist active attempt {} for task message {}", activeAttempt.getAttemptId(), messageId);
                return false;
            }

            ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, success, detail, errorCode, output,
                    !success && taskMsg.getRetryCount() < taskMsg.getMaxRetryCount(), false);
            if (workOutcome.status() == ResultApplyStatus.STALE_LEASE) {
                logger.warn("Rejecting result for task message {} because work lease is stale", messageId);
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
        String messageId = taskMsg.getMessageId();
        if (taskMsg.getStatus() == TaskMsgStatus.ASSIGNED) {
            TaskMsgStatus beforeRunningStatus = taskMsg.getStatus();
            if (!taskMsg.markAsRunning()) {
                logger.warn("Failed to mark task message {} as RUNNING before success completion", messageId);
                return false;
            }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                activeAttempt,
                beforeRunningStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message entered running from callback"
            );
        }
        TaskMsgStatus beforeFinalStatus = taskMsg.getStatus();
        if (!taskMsg.markAsSuccess(detail, TaskMsgFinalReason.BUSINESS_SUCCESS)) {
            logger.warn("Failed to mark task message {} as SUCCESS", messageId);
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
                activeAttempt,
                beforeFinalStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked success"
        );
        taskManager.updateTaskMessageAttempt(taskId, messageId, activeAttempt);
        if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist task message {} for task {}", messageId, taskId);
            return false;
        }
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message attempt succeeded");
            publishMessageLogicallyFinal(updatedTask, taskMsg, activeAttempt,
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
        String messageId = taskMsg.getMessageId();
        if (!activeAttempt.markRevokedForRetry()) {
            logger.warn("Failed to revoke attempt {} for retry", activeAttempt.getAttemptId());
            return false;
        }
        activeAttempt.setErrorCode(errorCode);
        activeAttempt.setOutput(output);
        taskManager.updateTaskMessageAttempt(taskId, messageId, activeAttempt);

        TaskMsgStatus beforeRetryFailureStatus = taskMsg.getStatus();
        if (!taskMsg.markAsFailed(detail, TaskMsgFinalReason.BUSINESS_FAILED)) {
            logger.warn("Failed to mark task message {} as FAILED before retry reset", messageId);
            return false;
        }
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                activeAttempt,
                beforeRetryFailureStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked failed before retry reset"
        );
        if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist intermediate failed state for task message {} in task {}", messageId, taskId);
            return false;
        }

        taskMsg.incrementRetryCount();
        taskMsg.resetForRetry();
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(taskManager.getTask(taskId), true);
        TraceEventLogger.taskMsgRetryReset(taskMsg,
                activeAttempt,
                workRetryDelayMillis,
                "HANDLE_TASK_MESSAGE_RESULT", "TaskManager", "retry budget allows re-dispatch");
        if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist retry state for task message {} in task {}", messageId, taskId);
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
            requestRetryDispatch(updatedTask, workRetryDelayMillis);
        }
        return true;
    }

    private boolean handleRetryExhaustedFailure(String taskId,
                                                TaskMsg taskMsg,
                                                TaskMsgAttempt activeAttempt,
                                                String detail,
                                                String errorCode,
                                                Map<String, Object> output) {
        String messageId = taskMsg.getMessageId();
        if (!activeAttempt.markFailed(TaskMsgAttemptFinalReason.BUSINESS_FAILURE, detail, errorCode)) {
            logger.warn("Failed to mark attempt {} as FAILED", activeAttempt.getAttemptId());
            return false;
        }
        activeAttempt.setOutput(output);
        taskManager.updateTaskMessageAttempt(taskId, messageId, activeAttempt);

        TaskMsgStatus beforeFinalStatus = taskMsg.getStatus();
        if (!taskMsg.markAsFailed(detail, TaskMsgFinalReason.RETRY_EXHAUSTED)) {
            logger.warn("Failed to mark task message {} as FAILED", messageId);
            return false;
        }
        taskMsg.setErrorCode(errorCode);
        TraceEventLogger.taskMsgStatusTransition(
                taskMsg,
                activeAttempt,
                beforeFinalStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked failure"
        );

        if (!taskManager.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist task message {} for task {}", messageId, taskId);
            return false;
        }
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "retry budget exhausted closed the current attempt");
            publishMessageLogicallyFinal(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message reached stable failure");
        }

        taskManager.getScheduler().handleTaskMsgFailure(taskMsg, detail);
        stateResolver.updateTaskProgress(taskId);
        return true;
    }

    private boolean isCallbackAcceptableMessageState(TaskMsg taskMsg) {
        return taskMsg.getStatus() == TaskMsgStatus.ASSIGNED
                || taskMsg.getStatus() == TaskMsgStatus.RUNNING;
    }

    private ResultApplyOutcome applyWorkResult(Task task,
                                               String taskId,
                                               String messageId,
                                               boolean success,
                                               String detail,
                                               String errorCode,
                                               Map<String, Object> output,
                                               boolean retryable,
                                               boolean expired) {
        String leaseToken = taskManager.getTaskWorkRuntime()
                .getActiveLease(taskId, messageId)
                .map(ActiveLeaseRecord::leaseToken)
                .orElse(null);
        TaskWorkResult result;
        if (success) {
            result = TaskWorkResult.success(taskId, messageId, leaseToken, detail, output);
        } else if (expired) {
            result = TaskWorkResult.expired(taskId, messageId, leaseToken, detail, retryable);
        } else {
            result = TaskWorkResult.failure(taskId, messageId, leaseToken, errorCode, detail, output, retryable);
        }
        if (retryable) {
            long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, true);
            if (workRetryDelayMillis > 0L) {
                result = result.withRetryVisibleAt(result.completedAt().plusMillis(workRetryDelayMillis));
            }
        }
        ResultApplyOutcome outcome = taskManager.getTaskWorkRuntime().applyResult(result);
        if (outcome.status() == ResultApplyStatus.NO_ACTIVE_LEASE) {
            logger.debug("No active work-runtime lease for task {}, msg {}; continuing through compatibility attempt path",
                    taskId, messageId);
        }
        return outcome;
    }

    private long resolveWorkRetryDelayMillis(Task task, boolean retryable) {
        if (!retryable) {
            return 0L;
        }
        TaskRuntimeRetryPolicy retryPolicy = taskRuntimeRetryPolicyResolver.resolve(task, 1L);
        return retryPolicy.workRetryDelayMillis();
    }

    void shutdown() {
        // Runtime-owned retry wakeup lifecycle is managed by TaskDispatchRequestService.
    }

    private void requestRetryDispatch(Task task, long workRetryDelayMillis) {
        taskManager.requestTaskRetryDispatch(task, workRetryDelayMillis);
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
                                              TaskMsgAttempt attempt,
                                              String trigger,
                                              String reason) {
        TraceEventLogger.taskMessageLogicallyFinal(task, taskMsg, attempt, trigger, "TaskManager", reason);
        taskManager.getEventPublisher().publishTaskMessageLogicallyFinal(task, taskMsg);
    }
}
