package com.xa.mass.task.runtime;

import java.util.List;

public record FinalResultWindow(
        String taskId,
        List<FinalResultRow> rows,
        long nextAfterSeq,
        boolean hasMore,
        long totalVisible
) {

    public FinalResultWindow {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        rows = TaskRuntimeContractChecks.copyList(rows);
        nextAfterSeq = Math.max(0L, nextAfterSeq);
        totalVisible = Math.max(0L, totalVisible);
    }
}
