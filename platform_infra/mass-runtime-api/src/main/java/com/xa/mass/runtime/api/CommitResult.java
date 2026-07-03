package com.xa.mass.runtime.api;

public record CommitResult(CommitResultStatus status, TaskResultRuntimeRow row, String reason) {

    public static CommitResult committed(TaskResultRuntimeRow row) {
        return new CommitResult(CommitResultStatus.COMMITTED, row, null);
    }

    public static CommitResult duplicate(TaskResultRuntimeRow row) {
        return new CommitResult(CommitResultStatus.DUPLICATE, row, null);
    }

    public static CommitResult rejected(String reason) {
        return new CommitResult(CommitResultStatus.REJECTED, null, reason);
    }

    public static CommitResult unavailable(String reason) {
        return new CommitResult(CommitResultStatus.UNAVAILABLE, null, reason);
    }

    public boolean visible() {
        return row != null && (status == CommitResultStatus.COMMITTED || status == CommitResultStatus.DUPLICATE);
    }
}
