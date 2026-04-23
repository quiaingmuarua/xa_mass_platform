package com.xa.mass.gateway.dispatcher.event;

import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Gateway bridge from transport envelopes into the SDK event runtime.
 */
public class EventGatewayBridge {

    private final BiFunction<EventRequest, EventPrincipal, EventResponse> dispatcher;

    public EventGatewayBridge(BiFunction<EventRequest, EventPrincipal, EventResponse> dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public EventResponse handle(EventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        EventRequest request = EventRequest.builder()
                .event(envelope.getEvent())
                .project(envelope.getProject())
                .requestId(envelope.getRequestId())
                .headers(envelope.getHeaders())
                .payload(envelope.getPayload())
                .build();
        return dispatcher.apply(request, envelope.getPrincipal());
    }
}
