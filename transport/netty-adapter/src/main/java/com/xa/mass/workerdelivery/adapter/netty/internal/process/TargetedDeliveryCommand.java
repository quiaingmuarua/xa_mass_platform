package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;

/** Adapter-local tuple; target identity remains outside the wire DTO. */
record TargetedDeliveryCommand(
        String workerId,
        DeliveryCommand command
) {
}
