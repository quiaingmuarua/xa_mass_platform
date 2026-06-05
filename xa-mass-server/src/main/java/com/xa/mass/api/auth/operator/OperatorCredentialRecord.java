package com.xa.mass.api.auth.operator;

import java.time.Instant;

public record OperatorCredentialRecord(
        String userId,
        String passwordHash,
        String hashAlgorithm,
        OperatorCredentialStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public OperatorCredentialRecord {
        userId = required(userId, "userId");
        passwordHash = required(passwordHash, "passwordHash");
        hashAlgorithm = normalizeOptional(hashAlgorithm);
        status = status == null ? OperatorCredentialStatus.ACTIVE : status;
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean active() {
        return status == OperatorCredentialStatus.ACTIVE;
    }

    private static String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
