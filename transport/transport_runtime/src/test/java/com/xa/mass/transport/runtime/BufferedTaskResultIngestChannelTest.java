package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BufferedTaskResultIngestChannelTest {

    @Test
    void reportIsDeliveredAsynchronouslyToDelegate() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        List<TaskResultReport> received = new CopyOnWriteArrayList<>();
        TaskResultIngestChannel delegate = report -> {
            received.add(report);
            latch.countDown();
            return true;
        };

        BufferedTaskResultIngestChannel channel = new BufferedTaskResultIngestChannel(delegate);
        boolean accepted = channel.ingest(report("t1", "m1"));

        assertTrue(accepted);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "delegate must receive the report within 2s");
        assertEquals(1, received.size());
        assertEquals("t1", received.get(0).getTaskId());

        channel.shutdown();
    }

    @Test
    void envelopeIsDeliveredAsynchronouslyToDelegate() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        List<TransportResultEnvelope> received = new CopyOnWriteArrayList<>();
        TaskResultIngestChannel delegate = new TaskResultIngestChannel() {
            @Override
            public boolean ingest(TaskResultReport report) {
                return true;
            }

            @Override
            public boolean ingest(TransportResultEnvelope envelope) {
                received.add(envelope);
                latch.countDown();
                return true;
            }
        };

        BufferedTaskResultIngestChannel channel = new BufferedTaskResultIngestChannel(delegate);
        TransportResultEnvelope envelope = TransportResultEnvelope.fromReport("polling", "w1", "w1", report("t1", "m1"));
        boolean accepted = channel.ingest(envelope);

        assertTrue(accepted);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "delegate must receive the envelope within 2s");
        assertEquals(1, received.size());

        channel.shutdown();
    }

    @Test
    void shutdownDrainsAllQueuedItems() throws InterruptedException {
        int itemCount = 50;
        List<String> processed = new CopyOnWriteArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);

        TaskResultIngestChannel slowDelegate = report -> {
            try {
                startLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processed.add(report.getMessageId());
            return true;
        };

        BufferedTaskResultIngestChannel channel = new BufferedTaskResultIngestChannel(slowDelegate, 100);
        for (int i = 0; i < itemCount; i++) {
            assertTrue(channel.ingest(report("task", "msg-" + i)));
        }

        // Unblock the slow delegate and immediately call shutdown — must drain all.
        startLatch.countDown();
        channel.shutdown();

        assertEquals(itemCount, processed.size(), "all queued items must be processed before shutdown returns");
    }

    @Test
    void fullQueueReturnsFalseInsteadOfBlocking() {
        TaskResultIngestChannel blockingDelegate = report -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };

        int capacity = 4;
        BufferedTaskResultIngestChannel channel = new BufferedTaskResultIngestChannel(blockingDelegate, capacity);

        // First item is consumed by the drainer immediately; fill the remaining slots.
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < capacity + 4; i++) {
            results.add(channel.ingest(report("t", "m-" + i)));
        }

        // At least the last few should be false (queue full).
        assertTrue(results.contains(false), "ingest must return false when queue is full");

        channel.shutdown();
    }

    @Test
    void nullReportReturnsFalseWithoutEnqueuing() {
        BufferedTaskResultIngestChannel channel = new BufferedTaskResultIngestChannel(r -> true);

        assertFalse(channel.ingest((TaskResultReport) null));
        assertFalse(channel.ingest((TransportResultEnvelope) null));
        assertEquals(0, channel.pendingCount());

        channel.shutdown();
    }

    @Test
    void ingestAfterShutdownReturnsFalse() {
        BufferedTaskResultIngestChannel channel = new BufferedTaskResultIngestChannel(r -> true);
        channel.shutdown();

        assertFalse(channel.ingest(report("t1", "m1")));
    }

    private static TaskResultReport report(String taskId, String messageId) {
        return new TaskResultReport(taskId, messageId, true, "ok", null, Map.of());
    }
}
