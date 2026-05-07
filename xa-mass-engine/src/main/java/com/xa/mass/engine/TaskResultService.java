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
import com.xa.mass.engine.util.TraceEventLogger.TaskMessageTraceView;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

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
        ActiveRuntimeProjection activeProjection = buildActiveRuntimeProjection(
                taskId,
                messageId,
                taskManager.getStoredTaskMessageProjection(taskId, messageId),
                activeLease,
                "EXPIRE_TASK_MESSAGE",
                "runtime active lease synchronized compatibility projection"
        );
        if (activeProjection == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "task message projection could not be recovered", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeMessageView messageState = activeProjection.messageState();
        if (messageState.isCompleted()) {
            logger.info("Task message {} of task {} is already in final status {}, skip expiry",
                    messageId, taskId, messageState.status());
            return TaskMessageMutationOutcome.rejected();
        }
        TaskMsgAttempt activeAttempt = activeProjection.activeAttempt();
        TaskMsgStatus fromStatus = messageState.status();
        if (!isExpiryAcceptableMessageState(messageState.status())) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "task message status " + messageState.status() + " cannot expire; only ASSIGNED/RUNNING can expire", 0);
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
        if (!TaskMessageAttemptSupport.projectExpired(activeAttempt, TaskMsgAttemptFinalReason.LEASE_EXPIRED, "task message expired")) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "attempt could not expire from status "
                    + activeAttempt.getStatus(), 0);
            return TaskMessageMutationOutcome.rejected();
        }
        persistAttemptProjectionUpsertBestEffort(taskId, messageId, activeAttempt,
                "mark attempt expired");
        boolean retryScheduled = workOutcome.status() == ResultApplyStatus.RETRY_SCHEDULED;
        RuntimeMessageView expiredSummary = summarizeExpired(messageState, expiryDetail);
        traceEventLogger.leaseExpired(
                expiredSummary.toTraceView(),
                activeAttempt,
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                expiryDetail
        );
        traceEventLogger.taskMsgStatusTransition(
                expiredSummary.toTraceView(),
                activeAttempt,
                fromStatus,
                expiredSummary.status(),
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
        RuntimeMessageView currentSummary = expiredSummary;
        if (retryScheduled) {
            RuntimeMessageView retrySummary = summarizeRetryReset(expiredSummary);
            traceEventLogger.taskMsgRetryReset(retrySummary.toTraceView(),
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
        TaskMsg storedProjection = taskManager.getStoredTaskMessageProjection(taskId, messageId);

        if (storedProjection != null && storedProjection.isCompleted()) {
            traceEventLogger.callbackIgnoredDuplicate(TaskMessageTraceView.from(storedProjection),
                    "task message already final in status " + storedProjection.getStatus());
            logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                    messageId, taskId, storedProjection.getStatus());
            return TaskMessageMutationOutcome.acceptedNoop();
        }

        if (task.getStatus().isFinal()) {
            TaskMessageTraceView lateView = resolveTraceTaskMessageView(taskId, messageId, storedProjection, activeLease);
            traceEventLogger.callbackIgnoredLate(lateView,
                    "task already terminal in status " + task.getStatus());
            logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                    taskId, messageId, lateView.status());
            return TaskMessageMutationOutcome.acceptedNoop();
        }

        if (activeLease == null) {
            TaskMessageTraceView noLeaseView = resolveTraceTaskMessageView(taskId, messageId, storedProjection, null);
            traceEventLogger.callbackRejectedNoActiveLease(
                    noLeaseView,
                    "callback arrived without any active runtime lease"
            );
            logger.error("Cannot handle task message result because msg {} in task {} has no active runtime lease", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        ActiveRuntimeProjection activeProjection = buildActiveRuntimeProjection(
                taskId,
                messageId,
                storedProjection,
                activeLease,
                "HANDLE_TASK_MESSAGE_RESULT",
                "runtime active lease synchronized compatibility projection"
        );
        if (activeProjection == null) {
            logger.warn("Cannot handle task message result because msg {} was not found in task {} and no runtime projection could be recovered",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeMessageView messageState = activeProjection.messageState();
        TaskMsgAttempt activeAttempt = activeProjection.activeAttempt();
        if (!isCallbackAcceptableMessageState(messageState.status())) {
            traceEventLogger.callbackRejectedInvalidState(messageState.toTraceView(),
                    "callback arrived while message status is " + messageState.status());
            logger.error("Cannot handle task message result because msg {} in task {} is in invalid callback state {}",
                    messageId, taskId, messageState.status());
            return TaskMessageMutationOutcome.rejected();
        }

        traceEventLogger.callbackAccepted(
                messageState.toTraceView(),
                success ? "success callback received" : "failure callback received");

        if (!TaskMessageAttemptSupport.projectCallbackAccepted(
                activeAttempt,
                taskManager.getTaskMessageLeaseSeconds())) {
            logger.warn("Cannot advance attempt {} for task message {} from status {}",
                    activeAttempt.getAttemptId(), messageId, activeAttempt.getStatus());
            return TaskMessageMutationOutcome.rejected();
        }

        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, activeLease.leaseToken(),
                success, detail, errorCode, output, !success, false);
        if (workOutcome.status() == ResultApplyStatus.STALE_LEASE
                || workOutcome.status() == ResultApplyStatus.NO_ACTIVE_LEASE) {
            logger.warn("Rejecting result for task message {} because runtime lease rejected the result with {}",
                    messageId, workOutcome.status());
            return TaskMessageMutationOutcome.rejected();
        }

        if (success) {
            return handleSuccess(taskId, messageState, activeAttempt, detail, output);
        }
        if (workOutcome.status() == ResultApplyStatus.RETRY_SCHEDULED) {
            return handleRetryableFailure(taskId, messageState, activeAttempt, detail, errorCode, output);
        }
        return handleRetryExhaustedFailure(taskId, messageState, activeAttempt, detail, errorCode, output);
    }

    TaskMessageMutationOutcome compensateDispatchSubmitFailure(Task task,
                                                              TaskDispatchBinding dispatchBinding,
                                                              String detail) {
        if (task == null || dispatchBinding == null) {
            return TaskMessageMutationOutcome.rejected();
        }

        String taskId = task.getTid();
        String messageId = dispatchBinding.messageId();
        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        if (activeLease == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no active runtime lease",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        ActiveRuntimeProjection activeProjection = buildActiveRuntimeProjection(
                taskId,
                messageId,
                taskManager.getStoredTaskMessageProjection(taskId, messageId),
                activeLease,
                "COMPENSATE_DISPATCH_SUBMIT_FAILURE",
                "runtime active lease synchronized compatibility projection"
        );
        if (activeProjection == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} was not found in task {}",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeMessageView messageState = activeProjection.messageState();

        TaskMsgAttempt activeAttempt = resolveOrRecoverDispatchAttemptProjection(messageState, activeLease, dispatchBinding);
        if (activeAttempt == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no recoverable attempt projection",
                    messageId, taskId);
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

        RuntimeMessageView retrySummary = resetForRetryWithoutPublishingAttemptClosure(
                taskId,
                messageState,
                activeAttempt,
                normalizedDetail,
                DISPATCH_SUBMIT_FAILED_ERROR_CODE,
                "COMPENSATE_DISPATCH_SUBMIT_FAILURE",
                "dispatch submit failed before transport delivery"
        );
        if (retrySummary == null) {
            return TaskMessageMutationOutcome.rejected();
        }

        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, true);
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
            requestRetryDispatch(updatedTask, workRetryDelayMillis);
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    private TaskMessageTraceView resolveTraceTaskMessageView(String taskId,
                                                             String messageId,
                                                             TaskMsg storedProjection,
                                                             ActiveLeaseRecord activeLease) {
        if (storedProjection != null) {
            return TaskMessageTraceView.from(storedProjection);
        }
        RuntimeMessageView recovered = materializeRuntimeMessageView(taskId, messageId, storedProjection, activeLease);
        return recovered != null
                ? recovered.toTraceView()
                : new TaskMessageTraceView(taskId, messageId, null, null, null, null, TaskMsgStatus.INIT, null, 0, null);
    }

    private ActiveRuntimeProjection buildActiveRuntimeProjection(String taskId,
                                                                 String messageId,
                                                                 TaskMsg storedProjection,
                                                                 ActiveLeaseRecord activeLease,
                                                                 String trigger,
                                                                 String reason) {
        RuntimeMessageView activeView = materializeRuntimeMessageView(taskId, messageId, storedProjection, activeLease);
        if (activeView == null) {
            return null;
        }
        TaskMsgAttempt activeAttempt = recoverActiveAttemptProjection(activeView, activeLease, null, null);
        if (activeAttempt == null) {
            traceEventLogger.callbackRejectedNoActiveAttempt(
                    taskId,
                    messageId,
                    activeView.status(),
                    "callback arrived without any recoverable active attempt"
            );
            return null;
        }
        TaskMsgStatus originalStatus = storedProjection != null && storedProjection.getStatus() != null
                ? storedProjection.getStatus()
                : TaskMsgStatus.INIT;
        if (originalStatus == TaskMsgStatus.INIT && activeView.status() == TaskMsgStatus.ASSIGNED) {
            traceEventLogger.taskMsgStatusTransition(
                    activeView.toTraceView(),
                    activeAttempt,
                    originalStatus,
                    activeView.status(),
                    trigger,
                    "TaskManager",
                    reason
            );
        }
        return new ActiveRuntimeProjection(activeView, activeAttempt);
    }

    private RuntimeMessageView materializeRuntimeMessageView(String taskId,
                                                             String messageId,
                                                             TaskMsg storedProjection,
                                                             ActiveLeaseRecord activeLease) {
        if (storedProjection == null && activeLease == null) {
            return null;
        }
        RuntimeMessageView baseView = storedProjection != null
                ? RuntimeMessageView.from(storedProjection)
                : RuntimeMessageView.synthetic(taskId, messageId);
        return baseView.overlayActiveLease(activeLease);
    }

    private TaskMsgAttempt recoverActiveAttemptProjection(RuntimeMessageView taskMsg,
                                                          ActiveLeaseRecord activeLease,
                                                          String preferredAttemptId,
                                                          Integer preferredAttemptNo) {
        if (taskMsg == null || activeLease == null) {
            return null;
        }
        int attemptNo = preferredAttemptNo != null && preferredAttemptNo > 0
                ? preferredAttemptNo
                : Math.max(1, activeLease.retryCount() + 1);
        String attemptId = preferredAttemptId;
        if (attemptId == null || attemptId.isBlank()) {
            attemptId = taskMsg.latestAttemptId();
        }
        if (attemptId == null || attemptId.isBlank()) {
            TaskMsgAttempt latestAuditView = taskManager.getLatestTaskMessageAttemptAuditView(
                    taskMsg.taskId(),
                    taskMsg.messageId()
            );
            if (isCompatibleRuntimeAttempt(latestAuditView, activeLease, attemptNo)) {
                attemptId = latestAuditView.getAttemptId();
            }
        }
        if (attemptId == null || attemptId.isBlank()) {
            attemptId = "recovered-attempt-" + taskMsg.messageId() + "-" + attemptNo + "-" + UUID.randomUUID();
        }
        return TaskMessageAttemptSupport.buildDispatchedProjection(
                taskMsg.taskId(),
                taskMsg.messageId(),
                activeLease,
                attemptId,
                attemptNo
        );
    }

    private boolean isCompatibleRuntimeAttempt(TaskMsgAttempt attempt,
                                               ActiveLeaseRecord activeLease,
                                               int attemptNo) {
        if (attempt == null || activeLease == null || attempt.getAttemptId() == null || attempt.getAttemptId().isBlank()) {
            return false;
        }
        if (attempt.getAttemptNo() != attemptNo) {
            return false;
        }
        if (!java.util.Objects.equals(attempt.getWorkerId(), activeLease.workerId())) {
            return false;
        }
        if (!java.util.Objects.equals(attempt.getWorkerContextId(), activeLease.workerContextId())) {
            return false;
        }
        return java.util.Objects.equals(attempt.getBatchId(), activeLease.batchId());
    }

    private TaskMsgAttempt resolveOrRecoverDispatchAttemptProjection(RuntimeMessageView taskMsg,
                                                                     ActiveLeaseRecord activeLease,
                                                                     TaskDispatchBinding dispatchBinding) {
        if (taskMsg == null || activeLease == null || dispatchBinding == null) {
            return null;
        }
        if (dispatchBinding.attemptId() == null || dispatchBinding.attemptId().isBlank()) {
            return null;
        }
        return recoverActiveAttemptProjection(taskMsg, activeLease, dispatchBinding.attemptId(), dispatchBinding.attemptNo());
    }

    private TaskMessageMutationOutcome handleSuccess(String taskId,
                                                     RuntimeMessageView taskMsg,
                                                     TaskMsgAttempt activeAttempt,
                                                     String detail,
                                                     Map<String, Object> output) {
        String messageId = taskMsg.messageId();
        RuntimeMessageView successBase = taskMsg;
        if (taskMsg.status() == TaskMsgStatus.ASSIGNED) {
            TaskMsgStatus beforeRunningStatus = taskMsg.status();
            RuntimeMessageView runningView = summarizeRunning(successBase);
            traceEventLogger.taskMsgStatusTransition(
                    runningView.toTraceView(),
                    activeAttempt,
                    beforeRunningStatus,
                    runningView.status(),
                    "HANDLE_TASK_MESSAGE_RESULT",
                    "TaskManager",
                    "task message entered running from callback"
            );
            successBase = runningView;
        }
        RuntimeMessageView successSummary = summarizeSuccess(successBase, detail, output);
        TaskMsgStatus beforeFinalStatus = successBase.status();
        if (!TaskMessageAttemptSupport.projectSucceeded(activeAttempt, output)) {
            logger.warn("Failed to mark attempt {} as SUCCEEDED", activeAttempt.getAttemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        traceEventLogger.taskMsgStatusTransition(
                successSummary.toTraceView(),
                activeAttempt,
                beforeFinalStatus,
                successSummary.status(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked success"
        );
        persistAttemptProjectionUpsertBestEffort(taskId, messageId, activeAttempt,
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
                                                              RuntimeMessageView taskMsg,
                                                              TaskMsgAttempt activeAttempt,
                                                              String detail,
                                                              String errorCode,
                                                              Map<String, Object> output) {
        RuntimeMessageView retrySummary = resetForRetryWithoutPublishingAttemptClosure(
                taskId,
                taskMsg,
                activeAttempt,
                detail,
                errorCode,
                "HANDLE_TASK_MESSAGE_RESULT",
                "retry budget allows re-dispatch"
        );
        if (retrySummary == null) {
            return TaskMessageMutationOutcome.rejected();
        }
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(taskManager.getTask(taskId), true);
        Task updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null) {
            publishAttemptClosed(updatedTask, retrySummary, activeAttempt,
                    "HANDLE_TASK_MESSAGE_RESULT", "retryable failure closed the current attempt");
        }
        updatedTask = taskManager.getTask(taskId);
        if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
            requestRetryDispatch(updatedTask, workRetryDelayMillis);
        }
        return TaskMessageMutationOutcome.acceptedDirty();
    }

    private RuntimeMessageView resetForRetryWithoutPublishingAttemptClosure(String taskId,
                                                                            RuntimeMessageView taskMsg,
                                                                            TaskMsgAttempt activeAttempt,
                                                                            String detail,
                                                                            String errorCode,
                                                                            String trigger,
                                                                            String resetReason) {
        String messageId = taskMsg.messageId();
        TaskMsgAttemptStatus beforeRevokedStatus = activeAttempt.getStatus();
        if (!TaskMessageAttemptSupport.projectRetryRevoked(activeAttempt, detail, errorCode)) {
            logger.warn("Failed to revoke attempt {} for retry", activeAttempt.getAttemptId());
            return null;
        }
        traceEventLogger.taskMsgAttemptStatusTransition(
                activeAttempt,
                beforeRevokedStatus,
                activeAttempt.getStatus(),
                trigger,
                "TaskManager",
                resetReason
        );
        persistAttemptProjectionUpsertBestEffort(taskId, messageId, activeAttempt,
                "revoke attempt for retry");

        RuntimeMessageView retryBase = buildRetryResetCompatibilityBaseView(taskMsg, activeAttempt);
        if (retryBase == null) {
            logger.warn("Failed to recover retry-reset compatibility view for task message {} from status {}",
                    messageId, taskMsg != null ? taskMsg.status() : null);
            return null;
        }

        TaskMsgStatus beforeRetryFailureStatus = retryBase.status();
        RuntimeMessageView failedView = summarizeBusinessFailure(retryBase, detail, errorCode);
        traceEventLogger.taskMsgStatusTransition(
                failedView.toTraceView(),
                activeAttempt,
                beforeRetryFailureStatus,
                failedView.status(),
                trigger,
                "TaskManager",
                "task message marked failed before retry reset"
        );
        RuntimeMessageView retrySummary = summarizeRetryReset(failedView);
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(taskManager.getTask(taskId), true);
        traceEventLogger.taskMsgRetryReset(retrySummary.toTraceView(),
                activeAttempt,
                workRetryDelayMillis,
                trigger,
                "TaskManager",
                resetReason);
        if (!persistTaskMessageProjection(taskId, retrySummary,
                "persist retry-reset compatibility summary")) {
            logger.warn("Failed to persist retry state for task message {} in task {}", messageId, taskId);
            return null;
        }
        return retrySummary;
    }

    private RuntimeMessageView buildRetryResetCompatibilityBaseView(RuntimeMessageView taskMsg,
                                                                    TaskMsgAttempt activeAttempt) {
        if (taskMsg == null) {
            return null;
        }
        if (taskMsg.status() == TaskMsgStatus.ASSIGNED || taskMsg.status() == TaskMsgStatus.RUNNING) {
            return taskMsg;
        }
        if (taskMsg.status() != TaskMsgStatus.INIT || activeAttempt == null) {
            return null;
        }
        TaskMsg assignedView = taskMsg.toCompatibilityProjection();
        assignedView.applyLatestAttemptProjection(
                activeAttempt.getAttemptId(),
                activeAttempt.getWorkerId(),
                activeAttempt.getWorkerContextId(),
                activeAttempt.getBatchId()
        );
        if (!assignedView.markAsAssigned()) {
            return null;
        }
        return RuntimeMessageView.from(assignedView);
    }

    private TaskMessageMutationOutcome handleRetryExhaustedFailure(String taskId,
                                                                   RuntimeMessageView taskMsg,
                                                                   TaskMsgAttempt activeAttempt,
                                                                   String detail,
                                                                   String errorCode,
                                                                   Map<String, Object> output) {
        String messageId = taskMsg.messageId();
        if (!TaskMessageAttemptSupport.projectFailed(
                activeAttempt,
                TaskMsgAttemptFinalReason.BUSINESS_FAILURE,
                detail,
                errorCode,
                output)) {
            logger.warn("Failed to mark attempt {} as FAILED", activeAttempt.getAttemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        persistAttemptProjectionUpsertBestEffort(taskId, messageId, activeAttempt,
                "mark attempt failure");

        TaskMsgStatus beforeFinalStatus = taskMsg.status();
        RuntimeMessageView failureSummary = summarizeRetryExhaustedFailure(taskMsg, detail, errorCode, output);
        traceEventLogger.taskMsgStatusTransition(
                failureSummary.toTraceView(),
                activeAttempt,
                beforeFinalStatus,
                failureSummary.status(),
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

    private RuntimeMessageView summarizeRunning(RuntimeMessageView base) {
        TaskMsg running = copyTaskMessage(base.toCompatibilityProjection());
        running.setStatus(TaskMsgStatus.RUNNING);
        return RuntimeMessageView.from(running);
    }

    private RuntimeMessageView summarizeSuccess(RuntimeMessageView base,
                                                String detail,
                                                Map<String, Object> output) {
        TaskMsg success = copyTaskMessage(base.toCompatibilityProjection());
        success.forceFinalize(TaskMsgStatus.SUCCESS, TaskMsgFinalReason.BUSINESS_SUCCESS, detail);
        success.setOutput(output);
        success.setErrorCode(null);
        return RuntimeMessageView.from(success);
    }

    private RuntimeMessageView summarizeBusinessFailure(RuntimeMessageView base,
                                                        String detail,
                                                        String errorCode) {
        TaskMsg failed = copyTaskMessage(base.toCompatibilityProjection());
        failed.forceFinalize(TaskMsgStatus.FAILED, TaskMsgFinalReason.BUSINESS_FAILED, detail);
        failed.setErrorCode(errorCode);
        failed.setOutput(null);
        return RuntimeMessageView.from(failed);
    }

    private RuntimeMessageView summarizeExpired(RuntimeMessageView base, String detail) {
        TaskMsg expired = copyTaskMessage(base.toCompatibilityProjection());
        expired.forceFinalize(TaskMsgStatus.EXPIRED, TaskMsgFinalReason.LEASE_EXPIRED, detail);
        expired.setOutput(null);
        return RuntimeMessageView.from(expired);
    }

    private RuntimeMessageView summarizeRetryReset(RuntimeMessageView failedView) {
        TaskMsg retrySummary = copyTaskMessage(failedView.toCompatibilityProjection());
        retrySummary.setRetryCount(retrySummary.getRetryCount() + 1);
        retrySummary.setStatus(TaskMsgStatus.INIT);
        retrySummary.clearLatestAttemptProjection();
        retrySummary.setStartTime(null);
        retrySummary.setCompleteTime(null);
        retrySummary.setErrorMessage(null);
        retrySummary.setErrorCode(null);
        retrySummary.setOutput(null);
        retrySummary.setFinalReason(null);
        return RuntimeMessageView.from(retrySummary);
    }

    private RuntimeMessageView summarizeRetryExhaustedFailure(RuntimeMessageView base,
                                                              String detail,
                                                              String errorCode,
                                                              Map<String, Object> output) {
        TaskMsg failure = copyTaskMessage(base.toCompatibilityProjection());
        failure.forceFinalize(TaskMsgStatus.FAILED, TaskMsgFinalReason.RETRY_EXHAUSTED, detail);
        failure.setErrorCode(errorCode);
        failure.setOutput(output);
        return RuntimeMessageView.from(failure);
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
                                                 RuntimeMessageView taskMsg,
                                                 String action) {
        if (taskMsg == null) {
            return false;
        }
        return persistTaskMessageProjection(taskId, taskMsg.toCompatibilityProjection(), action);
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

    private void persistAttemptProjectionUpsertBestEffort(String taskId,
                                                          String messageId,
                                                          TaskMsgAttempt attempt,
                                                          String action) {
        if (attempt == null) {
            return;
        }
        try {
            if (taskManager.updateTaskMessageAttemptAuditProjection(taskId, messageId, attempt)) {
                return;
            }
        } catch (RuntimeException e) {
            logger.warn("Failed to update compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; trying bounded reinsert",
                    taskId, messageId, attempt.getAttemptId(), action, e);
        }
        try {
            taskManager.addTaskMessageAttemptAuditProjection(taskId, messageId, attempt);
        } catch (RuntimeException e) {
            logger.warn("Failed to add compatibility attempt projection for taskId={}, messageId={}, attemptId={} during {}; runtime result convergence continues",
                    taskId, messageId, attempt.getAttemptId(), action, e);
        }
    }

    private boolean isCallbackAcceptableMessageState(TaskMsgStatus taskMsgStatus) {
        return taskMsgStatus == TaskMsgStatus.ASSIGNED
                || taskMsgStatus == TaskMsgStatus.RUNNING;
    }

    private boolean isExpiryAcceptableMessageState(TaskMsgStatus taskMsgStatus) {
        return taskMsgStatus == TaskMsgStatus.ASSIGNED
                || taskMsgStatus == TaskMsgStatus.RUNNING;
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
                                      RuntimeMessageView taskMsg,
                                      TaskMsgAttempt attempt,
                                      String trigger,
                                      String reason) {
        traceEventLogger.taskMessageAttemptClosed(task, taskMsg.toTraceView(), attempt, trigger, "TaskManager", reason);
        taskManager.publishTaskMessageAttemptClosed(
                task,
                TaskMessageAttemptClosedEvent.from(taskMsg.taskId(), taskMsg.messageId(), attempt)
        );
    }

    private void publishMessageLogicallyFinal(Task task,
                                              RuntimeMessageView taskMsg,
                                              TaskMsgAttempt attempt,
                                              String trigger,
                                              String reason) {
        traceEventLogger.taskMessageLogicallyFinal(task, taskMsg.toTraceView(), attempt, trigger, "TaskManager", reason);
        taskManager.publishTaskMessageLogicallyFinal(
                task,
                TaskMessageLogicallyFinalEvent.from(
                        taskMsg.taskId(),
                        taskMsg.messageId(),
                        taskMsg.status(),
                        taskMsg.finalReason(),
                        taskMsg.retryCount(),
                        taskMsg.errorCode(),
                        taskMsg.errorMessage(),
                        taskMsg.payloadRef(),
                        taskMsg.output()
                )
        );
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

    private record ActiveRuntimeProjection(RuntimeMessageView messageState, TaskMsgAttempt activeAttempt) {
    }

    private record RuntimeMessageView(String messageId,
                                      String taskId,
                                      String latestAttemptId,
                                      String latestAttemptWorkerId,
                                      String latestAttemptWorkerContextId,
                                      String latestAttemptBatchId,
                                      TaskMsgStatus status,
                                      LocalDateTime assignedTime,
                                      LocalDateTime createTime,
                                      LocalDateTime updateTime,
                                      LocalDateTime startTime,
                                      LocalDateTime completeTime,
                                      int retryCount,
                                      int maxRetryCount,
                                      String errorMessage,
                                      String errorCode,
                                      TaskMsgFinalReason finalReason,
                                      String payloadRef,
                                      Map<String, Object> input,
                                      Map<String, Object> output) {

        private static RuntimeMessageView synthetic(String taskId, String messageId) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    null,
                    null,
                    null,
                    null,
                    TaskMsgStatus.INIT,
                    null,
                    now,
                    now,
                    null,
                    null,
                    0,
                    3,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    null
            );
        }

        private static RuntimeMessageView from(TaskMsg projection) {
            if (projection == null) {
                return null;
            }
            return new RuntimeMessageView(
                    projection.getMessageId(),
                    projection.getTaskId(),
                    projection.latestAttemptId(),
                    projection.getLatestAttemptWorkerId(),
                    projection.getLatestAttemptWorkerContextId(),
                    projection.getLatestAttemptBatchId(),
                    projection.getStatus(),
                    projection.getAssignedTime(),
                    projection.getCreateTime(),
                    projection.getUpdateTime(),
                    projection.getStartTime(),
                    projection.getCompleteTime(),
                    projection.getRetryCount(),
                    projection.getMaxRetryCount(),
                    projection.getErrorMessage(),
                    projection.getErrorCode(),
                    projection.getFinalReason(),
                    projection.getPayloadRef(),
                    projection.getInput() == null ? Map.of() : new java.util.LinkedHashMap<>(projection.getInput()),
                    projection.getOutput() == null ? null : new java.util.LinkedHashMap<>(projection.getOutput())
            );
        }

        private boolean isCompleted() {
            return status != null && status.isFinal();
        }

        private RuntimeMessageView overlayActiveLease(ActiveLeaseRecord activeLease) {
            if (activeLease == null || isCompleted()) {
                return this;
            }
            int runtimeRetryCount = Math.max(0, activeLease.retryCount());
            boolean needsAssignedStatus = status == null || status == TaskMsgStatus.INIT;
            boolean attemptProjectionDiffers = !java.util.Objects.equals(latestAttemptWorkerId, activeLease.workerId())
                    || !java.util.Objects.equals(latestAttemptWorkerContextId, activeLease.workerContextId())
                    || !java.util.Objects.equals(latestAttemptBatchId, activeLease.batchId());
            boolean needsRetryProjection = retryCount != runtimeRetryCount;
            boolean needsAssignedTime = assignedTime == null;
            if (!attemptProjectionDiffers && !needsAssignedStatus && !needsRetryProjection && !needsAssignedTime) {
                return this;
            }
            LocalDateTime leasedAt = activeLease.leasedAt() == null
                    ? null
                    : LocalDateTime.ofInstant(activeLease.leasedAt(), java.time.ZoneId.systemDefault());
            LocalDateTime now = LocalDateTime.now();
            TaskMsgStatus nextStatus = needsAssignedStatus ? TaskMsgStatus.ASSIGNED : status;
            LocalDateTime nextAssignedTime = assignedTime != null
                    ? assignedTime
                    : leasedAt != null ? leasedAt : now;
            LocalDateTime nextUpdateTime = needsAssignedStatus ? now : updateTime;
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    latestAttemptId,
                    activeLease.workerId(),
                    activeLease.workerContextId(),
                    activeLease.batchId(),
                    nextStatus,
                    nextAssignedTime,
                    createTime,
                    nextUpdateTime,
                    startTime,
                    completeTime,
                    runtimeRetryCount,
                    maxRetryCount,
                    errorMessage,
                    errorCode,
                    finalReason,
                    payloadRef,
                    input,
                    output
            );
        }

        private TaskMessageTraceView toTraceView() {
            return new TaskMessageTraceView(
                    taskId,
                    messageId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    status,
                    finalReason,
                    retryCount,
                    errorCode
            );
        }

        private TaskMsg toCompatibilityProjection() {
            TaskMsg projection = new TaskMsg(messageId, taskId, input, payloadRef);
            projection.setStatus(status);
            projection.setAssignedTime(assignedTime);
            projection.setCreateTime(createTime);
            projection.setUpdateTime(updateTime);
            projection.setStartTime(startTime);
            projection.setCompleteTime(completeTime);
            projection.setRetryCount(retryCount);
            projection.setMaxRetryCount(maxRetryCount);
            projection.setErrorMessage(errorMessage);
            projection.setErrorCode(errorCode);
            projection.setFinalReason(finalReason);
            projection.setOutput(output);
            projection.applyLatestAttemptProjection(
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId
            );
            return projection;
        }
    }
}


