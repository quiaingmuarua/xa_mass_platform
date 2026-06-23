package com.xa.mass.sdk.worker;

/**
 * Embedded SDK worker action reply.
 */
public record WorkerActionReply(String replyRef,
                                boolean success,
                                String code,
                                String body) {

    public WorkerActionReply {
        replyRef = requireText(replyRef, "replyRef");
        code = normalize(code);
        body = normalize(body);
    }

    public static WorkerActionReply of(String replyRef,
                                       boolean success,
                                       String code,
                                       String body) {
        return new WorkerActionReply(replyRef, success, code, body);
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
