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

        if (uri.equals("/api/v1/tasks")) {
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
        if (uri.equals("/internal/v1/debug/task-invocations:sync") && "POST".equals(method)) {
            return sdkCredentialAttempt
                    ? route(PlatformResourceType.TASK, PlatformAction.CREATE, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                    : route(PlatformResourceType.TASK, PlatformAction.CREATE, ApiPermissionNames.TASK_CREATE);
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+$")) {
            return switch (method) {
                case "GET" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                case "PATCH" -> route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
                default -> null;
            };
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+/items$")) {
            return switch (method) {
                case "POST" -> route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
                default -> null;
            };
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+/items:sync$")) {
            return switch (method) {
                case "POST" -> route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
                default -> null;
            };
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+/items/[^/:]+/stages$")) {
            return switch (method) {
                case "GET" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                default -> null;
            };
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+/items/[^/:]+/stages/[^/:]+$")) {
            return switch (method) {
                case "GET" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                default -> null;
            };
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+/items/[^/:]+/stages/[^/:]+/evidence$")) {
            return switch (method) {
                case "POST" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
                default -> null;
            };
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+/commands$") && "POST".equals(method)) {
            return route(PlatformResourceType.TASK, PlatformAction.EDIT, ApiAuthInterceptor.OPERATOR_AUTH_ONLY);
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+/results$")) {
            return switch (method) {
                case "GET" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                default -> null;
            };
        }
        if (uri.matches("^/api/v1/tasks/[^/:]+/results/archive(/content)?$")) {
            return switch (method) {
                case "GET" -> sdkCredentialAttempt
                        ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                        : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                default -> null;
            };
        }
        if (uri.matches("^/internal/v1/review/tasks/[^/:]+(/seed-export|/result-export)?$")) {
            return switch (method) {
                case "GET" -> route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
                default -> null;
            };
        }
        if (uri.equals("/api/v1/projects") && "GET".equals(method)) {
            return sdkCredentialAttempt
                    ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_OR_OPERATOR_ROUTE)
                    : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
        }
        if (uri.matches("^/api/v1/projects/[^/]+(/events|/submitters)?$") && "GET".equals(method)) {
            return sdkCredentialAttempt
                    ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_OR_OPERATOR_ROUTE)
                    : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
        }
        if (uri.startsWith("/api/v1/catalog/") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS);
        }
        if (uri.equals("/api/v1/submitters/me") && "GET".equals(method)) {
            return sdkCredentialAttempt
                    ? route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiAuthInterceptor.SDK_CREDENTIAL_BYPASS)
                    : route(PlatformResourceType.TASK, PlatformAction.VIEW, ApiPermissionNames.TASK_VIEW);
        }
        if (uri.startsWith("/api/v1/runtime/queues") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.startsWith("/api/v1/runtime/sessions") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.equals("/api/v1/runtime/config/projects") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.equals("/api/v1/runtime/workers") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.matches("^/api/v1/runtime/workers/[^/]+/capability-reports$") && "POST".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.EDIT, ApiPermissionNames.WORKER_EDIT);
        }
        if (uri.matches("^/api/v1/runtime/workers/[^/]+/state-reports$") && "POST".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.EDIT, ApiPermissionNames.WORKER_EDIT);
        }
        if (uri.matches("^/api/v1/runtime/workers/[^/]+/state$") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.equals("/api/v1/runtime/workers/states") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.matches("^/api/v1/runtime/workers/[^/]+/commands$")) {
            return switch (method) {
                case "GET" -> route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
                case "POST" -> route(PlatformResourceType.WORKER, PlatformAction.EDIT, ApiPermissionNames.WORKER_EDIT);
                default -> null;
            };
        }
        if (uri.matches("^/api/v1/runtime/workers/commands/[^/]+$") && "GET".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.VIEW, ApiPermissionNames.WORKER_VIEW);
        }
        if (uri.matches("^/api/v1/runtime/workers/[^/]+/commands/[^/]+/ack$") && "POST".equals(method)) {
            return route(PlatformResourceType.WORKER, PlatformAction.EDIT, ApiPermissionNames.WORKER_EDIT);
        }
        if (uri.equals("/api/v1/runtime/rules") && "GET".equals(method)) {
            return route(PlatformResourceType.RULE, PlatformAction.VIEW, ApiPermissionNames.RULE_VIEW);
        }
        if (uri.equals("/api/v1/runtime/rules/meta") && "GET".equals(method)) {
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
