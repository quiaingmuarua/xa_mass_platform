package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.engine.util.TraceEventLogger.TaskMessageTraceView;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Owns task-message callback handling, retry sequencing, and result-side event ordering.
 */
class TaskResultService {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultService.class);
    static final String DISPATCH_SUBMIT_FAILED_ERROR_CODE = "DISPATCH_SUBMIT_FAILED";

    private final TaskManager taskManager;
    private final TaskCompatibilityProjectionAccess compatibilityProjectionAccess;
    private final TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver;
    private final TraceEventLogger traceEventLogger;

    TaskResultService(TaskManager taskManager,
                      TaskCompatibilityProjectionAccess compatibilityProjectionAccess,
                      TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver,
                      TraceEventLogger traceEventLogger) {
        this.taskManager = taskManager;
        this.compatibilityProjectionAccess = compatibilityProjectionAccess;
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
        TaskWorkEnvelope runtimeWork = taskManager.getTaskWork(taskId, messageId).orElse(null);
        if (activeLease == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "no active runtime lease", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        ActiveRuntimeProjection activeProjection = buildActiveRuntimeProjection(
                taskId,
                messageId,
                activeLease,
                runtimeWork,
                "EXPIRE_TASK_MESSAGE",
                "runtime active lease defines expiry admissibility"
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
        AttemptProjectionView activeAttempt = activeProjection.activeAttempt();
        TaskMessageProjectionStatus fromStatus = messageState.status();
        if (!isExpiryAcceptableMessageState(messageState.status())) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "task message status " + messageState.status() + " cannot expire; only ASSIGNED/RUNNING can expire", 0);
            return TaskMessageMutationOutcome.rejected();
        }
        // Once a worker has started executing the message, lease expiry must
        // converge to one final outcome instead of re-queueing the same work.
        boolean retryRequestedByPolicy = fromStatus == TaskMessageProjectionStatus.ASSIGNED;
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, retryRequestedByPolicy);
        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, activeLease.leaseToken(),
                false, expiryDetail, null, null, retryRequestedByPolicy, true);
        if (workOutcome.status() == ResultApplyStatus.STALE_LEASE
                || workOutcome.status() == ResultApplyStatus.NO_ACTIVE_LEASE) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "runtime lease rejected expiry result: " + workOutcome.status(), 0);
            return TaskMessageMutationOutcome.rejected();
        }
        if (!activeAttempt.projectExpired(TaskMessageAttemptProjectionFinalReason.LEASE_EXPIRED, "task message expired")) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "attempt could not expire from status "
                    + activeAttempt.status(), 0);
            return TaskMessageMutationOutcome.rejected();
        }
        persistAttemptProjectionUpsertBestEffort(taskId, messageId, activeAttempt,
                "mark attempt expired");
        boolean retryScheduled = workOutcome.status() == ResultApplyStatus.RETRY_SCHEDULED;
        RuntimeMessageView expiredSummary = summarizeExpired(messageState, expiryDetail);
        traceEventLogger.leaseExpired(
                expiredSummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                expiryDetail
        );
        traceEventLogger.taskMsgStatusTransition(
                expiredSummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                fromStatus,
                expiredSummary.status(),
                "EXPIRE_TASK_MESSAGE",
                "TaskManager",
                expiryDetail
        );
        persistTaskMessageProjection(taskId, expiredSummary,
                "persist expiry compatibility summary");
        Task freshTask = taskManager.getTask(taskId);
        RuntimeMessageView currentSummary = expiredSummary;
        if (retryScheduled) {
            RuntimeMessageView retrySummary = summarizeRetryReset(expiredSummary);
            traceEventLogger.taskMsgRetryReset(retrySummary.toTraceView(),
                    activeAttempt.attemptId(),
                    activeAttempt.workerId(),
                    activeAttempt.workerContextId(),
                    activeAttempt.batchId(),
                    workRetryDelayMillis,
                    "EXPIRE_TASK_MESSAGE", "TaskManager", "lease expired but retry budget allows re-dispatch");
            persistTaskMessageProjection(taskId, retrySummary,
                    "persist expiry retry-reset compatibility summary");
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

        if (task.getStatus().isFinal()) {
            RuntimeMessageView storedProjection = getStoredTaskMessageProjectionView(taskId, messageId);
            ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
            if (storedProjection != null && storedProjection.isCompleted()) {
                traceEventLogger.callbackIgnoredDuplicate(storedProjection.toTraceView(),
                        "task message already final in status " + storedProjection.status());
                logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                        messageId, taskId, storedProjection.status());
                return TaskMessageMutationOutcome.acceptedNoop();
            }
            TaskMessageTraceView lateView = resolveTraceTaskMessageView(taskId, messageId, storedProjection, activeLease);
            traceEventLogger.callbackIgnoredLate(lateView,
                    "task already terminal in status " + task.getStatus());
            logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                    taskId, messageId, lateView.status());
            return TaskMessageMutationOutcome.acceptedNoop();
        }

        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        TaskWorkEnvelope runtimeWork = taskManager.getTaskWork(taskId, messageId).orElse(null);
        if (activeLease == null) {
            RuntimeMessageView storedProjection = getStoredTaskMessageProjectionView(taskId, messageId);
            if (storedProjection != null && storedProjection.isCompleted()) {
                traceEventLogger.callbackIgnoredDuplicate(storedProjection.toTraceView(),
                        "task message already final in status " + storedProjection.status());
                logger.info("Task message {} of task {} is already in final status {}, skipping duplicate result",
                        messageId, taskId, storedProjection.status());
                return TaskMessageMutationOutcome.acceptedNoop();
            }
            TaskMessageTraceView noLeaseView = resolveTraceTaskMessageView(taskId, messageId, storedProjection, null);
            traceEventLogger.callbackRejectedNoActiveLease(
                    noLeaseView,
                    "callback arrived without any active runtime lease"
            );
            logger.error("Cannot handle task message result because msg {} in task {} has no active runtime lease", messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }

        // Active lease plus runtime work is the authoritative callback base.
        // Compatibility message residue stays out of the accepted hot path.
        ActiveRuntimeProjection activeProjection = buildActiveRuntimeProjection(
                taskId,
                messageId,
                activeLease,
                runtimeWork,
                "HANDLE_TASK_MESSAGE_RESULT",
                "runtime active lease defines callback admissibility"
        );
        if (activeProjection == null) {
            logger.warn("Cannot handle task message result because msg {} was not found in task {} and no runtime projection could be recovered",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeMessageView messageState = activeProjection.messageState();
        AttemptProjectionView activeAttempt = activeProjection.activeAttempt();
        if (!isCallbackAcceptableMessageState(messageState.status())) {
            traceEventLogger.callbackRejectedInvalidState(messageState.toTraceView(),
                    "callback arrived while message status is " + messageState.status());
            logger.error("Cannot handle task message result because msg {} in task {} is in invalid callback state {}",
                    messageId, taskId, messageState.status());
            return TaskMessageMutationOutcome.rejected();
        }

        if (!activeAttempt.projectCallbackAccepted()) {
            logger.warn("Cannot advance attempt {} for task message {} from status {}",
                    activeAttempt.attemptId(), messageId, activeAttempt.status());
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

        traceEventLogger.callbackAccepted(
                messageState.toTraceView(),
                success ? "success callback received" : "failure callback received");

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
        TaskWorkEnvelope runtimeWork = taskManager.getTaskWork(taskId, messageId).orElse(null);
        if (activeLease == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no active runtime lease",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        ActiveRuntimeProjection activeProjection = buildActiveRuntimeProjection(
                taskId,
                messageId,
                activeLease,
                runtimeWork,
                "COMPENSATE_DISPATCH_SUBMIT_FAILURE",
                "runtime active lease defines dispatch compensation admissibility"
        );
        if (activeProjection == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} was not found in task {}",
                    messageId, taskId);
            return TaskMessageMutationOutcome.rejected();
        }
        RuntimeMessageView messageState = activeProjection.messageState();

        AttemptProjectionView activeAttempt = resolveOrRecoverDispatchAttemptProjection(messageState, activeLease, dispatchBinding);
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
                                                             RuntimeMessageView storedProjection,
                                                             ActiveLeaseRecord activeLease) {
        if (storedProjection != null) {
            return storedProjection.toTraceView();
        }
        RuntimeMessageView recovered = materializeRuntimeMessageView(
                taskId,
                messageId,
                activeLease,
                taskManager.getTaskWork(taskId, messageId).orElse(null)
        );
        return recovered != null
                ? recovered.toTraceView()
                : new TaskMessageTraceView(taskId, messageId, null, null, null, null, TaskMessageProjectionStatus.INIT, null, 0, null);
    }

    private ActiveRuntimeProjection buildActiveRuntimeProjection(String taskId,
                                                                 String messageId,
                                                                 ActiveLeaseRecord activeLease,
                                                                 TaskWorkEnvelope runtimeWork,
                                                                 String trigger,
                                                                 String reason) {
        RuntimeMessageView activeView = materializeRuntimeMessageView(taskId, messageId, activeLease, runtimeWork);
        if (activeView == null) {
            return null;
        }
        AttemptProjectionView activeAttempt = recoverActiveAttemptProjection(activeView, activeLease, null, null);
        if (activeAttempt == null) {
            traceEventLogger.callbackRejectedNoActiveAttempt(
                    taskId,
                    messageId,
                    activeView.status(),
                    "callback arrived without any recoverable active attempt"
            );
            return null;
        }
        TaskMessageProjectionStatus originalStatus = activeView.status();
        activeView = activeView.attachAttempt(activeAttempt);
        if (originalStatus == TaskMessageProjectionStatus.INIT && activeView.status() == TaskMessageProjectionStatus.ASSIGNED) {
            traceEventLogger.taskMsgStatusTransition(
                    activeView.toTraceView(),
                    activeAttempt.attemptId(),
                    activeAttempt.workerId(),
                    activeAttempt.workerContextId(),
                    activeAttempt.batchId(),
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
                                                             ActiveLeaseRecord activeLease,
                                                             TaskWorkEnvelope runtimeWork) {
        if (activeLease == null && runtimeWork == null) {
            return null;
        }
        RuntimeMessageView baseView = runtimeWork != null
                ? RuntimeMessageView.fromRuntimeWorkEnvelope(runtimeWork)
                : RuntimeMessageView.synthetic(taskId, messageId, activeLease != null ? activeLease.payloadRef() : null);
        if (baseView != null && activeLease != null && baseView.isCompleted()) {
            baseView = baseView.reopenForActiveLease(activeLease);
        }
        return baseView.overlayActiveLease(activeLease);
    }

    private AttemptProjectionView recoverActiveAttemptProjection(RuntimeMessageView taskMsg,
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
            attemptId = TaskMessageAttemptSupport.runtimeAttemptId(taskMsg.messageId(), attemptNo, activeLease);
        }
        return AttemptProjectionView.dispatched(
                taskMsg.taskId(),
                taskMsg.messageId(),
                activeLease,
                attemptId,
                attemptNo
        );
    }

    private AttemptProjectionView resolveOrRecoverDispatchAttemptProjection(RuntimeMessageView taskMsg,
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
                                                     AttemptProjectionView activeAttempt,
                                                     String detail,
                                                     Map<String, Object> output) {
        String messageId = taskMsg.messageId();
        RuntimeMessageView successBase = taskMsg;
        if (taskMsg.status() == TaskMessageProjectionStatus.ASSIGNED) {
            TaskMessageProjectionStatus beforeRunningStatus = taskMsg.status();
            RuntimeMessageView runningView = summarizeRunning(successBase);
            traceEventLogger.taskMsgStatusTransition(
                    runningView.toTraceView(),
                    activeAttempt.attemptId(),
                    activeAttempt.workerId(),
                    activeAttempt.workerContextId(),
                    activeAttempt.batchId(),
                    beforeRunningStatus,
                    runningView.status(),
                    "HANDLE_TASK_MESSAGE_RESULT",
                    "TaskManager",
                    "task message entered running from callback"
            );
            successBase = runningView;
        }
        RuntimeMessageView successSummary = summarizeSuccess(successBase, detail, output);
        TaskMessageProjectionStatus beforeFinalStatus = successBase.status();
        if (!activeAttempt.projectSucceeded(output)) {
            logger.warn("Failed to mark attempt {} as SUCCEEDED", activeAttempt.attemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        traceEventLogger.taskMsgStatusTransition(
                successSummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                beforeFinalStatus,
                successSummary.status(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked success"
        );
        persistAttemptProjectionUpsertBestEffort(taskId, messageId, activeAttempt,
                "mark attempt success");
        persistTaskMessageProjection(taskId, successSummary,
                "persist success compatibility summary");
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
                                                              AttemptProjectionView activeAttempt,
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
                                                                            AttemptProjectionView activeAttempt,
                                                                            String detail,
                                                                            String errorCode,
                                                                            String trigger,
                                                                            String resetReason) {
        String messageId = taskMsg.messageId();
        TaskMessageAttemptProjectionStatus beforeRevokedStatus = activeAttempt.status();
        if (!activeAttempt.projectRetryRevoked(detail, errorCode)) {
            logger.warn("Failed to revoke attempt {} for retry", activeAttempt.attemptId());
            return null;
        }
        traceEventLogger.taskMsgAttemptStatusTransition(
                taskId,
                messageId,
                activeAttempt.attemptId(),
                activeAttempt.attemptNo(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                activeAttempt.finalReason(),
                beforeRevokedStatus,
                activeAttempt.status(),
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

        TaskMessageProjectionStatus beforeRetryFailureStatus = retryBase.status();
        RuntimeMessageView failedView = summarizeBusinessFailure(retryBase, detail, errorCode);
        traceEventLogger.taskMsgStatusTransition(
                failedView.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                beforeRetryFailureStatus,
                failedView.status(),
                trigger,
                "TaskManager",
                "task message marked failed before retry reset"
        );
        RuntimeMessageView retrySummary = summarizeRetryReset(failedView);
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(taskManager.getTask(taskId), true);
        traceEventLogger.taskMsgRetryReset(retrySummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                workRetryDelayMillis,
                trigger,
                "TaskManager",
                resetReason);
        persistTaskMessageProjection(taskId, retrySummary,
                "persist retry-reset compatibility summary");
        return retrySummary;
    }

    private RuntimeMessageView buildRetryResetCompatibilityBaseView(RuntimeMessageView taskMsg,
                                                                    AttemptProjectionView activeAttempt) {
        if (taskMsg == null) {
            return null;
        }
        if (taskMsg.status() == TaskMessageProjectionStatus.ASSIGNED || taskMsg.status() == TaskMessageProjectionStatus.RUNNING) {
            return taskMsg;
        }
        if (taskMsg.status() != TaskMessageProjectionStatus.INIT || activeAttempt == null) {
            return null;
        }
        return taskMsg.withAssignedAttempt(activeAttempt);
    }

    private TaskMessageMutationOutcome handleRetryExhaustedFailure(String taskId,
                                                                   RuntimeMessageView taskMsg,
                                                                   AttemptProjectionView activeAttempt,
                                                                   String detail,
                                                                   String errorCode,
                                                                   Map<String, Object> output) {
        String messageId = taskMsg.messageId();
        if (!activeAttempt.projectFailed(
                TaskMessageAttemptProjectionFinalReason.BUSINESS_FAILURE,
                detail,
                errorCode,
                output)) {
            logger.warn("Failed to mark attempt {} as FAILED", activeAttempt.attemptId());
            return TaskMessageMutationOutcome.rejected();
        }
        persistAttemptProjectionUpsertBestEffort(taskId, messageId, activeAttempt,
                "mark attempt failure");

        TaskMessageProjectionStatus beforeFinalStatus = taskMsg.status();
        RuntimeMessageView failureSummary = summarizeRetryExhaustedFailure(taskMsg, detail, errorCode, output);
        traceEventLogger.taskMsgStatusTransition(
                failureSummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                beforeFinalStatus,
                failureSummary.status(),
                "HANDLE_TASK_MESSAGE_RESULT",
                "TaskManager",
                "task message marked failure"
        );

        persistTaskMessageProjection(taskId, failureSummary,
                "persist exhausted-failure compatibility summary");
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
        return base.markRunning();
    }

    private RuntimeMessageView summarizeSuccess(RuntimeMessageView base,
                                                String detail,
                                                Map<String, Object> output) {
        return base.completeSuccess(output);
    }

    private RuntimeMessageView summarizeBusinessFailure(RuntimeMessageView base,
                                                        String detail,
                                                        String errorCode) {
        return base.completeFailure(TaskMessageProjectionFinalReason.BUSINESS_FAILED, detail, errorCode, null);
    }

    private RuntimeMessageView summarizeExpired(RuntimeMessageView base, String detail) {
        return base.completeExpiry(detail, base.errorCode());
    }

    private RuntimeMessageView summarizeRetryReset(RuntimeMessageView failedView) {
        return failedView.resetForRetry();
    }

    private RuntimeMessageView summarizeRetryExhaustedFailure(RuntimeMessageView base,
                                                              String detail,
                                                              String errorCode,
                                                              Map<String, Object> output) {
        return base.completeFailure(TaskMessageProjectionFinalReason.RETRY_EXHAUSTED, detail, errorCode, output);
    }

    /**
     * Compatibility projection persistence is best-effort only.
     *
     * <p>Runtime {@code applyResult(...)} has already decided execution truth
     * before this write runs, so projection failure must not redefine callback,
     * expiry, or retry convergence.</p>
     */
    private void persistTaskMessageProjection(String taskId,
                                              RuntimeMessageView taskMsg,
                                              String action) {
        if (!compatibilityProjectionAccess.upsertTaskMessageProjection(taskId, taskMsg, action)) {
            logger.warn("Compatibility task message projection write failed for taskId={}, messageId={} during {}; runtime truth already converged",
                    taskId, taskMsg != null ? taskMsg.messageId() : null, action);
        }
    }

    @CompatibilityProjectionOnly
    private void persistAttemptProjectionUpsertBestEffort(String taskId,
                                                          String messageId,
                                                          AttemptProjectionView attempt,
                                                          String action) {
        if (attempt == null) {
            return;
        }
        compatibilityProjectionAccess.upsertTaskMessageAttemptProjectionBestEffort(
                taskId,
                messageId,
                attempt,
                action
        );
    }

    private boolean isCallbackAcceptableMessageState(TaskMessageProjectionStatus taskMsgStatus) {
        return taskMsgStatus == TaskMessageProjectionStatus.ASSIGNED
                || taskMsgStatus == TaskMessageProjectionStatus.RUNNING;
    }

    private boolean isExpiryAcceptableMessageState(TaskMessageProjectionStatus taskMsgStatus) {
        return taskMsgStatus == TaskMessageProjectionStatus.ASSIGNED
                || taskMsgStatus == TaskMessageProjectionStatus.RUNNING;
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

    @CompatibilityProjectionOnly
    private RuntimeMessageView getStoredTaskMessageProjectionView(String taskId, String messageId) {
        return compatibilityProjectionAccess.getStoredRuntimeMessageProjectionView(taskId, messageId);
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
                                      AttemptProjectionView attempt,
                                      String trigger,
                                      String reason) {
        traceEventLogger.taskMessageAttemptClosed(
                task,
                taskMsg.toTraceView(),
                attempt.attemptId(),
                attempt.attemptNo(),
                attempt.workerId(),
                attempt.workerContextId(),
                attempt.batchId(),
                attempt.status(),
                attempt.finalReason(),
                trigger,
                "TaskManager",
                reason
        );
        taskManager.publishTaskMessageAttemptClosed(
                task,
                TaskMessageAttemptClosedEvent.from(
                        taskMsg.taskId(),
                        taskMsg.messageId(),
                        attempt.attemptId(),
                        attempt.attemptNo(),
                        attempt.workerId(),
                        attempt.workerContextId(),
                        attempt.batchId(),
                        attempt.status(),
                        attempt.finalReason()
                )
        );
    }

    private void publishMessageLogicallyFinal(Task task,
                                              RuntimeMessageView taskMsg,
                                              AttemptProjectionView attempt,
                                              String trigger,
                                              String reason) {
        traceEventLogger.taskMessageLogicallyFinal(
                task,
                taskMsg.toTraceView(),
                attempt != null ? attempt.attemptId() : null,
                attempt != null ? attempt.workerId() : null,
                attempt != null ? attempt.workerContextId() : null,
                attempt != null ? attempt.batchId() : null,
                trigger,
                "TaskManager",
                reason
        );
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

    private record ActiveRuntimeProjection(RuntimeMessageView messageState, AttemptProjectionView activeAttempt) {
    }

    static final class AttemptProjectionView {
        private final String attemptId;
        private final String taskId;
        private final String messageId;
        private final int attemptNo;
        private final String workerId;
        private final String workerContextId;
        private final String batchId;
        private TaskMessageAttemptProjectionStatus status;
        private TaskMessageAttemptProjectionFinalReason finalReason;
        private String errorMessage;
        private String errorCode;
        private Map<String, Object> output;

        private AttemptProjectionView(String attemptId,
                                      String taskId,
                                      String messageId,
                                      int attemptNo,
                                      String workerId,
                                      String workerContextId,
                                      String batchId) {
            this.attemptId = attemptId;
            this.taskId = taskId;
            this.messageId = messageId;
            this.attemptNo = attemptNo;
            this.workerId = workerId;
            this.workerContextId = workerContextId;
            this.batchId = batchId;
            this.status = TaskMessageAttemptProjectionStatus.DISPATCHED;
        }

        static AttemptProjectionView dispatched(String taskId,
                                                String messageId,
                                                ActiveLeaseRecord activeLease,
                                                String attemptId,
                                                int attemptNo) {
            if (taskId == null || messageId == null || activeLease == null) {
                return null;
            }
            AttemptProjectionView attempt = new AttemptProjectionView(
                    attemptId,
                    taskId,
                    messageId,
                    attemptNo,
                    activeLease.workerId(),
                    activeLease.workerContextId(),
                    activeLease.batchId()
            );
            return attempt;
        }

        String attemptId() {
            return attemptId;
        }

        String taskId() {
            return taskId;
        }

        String messageId() {
            return messageId;
        }

        int attemptNo() {
            return attemptNo;
        }

        String workerId() {
            return workerId;
        }

        String workerContextId() {
            return workerContextId;
        }

        String batchId() {
            return batchId;
        }

        TaskMessageAttemptProjectionStatus status() {
            return status;
        }

        TaskMessageAttemptProjectionFinalReason finalReason() {
            return finalReason;
        }

        String errorMessage() {
            return errorMessage;
        }

        String errorCode() {
            return errorCode;
        }

        Map<String, Object> output() {
            return output;
        }

        boolean projectCallbackAccepted() {
            if (status == null) {
                return false;
            }
            if (status.isFinal()) {
                return true;
            }
            status = TaskMessageAttemptProjectionStatus.RUNNING;
            return true;
        }

        boolean projectExpired(TaskMessageAttemptProjectionFinalReason nextFinalReason, String nextErrorMessage) {
            if (status == null || status.isFinal()) {
                return false;
            }
            status = TaskMessageAttemptProjectionStatus.EXPIRED;
            finalReason = nextFinalReason;
            errorMessage = nextErrorMessage;
            return true;
        }

        boolean projectSucceeded(Map<String, Object> nextOutput) {
            if (status == null || status.isFinal()) {
                return false;
            }
            status = TaskMessageAttemptProjectionStatus.SUCCEEDED;
            finalReason = TaskMessageAttemptProjectionFinalReason.SUCCESS;
            output = copyMap(nextOutput);
            return true;
        }

        boolean projectRetryRevoked(String nextErrorMessage, String nextErrorCode) {
            if (status == null || status.isFinal()) {
                return false;
            }
            status = TaskMessageAttemptProjectionStatus.REVOKED;
            finalReason = TaskMessageAttemptProjectionFinalReason.REVOKED_FOR_RETRY;
            errorMessage = nextErrorMessage;
            errorCode = nextErrorCode;
            output = null;
            return true;
        }

        boolean projectFailed(TaskMessageAttemptProjectionFinalReason nextFinalReason,
                              String nextErrorMessage,
                              String nextErrorCode,
                              Map<String, Object> nextOutput) {
            if (status == null || status.isFinal()) {
                return false;
            }
            status = TaskMessageAttemptProjectionStatus.FAILED;
            finalReason = nextFinalReason;
            errorMessage = nextErrorMessage;
            errorCode = nextErrorCode;
            output = copyMap(nextOutput);
            return true;
        }

        private Map<String, Object> copyMap(Map<String, Object> values) {
            return values == null ? null : new java.util.LinkedHashMap<>(values);
        }
    }

    record RuntimeMessageView(String messageId,
                              String taskId,
                              String latestAttemptId,
                              String latestAttemptWorkerId,
                              String latestAttemptWorkerContextId,
                              String latestAttemptBatchId,
                              TaskMessageProjectionStatus status,
                              LocalDateTime assignedTime,
                              LocalDateTime createTime,
                              LocalDateTime updateTime,
                              LocalDateTime startTime,
                              LocalDateTime completeTime,
                              int retryCount,
                              int maxRetryCount,
                              String errorMessage,
                              String errorCode,
                              TaskMessageProjectionFinalReason finalReason,
                              String payloadRef,
                              Map<String, Object> output) {

        private static RuntimeMessageView synthetic(String taskId, String messageId, String payloadRef) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    null,
                    null,
                    null,
                    null,
                    TaskMessageProjectionStatus.INIT,
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
                    payloadRef,
                    null
            );
        }

        private static RuntimeMessageView fromRuntimeWorkEnvelope(TaskWorkEnvelope runtimeWork) {
            if (runtimeWork == null) {
                return null;
            }
            LocalDateTime createdAt = runtimeWork.createdAt() == null
                    ? LocalDateTime.now()
                    : LocalDateTime.ofInstant(runtimeWork.createdAt(), java.time.ZoneId.systemDefault());
            return new RuntimeMessageView(
                    runtimeWork.messageId(),
                    runtimeWork.taskId(),
                    null,
                    null,
                    null,
                    null,
                    TaskMessageProjectionStatus.INIT,
                    null,
                    createdAt,
                    createdAt,
                    null,
                    null,
                    Math.max(0, runtimeWork.retryCount()),
                    Math.max(0, runtimeWork.maxRetryCount()),
                    null,
                    null,
                    null,
                    runtimeWork.payloadRef(),
                    null
            );
        }

        @CompatibilityProjectionOnly
        static RuntimeMessageView from(TaskCompatibilityProjectionAccess.MessageProjection projection) {
            if (projection == null) {
                return null;
            }
            return new RuntimeMessageView(
                    projection.messageId(),
                    projection.taskId(),
                    projection.latestAttemptId(),
                    projection.latestAttemptWorkerId(),
                    projection.latestAttemptWorkerContextId(),
                    projection.latestAttemptBatchId(),
                    projection.status(),
                    projection.assignedTime(),
                    projection.createTime(),
                    projection.updateTime(),
                    projection.startTime(),
                    projection.completeTime(),
                    projection.retryCount(),
                    projection.maxRetryCount(),
                    projection.errorMessage(),
                    projection.errorCode(),
                    projection.finalReason(),
                    projection.payloadRef(),
                    projection.output() == null ? null : new java.util.LinkedHashMap<>(projection.output())
            );
        }

        private boolean isCompleted() {
            return status != null && status.isFinal();
        }

        private RuntimeMessageView reopenForActiveLease(ActiveLeaseRecord activeLease) {
            if (!isCompleted() || activeLease == null) {
                return this;
            }
            int runtimeRetryCount = Math.max(0, activeLease.retryCount());
            LocalDateTime leasedAt = activeLease.leasedAt() == null
                    ? null
                    : LocalDateTime.ofInstant(activeLease.leasedAt(), java.time.ZoneId.systemDefault());
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    null,
                    activeLease.workerId(),
                    activeLease.workerContextId(),
                    activeLease.batchId(),
                    TaskMessageProjectionStatus.ASSIGNED,
                    assignedTime != null ? assignedTime : leasedAt != null ? leasedAt : now,
                    createTime,
                    now,
                    null,
                    null,
                    runtimeRetryCount,
                    maxRetryCount,
                    null,
                    null,
                    null,
                    payloadRef,
                    null
            );
        }

        private RuntimeMessageView overlayActiveLease(ActiveLeaseRecord activeLease) {
            if (activeLease == null || isCompleted()) {
                return this;
            }
            int runtimeRetryCount = Math.max(0, activeLease.retryCount());
            boolean needsAssignedStatus = status == null || status == TaskMessageProjectionStatus.INIT;
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
            TaskMessageProjectionStatus nextStatus = needsAssignedStatus ? TaskMessageProjectionStatus.ASSIGNED : status;
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
                    output
            );
        }

        private RuntimeMessageView withAssignedAttempt(AttemptProjectionView attempt) {
            if (attempt == null) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime nextAssignedTime = assignedTime != null ? assignedTime : now;
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    attempt.attemptId(),
                    attempt.workerId(),
                    attempt.workerContextId(),
                    attempt.batchId(),
                    TaskMessageProjectionStatus.ASSIGNED,
                    nextAssignedTime,
                    createTime,
                    now,
                    startTime,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    errorMessage,
                    errorCode,
                    finalReason,
                    payloadRef,
                    output == null ? null : new java.util.LinkedHashMap<>(output)
            );
        }

        private RuntimeMessageView attachAttempt(AttemptProjectionView attempt) {
            if (attempt == null) {
                return this;
            }
            boolean alreadyAttached = java.util.Objects.equals(latestAttemptId, attempt.attemptId())
                    && java.util.Objects.equals(latestAttemptWorkerId, attempt.workerId())
                    && java.util.Objects.equals(latestAttemptWorkerContextId, attempt.workerContextId())
                    && java.util.Objects.equals(latestAttemptBatchId, attempt.batchId());
            TaskMessageProjectionStatus nextStatus = status == null || status == TaskMessageProjectionStatus.INIT
                    ? TaskMessageProjectionStatus.ASSIGNED
                    : status;
            if (alreadyAttached && nextStatus == status && assignedTime != null) {
                return this;
            }
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    attempt.attemptId(),
                    attempt.workerId(),
                    attempt.workerContextId(),
                    attempt.batchId(),
                    nextStatus,
                    assignedTime != null ? assignedTime : now,
                    createTime,
                    now,
                    startTime,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    errorMessage,
                    errorCode,
                    finalReason,
                    payloadRef,
                    output == null ? null : new java.util.LinkedHashMap<>(output)
            );
        }

        private RuntimeMessageView markRunning() {
            if (status == TaskMessageProjectionStatus.RUNNING) {
                return this;
            }
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    TaskMessageProjectionStatus.RUNNING,
                    assignedTime,
                    createTime,
                    now,
                    startTime != null ? startTime : now,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    errorMessage,
                    errorCode,
                    finalReason,
                    payloadRef,
                    output == null ? null : new java.util.LinkedHashMap<>(output)
            );
        }

        private RuntimeMessageView completeSuccess(Map<String, Object> nextOutput) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    TaskMessageProjectionStatus.SUCCESS,
                    assignedTime,
                    createTime,
                    updateTime,
                    startTime,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    null,
                    null,
                    TaskMessageProjectionFinalReason.BUSINESS_SUCCESS,
                    payloadRef,
                    nextOutput == null ? null : new java.util.LinkedHashMap<>(nextOutput)
            ).complete(now);
        }

        private RuntimeMessageView completeFailure(TaskMessageProjectionFinalReason nextFinalReason,
                                                  String nextDetail,
                                                  String nextErrorCode,
                                                  Map<String, Object> nextOutput) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    TaskMessageProjectionStatus.FAILED,
                    assignedTime,
                    createTime,
                    updateTime,
                    startTime,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    nextDetail,
                    nextErrorCode,
                    nextFinalReason,
                    payloadRef,
                    nextOutput == null ? null : new java.util.LinkedHashMap<>(nextOutput)
            ).complete(now);
        }

        private RuntimeMessageView completeExpiry(String nextDetail, String nextErrorCode) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    TaskMessageProjectionStatus.EXPIRED,
                    assignedTime,
                    createTime,
                    updateTime,
                    startTime,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    nextDetail,
                    nextErrorCode,
                    TaskMessageProjectionFinalReason.LEASE_EXPIRED,
                    payloadRef,
                    null
            ).complete(now);
        }

        private RuntimeMessageView resetForRetry() {
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    null,
                    null,
                    null,
                    null,
                    TaskMessageProjectionStatus.INIT,
                    assignedTime,
                    createTime,
                    updateTime,
                    null,
                    null,
                    retryCount + 1,
                    maxRetryCount,
                    null,
                    null,
                    null,
                    payloadRef,
                    null
            );
        }

        private RuntimeMessageView complete(LocalDateTime completedAt) {
            LocalDateTime now = completedAt != null ? completedAt : LocalDateTime.now();
            return new RuntimeMessageView(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    status,
                    assignedTime,
                    createTime,
                    now,
                    startTime,
                    now,
                    retryCount,
                    maxRetryCount,
                    errorMessage,
                    errorCode,
                    finalReason,
                    payloadRef,
                    output == null ? null : new java.util.LinkedHashMap<>(output)
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
    }
}


