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
    private final Consumer<String> inboundMessageSink;
    private final int port;
    private final String endpointPath;
    private final WebSocketTransportFrameCodec deprecatedFrameCodec;

    public TransportServerFactoryContext(WorkerEndpointRegistry endpointRegistry,
                                         Consumer<String> inboundMessageSink,
                                         int port,
                                         String endpointPath) {
        this(endpointRegistry, inboundMessageSink, port, endpointPath, null);
    }

    public TransportServerFactoryContext(WorkerEndpointRegistry endpointRegistry,
                                         WebSocketTransportFrameCodec frameCodec,
                                         Consumer<String> inboundMessageSink,
                                         int port,
                                         String endpointPath) {
        this(endpointRegistry, inboundMessageSink, port, endpointPath, frameCodec);
    }

    private TransportServerFactoryContext(WorkerEndpointRegistry endpointRegistry,
                                          Consumer<String> inboundMessageSink,
                                          int port,
                                          String endpointPath,
                                          WebSocketTransportFrameCodec deprecatedFrameCodec) {
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.inboundMessageSink = Objects.requireNonNull(inboundMessageSink, "inboundMessageSink");
        this.port = port;
        this.endpointPath = Objects.requireNonNull(endpointPath, "endpointPath");
        this.deprecatedFrameCodec = deprecatedFrameCodec;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    /**
     * @deprecated Prefer {@link #acceptInboundRawMessage(String)} plus endpoint/path metadata. The WebSocket codec is
     * adapter-local compatibility only and is no longer the mainline transport-server factory dependency.
     */
    @Deprecated(forRemoval = false)
    public WebSocketTransportFrameCodec getFrameCodec() {
        return deprecatedFrameCodec;
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
