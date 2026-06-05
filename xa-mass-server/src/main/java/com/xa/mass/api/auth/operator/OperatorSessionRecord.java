package com.xa.mass.api.auth.operator;

import java.time.Instant;

public record OperatorSessionRecord(
        String sessionId,
        String userId,
        String csrfToken,
        Instant createdAt,
        Instant expiresAt,
        boolean revoked) {

    public OperatorSessionRecord {
        sessionId = required(sessionId, "sessionId");
        userId = required(userId, "userId");
        csrfToken = required(csrfToken, "csrfToken");
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        expiresAt = expiresAt == null ? createdAt.plusSeconds(3600) : expiresAt;
    }

    public boolean active(Instant now) {
        return !revoked && expiresAt.isAfter(now == null ? Instant.now() : now);
    }

    public OperatorSessionRecord markRevoked() {
        return new OperatorSessionRecord(sessionId, userId, csrfToken, createdAt, expiresAt, true);
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
