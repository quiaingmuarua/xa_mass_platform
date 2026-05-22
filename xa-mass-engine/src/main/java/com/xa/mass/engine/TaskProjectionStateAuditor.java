package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit full-scan auditor for compatibility projection residue.
 *
 * <p>This owner is intentionally separate from runtime task validation so
 * default engine diagnostics do not normalize scan-heavy projection reads as
 * part of runtime truth checks.</p>
 */
@CompatibilityProjectionOnly
final class TaskProjectionStateAuditor {

    private final TaskStateValidator runtimeValidator;
    private final TaskCompatibilityProjectionStore compatibilityProjectionStore;

    TaskProjectionStateAuditor(TaskStateValidator runtimeValidator,
                               TaskCompatibilityProjectionStore compatibilityProjectionStore) {
        this.runtimeValidator = runtimeValidator;
        this.compatibilityProjectionStore = compatibilityProjectionStore;
    }

    TaskStateValidationResult auditTaskProjectionState(String taskId) {
        TaskStateValidator.RuntimeValidationSnapshot runtimeSnapshot =
                runtimeValidator.computeRuntimeValidation(taskId);
        List<TaskStateValidationResult.ViolationCode> violations =
                new ArrayList<>(runtimeSnapshot.violations());
        boolean needsResolution = runtimeSnapshot.needsResolution();
        if (runtimeSnapshot.task() != null) {
            needsResolution = needsResolution || auditWorkProjectionResidue(taskId, violations);
        }
        TaskStateValidationResult result = new TaskStateValidationResult(
                violations.isEmpty(),
                needsResolution,
                runtimeSnapshot.task() != null ? runtimeSnapshot.task().getStatus() : null,
                runtimeSnapshot.task() != null ? runtimeSnapshot.task().getTerminalReason() : null,
                runtimeSnapshot.stats().totalCount(),
                runtimeSnapshot.stats().successCount(),
                runtimeSnapshot.stats().failedCount(),
                runtimeSnapshot.stats().processingCount(),
                TaskStateValidationResult.Scope.PROJECTION_AUDIT,
                List.copyOf(violations)
        );
        if (!result.isValid() || result.isNeedsResolution()) {
            runtimeValidator.emitTaskStateValidationSummary(
                    taskId,
                    result,
                    result.isNeedsResolution()
                            ? "task requires explicit terminal reconciliation"
                            : runtimeSnapshot.task() == null
                            ? "task not found"
                            : "task projection audit found residue violations",
                    "AUDIT_TASK_PROJECTION_STATE"
            );
        }
        return result;
    }

    private boolean auditWorkProjectionResidue(String taskId,
                                               List<TaskStateValidationResult.ViolationCode> violations) {
        boolean attemptNeedsResolution = false;
        for (TaskCompatibilityProjectionStore.WorkProjectionRecord messageProjection : getWorkProjectionsForProjectionAudit(taskId)) {
            if (messageProjection == null) {
                continue;
            }
            if (messageProjection.isFinal() && messageProjection.finalReason() == null) {
                violations.add(TaskStateValidationResult.ViolationCode.WORK_FINAL_REASON_MISSING);
            }
            if (messageProjection.isFinal()
                    && !TaskWorkProjectionState.isFinalReasonCompatible(
                    messageProjection.status(),
                    messageProjection.finalReason())) {
                violations.add(TaskStateValidationResult.ViolationCode.WORK_FINAL_REASON_STATUS_MISMATCH);
            }
            TaskCompatibilityProjectionStore.WorkAttemptStatsView attemptStats =
                    compatibilityProjectionStore.getWorkAttemptStats(taskId, messageProjection.messageId());
            long activeAttemptCount = attemptStats.activeAttempts();
            boolean hasActiveAttempt = activeAttemptCount > 0;
            if (activeAttemptCount > 1) {
                violations.add(TaskStateValidationResult.ViolationCode.MULTIPLE_ACTIVE_ATTEMPTS_FOR_MESSAGE);
            }
            if (hasActiveAttempt && messageProjection.isFinal()) {
                violations.add(TaskStateValidationResult.ViolationCode.ACTIVE_ATTEMPT_WITH_FINAL_MESSAGE);
            }
            boolean allAttemptsFinal = attemptStats.totalAttempts() > 0 && activeAttemptCount == 0;
            if (allAttemptsFinal
                    && !messageProjection.isFinal()
                    && messageProjection.status() != TaskWorkProjectionState.MessageStatus.INIT) {
                attemptNeedsResolution = true;
                violations.add(TaskStateValidationResult.ViolationCode.ALL_ATTEMPTS_FINAL_BUT_MESSAGE_NOT_FINAL);
            }
        }
        return attemptNeedsResolution;
    }

    private List<TaskCompatibilityProjectionStore.WorkProjectionRecord> getWorkProjectionsForProjectionAudit(String taskId) {
        long total = compatibilityProjectionStore.countWorkProjections(taskId);
        if (total <= 0) {
            return List.of();
        }
        return compatibilityProjectionStore.getWorkProjections(taskId, Math.toIntExact(total));
    }
}
