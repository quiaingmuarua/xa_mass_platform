package com.xa.mass.workerdelivery.adapter.dispatch;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;

public interface WorkerCommandDelivery {

    CommandDeliveryAttempt deliver(
            String workerId,
            DeliveryCommand command
    );

    enum CommandDeliveryAttempt {
        STARTED,
        RETRY_LATER,
        UNKNOWN
    }
}
