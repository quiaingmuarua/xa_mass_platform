package com.xa.mass.sdk.authz;

import java.util.Objects;

/**
 * Immutable authorization result for an event request.
 */
public final class AuthorizationDecision {

    private static final AuthorizationDecision ALLOWED =
            new AuthorizationDecision(true, AuthorizationReasonCode.ALLOWED, "allowed");

    private final boolean allowed;
    private final AuthorizationReasonCode reasonCode;
    private final String reason;

    private AuthorizationDecision(boolean allowed,
                                  AuthorizationReasonCode reasonCode,
                                  String reason) {
        this.allowed = allowed;
        this.reasonCode = reasonCode == null ? AuthorizationReasonCode.ALLOWED : reasonCode;
        this.reason = reason == null ? "" : reason;
    }

    public static AuthorizationDecision allow() {
        return ALLOWED;
    }

    public static AuthorizationDecision deny(String reason) {
        return deny(AuthorizationReasonCode.PERMISSION_DENIED, reason);
    }

    public static AuthorizationDecision deny(AuthorizationReasonCode reasonCode, String reason) {
        return new AuthorizationDecision(false, reasonCode, Objects.requireNonNullElse(reason, ""));
    }

    public boolean isAllowed() {
        return allowed;
    }

    public AuthorizationReasonCode getReasonCode() {
        return reasonCode;
    }

    public String getReason() {
        return reason;
    }
}
