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
        claim(handoff, "worker-1", "node-1", "websocket");
        DeliveryQueueOffer offer = DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        );

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(offer).stream().map(outcome -> outcome.getStatus()).toList()
        );

        DeliveryCommandBatch polled = handoff.poll(100L);
        assertNotNull(polled);
        assertEquals(DeliveryCommandFixtures.queueKey(), polled.deliveryQueueKey());
        assertEquals("websocket", polled.references().getFirst().adapterId());
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(polled));
    }

    @Test
    void fullQueueReturnsBackpressureWithoutBlockingProducer() {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(1);
        claim(handoff, "worker-1", "node-1", "websocket");
        claim(handoff, "worker-2", "node-1", "websocket");
        handoff.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        assertEquals(
                List.of(DispatchOutcomeStatus.BACKPRESSURE),
                handoff.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-2", "worker-2", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    @Test
    void missingConsumerEvidenceReturnsNoEndpoint() {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(1);

        assertEquals(
                List.of(DispatchOutcomeStatus.NO_ENDPOINT),
                handoff.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    private static void claim(InMemoryTransportDeliveryCommandHandoff handoff,
                              String workerId,
                              String queueConsumerKey,
                              String adapterId) {
        handoff.claimConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                workerId,
                queueConsumerKey,
                adapterId,
                System.currentTimeMillis() + 30_000L
        ));
    }
}
