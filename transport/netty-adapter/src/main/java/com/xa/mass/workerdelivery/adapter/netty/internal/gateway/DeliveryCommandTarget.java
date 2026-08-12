package com.xa.mass.workerdelivery.adapter.netty.internal.gateway;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;

public interface DeliveryCommandTarget {

    DeliveryAttempt deliver(String workerId, DeliveryCommand command);

    enum DeliveryAttempt {
        STARTED,
        RETRY_LATER,
        UNKNOWN
    }
}
