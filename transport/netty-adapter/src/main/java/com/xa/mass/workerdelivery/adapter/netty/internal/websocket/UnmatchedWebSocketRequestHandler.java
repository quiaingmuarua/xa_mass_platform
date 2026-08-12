package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;

final class UnmatchedWebSocketRequestHandler
        extends SimpleChannelInboundHandler<FullHttpRequest> {

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            FullHttpRequest request
    ) {
        var response = new DefaultFullHttpResponse(
                request.protocolVersion(),
                NOT_FOUND
        );
        response.headers().setInt(CONTENT_LENGTH, 0);
        context.writeAndFlush(response)
                .addListener(ChannelFutureListener.CLOSE);
    }
}
