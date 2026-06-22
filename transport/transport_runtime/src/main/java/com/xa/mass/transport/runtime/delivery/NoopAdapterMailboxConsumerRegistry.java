package com.xa.mass.transport.runtime.delivery;

/**
 * No-op mailbox consumer registry for runtimes without command handoff.
 */
public enum NoopAdapterMailboxConsumerRegistry implements AdapterMailboxConsumerRegistry {
    INSTANCE;

    @Override
    public void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
        // No command handoff is present in this runtime role.
    }

    @Override
    public void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
        // No command handoff is present in this runtime role.
    }
}
