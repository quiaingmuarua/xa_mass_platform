package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransportDispatchHandoffTest {

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTransportDispatchHandoff(0));
    }

    @Test
    void offerAndPollRoundTrip() throws Exception {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(2);
        claim(handoff, "consumer-1", 1L);

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(DispatchRoutingFixtures.batch(
                        DispatchRoutingFixtures.item("msg-1", "worker-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );

        List<DispatchRoutingItem> polled = handoff.poll(DispatchRoutingFixtures.mailboxKey(), 64, 100L);
        assertEquals("worker-1", polled.getFirst().selectedWorkerId());
        assertEquals(List.of("msg-1"), DispatchRoutingFixtures.messages(polled));
    }

    @Test
    void fullQueueReturnsBackpressureWithoutBlockingProducer() {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(1);
        claim(handoff, "consumer-1", 1L);
        handoff.offer(DispatchRoutingFixtures.batch(
                DispatchRoutingFixtures.item("msg-1", "worker-1")
        ));

        assertEquals(
                List.of(DispatchOutcomeStatus.BACKPRESSURE),
                handoff.offer(DispatchRoutingFixtures.batch(
                        DispatchRoutingFixtures.item("msg-2", "worker-2")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    @Test
    void backpressureIsScopedByAdapterMailboxQueue() {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(1);
        claimMailbox(handoff, "mailbox-1", "consumer-1", 1L);
        claimMailbox(handoff, "mailbox-2", "consumer-2", 1L);

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(new DispatchRoutingBatch(
                        com.xa.mass.transport.routing.RoutingTarget.adapterMailbox("mailbox-1"),
                        List.of(DispatchRoutingFixtures.item("msg-1", "worker-1"))
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(new DispatchRoutingBatch(
                        com.xa.mass.transport.routing.RoutingTarget.adapterMailbox("mailbox-2"),
                        List.of(DispatchRoutingFixtures.item("msg-2", "worker-2"))
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    @Test
    void pollIsDestructiveAndDoesNotRequireAck() throws Exception {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(2);
        claim(handoff, "consumer-1", 1L);
        handoff.offer(DispatchRoutingFixtures.batch(
                DispatchRoutingFixtures.item("msg-1", "worker-1")
        ));

        List<DispatchRoutingItem> batch = handoff.poll(DispatchRoutingFixtures.mailboxKey(), 64, 100L);

        assertEquals(List.of("msg-1"), DispatchRoutingFixtures.messages(batch));
        assertTrue(handoff.poll(DispatchRoutingFixtures.mailboxKey(), 64, 0L).isEmpty());
    }

    @Test
    void offerWithoutMailboxConsumerReturnsUnavailable() {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(1);

        assertEquals(
                List.of(DispatchOutcomeStatus.UNAVAILABLE),
                handoff.offer(DispatchRoutingFixtures.batch(
                        DispatchRoutingFixtures.item("msg-1", "worker-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    @Test
    void staleAvailabilityRemovalDoesNotRemoveNewMailboxConsumer() {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(2);
        claim(handoff, "consumer-old", 1L);
        claim(handoff, "consumer-new", 2L);

        handoff.removeMailboxConsumerAvailability(new AdapterMailboxConsumerAvailability(
                DispatchRoutingFixtures.mailboxKey(),
                "consumer-old",
                1L,
                0L
        ));

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(DispatchRoutingFixtures.batch(
                        DispatchRoutingFixtures.item("msg-1", "worker-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    private static void claim(InMemoryTransportDispatchHandoff handoff,
                              String consumerId,
                              long generation) {
        claimMailbox(handoff, DispatchRoutingFixtures.mailboxKey(), consumerId, generation);
    }

    private static void claimMailbox(InMemoryTransportDispatchHandoff handoff,
                                     String mailboxKey,
                                     String consumerId,
                                     long generation) {
        handoff.publishMailboxConsumerAvailability(new AdapterMailboxConsumerAvailability(
                mailboxKey,
                consumerId,
                generation,
                System.currentTimeMillis() + 30_000L
        ));
    }
}
