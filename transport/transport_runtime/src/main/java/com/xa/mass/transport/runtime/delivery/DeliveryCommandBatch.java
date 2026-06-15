package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;

import java.util.List;

/**
 * Local-consumer batch for commands claimed from one assigned-delivery queue.
 */
public record DeliveryCommandBatch(String deliveryQueueKey,
                                   List<DeliveryCommandReference> references,
                                   List<DeliveryCommand> items) {

    public DeliveryCommandBatch(String deliveryQueueKey,
                                List<DeliveryCommand> items) {
        this(deliveryQueueKey, List.of(), items);
    }

    public DeliveryCommandBatch {
        deliveryQueueKey = requireText(deliveryQueueKey, "deliveryQueueKey");
        references = references == null ? List.of() : List.copyOf(references);
        items = items == null ? List.of() : List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        for (DeliveryCommandReference reference : references) {
            if (reference == null) {
                throw new IllegalArgumentException("references must not contain null");
            }
        }
        for (DeliveryCommand item : items) {
            if (item == null) {
                throw new IllegalArgumentException("items must not contain null");
            }
        }
    }

    public List<DeliveryCommand> commands() {
        return items;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
