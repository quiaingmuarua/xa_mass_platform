package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.TransportDeliveryAddressing;

import java.util.List;

/**
 * Process-boundary batch for commands sharing one physical delivery lane.
 */
public record DeliveryCommandBatch(String adapterId,
                                   String deliveryQueueKey,
                                   String targetTransportNodeId,
                                   List<ResolvedDeliveryItem> items) {

    public DeliveryCommandBatch {
        adapterId = requireAdapterId(adapterId);
        deliveryQueueKey = requireText(deliveryQueueKey, "deliveryQueueKey");
        targetTransportNodeId = requireText(targetTransportNodeId, "targetTransportNodeId");
        items = items == null ? List.of() : List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        for (ResolvedDeliveryItem item : items) {
            if (item == null) {
                throw new IllegalArgumentException("items must not contain null");
            }
            if (!targetTransportNodeId.equals(item.endpoint().transportNodeId())) {
                throw new IllegalArgumentException("item endpoint transportNodeId must match batch targetTransportNodeId");
            }
        }
    }

    public List<DeliveryCommand> commands() {
        return items.stream()
                .map(ResolvedDeliveryItem::command)
                .toList();
    }

    private static String requireAdapterId(String value) {
        String normalized = TransportDeliveryAddressing.normalizeAdapterId(value);
        if (normalized == null) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
