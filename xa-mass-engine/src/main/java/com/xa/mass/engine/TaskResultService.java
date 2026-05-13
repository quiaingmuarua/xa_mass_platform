package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskWorkProjectionState.AttemptFinalReason;
import com.xa.mass.engine.TaskWorkProjectionState.AttemptStatus;
import com.xa.mass.engine.TaskWorkProjectionState.MessageFinalReason;
import com.xa.mass.engine.TaskWorkProjectionState.MessageStatus;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.engine.util.TraceEventLogger.TaskWorkTraceView;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.CommitResult;
import com.xa.mass.runtime.api.CommitResultStatus;
import com.xa.mass.runtime.api.RecentFinalWorkReceipt;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.RuntimeResultApplyContext;
import com.xa.mass.runtime.api.StageResult;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import com.xa.mass.runtime.api.TaskResultRepairCandidate;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns runtime work-result handling, retry sequencing, and result-side event ordering.
 */
class TaskResultService {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultService.class);
    static final String DISPATCH_SUBMIT_FAILED_ERROR_CODE = "DISPATCH_SUBMIT_FAILED";
    private static final String LEASE_EXPIRED_ERROR_CODE = "LEASE_EXPIRED";

    private final TaskManager taskManager;
    private final TaskCompatibilityProjectionStore compatibilityProjectionStore;
    private final TaskResultRuntime taskResultRuntime;
    private final TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver;
    private final TraceEventLogger traceEventLogger;
    private final ScheduledExecutorService repairExecutor;

    TaskResultService(TaskManager taskManager,
                      TaskCompatibilityProjectionStore compatibilityProjectionStore,
                      TaskResultRuntime taskResultRuntime,
                      TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver,
                      TraceEventLogger traceEventLogger) {
        this.taskManager = taskManager;
        this.compatibilityProjectionStore = compatibilityProjectionStore;
        this.taskResultRuntime = taskResultRuntime;
        this.taskRuntimeRetryPolicyResolver = taskRuntimeRetryPolicyResolver;
        this.traceEventLogger = traceEventLogger;
        this.repairExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "engine-result-repair-");
            thread.setDaemon(true);
            return thread;
        });
        startRepairPump();
    }

    ResultMutationOutcome expireLeasedWork(String taskId, String messageId) {
        LogUtils.setTaskId(taskId);
        LogUtils.logOperationStart("EXPIRE_LEASED_WORK", "TaskManager",
                "taskId", taskId, "messageId", messageId);
        String expiryDetail = "leased work expired";

        Task task = taskManager.getTask(taskId);
        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        TaskWorkEnvelope runtimeWork = taskManager.getTaskWork(taskId, messageId).orElse(null);
        if (activeLease == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "no active runtime lease", 0);
            return ResultMutationOutcome.rejected();
        }
        ActiveRuntimeProjection activeProjection = buildActiveRuntimeProjection(
                taskId,
                messageId,
                activeLease,
                runtimeWork,
                "EXPIRE_LEASED_WORK",
                "runtime active lease defines expiry admissibility"
        );
        if (activeProjection == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "compatibility projection could not be recovered", 0);
            return ResultMutationOutcome.rejected();
        }
        RuntimeWorkSummary workSummary = activeProjection.workSummary();
        if (workSummary.isCompleted()) {
            logger.info("Work item {} of task {} is already in final status {}, skip expiry",
                    messageId, taskId, workSummary.status());
            return ResultMutationOutcome.rejected();
        }
        AttemptProjectionView activeAttempt = activeProjection.activeAttempt();
        MessageStatus fromStatus = workSummary.status();
        if (!isExpiryAcceptableMessageState(workSummary.status())) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR",
                    "work item status " + workSummary.status() + " cannot expire; only ASSIGNED/RUNNING can expire", 0);
            return ResultMutationOutcome.rejected();
        }
        TaskResultCallbackDraft stagedDraft = buildCallbackDraft(
                task, taskId, messageId, false, expiryDetail, LEASE_EXPIRED_ERROR_CODE, null);
        StageResult stageResult = taskResultRuntime.stageCallback(stagedDraft);
        if (!stageResult.accepted()) {
            logger.warn("Cannot expire leased work because result runtime stage failed for taskId={}, messageId={}, reason={}",
                    taskId, messageId, stageResult.reason());
            return ResultMutationOutcome.rejected();
        }
        stagedDraft = stageResult.draft() != null ? stageResult.draft() : stagedDraft;
        boolean retryRequestedByPolicy = shouldRetryExpiredLease(task, fromStatus);
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, retryRequestedByPolicy);
        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, activeLease.leaseToken(),
                false, expiryDetail, null, null, retryRequestedByPolicy, true);
        if (workOutcome.status() == ResultApplyStatus.STALE_LEASE
                || workOutcome.status() == ResultApplyStatus.NO_ACTIVE_LEASE) {
            discardStage(stagedDraft);
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "runtime lease rejected expiry result: " + workOutcome.status(), 0);
            return ResultMutationOutcome.rejected();
        }
        if (!activeAttempt.projectExpired(AttemptFinalReason.LEASE_EXPIRED, "leased work expired")) {
            discardStage(stagedDraft);
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "attempt could not expire from status "
                    + activeAttempt.status(), 0);
            return ResultMutationOutcome.rejected();
        }
        boolean retryScheduled = workOutcome.status() == ResultApplyStatus.RETRY_SCHEDULED;
        Task freshTask = taskManager.getTask(taskId);
        RuntimeWorkSummary currentSummary;
        RuntimeWorkSummary logicallyFinalSummary = null;
        CommitResult visibleFinalCommit = null;
        if (retryScheduled) {
            RuntimeWorkSummary retrySummary = summarizeRetryReset(workSummary);
            traceEventLogger.taskWorkRetryReset(retrySummary.toTraceView(),
                    activeAttempt.attemptId(),
                    activeAttempt.workerId(),
                    activeAttempt.workerContextId(),
                    activeAttempt.batchId(),
                    workRetryDelayMillis,
                    "EXPIRE_LEASED_WORK", "TaskManager", "lease expired but retry budget allows re-dispatch");
            final RuntimeWorkSummary capturedRetrySummary = retrySummary;
            final AttemptProjectionView capturedExpiredAttempt = activeAttempt;
            taskManager.submitProjectionWrite(() -> {
                persistAttemptProjectionUpsertBestEffort(taskId, messageId, capturedExpiredAttempt,
                        "mark attempt expired");
                persistWorkProjectionBestEffort(taskId, capturedRetrySummary,
                        "persist expiry retry-reset compatibility summary");
            });
            currentSummary = retrySummary;
            discardStage(stagedDraft);
        } else {
            logicallyFinalSummary = summarizeLeaseExpiryFinal(task, workSummary, expiryDetail);
            traceEventLogger.taskWorkStatusTransition(
                    logicallyFinalSummary.toTraceView(),
                    activeAttempt.attemptId(),
                    activeAttempt.workerId(),
                    activeAttempt.workerContextId(),
                    activeAttempt.batchId(),
                    fromStatus,
                    logicallyFinalSummary.status(),
                    "EXPIRE_LEASED_WORK",
                    "TaskManager",
                    expiryDetail
            );
            visibleFinalCommit = commitVisibleFinal(logicallyFinalSummary, activeAttempt, stagedDraft);
            if (!visibleFinalCommit.visible()) {
                logger.warn("Result runtime visible commit failed for lease expiry taskId={}, messageId={}, status={}, reason={}",
                        taskId, messageId, visibleFinalCommit.status(), visibleFinalCommit.reason());
                return ResultMutationOutcome.acceptedNoop();
            }
            final RuntimeWorkSummary capturedFinalSummary = logicallyFinalSummary;
            final AttemptProjectionView capturedExpiredAttempt = activeAttempt;
            taskManager.submitProjectionWrite(() -> {
                persistAttemptProjectionUpsertBestEffort(taskId, messageId, capturedExpiredAttempt,
                        "mark attempt expired");
                persistWorkProjectionBestEffort(taskId, capturedFinalSummary,
                        "persist expiry compatibility summary");
            });
            currentSummary = logicallyFinalSummary;
        }
        traceEventLogger.leaseExpired(
                workSummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                fromStatus,
                currentSummary.status(),
                currentSummary.errorCode(),
                "EXPIRE_LEASED_WORK",
                "TaskManager",
                expiryDetail
        );
        if (freshTask != null && activeAttempt != null) {
            publishWorkAttemptClosed(freshTask, currentSummary, activeAttempt,
                    "EXPIRE_LEASED_WORK", retryScheduled
                            ? "lease expiry closed the current attempt before re-dispatch"
                            : expiryDetail);
        }
        if (!retryScheduled && freshTask != null) {
            publishWorkLogicallyFinalOnce(freshTask, logicallyFinalSummary, activeAttempt, visibleFinalCommit.row(),
                    "EXPIRE_LEASED_WORK", expiryDetail);
        }
        LogUtils.logOperationSuccess(expiryDetail, 0);
        if (retryScheduled) {
            Task updatedTask = taskManager.getTask(taskId);
            if (updatedTask != null && !updatedTask.getStatus().isFinal()) {
                requestRetryDispatch(updatedTask, workRetryDelayMillis);
            }
            return ResultMutationOutcome.acceptedDirty();
        }
        cleanupStageIfConverged(stagedDraft, visibleFinalCommit.row());
        return ResultMutationOutcome.acceptedDirtyWithProgressBarrier(taskId, messageId, visibleFinalCommit.row().seq());
    }

    ResultMutationOutcome ingestTaskResult(String taskId, String messageId, boolean success, String detail) {
        return ingestTaskResult(taskId, messageId, success, detail, null, null);
    }

    ResultMutationOutcome ingestTaskResult(String taskId, String messageId, boolean success, String detail, String errorCode) {
        return ingestTaskResult(taskId, messageId, success, detail, errorCode, null);
    }

    ResultMutationOutcome ingestTaskResult(String taskId,
                                                String messageId,
                                                boolean success,
                                                String detail,
                                                String errorCode,
                                                Map<String, Object> output) {
        // Load task ONCE - threaded through to all handlers to avoid repeat storage reads.
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            logger.warn("Cannot ingest task result because task {} was not found", taskId);
            return ResultMutationOutcome.rejected();
        }

        ResultMutationOutcome terminalCallback = handleTerminalCallback(task, taskId, messageId);
        if (terminalCallback != null) {
            return terminalCallback;
        }

        TaskResultCallbackDraft stagedDraft = buildCallbackDraft(task, taskId, messageId, success, detail, errorCode, output);
        StageResult stageResult = taskResultRuntime.stageCallback(stagedDraft);
        if (!stageResult.accepted()) {
            logger.warn("Cannot ingest task result because result runtime stage failed for taskId={}, messageId={}, reason={}",
                    taskId, messageId, stageResult.reason());
            return ResultMutationOutcome.rejected();
        }

        RuntimeResultApplyContext ctx = applyRuntimeResult(task, taskId, messageId, success, detail, errorCode, output);
        ResultMutationOutcome rejectedRuntimeOutcome = handleRejectedRuntimeOutcome(task, taskId, messageId, ctx, stagedDraft);
        if (rejectedRuntimeOutcome != null) {
            return rejectedRuntimeOutcome;
        }
        return handleAcceptedRuntimeOutcome(task, taskId, messageId, success, detail, errorCode, output, ctx, stagedDraft);
    }

    private ResultMutationOutcome handleTerminalCallback(Task task, String taskId, String messageId) {
        if (!task.getStatus().isFinal()) {
            return null;
        }
        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        RuntimeWorkSummary recentFinalMessage = recentFinalMessage(task, taskId, messageId);
        if (isManualOrPolicyTerminalStop(task)) {
            TaskWorkTraceView lateView = recentFinalMessage != null
                    ? recentFinalMessage.toTraceView()
                    : resolveTraceWorkView(taskId, messageId, activeLease);
            traceEventLogger.callbackIgnoredLate(lateView,
                    "task already terminal in status " + task.getStatus());
            logger.info("Ignoring late result for terminal task {}, msg {} still in status {}",
                    taskId, messageId, lateView.status());
            return ResultMutationOutcome.acceptedNoop();
        }
        TaskWorkTraceView duplicateView = recentFinalMessage != null
                ? recentFinalMessage.toTraceView()
                : resolveTraceWorkView(taskId, messageId, activeLease);
        traceEventLogger.callbackIgnoredDuplicate(duplicateView,
                "task already terminal after result convergence in status " + task.getStatus());
        logger.info("Ignoring duplicate result for terminal task {}, msg {} still in status {}",
                taskId, messageId, duplicateView.status());
        return ResultMutationOutcome.acceptedNoop();
    }

    private RuntimeResultApplyContext applyRuntimeResult(Task task,
                                                         String taskId,
                                                         String messageId,
                                                         boolean success,
                                                         String detail,
                                                         String errorCode,
                                                         Map<String, Object> output) {
        // Hot path: single atomic runtime call replaces three separate round-trips
        // (getActiveLease + getWork + applyResult), each of which previously required
        // its own synchronised runtime acquisition or Redis round-trip.
        TaskWorkResult workResult = buildWorkResultForCallback(
                task, taskId, messageId, success, detail, errorCode, output, !success, false);
        return taskManager.applyTaskWorkResultWithContext(workResult);
    }

    private ResultMutationOutcome handleRejectedRuntimeOutcome(Task task,
                                                               String taskId,
                                                               String messageId,
                                                               RuntimeResultApplyContext ctx,
                                                               TaskResultCallbackDraft stagedDraft) {
        if (!ctx.hasLeaseSnapshot()) {
            discardStage(stagedDraft);
            // No active lease at apply time - duplicate or late callback.
            RuntimeWorkSummary recentFinal = recentFinalMessage(task, taskId, messageId);
            if (recentFinal != null) {
                traceEventLogger.callbackIgnoredDuplicate(recentFinal.toTraceView(),
                        "work item already final in recent runtime receipt with status " + recentFinal.status());
                logger.info("Work item {} of task {} is already in final status {}, skipping duplicate result",
                        messageId, taskId, recentFinal.status());
                return ResultMutationOutcome.acceptedNoop();
            }
            TaskWorkTraceView noLeaseView = resolveTraceWorkView(taskId, messageId, null);
            traceEventLogger.callbackRejectedNoActiveLease(
                    noLeaseView, "callback arrived without any active runtime lease");
            logger.error("Cannot ingest task result because msg {} in task {} has no active runtime lease",
                    messageId, taskId);
            return ResultMutationOutcome.rejected();
        }

        ResultApplyStatus applyStatus = ctx.outcome().status();
        if (applyStatus == ResultApplyStatus.STALE_LEASE
                || applyStatus == ResultApplyStatus.NO_ACTIVE_LEASE) {
            discardStage(stagedDraft);
            logger.warn("Rejecting result for work item {} because runtime lease rejected the result with {}",
                    messageId, applyStatus);
            return ResultMutationOutcome.rejected();
        }
        return null;
    }

    private ActiveRuntimeProjection rebuildActiveProjection(String taskId,
                                                            String messageId,
                                                            RuntimeResultApplyContext ctx) {
        // Reconstruct lease/work snapshots from the context - no extra runtime reads needed.
        // The context carries all fields the projection pipeline requires.
        ActiveLeaseRecord syntheticLease = syntheticLeaseFromContext(ctx, taskId, messageId);
        TaskWorkEnvelope syntheticWork = syntheticWorkFromContext(ctx, taskId, messageId);
        return buildActiveRuntimeProjection(
                taskId, messageId, syntheticLease, syntheticWork,
                "HANDLE_TASK_RESULT", "runtime active lease defines callback admissibility");
    }

    private ResultMutationOutcome handleAcceptedRuntimeOutcome(Task task,
                                                               String taskId,
                                                               String messageId,
                                                               boolean success,
                                                               String detail,
                                                               String errorCode,
                                                               Map<String, Object> output,
                                                               RuntimeResultApplyContext ctx,
                                                               TaskResultCallbackDraft stagedDraft) {
        ActiveRuntimeProjection activeProjection = rebuildActiveProjection(taskId, messageId, ctx);
        if (activeProjection == null) {
            logger.warn("Cannot ingest task result because msg {} was not found in task {} "
                    + "and no runtime projection could be recovered", messageId, taskId);
            return ResultMutationOutcome.rejected();
        }
        RuntimeWorkSummary workSummary = activeProjection.workSummary();
        AttemptProjectionView activeAttempt = activeProjection.activeAttempt();

        traceEventLogger.callbackAccepted(
                workSummary.toTraceView(),
                success ? "success callback received" : "failure callback received");

        ResultApplyStatus applyStatus = ctx.outcome().status();
        if (success) {
            return handleSuccess(task, taskId, workSummary, activeAttempt, detail, output, stagedDraft);
        }
        return handleRuntimeAcceptedFailure(task, taskId, workSummary, activeAttempt, applyStatus, detail, errorCode, output, stagedDraft);
    }

    ResultMutationOutcome compensateDispatchSubmitFailure(Task task,
                                                              TaskDispatchBinding dispatchBinding,
                                                              String detail) {
        if (task == null || dispatchBinding == null) {
            return ResultMutationOutcome.rejected();
        }

        String taskId = task.getTid();
        String messageId = dispatchBinding.messageId();
        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        TaskWorkEnvelope runtimeWork = taskManager.getTaskWork(taskId, messageId).orElse(null);
        if (activeLease == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no active runtime lease",
                    messageId, taskId);
            return ResultMutationOutcome.rejected();
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
            return ResultMutationOutcome.rejected();
        }
        RuntimeWorkSummary workSummary = activeProjection.workSummary();

        AttemptProjectionView activeAttempt = resolveOrRecoverDispatchAttemptProjection(workSummary, activeLease, dispatchBinding);
        if (activeAttempt == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no recoverable attempt projection",
                    messageId, taskId);
            return ResultMutationOutcome.rejected();
        }

        String normalizedDetail = normalizeDispatchSubmitFailureDetail(detail);
        ResultApplyOutcome workOutcome = applyWorkResult(task, taskId, messageId, activeLease.leaseToken(),
                false, normalizedDetail, DISPATCH_SUBMIT_FAILED_ERROR_CODE, null, true, false);
        if (workOutcome.status() != ResultApplyStatus.RETRY_SCHEDULED) {
            logger.warn("Dispatch submit compensation for msg {} in task {} was rejected by runtime with {}",
                    messageId, taskId, workOutcome.status());
            return ResultMutationOutcome.rejected();
        }

        RuntimeWorkSummary retrySummary = resetForRetryWithoutPublishingAttemptClosure(
                task,
                taskId,
                workSummary,
                activeAttempt,
                normalizedDetail,
                DISPATCH_SUBMIT_FAILED_ERROR_CODE,
                "COMPENSATE_DISPATCH_SUBMIT_FAILURE",
                "dispatch submit failed before transport delivery"
        );
        if (retrySummary == null) {
            return ResultMutationOutcome.rejected();
        }

        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, true);
        if (task != null && !task.getStatus().isFinal()) {
            requestRetryDispatch(task, workRetryDelayMillis);
        }
        return ResultMutationOutcome.acceptedDirty();
    }

    private TaskWorkTraceView resolveTraceWorkView(String taskId,
                                                      String messageId,
                                                      ActiveLeaseRecord activeLease) {
        RuntimeWorkSummary recovered = materializeRuntimeWorkSummary(
                taskId,
                messageId,
                activeLease,
                taskManager.getTaskWork(taskId, messageId).orElse(null)
        );
        return recovered != null
                ? recovered.toTraceView()
                : new TaskWorkTraceView(taskId, messageId, null, null, null, null, MessageStatus.INIT, null, 0, null);
    }

    private ActiveRuntimeProjection buildActiveRuntimeProjection(String taskId,
                                                                 String messageId,
                                                                 ActiveLeaseRecord activeLease,
                                                                 TaskWorkEnvelope runtimeWork,
                                                                 String trigger,
                                                                 String reason) {
        RuntimeWorkSummary activeView = materializeRuntimeWorkSummary(taskId, messageId, activeLease, runtimeWork);
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
        MessageStatus originalStatus = activeView.status();
        activeView = activeView.attachAttempt(activeAttempt);
        if (originalStatus == MessageStatus.INIT && activeView.status() == MessageStatus.ASSIGNED) {
            traceEventLogger.taskWorkStatusTransition(
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

    private RuntimeWorkSummary materializeRuntimeWorkSummary(String taskId,
                                                             String messageId,
                                                             ActiveLeaseRecord activeLease,
                                                             TaskWorkEnvelope runtimeWork) {
        if (activeLease == null && runtimeWork == null) {
            return null;
        }
        RuntimeWorkSummary baseView = runtimeWork != null
                ? RuntimeWorkSummary.fromRuntimeWorkEnvelope(runtimeWork)
                : RuntimeWorkSummary.synthetic(taskId, messageId, activeLease != null ? activeLease.payloadRef() : null);
        if (baseView != null && activeLease != null && baseView.isCompleted()) {
            baseView = baseView.reopenForActiveLease(activeLease);
        }
        return baseView.overlayActiveLease(activeLease);
    }

    private AttemptProjectionView recoverActiveAttemptProjection(RuntimeWorkSummary workSummary,
                                                                 ActiveLeaseRecord activeLease,
                                                                 String preferredAttemptId,
                                                                 Integer preferredAttemptNo) {
        if (workSummary == null || activeLease == null) {
            return null;
        }
        int attemptNo = preferredAttemptNo != null && preferredAttemptNo > 0
                ? preferredAttemptNo
                : Math.max(1, activeLease.retryCount() + 1);
        String attemptId = preferredAttemptId;
        if (attemptId == null || attemptId.isBlank()) {
            attemptId = TaskWorkAttemptIdSupport.runtimeAttemptId(workSummary.messageId(), attemptNo, activeLease);
        }
        return AttemptProjectionView.dispatched(
                workSummary.taskId(),
                workSummary.messageId(),
                activeLease,
                attemptId,
                attemptNo
        );
    }

    private AttemptProjectionView resolveOrRecoverDispatchAttemptProjection(RuntimeWorkSummary workSummary,
                                                                            ActiveLeaseRecord activeLease,
                                                                            TaskDispatchBinding dispatchBinding) {
        if (workSummary == null || activeLease == null || dispatchBinding == null) {
            return null;
        }
        if (dispatchBinding.attemptId() == null || dispatchBinding.attemptId().isBlank()) {
            return null;
        }
        return recoverActiveAttemptProjection(workSummary, activeLease, dispatchBinding.attemptId(), dispatchBinding.attemptNo());
    }

    private ResultMutationOutcome handleSuccess(Task task,
                                              String taskId,
                                              RuntimeWorkSummary workSummary,
                                              AttemptProjectionView activeAttempt,
                                              String detail,
                                              Map<String, Object> output,
                                              TaskResultCallbackDraft stagedDraft) {
        String messageId = workSummary.messageId();
        RuntimeWorkSummary successBase = workSummary;
        if (workSummary.status() == MessageStatus.ASSIGNED) {
            MessageStatus beforeRunningStatus = workSummary.status();
            RuntimeWorkSummary runningView = summarizeRunning(successBase);
            traceEventLogger.taskWorkStatusTransition(
                    runningView.toTraceView(),
                    activeAttempt.attemptId(),
                    activeAttempt.workerId(),
                    activeAttempt.workerContextId(),
                    activeAttempt.batchId(),
                    beforeRunningStatus,
                    runningView.status(),
                    "HANDLE_TASK_RESULT",
                    "TaskManager",
                    "work item entered running from callback"
            );
            successBase = runningView;
        }
        RuntimeWorkSummary successSummary = summarizeSuccess(successBase, detail, output);
        MessageStatus beforeFinalStatus = successBase.status();
        if (!activeAttempt.projectSucceeded(output)) {
            logger.warn("Failed to mark attempt {} as SUCCEEDED", activeAttempt.attemptId());
            return ResultMutationOutcome.rejected();
        }
        traceEventLogger.taskWorkStatusTransition(
                successSummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                beforeFinalStatus,
                successSummary.status(),
                "HANDLE_TASK_RESULT",
                "TaskManager",
                "work item marked success"
        );
        // Projection writes are @CompatibilityProjectionOnly residue - submitted async
        // so they cannot block the runtime callback hot path.
        CommitResult commit = commitVisibleFinal(successSummary, activeAttempt, stagedDraft);
        if (!commit.visible()) {
            logger.warn("Result runtime visible commit failed for success result taskId={}, messageId={}, status={}, reason={}",
                    taskId, messageId, commit.status(), commit.reason());
            return ResultMutationOutcome.acceptedNoop();
        }
        final RuntimeWorkSummary capturedSummary = successSummary;
        final AttemptProjectionView capturedAttempt = activeAttempt;
        taskManager.submitProjectionWrite(() -> {
            persistAttemptProjectionUpsertBestEffort(taskId, messageId, capturedAttempt,
                    "mark attempt success");
            persistWorkProjectionBestEffort(taskId, capturedSummary,
                    "persist success compatibility summary");
        });
        // Event publishing is kept synchronous - it drives downstream state transitions.
        if (task != null) {
            publishWorkAttemptClosed(task, successSummary, activeAttempt,
                    "HANDLE_TASK_RESULT", "work attempt succeeded");
            publishWorkLogicallyFinalOnce(task, successSummary, activeAttempt, commit.row(),
                    "HANDLE_TASK_RESULT", "work item reached stable success");
        }
        cleanupStageIfConverged(stagedDraft, commit.row());
        return ResultMutationOutcome.acceptedDirtyWithProgressBarrier(taskId, messageId, commit.row().seq());
    }

    private ResultMutationOutcome handleRetryableFailure(Task task,
                                                       String taskId,
                                                       RuntimeWorkSummary workSummary,
                                                       AttemptProjectionView activeAttempt,
                                                      String detail,
                                                      String errorCode,
                                                      Map<String, Object> output,
                                                      TaskResultCallbackDraft stagedDraft) {
        RuntimeWorkSummary retrySummary = resetForRetryWithoutPublishingAttemptClosure(
                task,
                taskId,
                workSummary,
                activeAttempt,
                detail,
                errorCode,
                "HANDLE_TASK_RESULT",
                "retry budget allows re-dispatch"
        );
        if (retrySummary == null) {
            return ResultMutationOutcome.rejected();
        }
        discardStage(stagedDraft);
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, true);
        // Event publishing kept synchronous; use the already-loaded task (no extra storage read).
        if (task != null) {
            publishWorkAttemptClosed(task, retrySummary, activeAttempt,
                    "HANDLE_TASK_RESULT", "retryable failure closed the current attempt");
            if (!task.getStatus().isFinal()) {
                requestRetryDispatch(task, workRetryDelayMillis);
            }
        }
        return ResultMutationOutcome.acceptedDirty();
    }

    private RuntimeWorkSummary resetForRetryWithoutPublishingAttemptClosure(Task task,
                                                                            String taskId,
                                                                            RuntimeWorkSummary workSummary,
                                                                            AttemptProjectionView activeAttempt,
                                                                            String detail,
                                                                            String errorCode,
                                                                            String trigger,
                                                                            String resetReason) {
        String messageId = workSummary.messageId();
        AttemptStatus beforeRevokedStatus = activeAttempt.status();
        if (!activeAttempt.projectRetryRevoked(detail, errorCode)) {
            logger.warn("Failed to revoke attempt {} for retry", activeAttempt.attemptId());
            return null;
        }
        traceEventLogger.taskWorkAttemptStatusTransition(
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
        // Attempt projection write is @CompatibilityProjectionOnly - submit async.
        final AttemptProjectionView capturedAttempt = activeAttempt;
        taskManager.submitProjectionWrite(() ->
                persistAttemptProjectionUpsertBestEffort(taskId, messageId, capturedAttempt,
                        "revoke attempt for retry"));

        RuntimeWorkSummary retryBase = buildRetryResetCompatibilityBaseView(workSummary, activeAttempt);
        if (retryBase == null) {
            logger.warn("Failed to recover retry-reset compatibility view for work item {} from status {}",
                    messageId, workSummary != null ? workSummary.status() : null);
            return null;
        }

        MessageStatus beforeRetryFailureStatus = retryBase.status();
        RuntimeWorkSummary failedView = summarizeBusinessFailure(retryBase, detail, errorCode);
        traceEventLogger.taskWorkStatusTransition(
                failedView.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                beforeRetryFailureStatus,
                failedView.status(),
                trigger,
                "TaskManager",
                "work item marked failed before retry reset"
        );
        RuntimeWorkSummary retrySummary = summarizeRetryReset(failedView);
        // Use already-loaded task - no extra getTask() storage read.
        long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, true);
        traceEventLogger.taskWorkRetryReset(retrySummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                workRetryDelayMillis,
                trigger,
                "TaskManager",
                resetReason);
        // Work projection write is @CompatibilityProjectionOnly residue - submit async.
        final RuntimeWorkSummary capturedRetrySummary = retrySummary;
        taskManager.submitProjectionWrite(() ->
                persistWorkProjectionBestEffort(taskId, capturedRetrySummary,
                        "persist retry-reset compatibility summary"));
        return retrySummary;
    }

    private RuntimeWorkSummary buildRetryResetCompatibilityBaseView(RuntimeWorkSummary workSummary,
                                                                    AttemptProjectionView activeAttempt) {
        if (workSummary == null) {
            return null;
        }
        if (workSummary.status() == MessageStatus.ASSIGNED || workSummary.status() == MessageStatus.RUNNING) {
            return workSummary;
        }
        if (workSummary.status() != MessageStatus.INIT || activeAttempt == null) {
            return null;
        }
        return workSummary.withAssignedAttempt(activeAttempt);
    }

    private ResultMutationOutcome handleRetryExhaustedFailure(Task task,
                                                            String taskId,
                                                            RuntimeWorkSummary workSummary,
                                                            AttemptProjectionView activeAttempt,
                                                           String detail,
                                                           String errorCode,
                                                           Map<String, Object> output,
                                                           TaskResultCallbackDraft stagedDraft) {
        String messageId = workSummary.messageId();
        if (!activeAttempt.projectFailed(
                AttemptFinalReason.BUSINESS_FAILURE,
                detail,
                errorCode,
                output)) {
            logger.warn("Failed to mark attempt {} as FAILED", activeAttempt.attemptId());
            return ResultMutationOutcome.rejected();
        }

        MessageStatus beforeFinalStatus = workSummary.status();
        RuntimeWorkSummary failureSummary = summarizeRetryExhaustedFailure(workSummary, detail, errorCode, output);
        traceEventLogger.taskWorkStatusTransition(
                failureSummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.workerContextId(),
                activeAttempt.batchId(),
                beforeFinalStatus,
                failureSummary.status(),
                "HANDLE_TASK_RESULT",
                "TaskManager",
                "work item marked failure"
        );

        // Projection writes are @CompatibilityProjectionOnly residue - submitted async.
        CommitResult commit = commitVisibleFinal(failureSummary, activeAttempt, stagedDraft);
        if (!commit.visible()) {
            logger.warn("Result runtime visible commit failed for failure result taskId={}, messageId={}, status={}, reason={}",
                    taskId, messageId, commit.status(), commit.reason());
            return ResultMutationOutcome.acceptedNoop();
        }
        final RuntimeWorkSummary capturedSummary = failureSummary;
        final AttemptProjectionView capturedAttempt = activeAttempt;
        taskManager.submitProjectionWrite(() -> {
            persistAttemptProjectionUpsertBestEffort(taskId, messageId, capturedAttempt,
                    "mark attempt failure");
            persistWorkProjectionBestEffort(taskId, capturedSummary,
                    "persist exhausted-failure compatibility summary");
        });
        // Event publishing kept synchronous; use the already-loaded task.
        if (task != null) {
            publishWorkAttemptClosed(task, failureSummary, activeAttempt,
                    "HANDLE_TASK_RESULT", "retry budget exhausted closed the current attempt");
            publishWorkLogicallyFinalOnce(task, failureSummary, activeAttempt, commit.row(),
                    "HANDLE_TASK_RESULT", "work item reached stable failure");
        }
        cleanupStageIfConverged(stagedDraft, commit.row());
        return ResultMutationOutcome.acceptedDirtyWithProgressBarrier(taskId, messageId, commit.row().seq());
    }

    private RuntimeWorkSummary summarizeRunning(RuntimeWorkSummary base) {
        return base.markRunning();
    }

    private RuntimeWorkSummary summarizeSuccess(RuntimeWorkSummary base,
                                                String detail,
                                                Map<String, Object> output) {
        return base.completeSuccess(output);
    }

    private RuntimeWorkSummary summarizeBusinessFailure(RuntimeWorkSummary base,
                                                        String detail,
                                                        String errorCode) {
        return base.completeFailure(MessageFinalReason.BUSINESS_FAILED, detail, errorCode, null);
    }

    private RuntimeWorkSummary summarizeExpired(RuntimeWorkSummary base, String detail) {
        return base.completeExpiry(detail, base.errorCode());
    }

    private RuntimeWorkSummary summarizeLeaseExpiryFinal(Task task,
                                                         RuntimeWorkSummary base,
                                                         String detail) {
        return switch (resultContractMode(task)) {
            case BATCH -> summarizeBatchLeaseExpiryFinal(base, detail);
            case SESSION -> summarizeSessionLeaseExpiryFinal(base, detail);
        };
    }

    private RuntimeWorkSummary summarizeRetryReset(RuntimeWorkSummary failedView) {
        return failedView.resetForRetry();
    }

    private RuntimeWorkSummary summarizeRetryExhaustedFailure(RuntimeWorkSummary base,
                                                              String detail,
                                                              String errorCode,
                                                              Map<String, Object> output) {
        return base.completeFailure(MessageFinalReason.RETRY_EXHAUSTED, detail, errorCode, output);
    }

    /**
     * Compatibility projection persistence is best-effort only.
     *
     * <p>Runtime {@code applyResult(...)} has already decided execution truth
     * before this write runs, so projection failure must not redefine callback,
     * expiry, or retry convergence.</p>
     */
    @CompatibilityProjectionOnly
    private void persistWorkProjectionBestEffort(String taskId,
                                                 RuntimeWorkSummary workSummary,
                                                 String action) {
        compatibilityProjectionStore.upsertWorkSummaryBestEffort(
                taskId,
                toProjectionResidue(workSummary),
                action
        );
    }

    @CompatibilityProjectionOnly
    private void persistAttemptProjectionUpsertBestEffort(String taskId,
                                                          String messageId,
                                                          AttemptProjectionView attempt,
                                                          String action) {
        compatibilityProjectionStore.upsertAttemptSummaryBestEffort(
                taskId,
                messageId,
                toProjectionResidue(attempt),
                action
        );
    }

    private TaskCompatibilityProjectionStore.WorkSummaryResidue toProjectionResidue(RuntimeWorkSummary workSummary) {
        if (workSummary == null) {
            return null;
        }
        return new TaskCompatibilityProjectionStore.WorkSummaryResidue(
                workSummary.messageId(),
                workSummary.taskId(),
                workSummary.latestAttemptId(),
                workSummary.latestAttemptWorkerId(),
                workSummary.latestAttemptWorkerContextId(),
                workSummary.latestAttemptBatchId(),
                workSummary.status(),
                workSummary.assignedTime(),
                workSummary.createTime(),
                workSummary.updateTime(),
                workSummary.startTime(),
                workSummary.completeTime(),
                workSummary.retryCount(),
                workSummary.maxRetryCount(),
                workSummary.errorMessage(),
                workSummary.errorCode(),
                workSummary.finalReason(),
                workSummary.payloadRef(),
                workSummary.output()
        );
    }

    private TaskCompatibilityProjectionStore.WorkAttemptResidue toProjectionResidue(AttemptProjectionView attempt) {
        if (attempt == null) {
            return null;
        }
        return new TaskCompatibilityProjectionStore.WorkAttemptResidue(
                attempt.attemptId(),
                attempt.taskId(),
                attempt.messageId(),
                attempt.attemptNo(),
                attempt.workerId(),
                attempt.workerContextId(),
                attempt.batchId(),
                attempt.status(),
                attempt.finalReason(),
                attempt.errorMessage(),
                attempt.errorCode(),
                attempt.output()
        );
    }

    private boolean isCallbackAcceptableMessageState(MessageStatus workStatus) {
        return workStatus == MessageStatus.ASSIGNED
                || workStatus == MessageStatus.RUNNING;
    }

    /**
     * Builds a {@link TaskWorkResult} for engine-internal callback handling.
     *
     * <p>The {@code leaseToken} is intentionally omitted (null) because this is
     * a server-side apply - the engine does not receive a worker-issued token in
     * the callback path. The runtime skips stale-token validation when the token
     * field is blank, and the active-lease presence check acts as the gate.</p>
     */
    private TaskWorkResult buildWorkResultForCallback(Task task,
                                                      String taskId,
                                                      String messageId,
                                                      boolean success,
                                                      String detail,
                                                      String errorCode,
                                                      Map<String, Object> output,
                                                      boolean retryable,
                                                      boolean expired) {
        TaskWorkResult result;
        if (success) {
            result = TaskWorkResult.success(taskId, messageId, null, detail, output);
        } else if (expired) {
            result = TaskWorkResult.expired(taskId, messageId, null, detail, retryable);
        } else {
            result = TaskWorkResult.failure(taskId, messageId, null, errorCode, detail, output, retryable);
        }
        if (retryable) {
            long workRetryDelayMillis = resolveWorkRetryDelayMillis(task, true);
            if (workRetryDelayMillis > 0L) {
                result = result.withRetryVisibleAt(result.completedAt().plusMillis(workRetryDelayMillis));
            }
        }
        return result;
    }

    /**
     * Reconstructs a minimal {@link ActiveLeaseRecord} from an
     * {@link RuntimeResultApplyContext} snapshot so the existing projection
     * pipeline can continue to use lease-based logic without extra runtime reads.
     */
    private static ActiveLeaseRecord syntheticLeaseFromContext(RuntimeResultApplyContext ctx,
                                                               String taskId,
                                                               String messageId) {
        return new ActiveLeaseRecord(
                taskId,
                messageId,
                ctx.activeLeaseToken(),
                ctx.workerId(),
                ctx.workerContextId(),
                ctx.batchId(),
                ctx.payloadRef(),
                ctx.retryCount(),
                null,         // leaseExpireAt - not needed for projection
                ctx.leasedAt()
        );
    }

    /**
     * Reconstructs a minimal {@link TaskWorkEnvelope} from an
     * {@link RuntimeResultApplyContext} snapshot. Only fields required by the
     * projection pipeline (retryCount, maxRetryCount, payloadRef) are populated.
     */
    private static TaskWorkEnvelope syntheticWorkFromContext(RuntimeResultApplyContext ctx,
                                                             String taskId,
                                                             String messageId) {
        return new TaskWorkEnvelope(
                taskId,
                messageId,
                null,              // eventCode - not needed for projection
                null,              // payload - not in context
                ctx.payloadRef(),
                ctx.retryCount(),
                ctx.maxRetryCount(),
                null,              // shardKey
                null,              // nextVisibleAt
                java.time.Instant.now()  // createdAt - best approximation without work envelope
        );
    }

    private boolean isExpiryAcceptableMessageState(MessageStatus workStatus) {
        return workStatus == MessageStatus.ASSIGNED
                || workStatus == MessageStatus.RUNNING;
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

    private boolean shouldRetryExpiredLease(Task task, MessageStatus fromStatus) {
        return switch (resultContractMode(task)) {
            case BATCH -> true;
            case SESSION -> fromStatus == MessageStatus.ASSIGNED;
        };
    }

    private ResultContractMode resultContractMode(Task task) {
        return task != null && task.getContract() == TaskContract.BATCH
                ? ResultContractMode.BATCH
                : ResultContractMode.SESSION;
    }

    private boolean isManualOrPolicyTerminalStop(Task task) {
        if (task == null || task.getTerminalReason() == null) {
            return false;
        }
        TaskTerminalReason terminalReason = task.getTerminalReason();
        return terminalReason == TaskTerminalReason.MANUAL_CANCELLED || terminalReason.isPolicyDrivenStop();
    }

    private RuntimeWorkSummary recentFinalMessage(Task task, String taskId, String messageId) {
        if (taskId == null || taskId.isBlank() || messageId == null || messageId.isBlank()) {
            return null;
        }
        return taskManager.getRecentFinalReceipt(taskId, messageId)
                .map(receipt -> RuntimeWorkSummary.fromRecentFinalReceipt(task, receipt))
                .orElse(null);
    }

    private TaskResultCallbackDraft buildCallbackDraft(Task task,
                                                       String taskId,
                                                       String messageId,
                                                       boolean success,
                                                       String detail,
                                                       String errorCode,
                                                       Map<String, Object> output) {
        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        TaskWorkEnvelope runtimeWork = taskManager.getTaskWork(taskId, messageId).orElse(null);
        String attemptId = activeLease == null
                ? null
                : TaskWorkAttemptIdSupport.runtimeAttemptId(messageId, Math.max(1, activeLease.retryCount() + 1), activeLease);
        String identityDigest = callbackIdentityDigest(taskId, messageId, success, detail, errorCode, output, activeLease);
        return new TaskResultCallbackDraft(
                TaskResultCallbackDraft.stageId(taskId, messageId, identityDigest),
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                output,
                Instant.now(),
                attemptId,
                activeLease != null ? activeLease.leaseToken() : null,
                null,
                null,
                null,
                identityDigest,
                activeLease != null ? activeLease.workerId() : null,
                activeLease != null ? activeLease.workerContextId() : null,
                activeLease != null ? activeLease.batchId() : null,
                activeLease != null ? activeLease.payloadRef() : runtimeWork != null ? runtimeWork.payloadRef() : null,
                runtimeWork != null ? runtimeWork.eventCode() : task != null ? com.xa.mass.base.model.TaskSharedConfig.sdkEventCode(task) : null,
                activeLease != null ? activeLease.retryCount() : runtimeWork != null ? runtimeWork.retryCount() : 0,
                runtimeWork != null ? runtimeWork.maxRetryCount() : 0,
                activeLease != null ? activeLease.leasedAt() : null,
                runtimeWork != null ? runtimeWork.createdAt() : Instant.now()
        );
    }

    private String callbackIdentityDigest(String taskId,
                                          String messageId,
                                          boolean success,
                                          String detail,
                                          String errorCode,
                                          Map<String, Object> output,
                                          ActiveLeaseRecord activeLease) {
        String raw = String.join("|",
                Objects.toString(taskId, ""),
                Objects.toString(messageId, ""),
                Objects.toString(activeLease != null ? activeLease.leaseToken() : null, ""),
                Objects.toString(activeLease != null ? activeLease.workerId() : null, ""),
                Boolean.toString(success),
                Objects.toString(errorCode, ""),
                Objects.toString(detail, ""),
                Objects.toString(output, ""));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute result callback identity digest", e);
        }
    }

    private CommitResult commitVisibleFinal(RuntimeWorkSummary summary,
                                            AttemptProjectionView attempt,
                                            TaskResultCallbackDraft stagedDraft) {
        if (summary == null) {
            return CommitResult.rejected("summary must not be null");
        }
        return taskResultRuntime.commitVisibleFinal(new TaskResultFinalDraft(
                summary.taskId(),
                summary.messageId(),
                stagedDraft != null ? stagedDraft.eventCode() : null,
                summary.status() != null ? summary.status().name() : null,
                summary.finalReason() != null ? summary.finalReason().name() : null,
                summary.retryCount(),
                summary.maxRetryCount(),
                attempt != null ? attempt.workerId() : summary.latestAttemptWorkerId(),
                attempt != null ? attempt.workerContextId() : summary.latestAttemptWorkerContextId(),
                attempt != null ? attempt.batchId() : summary.latestAttemptBatchId(),
                attempt != null ? attempt.attemptId() : summary.latestAttemptId(),
                summary.payloadRef(),
                toInstant(summary.createTime()),
                toInstant(summary.assignedTime()),
                toInstant(summary.startTime()),
                toInstant(summary.completeTime()),
                toInstant(summary.updateTime()),
                summary.errorCode(),
                summary.errorMessage(),
                summary.output(),
                stagedDraft != null ? stagedDraft.stageId() : null
        ));
    }

    private void publishWorkLogicallyFinalOnce(Task task,
                                               RuntimeWorkSummary workSummary,
                                               AttemptProjectionView attempt,
                                               TaskResultRuntimeRow row,
                                               String trigger,
                                               String reason) {
        if (row == null) {
            return;
        }
        BarrierClaim claim = taskResultRuntime.claimLogicalFinalPublish(row.taskId(), row.messageId(), row.seq());
        if (!claim.claimedByCaller()) {
            return;
        }
        publishWorkLogicallyFinal(task, workSummary, attempt, trigger, reason);
        taskResultRuntime.markLogicalFinalPublished(row.taskId(), row.messageId(), row.seq());
    }

    private void cleanupStageIfConverged(TaskResultCallbackDraft stagedDraft, TaskResultRuntimeRow row) {
        if (stagedDraft == null || row == null) {
            return;
        }
        TaskResultRuntimeRow current = taskResultRuntime.getVisibleByMessageId(row.taskId(), row.messageId()).orElse(row);
        if (current.logicalFinalPublished() && current.progressApplied()) {
            discardStage(stagedDraft);
        }
    }

    private void discardStage(TaskResultCallbackDraft stagedDraft) {
        if (stagedDraft != null) {
            taskResultRuntime.discardStagedCallback(stagedDraft.stageId());
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private String normalizeDispatchSubmitFailureDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "dispatch submit failed before transport delivery";
        }
        return detail;
    }

    void shutdown() {
        repairExecutor.shutdownNow();
    }

    private void startRepairPump() {
        if (!Boolean.getBoolean("xa.mass.engine.resultRepairPumpDisabled")) {
            long intervalMillis = Long.getLong("xa.mass.engine.resultRepairPumpIntervalMillis", 1_000L);
            repairExecutor.scheduleWithFixedDelay(
                    this::repairResultRuntimeCandidatesSafely,
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void repairResultRuntimeCandidatesSafely() {
        try {
            repairResultRuntimeCandidates(Integer.getInteger("xa.mass.engine.resultRepairPumpBatchSize", 100));
        } catch (Exception e) {
            logger.warn("Result runtime repair pump failed: {}", e.getMessage(), e);
        }
    }

    int repairResultRuntimeCandidates(int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<TaskResultRepairCandidate> candidates = taskResultRuntime.scanRepairCandidates(limit);
        int repaired = 0;
        for (TaskResultRepairCandidate candidate : candidates) {
            if (repairResultRuntimeCandidate(candidate)) {
                repaired++;
            }
        }
        return repaired;
    }

    private boolean repairResultRuntimeCandidate(TaskResultRepairCandidate candidate) {
        if (candidate == null || candidate.draft() == null) {
            return false;
        }
        TaskResultCallbackDraft draft = candidate.draft();
        Task task = taskManager.getTask(draft.taskId());
        RecentFinalWorkReceipt receipt = taskManager.getRecentFinalReceipt(draft.taskId(), draft.messageId()).orElse(null);
        if (receipt == null || taskResultRuntime.getVisibleByMessageId(draft.taskId(), draft.messageId()).isPresent()) {
            return false;
        }
        RuntimeWorkSummary summary = rebuildFinalSummaryForRepair(task, draft, receipt);
        CommitResult commit = commitVisibleFinal(summary, null, draft);
        if (!commit.visible()) {
            return false;
        }
        if (task != null) {
            publishWorkLogicallyFinalOnce(task, summary, null, commit.row(),
                    "REPAIR_RESULT_RUNTIME", "result runtime repair resumed missing logical-final event");
            taskManager.applyTaskResultProgressOnce(commit.row().taskId(), commit.row().messageId(), commit.row().seq());
        }
        cleanupStageIfConverged(draft, commit.row());
        return true;
    }

    private RuntimeWorkSummary rebuildFinalSummaryForRepair(Task task,
                                                            TaskResultCallbackDraft draft,
                                                            RecentFinalWorkReceipt receipt) {
        RuntimeWorkSummary receiptSummary = RuntimeWorkSummary.fromRecentFinalReceipt(task, receipt);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createTime = draft.createTime() == null
                ? now
                : LocalDateTime.ofInstant(draft.createTime(), ZoneId.systemDefault());
        LocalDateTime leasedAt = draft.leasedAt() == null
                ? null
                : LocalDateTime.ofInstant(draft.leasedAt(), ZoneId.systemDefault());
        LocalDateTime completedAt = receipt.completedAt() == null
                ? now
                : LocalDateTime.ofInstant(receipt.completedAt(), ZoneId.systemDefault());
        return new RuntimeWorkSummary(
                draft.messageId(),
                draft.taskId(),
                draft.attemptId(),
                draft.workerId(),
                draft.workerContextId(),
                draft.batchId(),
                receiptSummary.status(),
                leasedAt,
                createTime,
                completedAt,
                leasedAt,
                completedAt,
                Math.max(0, receipt.retryCount()),
                Math.max(0, draft.maxRetryCount()),
                draft.detail(),
                receipt.errorCode() != null ? receipt.errorCode() : draft.errorCode(),
                receiptSummary.finalReason(),
                draft.payloadRef(),
                draft.output()
        );
    }

    private void requestRetryDispatch(Task task, long workRetryDelayMillis) {
        taskManager.requestTaskRetryDispatch(task, workRetryDelayMillis);
    }

    private void publishWorkAttemptClosed(Task task,
                                          RuntimeWorkSummary workSummary,
                                          AttemptProjectionView attempt,
                                          String trigger,
                                          String reason) {
        traceEventLogger.taskWorkAttemptClosed(
                task,
                workSummary.toTraceView(),
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
        taskManager.publishTaskWorkAttemptClosed(
                task,
                TaskWorkAttemptClosedEvent.from(
                        workSummary.taskId(),
                        workSummary.messageId(),
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

    private void publishWorkLogicallyFinal(Task task,
                                           RuntimeWorkSummary workSummary,
                                           AttemptProjectionView attempt,
                                           String trigger,
                                           String reason) {
        traceEventLogger.taskWorkLogicallyFinal(
                task,
                workSummary.toTraceView(),
                attempt != null ? attempt.attemptId() : null,
                attempt != null ? attempt.workerId() : null,
                attempt != null ? attempt.workerContextId() : null,
                attempt != null ? attempt.batchId() : null,
                trigger,
                "TaskManager",
                reason
        );
        taskManager.publishTaskWorkLogicallyFinal(
                task,
                TaskWorkLogicallyFinalEvent.from(
                        workSummary.taskId(),
                        workSummary.messageId(),
                        workSummary.status(),
                        workSummary.finalReason(),
                        workSummary.retryCount(),
                        workSummary.errorCode(),
                        workSummary.errorMessage(),
                        workSummary.payloadRef(),
                        workSummary.output()
                )
        );
    }

    private ResultMutationOutcome handleRuntimeAcceptedFailure(Task task,
                                                             String taskId,
                                                             RuntimeWorkSummary workSummary,
                                                             AttemptProjectionView activeAttempt,
                                                             ResultApplyStatus applyStatus,
                                                             String detail,
                                                             String errorCode,
                                                             Map<String, Object> output,
                                                             TaskResultCallbackDraft stagedDraft) {
        return switch (resultContractMode(task)) {
            case BATCH -> handleBatchRuntimeAcceptedFailure(
                    task, taskId, workSummary, activeAttempt, applyStatus, detail, errorCode, output, stagedDraft);
            case SESSION -> handleSessionRuntimeAcceptedFailure(
                    task, taskId, workSummary, activeAttempt, applyStatus, detail, errorCode, output, stagedDraft);
        };
    }

    private ResultMutationOutcome handleBatchRuntimeAcceptedFailure(Task task,
                                                                  String taskId,
                                                                  RuntimeWorkSummary workSummary,
                                                                  AttemptProjectionView activeAttempt,
                                                                  ResultApplyStatus applyStatus,
                                                                  String detail,
                                                                  String errorCode,
                                                                  Map<String, Object> output,
                                                                  TaskResultCallbackDraft stagedDraft) {
        if (applyStatus == ResultApplyStatus.RETRY_SCHEDULED) {
            return handleRetryableFailure(task, taskId, workSummary, activeAttempt, detail, errorCode, output, stagedDraft);
        }
        return handleRetryExhaustedFailure(task, taskId, workSummary, activeAttempt, detail, errorCode, output, stagedDraft);
    }

    private ResultMutationOutcome handleSessionRuntimeAcceptedFailure(Task task,
                                                                    String taskId,
                                                                    RuntimeWorkSummary workSummary,
                                                                    AttemptProjectionView activeAttempt,
                                                                    ResultApplyStatus applyStatus,
                                                                    String detail,
                                                                    String errorCode,
                                                                    Map<String, Object> output,
                                                                    TaskResultCallbackDraft stagedDraft) {
        if (applyStatus == ResultApplyStatus.RETRY_SCHEDULED) {
            return handleRetryableFailure(task, taskId, workSummary, activeAttempt, detail, errorCode, output, stagedDraft);
        }
        return handleRetryExhaustedFailure(task, taskId, workSummary, activeAttempt, detail, errorCode, output, stagedDraft);
    }

    private RuntimeWorkSummary summarizeBatchLeaseExpiryFinal(RuntimeWorkSummary base, String detail) {
        return summarizeRetryExhaustedFailure(base, detail, LEASE_EXPIRED_ERROR_CODE, null);
    }

    private RuntimeWorkSummary summarizeSessionLeaseExpiryFinal(RuntimeWorkSummary base, String detail) {
        return summarizeExpired(base, detail);
    }

    private enum ResultContractMode {
        SESSION,
        BATCH
    }

    static final class ResultMutationOutcome {
        enum Status {
            ACCEPTED_DIRTY,
            ACCEPTED_NOOP,
            REJECTED
        }

        private final Status status;
        private final String progressTaskId;
        private final String progressMessageId;
        private final long progressSeq;

        private ResultMutationOutcome(Status status) {
            this(status, null, null, 0L);
        }

        private ResultMutationOutcome(Status status, String progressTaskId, String progressMessageId, long progressSeq) {
            this.status = java.util.Objects.requireNonNull(status, "status");
            this.progressTaskId = progressTaskId;
            this.progressMessageId = progressMessageId;
            this.progressSeq = progressSeq;
        }

        static ResultMutationOutcome rejected() {
            return new ResultMutationOutcome(Status.REJECTED);
        }

        static ResultMutationOutcome acceptedNoop() {
            return new ResultMutationOutcome(Status.ACCEPTED_NOOP);
        }

        static ResultMutationOutcome acceptedDirty() {
            return new ResultMutationOutcome(Status.ACCEPTED_DIRTY);
        }

        static ResultMutationOutcome acceptedDirtyWithProgressBarrier(String taskId, String messageId, long seq) {
            return new ResultMutationOutcome(Status.ACCEPTED_DIRTY, taskId, messageId, seq);
        }

        Status status() {
            return status;
        }

        boolean accepted() {
            return status != Status.REJECTED;
        }

        boolean progressDirty() {
            return status == Status.ACCEPTED_DIRTY;
        }

        boolean hasProgressBarrier() {
            return progressTaskId != null && progressMessageId != null && progressSeq > 0L;
        }

        String progressTaskId() {
            return progressTaskId;
        }

        String progressMessageId() {
            return progressMessageId;
        }

        long progressSeq() {
            return progressSeq;
        }
    }

    private record ActiveRuntimeProjection(RuntimeWorkSummary workSummary, AttemptProjectionView activeAttempt) {
    }

    static final class AttemptProjectionView {
        private final String attemptId;
        private final String taskId;
        private final String messageId;
        private final int attemptNo;
        private final String workerId;
        private final String workerContextId;
        private final String batchId;
        private AttemptStatus status;
        private AttemptFinalReason finalReason;
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
            this.status = AttemptStatus.DISPATCHED;
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

        AttemptStatus status() {
            return status;
        }

        AttemptFinalReason finalReason() {
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

        boolean projectExpired(AttemptFinalReason nextFinalReason, String nextErrorMessage) {
            if (status == null || status.isFinal()) {
                return false;
            }
            status = AttemptStatus.EXPIRED;
            finalReason = nextFinalReason;
            errorMessage = nextErrorMessage;
            return true;
        }

        boolean projectSucceeded(Map<String, Object> nextOutput) {
            if (status == null || status.isFinal()) {
                return false;
            }
            status = AttemptStatus.SUCCEEDED;
            finalReason = AttemptFinalReason.SUCCESS;
            output = copyMap(nextOutput);
            return true;
        }

        boolean projectRetryRevoked(String nextErrorMessage, String nextErrorCode) {
            if (status == null || status.isFinal()) {
                return false;
            }
            status = AttemptStatus.REVOKED;
            finalReason = AttemptFinalReason.REVOKED_FOR_RETRY;
            errorMessage = nextErrorMessage;
            errorCode = nextErrorCode;
            output = null;
            return true;
        }

        boolean projectFailed(AttemptFinalReason nextFinalReason,
                              String nextErrorMessage,
                              String nextErrorCode,
                              Map<String, Object> nextOutput) {
            if (status == null || status.isFinal()) {
                return false;
            }
            status = AttemptStatus.FAILED;
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

    record RuntimeWorkSummary(String messageId,
                              String taskId,
                              String latestAttemptId,
                              String latestAttemptWorkerId,
                              String latestAttemptWorkerContextId,
                              String latestAttemptBatchId,
                              MessageStatus status,
                              LocalDateTime assignedTime,
                              LocalDateTime createTime,
                              LocalDateTime updateTime,
                              LocalDateTime startTime,
                              LocalDateTime completeTime,
                              int retryCount,
                              int maxRetryCount,
                              String errorMessage,
                              String errorCode,
                              MessageFinalReason finalReason,
                              String payloadRef,
                              Map<String, Object> output) {

        private static RuntimeWorkSummary synthetic(String taskId, String messageId, String payloadRef) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeWorkSummary(
                    messageId,
                    taskId,
                    null,
                    null,
                    null,
                    null,
                    MessageStatus.INIT,
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

        private static RuntimeWorkSummary fromRuntimeWorkEnvelope(TaskWorkEnvelope runtimeWork) {
            if (runtimeWork == null) {
                return null;
            }
            LocalDateTime createdAt = runtimeWork.createdAt() == null
                    ? LocalDateTime.now()
                    : LocalDateTime.ofInstant(runtimeWork.createdAt(), java.time.ZoneId.systemDefault());
            return new RuntimeWorkSummary(
                    runtimeWork.messageId(),
                    runtimeWork.taskId(),
                    null,
                    null,
                    null,
                    null,
                    MessageStatus.INIT,
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

        static RuntimeWorkSummary fromRecentFinalReceipt(Task task, RecentFinalWorkReceipt receipt) {
            if (receipt == null) {
                return null;
            }
            LocalDateTime completedAt = receipt.completedAt() == null
                    ? LocalDateTime.now()
                    : LocalDateTime.ofInstant(receipt.completedAt(), java.time.ZoneId.systemDefault());
            boolean batchLeaseExpiryFinalizedAsFailure = task != null
                    && task.getContract() == TaskContract.BATCH
                    && receipt.status() == com.xa.mass.runtime.api.TaskWorkFinalStatus.EXPIRED;
            MessageStatus status = switch (receipt.status()) {
                case SUCCESS -> MessageStatus.SUCCESS;
                case FAILED -> MessageStatus.FAILED;
                case EXPIRED -> batchLeaseExpiryFinalizedAsFailure ? MessageStatus.FAILED : MessageStatus.EXPIRED;
            };
            MessageFinalReason finalReason = switch (receipt.status()) {
                case SUCCESS -> MessageFinalReason.BUSINESS_SUCCESS;
                case FAILED -> MessageFinalReason.RETRY_EXHAUSTED;
                case EXPIRED -> batchLeaseExpiryFinalizedAsFailure
                        ? MessageFinalReason.RETRY_EXHAUSTED
                        : MessageFinalReason.LEASE_EXPIRED;
            };
            return new RuntimeWorkSummary(
                    receipt.messageId(),
                    receipt.taskId(),
                    null,
                    null,
                    null,
                    null,
                    status,
                    null,
                    completedAt,
                    completedAt,
                    null,
                    completedAt,
                    Math.max(0, receipt.retryCount()),
                    Math.max(0, receipt.retryCount()),
                    null,
                    receipt.errorCode(),
                    finalReason,
                    null,
                    null
            );
        }

        private boolean isCompleted() {
            return status != null && status.isFinal();
        }

        private RuntimeWorkSummary reopenForActiveLease(ActiveLeaseRecord activeLease) {
            if (!isCompleted() || activeLease == null) {
                return this;
            }
            int runtimeRetryCount = Math.max(0, activeLease.retryCount());
            LocalDateTime leasedAt = activeLease.leasedAt() == null
                    ? null
                    : LocalDateTime.ofInstant(activeLease.leasedAt(), java.time.ZoneId.systemDefault());
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeWorkSummary(
                    messageId,
                    taskId,
                    null,
                    activeLease.workerId(),
                    activeLease.workerContextId(),
                    activeLease.batchId(),
                    MessageStatus.ASSIGNED,
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

        private RuntimeWorkSummary overlayActiveLease(ActiveLeaseRecord activeLease) {
            if (activeLease == null || isCompleted()) {
                return this;
            }
            int runtimeRetryCount = Math.max(0, activeLease.retryCount());
            boolean needsAssignedStatus = status == null || status == MessageStatus.INIT;
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
            MessageStatus nextStatus = needsAssignedStatus ? MessageStatus.ASSIGNED : status;
            LocalDateTime nextAssignedTime = assignedTime != null
                    ? assignedTime
                    : leasedAt != null ? leasedAt : now;
            LocalDateTime nextUpdateTime = needsAssignedStatus ? now : updateTime;
            return new RuntimeWorkSummary(
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

        private RuntimeWorkSummary withAssignedAttempt(AttemptProjectionView attempt) {
            if (attempt == null) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime nextAssignedTime = assignedTime != null ? assignedTime : now;
            return new RuntimeWorkSummary(
                    messageId,
                    taskId,
                    attempt.attemptId(),
                    attempt.workerId(),
                    attempt.workerContextId(),
                    attempt.batchId(),
                    MessageStatus.ASSIGNED,
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

        private RuntimeWorkSummary attachAttempt(AttemptProjectionView attempt) {
            if (attempt == null) {
                return this;
            }
            boolean alreadyAttached = java.util.Objects.equals(latestAttemptId, attempt.attemptId())
                    && java.util.Objects.equals(latestAttemptWorkerId, attempt.workerId())
                    && java.util.Objects.equals(latestAttemptWorkerContextId, attempt.workerContextId())
                    && java.util.Objects.equals(latestAttemptBatchId, attempt.batchId());
            MessageStatus nextStatus = status == null || status == MessageStatus.INIT
                    ? MessageStatus.ASSIGNED
                    : status;
            if (alreadyAttached && nextStatus == status && assignedTime != null) {
                return this;
            }
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeWorkSummary(
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

        private RuntimeWorkSummary markRunning() {
            if (status == MessageStatus.RUNNING) {
                return this;
            }
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeWorkSummary(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    MessageStatus.RUNNING,
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

        private RuntimeWorkSummary completeSuccess(Map<String, Object> nextOutput) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeWorkSummary(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    MessageStatus.SUCCESS,
                    assignedTime,
                    createTime,
                    updateTime,
                    startTime,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    null,
                    null,
                    MessageFinalReason.BUSINESS_SUCCESS,
                    payloadRef,
                    nextOutput == null ? null : new java.util.LinkedHashMap<>(nextOutput)
            ).complete(now);
        }

        private RuntimeWorkSummary completeFailure(MessageFinalReason nextFinalReason,
                                                  String nextDetail,
                                                  String nextErrorCode,
                                                  Map<String, Object> nextOutput) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeWorkSummary(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    MessageStatus.FAILED,
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

        private RuntimeWorkSummary completeExpiry(String nextDetail, String nextErrorCode) {
            LocalDateTime now = LocalDateTime.now();
            return new RuntimeWorkSummary(
                    messageId,
                    taskId,
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId,
                    MessageStatus.EXPIRED,
                    assignedTime,
                    createTime,
                    updateTime,
                    startTime,
                    completeTime,
                    retryCount,
                    maxRetryCount,
                    nextDetail,
                    nextErrorCode,
                    MessageFinalReason.LEASE_EXPIRED,
                    payloadRef,
                    null
            ).complete(now);
        }

        private RuntimeWorkSummary resetForRetry() {
            return new RuntimeWorkSummary(
                    messageId,
                    taskId,
                    null,
                    null,
                    null,
                    null,
                    MessageStatus.INIT,
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

        private RuntimeWorkSummary complete(LocalDateTime completedAt) {
            LocalDateTime now = completedAt != null ? completedAt : LocalDateTime.now();
            return new RuntimeWorkSummary(
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

        private TaskWorkTraceView toTraceView() {
            return new TaskWorkTraceView(
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
