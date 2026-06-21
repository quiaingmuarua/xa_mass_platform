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
        AdapterMailboxDeliveryOffer offer = DeliveryCommandFixtures.offer(
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
        );

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(offer).stream().map(outcome -> outcome.getStatus()).toList()
        );

        DeliveryCommandBatch polled = handoff.poll(100L);
        assertNotNull(polled);
        assertEquals(DeliveryCommandFixtures.mailboxKey(), polled.adapterMailboxKey());
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
    void backpressureIsScopedByAdapterMailboxQueue() {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(1);
        claimMailbox(handoff, "mailbox-1", "consumer-1", 1L);
        claimMailbox(handoff, "mailbox-2", "consumer-2", 1L);

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(new AdapterMailboxDeliveryOffer(
                        "mailbox-1",
                        List.of(DeliveryCommandFixtures.command("msg-1", "worker-1", null))
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(new AdapterMailboxDeliveryOffer(
                        "mailbox-2",
                        List.of(DeliveryCommandFixtures.command("msg-2", "worker-2", null))
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    @Test
    void staleMailboxQueueDoesNotBlockActiveMailboxPoll() throws Exception {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(2);
        claimMailbox(handoff, "mailbox-stale", "consumer-stale", 1L);
        claimMailbox(handoff, "mailbox-active", "consumer-active", 1L);
        handoff.offer(new AdapterMailboxDeliveryOffer(
                "mailbox-stale",
                List.of(DeliveryCommandFixtures.command("msg-stale", "worker-stale", null))
        ));
        handoff.offer(new AdapterMailboxDeliveryOffer(
                "mailbox-active",
                List.of(DeliveryCommandFixtures.command("msg-active", "worker-active", null))
        ));

        handoff.releaseMailboxConsumer(new AdapterMailboxConsumerLease(
                "mailbox-stale",
                "consumer-stale",
                1L,
                0L
        ));
        DeliveryCommandBatch active = handoff.poll(0L);

        assertNotNull(active);
        assertEquals("mailbox-active", active.adapterMailboxKey());
        assertEquals(List.of("msg-active"), DeliveryCommandFixtures.messages(active));
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
    void offerWithoutMailboxConsumerReturnsUnavailable() {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(1);

        assertEquals(
                List.of(DispatchOutcomeStatus.UNAVAILABLE),
                handoff.offer(DeliveryCommandFixtures.offer(
                        DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    @Test
    void staleReleaseDoesNotRemoveNewMailboxConsumerLease() {
        InMemoryTransportDeliveryCommandHandoff handoff = new InMemoryTransportDeliveryCommandHandoff(2);
        claim(handoff, "consumer-old", 1L);
        claim(handoff, "consumer-new", 2L);

        handoff.releaseMailboxConsumer(new AdapterMailboxConsumerLease(
                DeliveryCommandFixtures.mailboxKey(),
                "consumer-old",
                1L,
                0L
        ));

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
        claim(handoff, "consumer-1", 1L);
    }

    private static void claim(InMemoryTransportDeliveryCommandHandoff handoff,
                              String workerId,
                              String endpointLeaseId,
                              String consumerEvidenceId,
                              String endpointDriverId) {
        claim(handoff, consumerEvidenceId, 1L);
    }

    private static void claim(InMemoryTransportDeliveryCommandHandoff handoff,
                              String consumerId,
                              long generation) {
        claimMailbox(handoff, DeliveryCommandFixtures.mailboxKey(), consumerId, generation);
    }

    private static void claimMailbox(InMemoryTransportDeliveryCommandHandoff handoff,
                                     String mailboxKey,
                                     String consumerId,
                                     long generation) {
        handoff.claimMailboxConsumer(new AdapterMailboxConsumerLease(
                mailboxKey,
                consumerId,
                generation,
                System.currentTimeMillis() + 30_000L
        ));
    }
}
