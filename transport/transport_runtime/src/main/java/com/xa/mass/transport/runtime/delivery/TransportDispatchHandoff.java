package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Producer/consumer handoff for already assigned dispatch items.
 */
public interface TransportDispatchHandoff {

    List<DispatchOutcome> offer(DispatchRoutingBatch batch);

    List<DispatchRoutingItem> poll(String adapterMailboxKey,
                                   int maxItems,
                                   long timeoutMillis) throws InterruptedException;

    void shutdown();
}
