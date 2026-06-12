package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Producer/consumer handoff for already assigned delivery commands.
 */
public interface TransportDeliveryCommandHandoff {

    List<DispatchOutcome> offer(DeliveryCommandBatch batch);

    DeliveryCommandBatch poll(long timeoutMillis) throws InterruptedException;

    void shutdown();
}
