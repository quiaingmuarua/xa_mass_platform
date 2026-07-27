package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;

public interface WorkerConnection {

    CommandDeliveryAttempt deliver(WorkerCommandEnvelope command);

    void close(WorkerConnectionCloseReason reason);

    enum CommandDeliveryAttempt {
        DELIVERED,
        REJECTED_BEFORE_SEND,
        UNKNOWN
    }

    enum WorkerConnectionCloseReason {
        REPLACED,
        RESULT_BUFFER_FULL,
        TRANSPORT_ERROR,
        ADAPTER_STOPPING
    }
}
