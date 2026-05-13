package com.xa.mass.api.model.task;

import java.util.List;

public record ApiTaskResultWindow(
        String mode,
        String taskId,
        boolean taskTerminal,
        boolean archiveReady,
        List<ApiTaskResultItem> items,
        long nextAfterSeq,
        boolean hasMore,
        String archiveUrl
) {
}
