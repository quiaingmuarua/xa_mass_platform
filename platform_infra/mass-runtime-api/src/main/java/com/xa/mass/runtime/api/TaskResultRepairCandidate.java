package com.xa.mass.runtime.api;

public record TaskResultRepairCandidate(TaskResultCallbackDraft draft) {

    public TaskResultRepairCandidate {
        if (draft == null) {
            throw new IllegalArgumentException("draft must not be null");
        }
    }

    public String taskId() {
        return draft.taskId();
    }

    public String messageId() {
        return draft.messageId();
    }

    public String stageId() {
        return draft.stageId();
    }
}
