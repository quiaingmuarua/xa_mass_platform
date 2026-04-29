package com.xa.mass.api.internal;

import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;

public final class SdkCredentialAuthSupport {

    public static final String API_KEY_HEADER = "X-Mass-Api-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private SdkCredentialAuthSupport() {
    }

    public static PrincipalContext authenticate(AuthProvider authProvider,
                                                String apiKeyHeader,
                                                String authorizationHeader) {
        if (authProvider == null) {
            return null;
        }
        String credential = firstNonBlank(apiKeyHeader);
        if (credential == null && authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)) {
            credential = firstNonBlank(authorizationHeader.substring(BEARER_PREFIX.length()));
        }
        if (credential == null) {
            return null;
        }
        return authProvider.authenticate(credential);
    }

    public static boolean hasCredentialAttempt(String apiKeyHeader, String authorizationHeader) {
        return firstNonBlank(apiKeyHeader) != null
                || (authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX));
    }

    public static String firstNonBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
