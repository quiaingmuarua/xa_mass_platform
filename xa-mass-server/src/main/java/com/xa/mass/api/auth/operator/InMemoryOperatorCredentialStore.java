package com.xa.mass.api.auth.operator;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryOperatorCredentialStore implements OperatorCredentialStore {

    private final Map<String, OperatorCredentialRecord> credentialsByUserId = new LinkedHashMap<>();

    @Override
    public synchronized List<OperatorCredentialRecord> list() {
        return credentialsByUserId.values().stream()
                .sorted(Comparator.comparing(OperatorCredentialRecord::userId))
                .toList();
    }

    @Override
    public synchronized OperatorCredentialRecord get(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return credentialsByUserId.get(userId.trim());
    }

    @Override
    public synchronized OperatorCredentialRecord upsert(OperatorCredentialRecord credential) {
        OperatorCredentialRecord normalized = Objects.requireNonNull(credential, "credential");
        credentialsByUserId.put(normalized.userId(), normalized);
        return normalized;
    }
}
