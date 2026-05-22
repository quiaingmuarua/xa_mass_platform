package com.xa.mass.transport.runtime.delivery;

import java.util.Objects;

final class DeliveryQueueKey implements Comparable<DeliveryQueueKey> {

    private final String adapterId;
    private final String routeKey;

    DeliveryQueueKey(String adapterId, String routeKey) {
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.routeKey = Objects.requireNonNull(routeKey, "routeKey");
    }

    String adapterId() {
        return adapterId;
    }

    String routeKey() {
        return routeKey;
    }

    @Override
    public int compareTo(DeliveryQueueKey other) {
        int adapterCompare = adapterId.compareTo(other.adapterId);
        if (adapterCompare != 0) {
            return adapterCompare;
        }
        return routeKey.compareTo(other.routeKey);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof DeliveryQueueKey other)) {
            return false;
        }
        return adapterId.equals(other.adapterId) && routeKey.equals(other.routeKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(adapterId, routeKey);
    }
}
