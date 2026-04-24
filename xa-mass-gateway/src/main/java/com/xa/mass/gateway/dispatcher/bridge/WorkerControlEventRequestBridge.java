package com.xa.mass.gateway.dispatcher.bridge;

import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Compatibility bridge from worker-control transport frames into the global
 * event runtime.
 */
public class WorkerControlEventRequestBridge implements ControlEventRequestFrameBridge {

    private final BiFunction<EventRequest, EventPrincipal, EventResponse> dispatcher;

    public WorkerControlEventRequestBridge(BiFunction<EventRequest, EventPrincipal, EventResponse> dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public EventResponse handleControlEventRequest(EventRequest request, EventPrincipal principal) {
        return dispatcher.apply(request, principal);
    }
}
