package com.xa.mass.api.auth.operator;

import java.util.List;

public interface OperatorCredentialStore {

    List<OperatorCredentialRecord> list();

    OperatorCredentialRecord get(String userId);

    OperatorCredentialRecord upsert(OperatorCredentialRecord credential);

    default boolean hasActiveCredential() {
        return list().stream().anyMatch(OperatorCredentialRecord::active);
    }
}
