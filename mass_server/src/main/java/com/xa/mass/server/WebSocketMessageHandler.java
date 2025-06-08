package com.xa.mass.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

public class WebSocketMessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        String response = msg.text();
        TaskResultHandler.onClientResponse(response); // 交给结果处理器
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