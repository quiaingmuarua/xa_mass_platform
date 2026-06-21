package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
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
    void selectedWorkerSelectorSeparatesWorkersSharingRouteAndDeliveryQueue() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        DispatchFixture firstWorkerItem = item("msg-1", "worker-1");
        DispatchFixture secondWorkerItem = item("msg-2", "worker-2");

        DispatchOutcome firstOutcome = store.enqueue("polling", queued("polling", firstWorkerItem));
        DispatchOutcome secondOutcome = store.enqueue("polling", queued("polling", secondWorkerItem));

        assertEquals(DispatchOutcomeStatus.QUEUED, firstOutcome.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, secondOutcome.getStatus());
        assertEquals(List.of("msg-1"), messageIds(store.drain("polling", "worker-1", 10)));
        assertTrue(store.drain("polling", "worker-1", 10).isEmpty());
        assertEquals(List.of("msg-2"), messageIds(store.drain("polling", "worker-2", 10)));
    }

    @Test
    void enqueueAndDrainTrimOpaqueDeliveryQueueKey() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        DispatchOutcome outcome = store.enqueue(" Polling ", queued(" Polling ", item("msg-1", " worker-1 ")));

        assertEquals("worker-1", outcome.getSelectedWorkerId());
        assertEquals(List.of("msg-1"), messageIds(store.drain(" Polling ", " worker-1 ", 10)));
        assertTrue(store.drain("polling", "worker-1", 10).isEmpty());
    }

    @Test
    void enqueueRejectsInvalidItem() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        DispatchOutcome outcome = store.enqueue("polling", invalidQueued("polling", item("msg-1", null)));

        assertEquals(DispatchOutcomeStatus.INVALID, outcome.getStatus());
        assertTrue(store.drain("polling", "worker-1", 10).isEmpty());
    }

    @Test
    void enqueueRejectsWhenBucketQueueIsFull() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(
                InMemoryTransportDeliveryStore.DEFAULT_MAX_QUEUED_ITEMS,
                1
        );

        DispatchOutcome first = store.enqueue("polling", queued("polling", item("msg-1", "worker-1")));
        DispatchOutcome second = store.enqueue("polling", queued("polling", item("msg-2", "worker-2")));

        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE, second.getStatus());
        assertTrue(second.isRetryable());
        assertEquals(List.of("msg-1"),
                messageIds(store.drain("polling", "worker-1", 10)));
    }

    @Test
    void enqueueRejectsWhenGlobalBacklogIsFull() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(1, 10);

        DispatchOutcome first = store.enqueue("polling", queued("polling", item("msg-1", "worker-1")));
        DispatchOutcome second = store.enqueue("polling", queued("polling", item("msg-2", "worker-2")));

        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE, second.getStatus());
        assertTrue(second.isRetryable());
        assertEquals("runtime delivery backlog is full", second.getReason());
        assertEquals(1, store.stats().getQueuedItems());
        assertEquals(1, store.stats().getQueueCount());
    }

    @Test
    void enqueueRejectsAfterShutdown() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        store.shutdown();

        DispatchOutcome outcome = store.enqueue("polling", queued("polling", item("msg-1", "worker-1")));

        assertEquals(DispatchOutcomeStatus.UNAVAILABLE, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        assertEquals("delivery store is stopped", outcome.getReason());
    }

    @Test
    void drainRespectsMaxItemsAndKeepsRemainingItems() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        store.enqueue("polling", queued("polling", item("msg-1", "worker-1")));
        store.enqueue("polling", queued("polling", item("msg-2", "worker-1")));

        assertEquals(List.of("msg-1"), messageIds(store.drain("polling", "worker-1", 1)));
        assertEquals(List.of("msg-2"), messageIds(store.drain("polling", "worker-1", 10)));
    }

    @Test
    void statsTrackQueuedItemsQueuesAndCapacity() {
        AtomicLong now = new AtomicLong(1_000L);
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(10, InMemoryTransportDeliveryStore.DEFAULT_MAX_ITEMS_PER_ROUTE, now::get);
        store.enqueue("polling", queued("polling", item("msg-1", "worker-1"), now.get()));
        now.set(1_250L);
        store.enqueue("polling", queued("polling", item("msg-2", "worker-1"), now.get()));
        store.enqueue("polling", queued("polling", item("msg-3", "worker-2"), now.get()));
        now.set(1_500L);

        TransportDeliveryStoreStats queued = store.stats();
        assertEquals(3, queued.getQueuedItems());
        assertEquals(1, queued.getQueueCount());
        assertEquals(10, queued.getMaxQueuedItems());
        assertEquals(500L, queued.getOldestQueuedAgeMillis());
        assertEquals(3L, queued.getEnqueuedItems());
        assertEquals(0L, queued.getDrainedItems());
        assertEquals(0L, queued.getBackpressureRejectedItems());
        assertEquals(1, queued.getQueueByAdapter().get("polling").getQueueCount());
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
        store.enqueue("polling", queued("polling", item("msg-1", "worker-1"), now.get()));

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
        store.enqueue("polling", invalidQueued("polling", item("msg-1", null)));
        store.enqueue("polling", queued("polling", item("msg-2", "worker-1")));
        store.enqueue("polling", queued("polling", item("msg-3", "worker-1")));
        store.enqueue("polling", queued("polling", item("msg-4", "worker-2")));
        store.shutdown();
        store.enqueue("polling", queued("polling", item("msg-5", "worker-1")));

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
        DispatchFixture item = item("msg-1", "worker-1");

        CompletableFuture<List<QueuedPulledDispatch>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", "worker-1", 10, 1, TimeUnit.SECONDS).getItems();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });

        Thread.sleep(50L);
        store.enqueue("polling", queued("polling", item));

        assertEquals(List.of("msg-1"), messageIds(polled.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void enqueueWakesOnePollerPerQueuedItemForSameWorker() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        CompletableFuture<List<QueuedPulledDispatch>> firstPoller = pollAsync(store, "worker-1");
        CompletableFuture<List<QueuedPulledDispatch>> secondPoller = pollAsync(store, "worker-1");

        waitUntil(() -> store.stats().getWaitingPollers() == 2);
        store.enqueue("polling", queued("polling", item("msg-1", "worker-1")));

        CompletableFuture<List<QueuedPulledDispatch>> completed =
                CompletableFuture.anyOf(firstPoller, secondPoller).thenApply(result -> castItems(result));
        assertEquals(List.of("msg-1"), messageIds(completed.get(1, TimeUnit.SECONDS)));
        assertEquals(1, store.stats().getWaitingPollers());
        assertFalse(firstPoller.isDone() && secondPoller.isDone());

        store.enqueue("polling", queued("polling", item("msg-2", "worker-1")));

        assertEquals(List.of("msg-1", "msg-2"),
                List.of(firstPoller.get(1, TimeUnit.SECONDS), secondPoller.get(1, TimeUnit.SECONDS)).stream()
                        .flatMap(List::stream)
                        .map(item -> messageId(item.payload()))
                        .sorted()
                        .toList());
    }

    @Test
    void statsTrackWaitingPollers() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        CompletableFuture<List<QueuedPulledDispatch>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", "worker-1", 10, 1, TimeUnit.SECONDS).getItems();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });

        waitUntil(() -> store.stats().getWaitingPollers() == 1);
        assertEquals(1, store.stats().getQueueByAdapter().get("polling").getWaitingPollers());
        store.enqueue("polling", queued("polling", item("msg-1", "worker-1")));

        assertEquals(List.of("msg-1"), messageIds(polled.get(1, TimeUnit.SECONDS)));
        assertEquals(0, store.stats().getWaitingPollers());
        assertEquals(Map.of(), store.stats().getQueueByAdapter());
    }

    @Test
    void statsExposePerAdapterQueueBreakdown() {
        AtomicLong now = new AtomicLong(2_000L);
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(10, InMemoryTransportDeliveryStore.DEFAULT_MAX_ITEMS_PER_ROUTE, now::get);
        store.enqueue("polling", queued("polling", item("msg-1", "worker-1"), now.get()));
        store.enqueue("websocket", queued("websocket", item("msg-2", "worker-2"), now.get()));
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
        store.enqueue("polling", queued("polling", item("queued", "worker-2")));
        CompletableFuture<List<QueuedPulledDispatch>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", "worker-1", 10, 30, TimeUnit.SECONDS).getItems();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of(queued("polling", item("interrupted", "worker-1")));
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
        assertTrue(items.getItems().isEmpty());
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

                DispatchOutcome outcome = store.enqueue("polling", queued("polling", item(messageId, "worker-1")));
                assertEquals(DispatchOutcomeStatus.QUEUED, outcome.getStatus());

                TransportDeliveryPollResult polled = poll.get(1, TimeUnit.SECONDS);
                if (polled.getStatus() == TransportDeliveryPollStatus.DELIVERED) {
                    assertEquals(List.of(messageId), messageIds(polled.getItems()));
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

        store.enqueue("polling", queued("polling", item("msg-1", "worker-1")));
        store.enqueue("polling", queued("polling", item("msg-2", "worker-2")));
        store.enqueue("websocket", queued("websocket", item("msg-3", "worker-3")));

        TransportDeliveryStoreStats queued = store.stats();
        assertQueueBreakdownConsistent(queued);

        store.drain("polling", "worker-1", 10);
        TransportDeliveryStoreStats remaining = store.stats();
        assertQueueBreakdownConsistent(remaining);
    }

    private DispatchFixture item(String messageId, String workerId) {
        return new DispatchFixture(messageId, workerId);
    }

    private QueuedPulledDispatch queued(String adapterId, DispatchFixture item) {
        return queued(adapterId, item, 1L);
    }

    private QueuedPulledDispatch queued(String adapterId, DispatchFixture item, long createdAtEpochMillis) {
        String deliveryId = "delivery-" + adapterId + "-" + item.messageId();
        return new QueuedPulledDispatch(
                deliveryId,
                item.workerId(),
                payload(item),
                correlation(item),
                createdAtEpochMillis
        );
    }

    private QueuedPulledDispatch invalidQueued(String adapterId, DispatchFixture item) {
        return null;
    }

    private String payload(DispatchFixture item) {
        return "{\"messageId\":\"" + item.messageId() + "\"}";
    }

    private String correlation(DispatchFixture item) {
        return "corr-" + item.messageId();
    }

    private CompletableFuture<List<QueuedPulledDispatch>> pollAsync(InMemoryTransportDeliveryStore store, String workerId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", workerId, 10, 2, TimeUnit.SECONDS).getItems();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<QueuedPulledDispatch> castItems(Object result) {
        return (List<QueuedPulledDispatch>) result;
    }

    private List<String> messageIds(List<QueuedPulledDispatch> items) {
        return items.stream()
                .map(item -> messageId(item.payload()))
                .toList();
    }

    private String messageId(String payload) {
        return payload.replace("{\"messageId\":\"", "").replace("\"}", "");
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

    private record DispatchFixture(String messageId, String workerId) {
    }
}
