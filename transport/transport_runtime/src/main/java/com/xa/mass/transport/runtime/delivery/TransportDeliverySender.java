package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDispatchEnvelope;

/**
 * Adapter-owned synchronous send operation used by the runtime delivery service.
 */
@FunctionalInterface
public interface TransportDeliverySender {

    boolean send(TransportDispatchEnvelope envelope);
}
