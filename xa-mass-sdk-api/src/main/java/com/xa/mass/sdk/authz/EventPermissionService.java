package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.event.EventRequest;

/**
 * Event-level authorization entry point.
 */
public interface EventPermissionService {

    AuthorizationDecision authorize(PrincipalContext principal, EventRequest request);
}
