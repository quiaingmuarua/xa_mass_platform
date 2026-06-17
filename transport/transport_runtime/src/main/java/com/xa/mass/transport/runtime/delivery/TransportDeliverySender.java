package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;

/**
 * Adapter-owned synchronous send operation used by the runtime delivery service.
 */
@FunctionalInterface
public interface TransportDeliverySender {

    boolean send(DeliveryCommand command);
}
