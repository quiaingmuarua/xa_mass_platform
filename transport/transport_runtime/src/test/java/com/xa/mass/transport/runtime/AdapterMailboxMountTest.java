package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerAvailability;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.ClaimedDispatchRoutingBatch;
import com.xa.mass.transport.runtime.delivery.DispatchHandoffReference;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.DispatchRoutingBatch;
import com.xa.mass.transport.runtime.delivery.DispatchRoutingItem;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import com.xa.mass.transport.runtime.delivery.TransportDispatchHandoff;
import com.xa.mass.transport.routing.RoutingTarget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterMailboxMountTest {

    @Test
    void drainsOwnMailboxDispatchesAndCompletesBatch() throws Exception {
        RecordingHandoff handoff = new RecordingHandoff();
        RecordingCommandExecutor commandExecutor = new RecordingCommandExecutor(DispatchOutcomeStatus.DELIVERED);
        AdapterMailboxMount mount = mount("mailbox-a", commandExecutor, handoff, event -> true);
        ClaimedDispatchRoutingBatch batch = claimed(
                "mailbox-a",
                item("msg-1", "worker-1")
        );

        try {
            mount.start();
            handoff.enqueue(batch);

            assertTrue(commandExecutor.await(2, TimeUnit.SECONDS));
            assertTrue(handoff.awaitComplete(2, TimeUnit.SECONDS));

            assertTrue(handoff.polledMailboxes().contains("mailbox-a"));
            assertEquals(List.of("cmd-msg-1"), itemIds(commandExecutor.dispatched()));
            assertEquals(List.of("cmd-msg-1"), itemIds(handoff.completed().getFirst()));
        } finally {
            mount.stop();
        }
    }

    @Test
    void retryableOutcomeEmitsFailureBeforeCompletingBatch() throws Exception {
        RecordingHandoff handoff = new RecordingHandoff();
        RecordingCommandExecutor commandExecutor = new RecordingCommandExecutor(DispatchOutcomeStatus.UNAVAILABLE);
        RecordingFailureHandler failures = new RecordingFailureHandler();
        AdapterMailboxMount mount = mount("mailbox-a", commandExecutor, handoff, failures);
        ClaimedDispatchRoutingBatch batch = claimed(
                "mailbox-a",
                item("msg-1", "worker-1")
        );

        try {
            mount.start();
            handoff.enqueue(batch);

            assertTrue(commandExecutor.await(2, TimeUnit.SECONDS));
            assertTrue(handoff.awaitComplete(2, TimeUnit.SECONDS));

            assertEquals(1, failures.events().size());
            assertEquals("worker-1", failures.events().getFirst().outcome().getSelectedWorkerId());
            assertEquals(DispatchOutcomeStatus.UNAVAILABLE, failures.events().getFirst().outcome().getStatus());
        } finally {
            mount.stop();
        }
    }

    @Test
    void stopCancelsMailboxDrainBeforeReleasingAvailability() {
        List<String> events = new ArrayList<>();
        RecordingFutureExecutor executor = new RecordingFutureExecutor(events);
        RecordingAvailabilityRegistry registry = new RecordingAvailabilityRegistry(events);
        TransportBinding binding = TransportBinding.builder("websocket", WorkerTransportHints.REALTIME, items -> List.of())
                .adapterMailboxKey("mailbox-a")
                .protocol("websocket")
                .build();
        MailboxConsumerAvailabilityPublisher availabilityPublisher = new MailboxConsumerAvailabilityPublisher(
                binding,
                registry,
                30_000L,
                executor
        );
        AdapterMailboxMount mount = new AdapterMailboxMount(
                binding,
                new RecordingHandoff(),
                availabilityPublisher,
                event -> true,
                executor
        );

        mount.start();
        mount.stop();

        assertTrue(events.indexOf("cancel:1") >= 0, "drain future should be cancelled");
        assertTrue(events.indexOf("release:mailbox-a") >= 0, "availability should be released");
        assertTrue(events.indexOf("cancel:1") < events.indexOf("release:mailbox-a"),
                "mailbox drain must stop before availability is released");
    }

    private static ClaimedDispatchRoutingBatch claimed(String adapterMailboxKey, DispatchRoutingItem item) {
        return new ClaimedDispatchRoutingBatch(
                new DispatchRoutingBatch(RoutingTarget.adapterMailbox(adapterMailboxKey), List.of(item)),
                List.of(new DispatchHandoffReference(adapterMailboxKey, item.deliveryId()))
        );
    }

    private static DispatchRoutingItem item(String messageId, String selectedWorkerId) {
        return new DispatchRoutingItem(
                "cmd-" + messageId,
                selectedWorkerId,
                "{\"input\":1}",
                "corr-" + messageId,
                0L,
                System.currentTimeMillis()
        );
    }

    private static List<String> itemIds(ClaimedDispatchRoutingBatch batch) {
        return batch.items().stream().map(DispatchRoutingItem::deliveryId).toList();
    }

    private static AdapterMailboxMount mount(String adapterMailboxKey,
                                             RecordingCommandExecutor commandExecutor,
                                             RecordingHandoff handoff,
                                             TransportDeliveryFailureHandler failureHandler) {
        TransportBinding binding = TransportBinding.builder("websocket", WorkerTransportHints.REALTIME, commandExecutor)
                .adapterMailboxKey(adapterMailboxKey)
                .protocol("websocket")
                .build();
        return new AdapterMailboxMount(
                binding,
                handoff,
                null,
                failureHandler,
                new ThreadedRuntimeTaskExecutor()
        );
    }

    private static final class RecordingHandoff implements TransportDispatchHandoff {
        private final BlockingQueue<ClaimedDispatchRoutingBatch> ready = new LinkedBlockingQueue<>();
        private final List<String> polledMailboxes = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<ClaimedDispatchRoutingBatch> completed = java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch completeLatch = new CountDownLatch(1);

        private void enqueue(ClaimedDispatchRoutingBatch batch) {
            ready.add(batch);
        }

        @Override
        public List<DispatchOutcome> offer(DispatchRoutingBatch batch) {
            return List.of();
        }

        @Override
        public ClaimedDispatchRoutingBatch poll(String adapterMailboxKey, long timeoutMillis) throws InterruptedException {
            polledMailboxes.add(adapterMailboxKey);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            do {
                ClaimedDispatchRoutingBatch batch = ready.poll(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
                if (batch == null) {
                    return null;
                }
                if (adapterMailboxKey.equals(batch.adapterMailboxKey())) {
                    return batch;
                }
                ready.add(batch);
                long remaining = deadline - System.nanoTime();
                timeoutMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
            } while (timeoutMillis > 0L);
            return null;
        }

        @Override
        public void complete(ClaimedDispatchRoutingBatch batch, List<DispatchOutcome> outcomes) {
            completed.add(batch);
            completeLatch.countDown();
        }

        @Override
        public void shutdown() {
        }

        private boolean awaitComplete(long timeout, TimeUnit unit) throws InterruptedException {
            return completeLatch.await(timeout, unit);
        }

        private List<String> polledMailboxes() {
            synchronized (polledMailboxes) {
                return List.copyOf(polledMailboxes);
            }
        }

        private List<ClaimedDispatchRoutingBatch> completed() {
            synchronized (completed) {
                return List.copyOf(completed);
            }
        }
    }

    private static final class RecordingCommandExecutor implements com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor {
        private final DispatchOutcomeStatus status;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile ClaimedDispatchRoutingBatch dispatched;

        private RecordingCommandExecutor(DispatchOutcomeStatus status) {
            this.status = status;
        }

        @Override
        public List<DispatchOutcome> dispatch(List<DispatchRoutingItem> items) {
            dispatched = claimed("mailbox-a", items.getFirst());
            latch.countDown();
            return items.stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            status,
                            status != DispatchOutcomeStatus.DELIVERED,
                            status == DispatchOutcomeStatus.DELIVERED ? null : "test failure"))
                    .toList();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        private ClaimedDispatchRoutingBatch dispatched() {
            return dispatched;
        }
    }

    private static final class RecordingFailureHandler implements TransportDeliveryFailureHandler {
        private final List<TransportDeliveryFailureEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean handle(TransportDeliveryFailureEvent event) {
            events.add(event);
            return true;
        }

        private List<TransportDeliveryFailureEvent> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }
    }

    private static final class ThreadedRuntimeTaskExecutor implements RuntimeTaskExecutor {
        private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "adapter-mailbox-mount-test");
            thread.setDaemon(true);
            return thread;
        });

        @Override
        public Future<?> submit(Runnable task) {
            return executor.submit(task);
        }

        @Override
        public <T> Future<T> submit(java.util.concurrent.Callable<T> task) {
            return executor.submit(task);
        }

        @Override
        public void shutdown() {
            executor.shutdownNow();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return executor.awaitTermination(timeout, unit);
        }

        @Override
        public RuntimeTaskExecutorStatistics getStatistics() {
            return new RuntimeTaskExecutorStatistics(0, 0, 0, 0, 0, 1);
        }
    }

    private static final class RecordingAvailabilityRegistry implements AdapterMailboxConsumerRegistry {
        private final List<String> events;

        private RecordingAvailabilityRegistry(List<String> events) {
            this.events = events;
        }

        @Override
        public void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability availability) {
            events.add("claim:" + availability.adapterMailboxKey());
        }

        @Override
        public void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability availability) {
            events.add("release:" + availability.adapterMailboxKey());
        }
    }

    private static final class RecordingFutureExecutor implements RuntimeTaskExecutor {
        private final List<String> events;
        private final AtomicInteger sequence = new AtomicInteger();

        private RecordingFutureExecutor(List<String> events) {
            this.events = events;
        }

        @Override
        public Future<?> submit(Runnable task) {
            return new RecordingFuture<>(sequence.incrementAndGet(), events);
        }

        @Override
        public <T> Future<T> submit(java.util.concurrent.Callable<T> task) {
            return new RecordingFuture<>(sequence.incrementAndGet(), events);
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
            return new RuntimeTaskExecutorStatistics(sequence.get(), 0, 0, 0, 0, 1);
        }
    }

    private static final class RecordingFuture<T> implements Future<T> {
        private final int id;
        private final List<String> events;
        private boolean cancelled;

        private RecordingFuture(int id, List<String> events) {
            this.id = id;
            this.events = events;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            events.add("cancel:" + id);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public T get() {
            return null;
        }

        @Override
        public T get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
