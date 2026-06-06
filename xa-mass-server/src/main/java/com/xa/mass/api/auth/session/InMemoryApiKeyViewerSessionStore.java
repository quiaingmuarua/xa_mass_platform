package com.xa.mass.api.auth.session;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class InMemoryApiKeyViewerSessionStore implements ApiKeyViewerSessionStore {

    private final Map<String, ApiKeyViewerSessionRecord> bySessionId = new LinkedHashMap<>();
    private final Map<String, String> sessionIdByCredentialHash = new LinkedHashMap<>();

    @Override
    public synchronized ApiKeyViewerSessionRecord create(ApiKeyViewerSessionRecord record) {
        ApiKeyViewerSessionRecord normalized = Objects.requireNonNull(record, "record");
        if (bySessionId.containsKey(normalized.sessionId())) {
            throw new IllegalArgumentException("API-key viewer session already exists: " + normalized.sessionId());
        }
        bySessionId.put(normalized.sessionId(), normalized);
        sessionIdByCredentialHash.put(normalized.credentialHash(), normalized.sessionId());
        return normalized;
    }

    @Override
    public synchronized ApiKeyViewerSessionRecord get(String sessionId) {
        return sessionId == null ? null : bySessionId.get(sessionId);
    }

    @Override
    public synchronized ApiKeyViewerSessionRecord getByCredentialHash(String credentialHash) {
        String sessionId = credentialHash == null ? null : sessionIdByCredentialHash.get(credentialHash);
        return sessionId == null ? null : bySessionId.get(sessionId);
    }

    @Override
    public synchronized ApiKeyViewerSessionRecord revoke(String sessionId) {
        ApiKeyViewerSessionRecord existing = bySessionId.get(sessionId);
        if (existing == null || existing.revokedAt() != null) {
            return existing;
        }
        ApiKeyViewerSessionRecord revoked = new ApiKeyViewerSessionRecord(
                existing.sessionId(),
                existing.keyId(),
                existing.principalId(),
                existing.createdForUserId(),
                existing.credentialHash(),
                existing.keyPrefix(),
                existing.permissions(),
                existing.projectScopes(),
                existing.eventScopes(),
                existing.attributes(),
                existing.createdAt(),
                existing.expiresAt(),
                Instant.now()
        );
        bySessionId.put(revoked.sessionId(), revoked);
        return revoked;
    }
}
