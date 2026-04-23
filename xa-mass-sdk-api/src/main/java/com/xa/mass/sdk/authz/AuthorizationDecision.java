package com.xa.mass.sdk.authz;

import java.util.Objects;

/**
 * Immutable authorization result for an event request.
 */
public final class AuthorizationDecision {

    private static final AuthorizationDecision ALLOWED = new AuthorizationDecision(true, "allowed");

    private final boolean allowed;
    private final String reason;

    private AuthorizationDecision(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason == null ? "" : reason;
    }

    public static AuthorizationDecision allow() {
        return ALLOWED;
    }

    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, Objects.requireNonNullElse(reason, ""));
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }
}
