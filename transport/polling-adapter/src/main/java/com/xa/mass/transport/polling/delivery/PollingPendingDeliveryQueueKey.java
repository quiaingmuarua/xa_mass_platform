package com.xa.mass.transport.polling.delivery;

import java.util.Objects;

final class PollingPendingDeliveryQueueKey implements Comparable<PollingPendingDeliveryQueueKey> {

    private final String queueKey;

    PollingPendingDeliveryQueueKey(String queueKey) {
        this.queueKey = Objects.requireNonNull(queueKey, "queueKey");
    }

    String queueKey() {
        return queueKey;
    }

    @Override
    public int compareTo(PollingPendingDeliveryQueueKey other) {
        return queueKey.compareTo(other.queueKey);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PollingPendingDeliveryQueueKey other)) {
            return false;
        }
        return queueKey.equals(other.queueKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(queueKey);
    }
}
