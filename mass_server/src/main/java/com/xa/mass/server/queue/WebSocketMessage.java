package com.xa.mass.server.queue;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

public class WebSocketMessage {
    private final String message;
    private final ChannelHandlerContext ctx;
    private final TextWebSocketFrame frame;

    public WebSocketMessage(String message, ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        this.message = message;
        this.ctx = ctx;
        this.frame = frame;
    }

    public String getMessage() {
        return message;
    }

    public ChannelHandlerContext getCtx() {
        return ctx;
    }

    public TextWebSocketFrame getFrame() {
        return frame;
    }
} 