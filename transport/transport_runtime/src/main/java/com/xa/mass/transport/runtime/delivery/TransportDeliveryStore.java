package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runtime-owned storage boundary for transport delivery handoff.
 */
public interface TransportDeliveryStore {

    DispatchOutcome enqueue(TransportDispatchEnvelope envelope, int maxItemsPerRoute);

    List<TransportDispatchEnvelope> drain(String adapterId, String routeKey, int maxItems);

    List<TransportDispatchEnvelope> poll(String adapterId,
                                         String routeKey,
                                         int maxItems,
                                         long timeout,
                                         TimeUnit unit) throws InterruptedException;

    TransportDeliveryStoreStats stats();

    void shutdown();
}
