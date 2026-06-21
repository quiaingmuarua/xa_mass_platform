package com.xa.mass.transport.runtime.delivery;

/**
 * Transport-internal evidence that this runtime can consume one adapter
 * mailbox.
 */
public record AdapterMailboxConsumerLease(String adapterMailboxKey,
                                          String consumerId,
                                          long generation,
                                          long leaseDeadlineEpochMillis) {

    public AdapterMailboxConsumerLease {
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        consumerId = requireText(consumerId, "consumerId");
        generation = Math.max(0L, generation);
        leaseDeadlineEpochMillis = Math.max(0L, leaseDeadlineEpochMillis);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
