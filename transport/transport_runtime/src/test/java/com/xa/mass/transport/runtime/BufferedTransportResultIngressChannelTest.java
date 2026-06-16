package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TransportResultIngressOutcome;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferedTransportResultIngressChannelTest {

    @Test
    void envelopeIsDeliveredAsynchronouslyToDelegate() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        List<TransportResultIngressEnvelope> received = new CopyOnWriteArrayList<>();
        BufferedTransportResultIngressChannel channel = new BufferedTransportResultIngressChannel(envelope -> {
            received.add(envelope);
            latch.countDown();
            return TransportResultIngressOutcome.ACKNOWLEDGED;
        });

        boolean accepted = channel.ingest(envelope("payload-1", "message-1"));

        assertTrue(accepted);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "delegate must receive the envelope within 2s");
        assertEquals("payload-1", received.getFirst().getPayload());

        channel.shutdown();
    }

    @Test
    void shutdownDrainsAllQueuedItems() throws InterruptedException {
        int itemCount = 50;
        List<String> processed = new CopyOnWriteArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        BufferedTransportResultIngressChannel channel = new BufferedTransportResultIngressChannel(envelope -> {
            try {
                startLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processed.add(envelope.getPartitionKey());
            return TransportResultIngressOutcome.ACKNOWLEDGED;
        }, 100);
        for (int i = 0; i < itemCount; i++) {
            assertTrue(channel.ingest(envelope("payload", "msg-" + i)));
        }

        startLatch.countDown();
        channel.shutdown();

        assertEquals(itemCount, processed.size(), "all queued items must be processed before shutdown returns");
    }

    @Test
    void fullQueueFallsBackToSynchronousDelegateInsteadOfDropping() throws InterruptedException {
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch firstDispatchStarted = new CountDownLatch(1);
        CountDownLatch synchronousFallback = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();
        BufferedTransportResultIngressChannel channel = new BufferedTransportResultIngressChannel(envelope -> {
            received.add(envelope.getPartitionKey());
            if ("msg-0".equals(envelope.getPartitionKey())) {
                firstDispatchStarted.countDown();
                try {
                    blocker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if ("msg-overflow".equals(envelope.getPartitionKey())) {
                synchronousFallback.countDown();
            }
            return TransportResultIngressOutcome.ACKNOWLEDGED;
        }, 1);

        assertTrue(channel.ingest(envelope("payload", "msg-0")));
        assertTrue(firstDispatchStarted.await(1, TimeUnit.SECONDS), "drainer must start processing the first item");
        assertTrue(channel.ingest(envelope("payload", "msg-1")));
        assertTrue(channel.ingest(envelope("payload", "msg-overflow")));

        assertTrue(synchronousFallback.await(1, TimeUnit.SECONDS), "overflow item must be ingested synchronously");

        blocker.countDown();
        channel.shutdown();
        assertTrue(received.contains("msg-overflow"));
    }

    @Test
    void retryableOutcomeRequeuesUntilAcked() throws InterruptedException {
        CountDownLatch secondAttempt = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        BufferedTransportResultIngressChannel channel = new BufferedTransportResultIngressChannel(envelope -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                return TransportResultIngressOutcome.RETRYABLE_FAILURE;
            }
            secondAttempt.countDown();
            return TransportResultIngressOutcome.ACKNOWLEDGED;
        }, 2);

        assertTrue(channel.ingest(envelope("payload", "msg-1")));

        assertTrue(secondAttempt.await(2, TimeUnit.SECONDS), "retryable result must be retried by buffer");
        assertEquals(2, attempts.get());
        channel.shutdown();
    }

    @Test
    void nullEnvelopeReturnsFalseWithoutEnqueuing() {
        BufferedTransportResultIngressChannel channel =
                new BufferedTransportResultIngressChannel(envelope -> TransportResultIngressOutcome.ACKNOWLEDGED);

        assertFalse(channel.ingest(null));
        assertEquals(0, channel.pendingCount());

        channel.shutdown();
    }

    @Test
    void ingestAfterShutdownReturnsFalse() {
        BufferedTransportResultIngressChannel channel =
                new BufferedTransportResultIngressChannel(envelope -> TransportResultIngressOutcome.ACKNOWLEDGED);
        channel.shutdown();

        assertFalse(channel.ingest(envelope("payload", "msg-1")));
    }

    private static TransportResultIngressEnvelope envelope(String payload, String partitionKey) {
        return TransportResultIngressEnvelope.received(
                payload,
                null,
                partitionKey,
                Map.of("traceId", partitionKey + "-trace")
        );
    }
}
