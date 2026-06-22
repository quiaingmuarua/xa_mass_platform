package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Producer/consumer handoff for already assigned delivery commands.
 */
public interface TransportDeliveryCommandHandoff {

    List<DispatchOutcome> offer(AdapterMailboxDeliveryOffer offer);

    DeliveryCommandBatch poll(String adapterMailboxKey, long timeoutMillis) throws InterruptedException;

    default void complete(DeliveryCommandBatch batch, List<DispatchOutcome> outcomes) {
    }

    void shutdown();
}
