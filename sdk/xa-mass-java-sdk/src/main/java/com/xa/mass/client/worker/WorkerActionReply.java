package com.xa.mass.client.worker;

public record WorkerActionReply(
        String replyRef,
        boolean success,
        String code,
        String body
) {
    public WorkerActionReply {
        replyRef = requireText(replyRef, "replyRef");
        code = normalize(code);
        body = normalize(body);
    }

    public static WorkerActionReply success(String replyRef, String body) {
        return new WorkerActionReply(replyRef, true, null, body);
    }

    public static WorkerActionReply failure(String replyRef, String code, String body) {
        return new WorkerActionReply(replyRef, false, code, body);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
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
