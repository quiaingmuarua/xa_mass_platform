package com.xa.mass.transport.channel;

/**
 * Opaque worker-result ingress message carried by transport.
 */
public record ResultIngressMessage(String resultMessageId,
                                   String resultCorrelationRef,
                                   String payload,
                                   long deadlineEpochMillis,
                                   long createdAtEpochMillis) {

    public ResultIngressMessage {
        resultMessageId = requireText(resultMessageId, "resultMessageId");
        resultCorrelationRef = requireText(resultCorrelationRef, "resultCorrelationRef");
        payload = requirePayload(payload, "payload");
        deadlineEpochMillis = Math.max(0L, deadlineEpochMillis);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String requirePayload(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }
}
