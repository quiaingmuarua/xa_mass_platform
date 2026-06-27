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

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-1", "worker-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );

        List<DispatchMessage> polled = handoff.poll(DispatchMessageFixtures.mailboxKey(), 64, 100L);
        assertEquals("worker-1", polled.getFirst().selectedWorkerId());
        assertEquals(List.of("msg-1"), DispatchMessageFixtures.messages(polled));
    }

    @Test
    void fullQueueReturnsBackpressureWithoutBlockingProducer() {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(1);
        handoff.offer(DispatchMessageFixtures.batch(
                DispatchMessageFixtures.item("msg-1", "worker-1")
        ));

        assertEquals(
                List.of(DispatchOutcomeStatus.BACKPRESSURE),
                handoff.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-2", "worker-2")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    @Test
    void backpressureIsScopedByAdapterMailboxQueue() {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(1);

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(new AdapterMailboxDispatchBatch(
                        "mailbox-1",
                        List.of(DispatchMessageFixtures.item("msg-1", "worker-1"))
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(new AdapterMailboxDispatchBatch(
                        "mailbox-2",
                        List.of(DispatchMessageFixtures.item("msg-2", "worker-2"))
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }

    @Test
    void pollIsDestructiveAndDoesNotRequireAck() throws Exception {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(2);
        handoff.offer(DispatchMessageFixtures.batch(
                DispatchMessageFixtures.item("msg-1", "worker-1")
        ));

        List<DispatchMessage> batch = handoff.poll(DispatchMessageFixtures.mailboxKey(), 64, 100L);

        assertEquals(List.of("msg-1"), DispatchMessageFixtures.messages(batch));
        assertTrue(handoff.poll(DispatchMessageFixtures.mailboxKey(), 64, 0L).isEmpty());
    }

    @Test
    void offerDoesNotRequireMailboxConsumerAvailability() {
        InMemoryTransportDispatchHandoff handoff = new InMemoryTransportDispatchHandoff(1);

        assertEquals(
                List.of(DispatchOutcomeStatus.QUEUED),
                handoff.offer(DispatchMessageFixtures.batch(
                        DispatchMessageFixtures.item("msg-1", "worker-1")
                )).stream().map(outcome -> outcome.getStatus()).toList()
        );
    }
}
