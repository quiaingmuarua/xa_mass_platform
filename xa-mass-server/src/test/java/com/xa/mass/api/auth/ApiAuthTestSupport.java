package com.xa.mass.api.auth;

import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;

import java.util.List;

public final class ApiAuthTestSupport {

    private ApiAuthTestSupport() {
    }

    public static ApiAuthService defaultOperatorAuthService() {
        return new ApiAuthService(
                new CompositePrincipalDirectory(List.of(new DefaultOperatorPrincipalDirectory(
                        InMemoryUserRolePermissionStore.bootstrapDefaults()))),
                new HeaderPrincipalContextFactory());
    }
}
