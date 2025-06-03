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
        // 清理客户端 Session
    }
}