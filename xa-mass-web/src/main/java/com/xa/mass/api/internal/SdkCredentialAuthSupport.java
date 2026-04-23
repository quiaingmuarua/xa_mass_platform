package com.xa.mass.api.internal;

import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.TaskSubmitterContext;

final class SdkCredentialAuthSupport {

    static final String API_KEY_HEADER = "X-Mass-Api-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private SdkCredentialAuthSupport() {
    }

    static TaskSubmitterContext authenticate(AuthProvider authProvider,
                                             String apiKeyHeader,
                                             String authorizationHeader) {
        String credential = firstNonBlank(apiKeyHeader);
        if (credential == null && authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)) {
            credential = firstNonBlank(authorizationHeader.substring(BEARER_PREFIX.length()));
        }
        if (credential == null) {
            return null;
        }
        return authProvider.authenticate(credential);
    }

    static String firstNonBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
