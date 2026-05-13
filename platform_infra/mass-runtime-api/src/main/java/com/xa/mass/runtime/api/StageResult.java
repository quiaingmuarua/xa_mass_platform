package com.xa.mass.runtime.api;

public record StageResult(StageResultStatus status, TaskResultCallbackDraft draft, String reason) {

    public static StageResult staged(TaskResultCallbackDraft draft) {
        return new StageResult(StageResultStatus.STAGED, draft, null);
    }

    public static StageResult duplicate(TaskResultCallbackDraft draft) {
        return new StageResult(StageResultStatus.DUPLICATE, draft, null);
    }

    public static StageResult rejected(String reason) {
        return new StageResult(StageResultStatus.REJECTED, null, reason);
    }

    public static StageResult unavailable(String reason) {
        return new StageResult(StageResultStatus.UNAVAILABLE, null, reason);
    }

    public boolean accepted() {
        return status == StageResultStatus.STAGED || status == StageResultStatus.DUPLICATE;
    }
}
