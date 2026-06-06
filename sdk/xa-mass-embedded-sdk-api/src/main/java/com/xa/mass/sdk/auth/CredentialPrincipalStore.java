package com.xa.mass.sdk.auth;

import java.util.List;

/**
 * Embedded credential-principal owner for auth projection, authentication, and
 * principal lookup.
 */
public interface CredentialPrincipalStore
        extends CredentialAuthProjectionWriter, AuthProvider, PrincipalDirectory {

    void registerCredentialPrincipal(CredentialPrincipalRegistration registration);

    List<CredentialPrincipalProfile> listCredentialPrincipals();

    CredentialPrincipalProfile getCredentialPrincipal(String principalId);
}
