package com.xa.mass.engine;

import com.xa.mass.runtime.api.CommitResult;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Owns visible-final result commits and staged callback cleanup.
 */
final class TaskResultVisibleFinalCommitter {

    private final TaskResultRuntime taskResultRuntime;

    TaskResultVisibleFinalCommitter(TaskResultRuntime taskResultRuntime) {
        this.taskResultRuntime = taskResultRuntime;
    }

    CommitResult commitVisibleFinal(TaskResultService.RuntimeWorkSummary summary,
                                    TaskResultService.RuntimeAttemptView attempt,
                                    TaskResultCallbackDraft stagedDraft) {
        if (summary == null) {
            return CommitResult.rejected("summary must not be null");
        }
        String workerId = attempt != null ? attempt.workerId() : summary.latestAttemptWorkerId();
        return taskResultRuntime.commitVisibleFinal(TaskResultFinalDraft.workerLevel(
                summary.taskId(),
                summary.messageId(),
                stagedDraft != null ? stagedDraft.eventCode() : null,
                summary.status() != null ? summary.status().name() : null,
                summary.finalReason() != null ? summary.finalReason().name() : null,
                summary.retryCount(),
                summary.maxRetryCount(),
                workerId,
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

    void cleanupStageIfConverged(String taskId, String messageId, TaskResultRuntimeRow row) {
        if (row == null || taskId == null || taskId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        TaskResultRuntimeRow current = taskResultRuntime.getVisibleByMessageId(row.taskId(), row.messageId()).orElse(row);
        if (current.attemptClosedPublished() && current.logicalFinalPublished() && current.progressApplied()) {
            taskResultRuntime.discardStagedCallbacksForMessage(taskId, messageId);
        }
    }

    void discardStage(TaskResultCallbackDraft stagedDraft) {
        if (stagedDraft != null) {
            taskResultRuntime.discardStagedCallback(stagedDraft.stageId());
        }
    }

    void discardStageIfVisibleFinalExists(String taskId, String messageId, TaskResultCallbackDraft stagedDraft) {
        if (stagedDraft == null) {
            return;
        }
        if (taskResultRuntime.getVisibleByMessageId(taskId, messageId).isPresent()) {
            discardStage(stagedDraft);
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
