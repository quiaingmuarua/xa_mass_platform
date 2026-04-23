package com.xa.mass.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.api.model.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    private final ApiAuthService apiAuthService;
    private final ObjectMapper objectMapper;

    public ApiAuthInterceptor(ApiAuthService apiAuthService, ObjectMapper objectMapper) {
        this.apiAuthService = apiAuthService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requiredPermission = resolveRequiredPermission(request);
        boolean requiresAuthenticationOnly = requiresAuthenticationOnly(request);
        if (requiredPermission == null && !requiresAuthenticationOnly) {
            return true;
        }

        try {
            if (requiredPermission != null) {
                apiAuthService.requirePermission(request, requiredPermission);
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

    private String resolveRequiredPermission(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod().toUpperCase();

        if (uri.equals("/status/api/tasks")) {
            return switch (method) {
                case "GET" -> ApiPermissionNames.TASK_VIEW;
                case "POST" -> hasSdkCredentialAttempt(request) ? null : ApiPermissionNames.TASK_CREATE;
                default -> null;
            };
        }
        if (uri.matches("^/status/api/tasks/[^/]+$")) {
            return switch (method) {
                case "GET" -> ApiPermissionNames.TASK_VIEW;
                case "PUT" -> ApiPermissionNames.TASK_EDIT;
                case "DELETE" -> ApiPermissionNames.TASK_TERMINATE;
                default -> null;
            };
        }
        if (uri.matches("^/status/api/tasks/[^/]+/status$") && "PUT".equals(method)) {
            return ApiPermissionNames.TASK_EDIT;
        }
        if (uri.matches("^/status/api/tasks/[^/]+/messages$") && "GET".equals(method)) {
            return ApiPermissionNames.TASK_VIEW;
        }
        if (uri.matches("^/status/api/tasks/[^/]+/audit$") && "POST".equals(method)) {
            return ApiPermissionNames.TASK_APPROVE;
        }
        if (uri.matches("^/status/api/tasks/[^/]+/pause$") && "POST".equals(method)) {
            return ApiPermissionNames.TASK_PAUSE;
        }
        if (uri.matches("^/status/api/tasks/[^/]+/resume$") && "POST".equals(method)) {
            return ApiPermissionNames.TASK_RESUME;
        }
        if (uri.matches("^/status/api/tasks/[^/]+/terminate$") && "POST".equals(method)) {
            return ApiPermissionNames.TASK_TERMINATE;
        }
        if (uri.matches("^/status/api/tasks/[^/]+/block$") && "POST".equals(method)) {
            return ApiPermissionNames.TASK_EDIT;
        }
        if (uri.matches("^/status/api/tasks/[^/]+/items$") && "POST".equals(method)) {
            return ApiPermissionNames.TASK_EDIT;
        }
        if (uri.matches("^/status/api/tasks/[^/]+/seal$") && "PUT".equals(method)) {
            return ApiPermissionNames.TASK_EDIT;
        }
        if (uri.equals("/status/api/workers") && "GET".equals(method)) {
            return ApiPermissionNames.WORKER_VIEW;
        }
        if (uri.equals("/status/api/worker-contexts") && "GET".equals(method)) {
            return ApiPermissionNames.WORKER_VIEW;
        }
        if (uri.matches("^/status/api/workers/[^/]+/supported-projects$") && "PUT".equals(method)) {
            return ApiPermissionNames.WORKER_EDIT;
        }
        if (uri.equals("/status/api/rules") && "GET".equals(method)) {
            return ApiPermissionNames.RULE_VIEW;
        }
        if (uri.equals("/status/api/rules/meta") && "GET".equals(method)) {
            return ApiPermissionNames.RULE_VIEW;
        }
        if (uri.equals("/status/workers/message-history") && "GET".equals(method)) {
            return ApiPermissionNames.WORKER_VIEW;
        }
        if (uri.equals("/status/workers/send-event") && "POST".equals(method)) {
            return ApiPermissionNames.WORKER_EDIT;
        }
        return null;
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
}
