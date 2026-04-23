package com.xa.mass.sdk.auth;

import java.util.*;

/**
 * Minimal in-memory submitter registry used by the SDK facade.
 */
public final class InMemorySubmitterRegistry implements AuthProvider {

    private final Map<String, SubmitterRegistration> byPrincipalId = new LinkedHashMap<>();
    private final Map<String, SubmitterRegistration> byCredential = new LinkedHashMap<>();

    public synchronized void register(SubmitterRegistration submitterRegistration) {
        SubmitterRegistration registration = Objects.requireNonNull(submitterRegistration, "submitterRegistration");
        SubmitterRegistration credentialOwner = byCredential.get(registration.getCredential());
        if (credentialOwner != null && !credentialOwner.getPrincipalId().equals(registration.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another submitter");
        }
        SubmitterRegistration previous = byPrincipalId.put(registration.getPrincipalId(), registration);
        if (previous != null) {
            byCredential.remove(previous.getCredential());
        }
        byCredential.put(registration.getCredential(), registration);
    }

    public synchronized List<SubmitterMetadata> listSubmitters() {
        return byPrincipalId.values().stream()
                .sorted(Comparator.comparing(SubmitterRegistration::getPrincipalId, Comparator.nullsLast(String::compareTo)))
                .map(SubmitterRegistration::toMetadata)
                .toList();
    }

    public synchronized SubmitterMetadata getSubmitter(String principalId) {
        SubmitterRegistration registration = byPrincipalId.get(principalId);
        return registration != null ? registration.toMetadata() : null;
    }

    @Override
    public synchronized TaskSubmitterContext authenticate(String credential) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        SubmitterRegistration registration = byCredential.get(credential.trim());
        if (registration == null || !registration.isEnabled()) {
            return null;
        }
        return registration.toSubmitterContext();
    }
}
