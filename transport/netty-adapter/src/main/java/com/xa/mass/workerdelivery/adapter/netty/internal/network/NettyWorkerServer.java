package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

/**
 * Complete physical Netty server owned by one Worker Delivery Adapter.
 *
 * <p>The server owns listener resources, child Channels, physical framing,
 * writes, and protocol-specific close behavior. The supplied shared handler
 * owns the connection mechanism above this physical boundary.
 */
public interface NettyWorkerServer extends AutoCloseable {

    void start(ChannelHandler sharedConnectionHandler);

    TextWriteAttempt writeText(Channel channel, String message);

    void writeTextAndClose(
            Channel channel,
            String message,
            AdapterConnectionCloseReason reason
    );

    void closeConnection(
            Channel channel,
            AdapterConnectionCloseReason reason
    );

    @Override
    void close();
}
