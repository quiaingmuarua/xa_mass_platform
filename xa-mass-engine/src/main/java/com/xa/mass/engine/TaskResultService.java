package com.xa.mass.engine;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Owns task-message callback handling, retry sequencing, and result-side event ordering.
 */
class TaskResultService {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultService.class);
    static final String DISPATCH_SUBMIT_FAILED_ERROR_CODE = "DISPATCH_SUBMIT_FAILED";

    private final TaskResultRuntimePort resultRuntime;
    private final TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver;
    private final TraceEventLogger traceEventLogger;

    TaskResultService(TaskResultRuntimePort resultRuntime,
                      TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver,
                      TraceEventLogger traceEventLogger) {
        this.resultRuntime = resultRuntime;
        this.taskRuntimeRetryPolicyResolver = taskRuntimeRetryPolicyResolver;
        this.traceEventLogger = traceEventLogger;
    }

    TaskMessageMutationOutcome expireTaskMessage(String taskId, String messageId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("EXPIRE_TASK_MESSAGE", "TaskManager",
                "taskId", taskId, "messageId", messageId);

        TaskMsg taskMsg = resultRuntime.getTaskMessage(taskId, messageId);
        if (taskMsg == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message not found", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        Task task = resultRuntime.getTask(taskId);
        if (taskMsg.isCompleted()) {
            logger.info("Task message {} of task {} is already in final status {}, skip expiry",
                    messageId, taskId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.rejected();
        }
        ActiveLeaseRecord activeLease = resultRuntime.getActiveLease(taskId, messageId).orElse(null);
        if (activeLease == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "no active runtime lease", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeLeaseProjectionSupport.ProjectionLeaseSyncResult leaseSync =
                RuntimeLeaseProjectionSupport.recoverAndSynchronizeActiveAttempt(
                        resultRuntime,
                        taskId,
                        taskMsg,
                        activeLease,
                        traceEventLogger,
                        "EXPIRE_TASK_MESSAGE",
                        "runtime active lease synchronized compatibility projection"
                );
        if (!leaseSync.synchronizedProjection()) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message projection synchronization failed", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        TaskMsgAttempt activeAttempt = leaseSync.activeAttempt();
        if (activeAttempt != null) {
            if (!TaskMessageAttemptSupport.expireAttempt(activeAttempt, TaskMsgAttemptFinalReason.LEASE_EXPIRED, "task message expired")) {
                LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "attempt could not expire from status "
                        + activeAttempt.getStatus(), 0);
                return TaskMessageMutationOutcome.rejected();
            }
            resultRuntime.updateTaskMessageAttempt(taskId, messageId, activeAttempt);
        }
        TaskMsgStatus fromStatus = taskMsg.getStatus();
        // Once a worker has started executing the message, lease expiry must
        // converge to one final outcome instead of re-queueing the same work.
        boolean retryRequestedByPolicy = fromStatus == TaskMsgStatus.ASSIGNED;
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, retryRequestedByPolicy);
        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, activeLease.leaseToken(),
                false, "task message expired", null, null, retryRequestedByPolicy, true);
        if (workOutcome.status() == ResultApplyStatus.STALE_LEASE
                || workOutcome.status() == ResultApplyStatus.NO_ACTIVE_LEASE) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "runtime lease rejected expiry result: " + workOutcome.status(), 0);
            return TaskMessageMutationOutcome.rejected();
        }
        boolean retryScheduled = workOutcome.status() == ResultApplyStatus.RETRY_SCHEDULED;
        boolean expired = taskMsg.markAsExpired(TaskMsgFinalReason.LEASE_EXPIRED);
        if (!expired) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "task message status " + taskMsg.getStatus() + " cannot expire; only ASSIGNED/RUNNING can expire", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        traceEventLogger.leaseExpired(
                taskMsg,
                activeAttempt,
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                "task message expired"
        );
        traceEventLogger.taskMsgStatusTransition(
                taskMsg,
                activeAttempt,
                fromStatus,
                taskMsg.getStatus(),
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                "task message expired"
        );
        boolean stored = resultRuntime.updateTaskMessage(taskId, taskMsg);
        if (!stored) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message persistence failed", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        Task freshTask = resultRuntime.getTask(taskId);
        if (retryScheduled) {
            taskMsg.incrementRetryCount();
            taskMsg.resetForRetry();
            traceEventLogger.taskMsgRetryReset(taskMsg,
                    activeAttempt,
                    workRetryDelayMillis,
                    "EXPIRE_TASK_MESSAGE", "TaskManager", "lease expired but retry budget allows re-dispatch");
            if (!resultRuntime.updateTaskMessage(taskId, taskMsg)) {
                LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message retry reset persistence failed", 0);
                return TaskMessageMutationOutcome.rejected();
            }
        }
        if (freshTask != null && activeAttempt != null) {
            publishAttemptClosed(freshTask, taskMsg, activeAttempt,
                    "EXPIRE_TASK_MESSAGE", retryScheduled
                            ? "lease expiry closed the current attempt before re-dispatch"
                            : "task message lease expired");
        }
        if (!retryScheduled && freshTask != null) {
            publishMessageLogicallyFinal(freshTask, taskMsg, activeAttempt,
                    "EXPIRE_TASK_MESSAGE", "task message expired");
        }
        LogUtils.logOperationSuccess("task message expired", 0);
        if (retryScheduled) {
            Task updatedTask = resultRuntime.getTask(taskId);
            if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
                requestRetryDispatch(updatedTask, workRetryDelayMillis);
            }
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    TaskMessageMutationOutcome handleTaskMessageResult(String taskId, String messageId, boolean success, String detail) {
        return handleTaskMessageResult(taskId, messageId, success, detail, null, null);
    }

    TaskMessageMutationOutcome handleTaskMessageResult(String taskId, String messageId, boolean success, String detail, String errorCode) {
        return handleTaskMessageResult(taskId, messageId, success, detail, errorCode, null);
    }

    TaskMessageMutationOutcome handleTaskMessageResult(String taskId,
                                                       String messageId,
                                                       boolean success,
                                                       String detail,
                                                       String errorCode,
                                                       Map<String, Object> output) {
        Task task = resultRuntime.getTask(taskId);
        if (task == null) {
            logger.warn("Cannot handle task message result because task {} was not found", taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        ActiveLeaseRecord activeLease = resultRuntime.getActiveLease(taskId, messageId).orElse(null);
        TaskMsg taskMsg = resolveOrRecoverTaskMessageProjection(taskId, messageId, activeLease);
        if (taskMsg == null) {
            logger.warn("Cannot handle task message result because msg {} was not found in task {} and no runtime projection could be recovered",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        if (taskMsg.isCompleted()) {
            TaskMsgAttempt latestAttempt = resultRuntime.getLatestTaskMessageAttempt(taskId, messageId);
            traceEventLogger.callbackIgnoredDuplicate(taskMsg,
                    latestAttempt,
                    "task message already final in status " + taskMsg.getStatus());
            logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                    messageId, taskId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.acceptedNoop();
        }

        if (task.getStatus().isFinal()) {
            TaskMsgAttempt latestAttempt = resultRuntime.getLatestTaskMessageAttempt(taskId, messageId);
            traceEventLogger.callbackIgnoredLate(taskMsg,
                    latestAttempt,
                    "task already terminal in status " + task.getStatus());
            logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                    taskId, messageId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.acceptedNoop();
        }

        if (activeLease == null) {
            TaskMsgAttempt latestAttempt = resultRuntime.getLatestTaskMessageAttempt(taskId, messageId);
            traceEventLogger.callbackRejectedNoActiveLease(
                    taskMsg,
                    latestAttempt,
                    "callback arrived without any active runtime lease"
            );
            logger.error("Cannot handle task message result because msg {} in task {} has no active runtime lease", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeLeaseProjectionSupport.ProjectionLeaseSyncResult leaseSync =
                RuntimeLeaseProjectionSupport.recoverAndSynchronizeActiveAttempt(
                        resultRuntime,
                        taskId,
                        taskMsg,
                        activeLease,
                        traceEventLogger,
                        "HANDLE_TASK_MESSAGE_RESULT",
                        "runtime active lease synchronized compatibility projection"
                );
        TaskMsgAttempt activeAttempt = leaseSync.activeAttempt();
        if (activeAttempt == null) {
            traceEventLogger.callbackRejectedNoActiveAttempt(
                    taskId,
                    messageId,
                    taskMsg.getStatus(),
                    "callback arrived without any recoverable active attempt"
            );
            logger.error("Cannot handle task message result because msg {} in task {} has no recoverable active attempt", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        if (!leaseSync.synchronizedProjection()) {
            logger.warn("Failed to synchronize task message {} projection from runtime active lease", messageId);
            return TaskMessageMutationOutcome.rejected();
        }
        if (!isCallbackAcceptableMessageState(taskMsg)) {
            traceEventLogger.callbackRejectedInvalidState(taskMsg,
                    activeAttempt,
                    "callback arrived while message status is " + taskMsg.getStatus());
            logger.error("Cannot handle task message result because msg {} in task {} is in invalid callback state {}",
                    messageId, taskId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.rejected();
        }

        traceEventLogger.callbackAccepted(
                taskMsg,
                activeAttempt,
                success ? "success callback received" : "failure callback received");

        if (!TaskMessageAttemptSupport.advanceAttemptForCallback(
                activeAttempt,
                resultRuntime.getTaskMessageLeaseSeconds())) {
            logger.warn("Cannot advance attempt {} for task message {} from status {}",
                    activeAttempt.getAttemptId(), messageId, activeAttempt.getStatus());
            return TaskMessageMutationOutcome.rejected();
        }
        persistAttemptProjectionBestEffort(taskId, messageId, activeAttempt,
                "advance attempt for callback");

        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, activeLease.leaseToken(),
                success, detail, errorCode, output, !success, false);
        if (workOutcome.status() == ResultApplyStatus.STALE_LEASE
                || workOutcome.status() == ResultApplyStatus.NO_ACTIVE_LEASE) {
            logger.warn("Rejecting result for task message {} because runtime lease rejected the result with {}",
                    messageId, workOutcome.status());
            return TaskMessageMutationOutcome.rejected();
        }

        if (success) {
            return handleSuccess(taskId, taskMsg, activeAttempt, detail, output);
        }
        if (workOutcome.status() == ResultApplyStatus.RETRY_SCHEDULED) {
            return handleRetryableFailure(taskId, taskMsg, activeAttempt, detail, errorCode, output);
        }
        return handleRetryExhaustedFailure(taskId, taskMsg, activeAttempt, detail, errorCode, output);
    }

    TaskMessageMutationOutcome compensateDispatchSubmitFailure(Task task,
                                                              TaskDispatchBinding dispatchBinding,
                                                              String detail) {
        if (task == null || dispatchBinding == null) {
            return TaskMessageMutationOutcome.rejected();
        }

        String taskId = task.getTid();
        String messageId = dispatchBinding.messageId();
        TaskMsg taskMsg = resolveOrRecoverTaskMessageProjection(taskId, messageId,
                resultRuntime.getActiveLease(taskId, messageId).orElse(null));
        if (taskMsg == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} was not found in task {}",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        ActiveLeaseRecord activeLease = resultRuntime.getActiveLease(taskId, messageId).orElse(null);
        if (activeLease == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no active runtime lease",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        TaskMsgAttempt activeAttempt = resolveOrRecoverDispatchAttemptProjection(taskMsg, activeLease, dispatchBinding);
        if (activeAttempt == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no recoverable attempt projection",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        if (!Objects.equals(dispatchBinding.attemptId(), activeAttempt.getAttemptId())) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} advanced to attempt {} instead of {}",
                    messageId, taskId, activeAttempt.getAttemptId(), dispatchBinding.attemptId());
            return TaskMessageMutationOutcome.rejected();
        }

        String normalizedDetail = normalizeDispatchSubmitFailureDetail(detail);
        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, activeLease.leaseToken(),
                false, normalizedDetail, DISPATCH_SUBMIT_FAILED_ERROR_CODE, null, true, false);
        if (workOutcome.status() != ResultApplyStatus.RETRY_SCHEDULED) {
            logger.warn("Dispatch submit compensation for msg {} in task {} was rejected by runtime with {}",
                    messageId, taskId, workOutcome.status());
            return TaskMessageMutationOutcome.rejected();
        }

        TaskMessageMutationOutcome retryOutcome = resetForRetryWithoutPublishingAttemptClosure(
                taskId,
                taskMsg,
                activeAttempt,
                normalizedDetail,
                DISPATCH_SUBMIT_FAILED_ERROR_CODE,
                "COMPENSATE_DISPATCH_SUBMIT_FAILURE",
                "dispatch submit failed before transport delivery"
        );
        if (!retryOutcome.accepted()) {
            return retryOutcome;
        }

        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, true);
        Task updatedTask = resultRuntime.getTask(taskId);
        if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
            requestRetryDispatch(updatedTask, workRetryDelayMillis);
        }
        return retryOutcome;
    }

    private TaskMsg resolveOrRecoverTaskMessageProjection(String taskId,
                                                          String messageId,
                                                          ActiveLeaseRecord activeLease) {
        TaskMsg taskMsg = resultRuntime.getTaskMessage(taskId, messageId);
        if (taskMsg != null || activeLease == null) {
            return taskMsg;
        }
        TaskMsg recovered = new TaskMsg(messageId, taskId, Map.of());
        recovered.setRetryCount(Math.max(0, activeLease.retryCount()));
        recovered.applyLatestAttemptProjection(null,
                activeLease.workerId(),
                activeLease.workerContextId(),
                activeLease.batchId());
        recovered.markAsAssigned();
        resultRuntime.addTaskMessageProjection(taskId, recovered);
        return resultRuntime.getTaskMessage(taskId, messageId);
    }

    private TaskMsgAttempt resolveOrRecoverDispatchAttemptProjection(TaskMsg taskMsg,
                                                                     ActiveLeaseRecord activeLease,
                                                                     TaskDispatchBinding dispatchBinding) {
        TaskMsgAttempt activeAttempt = resultRuntime.getLatestTaskMessageAttempt(taskMsg.getTaskId(), taskMsg.getMessageId());
        if (activeAttempt != null) {
            return activeAttempt;
        }
        if (activeLease == null || dispatchBinding == null) {
            return null;
        }
        TaskMsgAttempt recoveredAttempt = new TaskMsgAttempt(
                dispatchBinding.attemptId(),
                taskMsg.getTaskId(),
                taskMsg.getMessageId(),
                dispatchBinding.attemptNo()
        );
        recoveredAttempt.setWorkerId(dispatchBinding.workerId());
        recoveredAttempt.setWorkerContextId(dispatchBinding.workerContextId());
        recoveredAttempt.setBatchId(dispatchBinding.batchId());
        if (!recoveredAttempt.markLeased(java.time.LocalDateTime.ofInstant(activeLease.leaseExpireAt(), java.time.ZoneId.systemDefault()))) {
            return null;
        }
        if (!recoveredAttempt.markDispatched()) {
            return null;
        }
        persistAttemptProjectionAddBestEffort(taskMsg.getTaskId(), taskMsg.getMessageId(), recoveredAttempt,
                "recover dispatch compensation attempt projection");
        return recoveredAttempt;
    }

    private TaskMessageMutationOutcome handleSuccess(String taskId,
                                                     TaskMsg taskMsg,
                                                     TaskMsgAttempt activeAttempt,
                                                     String detail,
                                                     Map<String, Object> output) {
        String messageId = taskMsg.getMessageId();
        if (taskMsg.getStatus() == TaskMsgStatus.ASSIGNED) {
            TaskMsgStatus beforeRunningStatus = taskMsg.getStatus();
            if (!taskMsg.markAsRunning()) {
                logger.warn("Failed to mark task message {} as RUNNING before success completion", messageId);
                return TaskMessageMutationOutcome.rejected();
            }
            traceEventLogger.taskMsgStatusTransition(
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
            return TaskMessageMutationOutcome.rejected();
        }
        taskMsg.setOutput(output);
        if (!activeAttempt.markSucceeded()) {
            logger.warn("Failed to mark attempt {} as SUCCEEDED", activeAttempt.getAttemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        activeAttempt.setOutput(output);
        traceEventLogger.taskMsgStatusTransition(
                taskMsg,
                activeAttempt,
                beforeFinalStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked success"
        );
        persistAttemptProjectionBestEffort(taskId, messageId, activeAttempt,
                "mark attempt success");
        if (!resultRuntime.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist task message {} for task {}", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        Task updatedTask = resultRuntime.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message attempt succeeded");
            publishMessageLogicallyFinal(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message reached stable success");
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    private TaskMessageMutationOutcome handleRetryableFailure(String taskId,
                                                              TaskMsg taskMsg,
                                                              TaskMsgAttempt activeAttempt,
                                                              String detail,
                                                              String errorCode,
                                                              Map<String, Object> output) {
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(resultRuntime.getTask(taskId), true);
        TaskMessageMutationOutcome retryOutcome = resetForRetryWithoutPublishingAttemptClosure(
                taskId,
                taskMsg,
                activeAttempt,
                detail,
                errorCode,
                "HANDLE_TASK_MESSAGE_RESULT",
                "retry budget allows re-dispatch"
        );
        if (!retryOutcome.accepted()) {
            return retryOutcome;
        }
        Task updatedTask = resultRuntime.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "retryable failure closed the current attempt");
        }
        updatedTask = resultRuntime.getTask(taskId);
        if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
            requestRetryDispatch(updatedTask, workRetryDelayMillis);
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    private TaskMessageMutationOutcome resetForRetryWithoutPublishingAttemptClosure(String taskId,
                                                                                    TaskMsg taskMsg,
                                                                                    TaskMsgAttempt activeAttempt,
                                                                                    String detail,
                                                                                    String errorCode,
                                                                                    String trigger,
                                                                                    String resetReason) {
        String messageId = taskMsg.getMessageId();
        TaskMsgAttemptStatus beforeRevokedStatus = activeAttempt.getStatus();
        if (!activeAttempt.markRevokedForRetry()) {
            logger.warn("Failed to revoke attempt {} for retry", activeAttempt.getAttemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        activeAttempt.setErrorMessage(detail);
        activeAttempt.setErrorCode(errorCode);
        activeAttempt.setOutput(null);
        traceEventLogger.taskMsgAttemptStatusTransition(
                activeAttempt,
                beforeRevokedStatus,
                activeAttempt.getStatus(),
                trigger,
                "TaskManager",
                resetReason
        );
        persistAttemptProjectionBestEffort(taskId, messageId, activeAttempt,
                "revoke attempt for retry");

        TaskMsgStatus beforeRetryFailureStatus = taskMsg.getStatus();
        if (!taskMsg.markAsFailed(detail, TaskMsgFinalReason.BUSINESS_FAILED)) {
            logger.warn("Failed to mark task message {} as FAILED before retry reset", messageId);
            return TaskMessageMutationOutcome.rejected();
        }
        traceEventLogger.taskMsgStatusTransition(
                taskMsg,
                activeAttempt,
                beforeRetryFailureStatus,
                taskMsg.getStatus(),
                trigger,
                "TaskManager",
                "task message marked failed before retry reset"
        );
        if (!resultRuntime.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist intermediate failed state for task message {} in task {}", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        taskMsg.incrementRetryCount();
        taskMsg.resetForRetry();
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(resultRuntime.getTask(taskId), true);
        traceEventLogger.taskMsgRetryReset(taskMsg,
                activeAttempt,
                workRetryDelayMillis,
                trigger,
                "TaskManager",
                resetReason);
        if (!resultRuntime.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist retry state for task message {} in task {}", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    private TaskMessageMutationOutcome handleRetryExhaustedFailure(String taskId,
                                                                   TaskMsg taskMsg,
                                                                   TaskMsgAttempt activeAttempt,
                                                                   String detail,
                                                                   String errorCode,
                                                                   Map<String, Object> output) {
        String messageId = taskMsg.getMessageId();
        if (!activeAttempt.markFailed(TaskMsgAttemptFinalReason.BUSINESS_FAILURE, detail, errorCode)) {
            logger.warn("Failed to mark attempt {} as FAILED", activeAttempt.getAttemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        activeAttempt.setOutput(output);
        persistAttemptProjectionBestEffort(taskId, messageId, activeAttempt,
                "mark attempt failure");

        TaskMsgStatus beforeFinalStatus = taskMsg.getStatus();
        if (!taskMsg.markAsFailed(detail, TaskMsgFinalReason.RETRY_EXHAUSTED)) {
            logger.warn("Failed to mark task message {} as FAILED", messageId);
            return TaskMessageMutationOutcome.rejected();
        }
        taskMsg.setErrorCode(errorCode);
        taskMsg.setOutput(output);
        traceEventLogger.taskMsgStatusTransition(
                taskMsg,
                activeAttempt,
                beforeFinalStatus,
                taskMsg.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked failure"
        );

        if (!resultRuntime.updateTaskMessage(taskId, taskMsg)) {
            logger.warn("Failed to persist task message {} for task {}", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        Task updatedTask = resultRuntime.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "retry budget exhausted closed the current attempt");
            publishMessageLogicallyFinal(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message reached stable failure");
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    private void persistAttemptProjectionBestEffort(String taskId,
                                                    String messageId,
                                                    TaskMsgAttempt attempt,
                                                    String action) {
        if (attempt == null) {
            return;
        }
        try {
            if (!resultRuntime.updateTaskMessageAttempt(taskId, messageId, attempt)) {
                logger.warn("Failed to update compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}",
                        taskId, messageId, attempt.getAttemptId(), action);
            }
        } catch (RuntimeException e) {
            logger.warn("Failed to update compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; runtime result convergence continues",
                    taskId, messageId, attempt.getAttemptId(), action, e);
        }
    }

    private void persistAttemptProjectionAddBestEffort(String taskId,
                                                       String messageId,
                                                       TaskMsgAttempt attempt,
                                                       String action) {
        if (attempt == null) {
            return;
        }
        try {
            resultRuntime.addTaskMessageAttempt(taskId, messageId, attempt);
        } catch (RuntimeException e) {
            logger.warn("Failed to add compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; runtime result convergence continues",
                    taskId, messageId, attempt.getAttemptId(), action, e);
        }
    }

    private boolean isCallbackAcceptableMessageState(TaskMsg taskMsg) {
        return taskMsg.getStatus() == TaskMsgStatus.ASSIGNED
                || taskMsg.getStatus() == TaskMsgStatus.RUNNING;
    }

    private ResultApplyOutcome applyWorkResult(Task task,
                                               String taskId,
                                               String messageId,
                                               String leaseToken,
                                               boolean success,
                                               String detail,
                                               String errorCode,
                                               Map<String, Object> output,
                                               boolean retryable,
                                               boolean expired) {
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
        return resultRuntime.applyTaskWorkResult(result);
    }

    private long resolveWorkRetryDelayMillis(Task task, boolean retryable) {
        if (!retryable) {
            return 0L;
        }
        TaskRuntimeRetryPolicy retryPolicy = taskRuntimeRetryPolicyResolver.resolve(task, 1L);
        return retryPolicy.workRetryDelayMillis();
    }

    private String normalizeDispatchSubmitFailureDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "dispatch submit failed before transport delivery";
        }
        return detail;
    }

    void shutdown() {
        // Runtime-owned retry wakeup lifecycle is managed by TaskDispatchRequestService.
    }

    private void requestRetryDispatch(Task task, long workRetryDelayMillis) {
        resultRuntime.requestTaskRetryDispatch(task, workRetryDelayMillis);
    }

    private void publishAttemptClosed(Task task,
                                      TaskMsg taskMsg,
                                      TaskMsgAttempt attempt,
                                      String trigger,
                                      String reason) {
        traceEventLogger.taskMessageAttemptClosed(task, taskMsg, attempt, trigger, "TaskManager", reason);
        resultRuntime.publishTaskMessageAttemptClosed(task, taskMsg, attempt);
    }

    private void publishMessageLogicallyFinal(Task task,
                                              TaskMsg taskMsg,
                                              TaskMsgAttempt attempt,
                                              String trigger,
                                              String reason) {
        traceEventLogger.taskMessageLogicallyFinal(task, taskMsg, attempt, trigger, "TaskManager", reason);
        resultRuntime.publishTaskMessageLogicallyFinal(task, taskMsg);
    }

    static final class TaskMessageMutationOutcome {
        private final boolean accepted;
        private final boolean progressDirty;

        private TaskMessageMutationOutcome(boolean accepted, boolean progressDirty) {
            this.accepted = accepted;
            this.progressDirty = progressDirty;
        }

        static TaskMessageMutationOutcome rejected() {
            return new TaskMessageMutationOutcome(false, false);
        }

        static TaskMessageMutationOutcome acceptedNoop() {
            return new TaskMessageMutationOutcome(true, false);
        }

        static TaskMessageMutationOutcome acceptedDirty() {
            return new TaskMessageMutationOutcome(true, true);
        }

        boolean accepted() {
            return accepted;
        }

        boolean progressDirty() {
            return progressDirty;
        }
    }
}
