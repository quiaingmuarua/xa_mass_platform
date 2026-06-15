package com.xa.mass.transport.route;

/**
 * Narrow producer-side locality hint for an already selected worker.
 */
public record SelectedWorkerDeliveryTarget(String deliveryBucketId,
                                           String selectedWorkerId,
                                           String targetTransportNodeId) {

    public SelectedWorkerDeliveryTarget {
        deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        targetTransportNodeId = requireText(targetTransportNodeId, "targetTransportNodeId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
