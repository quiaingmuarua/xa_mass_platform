package com.xa.mass.api.auth.apikey;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class InMemoryApiKeyCredentialStore implements ApiKeyCredentialStore {

    private final Map<String, ApiKeyCredentialRecord> byKeyId = new LinkedHashMap<>();
    private final Map<String, String> keyIdByPrincipalId = new LinkedHashMap<>();

    @Override
    public synchronized ApiKeyCredentialRecord create(ApiKeyCredentialRecord record) {
        ApiKeyCredentialRecord normalized = Objects.requireNonNull(record, "record");
        if (byKeyId.containsKey(normalized.keyId())) {
            throw new IllegalArgumentException("API key already exists: " + normalized.keyId());
        }
        String existingKeyId = keyIdByPrincipalId.get(normalized.principalId());
        if (existingKeyId != null) {
            throw new IllegalArgumentException("principal already has an API key credential: " + normalized.principalId());
        }
        byKeyId.put(normalized.keyId(), normalized);
        keyIdByPrincipalId.put(normalized.principalId(), normalized.keyId());
        return normalized;
    }

    @Override
    public synchronized ApiKeyCredentialRecord get(String keyId) {
        return keyId == null ? null : byKeyId.get(keyId);
    }

    @Override
    public synchronized ApiKeyCredentialRecord getByPrincipalId(String principalId) {
        String keyId = principalId == null ? null : keyIdByPrincipalId.get(principalId);
        return keyId == null ? null : byKeyId.get(keyId);
    }

    @Override
    public synchronized List<ApiKeyCredentialRecord> list() {
        return new ArrayList<>(byKeyId.values()).stream()
                .sorted(Comparator.comparing(ApiKeyCredentialRecord::createdAt)
                        .thenComparing(ApiKeyCredentialRecord::keyId))
                .toList();
    }

    @Override
    public synchronized ApiKeyCredentialRecord revoke(String keyId, String revokedBy, String revokeReason) {
        ApiKeyCredentialRecord existing = byKeyId.get(keyId);
        if (existing == null) {
            return null;
        }
        if (existing.status() == ApiKeyCredentialStatus.REVOKED) {
            return existing;
        }
        ApiKeyCredentialRecord revoked = new ApiKeyCredentialRecord(
                existing.keyId(),
                existing.principalId(),
                existing.createdForUserId(),
                existing.keyPrefix(),
                existing.credentialHash(),
                existing.projectScopes(),
                existing.eventScopes(),
                existing.permissions(),
                ApiKeyCredentialStatus.REVOKED,
                existing.applicationId(),
                existing.createdBy(),
                existing.createdAt(),
                existing.expiresAt(),
                Instant.now(),
                normalize(revokedBy),
                normalize(revokeReason),
                existing.attributes()
        );
        byKeyId.put(keyId, revoked);
        return revoked;
    }

    @Override
    public synchronized List<ApiKeyCredentialRecord> disableByUserId(String userId, String disabledBy, String disableReason) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId == null) {
            return List.of();
        }
        List<ApiKeyCredentialRecord> disabled = new ArrayList<>();
        for (ApiKeyCredentialRecord existing : new ArrayList<>(byKeyId.values())) {
            if (!normalizedUserId.equals(existing.createdForUserId())
                    || existing.status() == ApiKeyCredentialStatus.REVOKED
                    || existing.status() == ApiKeyCredentialStatus.DISABLED) {
                continue;
            }
            ApiKeyCredentialRecord updated = new ApiKeyCredentialRecord(
                    existing.keyId(),
                    existing.principalId(),
                    existing.createdForUserId(),
                    existing.keyPrefix(),
                    existing.credentialHash(),
                    existing.projectScopes(),
                    existing.eventScopes(),
                    existing.permissions(),
                    ApiKeyCredentialStatus.DISABLED,
                    existing.applicationId(),
                    existing.createdBy(),
                    existing.createdAt(),
                    existing.expiresAt(),
                    Instant.now(),
                    normalize(disabledBy),
                    normalize(disableReason),
                    existing.attributes()
            );
            byKeyId.put(updated.keyId(), updated);
            disabled.add(updated);
        }
        return disabled;
    }

    @Override
    public synchronized ApiKeyCredentialRecord expire(String keyId) {
        ApiKeyCredentialRecord existing = byKeyId.get(keyId);
        if (existing == null) {
            return null;
        }
        if (existing.status() != ApiKeyCredentialStatus.ACTIVE) {
            return existing;
        }
        ApiKeyCredentialRecord expired = new ApiKeyCredentialRecord(
                existing.keyId(),
                existing.principalId(),
                existing.createdForUserId(),
                existing.keyPrefix(),
                existing.credentialHash(),
                existing.projectScopes(),
                existing.eventScopes(),
                existing.permissions(),
                ApiKeyCredentialStatus.EXPIRED,
                existing.applicationId(),
                existing.createdBy(),
                existing.createdAt(),
                existing.expiresAt(),
                Instant.now(),
                "system",
                "expiresAt reached",
                existing.attributes()
        );
        byKeyId.put(keyId, expired);
        return expired;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
