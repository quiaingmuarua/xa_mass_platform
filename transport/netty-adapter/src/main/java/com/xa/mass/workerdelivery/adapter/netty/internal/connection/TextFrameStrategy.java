package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

public interface TextFrameStrategy {

    ChannelFuture writeText(Channel channel, String encodedText);

    void close(Channel channel, ConnectionCloseReason reason);
}
