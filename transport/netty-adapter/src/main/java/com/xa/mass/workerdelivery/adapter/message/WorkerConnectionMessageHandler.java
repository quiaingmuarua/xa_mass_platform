package com.xa.mass.workerdelivery.adapter.message;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;

public interface WorkerConnectionMessageHandler<
        M extends WorkerConnectionMessage> {

    WorkerConnectionMessageType messageType();

    Class<M> messageClass();

    WorkerMessageHandlingResult handle(
            String workerId,
            M message
    );
}
