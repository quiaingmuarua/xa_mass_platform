package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.RuntimeDispatchOutcomes;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDeliveryAddressing;
import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.ArrayList;
import java.util.AbstractList;
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

    public List<DispatchOutcome> enqueue(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(envelopes.size());
        for (TransportDispatchEnvelope envelope : envelopes) {
            outcomes.add(deliveryStore.enqueue(envelope));
        }
        return Collections.unmodifiableList(outcomes);
    }

    public List<TransportDispatchEnvelope> drainEnvelopes(String adapterId, String routeKey, int maxItems) {
        return deliveryStore.drain(adapterId, routeKey, maxItems);
    }

    public List<TransportDispatchEnvelope> pollEnvelopes(String adapterId, String routeKey, int maxItems, long timeoutMillis) {
        return pollEnvelopeResult(adapterId, routeKey, maxItems, timeoutMillis).getEnvelopes();
    }

    public TransportDeliveryPollResult pollEnvelopeResult(String adapterId, String routeKey, int maxItems, long timeoutMillis) {
        TransportDeliveryPollResult result;
        try {
            result = deliveryStore.poll(adapterId, routeKey, maxItems, Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TransportDeliveryPollResult.unavailable();
        }
        return result;
    }

    public List<TaskDispatchItem> pollDispatchItems(String adapterId, String routeKey, int maxItems, long timeoutMillis) {
        return toDispatchItems(pollEnvelopeResult(adapterId, routeKey, maxItems, timeoutMillis).getEnvelopes());
    }

    public static TaskDispatchItem toDispatchItem(TransportDispatchEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        return TaskDispatchItem.fromTransportPacket(envelope.getPacket());
    }

    public static List<TaskDispatchItem> toDispatchItems(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new DispatchItemListView(envelopes));
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
                                            List<TransportDispatchEnvelope> envelopes,
                                            TransportDeliverySender sender,
                                            String unavailableReason) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(envelopes.size());
        for (TransportDispatchEnvelope envelope : envelopes) {
            DirectDeliveryCounters adapterCounters = directCounters(adapterId);
            if (RuntimeDispatchOutcomes.missingRoute(envelope)) {
                directInvalidItems.incrementAndGet();
                adapterCounters.invalidItems.incrementAndGet();
                outcomes.add(DispatchOutcome.invalid(adapterId, envelope, "routeKey must not be blank"));
                continue;
            }
            if (sender == null) {
                directUnavailableItems.incrementAndGet();
                adapterCounters.unavailableItems.incrementAndGet();
                outcomes.add(DispatchOutcome.adapterUnavailable(adapterId, envelope, unavailableReason));
                continue;
            }
            try {
                boolean sent = sender.send(envelope);
                if (sent) {
                    directSentItems.incrementAndGet();
                    adapterCounters.sentItems.incrementAndGet();
                    outcomes.add(DispatchOutcome.sent(adapterId, envelope));
                } else {
                    directOfflineItems.incrementAndGet();
                    adapterCounters.offlineItems.incrementAndGet();
                    outcomes.add(DispatchOutcome.endpointOffline(adapterId, envelope, "endpoint is unavailable"));
                }
            } catch (RuntimeException e) {
                directFailedItems.incrementAndGet();
                adapterCounters.failedItems.incrementAndGet();
                outcomes.add(DispatchOutcome.failed(adapterId, envelope, e.getMessage(), true));
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

    private static final class DispatchItemListView extends AbstractList<TaskDispatchItem> {
        private final List<TransportDispatchEnvelope> envelopes;
        private final TaskDispatchItem[] cache;

        private DispatchItemListView(List<TransportDispatchEnvelope> envelopes) {
            this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
            this.cache = new TaskDispatchItem[envelopes.size()];
        }

        @Override
        public TaskDispatchItem get(int index) {
            TaskDispatchItem cached = cache[index];
            if (cached != null) {
                return cached;
            }
            TaskDispatchItem resolved = toDispatchItem(envelopes.get(index));
            cache[index] = resolved;
            return resolved;
        }

        @Override
        public int size() {
            return envelopes.size();
        }
    }
}

