package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("worker-1", polled.items().getFirst().getSelectedWorkerId());
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
    void uncompletedClaimReturnsToReadyAfterVisibilityTimeout() throws Exception {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(2, 30_000L);
        claim(handoff, "worker-1", "node-1", "websocket");
        handoff.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));

        DeliveryCommandBatch first = handoff.poll(100L);
        assertNotNull(first);
        assertEquals(1L, handoff.inflightClaimsForTest());
        assertNull(handoff.poll(0L));

        handoff.expireInflightForTest();
        DeliveryCommandBatch redelivered = handoff.poll(100L);

        assertNotNull(redelivered);
        assertEquals(List.of("msg-1"), DeliveryCommandFixtures.messages(redelivered));
        assertEquals(1L, handoff.inflightClaimsForTest());
    }

    @Test
    void completeAcknowledgesClaimedCommand() throws Exception {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(2, 30_000L);
        claim(handoff, "worker-1", "node-1", "websocket");
        handoff.offer(DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        ));
        DeliveryCommandBatch batch = handoff.poll(100L);

        assertNotNull(batch);
        handoff.complete(batch, List.of());

        assertEquals(0L, handoff.inflightClaimsForTest());
        assertNull(handoff.poll(0L));
    }

    @Test
    void offerQueuesWithoutProducerSideConsumerEvidenceLookup() throws Exception {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(1);

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
        DeliveryCommandBatch polled = handoff.poll(100L);
        assertNotNull(polled);
        assertEquals("worker-1", polled.items().getFirst().getSelectedWorkerId());
    }

    @Test
    void staleReleaseDoesNotRemoveNewConsumerEvidenceOnSameQueueConsumer() {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(2);
        claim(handoff, "worker-1", "node-1", "conn-old", "websocket");
        claim(handoff, "worker-1", "node-1", "conn-new", "websocket");

        handoff.releaseConsumer(new DeliveryCommandConsumerClaim("bucket-1", "worker-1", "conn-old", 0L));

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    private static void claim(InMemoryTransportDeliveryCommandHandoff handoff,
                              String workerId,
                              String endpointLeaseId,
                              String endpointDriverId) {
        claim(handoff, workerId, endpointLeaseId, endpointLeaseId, endpointDriverId);
    }

    private static void claim(InMemoryTransportDeliveryCommandHandoff handoff,
                              String workerId,
                              String endpointLeaseId,
                              String consumerEvidenceId,
                              String endpointDriverId) {
        handoff.claimConsumer(new DeliveryCommandConsumerClaim(
                "bucket-1",
                workerId,
                consumerEvidenceId,
                System.currentTimeMillis() + 30_000L
        ));
    }
}
