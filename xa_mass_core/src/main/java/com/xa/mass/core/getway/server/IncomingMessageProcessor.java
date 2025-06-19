package com.xa.mass.core.getway.server;

import io.netty.channel.ChannelHandlerContext;

public interface IncomingMessageProcessor {
    void process(String rawJson, ChannelHandlerContext ctx);
} 