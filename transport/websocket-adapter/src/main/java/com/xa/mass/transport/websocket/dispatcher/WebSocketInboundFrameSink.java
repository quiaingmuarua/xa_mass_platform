package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface WebSocketInboundFrameSink {

    void accept(JsonObject frame);
}
