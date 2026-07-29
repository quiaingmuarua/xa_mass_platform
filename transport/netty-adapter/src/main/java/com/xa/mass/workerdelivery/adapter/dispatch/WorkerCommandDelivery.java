package com.xa.mass.workerdelivery.adapter.dispatch;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;

public interface WorkerCommandDelivery {

    CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommandEnvelope command
    );

    enum CommandDeliveryAttempt {
        STARTED,
        RETRY_LATER,
        UNKNOWN
    }
}
