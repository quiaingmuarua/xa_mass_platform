package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;

import java.util.Objects;

/**
 * Producer-side pairing of an opaque adapter mailbox target with one assigned
 * delivery command.
 */
public record AdapterMailboxDeliveryCommand(String adapterMailboxKey,
                                            DeliveryCommand command) {

    public AdapterMailboxDeliveryCommand {
        adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        command = Objects.requireNonNull(command, "command");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
