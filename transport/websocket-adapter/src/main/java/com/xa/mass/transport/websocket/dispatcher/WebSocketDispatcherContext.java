package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.channel.TransportResultIngressChannel;

import java.util.Objects;

/**
 * WebSocket adapter-owned dispatch runtime context.
 */
public final class WebSocketDispatcherContext {
    private final String adapterId;
    private final RawWorkerRouteEndpointRegistry rawRouteEndpointRegistry;
    private final WebSocketTransportFrameCodec frameCodec;
    private final TransportResultIngressChannel resultIngressChannel;

    public WebSocketDispatcherContext(String adapterId,
                                      RawWorkerRouteEndpointRegistry rawRouteEndpointRegistry,
                                      WebSocketTransportFrameCodec frameCodec,
                                      TransportResultIngressChannel resultIngressChannel) {
        this.adapterId = requireAdapterId(adapterId);
        this.rawRouteEndpointRegistry = rawRouteEndpointRegistry;
        this.frameCodec = frameCodec;
        this.resultIngressChannel = resultIngressChannel;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public RawWorkerRouteEndpointRegistry getRawRouteEndpointRegistry() {
        return rawRouteEndpointRegistry;
    }

    public WebSocketTransportFrameCodec getFrameCodec() {
        return frameCodec;
    }

    public TransportResultIngressChannel getResultIngressChannel() {
        return resultIngressChannel;
    }

    private static String requireAdapterId(String adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim();
    }
}
