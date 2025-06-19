package com.xa.mass.core.getway.server;

import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.getway.queue.Envelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

public class DispatcherInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final DispatcherContext dispatcherContext;

    public DispatcherInboundHandler(DispatcherContext dispatcherContext) {
        this.dispatcherContext = dispatcherContext;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String raw = msgFrame.text();
        Envelope envelope = Envelope.builder().rawJson(raw).build();
        // 可根据需要补充 deviceId/connRole 等
        dispatcherContext.getInputQueue().offer(envelope);
    }
} 