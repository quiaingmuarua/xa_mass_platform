package com.xa.mass.sdk.worker;

import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;

/**
 * Embedded SDK worker result submit command.
 */
public record WorkerResultSubmitRequest(String taskId,
                                        String messageId,
                                        boolean success,
                                        String detail,
                                        String errorCode,
                                        Map<String, Object> output,
                                        String attemptId,
                                        String leaseToken,
                                        String traceId) {

    public WorkerResultSubmitRequest {
        taskId = requireText(taskId, "taskId");
        messageId = requireText(messageId, "messageId");
        detail = normalize(detail);
        errorCode = normalize(errorCode);
        output = TransportJsonValueNormalizer.normalizeObject(output, "output");
        attemptId = normalize(attemptId);
        leaseToken = normalize(leaseToken);
        traceId = normalize(traceId);
    }

    public static WorkerResultSubmitRequest of(String taskId,
                                               String messageId,
                                               boolean success,
                                               String detail,
                                               String errorCode,
                                               Map<String, Object> output) {
        return new WorkerResultSubmitRequest(taskId, messageId, success, detail, errorCode, output, null, null, null);
    }

    public WorkerResultSubmitRequest withAttemptId(String attemptId) {
        return new WorkerResultSubmitRequest(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                output,
                attemptId,
                leaseToken,
                traceId
        );
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
