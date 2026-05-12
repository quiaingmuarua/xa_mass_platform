package com.xa.mass.sdk.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK-owned notification for a task work item reaching stable finality.
 */
public record TaskWorkFinalNotification(
        String taskId,
        Map<String, Object> sharedConfig,
        TaskWorkFinalSnapshot finalSnapshot
) {

    public TaskWorkFinalNotification {
        sharedConfig = sharedConfig == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(sharedConfig));
    }
}
