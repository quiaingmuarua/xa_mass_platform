package com.xa.mass.transport.websocket.session;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

public interface WebSocketServerSessionHandle {

    void addSession(String deliveryBucketId,
                    String endpointAddress,
                    String workerId,
                    Channel channel,
                    ChannelHandlerContext context);

    void removeSession(Channel channel);

    WebSocketServerSession currentSession(Channel channel);
}
