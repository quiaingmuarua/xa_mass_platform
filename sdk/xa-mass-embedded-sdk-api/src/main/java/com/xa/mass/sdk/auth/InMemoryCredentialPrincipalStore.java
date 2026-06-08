package com.xa.mass.sdk.auth;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory credential-principal store used by embedded SDK and tests.
 */
public final class InMemoryCredentialPrincipalStore implements CredentialPrincipalStore {

    private final Map<String, StoredBinding> byPrincipalId = new LinkedHashMap<>();
    private final Map<String, StoredBinding> byCredentialHash = new LinkedHashMap<>();

    @Override
    public synchronized void registerCredentialPrincipal(CredentialPrincipalRegistration registration) {
        CredentialPrincipalRegistration normalized = Objects.requireNonNull(registration, "registration");
        String credentialHash = CredentialHashing.sha256(normalized.getCredential());
        StoredBinding credentialOwner = byCredentialHash.get(credentialHash);
        if (credentialOwner != null && !credentialOwner.profile().getPrincipalId().equals(normalized.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another principal");
        }
        StoredBinding binding = new StoredBinding(
                normalized.toProfile(),
                normalized.toPrincipalContext(),
                credentialHash
        );
        StoredBinding previous = byPrincipalId.put(normalized.getPrincipalId(), binding);
        if (previous != null) {
            byCredentialHash.remove(previous.credentialHash());
        }
        byCredentialHash.put(credentialHash, binding);
    }

    @Override
    public void projectCredential(CredentialPrincipalRegistration registration) {
        registerCredentialPrincipal(registration);
    }

    @Override
    public synchronized boolean hasProjectedCredential(String principalId) {
        return byPrincipalId.containsKey(principalId);
    }

    public synchronized void loadDurable(CredentialPrincipalProfile profile, String credentialHash) {
        CredentialPrincipalProfile normalizedProfile = Objects.requireNonNull(profile, "profile");
        String normalizedCredentialHash = Objects.requireNonNull(credentialHash, "credentialHash");
        StoredBinding credentialOwner = byCredentialHash.get(normalizedCredentialHash);
        if (credentialOwner != null && !credentialOwner.profile().getPrincipalId().equals(normalizedProfile.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another principal");
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

    @Override
    public synchronized List<CredentialPrincipalProfile> listCredentialPrincipals() {
        return byPrincipalId.values().stream()
                .map(StoredBinding::profile)
                .sorted(Comparator.comparing(CredentialPrincipalProfile::getPrincipalId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @Override
    public synchronized CredentialPrincipalProfile getCredentialPrincipal(String principalId) {
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

    private record StoredBinding(CredentialPrincipalProfile profile,
                                 PrincipalContext principalContext,
                                 String credentialHash) {
    }
}
