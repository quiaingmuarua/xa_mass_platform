package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;

/**
 * Event-level authorization entry point.
 */
public interface EventPermissionService {

    AuthorizationDecision authorize(EventPrincipal principal, EventRequest request);
}
