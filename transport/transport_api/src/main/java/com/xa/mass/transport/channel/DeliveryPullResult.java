package com.xa.mass.transport.channel;

import java.util.List;
import java.util.Objects;

public final class DeliveryPullResult {

    private final DeliveryPullStatus status;
    private final List<PulledDeliveryMessage> items;

    private DeliveryPullResult(DeliveryPullStatus status, List<PulledDeliveryMessage> items) {
        this.status = Objects.requireNonNull(status, "status");
        this.items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
    }

    public static DeliveryPullResult of(DeliveryPullStatus status, List<PulledDeliveryMessage> items) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case DELIVERED -> delivered(items);
            case EMPTY -> empty();
            case INVALID_REQUEST -> invalidRequest();
            case UNAVAILABLE -> unavailable();
            case SHUTDOWN -> shutdown();
        };
    }

    public static DeliveryPullResult delivered(List<PulledDeliveryMessage> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("delivered pull result must include at least one item");
        }
        return new DeliveryPullResult(DeliveryPullStatus.DELIVERED, items);
    }

    public static DeliveryPullResult empty() {
        return new DeliveryPullResult(DeliveryPullStatus.EMPTY, List.of());
    }

    public static DeliveryPullResult invalidRequest() {
        return new DeliveryPullResult(DeliveryPullStatus.INVALID_REQUEST, List.of());
    }

    public static DeliveryPullResult unavailable() {
        return new DeliveryPullResult(DeliveryPullStatus.UNAVAILABLE, List.of());
    }

    public static DeliveryPullResult shutdown() {
        return new DeliveryPullResult(DeliveryPullStatus.SHUTDOWN, List.of());
    }

    public DeliveryPullStatus getStatus() {
        return status;
    }

    public List<PulledDeliveryMessage> getItems() {
        return items;
    }
}
