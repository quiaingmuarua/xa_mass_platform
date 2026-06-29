package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransportResultIngressQueueTest {

    @Test
    void resultEntryRoundTripsThroughPrimitiveBackedQueue() throws Exception {
        InMemoryTransportResultIngressQueue queue = new InMemoryTransportResultIngressQueue(2);
        ResultIngressEntry entry = entry("task-result-json", "corr-1");

        assertTrue(queue.offer(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, entry));
        ResultIngressEntry polled = queue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, 1000L);

        assertNotNull(polled);
        assertEquals("task-result-json", polled.message().payload());
        assertEquals("corr-1", polled.message().resultCorrelationRef());
        assertEquals("trace-1", polled.diagnostics().get("traceId"));
        assertNull(queue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, 0L));
    }

    @Test
    void fullQueueRejectsWithoutDroppingExistingResult() throws Exception {
        InMemoryTransportResultIngressQueue queue = new InMemoryTransportResultIngressQueue(1);

        assertTrue(queue.offer(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, entry("payload-1", "msg-1")));
        assertFalse(queue.offer(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, entry("payload-2", "msg-2")));

        ResultIngressEntry polled = queue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, 1000L);

        assertNotNull(polled);
        assertEquals("msg-1", polled.message().resultCorrelationRef());
    }

    @Test
    void onlyDefaultResultQueueKeyIsAccepted() {
        InMemoryTransportResultIngressQueue queue = new InMemoryTransportResultIngressQueue(1);

        assertThrows(IllegalArgumentException.class, () -> queue.offer("other", entry("payload", "msg-1")));
        assertThrows(IllegalArgumentException.class, () -> queue.poll("other", 0L));
    }

    @Test
    void invalidReadyPayloadIsDiscarded() throws Exception {
        InMemoryTransportResultIngressQueue queue = new InMemoryTransportResultIngressQueue(2);
        queue.pushRawReadyValueForTest("missing-ref");

        ResultIngressEntry polled = queue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, 100L);

        assertNull(polled);
        assertNull(queue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, 0L));
    }

    @Test
    void shutdownRejectsOfferAndPoll() throws Exception {
        InMemoryTransportResultIngressQueue queue = new InMemoryTransportResultIngressQueue(1);
        queue.shutdown();

        assertFalse(queue.offer(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, entry("payload", "msg-1")));
        assertNull(queue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, 0L));
    }

    private static ResultIngressEntry entry(String payload, String resultCorrelationRef) {
        return new ResultIngressEntry(
                resultCorrelationRef,
                new ResultIngressMessage(
                        UUID.randomUUID().toString(),
                        resultCorrelationRef,
                        payload,
                        0L,
                        System.currentTimeMillis()
                ),
                new ResultIngressDiagnostics(Map.of("traceId", "trace-1"))
        );
    }
}
