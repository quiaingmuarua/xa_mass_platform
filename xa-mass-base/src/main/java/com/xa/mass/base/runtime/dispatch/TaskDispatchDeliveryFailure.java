package com.xa.mass.base.runtime.dispatch;

import java.util.Objects;

/**
 * Engine-owned delivery failure fact for an already assigned task item.
 *
 * <p>Transport may report this fact through an assembly-owned translator, but
 * transport does not use it to decide retry, release, or lifecycle state.</p>
 */
public record TaskDispatchDeliveryFailure(String taskId,
                                          String messageId,
                                          String attemptId,
                                          int attemptNo,
                                          String selectedWorkerId,
                                          String detail) {

    public TaskDispatchDeliveryFailure {
        taskId = requireText(taskId, "taskId");
        messageId = requireText(messageId, "messageId");
        attemptId = requireText(attemptId, "attemptId");
        attemptNo = Math.max(1, attemptNo);
        selectedWorkerId = optionalText(selectedWorkerId);
        detail = optionalText(detail);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
