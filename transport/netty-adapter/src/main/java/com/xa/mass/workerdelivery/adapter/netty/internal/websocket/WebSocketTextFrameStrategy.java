package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

import com.xa.mass.workerdelivery.adapter.netty.internal.connection.ConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.TextFrameStrategy;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

public final class WebSocketTextFrameStrategy
        implements TextFrameStrategy {

    public static final WebSocketTextFrameStrategy INSTANCE =
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
                    closeCode(reason),
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

    private static int closeCode(ConnectionCloseReason reason) {
        return switch (reason) {
            case ADAPTER_STOPPING -> 1001;
            case BINARY_UNSUPPORTED -> 1003;
            case INVALID_REPORT -> 1007;
            case IDENTITY_REQUIRED,
                    VERIFICATION_IN_PROGRESS,
                    VERIFICATION_FAILED,
                    REPLACED -> 1008;
            case TRANSPORT_ERROR -> 1011;
            case RESULT_BUFFER_FULL -> 1013;
        };
    }
}
