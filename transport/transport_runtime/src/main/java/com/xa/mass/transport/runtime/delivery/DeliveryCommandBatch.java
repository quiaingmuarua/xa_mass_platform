package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;

import java.util.List;

/**
 * Process-boundary batch for commands sharing one physical delivery lane.
 */
public record DeliveryCommandBatch(String deliveryQueueKey,
                                   String targetTransportNodeId,
                                   List<DeliveryCommand> commands) {

    public DeliveryCommandBatch {
        deliveryQueueKey = requireText(deliveryQueueKey, "deliveryQueueKey");
        targetTransportNodeId = requireText(targetTransportNodeId, "targetTransportNodeId");
        commands = commands == null ? List.of() : List.copyOf(commands);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        for (DeliveryCommand command : commands) {
            if (command == null) {
                throw new IllegalArgumentException("commands must not contain null");
            }
            if (!deliveryQueueKey.equals(command.getDeliveryQueueKey())) {
                throw new IllegalArgumentException("command deliveryQueueKey must match batch deliveryQueueKey");
            }
            if (!targetTransportNodeId.equals(command.getTargetTransportNodeId())) {
                throw new IllegalArgumentException("command targetTransportNodeId must match batch targetTransportNodeId");
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
