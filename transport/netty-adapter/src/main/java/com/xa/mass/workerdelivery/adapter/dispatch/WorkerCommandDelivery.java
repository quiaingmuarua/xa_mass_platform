package com.xa.mass.workerdelivery.adapter.dispatch;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;

public interface WorkerCommandDelivery {

    CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommand command
    );

    enum CommandDeliveryAttempt {
        STARTED,
        RETRY_LATER,
        UNKNOWN
    }
}
