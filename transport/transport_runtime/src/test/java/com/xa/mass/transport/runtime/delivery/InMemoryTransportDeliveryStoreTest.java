package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransportDeliveryStoreTest {

    @Test
    void enqueueStoresAndDrainsByAdapterAndWorker() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        TaskDispatchItem pollingItem = item("msg-1", "worker-1");
        TaskDispatchItem websocketItem = item("msg-2", "worker-1");

        DispatchOutcome pollingOutcome = store.enqueue("polling", pollingItem, 10);
        DispatchOutcome websocketOutcome = store.enqueue("websocket", websocketItem, 10);

        assertEquals(DispatchOutcomeStatus.QUEUED, pollingOutcome.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, websocketOutcome.getStatus());
        assertEquals(List.of(pollingItem), store.drain("polling", "worker-1", 10));
        assertEquals(List.of(websocketItem), store.drain("websocket", "worker-1", 10));
    }

    @Test
    void enqueueRejectsInvalidItem() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        DispatchOutcome outcome = store.enqueue("polling", item("msg-1", null), 10);

        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, outcome.getStatus());
        assertTrue(store.drain("polling", "worker-1", 10).isEmpty());
    }

    @Test
    void enqueueRejectsWhenWorkerQueueIsFull() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        DispatchOutcome first = store.enqueue("polling", item("msg-1", "worker-1"), 1);
        DispatchOutcome second = store.enqueue("polling", item("msg-2", "worker-1"), 1);

        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE_REJECTED, second.getStatus());
        assertTrue(second.isRetryable());
        assertEquals(List.of("msg-1"),
                store.drain("polling", "worker-1", 10).stream().map(TaskDispatchItem::getMessageId).toList());
    }

    @Test
    void enqueueRejectsWhenGlobalBacklogIsFull() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(1);

        DispatchOutcome first = store.enqueue("polling", item("msg-1", "worker-1"), 10);
        DispatchOutcome second = store.enqueue("polling", item("msg-2", "worker-2"), 10);

        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE_REJECTED, second.getStatus());
        assertTrue(second.isRetryable());
        assertEquals("runtime delivery backlog is full", second.getReason());
        assertEquals(1, store.stats().getQueuedItems());
        assertEquals(1, store.stats().getQueueCount());
    }

    @Test
    void drainRespectsMaxItemsAndKeepsRemainingItems() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        store.enqueue("polling", item("msg-1", "worker-1"), 10);
        store.enqueue("polling", item("msg-2", "worker-1"), 10);

        assertEquals(List.of("msg-1"),
                store.drain("polling", "worker-1", 1).stream().map(TaskDispatchItem::getMessageId).toList());
        assertEquals(List.of("msg-2"),
                store.drain("polling", "worker-1", 10).stream().map(TaskDispatchItem::getMessageId).toList());
    }

    @Test
    void statsTrackQueuedItemsQueuesAndCapacity() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore(10);
        store.enqueue("polling", item("msg-1", "worker-1"), 10);
        store.enqueue("polling", item("msg-2", "worker-1"), 10);
        store.enqueue("polling", item("msg-3", "worker-2"), 10);

        TransportDeliveryStoreStats queued = store.stats();
        assertEquals(3, queued.getQueuedItems());
        assertEquals(2, queued.getQueueCount());
        assertEquals(10, queued.getMaxQueuedItems());

        store.drain("polling", "worker-1", 10);
        TransportDeliveryStoreStats remaining = store.stats();
        assertEquals(1, remaining.getQueuedItems());
        assertEquals(1, remaining.getQueueCount());
    }

    @Test
    void pollWaitsUntilDeliveryArrives() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        TaskDispatchItem item = item("msg-1", "worker-1");

        CompletableFuture<List<TaskDispatchItem>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", "worker-1", 10, 1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });

        Thread.sleep(50L);
        store.enqueue("polling", item, 10);

        assertEquals(List.of(item), polled.get(1, TimeUnit.SECONDS));
    }

    @Test
    void statsTrackWaitingPollers() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        CompletableFuture<List<TaskDispatchItem>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("polling", "worker-1", 10, 1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });

        waitUntil(() -> store.stats().getWaitingPollers() == 1);
        store.enqueue("polling", item("msg-1", "worker-1"), 10);

        assertEquals(List.of("msg-1"), polled.get(1, TimeUnit.SECONDS)
                .stream()
                .map(TaskDispatchItem::getMessageId)
                .toList());
        assertEquals(0, store.stats().getWaitingPollers());
    }

    @Test
    void pollReturnsEmptyAfterTimeout() throws Exception {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();

        List<TaskDispatchItem> items = store.poll("polling", "worker-1", 10, 50, TimeUnit.MILLISECONDS);

        assertTrue(items.isEmpty());
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
                workerId,
                null,
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
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
