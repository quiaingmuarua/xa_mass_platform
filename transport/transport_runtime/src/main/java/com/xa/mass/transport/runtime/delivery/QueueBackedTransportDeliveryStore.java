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
    private final Map<String, AtomicLong> backpressureRejectedItemsByAdapter = new ConcurrentHashMap<>();

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
        if (envelope == null || normalizedRouteKey == null) {
            localInvalidItems.incrementAndGet();
            return DispatchOutcome.invalid(normalizedAdapterId, envelope, "routeKey must not be blank");
        }

        DeliveryQueueKey key = new DeliveryQueueKey(normalizedAdapterId, normalizedRouteKey);
        TransportPacket normalizedPacket = envelope.getPacket().withTransportAddress(normalizedAdapterId, normalizedRouteKey);
        TransportDispatchEnvelope normalizedEnvelope = new TransportDispatchEnvelope(
                envelope.getDeliveryId(),
                normalizedPacket,
                envelope.getCreatedAtEpochMillis()
        );
        KeyedQueueOfferResult result = queueStore.offer(
                key,
                new KeyedQueueEntry<>(normalizedEnvelope, normalizedEnvelope.getCreatedAtEpochMillis()),
                this.maxItemsPerRoute
        );
        return switch (result.status()) {
            case ENQUEUED -> DispatchOutcome.queued(normalizedAdapterId, normalizedEnvelope);
            case INVALID -> DispatchOutcome.invalid(normalizedAdapterId, normalizedEnvelope,
                    result.reason() == null ? "routeKey must not be blank" : result.reason());
            case UNAVAILABLE -> DispatchOutcome.adapterUnavailable(normalizedAdapterId, normalizedEnvelope,
                    "delivery store is stopped");
            case BACKPRESSURE_REJECTED -> {
                backpressureRejectedItemsByAdapter
                        .computeIfAbsent(normalizedAdapterId == null ? "unknown" : normalizedAdapterId, ignored -> new AtomicLong())
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
    public List<TransportDispatchEnvelope> drain(String adapterId, String routeKey, int maxItems) {
        String normalizedAdapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        String normalizedRouteKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null || maxItems <= 0) {
            return List.of();
        }
        return queueStore.drain(new DeliveryQueueKey(normalizedAdapterId, normalizedRouteKey), maxItems).stream()
                .map(KeyedQueueEntry::value)
                .toList();
    }

    @Override
    public TransportDeliveryPollResult poll(String adapterId,
                                            String routeKey,
                                            int maxItems,
                                            long timeout,
                                            TimeUnit unit) throws InterruptedException {
        String normalizedAdapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        String normalizedRouteKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        if (normalizedAdapterId == null || normalizedRouteKey == null || maxItems <= 0) {
            return TransportDeliveryPollResult.invalidRequest();
        }
        KeyedQueuePollResult<TransportDispatchEnvelope> result =
                queueStore.poll(new DeliveryQueueKey(normalizedAdapterId, normalizedRouteKey), maxItems, timeout, unit);
        return switch (result.status()) {
            case DELIVERED -> TransportDeliveryPollResult.delivered(result.items().stream().map(KeyedQueueEntry::value).toList());
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
        if ((queueByKey == null || queueByKey.isEmpty()) && backpressureRejectedItemsByAdapter.isEmpty()) {
            return Map.of();
        }
        Map<String, MutableAdapterQueueStats> aggregated = new LinkedHashMap<>();
        if (queueByKey != null) {
            queueByKey.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        MutableAdapterQueueStats stats = aggregated.computeIfAbsent(
                                entry.getKey().adapterId(),
                                ignored -> new MutableAdapterQueueStats()
                        );
                        KeyedQueueKeySnapshot keySnapshot = entry.getValue();
                        stats.queueCount++;
                        stats.queuedItems += keySnapshot.queuedItems();
                        stats.waitingPollers += keySnapshot.waitingPollers();
                        stats.oldestQueuedAgeMillis = Math.max(stats.oldestQueuedAgeMillis, keySnapshot.oldestQueuedAgeMillis());
                    });
        }
        backpressureRejectedItemsByAdapter.entrySet().stream()
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

    private static final class MutableAdapterQueueStats {
        private int queuedItems;
        private int queueCount;
        private int waitingPollers;
        private long oldestQueuedAgeMillis;
        private long backpressureRejectedItems;
    }
}
