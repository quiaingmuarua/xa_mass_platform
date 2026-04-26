package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.runtime.RuntimeDispatchOutcomes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runtime-owned delivery service shared by concrete transport adapters.
 *
 * <p>Adapters use this service for queueing/draining/direct-send normalization.
 * This service reports delivery outcomes only; task lifecycle mutation remains
 * in engine result and assignment services.</p>
 */
public final class TransportDeliveryService {

    private final TransportDeliveryStore deliveryStore;

    public TransportDeliveryService(TransportDeliveryStore deliveryStore) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore");
    }

    public List<DispatchOutcome> enqueue(String adapterId, List<TaskDispatchItem> items, int maxItemsPerWorker) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(items.size());
        for (TaskDispatchItem item : items) {
            outcomes.add(deliveryStore.enqueue(adapterId, item, maxItemsPerWorker));
        }
        return List.copyOf(outcomes);
    }

    public List<TaskDispatchItem> drain(String adapterId, String workerId, int maxItems) {
        return deliveryStore.drain(adapterId, workerId, maxItems);
    }

    public List<TaskDispatchItem> poll(String adapterId, String workerId, int maxItems, long timeoutMillis) {
        try {
            return deliveryStore.poll(adapterId, workerId, maxItems, Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    public TransportDeliveryStoreStats stats() {
        return deliveryStore.stats();
    }

    public void shutdown() {
        deliveryStore.shutdown();
    }

    public List<DispatchOutcome> sendDirect(String adapterId,
                                            List<TaskDispatchItem> items,
                                            TransportDeliverySender sender,
                                            String unavailableReason) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(items.size());
        for (TaskDispatchItem item : items) {
            if (RuntimeDispatchOutcomes.missingWorker(item)) {
                outcomes.add(DispatchOutcome.invalid(adapterId, item, "workerId must not be blank"));
                continue;
            }
            if (sender == null) {
                outcomes.add(DispatchOutcome.adapterUnavailable(adapterId, item, unavailableReason));
                continue;
            }
            try {
                boolean sent = sender.send(item);
                outcomes.add(sent
                        ? DispatchOutcome.sent(adapterId, item)
                        : DispatchOutcome.endpointOffline(adapterId, item, "endpoint is unavailable"));
            } catch (RuntimeException e) {
                outcomes.add(DispatchOutcome.failed(adapterId, item, e.getMessage(), true));
            }
        }
        return List.copyOf(outcomes);
    }
}
