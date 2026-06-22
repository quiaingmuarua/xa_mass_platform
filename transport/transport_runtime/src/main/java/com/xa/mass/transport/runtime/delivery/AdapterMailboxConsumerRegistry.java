package com.xa.mass.transport.runtime.delivery;

/**
 * Transport-internal mailbox consumer registry used by distributed assigned
 * delivery handoff.
 */
public interface AdapterMailboxConsumerRegistry {

    void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease);

    void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease);
}
