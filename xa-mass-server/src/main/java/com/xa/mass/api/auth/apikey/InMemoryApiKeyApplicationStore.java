package com.xa.mass.api.auth.apikey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class InMemoryApiKeyApplicationStore implements ApiKeyApplicationStore {

    private final Map<String, ApiKeyApplicationRecord> byApplicationId = new LinkedHashMap<>();

    @Override
    public synchronized ApiKeyApplicationRecord create(ApiKeyApplicationRecord record) {
        ApiKeyApplicationRecord normalized = Objects.requireNonNull(record, "record");
        if (byApplicationId.containsKey(normalized.applicationId())) {
            throw new IllegalArgumentException("API key application already exists: " + normalized.applicationId());
        }
        byApplicationId.put(normalized.applicationId(), normalized);
        return normalized;
    }

    @Override
    public synchronized ApiKeyApplicationRecord get(String applicationId) {
        return applicationId == null ? null : byApplicationId.get(applicationId);
    }

    @Override
    public synchronized List<ApiKeyApplicationRecord> list() {
        return new ArrayList<>(byApplicationId.values()).stream()
                .sorted(Comparator.comparing(ApiKeyApplicationRecord::createdAt)
                        .thenComparing(ApiKeyApplicationRecord::applicationId))
                .toList();
    }

    @Override
    public synchronized ApiKeyApplicationRecord markApproved(String applicationId, String reviewedBy, String reviewReason) {
        return transition(applicationId, ApiKeyApplicationStatus.APPROVED, reviewedBy, reviewReason);
    }

    @Override
    public synchronized ApiKeyApplicationRecord markRejected(String applicationId, String reviewedBy, String reviewReason) {
        return transition(applicationId, ApiKeyApplicationStatus.REJECTED, reviewedBy, reviewReason);
    }

    private ApiKeyApplicationRecord transition(String applicationId,
                                               ApiKeyApplicationStatus status,
                                               String reviewedBy,
                                               String reviewReason) {
        ApiKeyApplicationRecord existing = byApplicationId.get(applicationId);
        if (existing == null) {
            return null;
        }
        if (existing.status() != ApiKeyApplicationStatus.PENDING) {
            throw new IllegalArgumentException("API key application is not pending: " + applicationId);
        }
        ApiKeyApplicationRecord updated = new ApiKeyApplicationRecord(
                existing.applicationId(),
                existing.applicantUserId(),
                existing.applicantName(),
                existing.requestedPrincipalId(),
                existing.requestedUserId(),
                existing.requestedProjectScopes(),
                existing.requestedEventScopes(),
                existing.requestedPermissions(),
                existing.purpose(),
                status,
                normalize(reviewReason),
                normalize(reviewedBy),
                existing.createdAt(),
                Instant.now(),
                existing.attributes()
        );
        byApplicationId.put(applicationId, updated);
        return updated;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
