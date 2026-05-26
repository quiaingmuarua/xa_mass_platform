package com.xa.mass.api.auth.iam;

import java.time.Instant;
import java.util.Map;

public record UserRecord(
        String userId,
        String displayName,
        String email,
        UserStatus status,
        Map<String, String> attributes,
        Instant createdAt,
        Instant updatedAt
) {
    public UserRecord {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
