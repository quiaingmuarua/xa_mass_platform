package com.xa.mass.transport.runtime.delivery;

import java.util.List;

/**
 * Producer/queue dispatch carrier for one adapter mailbox.
 */
public record AdapterMailboxDispatchBatch(String adapterMailboxKey,
                                          List<DispatchMessage> items) {

    public AdapterMailboxDispatchBatch {
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        if (items != null) {
            for (DispatchMessage item : items) {
                if (item == null) {
                    throw new IllegalArgumentException("items must not contain null");
                }
            }
        }
        items = items == null ? List.of() : List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
