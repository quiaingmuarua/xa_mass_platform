package com.xa.mass.sdk.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Minimal in-memory submitter registry used by the SDK facade.
 */
public final class InMemorySubmitterRegistry implements AuthProvider {

    private final Map<String, SubmitterRegistration> byPrincipalId = new LinkedHashMap<>();
    private final Map<String, SubmitterRegistration> byCredentialHash = new LinkedHashMap<>();

    public synchronized void register(SubmitterRegistration submitterRegistration) {
        SubmitterRegistration registration = Objects.requireNonNull(submitterRegistration, "submitterRegistration");
        String credentialHash = hashCredential(registration.getCredential());
        SubmitterRegistration credentialOwner = byCredentialHash.get(credentialHash);
        if (credentialOwner != null && !credentialOwner.getPrincipalId().equals(registration.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another submitter");
        }
        SubmitterRegistration previous = byPrincipalId.put(registration.getPrincipalId(), registration);
        if (previous != null) {
            byCredentialHash.remove(hashCredential(previous.getCredential()));
        }
        byCredentialHash.put(credentialHash, registration);
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
        SubmitterRegistration registration = byCredentialHash.get(hashCredential(credential.trim()));
        if (registration == null || !registration.isEnabled()) {
            return null;
        }
        return registration.toSubmitterContext();
    }

    private static String hashCredential(String credential) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(credential.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
