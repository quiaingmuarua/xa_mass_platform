package com.xa.mass.sdk.authz;

/**
 * Host-neutral authorization policy seam used by server, SDK, and future hosts.
 */
public interface AuthorizationPolicy {

    AuthorizationDecision authorize(AuthorizationRequest request);
}
