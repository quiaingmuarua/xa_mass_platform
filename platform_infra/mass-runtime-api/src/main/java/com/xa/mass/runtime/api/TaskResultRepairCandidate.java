package com.xa.mass.runtime.api;

import java.time.Instant;

public record TaskResultRepairCandidate(TaskResultRepairKind kind,
                                        String taskId,
                                        String messageId,
                                        Long seq,
                                        TaskResultCallbackDraft draft,
                                        TaskResultRuntimeRow row,
                                        Instant observedAt) {

    public TaskResultRepairCandidate {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }

    public static TaskResultRepairCandidate missingVisibleFinal(TaskResultCallbackDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("draft must not be null");
        }
        return new TaskResultRepairCandidate(
                TaskResultRepairKind.MISSING_VISIBLE_FINAL,
                draft.taskId(),
                draft.messageId(),
                null,
                draft,
                null,
                draft.receivedAt()
        );
    }

    public static TaskResultRepairCandidate missingLogicalFinalPublish(TaskResultRuntimeRow row) {
        if (row == null) {
            throw new IllegalArgumentException("row must not be null");
        }
        return new TaskResultRepairCandidate(
                TaskResultRepairKind.MISSING_LOGICAL_FINAL_PUBLISH,
                row.taskId(),
                row.messageId(),
                row.seq(),
                null,
                row,
                row.updateTime()
        );
    }

    public static TaskResultRepairCandidate missingProgressApply(TaskResultRuntimeRow row) {
        if (row == null) {
            throw new IllegalArgumentException("row must not be null");
        }
        return new TaskResultRepairCandidate(
                TaskResultRepairKind.MISSING_PROGRESS_APPLY,
                row.taskId(),
                row.messageId(),
                row.seq(),
                null,
                row,
                row.updateTime()
        );
    }

    public String stageId() {
        return draft == null ? null : draft.stageId();
    }
}
