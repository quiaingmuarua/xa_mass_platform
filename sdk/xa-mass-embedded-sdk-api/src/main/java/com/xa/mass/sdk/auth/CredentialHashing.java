package com.xa.mass.sdk.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared credential hashing helper for low-frequency control-plane auth
 * persistence.
 */
public final class CredentialHashing {

    private CredentialHashing() {
    }

    public static String sha256(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("credential must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(credential.trim().getBytes(StandardCharsets.UTF_8));
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
