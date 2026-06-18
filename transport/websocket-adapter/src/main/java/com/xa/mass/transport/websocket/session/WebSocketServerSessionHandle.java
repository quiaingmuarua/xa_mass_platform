package com.xa.mass.transport.websocket.session;

import io.netty.channel.Channel;

public interface WebSocketServerSessionHandle {

    void addSession(String deliveryBucketId,
                    String endpointAddress,
                    String workerId,
                    Channel channel);

    void removeSession(Channel channel);

    String currentWorkerId(Channel channel);
}
