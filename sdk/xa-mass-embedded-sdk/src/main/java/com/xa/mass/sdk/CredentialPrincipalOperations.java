package com.xa.mass.sdk;

import com.xa.mass.sdk.auth.CredentialPrincipalProfile;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;

import java.util.List;

/**
 * API-key credential-principal resource operations.
 */
public interface CredentialPrincipalOperations {

    void registerCredentialPrincipal(CredentialPrincipalRegistration registration);

    default void registerCredentialPrincipals(List<CredentialPrincipalRegistration> registrations) {
        if (registrations == null) {
            return;
        }
        registrations.forEach(this::registerCredentialPrincipal);
    }

    List<CredentialPrincipalProfile> listCredentialPrincipals();

    CredentialPrincipalProfile getCredentialPrincipal(String principalId);

    default boolean hasCredentialPrincipal(String principalId) {
        return getCredentialPrincipal(principalId) != null;
    }

    PrincipalContext authenticateCredential(String credential);
}
