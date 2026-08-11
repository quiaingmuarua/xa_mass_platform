package com.xa.mass.workerdelivery.adapter.websocket;

import com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;

interface WorkerConnectionRegistry extends WorkerCommandDelivery {

    void activate(
            String workerId,
            Channel channel
    );

    void deactivate(
            String workerId,
            Channel channel
    );

    @Override
    CommandDeliveryAttempt deliver(
            String workerId,
            DeliveryCommand command
    );

    void close(
            String workerId,
            Channel channel,
            ConnectionCloseReason reason
    );

    void closeAll(ConnectionCloseReason reason);

    enum ConnectionCloseReason {
        REPLACED,
        RESULT_BUFFER_FULL,
        TRANSPORT_ERROR,
        ADAPTER_STOPPING
    }
}
