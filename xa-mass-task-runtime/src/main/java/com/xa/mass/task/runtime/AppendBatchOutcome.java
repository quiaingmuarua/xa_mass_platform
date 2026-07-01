package com.xa.mass.task.runtime;

import java.util.List;

public record AppendBatchOutcome(AppendBatchStatus status, String taskId, List<String> acceptedMessageIds, String reason) {

    public AppendBatchOutcome {
        status = status == null ? AppendBatchStatus.REJECTED_BEFORE_RUNTIME : status;
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        acceptedMessageIds = TaskRuntimeContractChecks.copyList(acceptedMessageIds);
        reason = reason == null ? "" : reason;
        if (status == AppendBatchStatus.ALL_ACCEPTED && acceptedMessageIds.isEmpty()) {
            throw new IllegalArgumentException("acceptedMessageIds must be non-empty for ALL_ACCEPTED");
        }
    }

    public static AppendBatchOutcome allAccepted(String taskId, List<String> acceptedMessageIds) {
        return new AppendBatchOutcome(AppendBatchStatus.ALL_ACCEPTED, taskId, acceptedMessageIds, "");
    }

    public static AppendBatchOutcome rejectedBeforeRuntime(String taskId, String reason) {
        return new AppendBatchOutcome(AppendBatchStatus.REJECTED_BEFORE_RUNTIME, taskId, List.of(), reason);
    }
}
