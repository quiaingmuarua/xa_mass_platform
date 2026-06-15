package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryTransportDeliveryCommandHandoffTest {

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTransportDeliveryCommandHandoff(0));
    }

    @Test
    void offerAndPollRoundTrip() throws Exception {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(2);
        DeliveryCommandBatch batch = DeliveryCommandFixtures.batch(
                "node-1",
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        );

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(batch).stream().map(outcome -> outcome.getStatus()).toList()
        );

        DeliveryCommandBatch polled = handoff.poll(100L);
        assertNotNull(polled);
        assertEquals("bucket-1", polled.deliveryBucketId());
        assertEquals("bucket-1", polled.deliveryLaneKey());
        assertEquals("node-1", polled.targetTransportNodeId());
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(polled));
    }

    @Test
    void fullQueueReturnsBackpressureWithoutBlockingProducer() {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(1);
        handoff.offer(DeliveryCommandFixtures.batch(
                "node-1",
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        assertEquals(
                List.of(DispatchOutcomeStatus.BACKPRESSURE),
                handoff.offer(DeliveryCommandFixtures.batch(
                        "node-1",
                        DeliveryCommandFixtures.command("msg-2", "worker-2", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }
}
