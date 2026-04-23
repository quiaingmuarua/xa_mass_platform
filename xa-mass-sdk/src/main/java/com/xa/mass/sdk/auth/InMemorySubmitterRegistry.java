package com.xa.mass.sdk.auth;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal in-memory submitter registry used by the SDK facade.
 */
public final class InMemorySubmitterRegistry implements AuthProvider {

    private final Map<String, SubmitterRegistration> byPrincipalId = new LinkedHashMap<>();
    private final Map<String, SubmitterRegistration> byCredential = new LinkedHashMap<>();

    public synchronized void register(SubmitterRegistration submitterRegistration) {
        SubmitterRegistration registration = Objects.requireNonNull(submitterRegistration, "submitterRegistration");
        SubmitterRegistration previous = byPrincipalId.put(registration.getPrincipalId(), registration);
        if (previous != null) {
            byCredential.remove(previous.getCredential());
        }
        byCredential.put(registration.getCredential(), registration);
    }

    public synchronized List<SubmitterRegistration> listSubmitters() {
        return byPrincipalId.values().stream()
                .sorted(Comparator.comparing(SubmitterRegistration::getPrincipalId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public synchronized SubmitterRegistration getSubmitter(String principalId) {
        return byPrincipalId.get(principalId);
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
