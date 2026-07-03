package com.xa.mass.engine.model;

import java.util.List;

/**
 * Engine-owned append receipt for newly ingested task work items.
 */
public record TaskAppendReceipt(
        String taskId,
        int added,
        List<String> messageIds
) {

    public TaskAppendReceipt {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        added = Math.max(0, added);
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
