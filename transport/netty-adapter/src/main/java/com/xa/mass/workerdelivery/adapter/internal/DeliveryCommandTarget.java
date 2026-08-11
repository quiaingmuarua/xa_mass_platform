package com.xa.mass.workerdelivery.adapter.internal;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;

interface DeliveryCommandTarget {

    DeliveryAttempt deliver(String workerId, DeliveryCommand command);

    enum DeliveryAttempt {
        STARTED,
        RETRY_LATER,
        UNKNOWN
    }
}
