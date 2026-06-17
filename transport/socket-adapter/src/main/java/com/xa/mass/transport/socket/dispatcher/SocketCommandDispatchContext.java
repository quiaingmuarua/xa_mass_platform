package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;

import java.util.Objects;

/**
 * Socket assigned-delivery command context.
 */
public final class SocketCommandDispatchContext {
    private final String adapterId;
    private final WorkerEndpointRegistry endpointRegistry;
    private final SocketTransportFrameCodec frameCodec;

    public SocketCommandDispatchContext(String adapterId,
                                        WorkerEndpointRegistry endpointRegistry,
                                        SocketTransportFrameCodec frameCodec) {
        this.adapterId = requireAdapterId(adapterId);
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
    }

    public String getAdapterId() {
        return adapterId;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public SocketTransportFrameCodec getFrameCodec() {
        return frameCodec;
    }

    private static String requireAdapterId(String adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim();
    }
}
