package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.KeyedBlockingQueueStore;
import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueKeySnapshot;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import com.xa.mass.runtime.queue.KeyedQueueSnapshot;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDeliveryAddressing;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.TransportPacket;

import java.util.Collections;
import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class QueueBackedTransportDeliveryStore implements TransportDeliveryStore {

    private final KeyedBlockingQueueStore<DeliveryQueueKey, TransportDispatchEnvelope> queueStore;
    private final int maxItemsPerRoute;
    private final AtomicLong localInvalidItems = new AtomicLong();
    private final Map<String, AtomicLong> backpressureRejectedItemsByDeliveryQueue = new ConcurrentHashMap<>();

    QueueBackedTransportDeliveryStore(
            KeyedBlockingQueueStore<DeliveryQueueKey, TransportDispatchEnvelope> queueStore,
            int maxItemsPerRoute
    ) {
        this.queueStore = Objects.requireNonNull(queueStore, "queueStore");
        if (maxItemsPerRoute <= 0) {
            throw new IllegalArgumentException("maxItemsPerRoute must be positive");
        }
        this.maxItemsPerRoute = maxItemsPerRoute;
    }

    @Override
    public DispatchOutcome enqueue(TransportDispatchEnvelope envelope) {
        String normalizedAdapterId = TransportDeliveryAddressing.normalizeAdapterId(envelope == null ? null : envelope.getAdapterId());
        String normalizedRouteKey = envelope == null ? null : TransportDeliveryAddressing.normalizeRouteKey(envelope.getRouteKey());
        String normalizedDeliveryQueueKey = envelope == null ? null : normalizeDeliveryQueueKey(envelope.getDeliveryQueueKey());
        String normalizedSelectedWorkerId = envelope == null ? null : TransportDeliveryAddressing.normalizeText(envelope.getSelectedWorkerId());
        if (envelope == null || normalizedDeliveryQueueKey == null) {
            localInvalidItems.incrementAndGet();
            return DispatchOutcome.invalid(normalizedAdapterId, envelope, "deliveryQueueKey must not be blank");
        }
        if (normalizedSelectedWorkerId == null) {
            localInvalidItems.incrementAndGet();
            return DispatchOutcome.invalid(normalizedAdapterId, envelope, "selectedWorkerId must not be blank");
        }

        DeliveryQueueKey key = new DeliveryQueueKey(normalizedDeliveryQueueKey, normalizedSelectedWorkerId);
        TransportDispatchEnvelope normalizedEnvelope = normalizeEnvelope(
                envelope,
                normalizedDeliveryQueueKey,
                normalizedSelectedWorkerId,
                normalizedAdapterId,
                normalizedRouteKey
        );
        KeyedQueueOfferResult result = queueStore.offer(
                key,
                new KeyedQueueEntry<>(normalizedEnvelope, normalizedEnvelope.getCreatedAtEpochMillis()),
                this.maxItemsPerRoute
        );
        return switch (result.status()) {
            case ENQUEUED -> DispatchOutcome.queued(normalizedAdapterId, normalizedEnvelope);
            case INVALID -> DispatchOutcome.invalid(normalizedAdapterId, normalizedEnvelope,
                    result.reason() == null ? "deliveryQueueKey must not be blank" : result.reason());
            case UNAVAILABLE -> DispatchOutcome.adapterUnavailable(normalizedAdapterId, normalizedEnvelope,
                    "delivery store is stopped");
            case BACKPRESSURE_REJECTED -> {
                backpressureRejectedItemsByDeliveryQueue
                        .computeIfAbsent(normalizedDeliveryQueueKey, ignored -> new AtomicLong())
                        .incrementAndGet();
                yield DispatchOutcome.backpressureRejected(
                        normalizedAdapterId,
                        normalizedEnvelope,
                        resolveBackpressureReason(result.reason())
                );
            }
        };
    }

    @Override
    public List<TransportDispatchEnvelope> drain(String deliveryQueueKey, String selectedWorkerId, int maxItems) {
        String normalizedDeliveryQueueKey = normalizeDeliveryQueueKey(deliveryQueueKey);
        String normalizedSelectedWorkerId = TransportDeliveryAddressing.normalizeText(selectedWorkerId);
        if (normalizedDeliveryQueueKey == null || normalizedSelectedWorkerId == null || maxItems <= 0) {
            return List.of();
        }
        List<KeyedQueueEntry<TransportDispatchEnvelope>> drained =
                queueStore.drain(new DeliveryQueueKey(normalizedDeliveryQueueKey, normalizedSelectedWorkerId), maxItems);
        if (drained.isEmpty()) {
            return List.of();
        }
        return envelopeView(drained);
    }

    @Override
    public TransportDeliveryPollResult poll(String deliveryQueueKey,
                                            String selectedWorkerId,
                                            int maxItems,
                                            long timeout,
                                            TimeUnit unit) throws InterruptedException {
        String normalizedDeliveryQueueKey = normalizeDeliveryQueueKey(deliveryQueueKey);
        String normalizedSelectedWorkerId = TransportDeliveryAddressing.normalizeText(selectedWorkerId);
        if (normalizedDeliveryQueueKey == null || normalizedSelectedWorkerId == null || maxItems <= 0) {
            return TransportDeliveryPollResult.invalidRequest();
        }
        KeyedQueuePollResult<TransportDispatchEnvelope> result =
                queueStore.poll(new DeliveryQueueKey(normalizedDeliveryQueueKey, normalizedSelectedWorkerId), maxItems, timeout, unit);
        return switch (result.status()) {
            case DELIVERED -> TransportDeliveryPollResult.deliveredView(envelopeView(result.items()));
            case EMPTY -> TransportDeliveryPollResult.empty();
            case INVALID_REQUEST -> TransportDeliveryPollResult.invalidRequest();
            case UNAVAILABLE -> TransportDeliveryPollResult.unavailable();
            case SHUTDOWN -> TransportDeliveryPollResult.shutdown();
        };
    }

    @Override
    public TransportDeliveryStoreStats stats() {
        KeyedQueueSnapshot<DeliveryQueueKey> snapshot = queueStore.snapshot();
        return new TransportDeliveryStoreStats(
                snapshot.queuedItems(),
                snapshot.queueCount(),
                snapshot.waitingPollers(),
                snapshot.maxQueuedItems(),
                snapshot.oldestQueuedAgeMillis(),
                snapshot.enqueuedItems(),
                snapshot.drainedItems(),
                snapshot.backpressureRejectedItems(),
                snapshot.invalidItems() + localInvalidItems.get(),
                snapshot.unavailableItems(),
                snapshot.shutdownClearedItems(),
                queueByAdapter(snapshot.queueByKey())
        );
    }

    @Override
    public void shutdown() {
        queueStore.shutdown();
    }

    private Map<String, TransportDeliveryQueueStats> queueByAdapter(
            Map<DeliveryQueueKey, KeyedQueueKeySnapshot> queueByKey) {
        if ((queueByKey == null || queueByKey.isEmpty()) && backpressureRejectedItemsByDeliveryQueue.isEmpty()) {
            return Map.of();
        }
        Map<String, MutableAdapterQueueStats> aggregated = new LinkedHashMap<>();
        if (queueByKey != null) {
            queueByKey.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        MutableAdapterQueueStats stats = aggregated.computeIfAbsent(
                                entry.getKey().deliveryQueueKey(),
                                ignored -> new MutableAdapterQueueStats()
                        );
                        KeyedQueueKeySnapshot keySnapshot = entry.getValue();
                        stats.queueCount++;
                        stats.queuedItems += keySnapshot.queuedItems();
                        stats.waitingPollers += keySnapshot.waitingPollers();
                        stats.oldestQueuedAgeMillis = Math.max(stats.oldestQueuedAgeMillis, keySnapshot.oldestQueuedAgeMillis());
                    });
        }
        backpressureRejectedItemsByDeliveryQueue.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> aggregated.computeIfAbsent(entry.getKey(), ignored -> new MutableAdapterQueueStats())
                        .backpressureRejectedItems += entry.getValue().get());

        Map<String, TransportDeliveryQueueStats> result = new LinkedHashMap<>();
        aggregated.forEach((adapterId, stats) -> result.put(adapterId, new TransportDeliveryQueueStats(
                stats.queuedItems,
                stats.queueCount,
                stats.waitingPollers,
                stats.oldestQueuedAgeMillis,
                stats.backpressureRejectedItems
        )));
        return Map.copyOf(result);
    }

    private String resolveBackpressureReason(String primitiveReason) {
        return switch (primitiveReason == null ? "" : primitiveReason) {
            case "runtime backlog is full" -> "runtime delivery backlog is full";
            case "queue is full", "queue capacity is exhausted" -> "delivery queue is full";
            default -> "delivery queue is full";
        };
    }

    private static TransportDispatchEnvelope normalizeEnvelope(TransportDispatchEnvelope envelope,
                                                               String normalizedDeliveryQueueKey,
                                                               String normalizedSelectedWorkerId,
                                                               String normalizedAdapterId,
                                                               String normalizedRouteKey) {
        if (Objects.equals(normalizedDeliveryQueueKey, envelope.getDeliveryQueueKey())
                && Objects.equals(normalizedSelectedWorkerId, envelope.getSelectedWorkerId())
                && Objects.equals(normalizedAdapterId, envelope.getAdapterId())
                && Objects.equals(normalizedRouteKey, envelope.getRouteKey())) {
            return envelope;
        }
        TransportPacket normalizedPacket = envelope.getPacket().withTransportAddress(normalizedAdapterId, normalizedRouteKey);
        return new TransportDispatchEnvelope(
                envelope.getDeliveryId(),
                normalizedDeliveryQueueKey,
                normalizedSelectedWorkerId,
                normalizedPacket,
                envelope.getCreatedAtEpochMillis()
        );
    }

    private static String normalizeDeliveryQueueKey(String value) {
        return TransportDeliveryAddressing.normalizeAdapterId(value);
    }

    private static List<TransportDispatchEnvelope> envelopeView(List<KeyedQueueEntry<TransportDispatchEnvelope>> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new KeyedQueueEnvelopeList(items));
    }

    private static final class MutableAdapterQueueStats {
        private int queuedItems;
        private int queueCount;
        private int waitingPollers;
        private long oldestQueuedAgeMillis;
        private long backpressureRejectedItems;
    }

    private static final class KeyedQueueEnvelopeList extends AbstractList<TransportDispatchEnvelope> {
        private final List<KeyedQueueEntry<TransportDispatchEnvelope>> entries;

        private KeyedQueueEnvelopeList(List<KeyedQueueEntry<TransportDispatchEnvelope>> entries) {
            this.entries = Objects.requireNonNull(entries, "entries");
        }

        @Override
        public TransportDispatchEnvelope get(int index) {
            return entries.get(index).value();
        }

        @Override
        public int size() {
            return entries.size();
        }
    }
}
