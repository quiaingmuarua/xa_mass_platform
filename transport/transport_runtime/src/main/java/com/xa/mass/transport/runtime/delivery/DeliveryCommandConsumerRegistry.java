package com.xa.mass.transport.runtime.delivery;

/**
 * Transport-internal selected-worker consumer registry used by distributed
 * assigned-delivery handoff.
 */
public interface DeliveryCommandConsumerRegistry {

    void claimConsumer(DeliveryCommandConsumerClaim claim);

    void releaseConsumer(DeliveryCommandConsumerClaim claim);
}
