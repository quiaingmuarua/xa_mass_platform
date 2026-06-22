package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Producer/consumer handoff for already assigned dispatch items.
 */
public interface TransportDispatchHandoff {

    List<DispatchOutcome> offer(DispatchRoutingBatch batch);

    ClaimedDispatchRoutingBatch poll(String adapterMailboxKey, long timeoutMillis) throws InterruptedException;

    default void complete(ClaimedDispatchRoutingBatch batch, List<DispatchOutcome> outcomes) {
    }

    void shutdown();
}
