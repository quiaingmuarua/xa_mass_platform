package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.AdapterDispatchRequest;

/**
 * Adapter-owned synchronous send operation used by the runtime delivery service.
 */
@FunctionalInterface
public interface TransportDeliverySender {

    boolean send(AdapterDispatchRequest request);
}
