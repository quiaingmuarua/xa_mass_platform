package com.xa.mass.sdk.model;

import java.util.Map;

/**
 * SDK-level notification for a concrete attempt closing.
 */
public record TaskWorkAttemptClosedNotification(String taskId,
                                                Map<String, Object> sharedConfig,
                                                TaskWorkAttemptClosedSnapshot attemptSnapshot) {

    public TaskWorkAttemptClosedNotification {
        sharedConfig = sharedConfig == null ? Map.of() : Map.copyOf(sharedConfig);
    }
}
