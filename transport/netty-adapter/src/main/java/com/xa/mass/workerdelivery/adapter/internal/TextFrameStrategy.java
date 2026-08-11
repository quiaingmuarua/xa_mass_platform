package com.xa.mass.workerdelivery.adapter.internal;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

interface TextFrameStrategy {

    ChannelFuture writeText(Channel channel, String encodedText);

    void close(Channel channel, ConnectionCloseReason reason);
}

final class WebSocketTextFrameStrategy implements TextFrameStrategy {

    static final WebSocketTextFrameStrategy INSTANCE =
            new WebSocketTextFrameStrategy();

    private WebSocketTextFrameStrategy() {
    }

    @Override
    public ChannelFuture writeText(Channel channel, String encodedText) {
        return channel.writeAndFlush(new TextWebSocketFrame(encodedText));
    }

    @Override
    public void close(Channel channel, ConnectionCloseReason reason) {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.writeAndFlush(new CloseWebSocketFrame(
                    reason.webSocketCode(),
                    reason.message()
            )).addListener(ChannelFutureListener.CLOSE);
        } catch (RuntimeException ignored) {
            closeBestEffort(channel);
        }
    }

    private static void closeBestEffort(Channel channel) {
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Channel teardown is best effort.
        }
    }
}

final class SocketLineFrameStrategy implements TextFrameStrategy {

    static final SocketLineFrameStrategy INSTANCE =
            new SocketLineFrameStrategy();

    private SocketLineFrameStrategy() {
    }

    @Override
    public ChannelFuture writeText(Channel channel, String encodedText) {
        return channel.writeAndFlush(encodedText + "\n");
    }

    @Override
    public void close(Channel channel, ConnectionCloseReason reason) {
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Channel teardown is best effort.
        }
    }
}
