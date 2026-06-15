package com.xa.mass.transport.runtime.delivery;

/**
 * No-op selected-worker consumer registry for runtimes without command handoff.
 */
public enum NoopDeliveryCommandConsumerRegistry implements DeliveryCommandConsumerRegistry {
    INSTANCE;

    @Override
    public void claimConsumer(DeliveryCommandConsumerClaim claim) {
        // No command handoff is present in this runtime role.
    }

    @Override
    public void releaseConsumer(DeliveryCommandConsumerClaim claim) {
        // No command handoff is present in this runtime role.
    }
}
