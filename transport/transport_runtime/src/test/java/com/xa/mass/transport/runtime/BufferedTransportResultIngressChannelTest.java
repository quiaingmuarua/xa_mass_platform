package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;
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
        List<ResultIngressEntry> received = new CopyOnWriteArrayList<>();
        BufferedTransportResultIngressChannel channel = new BufferedTransportResultIngressChannel(entry -> {
            received.add(entry);
            latch.countDown();
        });

        boolean accepted = channel.ingest(entry("payload-1", "message-1"));

        assertTrue(accepted);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "delegate must receive the entry within 2s");
        assertEquals("payload-1", received.getFirst().message().payload());

        channel.shutdown();
    }

    @Test
    void shutdownDrainsAllQueuedItems() throws InterruptedException {
        int itemCount = 50;
        List<String> processed = new CopyOnWriteArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        BufferedTransportResultIngressChannel channel = new BufferedTransportResultIngressChannel(entry -> {
            try {
                startLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processed.add(entry.message().resultCorrelationRef());
        }, 100);
        for (int i = 0; i < itemCount; i++) {
            assertTrue(channel.ingest(entry("payload", "msg-" + i)));
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
        BufferedTransportResultIngressChannel channel = new BufferedTransportResultIngressChannel(entry -> {
            received.add(entry.message().resultCorrelationRef());
            if ("msg-0".equals(entry.message().resultCorrelationRef())) {
                firstDispatchStarted.countDown();
                try {
                    blocker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if ("msg-overflow".equals(entry.message().resultCorrelationRef())) {
                synchronousFallback.countDown();
            }
        }, 1);

        assertTrue(channel.ingest(entry("payload", "msg-0")));
        assertTrue(firstDispatchStarted.await(1, TimeUnit.SECONDS), "drainer must start processing the first item");
        assertTrue(channel.ingest(entry("payload", "msg-1")));
        assertTrue(channel.ingest(entry("payload", "msg-overflow")));

        assertTrue(synchronousFallback.await(1, TimeUnit.SECONDS), "overflow item must be ingested synchronously");

        blocker.countDown();
        channel.shutdown();
        assertTrue(received.contains("msg-overflow"));
    }

    @Test
    void delegateFailureIsBestEffortAndNotRetriedByTransport() throws InterruptedException {
        CountDownLatch firstAttempt = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        BufferedTransportResultIngressChannel channel = new BufferedTransportResultIngressChannel(entry -> {
            attempts.incrementAndGet();
            firstAttempt.countDown();
            throw new IllegalStateException("delegate down");
        }, 2);

        assertTrue(channel.ingest(entry("payload", "msg-1")));

        assertTrue(firstAttempt.await(2, TimeUnit.SECONDS), "delegate must be called once");
        channel.shutdown();
        assertEquals(1, attempts.get());
    }

    @Test
    void nullEnvelopeReturnsFalseWithoutEnqueuing() {
        AtomicInteger delegateCalls = new AtomicInteger();
        BufferedTransportResultIngressChannel channel =
                new BufferedTransportResultIngressChannel(entry -> {
                    delegateCalls.incrementAndGet();
                });

        assertFalse(channel.ingest(null));

        channel.shutdown();
        assertEquals(0, delegateCalls.get());
    }

    @Test
    void ingestAfterShutdownReturnsFalse() {
        BufferedTransportResultIngressChannel channel =
                new BufferedTransportResultIngressChannel(entry -> {
                });
        channel.shutdown();

        assertFalse(channel.ingest(entry("payload", "msg-1")));
    }

    private static ResultIngressEntry entry(String payload, String resultCorrelationRef) {
        return new ResultIngressEntry(
                resultCorrelationRef,
                new ResultIngressMessage(
                        java.util.UUID.randomUUID().toString(),
                        resultCorrelationRef,
                        payload,
                        0L,
                        System.currentTimeMillis()
                ),
                new ResultIngressDiagnostics(Map.of("traceId", resultCorrelationRef + "-trace"))
        );
    }
}
