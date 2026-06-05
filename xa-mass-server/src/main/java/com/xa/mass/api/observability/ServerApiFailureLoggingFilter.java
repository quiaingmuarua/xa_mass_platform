package com.xa.mass.api.observability;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ServerApiFailureLoggingFilter extends OncePerRequestFilter {

    private final ServerApiFailureLogger failureLogger;

    public ServerApiFailureLoggingFilter(ServerApiFailureLogger failureLogger) {
        this.failureLogger = failureLogger;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            emitIfNeeded(request, response, startedNanos);
        }
    }

    private void emitIfNeeded(HttpServletRequest request,
                              HttpServletResponse response,
                              long startedNanos) {
        int status = response.getStatus();
        if (!isInScope(request, status) || Boolean.TRUE.equals(request.getAttribute(ServerApiFailureAttributes.EMITTED_ATTR))) {
            return;
        }
        request.setAttribute(ServerApiFailureAttributes.EMITTED_ATTR, Boolean.TRUE);
        String failureClass = attribute(request, ServerApiFailureAttributes.FAILURE_CLASS_ATTR);
        if (failureClass == null) {
            failureClass = ServerApiFailureAttributes.failureClassForStatus(status);
        }
        String originSurface = attribute(request, ServerApiFailureAttributes.ORIGIN_SURFACE_ATTR);
        if (originSurface == null) {
            originSurface = originSurface(request);
        }
        PrincipalContext principal = principal(request);
        long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        failureLogger.logFailure(new ServerApiFailureLogger.ServerApiFailureEvent(
                failureClass,
                request.getMethod(),
                sanitizePath(request.getRequestURI()),
                status,
                attribute(request, ServerApiFailureAttributes.SAFE_MESSAGE_ATTR),
                attribute(request, ServerApiFailureAttributes.TRACE_ID_ATTR),
                durationMs,
                principal == null ? null : principal.getPrincipalId(),
                principal == null ? null : principal.getPrincipalType().name(),
                attribute(request, ServerApiFailureAttributes.ROUTE_AUTHORIZATION_CLASS_ATTR),
                attribute(request, ServerApiFailureAttributes.REQUIRED_PERMISSION_ATTR),
                originSurface,
                requestSource(request, originSurface)
        ));
    }

    private boolean isInScope(HttpServletRequest request, int status) {
        if (status < 400 || status > 599) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null
                && (uri.startsWith("/api/v1/")
                || uri.startsWith("/internal/v1/")
                || uri.startsWith("/worker-api/v1/"));
    }

    private String originSurface(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return "unknown";
        }
        if (uri.startsWith("/worker-api/v1/")) {
            return "worker-api";
        }
        if (uri.startsWith("/internal/v1/")) {
            return "console";
        }
        if (uri.startsWith("/api/v1/submitter-sessions")) {
            return "submitter-viewer";
        }
        if (uri.startsWith("/api/v1/submitters/me")) {
            return "sdk";
        }
        if (hasSdkCredentialAttempt(request)
                && (uri.startsWith("/api/v1/tasks")
                || uri.startsWith("/api/v1/projects")
                || uri.startsWith("/api/v1/catalog")
                || uri.matches("^/api/v1/api-keys/[^/]+/usage$"))) {
            return "sdk";
        }
        PrincipalContext principal = principal(request);
        if (principal != null && principal.getPrincipalType() == PrincipalType.OPERATOR) {
            return "console";
        }
        if (principal != null && principal.getPrincipalType() == PrincipalType.SERVICE) {
            return "sdk";
        }
        if (uri.startsWith("/api/v1/auth/")
                || uri.startsWith("/api/v1/users")
                || uri.startsWith("/api/v1/roles")
                || uri.startsWith("/api/v1/permissions")
                || uri.startsWith("/api/v1/api-keys")
                || uri.startsWith("/api/v1/api-key-applications")
                || uri.startsWith("/api/v1/admin/rules")
                || uri.startsWith("/api/v1/runtime/")) {
            return "console";
        }
        return "unknown";
    }

    private String requestSource(HttpServletRequest request, String originSurface) {
        if ("sdk".equals(originSurface) || "worker-api".equals(originSurface)) {
            return "sdk";
        }
        String fetchMode = request.getHeader("Sec-Fetch-Mode");
        if (fetchMode != null && !fetchMode.isBlank()) {
            return "browser";
        }
        return "unknown";
    }

    private boolean hasSdkCredentialAttempt(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ServerApiFailureAttributes.SDK_CREDENTIAL_ATTEMPT_ATTR));
    }

    private PrincipalContext principal(HttpServletRequest request) {
        Object value = request.getAttribute(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR);
        return value instanceof PrincipalContext principal ? principal : null;
    }

    private String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.replaceAll("[\\r\\n\\t]", "");
    }

    private String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
