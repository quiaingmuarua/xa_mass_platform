package com.xa.mass.api.auth;

import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.api.auth.operator.OperatorSessionService;

import java.util.List;

public final class ApiAuthTestSupport {

    private ApiAuthTestSupport() {
    }

    public static ApiAuthService defaultOperatorAuthService() {
        return operatorAuthService(OperatorAuthProperties.devHeaderForTests());
    }

    public static ApiAuthService sessionOperatorAuthService() {
        return operatorAuthService(OperatorAuthProperties.sessionForTests());
    }

    public static ApiAuthService sessionOperatorAuthService(OperatorSessionService sessionService) {
        return operatorAuthService(OperatorAuthProperties.sessionForTests(), sessionService);
    }

    public static ApiAuthService disabledOperatorAuthService() {
        return operatorAuthService(OperatorAuthProperties.disabledForTests());
    }

    private static ApiAuthService operatorAuthService(OperatorAuthProperties properties) {
        return operatorAuthService(properties, null);
    }

    private static ApiAuthService operatorAuthService(OperatorAuthProperties properties,
                                                     OperatorSessionService sessionService) {
        return new ApiAuthService(
                new CompositePrincipalDirectory(List.of(new DefaultOperatorPrincipalDirectory(
                        InMemoryUserRolePermissionStore.bootstrapDefaults()))),
                new HeaderPrincipalContextFactory(),
                properties,
                sessionService);
    }
}
