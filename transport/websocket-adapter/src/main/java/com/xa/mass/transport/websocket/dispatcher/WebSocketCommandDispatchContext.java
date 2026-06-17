package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.WorkerEndpointRegistry;

import java.util.Objects;

/**
 * WebSocket assigned-delivery command context.
 *
 * <p>This context is intentionally narrower than the inbound/raw dispatcher
 * context. It contains only the facts needed for
 * {@code DeliveryCommand -> selected-worker send}.
 */
public final class WebSocketCommandDispatchContext {
    private final String adapterId;
    private final WorkerEndpointRegistry endpointRegistry;

    public WebSocketCommandDispatchContext(String adapterId,
                                           WorkerEndpointRegistry endpointRegistry) {
        this.adapterId = requireAdapterId(adapterId);
        this.endpointRegistry = endpointRegistry;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    private static String requireAdapterId(String adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim();
    }
}
