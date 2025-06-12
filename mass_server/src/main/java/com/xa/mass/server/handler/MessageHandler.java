package com.xa.mass.server.handler;

import com.google.gson.JsonObject;
import io.netty.channel.Channel;

@FunctionalInterface
public interface MessageHandler {
    void handle(JsonObject data, Channel channel);
}