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

    private final TaskManager taskManager;
    private final TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver;
    private final TraceEventLogger traceEventLogger;

    TaskResultService(TaskManager taskManager,
                      TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver,
                      TraceEventLogger traceEventLogger) {
        this.taskManager = taskManager;
        this.taskRuntimeRetryPolicyResolver = taskRuntimeRetryPolicyResolver;
        this.traceEventLogger = traceEventLogger;
    }

    TaskMessageMutationOutcome expireTaskMessage(String taskId, String messageId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("EXPIRE_TASK_MESSAGE", "TaskManager",
                "taskId", taskId, "messageId", messageId);
        String expiryDetail = "task message expired";

        Task task = taskManager.getTask(taskId);
        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        if (activeLease == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "no active runtime lease", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        TaskMsg taskMsg = resolveOrRecoverTaskMessageProjection(taskId, messageId, activeLease);
        if (taskMsg == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message projection could not be recovered", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        if (taskMsg.isCompleted()) {
            logger.info("Task message {} of task {} is already in final status {}, skip expiry",
                    messageId, taskId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeLeaseProjectionSupport.ProjectionLeaseSyncResult leaseSync =
                RuntimeLeaseProjectionSupport.recoverAndSynchronizeActiveAttempt(
                        taskManager,
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
            if (!TaskMessageAttemptSupport.projectExpired(activeAttempt, TaskMsgAttemptFinalReason.LEASE_EXPIRED, "task message expired")) {
                LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "attempt could not expire from status "
                        + activeAttempt.getStatus(), 0);
                return TaskMessageMutationOutcome.rejected();
            }
            taskManager.updateTaskMessageAttemptAuditProjection(taskId, messageId, activeAttempt);
        }
        TaskMsgStatus fromStatus = taskMsg.getStatus();
        if (!isExpiryAcceptableMessageState(taskMsg)) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "task message status " + taskMsg.getStatus() + " cannot expire; only ASSIGNED/RUNNING can expire", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        // Once a worker has started executing the message, lease expiry must
        // converge to one final outcome instead of re-queueing the same work.
        boolean retryRequestedByPolicy = fromStatus == TaskMsgStatus.ASSIGNED;
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, retryRequestedByPolicy);
        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, activeLease.leaseToken(),
                false, expiryDetail, null, null, retryRequestedByPolicy, true);
        if (workOutcome.status() == ResultApplyStatus.STALE_LEASE
                || workOutcome.status() == ResultApplyStatus.NO_ACTIVE_LEASE) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "runtime lease rejected expiry result: " + workOutcome.status(), 0);
            return TaskMessageMutationOutcome.rejected();
        }
        boolean retryScheduled = workOutcome.status() == ResultApplyStatus.RETRY_SCHEDULED;
        TaskMsg expiredSummary = summarizeExpired(taskMsg, expiryDetail);
        traceEventLogger.leaseExpired(
                expiredSummary,
                activeAttempt,
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                expiryDetail
        );
        traceEventLogger.taskMsgStatusTransition(
                expiredSummary,
                activeAttempt,
                fromStatus,
                expiredSummary.getStatus(),
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                expiryDetail
        );
        boolean stored = persistTaskMessageProjection(taskId, expiredSummary,
                "persist expiry compatibility summary");
        if (!stored) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message persistence failed", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        Task freshTask = taskManager.getTask(taskId);
        TaskMsg currentSummary = expiredSummary;
        if (retryScheduled) {
            TaskMsg retrySummary = summarizeRetryReset(expiredSummary);
            traceEventLogger.taskMsgRetryReset(retrySummary,
                    activeAttempt,
                    workRetryDelayMillis,
                    "EXPIRE_TASK_MESSAGE", "TaskManager", "lease expired but retry budget allows re-dispatch");
            if (!persistTaskMessageProjection(taskId, retrySummary,
                    "persist expiry retry-reset compatibility summary")) {
                LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message retry reset persistence failed", 0);
                return TaskMessageMutationOutcome.rejected();
            }
            currentSummary = retrySummary;
        }
        if (freshTask != null && activeAttempt != null) {
            publishAttemptClosed(freshTask, currentSummary, activeAttempt,
                    "EXPIRE_TASK_MESSAGE", retryScheduled
                            ? "lease expiry closed the current attempt before re-dispatch"
                            : expiryDetail);
        }
        if (!retryScheduled && freshTask != null) {
            publishMessageLogicallyFinal(freshTask, expiredSummary, activeAttempt,
                    "EXPIRE_TASK_MESSAGE", expiryDetail);
        }
        LogUtils.logOperationSuccess(expiryDetail, 0);
        if (retryScheduled) {
            Task updatedTask = taskManager.getTask(taskId);
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
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            logger.warn("Cannot handle task message result because task {} was not found", taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        TaskMsg taskMsg = resolveOrRecoverTaskMessageProjection(taskId, messageId, activeLease);
        if (taskMsg == null) {
            logger.warn("Cannot handle task message result because msg {} was not found in task {} and no runtime projection could be recovered",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        if (taskMsg.isCompleted()) {
            traceEventLogger.callbackIgnoredDuplicate(taskMsg,
                    "task message already final in status " + taskMsg.getStatus());
            logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                    messageId, taskId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.acceptedNoop();
        }

        if (task.getStatus().isFinal()) {
            traceEventLogger.callbackIgnoredLate(taskMsg,
                    "task already terminal in status " + task.getStatus());
            logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                    taskId, messageId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.acceptedNoop();
        }

        if (activeLease == null) {
            traceEventLogger.callbackRejectedNoActiveLease(
                    taskMsg,
                    "callback arrived without any active runtime lease"
            );
            logger.error("Cannot handle task message result because msg {} in task {} has no active runtime lease", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeLeaseProjectionSupport.ProjectionLeaseSyncResult leaseSync =
                RuntimeLeaseProjectionSupport.recoverAndSynchronizeActiveAttempt(
                        taskManager,
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
                    "callback arrived while message status is " + taskMsg.getStatus());
            logger.error("Cannot handle task message result because msg {} in task {} is in invalid callback state {}",
                    messageId, taskId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.rejected();
        }

        traceEventLogger.callbackAccepted(
                taskMsg,
                success ? "success callback received" : "failure callback received");

        if (!TaskMessageAttemptSupport.projectCallbackAccepted(
                activeAttempt,
                taskManager.getTaskMessageLeaseSeconds())) {
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
                taskManager.getActiveLease(taskId, messageId).orElse(null));
        if (taskMsg == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} was not found in task {}",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
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
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
            requestRetryDispatch(updatedTask, workRetryDelayMillis);
        }
        return retryOutcome;
    }

    private TaskMsg resolveOrRecoverTaskMessageProjection(String taskId,
                                                          String messageId,
                                                          ActiveLeaseRecord activeLease) {
        TaskMsg taskMsg = taskManager.getStoredTaskMessageProjection(taskId, messageId);
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
        return recovered;
    }

    private TaskMsgAttempt resolveOrRecoverDispatchAttemptProjection(TaskMsg taskMsg,
                                                                     ActiveLeaseRecord activeLease,
                                                                     TaskDispatchBinding dispatchBinding) {
        TaskMsgAttempt activeAttempt = taskManager.getLatestActiveAttemptProjection(taskMsg.getTaskId(), taskMsg.getMessageId());
        if (activeAttempt != null) {
            return activeAttempt;
        }
        if (activeLease == null || dispatchBinding == null) {
            return null;
        }
        TaskMsgAttempt recoveredAttempt = TaskMessageAttemptSupport.buildDispatchedProjection(
                dispatchBinding.attemptId(),
                taskMsg.getTaskId(),
                taskMsg.getMessageId(),
                dispatchBinding.attemptNo(),
                dispatchBinding.workerId(),
                dispatchBinding.workerContextId(),
                dispatchBinding.batchId(),
                activeLease.leaseExpireAt()
        );
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
        TaskMsg successBase = taskMsg;
        if (taskMsg.getStatus() == TaskMsgStatus.ASSIGNED) {
            TaskMsgStatus beforeRunningStatus = taskMsg.getStatus();
            TaskMsg runningView = summarizeRunning(taskMsg);
            traceEventLogger.taskMsgStatusTransition(
                    runningView,
                    activeAttempt,
                    beforeRunningStatus,
                    runningView.getStatus(),
                    "HANDLE_TASK_MESSAGE_RESULT",
                    "TaskManager",
                    "task message entered running from callback"
            );
            successBase = runningView;
        }
        TaskMsg successSummary = summarizeSuccess(successBase, detail, output);
        TaskMsgStatus beforeFinalStatus = successBase.getStatus();
        if (!TaskMessageAttemptSupport.projectSucceeded(activeAttempt, output)) {
            logger.warn("Failed to mark attempt {} as SUCCEEDED", activeAttempt.getAttemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        traceEventLogger.taskMsgStatusTransition(
                successSummary,
                activeAttempt,
                beforeFinalStatus,
                successSummary.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked success"
        );
        persistAttemptProjectionBestEffort(taskId, messageId, activeAttempt,
                "mark attempt success");
        if (!persistTaskMessageProjection(taskId, successSummary,
                "persist success compatibility summary")) {
            logger.warn("Failed to persist task message {} for task {}", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, successSummary, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message attempt succeeded");
            publishMessageLogicallyFinal(updatedTask, successSummary, activeAttempt,
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
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(taskManager.getTask(taskId), true);
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
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, taskMsg, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "retryable failure closed the current attempt");
        }
        updatedTask = taskManager.getTask(taskId);
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
        if (!TaskMessageAttemptSupport.projectRetryRevoked(activeAttempt, detail, errorCode)) {
            logger.warn("Failed to revoke attempt {} for retry", activeAttempt.getAttemptId());
            return TaskMessageMutationOutcome.rejected();
        }
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

        if (!ensureRetryResetCompatibilityView(taskMsg, activeAttempt)) {
            logger.warn("Failed to recover retry-reset compatibility view for task message {} from status {}",
                    messageId, taskMsg.getStatus());
            return TaskMessageMutationOutcome.rejected();
        }

        TaskMsgStatus beforeRetryFailureStatus = taskMsg.getStatus();
        TaskMsg failedView = summarizeBusinessFailure(taskMsg, detail, errorCode);
        traceEventLogger.taskMsgStatusTransition(
                failedView,
                activeAttempt,
                beforeRetryFailureStatus,
                failedView.getStatus(),
                trigger,
                "TaskManager",
                "task message marked failed before retry reset"
        );
        TaskMsg retrySummary = summarizeRetryReset(failedView);
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(taskManager.getTask(taskId), true);
        traceEventLogger.taskMsgRetryReset(retrySummary,
                activeAttempt,
                workRetryDelayMillis,
                trigger,
                "TaskManager",
                resetReason);
        if (!persistTaskMessageProjection(taskId, retrySummary,
                "persist retry-reset compatibility summary")) {
            logger.warn("Failed to persist retry state for task message {} in task {}", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    private boolean ensureRetryResetCompatibilityView(TaskMsg taskMsg,
                                                      TaskMsgAttempt activeAttempt) {
        if (taskMsg == null) {
            return false;
        }
        if (taskMsg.getStatus() == TaskMsgStatus.ASSIGNED || taskMsg.getStatus() == TaskMsgStatus.RUNNING) {
            return true;
        }
        if (taskMsg.getStatus() != TaskMsgStatus.INIT || activeAttempt == null) {
            return false;
        }
        taskMsg.applyLatestAttemptProjection(
                activeAttempt.getAttemptId(),
                activeAttempt.getWorkerId(),
                activeAttempt.getWorkerContextId(),
                activeAttempt.getBatchId()
        );
        return taskMsg.markAsAssigned();
    }

    private TaskMessageMutationOutcome handleRetryExhaustedFailure(String taskId,
                                                                   TaskMsg taskMsg,
                                                                   TaskMsgAttempt activeAttempt,
                                                                   String detail,
                                                                   String errorCode,
                                                                   Map<String, Object> output) {
        String messageId = taskMsg.getMessageId();
        if (!TaskMessageAttemptSupport.projectFailed(
                activeAttempt,
                TaskMsgAttemptFinalReason.BUSINESS_FAILURE,
                detail,
                errorCode,
                output)) {
            logger.warn("Failed to mark attempt {} as FAILED", activeAttempt.getAttemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        persistAttemptProjectionBestEffort(taskId, messageId, activeAttempt,
                "mark attempt failure");

        TaskMsgStatus beforeFinalStatus = taskMsg.getStatus();
        TaskMsg failureSummary = summarizeRetryExhaustedFailure(taskMsg, detail, errorCode, output);
        traceEventLogger.taskMsgStatusTransition(
                failureSummary,
                activeAttempt,
                beforeFinalStatus,
                failureSummary.getStatus(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked failure"
        );

        if (!persistTaskMessageProjection(taskId, failureSummary,
                "persist exhausted-failure compatibility summary")) {
            logger.warn("Failed to persist task message {} for task {}", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, failureSummary, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "retry budget exhausted closed the current attempt");
            publishMessageLogicallyFinal(updatedTask, failureSummary, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "task message reached stable failure");
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    private TaskMsg summarizeRunning(TaskMsg base) {
        TaskMsg running = copyTaskMessage(base);
        running.setStatus(TaskMsgStatus.RUNNING);
        return running;
    }

    private TaskMsg summarizeSuccess(TaskMsg base,
                                     String detail,
                                     Map<String, Object> output) {
        TaskMsg success = copyTaskMessage(base);
        success.forceFinalize(TaskMsgStatus.SUCCESS, TaskMsgFinalReason.BUSINESS_SUCCESS, detail);
        success.setOutput(output);
        success.setErrorCode(null);
        return success;
    }

    private TaskMsg summarizeBusinessFailure(TaskMsg base,
                                             String detail,
                                             String errorCode) {
        TaskMsg failed = copyTaskMessage(base);
        failed.forceFinalize(TaskMsgStatus.FAILED, TaskMsgFinalReason.BUSINESS_FAILED, detail);
        failed.setErrorCode(errorCode);
        failed.setOutput(null);
        return failed;
    }

    private TaskMsg summarizeExpired(TaskMsg base, String detail) {
        TaskMsg expired = copyTaskMessage(base);
        expired.forceFinalize(TaskMsgStatus.EXPIRED, TaskMsgFinalReason.LEASE_EXPIRED, detail);
        expired.setOutput(null);
        return expired;
    }

    private TaskMsg summarizeRetryReset(TaskMsg failedView) {
        TaskMsg retrySummary = copyTaskMessage(failedView);
        retrySummary.setRetryCount(retrySummary.getRetryCount() + 1);
        retrySummary.setStatus(TaskMsgStatus.INIT);
        retrySummary.clearLatestAttemptProjection();
        retrySummary.setStartTime(null);
        retrySummary.setCompleteTime(null);
        retrySummary.setErrorMessage(null);
        retrySummary.setErrorCode(null);
        retrySummary.setOutput(null);
        retrySummary.setFinalReason(null);
        return retrySummary;
    }

    private TaskMsg summarizeRetryExhaustedFailure(TaskMsg base,
                                                   String detail,
                                                   String errorCode,
                                                   Map<String, Object> output) {
        TaskMsg failure = copyTaskMessage(base);
        failure.forceFinalize(TaskMsgStatus.FAILED, TaskMsgFinalReason.RETRY_EXHAUSTED, detail);
        failure.setErrorCode(errorCode);
        failure.setOutput(output);
        return failure;
    }

    private TaskMsg copyTaskMessage(TaskMsg source) {
        TaskMsg copy = new TaskMsg(source.getMessageId(), source.getTaskId(), source.getInput(), source.getPayloadRef());
        copy.setStatus(source.getStatus());
        copy.setAssignedTime(source.getAssignedTime());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setStartTime(source.getStartTime());
        copy.setCompleteTime(source.getCompleteTime());
        copy.setRetryCount(source.getRetryCount());
        copy.setMaxRetryCount(source.getMaxRetryCount());
        copy.setErrorMessage(source.getErrorMessage());
        copy.setErrorCode(source.getErrorCode());
        copy.setFinalReason(source.getFinalReason());
        copy.setOutput(source.getOutput());
        copy.applyLatestAttemptProjection(
                source.latestAttemptId(),
                source.getLatestAttemptWorkerId(),
                source.getLatestAttemptWorkerContextId(),
                source.getLatestAttemptBatchId()
        );
        return copy;
    }

    private boolean persistTaskMessageProjection(String taskId,
                                                 TaskMsg taskMsg,
                                                 String action) {
        if (taskMsg == null) {
            return false;
        }
        if (taskManager.updateTaskMessageProjection(taskId, taskMsg)) {
            return true;
        }
        try {
            taskManager.addTaskMessageProjection(taskId, taskMsg);
            return true;
        } catch (RuntimeException e) {
            logger.warn("Failed to upsert compatibility task message projection for taskId={}, messageId={} during {}",
                    taskId, taskMsg.getMessageId(), action, e);
            return false;
        }
    }

    private void persistAttemptProjectionBestEffort(String taskId,
                                                    String messageId,
                                                    TaskMsgAttempt attempt,
                                                    String action) {
        if (attempt == null) {
            return;
        }
        try {
            if (!taskManager.updateTaskMessageAttemptAuditProjection(taskId, messageId, attempt)) {
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
            taskManager.addTaskMessageAttemptAuditProjection(taskId, messageId, attempt);
        } catch (RuntimeException e) {
            logger.warn("Failed to add compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; runtime result convergence continues",
                    taskId, messageId, attempt.getAttemptId(), action, e);
        }
    }

    private boolean isCallbackAcceptableMessageState(TaskMsg taskMsg) {
        return taskMsg.getStatus() == TaskMsgStatus.ASSIGNED
                || taskMsg.getStatus() == TaskMsgStatus.RUNNING;
    }

    private boolean isExpiryAcceptableMessageState(TaskMsg taskMsg) {
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
        return taskManager.applyTaskWorkResult(result);
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
        taskManager.requestTaskRetryDispatch(task, workRetryDelayMillis);
    }

    private void publishAttemptClosed(Task task,
                                      TaskMsg taskMsg,
                                      TaskMsgAttempt attempt,
                                      String trigger,
                                      String reason) {
        traceEventLogger.taskMessageAttemptClosed(task, taskMsg, attempt, trigger, "TaskManager", reason);
        taskManager.publishTaskMessageAttemptClosed(
                task,
                TaskMessageAttemptClosedEvent.from(taskMsg.getTaskId(), taskMsg.getMessageId(), attempt)
        );
    }

    private void publishMessageLogicallyFinal(Task task,
                                              TaskMsg taskMsg,
                                              TaskMsgAttempt attempt,
                                              String trigger,
                                              String reason) {
        traceEventLogger.taskMessageLogicallyFinal(task, taskMsg, attempt, trigger, "TaskManager", reason);
        taskManager.publishTaskMessageLogicallyFinal(task, TaskMessageLogicallyFinalEvent.from(taskMsg));
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


