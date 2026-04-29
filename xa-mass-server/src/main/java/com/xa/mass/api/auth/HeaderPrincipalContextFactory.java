package com.xa.mass.api.auth;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class HeaderPrincipalContextFactory {

    private static final String ATTR_DISPLAY_NAME = "displayName";
    private static final String ATTR_EMAIL = "email";
    private static final String ATTR_ROLES = "roles";

    public PrincipalContext buildOperatorPrincipal(HttpServletRequest request) {
        List<String> roles = parseCsvHeader(request.getHeader(ApiAuthService.USER_ROLES_HEADER));
        List<String> permissions = parseCsvHeader(request.getHeader(ApiAuthService.USER_PERMISSIONS_HEADER));
        String principalId = defaultIfBlank(readTrimmed(request.getHeader(ApiAuthService.USER_ID_HEADER)), "custom-user");
        return PrincipalContext.builder()
                .principalId(principalId)
                .principalType(PrincipalType.OPERATOR)
                .userId(principalId)
                .permissions(permissions)
                .attributes(Map.of(
                        ATTR_DISPLAY_NAME, defaultIfBlank(readTrimmed(request.getHeader(ApiAuthService.USER_NAME_HEADER)), "Custom User"),
                        ATTR_EMAIL, defaultIfBlank(readTrimmed(request.getHeader(ApiAuthService.USER_EMAIL_HEADER)), "custom-user@example.internal"),
                        ATTR_ROLES, roles.isEmpty() ? "CUSTOM" : String.join(",", roles)
                ))
                .projectScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .eventScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .build();
    }

    private List<String> parseCsvHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(headerValue.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private String readTrimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
