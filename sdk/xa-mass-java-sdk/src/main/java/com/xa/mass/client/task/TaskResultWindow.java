package com.xa.mass.client.task;

import java.util.List;

public record TaskResultWindow(
        String mode,
        String taskId,
        boolean taskTerminal,
        boolean archiveReady,
        List<TaskResultItem> items,
        long nextAfterSeq,
        boolean hasMore,
        String archiveUrl
) {
}
