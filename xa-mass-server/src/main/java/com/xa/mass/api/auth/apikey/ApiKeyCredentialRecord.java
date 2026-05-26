package com.xa.mass.api.auth.apikey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApiKeyCredentialRecord(
        String keyId,
        String principalId,
        String createdForUserId,
        String keyPrefix,
        String credentialHash,
        List<String> projectScopes,
        List<String> eventScopes,
        List<String> permissions,
        ApiKeyCredentialStatus status,
        String applicationId,
        String createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        String revokedBy,
        String revokeReason,
        Map<String, String> attributes
) {
}
