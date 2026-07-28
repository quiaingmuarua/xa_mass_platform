package com.xa.mass.workerdelivery.adapter.websocket;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import io.netty.channel.Channel;

interface WorkerConnectionRegistry {

    void bind(
            String workerId,
            Channel channel
    );

    void unbind(
            String workerId,
            Channel channel
    );

    CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommandEnvelope command
    );

    void close(
            String workerId,
            Channel channel,
            ConnectionCloseReason reason
    );

    void closeAll(ConnectionCloseReason reason);

    enum CommandDeliveryAttempt {
        DELIVERED,
        REJECTED_BEFORE_SEND,
        UNKNOWN
    }

    enum ConnectionCloseReason {
        REPLACED,
        RESULT_BUFFER_FULL,
        TRANSPORT_ERROR,
        ADAPTER_STOPPING
    }
}
