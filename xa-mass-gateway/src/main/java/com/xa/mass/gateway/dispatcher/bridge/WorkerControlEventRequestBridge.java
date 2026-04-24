package com.xa.mass.gateway.dispatcher.bridge;

import com.xa.mass.gateway.dispatcher.event.EventEnvelope;
import com.xa.mass.gateway.dispatcher.event.EventGatewayBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;

import java.util.Objects;

/**
 * Compatibility bridge from worker-control transport frames into the global
 * event runtime.
 */
public class WorkerControlEventRequestBridge implements ControlEventRequestFrameBridge {

    private final EventGatewayBridge bridge;

    public WorkerControlEventRequestBridge(EventGatewayBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public EventResponse handleControlEventRequest(EventRequest request, EventPrincipal principal) {
        EventEnvelope envelope = EventEnvelope.builder()
                .event(request.getEvent() != null ? request.getEvent().value() : null)
                .project(request.getProject())
                .requestId(request.getRequestId())
                .headers(request.getHeaders())
                .payload(request.getPayload())
                .principal(principal)
                .build();
        return bridge.handle(envelope);
    }
}
