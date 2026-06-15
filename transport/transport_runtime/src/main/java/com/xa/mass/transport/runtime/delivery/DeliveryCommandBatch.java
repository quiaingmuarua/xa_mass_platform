package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;

import java.util.List;

/**
 * Process-boundary batch for commands sharing one physical delivery lane.
 */
public record DeliveryCommandBatch(String deliveryBucketId,
                                   String deliveryLaneKey,
                                   String targetTransportNodeId,
                                   List<DeliveryCommand> items) {

    public DeliveryCommandBatch {
        deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        deliveryLaneKey = requireText(deliveryLaneKey, "deliveryLaneKey");
        targetTransportNodeId = requireText(targetTransportNodeId, "targetTransportNodeId");
        items = items == null ? List.of() : List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
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
