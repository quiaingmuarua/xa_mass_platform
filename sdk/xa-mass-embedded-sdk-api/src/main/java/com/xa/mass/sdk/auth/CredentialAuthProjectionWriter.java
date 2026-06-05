package com.xa.mass.sdk.auth;

/**
 * Narrow write surface for publishing credential authentication projection
 * state from a lifecycle owner.
 *
 * <p>This is intentionally smaller than submitter resource operations: it does
 * not expose list/get facade methods or secret authentication. Lifecycle owners
 * use it to replace the active or disabled auth projection derived from their
 * own durable credential record.
 */
public interface CredentialAuthProjectionWriter {

    void projectCredential(SubmitterRegistration submitterRegistration);

    boolean hasProjectedCredential(String principalId);
}
