package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.runtime.RuntimeDispatchOutcomes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-owned delivery service shared by concrete transport adapters.
 *
 * <p>Adapters use this service for queueing/draining/direct-send normalization.
 * This service reports delivery outcomes only; task lifecycle mutation remains
 * in engine result and assignment services.</p>
 */
public final class TransportDeliveryService {

    private final TransportDeliveryStore deliveryStore;
    private final AtomicLong directSentItems = new AtomicLong();
    private final AtomicLong directOfflineItems = new AtomicLong();
    private final AtomicLong directFailedItems = new AtomicLong();
    private final AtomicLong directInvalidItems = new AtomicLong();
    private final AtomicLong directUnavailableItems = new AtomicLong();
    private final ConcurrentHashMap<String, DirectDeliveryCounters> directCountersByAdapter = new ConcurrentHashMap<>();

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
        TransportDeliveryStoreStats storeStats = deliveryStore.stats();
        return new TransportDeliveryStoreStats(
                storeStats.getQueuedItems(),
                storeStats.getQueueCount(),
                storeStats.getWaitingPollers(),
                storeStats.getMaxQueuedItems(),
                storeStats.getOldestQueuedAgeMillis(),
                storeStats.getEnqueuedItems(),
                storeStats.getDrainedItems(),
                storeStats.getBackpressureRejectedItems(),
                storeStats.getInvalidItems(),
                storeStats.getUnavailableItems(),
                storeStats.getShutdownClearedItems(),
                directSentItems.get(),
                directOfflineItems.get(),
                directFailedItems.get(),
                directInvalidItems.get(),
                directUnavailableItems.get(),
                storeStats.getQueueByAdapter()
        );
    }

    public Map<String, TransportDirectDeliveryStats> directStatsByAdapter() {
        Map<String, TransportDirectDeliveryStats> snapshot = new LinkedHashMap<>();
        directCountersByAdapter.keySet().stream()
                .sorted()
                .forEach(adapterId -> snapshot.put(adapterId, directCountersByAdapter.get(adapterId).snapshot()));
        return Map.copyOf(snapshot);
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
            DirectDeliveryCounters adapterCounters = directCounters(adapterId);
            if (RuntimeDispatchOutcomes.missingWorker(item)) {
                directInvalidItems.incrementAndGet();
                adapterCounters.invalidItems.incrementAndGet();
                outcomes.add(DispatchOutcome.invalid(adapterId, item, "workerId must not be blank"));
                continue;
            }
            if (sender == null) {
                directUnavailableItems.incrementAndGet();
                adapterCounters.unavailableItems.incrementAndGet();
                outcomes.add(DispatchOutcome.adapterUnavailable(adapterId, item, unavailableReason));
                continue;
            }
            try {
                boolean sent = sender.send(item);
                if (sent) {
                    directSentItems.incrementAndGet();
                    adapterCounters.sentItems.incrementAndGet();
                    outcomes.add(DispatchOutcome.sent(adapterId, item));
                } else {
                    directOfflineItems.incrementAndGet();
                    adapterCounters.offlineItems.incrementAndGet();
                    outcomes.add(DispatchOutcome.endpointOffline(adapterId, item, "endpoint is unavailable"));
                }
            } catch (RuntimeException e) {
                directFailedItems.incrementAndGet();
                adapterCounters.failedItems.incrementAndGet();
                outcomes.add(DispatchOutcome.failed(adapterId, item, e.getMessage(), true));
            }
        }
        return List.copyOf(outcomes);
    }

    private DirectDeliveryCounters directCounters(String adapterId) {
        return directCountersByAdapter.computeIfAbsent(normalizeAdapterId(adapterId), ignored -> new DirectDeliveryCounters());
    }

    private String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return "unknown";
        }
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }

    private static final class DirectDeliveryCounters {
        private final AtomicLong sentItems = new AtomicLong();
        private final AtomicLong offlineItems = new AtomicLong();
        private final AtomicLong failedItems = new AtomicLong();
        private final AtomicLong invalidItems = new AtomicLong();
        private final AtomicLong unavailableItems = new AtomicLong();

        private TransportDirectDeliveryStats snapshot() {
            return new TransportDirectDeliveryStats(
                    sentItems.get(),
                    offlineItems.get(),
                    failedItems.get(),
                    invalidItems.get(),
                    unavailableItems.get()
            );
        }
    }
}
