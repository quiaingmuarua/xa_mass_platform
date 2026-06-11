package com.xa.mass.transport.runtime.delivery;

import java.util.Objects;

final class DeliveryQueueKey implements Comparable<DeliveryQueueKey> {

    private final String deliveryQueueKey;
    private final String selectedWorkerId;

    DeliveryQueueKey(String deliveryQueueKey, String selectedWorkerId) {
        this.deliveryQueueKey = Objects.requireNonNull(deliveryQueueKey, "deliveryQueueKey");
        this.selectedWorkerId = Objects.requireNonNull(selectedWorkerId, "selectedWorkerId");
    }

    String deliveryQueueKey() {
        return deliveryQueueKey;
    }

    String selectedWorkerId() {
        return selectedWorkerId;
    }

    @Override
    public int compareTo(DeliveryQueueKey other) {
        int queueComparison = deliveryQueueKey.compareTo(other.deliveryQueueKey);
        if (queueComparison != 0) {
            return queueComparison;
        }
        return selectedWorkerId.compareTo(other.selectedWorkerId);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof DeliveryQueueKey other)) {
            return false;
        }
        return deliveryQueueKey.equals(other.deliveryQueueKey)
                && selectedWorkerId.equals(other.selectedWorkerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deliveryQueueKey, selectedWorkerId);
    }
}
