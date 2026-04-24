package com.xa.mass.starter.transport;

import com.xa.mass.gateway.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapter context used when the embedded runtime asks a transport factory to
 * create its inbound server.
 */
public final class TransportServerFactoryContext {

    private final WorkerEndpointRegistry endpointRegistry;
    private final WebSocketTransportFrameCodec frameCodec;
    private final Consumer<String> inboundMessageSink;
    private final int port;
    private final String endpointPath;

    public TransportServerFactoryContext(WorkerEndpointRegistry endpointRegistry,
                                         WebSocketTransportFrameCodec frameCodec,
                                         Consumer<String> inboundMessageSink,
                                         int port,
                                         String endpointPath) {
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.inboundMessageSink = Objects.requireNonNull(inboundMessageSink, "inboundMessageSink");
        this.port = port;
        this.endpointPath = Objects.requireNonNull(endpointPath, "endpointPath");
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public WebSocketTransportFrameCodec getFrameCodec() {
        return frameCodec;
    }

    public int getPort() {
        return port;
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public void acceptInboundRawMessage(String rawJson) {
        inboundMessageSink.accept(rawJson);
    }
}
