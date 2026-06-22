package com.xa.mass.transport.polling.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.DispatchRoutingItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPollingPendingDeliveryBufferTest {

    @Test
    void sameMailboxWorkersCannotCrossConsumePendingItems() throws Exception {
        InMemoryPollingPendingDeliveryBuffer buffer = new InMemoryPollingPendingDeliveryBuffer();

        buffer.enqueue("mailbox-a", List.of(
                item("a-1", "worker-a"),
                item("b-1", "worker-b"),
                item("a-2", "worker-a")
        ));

        PollingPendingDeliveryPollResult workerA = buffer.poll("mailbox-a", "worker-a", 10, 0);
        assertEquals(PollingPendingDeliveryPollStatus.DELIVERED, workerA.getStatus());
        assertEquals(List.of("delivery-a-1", "delivery-a-2"), workerA.getItems().stream()
                .map(DispatchRoutingItem::deliveryId)
                .toList());

        PollingPendingDeliveryPollResult workerB = buffer.poll("mailbox-a", "worker-b", 10, 0);
        assertEquals(PollingPendingDeliveryPollStatus.DELIVERED, workerB.getStatus());
        assertEquals(List.of("delivery-b-1"), workerB.getItems().stream()
                .map(DispatchRoutingItem::deliveryId)
                .toList());
    }

    @Test
    void workerSlotBackpressureDoesNotBlockDifferentWorkerInSameMailbox() throws Exception {
        InMemoryPollingPendingDeliveryBuffer buffer = new InMemoryPollingPendingDeliveryBuffer(10, 1);

        List<DispatchOutcome> workerAOutcomes = buffer.enqueue("mailbox-a", List.of(
                item("a-1", "worker-a"),
                item("a-2", "worker-a")
        ));
        List<DispatchOutcome> workerBOutcomes = buffer.enqueue("mailbox-a", List.of(item("b-1", "worker-b")));

        assertEquals(DispatchOutcomeStatus.QUEUED, workerAOutcomes.get(0).getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE, workerAOutcomes.get(1).getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, workerBOutcomes.get(0).getStatus());
        assertEquals(List.of("delivery-b-1"), buffer.poll("mailbox-a", "worker-b", 10, 0).getItems().stream()
                .map(DispatchRoutingItem::deliveryId)
                .toList());
    }

    @Test
    void statsAreConcreteDiagnosticsOnly() {
        InMemoryPollingPendingDeliveryBuffer buffer = new InMemoryPollingPendingDeliveryBuffer();

        buffer.enqueue("mailbox-a", List.of(item("a-1", "worker-a"), item("b-1", "worker-b")));

        PollingPendingDeliveryBufferStats stats = buffer.stats();
        assertEquals(2, stats.getQueuedItems());
        assertEquals(2, stats.getQueueCount());
        assertTrue(stats.getQueueByAdapter().containsKey("mailbox-a"));
    }

    private static DispatchRoutingItem item(String id, String workerId) {
        return new DispatchRoutingItem(
                "delivery-" + id,
                workerId,
                "{\"messageId\":\"" + id + "\"}",
                "corr-" + id,
                0L,
                1L
        );
    }
}
