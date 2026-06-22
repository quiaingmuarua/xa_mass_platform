package com.xa.mass.transport.polling.delivery;

import com.xa.mass.transport.runtime.delivery.DispatchRoutingItem;

import java.util.List;
import java.util.Objects;

public final class PollingPendingDeliveryPollResult {

    private final PollingPendingDeliveryPollStatus status;
    private final List<DispatchRoutingItem> items;

    private PollingPendingDeliveryPollResult(PollingPendingDeliveryPollStatus status, List<DispatchRoutingItem> items) {
        this(status, items, false);
    }

    private PollingPendingDeliveryPollResult(PollingPendingDeliveryPollStatus status,
                                        List<DispatchRoutingItem> items,
                                        boolean trustedView) {
        this.status = Objects.requireNonNull(status, "status");
        if (items == null || items.isEmpty()) {
            this.items = List.of();
            return;
        }
        this.items = trustedView ? items : List.copyOf(items);
    }

    public static PollingPendingDeliveryPollResult of(PollingPendingDeliveryPollStatus status,
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

    public static PollingPendingDeliveryPollResult delivered(List<DispatchRoutingItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("delivered poll result must include at least one item");
        }
        return new PollingPendingDeliveryPollResult(PollingPendingDeliveryPollStatus.DELIVERED, items);
    }

    static PollingPendingDeliveryPollResult deliveredView(List<DispatchRoutingItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("delivered poll result must include at least one item");
        }
        return new PollingPendingDeliveryPollResult(PollingPendingDeliveryPollStatus.DELIVERED, items, true);
    }

    public static PollingPendingDeliveryPollResult empty() {
        return new PollingPendingDeliveryPollResult(PollingPendingDeliveryPollStatus.EMPTY, List.of());
    }

    public static PollingPendingDeliveryPollResult invalidRequest() {
        return new PollingPendingDeliveryPollResult(PollingPendingDeliveryPollStatus.INVALID_REQUEST, List.of());
    }

    public static PollingPendingDeliveryPollResult unavailable() {
        return new PollingPendingDeliveryPollResult(PollingPendingDeliveryPollStatus.UNAVAILABLE, List.of());
    }

    public static PollingPendingDeliveryPollResult shutdown() {
        return new PollingPendingDeliveryPollResult(PollingPendingDeliveryPollStatus.SHUTDOWN, List.of());
    }

    public PollingPendingDeliveryPollStatus getStatus() {
        return status;
    }

    public List<DispatchRoutingItem> getItems() {
        return items;
    }
}
