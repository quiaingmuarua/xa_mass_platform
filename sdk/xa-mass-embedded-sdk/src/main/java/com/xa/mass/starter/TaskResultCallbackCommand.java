package com.xa.mass.starter;

import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;

/**
 * Starter-owned task-shaped callback command decoded from opaque transport
 * result ingress.
 */
public record TaskResultCallbackCommand(String taskId,
                                        String messageId,
                                        boolean success,
                                        String detail,
                                        String errorCode,
                                        Map<String, Object> output,
                                        String attemptId,
                                        String leaseToken,
                                        String traceId) {

    public TaskResultCallbackCommand {
        taskId = requireText(taskId, "taskId");
        messageId = requireText(messageId, "messageId");
        detail = normalize(detail);
        errorCode = normalize(errorCode);
        output = TransportJsonValueNormalizer.normalizeObject(output, "output");
        attemptId = normalize(attemptId);
        leaseToken = normalize(leaseToken);
        traceId = normalize(traceId);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
