package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;
import java.util.Objects;

/**
 * Narrow adapter-facing pull-delivery buffer for one adapter mailbox.
 *
 * <p>Polling adapters may enqueue and poll delivery messages for their own
 * mailbox. They cannot use this capability to inspect stats, shut down the
 * store, or target another mailbox.</p>
 */
public final class AdapterPullDeliveryBuffer {

    private final String adapterMailboxKey;
    private final TransportDeliveryService deliveryService;

    public AdapterPullDeliveryBuffer(String adapterMailboxKey, TransportDeliveryService deliveryService) {
        this.adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    public List<DispatchOutcome> enqueue(List<DispatchRoutingItem> items) {
        return deliveryService.enqueueForMailbox(adapterMailboxKey, items);
    }

    public TransportDeliveryPollResult poll(String selectedWorkerId, int maxItems, long timeoutMillis) {
        return deliveryService.pollMailboxItemResult(adapterMailboxKey, selectedWorkerId, maxItems, timeoutMillis);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
