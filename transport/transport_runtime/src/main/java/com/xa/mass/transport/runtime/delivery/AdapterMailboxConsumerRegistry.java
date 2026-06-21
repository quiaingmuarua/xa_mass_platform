package com.xa.mass.transport.runtime.delivery;

/**
 * Transport-internal mailbox consumer registry used by distributed assigned
 * delivery handoff.
 */
public interface AdapterMailboxConsumerRegistry {

    void claimMailboxConsumer(AdapterMailboxConsumerLease lease);

    void releaseMailboxConsumer(AdapterMailboxConsumerLease lease);
}
