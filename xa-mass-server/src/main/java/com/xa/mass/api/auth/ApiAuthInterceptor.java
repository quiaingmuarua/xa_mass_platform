package com.xa.mass.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.auth.PrincipalContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    static final String SDK_CREDENTIAL_BYPASS = "__SDK_CREDENTIAL_BYPASS__";
    static final String SDK_OR_OPERATOR_ROUTE = "__SDK_OR_OPERATOR_ROUTE__";
    public static final String AUTHENTICATED_PRINCIPAL_ATTR =
            ApiAuthInterceptor.class.getName() + ".authenticatedPrincipal";

    private final ApiAuthService apiAuthService;
    private final ObjectMapper objectMapper;
    private final ApiAuthorizationService apiAuthorizationService;
    private final ApiRouteAuthorizationCatalog routeAuthorizationCatalog;

    public ApiAuthInterceptor(ApiAuthService apiAuthService, ObjectMapper objectMapper) {
        this(apiAuthService, objectMapper, new ApiAuthorizationService(), new ApiRouteAuthorizationCatalog());
    }

    @Autowired
    public ApiAuthInterceptor(ApiAuthService apiAuthService,
                              ObjectMapper objectMapper,
                              ApiAuthorizationService apiAuthorizationService,
                              ApiRouteAuthorizationCatalog routeAuthorizationCatalog) {
        this.apiAuthService = apiAuthService;
        this.objectMapper = objectMapper;
        this.apiAuthorizationService = apiAuthorizationService == null ? new ApiAuthorizationService() : apiAuthorizationService;
        this.routeAuthorizationCatalog = routeAuthorizationCatalog == null ? new ApiRouteAuthorizationCatalog() : routeAuthorizationCatalog;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        ApiRouteAuthorizationCatalog.RouteAuthorization routeAuthorization =
                routeAuthorizationCatalog.resolve(request, hasSdkCredentialAttempt(request));
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
                if (SDK_OR_OPERATOR_ROUTE.equals(routeAuthorization.requiredPermission())) {
                    PrincipalContext submitter = apiAuthorizationService.resolveTaskViewerCredential(
                            request.getHeader(SdkCredentialAuthSupport.API_KEY_HEADER),
                            request.getHeader("Authorization"),
                            java.util.Map.of(
                                    "method", request.getMethod(),
                                    "path", request.getRequestURI()
                            )
                    );
                    if (submitter != null) {
                        request.setAttribute(AUTHENTICATED_PRINCIPAL_ATTR, submitter);
                        return true;
                    }
                }
                PrincipalContext principal = apiAuthService.requireAuthenticated(request);
                request.setAttribute(AUTHENTICATED_PRINCIPAL_ATTR, principal);
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
        return ("GET".equalsIgnoreCase(method) && "/api/v1/auth/me".equals(uri))
                || ("POST".equalsIgnoreCase(method) && "/api/v1/auth/logout".equals(uri));
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
