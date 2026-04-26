package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void drainRespectsMaxItemsAndKeepsRemainingItems() {
        InMemoryTransportDeliveryStore store = new InMemoryTransportDeliveryStore();
        store.enqueue("polling", item("msg-1", "worker-1"), 10);
        store.enqueue("polling", item("msg-2", "worker-1"), 10);

        assertEquals(List.of("msg-1"),
                store.drain("polling", "worker-1", 1).stream().map(TaskDispatchItem::getMessageId).toList());
        assertEquals(List.of("msg-2"),
                store.drain("polling", "worker-1", 10).stream().map(TaskDispatchItem::getMessageId).toList());
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
}
