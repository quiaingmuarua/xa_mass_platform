package com.xa.mass.sdk.event;

import com.xa.mass.sdk.auth.PrincipalContext;

/**
 * SDK-facing runtime event handler contract.
 *
 * <p>Use this when an embedding runtime wants an event to return data
 * directly instead of being mapped into task creation.
 */
@FunctionalInterface
public interface EventHandler {

    EventResponse handle(EventRequest request, PrincipalContext principal) throws Exception;
}
