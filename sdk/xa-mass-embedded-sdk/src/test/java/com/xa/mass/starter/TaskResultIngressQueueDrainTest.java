package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;
import com.xa.mass.transport.runtime.InMemoryTransportResultIngressQueue;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultIngressQueueDrainTest {

    @Test
    void starterDrainPollsTransportResultQueueIntoHandler() throws Exception {
        InMemoryTransportResultIngressQueue queue = new InMemoryTransportResultIngressQueue(10);
        RecordingExecutor executor = new RecordingExecutor();
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<ResultIngressEntry> captured = new AtomicReference<>();
        TaskResultIngressQueueDrain drain = new TaskResultIngressQueueDrain(
                timeoutMillis -> queue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, timeoutMillis),
                entry -> {
            captured.set(entry);
            handled.countDown();
        }, executor);
        ResultIngressEntry entry = entry("corr-1");

        try {
            drain.start();
            assertTrue(queue.offer(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, entry));

            assertTrue(handled.await(3, TimeUnit.SECONDS), "result queue entry should be drained");
            assertEquals(entry, captured.get());
        } finally {
            drain.stop();
            executor.shutdown();
            executor.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private static ResultIngressEntry entry(String correlationRef) {
        return new ResultIngressEntry(
                correlationRef,
                new ResultIngressMessage(
                        "result-" + correlationRef,
                        correlationRef,
                        "{\"replyRef\":\"" + correlationRef + "\"}",
                        0L,
                        1L
                ),
                ResultIngressDiagnostics.empty()
        );
    }

    private static final class RecordingExecutor implements RuntimeTaskExecutor {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();

        @Override
        public Future<?> submit(Runnable task) {
            return delegate.submit(task);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(task);
        }

        @Override
        public void shutdown() {
            delegate.shutdownNow();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public RuntimeTaskExecutorStatistics getStatistics() {
            return new RuntimeTaskExecutorStatistics(0, 0, 0, 0, 0, 1);
        }
    }
}
