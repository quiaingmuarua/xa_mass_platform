package com.xa.mass.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.PlatformAction;
import com.xa.mass.sdk.authz.PlatformResourceType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    private static final String SDK_CREDENTIAL_BYPASS = "__SDK_CREDENTIAL_BYPASS__";

    private final ApiAuthService apiAuthService;
    private final ObjectMapper objectMapper;
    private final ApiAuthorizationService apiAuthorizationService;

    public ApiAuthInterceptor(ApiAuthService apiAuthService, ObjectMapper objectMapper) {
        this(apiAuthService, objectMapper, new ApiAuthorizationService());
    }

    @Autowired
    public ApiAuthInterceptor(ApiAuthService apiAuthService,
                              ObjectMapper objectMapper,
                              ApiAuthorizationService apiAuthorizationService) {
        this.apiAuthService = apiAuthService;
        this.objectMapper = objectMapper;
        this.apiAuthorizationService = apiAuthorizationService == null ? new ApiAuthorizationService() : apiAuthorizationService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        OperatorRouteAuthorization routeAuthorization = resolveRequiredPermission(request);
        boolean requiresAuthenticationOnly = requiresAuthenticationOnly(request);
        if (routeAuthorization != null && SDK_CREDENTIAL_BYPASS.equals(routeAuthorization.requiredPermission())) {
            return true;
        }
        if (routeAuthorization == null && !requiresAuthenticationOnly) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "API route is not enabled for anonymous or implicit access: " + request.getRequestURI());
            return false;
        }

        try {
            if (routeAuthorization != null) {
                PrincipalContext principal = apiAuthService.requireAuthenticated(request);
                apiAuthorizationService.requireOperatorRoutePermission(
                        principal,
                        routeAuthorization.resourceType(),
                        routeAuthorization.action(),
                        routeAuthorization.requiredPermission(),
                        "operator-route",
                        java.util.Map.of(
                                "method", request.getMethod(),
                                "path", request.getRequestURI()
                        )
                );
            } else {
                apiAuthService.requireAuthenticated(request);
            }
            return true;
        } catch (ApiUnauthenticatedException ex) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
            return false;
        } catch (ApiForbiddenException ex) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ex.getMessage());
            return false;
        } catch (IllegalArgumentException ex) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
            return false;
        }
    }

    private boolean requiresAuthenticationOnly(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        return ("GET".equalsIgnoreCase(method) && "/api/auth/me".equals(uri))
                || ("POST".equalsIgnoreCase(method) && "/api/auth/logout".equals(uri));
    }

    private OperatorRouteAuthorization resolveRequiredPermission(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod().toUpperCase();

        if (uri.equals("/status/api/tasks")) {
            return switch (method) {
                case "GET" -> route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                case "POST" -> hasSdkCredentialAttempt(request)
                        ? route(PlatformResourceType.TASK, PlatformAction.CREATE, SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.CREATE, ApiPermissionNames.TASK_CREATE);
                default -> null;
            };
        }
        if (uri.equals("/status/api/tasks/sync") && "POST".equals(method)) {
            return hasSdkCredentialAttempt(request)
                    ? route(PlatformResourceType.TASK, PlatformAction.CREATE, SDK_CREDENTIAL_BYPASS)
                    : route(PlatformResourceType.TASK, PlatformAction.CREATE, ApiPermissionNames.TASK_CREATE);
        }
        if (uri.matches("^/status/api/tasks/[^/]+$")) {
            return switch (method) {
                case "GET" -> route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                case "PUT" -> route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
                case "DELETE" -> route(PlatformResourceType.TASK, PlatformAction.TERMINATE, ApiPermissionNames.TASK_TERMINATE);
                default -> null;
            };
        }
        if (uri.matches("^/status/api/tasks/[^/]+/status$") && "PUT".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/messages$") && "GET".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/audit$") && "POST".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.APPROVE, ApiPermissionNames.TASK_APPROVE);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/pause$") && "POST".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.PAUSE, ApiPermissionNames.TASK_PAUSE);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/resume$") && "POST".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.RESUME, ApiPermissionNames.TASK_RESUME);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/terminate$") && "POST".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.TERMINATE, ApiPermissionNames.TASK_TERMINATE);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/block$") && "POST".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/items$") && "POST".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/seal$") && "PUT".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
        }
        if (uri.startsWith("/api/queue/") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.startsWith("/api/session/") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.equals("/api/config/projects") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.equals("/status/api/workers") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.equals("/status/api/worker-contexts") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER_CONTEXT, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.equals("/status/api/rules") && "GET".equals(method)) {
            return route(PlatformResourceType.RULE, PlatformAction.VIEW, ApiPermissionNames.RULE_VIEW);
        }
        if (uri.equals("/status/api/rules/meta") && "GET".equals(method)) {
            return route(PlatformResourceType.RULE, PlatformAction.VIEW, ApiPermissionNames.RULE_VIEW);
        }
        return null;
    }

    private OperatorRouteAuthorization route(PlatformResourceType resourceType,
                                             PlatformAction action,
                                             String requiredPermission) {
        return new OperatorRouteAuthorization(resourceType, action, requiredPermission);
    }

    private boolean hasSdkCredentialAttempt(HttpServletRequest request) {
        return SdkCredentialAuthSupport.hasCredentialAttempt(
                request.getHeader(SdkCredentialAuthSupport.API_KEY_HEADER),
                request.getHeader("Authorization")
        );
    }

    private void writeError(HttpServletResponse response, int statusCode, String message) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(statusCode, message));
    }

    private record OperatorRouteAuthorization(PlatformResourceType resourceType,
                                              PlatformAction action,
                                              String requiredPermission) {
    }
}
