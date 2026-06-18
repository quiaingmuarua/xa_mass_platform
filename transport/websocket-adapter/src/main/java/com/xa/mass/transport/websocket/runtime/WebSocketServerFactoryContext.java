package com.xa.mass.transport.websocket.runtime;

import com.xa.mass.transport.websocket.session.WebSocketServerSessionHandle;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * WebSocket adapter context used when embedded runtime asks a custom server
 * factory to create its inbound server.
 */
public final class WebSocketServerFactoryContext {

    private final WebSocketServerSessionHandle sessionHandle;
    private final Consumer<String> inboundMessageSink;
    private final int port;
    private final String endpointPath;

    public WebSocketServerFactoryContext(WebSocketServerSessionHandle sessionHandle,
                                         Consumer<String> inboundMessageSink,
                                         int port,
                                         String endpointPath) {
        this.sessionHandle = Objects.requireNonNull(sessionHandle, "sessionHandle");
        this.inboundMessageSink = Objects.requireNonNull(inboundMessageSink, "inboundMessageSink");
        this.port = port;
        this.endpointPath = Objects.requireNonNull(endpointPath, "endpointPath");
    }

    public WebSocketServerSessionHandle getSessionHandle() {
        return sessionHandle;
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
