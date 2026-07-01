package com.xa.mass.task.runtime;

import java.util.List;

public record AppendBatchCommand(
        String taskId,
        List<AppendItemInput> items,
        AppendAdmissionPolicy admissionPolicy,
        RuntimeEpoch runtimeEpoch
) {

    public AppendBatchCommand {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        items = TaskRuntimeContractChecks.copyNonEmpty(items, "items");
        admissionPolicy = admissionPolicy == null
                ? new AppendAdmissionPolicy(items.size(), AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG)
                : admissionPolicy;
        if (items.size() > admissionPolicy.maxAppendBatchSize()) {
            throw new IllegalArgumentException("items exceed maxAppendBatchSize");
        }
        runtimeEpoch = runtimeEpoch == null ? RuntimeEpoch.of(taskId, 0L) : runtimeEpoch;
    }
}
