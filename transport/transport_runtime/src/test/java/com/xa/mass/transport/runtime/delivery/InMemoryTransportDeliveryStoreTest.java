package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.packet.TransportPacketFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransportDeliveryStoreTest {

    @Test
    void routeKeyQueueAllowsAdapterTakeoverDrain() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        TaskDispatchItem pollingItem = item("msg-1", "worker-1");
        TaskDispatchItem websocketItem = item("msg-2", "worker-1");

        DispatchOutcome pollingOutcome = store.enqueue(envelope("polling", pollingItem));
        DispatchOutcome websocketOutcome = store.enqueue(envelope("websocket", websocketItem));

        assertEquals(DispatchOutcomeStatus.QUEUED, pollingOutcome.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, websocketOutcome.getStatus());
        assertEquals(List.of("msg-1", "msg-2"), messageIds(store.drain("polling", "worker-1", 10)));
        assertTrue(store.drain("websocket", "worker-1", 10).isEmpty());
    }

    @Test
    void enqueueAndDrainUseCanonicalAdapterAndRouteKeys() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        DispatchOutcome outcome = store.enqueue(envelope(" Polling ", item("msg-1", " worker-1 ")));

        assertEquals("polling", outcome.getAdapterId());
        assertEquals("worker-1", outcome.getRouteKey());
        assertEquals(List.of("msg-1"), messageIds(store.drain("polling", "worker-1", 10)));
        assertTrue(store.drain(" Polling ", " worker-1 ", 10).isEmpty());
    }

    @Test
    void enqueueRejectsInvalidItem() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        DispatchOutcome outcome = store.enqueue(invalidEnvelope("polling", item("msg-1", null)));

        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, outcome.getStatus());
        assertTrue(store.drain("polling", "worker-1", 10).isEmpty());
    }

    @Test
    void enqueueRejectsWhenWorkerQueueIsFull() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(
                InMemoryTransportDeliveryStore.DEFAULT_MAX_QUEUED_ITEMS,
                1
        );

        DispatchOutcome first = store.enqueue(envelope("polling", item("msg-1", "worker-1")));
        DispatchOutcome second = store.enqueue(envelope("polling", item("msg-2", "worker-1")));

        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE_REJECTED, second.getStatus());
        assertTrue(second.isRetryable());
        assertEquals(List.of("msg-1"),
                messageIds(store.drain("polling", "worker-1", 10)));
    }

    @Test
    void enqueueRejectsWhenGlobalBacklogIsFull() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(1, 10);

        DispatchOutcome first = store.enqueue(envelope("polling", item("msg-1", "worker-1")));
        DispatchOutcome second = store.enqueue(envelope("polling", item("msg-2", "worker-2")));

        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE_REJECTED, second.getStatus());
        assertTrue(second.isRetryable());
        assertEquals("runtime delivery backlog is full", second.getReason());
        assertEquals(1, store.stats().getQueuedItems());
        assertEquals(1, store.stats().getQueueCount());
    }

    @Test
    void enqueueRejectsAfterShutdown() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        store.shutdown();

        DispatchOutcome outcome = store.enqueue(envelope("polling", item("msg-1", "worker-1")));

        assertEquals(DispatchOutcomeStatus.ADAPTER_UNAVAILABLE, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        assertEquals("delivery store is stopped", outcome.getReason());
    }

    @Test
    void drainRespectsMaxItemsAndKeepsRemainingItems() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        store.enqueue(envelope("polling", item("msg-1", "worker-1")));
        store.enqueue(envelope("polling", item("msg-2", "worker-1")));

        assertEquals(List.of("msg-1"), messageIds(store.drain("polling", "worker-1", 1)));
        assertEquals(List.of("msg-2"), messageIds(store.drain("polling", "worker-1", 10)));
    }

    @Test
    void statsTrackQueuedItemsQueuesAndCapacity() {
        AtomicLong now = new AtomicLong(1_000L);
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(10, InMemoryTransportDeliveryStore.DEFAULT_MAX_ITEMS_PER_ROUTE, now::get);
        store.enqueue(envelope("polling", item("msg-1", "worker-1"), now.get()));
        now.set(1_250L);
        store.enqueue(envelope("polling", item("msg-2", "worker-1"), now.get()));
        store.enqueue(envelope("polling", item("msg-3", "worker-2"), now.get()));
        now.set(1_500L);

        TransportDeliveryStoreStats queued = store.stats();
        assertEquals(3, queued.getQueuedItems());
        assertEquals(2, queued.getQueueCount());
        assertEquals(10, queued.getMaxQueuedItems());
        assertEquals(500L, queued.getOldestQueuedAgeMillis());
        assertEquals(3L, queued.getEnqueuedItems());
        assertEquals(0L, queued.getDrainedItems());
        assertEquals(0L, queued.getBackpressureRejectedItems());
        assertEquals(2, queued.getQueueByAdapter().get("polling").getQueueCount());
        assertEquals(3, queued.getQueueByAdapter().get("polling").getQueuedItems());
        assertEquals(500L, queued.getQueueByAdapter().get("polling").getOldestQueuedAgeMillis());

        store.drain("polling", "worker-1", 10);
        now.set(1_800L);
        TransportDeliveryStoreStats remaining = store.stats();
        assertEquals(1, remaining.getQueuedItems());
        assertEquals(1, remaining.getQueueCount());
        assertEquals(550L, remaining.getOldestQueuedAgeMillis());
        assertEquals(3L, remaining.getEnqueuedItems());
        assertEquals(2L, remaining.getDrainedItems());
        assertEquals(1, remaining.getQueueByAdapter().get("polling").getQueueCount());
        assertEquals(1, remaining.getQueueByAdapter().get("polling").getQueuedItems());
    }

    @Test
    void statsUseShortLivedSnapshotCacheBeforeRefreshing() {
        AtomicLong now = new AtomicLong(1_000L);
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(10, InMemoryTransportDeliveryStore.DEFAULT_MAX_ITEMS_PER_ROUTE, now::get);
        store.enqueue(envelope("polling", item("msg-1", "worker-1"), now.get()));

        TransportDeliveryStoreStats initial = store.stats();
        assertEquals(1, initial.getQueuedItems());
        assertEquals(1, initial.getQueueCount());
        assertEquals(0L, initial.getOldestQueuedAgeMillis());

        now.set(1_100L);
        TransportDeliveryStoreStats cached = store.stats();
        assertEquals(1, cached.getQueuedItems());
        assertEquals(1, cached.getQueueCount());
        assertEquals(0L, cached.getOldestQueuedAgeMillis());

        now.set(1_300L);
        TransportDeliveryStoreStats refreshed = store.stats();
        assertEquals(1, refreshed.getQueuedItems());
        assertEquals(1, refreshed.getQueueCount());
        assertEquals(300L, refreshed.getOldestQueuedAgeMillis());
    }

    @Test
    void statsTrackRejectedAndUnavailableDeliveryOutcomes() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(1, 1);
        store.enqueue(invalidEnvelope("polling", item("msg-1", null)));
        store.enqueue(envelope("polling", item("msg-2", "worker-1")));
        store.enqueue(envelope("polling", item("msg-3", "worker-1")));
        store.enqueue(envelope("polling", item("msg-4", "worker-2")));
        store.shutdown();
        store.enqueue(envelope("polling", item("msg-5", "worker-1")));

        TransportDeliveryStoreStats stats = store.stats();
        assertEquals(1L, stats.getInvalidItems());
        assertEquals(2L, stats.getBackpressureRejectedItems());
        assertEquals(1L, stats.getUnavailableItems());
        assertEquals(1L, stats.getShutdownClearedItems());
        assertEquals(2L, stats.getQueueByAdapter().get("polling").getBackpressureRejectedItems());
    }

    @Test
    void pollWaitsUntilDeliveryArrives() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        TaskDispatchItem item = item("msg-1", "worker-1");

        CompletableFuture<List<TransportDispatchEnvelope>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", "worker-1", 10, 1, TimeUnit.SECONDS).getEnvelopes();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });

        Thread.sleep(50L);
        store.enqueue(envelope("polling", item));

        assertEquals(List.of("msg-1"), messageIds(polled.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void enqueueWakesOnePollerPerQueuedItemForSameWorker() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        CompletableFuture<List<TransportDispatchEnvelope>> firstPoller = pollAsync(store, "worker-1");
        CompletableFuture<List<TransportDispatchEnvelope>> secondPoller = pollAsync(store, "worker-1");

        waitUntil(() -> store.stats().getWaitingPollers() == 2);
        store.enqueue(envelope("polling", item("msg-1", "worker-1")));

        CompletableFuture<List<TransportDispatchEnvelope>> completed =
                CompletableFuture.anyOf(firstPoller, secondPoller).thenApply(result -> castEnvelopes(result));
        assertEquals(List.of("msg-1"), messageIds(completed.get(1, TimeUnit.SECONDS)));
        assertEquals(1, store.stats().getWaitingPollers());
        assertFalse(firstPoller.isDone() && secondPoller.isDone());

        store.enqueue(envelope("polling", item("msg-2", "worker-1")));

        assertEquals(List.of("msg-1", "msg-2"),
                List.of(firstPoller.get(1, TimeUnit.SECONDS), secondPoller.get(1, TimeUnit.SECONDS)).stream()
                        .flatMap(List::stream)
                        .map(TransportDispatchEnvelope::getPacket)
                        .map(TransportPacket::messageId)
                        .sorted()
                        .toList());
    }

    @Test
    void statsTrackWaitingPollers() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        CompletableFuture<List<TransportDispatchEnvelope>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", "worker-1", 10, 1, TimeUnit.SECONDS).getEnvelopes();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });

        waitUntil(() -> store.stats().getWaitingPollers() == 1);
        assertEquals(1, store.stats().getQueueByAdapter().get("polling").getWaitingPollers());
        store.enqueue(envelope("polling", item("msg-1", "worker-1")));

        assertEquals(List.of("msg-1"), messageIds(polled.get(1, TimeUnit.SECONDS)));
        assertEquals(0, store.stats().getWaitingPollers());
        assertEquals(Map.of(), store.stats().getQueueByAdapter());
    }

    @Test
    void statsExposePerAdapterQueueBreakdown() {
        AtomicLong now = new AtomicLong(2_000L);
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(10, InMemoryTransportDeliveryStore.DEFAULT_MAX_ITEMS_PER_ROUTE, now::get);
        store.enqueue(envelope("polling", item("msg-1", "worker-1"), now.get()));
        store.enqueue(envelope("websocket", item("msg-2", "worker-2"), now.get()));
        now.set(2_300L);

        TransportDeliveryStoreStats stats = store.stats();

        assertEquals(2, stats.getQueueByAdapter().size());
        assertEquals(1, stats.getQueueByAdapter().get("polling").getQueuedItems());
        assertEquals(1, stats.getQueueByAdapter().get("websocket").getQueuedItems());
        assertEquals(300L, stats.getQueueByAdapter().get("polling").getOldestQueuedAgeMillis());
        assertEquals(300L, stats.getQueueByAdapter().get("websocket").getOldestQueuedAgeMillis());
    }

    @Test
    void shutdownWakesWaitingPollersAndClearsQueuedItems() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        store.enqueue(envelope("polling", item("queued", "worker-2")));
        CompletableFuture<List<TransportDispatchEnvelope>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", "worker-1", 10, 30, TimeUnit.SECONDS).getEnvelopes();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of(envelope("polling", item("interrupted", "worker-1")));
            }
        });

        waitUntil(() -> store.stats().getWaitingPollers() == 1);
        store.shutdown();

        assertTrue(polled.get(1, TimeUnit.SECONDS).isEmpty());
        assertEquals(0, store.stats().getQueuedItems());
        assertEquals(0, store.stats().getQueueCount());
        assertEquals(0, store.stats().getWaitingPollers());
        assertEquals(1L, store.stats().getShutdownClearedItems());
        assertTrue(store.drain("polling", "worker-2", 10).isEmpty());
    }

    @Test
    void pollReturnsEmptyAfterTimeout() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        TransportDeliveryPollResult items = store.poll("polling", "worker-1", 10, 50, TimeUnit.MILLISECONDS);

        assertEquals(TransportDeliveryPollStatus.EMPTY, items.getStatus());
        assertTrue(items.getEnvelopes().isEmpty());
    }

    @Test
    void queuedOutcomeRemainsReachableAcrossConcurrentPollTimeoutCleanup() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(10_000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 500; i++) {
                String messageId = "msg-" + i;
                Future<TransportDeliveryPollResult> poll = executor.submit(
                        () -> store.poll("polling", "worker-1", 1, 1, TimeUnit.MILLISECONDS));

                DispatchOutcome outcome = store.enqueue(envelope("polling", item(messageId, "worker-1")));
                assertEquals(DispatchOutcomeStatus.QUEUED, outcome.getStatus());

                TransportDeliveryPollResult polled = poll.get(1, TimeUnit.SECONDS);
                if (polled.getStatus() == TransportDeliveryPollStatus.DELIVERED) {
                    assertEquals(List.of(messageId), messageIds(polled.getEnvelopes()));
                } else {
                    assertEquals(List.of(messageId), messageIds(store.drain("polling", "worker-1", 1)));
                }
                assertQueueBreakdownConsistent(store.stats());
            }
            TransportDeliveryStoreStats stats = store.stats();
            assertEquals(0, stats.getQueuedItems());
            assertEquals(Map.of(), stats.getQueueByAdapter());
            assertEquals(stats.getEnqueuedItems(), stats.getDrainedItems());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void statsGlobalQueuedItemsMatchPerAdapterBreakdown() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        store.enqueue(envelope("polling", item("msg-1", "worker-1")));
        store.enqueue(envelope("polling", item("msg-2", "worker-2")));
        store.enqueue(envelope("websocket", item("msg-3", "worker-3")));

        TransportDeliveryStoreStats queued = store.stats();
        assertQueueBreakdownConsistent(queued);

        store.drain("polling", "worker-1", 10);
        TransportDeliveryStoreStats remaining = store.stats();
        assertQueueBreakdownConsistent(remaining);
    }

    private TaskDispatchItem item(String messageId, String workerId) {
        return new TaskDispatchItem(
                "task-1",
                messageId,
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-" + messageId,
                workerId,
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }

    private TransportDispatchEnvelope envelope(String adapterId, TaskDispatchItem item) {
        return envelope(adapterId, item, 1L);
    }

    private TransportDispatchEnvelope envelope(String adapterId, TaskDispatchItem item, long createdAtEpochMillis) {
        String deliveryId = "delivery-" + adapterId + "-" + item.getMessageId();
        return new TransportDispatchEnvelope(
                deliveryId,
                new TransportPacketFactory(() -> deliveryId)
                        .fromDispatchView(adapterId, item.getWorkerId(), item.attemptId(), item),
                createdAtEpochMillis
        );
    }

    private TransportDispatchEnvelope invalidEnvelope(String adapterId, TaskDispatchItem item) {
        String deliveryId = "delivery-" + adapterId + "-" + item.getMessageId();
        return new TransportDispatchEnvelope(
                deliveryId,
                new TransportPacketFactory(() -> deliveryId)
                        .fromDispatchView(adapterId, " ", item.attemptId(), item),
                1L
        );
    }

    private CompletableFuture<List<TransportDispatchEnvelope>> pollAsync(InMemoryTransportDeliveryStore store, String workerId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", workerId, 10, 2, TimeUnit.SECONDS).getEnvelopes();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<TransportDispatchEnvelope> castEnvelopes(Object result) {
        return (List<TransportDispatchEnvelope>) result;
    }

    private List<String> messageIds(List<TransportDispatchEnvelope> envelopes) {
        return envelopes.stream()
                .map(TransportDispatchEnvelope::getPacket)
                .map(TransportPacket::messageId)
                .toList();
    }

    private void assertQueueBreakdownConsistent(TransportDeliveryStoreStats stats) {
        int adapterQueuedItems = stats.getQueueByAdapter().values().stream()
                .mapToInt(TransportDeliveryQueueStats::getQueuedItems)
                .sum();
        assertEquals(stats.getQueuedItems(), adapterQueuedItems);
        if (stats.getQueuedItems() > 0) {
            assertFalse(stats.getQueueByAdapter().isEmpty());
        }
    }

    private void waitUntil(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean());
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
