package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.TransportDeliveryAddressing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    public List<DispatchOutcome> enqueue(String adapterId, List<AdapterDispatchRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        String deliveryQueueKey = resolveDeliveryQueueKey(adapterId);
        List<DispatchOutcome> outcomes = new ArrayList<>(requests.size());
        for (AdapterDispatchRequest request : requests) {
            if (request == null) {
                outcomes.add(DispatchOutcome.invalid(
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        "request must not be null"
                ));
                continue;
            }
            outcomes.add(deliveryStore.enqueue(adapterId, deliveryQueueKey, QueuedPulledDispatch.from(request)));
        }
        return Collections.unmodifiableList(outcomes);
    }

    public List<QueuedPulledDispatch> drainItems(String adapterId, String selectedWorkerId, int maxItems) {
        return deliveryStore.drain(resolveDeliveryQueueKey(adapterId), selectedWorkerId, maxItems);
    }

    public List<QueuedPulledDispatch> pollItems(String adapterId, String selectedWorkerId, int maxItems, long timeoutMillis) {
        return pollItemResult(adapterId, selectedWorkerId, maxItems, timeoutMillis).getItems();
    }

    public TransportDeliveryPollResult pollItemResult(String adapterId, String selectedWorkerId, int maxItems, long timeoutMillis) {
        TransportDeliveryPollResult result;
        try {
            result = deliveryStore.poll(
                    resolveDeliveryQueueKey(adapterId),
                    selectedWorkerId,
                    maxItems,
                    Math.max(0L, timeoutMillis),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TransportDeliveryPollResult.unavailable();
        }
        return result;
    }

    public TransportDeliveryServiceStats stats() {
        TransportDeliveryStoreStats storeStats = deliveryStore.stats();
        return new TransportDeliveryServiceStats(
                storeStats,
                directSentItems.get(),
                directOfflineItems.get(),
                directFailedItems.get(),
                directInvalidItems.get(),
                directUnavailableItems.get()
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
                                            List<AdapterDispatchRequest> requests,
                                            TransportDeliverySender sender,
                                            String unavailableReason) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(requests.size());
        for (AdapterDispatchRequest request : requests) {
            DirectDeliveryCounters adapterCounters = directCounters(adapterId);
            if (request == null) {
                directInvalidItems.incrementAndGet();
                adapterCounters.invalidItems.incrementAndGet();
                outcomes.add(DispatchOutcome.invalid(null, "request must not be null"));
                continue;
            }
            if (sender == null) {
                directUnavailableItems.incrementAndGet();
                adapterCounters.unavailableItems.incrementAndGet();
                outcomes.add(DispatchOutcome.unavailable(request, unavailableReason));
                continue;
            }
            try {
                boolean sent = sender.send(request);
                if (sent) {
                    directSentItems.incrementAndGet();
                    adapterCounters.sentItems.incrementAndGet();
                    outcomes.add(DispatchOutcome.delivered(request));
                } else {
                    directOfflineItems.incrementAndGet();
                    adapterCounters.offlineItems.incrementAndGet();
                    outcomes.add(DispatchOutcome.noEndpoint(request, "endpoint is unavailable"));
                }
            } catch (RuntimeException e) {
                directFailedItems.incrementAndGet();
                adapterCounters.failedItems.incrementAndGet();
                outcomes.add(DispatchOutcome.failed(request, e.getMessage(), true));
            }
        }
        return Collections.unmodifiableList(outcomes);
    }

    private DirectDeliveryCounters directCounters(String adapterId) {
        return directCountersByAdapter.computeIfAbsent(normalizeAdapterId(adapterId), ignored -> new DirectDeliveryCounters());
    }

    private String normalizeAdapterId(String adapterId) {
        String normalizedAdapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        return normalizedAdapterId == null ? "unknown" : normalizedAdapterId;
    }

    private static String resolveDeliveryQueueKey(String adapterId) {
        return TransportDeliveryAddressing.normalizeAdapterId(adapterId);
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
