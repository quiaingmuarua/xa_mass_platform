package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterDispatchQueueConsumerLoopTest {

    @Test
    void consumerLoopPollsDirectDispatchQueueAndDispatchesItems() throws Exception {
        InMemoryTransportDispatchHandoff queue = new InMemoryTransportDispatchHandoff(10);
        DispatchMessage item = item("msg-1", "worker-1");
        queue.offer("mailbox-a", List.of(item));
        RecordingExecutor executor = new RecordingExecutor(items -> List.of(
                DispatchOutcomeFactory.delivered(items.getFirst())
        ));
        AdapterDispatchQueueConsumerLoop loop = new AdapterDispatchQueueConsumerLoop(
                "mailbox-a",
                queue,
                executor,
                ignored -> true,
                executor,
                64,
                10L
        );

        loop.start();

        assertTrue(executor.awaitDispatch(2, TimeUnit.SECONDS), "dispatch should be called");
        loop.stop();

        assertEquals(List.of("worker-1"), executor.dispatchedWorkerIds());
    }

    @Test
    void retryableOutcomesAreEmittedToFailureHandler() throws Exception {
        InMemoryTransportDispatchHandoff queue = new InMemoryTransportDispatchHandoff(10);
        DispatchMessage item = item("msg-1", "worker-1");
        queue.offer("mailbox-a", List.of(item));
        RecordingFailureHandler failureHandler = new RecordingFailureHandler(1);
        RecordingExecutor executor = new RecordingExecutor(items -> List.of(
                DispatchOutcomeFactory.unavailable(items.getFirst(), "no endpoint")
        ));
        AdapterDispatchQueueConsumerLoop loop = new AdapterDispatchQueueConsumerLoop(
                "mailbox-a",
                queue,
                executor,
                failureHandler::handle,
                executor,
                64,
                10L
        );

        loop.start();

        assertTrue(failureHandler.await(2, TimeUnit.SECONDS), "failure evidence should be emitted");
        loop.stop();

        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE),
                failureHandler.outcomes().stream().map(DispatchOutcome::getStatus).toList());
    }

    private static DispatchMessage item(String messageId, String selectedWorkerId) {
        return new DispatchMessage(
                "cmd-" + messageId,
                selectedWorkerId,
                "{\"messageId\":\"" + messageId + "\"}",
                "corr-" + messageId,
                0L,
                10L
        );
    }

    private static final class RecordingFailureHandler {
        private final CountDownLatch latch;
        private final List<DispatchOutcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());

        private RecordingFailureHandler(int expectedCalls) {
            this.latch = new CountDownLatch(expectedCalls);
        }

        private boolean handle(TransportDeliveryFailureEvent event) {
            outcomes.add(event.outcome());
            latch.countDown();
            return true;
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        private List<DispatchOutcome> outcomes() {
            return outcomes;
        }
    }

    private static final class RecordingExecutor implements RuntimeTaskExecutor, AdapterCommandExecutor {
        private final AdapterCommandExecutor delegate;
        private final CountDownLatch dispatchLatch = new CountDownLatch(1);
        private final List<String> dispatchedWorkerIds = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile Future<?> submitted;

        private RecordingExecutor(AdapterCommandExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
            dispatchedWorkerIds.addAll(items.stream().map(DispatchMessage::selectedWorkerId).toList());
            try {
                return delegate.dispatch(items);
            } finally {
                dispatchLatch.countDown();
            }
        }

        @Override
        public Future<?> submit(Runnable task) {
            submitted = CompletableFuture.runAsync(task);
            return submitted;
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            CompletableFuture<T> future = new CompletableFuture<>();
            submitted = future;
            CompletableFuture.runAsync(() -> {
                try {
                    future.complete(task.call());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        }

        @Override
        public void shutdown() {
            if (submitted != null) {
                submitted.cancel(true);
            }
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public RuntimeTaskExecutorStatistics getStatistics() {
            return new RuntimeTaskExecutorStatistics(0, 0, 0, 0, 0, 1);
        }

        private boolean awaitDispatch(long timeout, TimeUnit unit) throws InterruptedException {
            return dispatchLatch.await(timeout, unit);
        }

        private List<String> dispatchedWorkerIds() {
            return dispatchedWorkerIds;
        }
    }
}
