package com.xa.mass.sdk.auth;

/**
 * Minimal SPI for future API-key and worker-token authentication paths.
 */
public interface AuthProvider {

    TaskSubmitterContext authenticate(String credential);
}
