package com.xa.mass.api.observability;

import jakarta.servlet.http.HttpServletRequest;

public final class ServerApiFailureAttributes {

    public static final String TRACE_ID_ATTR = ServerApiFailureAttributes.class.getName() + ".traceId";
    public static final String FAILURE_CLASS_ATTR = ServerApiFailureAttributes.class.getName() + ".failureClass";
    public static final String SAFE_MESSAGE_ATTR = ServerApiFailureAttributes.class.getName() + ".safeMessage";
    public static final String REQUIRED_PERMISSION_ATTR = ServerApiFailureAttributes.class.getName() + ".requiredPermission";
    public static final String ROUTE_AUTHORIZATION_CLASS_ATTR =
            ServerApiFailureAttributes.class.getName() + ".routeAuthorizationClass";
    public static final String ORIGIN_SURFACE_ATTR = ServerApiFailureAttributes.class.getName() + ".originSurface";
    public static final String SDK_CREDENTIAL_ATTEMPT_ATTR =
            ServerApiFailureAttributes.class.getName() + ".sdkCredentialAttempt";
    public static final String EMITTED_ATTR = ServerApiFailureAttributes.class.getName() + ".emitted";

    public static final String EVENT = "SERVER_API_FAILURE";

    public static final String AUTHENTICATION = "AUTHENTICATION";
    public static final String AUTHORIZATION = "AUTHORIZATION";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String CONFLICT = "CONFLICT";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String RATE_LIMIT = "RATE_LIMIT";
    public static final String UNHANDLED = "UNHANDLED";

    private ServerApiFailureAttributes() {
    }

    public static void markFailure(HttpServletRequest request,
                                   String failureClass,
                                   String safeMessage) {
        if (request == null) {
            return;
        }
        setIfPresent(request, FAILURE_CLASS_ATTR, failureClass);
        setIfPresent(request, SAFE_MESSAGE_ATTR, safeMessage);
    }

    public static void markRouteAuthorization(HttpServletRequest request,
                                              String requiredPermission,
                                              String routeAuthorizationClass) {
        if (request == null) {
            return;
        }
        setIfPresent(request, REQUIRED_PERMISSION_ATTR, requiredPermission);
        setIfPresent(request, ROUTE_AUTHORIZATION_CLASS_ATTR, routeAuthorizationClass);
    }

    public static void markOriginSurface(HttpServletRequest request, String originSurface) {
        if (request != null && originSurface != null && !originSurface.isBlank()) {
            request.setAttribute(ORIGIN_SURFACE_ATTR, originSurface.trim());
        }
    }

    public static void markSdkCredentialAttempt(HttpServletRequest request, boolean attempted) {
        if (request != null && attempted) {
            request.setAttribute(SDK_CREDENTIAL_ATTEMPT_ATTR, Boolean.TRUE);
        }
    }

    public static String failureClassForStatus(int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> AUTHENTICATION;
            case 403 -> AUTHORIZATION;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 429 -> RATE_LIMIT;
            default -> status >= 500 ? UNHANDLED : BAD_REQUEST;
        };
    }

    private static void setIfPresent(HttpServletRequest request, String attribute, String value) {
        if (value != null && !value.isBlank()) {
            request.setAttribute(attribute, value.trim());
        }
    }
}
