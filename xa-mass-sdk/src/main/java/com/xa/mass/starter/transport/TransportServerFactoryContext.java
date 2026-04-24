package com.xa.mass.starter.transport;

import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapter context used when the embedded runtime asks a transport factory to
 * create its inbound server.
 */
public final class TransportServerFactoryContext {

    private final WorkerEndpointRegistry endpointRegistry;
    private final MessageCodec messageCodec;
    private final Consumer<String> inboundMessageSink;
    private final int port;
    private final String endpointPath;

    public TransportServerFactoryContext(WorkerEndpointRegistry endpointRegistry,
                                         MessageCodec messageCodec,
                                         Consumer<String> inboundMessageSink,
                                         int port,
                                         String endpointPath) {
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.inboundMessageSink = Objects.requireNonNull(inboundMessageSink, "inboundMessageSink");
        this.port = port;
        this.endpointPath = Objects.requireNonNull(endpointPath, "endpointPath");
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public MessageCodec getMessageCodec() {
        return messageCodec;
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
