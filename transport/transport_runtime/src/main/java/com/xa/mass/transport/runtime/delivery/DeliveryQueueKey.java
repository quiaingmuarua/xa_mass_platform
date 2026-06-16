package com.xa.mass.transport.runtime.delivery;

import java.util.Objects;

final class DeliveryQueueKey implements Comparable<DeliveryQueueKey> {

    private final String deliveryQueueKey;

    DeliveryQueueKey(String deliveryQueueKey) {
        this.deliveryQueueKey = Objects.requireNonNull(deliveryQueueKey, "deliveryQueueKey");
    }

    String deliveryQueueKey() {
        return deliveryQueueKey;
    }

    @Override
    public int compareTo(DeliveryQueueKey other) {
        return deliveryQueueKey.compareTo(other.deliveryQueueKey);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof DeliveryQueueKey other)) {
            return false;
        }
        return deliveryQueueKey.equals(other.deliveryQueueKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deliveryQueueKey);
    }
}
