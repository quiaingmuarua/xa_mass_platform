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
    private final Consumer<String> inboundRawFrameSink;
    private final int port;
    private final String endpointPath;

    public WebSocketServerFactoryContext(WebSocketServerSessionHandle sessionHandle,
                                         Consumer<String> inboundRawFrameSink,
                                         int port,
                                         String endpointPath) {
        this.sessionHandle = Objects.requireNonNull(sessionHandle, "sessionHandle");
        this.inboundRawFrameSink = Objects.requireNonNull(inboundRawFrameSink, "inboundRawFrameSink");
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

    public void acceptInboundRawFrame(String rawJson) {
        inboundRawFrameSink.accept(rawJson);
    }
}
