package com.xa.mass.sdk;

import com.xa.mass.sdk.auth.SubmitterProfile;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;

import java.util.List;

/**
 * Submitter credential resource operations.
 */
public interface SubmitterOperations {

    /**
     * Register or replace a submitter credential binding.
     */
    void registerSubmitter(SubmitterRegistration submitterRegistration);

    /**
     * Register or replace multiple submitter credential bindings.
     */
    default void registerSubmitters(List<SubmitterRegistration> submitterRegistrations) {
        if (submitterRegistrations == null) {
            return;
        }
        submitterRegistrations.forEach(this::registerSubmitter);
    }

    List<SubmitterProfile> listSubmitters();

    SubmitterProfile getSubmitter(String principalId);

    default boolean hasSubmitter(String principalId) {
        return getSubmitter(principalId) != null;
    }

    PrincipalContext authenticateSubmitter(String credential);
}
