package com.xa.mass.transport.websocket.dispatcher;

@FunctionalInterface
public interface WebSocketInboundMessageSink {

    void accept(WebSocketInboundMessage message);
}
