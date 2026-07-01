package com.xa.mass.task.runtime;

import java.util.List;

public record ClaimReadyOutcome(String taskId, List<ClaimedWorkItem> claimedItems, String rejectionReason) {

    public ClaimReadyOutcome {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        claimedItems = TaskRuntimeContractChecks.copyList(claimedItems);
        rejectionReason = rejectionReason == null ? "" : rejectionReason;
    }

    public boolean accepted() {
        return !claimedItems.isEmpty();
    }
}
