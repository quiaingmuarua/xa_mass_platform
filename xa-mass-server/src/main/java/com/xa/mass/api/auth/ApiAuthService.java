package com.xa.mass.api.auth;

import com.xa.mass.api.auth.operator.OperatorSessionRecord;
import com.xa.mass.api.auth.operator.OperatorSessionService;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalDirectory;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class ApiAuthService {
    private static final Logger logger = LoggerFactory.getLogger(ApiAuthService.class);
    private static final String ATTR_DISPLAY_NAME = "displayName";
    private static final String ATTR_EMAIL = "email";
    private static final String ATTR_ROLES = "roles";

    public static final String USER_MODE_HEADER = "X-Mass-User-Mode";
    public static final String USER_ID_HEADER = "X-Mass-User-Id";
    public static final String USER_NAME_HEADER = "X-Mass-User-Name";
    public static final String USER_EMAIL_HEADER = "X-Mass-User-Email";
    public static final String USER_ROLES_HEADER = "X-Mass-Roles";
    public static final String USER_PERMISSIONS_HEADER = "X-Mass-Permissions";

    private final PrincipalDirectory principalDirectory;
    private final HeaderPrincipalContextFactory headerPrincipalContextFactory;
    private final OperatorAuthProperties operatorAuthProperties;
    private final OperatorSessionService operatorSessionService;

    public ApiAuthService(PrincipalDirectory principalDirectory,
                          HeaderPrincipalContextFactory headerPrincipalContextFactory) {
        this(principalDirectory, headerPrincipalContextFactory, OperatorAuthProperties.devHeaderForTests(), null);
    }

    @Autowired
    public ApiAuthService(PrincipalDirectory principalDirectory,
                          HeaderPrincipalContextFactory headerPrincipalContextFactory,
                          OperatorAuthProperties operatorAuthProperties,
                          OperatorSessionService operatorSessionService) {
        this.principalDirectory = Objects.requireNonNull(principalDirectory, "principalDirectory");
        this.headerPrincipalContextFactory = Objects.requireNonNull(headerPrincipalContextFactory, "headerPrincipalContextFactory");
        this.operatorAuthProperties = Objects.requireNonNull(operatorAuthProperties, "operatorAuthProperties");
        this.operatorSessionService = operatorSessionService;
    }

    public ApiAuthService(PrincipalDirectory principalDirectory,
                          HeaderPrincipalContextFactory headerPrincipalContextFactory,
                          OperatorAuthProperties operatorAuthProperties) {
        this(principalDirectory, headerPrincipalContextFactory, operatorAuthProperties, null);
    }

    public PrincipalContext resolveCurrentPrincipal(HttpServletRequest request) {
        if (operatorAuthProperties.mode() == OperatorAuthMode.SESSION) {
            return resolveSessionPrincipal(request);
        }
        if (operatorAuthProperties.mode() == OperatorAuthMode.DISABLED) {
            return null;
        }
        String explicitMode = readTrimmed(request.getHeader(USER_MODE_HEADER));
        if (explicitMode == null && hasCustomHeaders(request)) {
            return buildCustomPrincipal(request);
        }
        if (explicitMode == null || explicitMode.isBlank()) {
            return requireKnownPrincipal("ops-admin");
        }

        return switch (explicitMode.trim().toLowerCase()) {
            case "admin" -> requireKnownPrincipal("ops-admin");
            case "viewer" -> requireKnownPrincipal("ops-viewer");
            case "anonymous" -> null;
            case "custom" -> buildCustomPrincipal(request);
            default -> throw new IllegalArgumentException("Unsupported auth mode header: " + explicitMode);
        };
    }

    public PrincipalContext requireAuthenticated(HttpServletRequest request) {
        PrincipalContext principal = resolveCurrentPrincipal(request);
        if (principal == null) {
            logger.warn("Authentication required: method={} path={} mode={}",
                    request.getMethod(), request.getRequestURI(), readTrimmed(request.getHeader(USER_MODE_HEADER)));
            throw new ApiUnauthenticatedException("Authentication is required");
        }
        return principal;
    }

    public PrincipalContext requirePermission(HttpServletRequest request, String permission) {
        PrincipalContext principal = requireAuthenticated(request);
        if (!principal.hasPermission(permission)) {
            throw new ApiForbiddenException("Missing permission: " + permission);
        }
        return principal;
    }

    public void requireCsrf(HttpServletRequest request) {
        if (operatorAuthProperties.mode() != OperatorAuthMode.SESSION || isSafeMethod(request.getMethod())) {
            return;
        }
        if (operatorSessionService == null || !operatorSessionService.csrfMatches(request)) {
            throw new ApiForbiddenException("Missing or invalid CSRF token");
        }
    }

    private boolean hasCustomHeaders(HttpServletRequest request) {
        return readTrimmed(request.getHeader(USER_ID_HEADER)) != null
                || readTrimmed(request.getHeader(USER_NAME_HEADER)) != null
                || readTrimmed(request.getHeader(USER_ROLES_HEADER)) != null
                || readTrimmed(request.getHeader(USER_PERMISSIONS_HEADER)) != null;
    }

    public ApiCurrentUser toApiCurrentUser(PrincipalContext principal) {
        if (principal == null) {
            return null;
        }
        String defaultName = switch (principal.getPrincipalType()) {
            case OPERATOR -> "Ops User";
            case WORKER -> "Worker Principal";
            case SERVICE -> "Service Principal";
        };
        String emailPrefix = switch (principal.getPrincipalType()) {
            case OPERATOR -> "ops";
            case WORKER -> "worker";
            case SERVICE -> "service";
        };
        return new ApiCurrentUser(
                principal.getPrincipalId(),
                defaultIfBlank(principal.getAttributes().get(ATTR_DISPLAY_NAME), defaultName),
                defaultIfBlank(principal.getAttributes().get(ATTR_EMAIL),
                        principal.getUserId() == null ? emailPrefix + "-" + principal.getPrincipalId() + "@example.internal"
                                : principal.getUserId() + "@example.internal"),
                parseCsvAttribute(principal.getAttributes().get(ATTR_ROLES), principal.getPrincipalType().name()),
                principal.getPermissions()
        );
    }

    private PrincipalContext buildCustomPrincipal(HttpServletRequest request) {
        return headerPrincipalContextFactory.buildOperatorPrincipal(request);
    }

    public PrincipalContext requireKnownOperatorPrincipal(String principalId) {
        return requireKnownPrincipal(principalId);
    }

    private PrincipalContext resolveSessionPrincipal(HttpServletRequest request) {
        if (operatorSessionService == null) {
            return null;
        }
        OperatorSessionRecord session = operatorSessionService.resolve(request);
        if (session == null) {
            return null;
        }
        return principalDirectory.getPrincipal(session.userId());
    }

    private PrincipalContext requireKnownPrincipal(String principalId) {
        PrincipalContext principal = principalDirectory.getPrincipal(principalId);
        if (principal == null) {
            throw new IllegalStateException("Missing principal definition: " + principalId);
        }
        return principal;
    }

    private boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private List<String> parseCsvAttribute(String value, String defaultValue) {
        List<String> parsed = parseCsvHeader(value);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return List.of(defaultValue);
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
}
