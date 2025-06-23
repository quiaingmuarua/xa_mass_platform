package com.xa.mass.gateway.server;

import io.netty.channel.ChannelHandlerContext;

public interface IncomingMessageProcessor {
    void process(String rawJson, ChannelHandlerContext ctx);
} 