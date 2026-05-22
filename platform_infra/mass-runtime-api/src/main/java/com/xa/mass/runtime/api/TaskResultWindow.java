package com.xa.mass.runtime.api;

import java.util.List;

public record TaskResultWindow(
        String taskId,
        List<TaskResultRuntimeRow> items,
        long nextAfterSeq,
        boolean hasMore,
        long totalVisible
) {

    public TaskResultWindow {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        items = items == null ? List.of() : List.copyOf(items);
        nextAfterSeq = Math.max(0L, nextAfterSeq);
        totalVisible = Math.max(0L, totalVisible);
    }
}
