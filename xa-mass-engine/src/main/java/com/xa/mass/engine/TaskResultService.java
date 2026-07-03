package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchDeliveryFailure;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptFinalReason;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptStatus;
import com.xa.mass.engine.TaskWorkLifecycleState.MessageFinalReason;
import com.xa.mass.engine.TaskWorkLifecycleState.MessageStatus;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicy;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.ResultFinalityPolicy;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.engine.TraceEventLogger.TaskWorkTraceView;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.BarrierMarkResult;
import com.xa.mass.runtime.api.CommitResult;
import com.xa.mass.runtime.api.CommitResultStatus;
import com.xa.mass.runtime.api.RecentFinalWorkReceipt;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.RuntimeResultApplyContext;
import com.xa.mass.runtime.api.StageResult;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultRepairCandidate;
import com.xa.mass.runtime.api.TaskResultRepairKind;
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

/**
 * Owns runtime work-result handling, retry sequencing, and result-side event ordering.
 */
class TaskResultService {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultService.class);
    static final String DISPATCH_SUBMIT_FAILED_ERROR_CODE = "DISPATCH_SUBMIT_FAILED";
    private static final String LEASE_EXPIRED_ERROR_CODE = "LEASE_EXPIRED";

    private final TaskManager taskManager;
    private final TaskResultRuntime taskResultRuntime;
    private final TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver;
    private final TraceEventLogger traceEventLogger;
    private final TaskResultVisibleFinalCommitter visibleFinalCommitter;
    private final TaskResultRepairPump repairPump;

    TaskResultService(TaskManager taskManager,
                      TaskResultRuntime taskResultRuntime,
                      TaskRuntimeRetryPolicyResolver taskRuntimeRetryPolicyResolver,
                      TraceEventLogger traceEventLogger) {
        this.taskManager = taskManager;
        this.taskResultRuntime = taskResultRuntime;
        this.taskRuntimeRetryPolicyResolver = taskRuntimeRetryPolicyResolver;
        this.traceEventLogger = traceEventLogger;
        this.visibleFinalCommitter = new TaskResultVisibleFinalCommitter(taskResultRuntime);
        this.repairPump = new TaskResultRepairPump(this::repairResultRuntimeCandidates);
        this.repairPump.start();
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
        ActiveRuntimeView activeRuntimeView = buildActiveRuntimeView(
                taskId,
                messageId,
                activeLease,
                runtimeWork,
                "EXPIRE_LEASED_WORK",
                "runtime active lease defines expiry admissibility"
        );
        if (activeRuntimeView == null) {
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "runtime view could not be recovered", 0);
            return ResultMutationOutcome.rejected();
        }
        RuntimeWorkSummary workSummary = activeRuntimeView.workSummary();
        if (workSummary.isCompleted()) {
            logger.info("Work item {} of task {} is already in final status {}, skip expiry",
                    messageId, taskId, workSummary.status());
            return ResultMutationOutcome.rejected();
        }
        RuntimeAttemptView activeAttempt = activeRuntimeView.activeAttempt();
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
            visibleFinalCommitter.discardStage(stagedDraft);
            LogUtils.logOperationFailure("EXPIRE_MSG_ERROR", "runtime lease rejected expiry result: " + workOutcome.status(), 0);
            return ResultMutationOutcome.rejected();
        }
        if (!activeAttempt.projectExpired(AttemptFinalReason.LEASE_EXPIRED, "leased work expired")) {
            visibleFinalCommitter.discardStage(stagedDraft);
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
                    activeAttempt.batchId(),
                    workRetryDelayMillis,
                    "EXPIRE_LEASED_WORK", "TaskManager", "lease expired but retry budget allows re-dispatch");
            currentSummary = retrySummary;
            visibleFinalCommitter.discardStage(stagedDraft);
        } else {
            logicallyFinalSummary = summarizeLeaseExpiryFinal(task, workSummary, expiryDetail);
            traceEventLogger.taskWorkStatusTransition(
                    logicallyFinalSummary.toTraceView(),
                    activeAttempt.attemptId(),
                    activeAttempt.workerId(),
                    activeAttempt.batchId(),
                    fromStatus,
                    logicallyFinalSummary.status(),
                    "EXPIRE_LEASED_WORK",
                    "TaskManager",
                    expiryDetail
            );
            visibleFinalCommit = visibleFinalCommitter.commitVisibleFinal(logicallyFinalSummary, activeAttempt, stagedDraft);
            if (!visibleFinalCommit.visible()) {
                logger.warn("Result runtime visible commit failed for lease expiry taskId={}, messageId={}, status={}, reason={}",
                        taskId, messageId, visibleFinalCommit.status(), visibleFinalCommit.reason());
                return ResultMutationOutcome.acceptedNoop();
            }
            currentSummary = logicallyFinalSummary;
        }
        traceEventLogger.leaseExpired(
                workSummary.toTraceView(),
                activeAttempt.attemptId(),
                activeAttempt.workerId(),
                activeAttempt.batchId(),
                fromStatus,
                currentSummary.status(),
                currentSummary.errorCode(),
                "EXPIRE_LEASED_WORK",
                "TaskManager",
                expiryDetail
        );
        if (freshTask != null && activeAttempt != null) {
            if (retryScheduled) {
                publishWorkAttemptClosed(freshTask, currentSummary, activeAttempt,
                        "EXPIRE_LEASED_WORK", "lease expiry closed the current attempt before re-dispatch");
            } else {
                publishWorkAttemptClosedOnce(freshTask, currentSummary, activeAttempt, visibleFinalCommit.row(),
                        "EXPIRE_LEASED_WORK", expiryDetail);
            }
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
        visibleFinalCommitter.cleanupStageIfConverged(taskId, messageId, visibleFinalCommit.row());
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
            // No active lease at apply time - duplicate or late callback.
            RuntimeWorkSummary recentFinal = recentFinalMessage(task, taskId, messageId);
            if (recentFinal != null) {
                visibleFinalCommitter.discardStageIfVisibleFinalExists(taskId, messageId, stagedDraft);
                traceEventLogger.callbackIgnoredDuplicate(recentFinal.toTraceView(),
                        "work item already final in recent runtime receipt with status " + recentFinal.status());
                logger.info("Work item {} of task {} is already in final status {}, skipping duplicate result",
                        messageId, taskId, recentFinal.status());
                return ResultMutationOutcome.acceptedNoop();
            }
            visibleFinalCommitter.discardStage(stagedDraft);
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
            visibleFinalCommitter.discardStage(stagedDraft);
            logger.warn("Rejecting result for work item {} because runtime lease rejected the result with {}",
                    messageId, applyStatus);
            return ResultMutationOutcome.rejected();
        }
        return null;
    }

    private ActiveRuntimeView rebuildActiveRuntimeView(String taskId,
                                                            String messageId,
                                                            RuntimeResultApplyContext ctx) {
        // Reconstruct lease/work snapshots from the context - no extra runtime reads needed.
        // The context carries all fields the runtime view reconstruction requires.
        ActiveLeaseRecord syntheticLease = syntheticLeaseFromContext(ctx, taskId, messageId);
        TaskWorkEnvelope syntheticWork = syntheticWorkFromContext(ctx, taskId, messageId);
        return buildActiveRuntimeView(
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
        ActiveRuntimeView activeRuntimeView = rebuildActiveRuntimeView(taskId, messageId, ctx);
        if (activeRuntimeView == null) {
            logger.warn("Cannot ingest task result because msg {} was not found in task {} "
                    + "and no runtime view could be recovered", messageId, taskId);
            return ResultMutationOutcome.rejected();
        }
        RuntimeWorkSummary workSummary = activeRuntimeView.workSummary();
        RuntimeAttemptView activeAttempt = activeRuntimeView.activeAttempt();

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
        return compensateDispatchDeliveryFailure(
                task,
                new TaskDispatchDeliveryFailure(
                        task.getTid(),
                        dispatchBinding.messageId(),
                        dispatchBinding.attemptId(),
                        dispatchBinding.attemptNo(),
                        dispatchBinding.workerId(),
                        detail
                )
        );
    }

    ResultMutationOutcome compensateDispatchDeliveryFailure(Task task,
                                                            TaskDispatchDeliveryFailure failure) {
        if (task == null || failure == null) {
            return ResultMutationOutcome.rejected();
        }

        String taskId = task.getTid();
        String messageId = failure.messageId();
        ActiveLeaseRecord activeLease = taskManager.getActiveLease(taskId, messageId).orElse(null);
        TaskWorkEnvelope runtimeWork = taskManager.getTaskWork(taskId, messageId).orElse(null);
        if (activeLease == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no active runtime lease",
                    messageId, taskId);
            return ResultMutationOutcome.rejected();
        }
        ActiveRuntimeView activeRuntimeView = buildActiveRuntimeView(
                taskId,
                messageId,
                activeLease,
                runtimeWork,
                "COMPENSATE_DISPATCH_SUBMIT_FAILURE",
                "runtime active lease defines dispatch compensation admissibility"
        );
        if (activeRuntimeView == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} was not found in task {}",
                    messageId, taskId);
            return ResultMutationOutcome.rejected();
        }
        RuntimeWorkSummary workSummary = activeRuntimeView.workSummary();

        RuntimeAttemptView activeAttempt = resolveOrRecoverDispatchAttemptView(
                workSummary,
                activeLease,
                failure.attemptId(),
                failure.attemptNo()
        );
        if (activeAttempt == null) {
            logger.warn("Cannot compensate dispatch submit failure because msg {} in task {} has no recoverable attempt view",
                    messageId, taskId);
            return ResultMutationOutcome.rejected();
        }

        String normalizedDetail = normalizeDispatchSubmitFailureDetail(failure.detail());
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
                : new TaskWorkTraceView(taskId, messageId, null, null, null, MessageStatus.INIT, null, 0, null);
    }

    private ActiveRuntimeView buildActiveRuntimeView(String taskId,
                                                                 String messageId,
                                                                 ActiveLeaseRecord activeLease,
                                                                 TaskWorkEnvelope runtimeWork,
                                                                 String trigger,
                                                                 String reason) {
        RuntimeWorkSummary activeView = materializeRuntimeWorkSummary(taskId, messageId, activeLease, runtimeWork);
        if (activeView == null) {
            return null;
        }
        RuntimeAttemptView activeAttempt = recoverActiveAttemptView(activeView, activeLease, null, null);
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
                    activeAttempt.batchId(),
                    originalStatus,
                    activeView.status(),
                    trigger,
                    "TaskManager",
                    reason
            );
        }
        return new ActiveRuntimeView(activeView, activeAttempt);
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

    private RuntimeAttemptView recoverActiveAttemptView(RuntimeWorkSummary workSummary,
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
        return RuntimeAttemptView.dispatched(
                workSummary.taskId(),
                workSummary.messageId(),
                activeLease,
                attemptId,
                attemptNo
        );
    }

    private RuntimeAttemptView resolveOrRecoverDispatchAttemptView(RuntimeWorkSummary workSummary,
                                                                            ActiveLeaseRecord activeLease,
                                                                            TaskDispatchBinding dispatchBinding) {
        if (workSummary == null || activeLease == null || dispatchBinding == null) {
            return null;
        }
        if (dispatchBinding.attemptId() == null || dispatchBinding.attemptId().isBlank()) {
            return null;
        }
        return recoverActiveAttemptView(workSummary, activeLease, dispatchBinding.attemptId(), dispatchBinding.attemptNo());
    }

    private RuntimeAttemptView resolveOrRecoverDispatchAttemptView(RuntimeWorkSummary workSummary,
                                                                   ActiveLeaseRecord activeLease,
                                                                   String attemptId,
                                                                   int attemptNo) {
        if (workSummary == null || activeLease == null || attemptId == null || attemptId.isBlank()) {
            return null;
        }
        return recoverActiveAttemptView(workSummary, activeLease, attemptId, attemptNo);
    }

    private ResultMutationOutcome handleSuccess(Task task,
                                              String taskId,
                                              RuntimeWorkSummary workSummary,
                                              RuntimeAttemptView activeAttempt,
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
                activeAttempt.batchId(),
                beforeFinalStatus,
                successSummary.status(),
                "HANDLE_TASK_RESULT",
                "TaskManager",
                "work item marked success"
        );
        CommitResult commit = visibleFinalCommitter.commitVisibleFinal(successSummary, activeAttempt, stagedDraft);
        if (!commit.visible()) {
            logger.warn("Result runtime visible commit failed for success result taskId={}, messageId={}, status={}, reason={}",
                    taskId, messageId, commit.status(), commit.reason());
            return ResultMutationOutcome.acceptedNoop();
        }
        // Event publishing is kept synchronous - it drives downstream state transitions.
        if (task != null) {
            publishWorkAttemptClosedOnce(task, successSummary, activeAttempt, commit.row(),
                    "HANDLE_TASK_RESULT", "work attempt succeeded");
            publishWorkLogicallyFinalOnce(task, successSummary, activeAttempt, commit.row(),
                    "HANDLE_TASK_RESULT", "work item reached stable success");
        }
        visibleFinalCommitter.cleanupStageIfConverged(taskId, messageId, commit.row());
        return ResultMutationOutcome.acceptedDirtyWithProgressBarrier(taskId, messageId, commit.row().seq());
    }

    private ResultMutationOutcome handleRetryableFailure(Task task,
                                                       String taskId,
                                                       RuntimeWorkSummary workSummary,
                                                       RuntimeAttemptView activeAttempt,
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
        visibleFinalCommitter.discardStage(stagedDraft);
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
                                                                            RuntimeAttemptView activeAttempt,
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
                activeAttempt.batchId(),
                activeAttempt.finalReason(),
                beforeRevokedStatus,
                activeAttempt.status(),
                trigger,
                "TaskManager",
                resetReason
        );
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
                activeAttempt.batchId(),
                workRetryDelayMillis,
                trigger,
                "TaskManager",
                resetReason);
        return retrySummary;
    }

    private RuntimeWorkSummary buildRetryResetCompatibilityBaseView(RuntimeWorkSummary workSummary,
                                                                    RuntimeAttemptView activeAttempt) {
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
                                                            RuntimeAttemptView activeAttempt,
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
                activeAttempt.batchId(),
                beforeFinalStatus,
                failureSummary.status(),
                "HANDLE_TASK_RESULT",
                "TaskManager",
                "work item marked failure"
        );

        CommitResult commit = visibleFinalCommitter.commitVisibleFinal(failureSummary, activeAttempt, stagedDraft);
        if (!commit.visible()) {
            logger.warn("Result runtime visible commit failed for failure result taskId={}, messageId={}, status={}, reason={}",
                    taskId, messageId, commit.status(), commit.reason());
            return ResultMutationOutcome.acceptedNoop();
        }
        // Event publishing kept synchronous; use the already-loaded task.
        if (task != null) {
            publishWorkAttemptClosedOnce(task, failureSummary, activeAttempt, commit.row(),
                    "HANDLE_TASK_RESULT", "retry budget exhausted closed the current attempt");
            publishWorkLogicallyFinalOnce(task, failureSummary, activeAttempt, commit.row(),
                    "HANDLE_TASK_RESULT", "work item reached stable failure");
        }
        visibleFinalCommitter.cleanupStageIfConverged(taskId, workSummary.messageId(), commit.row());
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
        return resultFinalityPolicy(task).expiredLeaseFinalizesAsFailure()
                ? summarizeBatchLeaseExpiryFinal(base, detail)
                : summarizeSessionLeaseExpiryFinal(base, detail);
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
     * {@link RuntimeResultApplyContext} snapshot so runtime view reconstruction
     * can continue to use lease-based logic without extra runtime reads.
     */
    private static ActiveLeaseRecord syntheticLeaseFromContext(RuntimeResultApplyContext ctx,
                                                               String taskId,
                                                               String messageId) {
        return new ActiveLeaseRecord(
                taskId,
                messageId,
                ctx.activeLeaseToken(),
                ctx.workerId(),
                ctx.workerGroupId(),
                ctx.batchId(),
                ctx.selectionToken(),
                ctx.scoreBandClaimScore(),
                ctx.payloadRef(),
                ctx.retryCount(),
                null,         // leaseExpireAt - not needed for runtime view reconstruction
                ctx.leasedAt()
        );
    }

    /**
     * Reconstructs a minimal {@link TaskWorkEnvelope} from an
     * {@link RuntimeResultApplyContext} snapshot. Only fields required by the
     * runtime view reconstruction (retryCount, maxRetryCount, payloadRef) are populated.
     */
    private static TaskWorkEnvelope syntheticWorkFromContext(RuntimeResultApplyContext ctx,
                                                             String taskId,
                                                             String messageId) {
        return new TaskWorkEnvelope(
                taskId,
                messageId,
                null,              // eventCode - not needed for runtime view reconstruction
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
        TaskRuntimeRetryPolicy retryPolicy = taskRuntimeRetryPolicyResolver.resolve(
                taskManager.resolveTaskSchedulingPolicy(task),
                1L);
        return retryPolicy.workRetryDelayMillis();
    }

    private boolean shouldRetryExpiredLease(Task task, MessageStatus fromStatus) {
        ResultFinalityPolicy policy = resultFinalityPolicy(task);
        return policy.retryExpiredLeaseFromAnyActiveState() || fromStatus == MessageStatus.ASSIGNED;
    }

    private ResultFinalityPolicy resultFinalityPolicy(Task task) {
        return taskManager.resolveTaskSchedulingPolicy(task).resultFinalityPolicy();
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
                .map(receipt -> RuntimeWorkSummary.fromRecentFinalReceipt(task, receipt, resultFinalityPolicy(task)))
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
        String stageId = TaskResultCallbackDraft.stageId(taskId, messageId, identityDigest);
        String workerId = activeLease != null ? activeLease.workerId() : null;
        String workerGroupId = activeLease != null ? activeLease.workerGroupId() : null;
        return TaskResultCallbackDraft.workerLevel(
                stageId,
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
                workerId,
                workerGroupId,
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

    private void publishWorkLogicallyFinalOnce(Task task,
                                               RuntimeWorkSummary workSummary,
                                               RuntimeAttemptView attempt,
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
        BarrierMarkResult markResult = taskResultRuntime.markLogicalFinalPublished(
                row.taskId(), row.messageId(), row.seq(), claim.claimToken());
        if (!markResult.completed()) {
            logger.warn("Logical-final barrier mark did not complete for taskId={}, messageId={}, seq={}, status={}, reason={}",
                    row.taskId(), row.messageId(), row.seq(), markResult.status(), markResult.reason());
        }
    }

    private void publishWorkAttemptClosedOnce(Task task,
                                              RuntimeWorkSummary workSummary,
                                              RuntimeAttemptView attempt,
                                              TaskResultRuntimeRow row,
                                              String trigger,
                                              String reason) {
        if (row == null || attempt == null) {
            return;
        }
        BarrierClaim claim = taskResultRuntime.claimAttemptClosedPublish(row.taskId(), row.messageId(), row.seq());
        if (!claim.claimedByCaller()) {
            return;
        }
        publishWorkAttemptClosed(task, workSummary, attempt, trigger, reason);
        BarrierMarkResult markResult = taskResultRuntime.markAttemptClosedPublished(
                row.taskId(), row.messageId(), row.seq(), claim.claimToken());
        if (!markResult.completed()) {
            logger.warn("Attempt-closed barrier mark did not complete for taskId={}, messageId={}, seq={}, status={}, reason={}",
                    row.taskId(), row.messageId(), row.seq(), markResult.status(), markResult.reason());
        }
    }

    private String normalizeDispatchSubmitFailureDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "dispatch submit failed before transport delivery";
        }
        return detail;
    }

    void shutdown() {
        repairPump.shutdown();
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
        if (candidate == null || candidate.kind() == null) {
            return false;
        }
        return switch (candidate.kind()) {
            case MISSING_VISIBLE_FINAL -> repairMissingVisibleFinal(candidate);
            case MISSING_ATTEMPT_CLOSED_PUBLISH -> repairMissingAttemptClosedPublish(candidate);
            case MISSING_LOGICAL_FINAL_PUBLISH -> repairMissingLogicalFinalPublish(candidate);
            case MISSING_PROGRESS_APPLY -> repairMissingProgressApply(candidate);
        };
    }

    private boolean repairMissingVisibleFinal(TaskResultRepairCandidate candidate) {
        TaskResultCallbackDraft draft = candidate.draft();
        if (draft == null) {
            return false;
        }
        Task task = taskManager.getTask(draft.taskId());
        RecentFinalWorkReceipt receipt = taskManager.getRecentFinalReceipt(draft.taskId(), draft.messageId()).orElse(null);
        if (receipt == null || taskResultRuntime.getVisibleByMessageId(draft.taskId(), draft.messageId()).isPresent()) {
            return false;
        }
        RuntimeWorkSummary summary = rebuildFinalSummaryForRepair(task, draft, receipt);
        CommitResult commit = visibleFinalCommitter.commitVisibleFinal(summary, null, draft);
        if (!commit.visible()) {
            return false;
        }
        if (task != null) {
            publishWorkAttemptClosedRepairOnce(task, summary, commit.row(),
                    "REPAIR_RESULT_RUNTIME", "result runtime repair resumed missing attempt-closed event");
            publishWorkLogicallyFinalOnce(task, summary, null, commit.row(),
                    "REPAIR_RESULT_RUNTIME", "result runtime repair resumed missing logical-final event");
            taskManager.applyTaskResultProgressOnce(commit.row().taskId(), commit.row().messageId(), commit.row().seq());
        }
        visibleFinalCommitter.cleanupStageIfConverged(draft.taskId(), draft.messageId(), commit.row());
        return true;
    }

    private boolean repairMissingAttemptClosedPublish(TaskResultRepairCandidate candidate) {
        TaskResultRuntimeRow row = candidate.row();
        if (row == null) {
            return false;
        }
        Task task = taskManager.getTask(row.taskId());
        if (task == null) {
            return false;
        }
        TaskResultRuntimeRow current = taskResultRuntime.getVisibleByMessageId(row.taskId(), row.messageId()).orElse(null);
        if (current == null || current.attemptClosedPublished()) {
            return false;
        }
        RuntimeWorkSummary summary = rebuildFinalSummaryForRepair(
                task,
                current,
                taskManager.getRecentFinalReceipt(row.taskId(), row.messageId()).orElse(null)
        );
        if (summary == null) {
            return false;
        }
        publishWorkAttemptClosedRepairOnce(task, summary, current,
                "REPAIR_RESULT_RUNTIME", "result runtime repair resumed missing attempt-closed event");
        TaskResultRuntimeRow updated = taskResultRuntime.getVisibleByMessageId(current.taskId(), current.messageId()).orElse(null);
        visibleFinalCommitter.cleanupStageIfConverged(current.taskId(), current.messageId(), updated);
        return updated != null && updated.attemptClosedPublished();
    }

    private boolean repairMissingLogicalFinalPublish(TaskResultRepairCandidate candidate) {
        TaskResultRuntimeRow row = candidate.row();
        if (row == null) {
            return false;
        }
        Task task = taskManager.getTask(row.taskId());
        if (task == null) {
            return false;
        }
        TaskResultRuntimeRow current = taskResultRuntime.getVisibleByMessageId(row.taskId(), row.messageId()).orElse(null);
        if (current == null || current.logicalFinalPublished()) {
            return false;
        }
        RuntimeWorkSummary summary = rebuildFinalSummaryForRepair(
                task,
                current,
                taskManager.getRecentFinalReceipt(row.taskId(), row.messageId()).orElse(null)
        );
        if (summary == null) {
            return false;
        }
        if (!current.attemptClosedPublished()) {
            publishWorkAttemptClosedRepairOnce(task, summary, current,
                    "REPAIR_RESULT_RUNTIME", "result runtime repair resumed missing attempt-closed event");
            current = taskResultRuntime.getVisibleByMessageId(row.taskId(), row.messageId()).orElse(null);
            if (current == null || !current.attemptClosedPublished()) {
                return false;
            }
        }
        publishWorkLogicallyFinalOnce(task, summary, null, current,
                "REPAIR_RESULT_RUNTIME", "result runtime repair resumed missing logical-final event");
        visibleFinalCommitter.cleanupStageIfConverged(current.taskId(), current.messageId(), current);
        return true;
    }

    private boolean repairMissingProgressApply(TaskResultRepairCandidate candidate) {
        TaskResultRuntimeRow row = candidate.row();
        if (row == null) {
            return false;
        }
        TaskResultRuntimeRow current = taskResultRuntime.getVisibleByMessageId(row.taskId(), row.messageId()).orElse(null);
        if (current == null || current.progressApplied()) {
            return false;
        }
        if (!current.attemptClosedPublished() || !current.logicalFinalPublished()) {
            return false;
        }
        taskManager.applyTaskResultProgressOnce(current.taskId(), current.messageId(), current.seq());
        TaskResultRuntimeRow updated = taskResultRuntime.getVisibleByMessageId(current.taskId(), current.messageId()).orElse(null);
        visibleFinalCommitter.cleanupStageIfConverged(current.taskId(), current.messageId(), updated);
        return updated != null && updated.progressApplied();
    }

    private RuntimeWorkSummary rebuildFinalSummaryForRepair(Task task,
                                                            TaskResultCallbackDraft draft,
                                                            RecentFinalWorkReceipt receipt) {
        if (draft == null || receipt == null) {
            return null;
        }
        RuntimeWorkSummary receiptSummary = RuntimeWorkSummary.fromRecentFinalReceipt(task, receipt,
                resultFinalityPolicy(task));
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

    private RuntimeWorkSummary rebuildFinalSummaryForRepair(Task task,
                                                            TaskResultRuntimeRow row,
                                                            RecentFinalWorkReceipt receipt) {
        if (row == null || receipt == null) {
            return null;
        }
        RuntimeWorkSummary receiptSummary = RuntimeWorkSummary.fromRecentFinalReceipt(task, receipt,
                resultFinalityPolicy(task));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createTime = row.createTime() == null
                ? now
                : LocalDateTime.ofInstant(row.createTime(), ZoneId.systemDefault());
        LocalDateTime assignedTime = row.assignedTime() == null
                ? null
                : LocalDateTime.ofInstant(row.assignedTime(), ZoneId.systemDefault());
        LocalDateTime startTime = row.startTime() == null
                ? assignedTime
                : LocalDateTime.ofInstant(row.startTime(), ZoneId.systemDefault());
        LocalDateTime completedAt = receipt.completedAt() == null
                ? now
                : LocalDateTime.ofInstant(receipt.completedAt(), ZoneId.systemDefault());
        return new RuntimeWorkSummary(
                row.messageId(),
                row.taskId(),
                row.attemptId(),
                row.workerId(),
                row.batchId(),
                receiptSummary.status(),
                assignedTime,
                createTime,
                completedAt,
                startTime,
                completedAt,
                Math.max(0, receipt.retryCount()),
                Math.max(0, row.maxRetryCount()),
                row.errorMessage(),
                receipt.errorCode() != null ? receipt.errorCode() : row.errorCode(),
                receiptSummary.finalReason(),
                row.payloadRef(),
                row.output()
        );
    }

    private void publishWorkAttemptClosedRepairOnce(Task task,
                                                    RuntimeWorkSummary workSummary,
                                                    TaskResultRuntimeRow row,
                                                    String trigger,
                                                    String reason) {
        if (task == null || workSummary == null || row == null || row.attemptId() == null || row.attemptId().isBlank()) {
            return;
        }
        RuntimeAttemptView repairAttempt = new RuntimeAttemptView(
                row.attemptId(),
                row.taskId(),
                row.messageId(),
                0,
                row.workerId(),
                row.workerGroupId(),
                row.batchId(),
                null,
                null
        );
        repairAttempt.status = attemptStatusForRepair(workSummary.status());
        repairAttempt.finalReason = attemptFinalReasonForRepair(workSummary.finalReason());
        repairAttempt.errorMessage = workSummary.errorMessage();
        repairAttempt.errorCode = workSummary.errorCode();
        repairAttempt.output = workSummary.output();
        publishWorkAttemptClosedOnce(task, workSummary, repairAttempt, row, trigger, reason);
    }

    private AttemptStatus attemptStatusForRepair(MessageStatus status) {
        if (status == null) {
            return AttemptStatus.FAILED;
        }
        return switch (status) {
            case SUCCESS -> AttemptStatus.SUCCEEDED;
            case FAILED -> AttemptStatus.FAILED;
            case EXPIRED -> AttemptStatus.EXPIRED;
            default -> AttemptStatus.FAILED;
        };
    }

    private AttemptFinalReason attemptFinalReasonForRepair(MessageFinalReason finalReason) {
        if (finalReason == null) {
            return AttemptFinalReason.BUSINESS_FAILURE;
        }
        return switch (finalReason) {
            case BUSINESS_SUCCESS -> AttemptFinalReason.SUCCESS;
            case TIMEOUT -> AttemptFinalReason.TIMEOUT;
            case WORKER_LOST -> AttemptFinalReason.WORKER_LOST;
            case MANUAL_CANCELLED -> AttemptFinalReason.MANUAL_CANCELLED;
            case LEASE_EXPIRED -> AttemptFinalReason.LEASE_EXPIRED;
            case BUSINESS_FAILED, RETRY_EXHAUSTED -> AttemptFinalReason.BUSINESS_FAILURE;
        };
    }

    private void requestRetryDispatch(Task task, long workRetryDelayMillis) {
        taskManager.requestTaskRetryDispatch(task, workRetryDelayMillis);
    }

    private void publishWorkAttemptClosed(Task task,
                                          RuntimeWorkSummary workSummary,
                                          RuntimeAttemptView attempt,
                                          String trigger,
                                          String reason) {
        traceEventLogger.taskWorkAttemptClosed(
                task,
                workSummary.toTraceView(),
                attempt.attemptId(),
                attempt.attemptNo(),
                attempt.workerId(),
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
                        attempt.workerGroupId(),
                        attempt.batchId(),
                        attempt.selectionToken(),
                        attempt.scoreBandClaimScore(),
                        attempt.status(),
                        attempt.finalReason()
                )
        );
    }

    private void publishWorkLogicallyFinal(Task task,
                                           RuntimeWorkSummary workSummary,
                                           RuntimeAttemptView attempt,
                                           String trigger,
                                           String reason) {
        traceEventLogger.taskWorkLogicallyFinal(
                task,
                workSummary.toTraceView(),
                attempt != null ? attempt.attemptId() : null,
                attempt != null ? attempt.workerId() : null,
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
                                                             RuntimeAttemptView activeAttempt,
                                                             ResultApplyStatus applyStatus,
                                                             String detail,
                                                             String errorCode,
                                                             Map<String, Object> output,
                                                             TaskResultCallbackDraft stagedDraft) {
        return resultFinalityPolicy(task).expiredLeaseFinalizesAsFailure()
                ? handleBatchRuntimeAcceptedFailure(
                task, taskId, workSummary, activeAttempt, applyStatus, detail, errorCode, output, stagedDraft)
                : handleSessionRuntimeAcceptedFailure(
                task, taskId, workSummary, activeAttempt, applyStatus, detail, errorCode, output, stagedDraft);
    }

    private ResultMutationOutcome handleBatchRuntimeAcceptedFailure(Task task,
                                                                  String taskId,
                                                                  RuntimeWorkSummary workSummary,
                                                                  RuntimeAttemptView activeAttempt,
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
                                                                    RuntimeAttemptView activeAttempt,
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

    private record ActiveRuntimeView(RuntimeWorkSummary workSummary, RuntimeAttemptView activeAttempt) {
    }

    static final class RuntimeAttemptView {
        private final String attemptId;
        private final String taskId;
        private final String messageId;
        private final int attemptNo;
        private final String workerId;
        private final String workerGroupId;
        private final String batchId;
        private final String selectionToken;
        private final Long scoreBandClaimScore;
        private AttemptStatus status;
        private AttemptFinalReason finalReason;
        private String errorMessage;
        private String errorCode;
        private Map<String, Object> output;

        private RuntimeAttemptView(String attemptId,
                                      String taskId,
                                      String messageId,
                                      int attemptNo,
                                      String workerId,
                                      String workerGroupId,
                                      String batchId,
                                      String selectionToken,
                                      Long scoreBandClaimScore) {
            this.attemptId = attemptId;
            this.taskId = taskId;
            this.messageId = messageId;
            this.attemptNo = attemptNo;
            this.workerId = workerId;
            this.workerGroupId = workerGroupId;
            this.batchId = batchId;
            this.selectionToken = selectionToken;
            this.scoreBandClaimScore = scoreBandClaimScore;
            this.status = AttemptStatus.DISPATCHED;
        }

        static RuntimeAttemptView dispatched(String taskId,
                                                String messageId,
                                                ActiveLeaseRecord activeLease,
                                                String attemptId,
                                                int attemptNo) {
            if (taskId == null || messageId == null || activeLease == null) {
                return null;
            }
            RuntimeAttemptView attempt = new RuntimeAttemptView(
                    attemptId,
                    taskId,
                    messageId,
                    attemptNo,
                    activeLease.workerId(),
                    activeLease.workerGroupId(),
                    activeLease.batchId(),
                    activeLease.selectionToken(),
                    activeLease.scoreBandClaimScore()
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

        String workerGroupId() {
            return workerGroupId;
        }

        String batchId() {
            return batchId;
        }

        String selectionToken() {
            return selectionToken;
        }

        Long scoreBandClaimScore() {
            return scoreBandClaimScore;
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

        static RuntimeWorkSummary fromRecentFinalReceipt(Task task,
                                                         RecentFinalWorkReceipt receipt,
                                                         ResultFinalityPolicy resultFinalityPolicy) {
            if (receipt == null) {
                return null;
            }
            LocalDateTime completedAt = receipt.completedAt() == null
                    ? LocalDateTime.now()
                    : LocalDateTime.ofInstant(receipt.completedAt(), java.time.ZoneId.systemDefault());
            ResultFinalityPolicy resolvedPolicy = resultFinalityPolicy == null
                    ? ResultFinalityPolicy.batch()
                    : resultFinalityPolicy;
            boolean batchLeaseExpiryFinalizedAsFailure = resolvedPolicy.expiredLeaseFinalizesAsFailure()
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
            boolean attemptViewDiffers = !java.util.Objects.equals(latestAttemptWorkerId, activeLease.workerId())
                    || !java.util.Objects.equals(latestAttemptBatchId, activeLease.batchId());
            boolean needsRetryView = retryCount != runtimeRetryCount;
            boolean needsAssignedTime = assignedTime == null;
            if (!attemptViewDiffers && !needsAssignedStatus && !needsRetryView && !needsAssignedTime) {
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

        private RuntimeWorkSummary withAssignedAttempt(RuntimeAttemptView attempt) {
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

        private RuntimeWorkSummary attachAttempt(RuntimeAttemptView attempt) {
            if (attempt == null) {
                return this;
            }
            boolean alreadyAttached = java.util.Objects.equals(latestAttemptId, attempt.attemptId())
                    && java.util.Objects.equals(latestAttemptWorkerId, attempt.workerId())
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
                    latestAttemptBatchId,
                    status,
                    finalReason,
                    retryCount,
                    errorCode
            );
        }
    }
}
