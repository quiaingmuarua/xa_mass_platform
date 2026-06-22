package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.MailboxConsumerAvailabilityPublisher;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerAvailability;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterMailboxConsumerLoopTest {

    @Test
    void consumerLoopPollsDispatchesAndEmitsRetryableFailureEvidence() throws Exception {
        DispatchMessage item = item("msg-1", "worker-1");
        RecordingMailboxClient mailboxClient = new RecordingMailboxClient(List.of(item));
        RecordingFailureSink failureSink = new RecordingFailureSink(1);
        RecordingExecutor executor = new RecordingExecutor(items -> List.of(
                DispatchOutcomeFactory.unavailable(items.getFirst(), "no endpoint")
        ));
        AdapterMailboxConsumerLoop loop = new AdapterMailboxConsumerLoop(
                "mailbox-a",
                mailboxClient,
                executor,
                failureSink,
                null,
                executor,
                64,
                10L
        );

        loop.start();

        assertTrue(failureSink.await(2, TimeUnit.SECONDS), "failure evidence should be emitted");
        loop.stop();

        assertEquals(List.of("worker-1"), executor.dispatchedWorkerIds());
        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE),
                failureSink.outcomes().stream().map(DispatchOutcome::getStatus).toList());
        assertTrue(mailboxClient.polledMailboxes().contains("mailbox-a"));
    }

    @Test
    void finalHopExceptionIsConvertedToKnownFailureEvidence() throws Exception {
        DispatchMessage item = item("msg-1", "worker-1");
        RecordingMailboxClient mailboxClient = new RecordingMailboxClient(List.of(item));
        RecordingFailureSink failureSink = new RecordingFailureSink(1);
        RecordingExecutor executor = new RecordingExecutor(ignored -> {
            throw new IllegalStateException("send failed");
        });
        AdapterMailboxConsumerLoop loop = new AdapterMailboxConsumerLoop(
                "mailbox-a",
                mailboxClient,
                executor,
                failureSink,
                null,
                executor,
                64,
                10L
        );

        loop.start();

        assertTrue(failureSink.await(2, TimeUnit.SECONDS), "failure evidence should be emitted");
        loop.stop();

        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE),
                failureSink.outcomes().stream().map(DispatchOutcome::getStatus).toList());
    }

    @Test
    void startAndStopDelegateMailboxAvailabilityPublisher() {
        RecordingRuntimeTaskExecutor executor = new RecordingRuntimeTaskExecutor();
        RecordingMailboxRegistry registry = new RecordingMailboxRegistry();
        MailboxConsumerAvailabilityPublisher publisher = new MailboxConsumerAvailabilityPublisher(
                "mailbox-a",
                "consumer-a",
                registry,
                30_000L,
                executor
        );
        AdapterMailboxConsumerLoop loop = new AdapterMailboxConsumerLoop(
                "mailbox-a",
                (mailbox, maxItems, timeoutMillis) -> List.of(),
                items -> List.of(),
                ignored -> { },
                publisher,
                executor,
                64,
                10L
        );

        loop.start();
        loop.stop();

        assertEquals(List.of("claim:mailbox-a", "release:mailbox-a"), registry.events());
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

    private static final class RecordingMailboxClient implements AdapterMailboxClient {
        private final LinkedBlockingQueue<DispatchMessage> ready = new LinkedBlockingQueue<>();
        private final List<String> polledMailboxes = java.util.Collections.synchronizedList(new ArrayList<>());

        private RecordingMailboxClient(List<DispatchMessage> items) {
            ready.addAll(items);
        }

        @Override
        public List<DispatchMessage> poll(String adapterMailboxKey, int maxItems, long timeoutMillis) {
            polledMailboxes.add(adapterMailboxKey);
            DispatchMessage item = ready.poll();
            return item == null ? List.of() : List.of(item);
        }

        private List<String> polledMailboxes() {
            return polledMailboxes;
        }
    }

    private static final class RecordingFailureSink implements DeliveryFailureEvidenceSink {
        private final CountDownLatch latch;
        private final List<DispatchOutcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());

        private RecordingFailureSink(int expectedCalls) {
            this.latch = new CountDownLatch(expectedCalls);
        }

        @Override
        public void accept(List<DispatchOutcome> outcomes) {
            this.outcomes.addAll(outcomes);
            latch.countDown();
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
        private final List<String> dispatchedWorkerIds = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile Future<?> submitted;

        private RecordingExecutor(AdapterCommandExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
            dispatchedWorkerIds.addAll(items.stream().map(DispatchMessage::selectedWorkerId).toList());
            return delegate.dispatch(items);
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

        private List<String> dispatchedWorkerIds() {
            return dispatchedWorkerIds;
        }
    }

    private static final class RecordingRuntimeTaskExecutor implements RuntimeTaskExecutor {
        @Override
        public Future<?> submit(Runnable task) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public RuntimeTaskExecutorStatistics getStatistics() {
            return new RuntimeTaskExecutorStatistics(0, 0, 0, 0, 0, 1);
        }
    }

    private static final class RecordingMailboxRegistry implements AdapterMailboxConsumerRegistry {
        private final List<String> events = new ArrayList<>();

        @Override
        public void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
            events.add("claim:" + lease.adapterMailboxKey());
        }

        @Override
        public void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
            events.add("release:" + lease.adapterMailboxKey());
        }

        private List<String> events() {
            return events;
        }
    }
}
