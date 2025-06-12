package com.xa.mass.server.handler;

import com.xa.mass.server.TaskResultHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

public class WebSocketMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        String json = msg.text();
        MessageDispatcher.dispatch(json, ctx.channel());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        String clientId = ctx.channel().id().asShortText();
        System.out.println("Client disconnected: " + clientId);
        // TODO: remove client from session tracking if needed
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String clientId = ctx.channel().id().asShortText();
        System.out.println("Client connected: " + clientId);
        // TODO: register client if needed
    }
}