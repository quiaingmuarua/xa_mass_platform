package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;

import java.util.List;

/**
 * Producer-side assigned-delivery command offer scoped by an opaque queue key.
 */
public record DeliveryQueueOffer(String deliveryQueueKey,
                                 List<DeliveryCommand> commands) {

    public DeliveryQueueOffer {
        deliveryQueueKey = requireText(deliveryQueueKey, "deliveryQueueKey");
        commands = commands == null ? List.of() : List.copyOf(commands);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        for (DeliveryCommand command : commands) {
            if (command == null) {
                throw new IllegalArgumentException("commands must not contain null");
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
