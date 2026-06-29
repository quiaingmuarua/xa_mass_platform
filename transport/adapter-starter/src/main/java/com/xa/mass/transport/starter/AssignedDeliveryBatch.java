package com.xa.mass.transport.starter;

import java.util.List;
import java.util.Objects;

/**
 * Stable embedded assigned-delivery batch keyed by adapter mailbox.
 */
public record AssignedDeliveryBatch(String adapterMailboxKey,
                                    List<AssignedDeliveryMessage> messages) {

    public AssignedDeliveryBatch {
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
