package com.xa.mass.workerdelivery.adapter.netty.internal.socket;

import com.xa.mass.workerdelivery.adapter.netty.internal.connection.ConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.TextFrameStrategy;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

public final class SocketLineFrameStrategy implements TextFrameStrategy {

    public static final SocketLineFrameStrategy INSTANCE =
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
