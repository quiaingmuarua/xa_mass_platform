package com.xa.mass.api.auth.apikey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApiKeyApplicationRecord(
        String applicationId,
        String applicantUserId,
        String applicantName,
        String requestedPrincipalId,
        String requestedUserId,
        List<String> requestedProjectScopes,
        List<String> requestedEventScopes,
        List<String> requestedPermissions,
        String purpose,
        ApiKeyApplicationStatus status,
        String reviewReason,
        String reviewedBy,
        Instant createdAt,
        Instant reviewedAt,
        Map<String, String> attributes
) {
}
