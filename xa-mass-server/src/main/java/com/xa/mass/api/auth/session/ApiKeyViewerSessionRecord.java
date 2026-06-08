package com.xa.mass.api.auth.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApiKeyViewerSessionRecord(
        String sessionId,
        String keyId,
        String principalId,
        String createdForUserId,
        String credentialHash,
        String keyPrefix,
        List<String> permissions,
        List<String> projectScopes,
        List<String> eventScopes,
        Map<String, String> attributes,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt
) {
}
