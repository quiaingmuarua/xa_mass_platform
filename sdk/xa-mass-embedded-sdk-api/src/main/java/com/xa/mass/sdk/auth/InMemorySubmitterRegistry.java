package com.xa.mass.sdk.auth;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal in-memory submitter registry used by the SDK facade.
 */
public final class InMemorySubmitterRegistry
        implements SubmitterRegistry, CredentialAuthProjectionWriter, AuthProvider, PrincipalDirectory {

    private final Map<String, StoredBinding> byPrincipalId = new LinkedHashMap<>();
    private final Map<String, StoredBinding> byCredentialHash = new LinkedHashMap<>();

    public synchronized void register(SubmitterRegistration submitterRegistration) {
        SubmitterRegistration registration = Objects.requireNonNull(submitterRegistration, "submitterRegistration");
        String credentialHash = CredentialHashing.sha256(registration.getCredential());
        StoredBinding credentialOwner = byCredentialHash.get(credentialHash);
        if (credentialOwner != null && !credentialOwner.profile().getPrincipalId().equals(registration.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another submitter");
        }
        StoredBinding binding = new StoredBinding(
                registration.toProfile(),
                registration.toPrincipalContext(),
                credentialHash
        );
        StoredBinding previous = byPrincipalId.put(registration.getPrincipalId(), binding);
        if (previous != null) {
            byCredentialHash.remove(previous.credentialHash());
        }
        byCredentialHash.put(credentialHash, binding);
    }

    @Override
    public void projectCredential(SubmitterRegistration submitterRegistration) {
        register(submitterRegistration);
    }

    @Override
    public synchronized boolean hasProjectedCredential(String principalId) {
        return byPrincipalId.containsKey(principalId);
    }

    public synchronized void loadDurable(SubmitterProfile profile, String credentialHash) {
        SubmitterProfile normalizedProfile = Objects.requireNonNull(profile, "profile");
        String normalizedCredentialHash = Objects.requireNonNull(credentialHash, "credentialHash");
        StoredBinding credentialOwner = byCredentialHash.get(normalizedCredentialHash);
        if (credentialOwner != null && !credentialOwner.profile().getPrincipalId().equals(normalizedProfile.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another submitter");
        }
        StoredBinding binding = new StoredBinding(
                normalizedProfile,
                normalizedProfile.toPrincipalContext(),
                normalizedCredentialHash
        );
        StoredBinding previous = byPrincipalId.put(normalizedProfile.getPrincipalId(), binding);
        if (previous != null) {
            byCredentialHash.remove(previous.credentialHash());
        }
        byCredentialHash.put(normalizedCredentialHash, binding);
    }

    public synchronized List<SubmitterProfile> listSubmitters() {
        return byPrincipalId.values().stream()
                .map(StoredBinding::profile)
                .sorted(Comparator.comparing(SubmitterProfile::getPrincipalId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public synchronized SubmitterProfile getSubmitter(String principalId) {
        StoredBinding binding = byPrincipalId.get(principalId);
        return binding != null ? binding.profile() : null;
    }

    @Override
    public synchronized PrincipalContext getPrincipal(String principalId) {
        StoredBinding binding = byPrincipalId.get(principalId);
        return binding != null ? binding.principalContext() : null;
    }

    @Override
    public synchronized PrincipalContext authenticate(String credential) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        StoredBinding binding = byCredentialHash.get(CredentialHashing.sha256(credential.trim()));
        if (binding == null || !binding.profile().isEnabled()) {
            return null;
        }
        return binding.principalContext();
    }

    private record StoredBinding(SubmitterProfile profile,
                                 PrincipalContext principalContext,
                                 String credentialHash) {
    }
}
