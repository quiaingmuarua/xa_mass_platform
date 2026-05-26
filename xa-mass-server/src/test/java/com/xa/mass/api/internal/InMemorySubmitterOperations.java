package com.xa.mass.api.internal;

import com.xa.mass.sdk.SubmitterOperations;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.InMemorySubmitterRegistry;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterProfile;
import com.xa.mass.sdk.auth.SubmitterRegistration;

import java.util.List;

final class InMemorySubmitterOperations implements SubmitterOperations, AuthProvider {

    private final InMemorySubmitterRegistry registry = new InMemorySubmitterRegistry();

    @Override
    public void registerSubmitter(SubmitterRegistration submitterRegistration) {
        registry.register(submitterRegistration);
    }

    @Override
    public List<SubmitterProfile> listSubmitters() {
        return registry.listSubmitters();
    }

    @Override
    public SubmitterProfile getSubmitter(String principalId) {
        return registry.getSubmitter(principalId);
    }

    @Override
    public PrincipalContext authenticateSubmitter(String credential) {
        return registry.authenticate(credential);
    }

    @Override
    public PrincipalContext authenticate(String credential) {
        return registry.authenticate(credential);
    }
}
