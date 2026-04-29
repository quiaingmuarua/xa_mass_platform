package com.xa.mass.sdk.auth;

import java.util.*;

/**
 * Minimal in-memory submitter registry used by the SDK facade.
 */
public final class InMemorySubmitterRegistry implements SubmitterRegistry {

    private final Map<String, StoredBinding> byPrincipalId = new LinkedHashMap<>();
    private final Map<String, StoredBinding> byCredentialHash = new LinkedHashMap<>();

    public synchronized void register(SubmitterRegistration submitterRegistration) {
        SubmitterRegistration registration = Objects.requireNonNull(submitterRegistration, "submitterRegistration");
        String credentialHash = CredentialHashing.sha256(registration.getCredential());
        StoredBinding credentialOwner = byCredentialHash.get(credentialHash);
        if (credentialOwner != null && !credentialOwner.metadata().getPrincipalId().equals(registration.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another submitter");
        }
        StoredBinding binding = new StoredBinding(
                registration.toMetadata(),
                registration.toPrincipalContext(),
                credentialHash
        );
        StoredBinding previous = byPrincipalId.put(registration.getPrincipalId(), binding);
        if (previous != null) {
            byCredentialHash.remove(previous.credentialHash());
        }
        byCredentialHash.put(credentialHash, binding);
    }

    public synchronized void loadDurable(SubmitterMetadata metadata, String credentialHash) {
        SubmitterMetadata normalizedMetadata = Objects.requireNonNull(metadata, "metadata");
        String normalizedCredentialHash = Objects.requireNonNull(credentialHash, "credentialHash");
        StoredBinding credentialOwner = byCredentialHash.get(normalizedCredentialHash);
        if (credentialOwner != null && !credentialOwner.metadata().getPrincipalId().equals(normalizedMetadata.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another submitter");
        }
        StoredBinding binding = new StoredBinding(
                normalizedMetadata,
                normalizedMetadata.toPrincipalContext(),
                normalizedCredentialHash
        );
        StoredBinding previous = byPrincipalId.put(normalizedMetadata.getPrincipalId(), binding);
        if (previous != null) {
            byCredentialHash.remove(previous.credentialHash());
        }
        byCredentialHash.put(normalizedCredentialHash, binding);
    }

    public synchronized List<SubmitterMetadata> listSubmitters() {
        return byPrincipalId.values().stream()
                .map(StoredBinding::metadata)
                .sorted(Comparator.comparing(SubmitterMetadata::getPrincipalId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public synchronized SubmitterMetadata getSubmitter(String principalId) {
        StoredBinding binding = byPrincipalId.get(principalId);
        return binding != null ? binding.metadata() : null;
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
        if (binding == null || !binding.metadata().isEnabled()) {
            return null;
        }
        return binding.principalContext();
    }

    private record StoredBinding(SubmitterMetadata metadata,
                                 PrincipalContext principalContext,
                                 String credentialHash) {
    }
}
