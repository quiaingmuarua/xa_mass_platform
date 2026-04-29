package com.xa.mass.sdk.authz;

/**
 * Structured deny reasons shared by the platform authorization and event permission layers.
 */
public enum AuthorizationReasonCode {
    ALLOWED,
    PRINCIPAL_REQUIRED,
    PERMISSION_DENIED,
    PROJECT_SCOPE_DENIED,
    EVENT_SCOPE_DENIED,
    USER_SCOPE_DENIED,
    WORKER_BINDING_MISSING,
    WORKER_BINDING_DENIED,
    EVENT_NOT_ALLOWED,
    PROJECT_NOT_ALLOWED,
    EVENT_DISABLED,
    PROJECT_EVENT_UNSUPPORTED,
    PROJECT_REQUIRED
}
