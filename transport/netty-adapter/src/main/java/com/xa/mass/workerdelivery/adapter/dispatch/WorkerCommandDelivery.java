package com.xa.mass.workerdelivery.adapter.dispatch;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;

public interface WorkerCommandDelivery {

    CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommandEnvelope command
    );

    enum CommandDeliveryAttempt {
        DELIVERED,
        REJECTED_BEFORE_SEND,
        UNKNOWN
    }
}
