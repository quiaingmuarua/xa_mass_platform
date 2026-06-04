package com.xa.mass.api.auth.session;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class InMemorySubmitterViewerSessionStore implements SubmitterViewerSessionStore {

    private final Map<String, SubmitterViewerSessionRecord> bySessionId = new LinkedHashMap<>();
    private final Map<String, String> sessionIdByCredentialHash = new LinkedHashMap<>();

    @Override
    public synchronized SubmitterViewerSessionRecord create(SubmitterViewerSessionRecord record) {
        SubmitterViewerSessionRecord normalized = Objects.requireNonNull(record, "record");
        if (bySessionId.containsKey(normalized.sessionId())) {
            throw new IllegalArgumentException("submitter viewer session already exists: " + normalized.sessionId());
        }
        bySessionId.put(normalized.sessionId(), normalized);
        sessionIdByCredentialHash.put(normalized.credentialHash(), normalized.sessionId());
        return normalized;
    }

    @Override
    public synchronized SubmitterViewerSessionRecord get(String sessionId) {
        return sessionId == null ? null : bySessionId.get(sessionId);
    }

    @Override
    public synchronized SubmitterViewerSessionRecord getByCredentialHash(String credentialHash) {
        String sessionId = credentialHash == null ? null : sessionIdByCredentialHash.get(credentialHash);
        return sessionId == null ? null : bySessionId.get(sessionId);
    }

    @Override
    public synchronized SubmitterViewerSessionRecord revoke(String sessionId) {
        SubmitterViewerSessionRecord existing = bySessionId.get(sessionId);
        if (existing == null || existing.revokedAt() != null) {
            return existing;
        }
        SubmitterViewerSessionRecord revoked = new SubmitterViewerSessionRecord(
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
