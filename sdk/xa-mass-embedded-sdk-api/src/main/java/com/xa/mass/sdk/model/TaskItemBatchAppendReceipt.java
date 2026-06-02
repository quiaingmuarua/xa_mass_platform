package com.xa.mass.sdk.model;

import java.util.List;

/**
 * SDK-owned append receipt for task ingest.
 */
public record TaskItemBatchAppendReceipt(
        String taskId,
        int added,
        List<String> messageIds
) {

    public TaskItemBatchAppendReceipt {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
