package com.xa.mass.transport.runtime.delivery;

import java.util.List;
import java.util.Objects;

public final class TransportDeliveryPollResult {

    private final TransportDeliveryPollStatus status;
    private final List<DispatchRoutingItem> items;

    private TransportDeliveryPollResult(TransportDeliveryPollStatus status, List<DispatchRoutingItem> items) {
        this(status, items, false);
    }

    private TransportDeliveryPollResult(TransportDeliveryPollStatus status,
                                        List<DispatchRoutingItem> items,
                                        boolean trustedView) {
        this.status = Objects.requireNonNull(status, "status");
        if (items == null || items.isEmpty()) {
            this.items = List.of();
            return;
        }
        this.items = trustedView ? items : List.copyOf(items);
    }

    public static TransportDeliveryPollResult of(TransportDeliveryPollStatus status,
                                                 List<DispatchRoutingItem> items) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case DELIVERED -> delivered(items);
            case EMPTY -> empty();
            case INVALID_REQUEST -> invalidRequest();
            case UNAVAILABLE -> unavailable();
            case SHUTDOWN -> shutdown();
        };
    }

    public static TransportDeliveryPollResult delivered(List<DispatchRoutingItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("delivered poll result must include at least one item");
        }
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.DELIVERED, items);
    }

    static TransportDeliveryPollResult deliveredView(List<DispatchRoutingItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("delivered poll result must include at least one item");
        }
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.DELIVERED, items, true);
    }

    public static TransportDeliveryPollResult empty() {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.EMPTY, List.of());
    }

    public static TransportDeliveryPollResult invalidRequest() {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.INVALID_REQUEST, List.of());
    }

    public static TransportDeliveryPollResult unavailable() {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.UNAVAILABLE, List.of());
    }

    public static TransportDeliveryPollResult shutdown() {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.SHUTDOWN, List.of());
    }

    public TransportDeliveryPollStatus getStatus() {
        return status;
    }

    public List<DispatchRoutingItem> getItems() {
        return items;
    }
}
