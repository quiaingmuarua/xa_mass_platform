package com.xa.mass.api.auth;

import com.xa.mass.sdk.authz.PlatformAction;
import com.xa.mass.sdk.authz.PlatformResourceType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ApiRouteAuthorizationCatalog {

    public RouteAuthorization resolve(HttpServletRequest request, boolean sdkCredentialAttempt) {
        String uri = request.getRequestURI();
        String method = request.getMethod().toUpperCase();

        if (uri.equals("/status/api/tasks")) {
            return switch (method) {
                case "GET" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                case "POST" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.CREATE, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.CREATE, ApiPermissionNames.TASK_CREATE);
                default -> null;
            };
        }
        if (uri.equals("/status/api/tasks/sync") && "POST".equals(method)) {
            return sdkCredentialAttempt
                    ? route(PlatformResourceType.TASK, PlatformAction.CREATE, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                    : route(PlatformResourceType.TASK, PlatformAction.CREATE, ApiPermissionNames.TASK_CREATE);
        }
        if (uri.matches("^/status/api/tasks/[^/]+$")) {
            return switch (method) {
                case "GET" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                case "PUT" -> route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
                case "DELETE" -> route(PlatformResourceType.TASK, PlatformAction.TERMINATE, ApiPermissionNames.TASK_TERMINATE);
                default -> null;
            };
        }
        if (uri.matches("^/status/api/tasks/[^/]+/status$") && "PUT".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
        }
        if (uri.matches("^/status/api/tasks/[^/]+/messages$") && "GET".equals(method)) {
            return sdkCredentialAttempt
                    ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                    : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
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

    private RouteAuthorization route(PlatformResourceType resourceType,
                                     PlatformAction action,
                                     String requiredPermission) {
        return new RouteAuthorization(resourceType, action, requiredPermission);
    }

    public record RouteAuthorization(PlatformResourceType resourceType,
                                     PlatformAction action,
                                     String requiredPermission) {
    }
}
