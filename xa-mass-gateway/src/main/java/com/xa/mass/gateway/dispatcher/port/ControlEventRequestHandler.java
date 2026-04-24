package com.xa.mass.gateway.dispatcher.port;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;

/**
 * Explicit adapter port for inbound event-first control requests.
 */
@FunctionalInterface
public interface ControlEventRequestHandler {

    EventResponse handleControlEventRequest(EventRequest request, EventPrincipal principal);
}
