package com.xa.mass.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ApiAuthService {

    public static final String USER_MODE_HEADER = "X-Mass-User-Mode";
    public static final String USER_ID_HEADER = "X-Mass-User-Id";
    public static final String USER_NAME_HEADER = "X-Mass-User-Name";
    public static final String USER_EMAIL_HEADER = "X-Mass-User-Email";
    public static final String USER_ROLES_HEADER = "X-Mass-Roles";
    public static final String USER_PERMISSIONS_HEADER = "X-Mass-Permissions";

    public ApiCurrentUser resolveCurrentUser(HttpServletRequest request) {
        String explicitMode = readTrimmed(request.getHeader(USER_MODE_HEADER));
        if (explicitMode == null && hasCustomHeaders(request)) {
            return buildCustomUser(request);
        }
        if (explicitMode == null || explicitMode.isBlank()) {
            return adminUser();
        }

        return switch (explicitMode.trim().toLowerCase()) {
            case "admin" -> adminUser();
            case "viewer" -> viewerUser();
            case "anonymous" -> null;
            case "custom" -> buildCustomUser(request);
            default -> throw new IllegalArgumentException("Unsupported auth mode header: " + explicitMode);
        };
    }

    public ApiCurrentUser requireAuthenticated(HttpServletRequest request) {
        ApiCurrentUser currentUser = resolveCurrentUser(request);
        if (currentUser == null) {
            throw new ApiUnauthenticatedException("Authentication is required");
        }
        return currentUser;
    }

    public ApiCurrentUser requirePermission(HttpServletRequest request, String permission) {
        ApiCurrentUser currentUser = requireAuthenticated(request);
        if (!currentUser.permissions().contains(permission)) {
            throw new ApiForbiddenException("Missing permission: " + permission);
        }
        return currentUser;
    }

    private boolean hasCustomHeaders(HttpServletRequest request) {
        return readTrimmed(request.getHeader(USER_ID_HEADER)) != null
                || readTrimmed(request.getHeader(USER_NAME_HEADER)) != null
                || readTrimmed(request.getHeader(USER_ROLES_HEADER)) != null
                || readTrimmed(request.getHeader(USER_PERMISSIONS_HEADER)) != null;
    }

    private ApiCurrentUser adminUser() {
        return new ApiCurrentUser(
                "ops-admin",
                "Ops Admin",
                "ops-admin@example.internal",
                List.of("OPS_ADMIN"),
                ApiPermissionNames.ALL
        );
    }

    private ApiCurrentUser viewerUser() {
        return new ApiCurrentUser(
                "ops-viewer",
                "Ops Viewer",
                "ops-viewer@example.internal",
                List.of("OPS_VIEWER"),
                List.of(
                        ApiPermissionNames.TASK_VIEW,
                        ApiPermissionNames.WORKER_VIEW,
                        ApiPermissionNames.RULE_VIEW,
                        ApiPermissionNames.CONFIG_VIEW,
                        ApiPermissionNames.AUDIT_VIEW
                )
        );
    }

    private ApiCurrentUser buildCustomUser(HttpServletRequest request) {
        List<String> roles = parseCsvHeader(request.getHeader(USER_ROLES_HEADER));
        List<String> permissions = parseCsvHeader(request.getHeader(USER_PERMISSIONS_HEADER));
        return new ApiCurrentUser(
                defaultIfBlank(readTrimmed(request.getHeader(USER_ID_HEADER)), "custom-user"),
                defaultIfBlank(readTrimmed(request.getHeader(USER_NAME_HEADER)), "Custom User"),
                defaultIfBlank(readTrimmed(request.getHeader(USER_EMAIL_HEADER)), "custom-user@example.internal"),
                roles.isEmpty() ? List.of("CUSTOM") : roles,
                permissions
        );
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
