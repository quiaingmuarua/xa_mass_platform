package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.BatchDispatcher.DispatchStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.BatchDispatcher.DispatchStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.BatchDispatcher.DispatchStatus.FULL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BatchDispatcherTest {

    @Test
    void queuedModeBlocksThenTakesAndDrainsOneFifoBatch()
            throws Exception {
        List<List<String>> observed = new CopyOnWriteArrayList<>();
        CountDownLatch processed = new CountDownLatch(1);
        BatchDispatcher<String> dispatcher = queued(
                4,
                3,
                batch -> {
                    observed.add(batch);
                    processed.countDown();
                    return BatchProcessResult.completed();
                }
        );

        dispatcher.start();
        awaitState(dispatcher.thread(), Thread.State.WAITING);
        assertThat(dispatcher.tryDispatch(List.of("one", "two", "three")))
                .isEqualTo(ACCEPTED);
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(observed).containsExactly(
                List.of("one", "two", "three")
        );
    }

    @Test
    void selectedItemsReturnBehindAlreadyQueuedWork() throws Exception {
        List<List<String>> observed = new CopyOnWriteArrayList<>();
        CountDownLatch processed = new CountDownLatch(2);
        AtomicInteger attempts = new AtomicInteger();
        BatchDispatcher<String> dispatcher = queued(
                4,
                3,
                batch -> {
                    observed.add(batch);
                    processed.countDown();
                    if (attempts.getAndIncrement() == 0) {
                        return BatchProcessResult.requeue(
                                WorkerDeliveryAdapterErrorCode
                                        .WORKER_DELIVERY_RETRY_LATER,
                                List.of(0, 2)
                        );
                    }
                    return BatchProcessResult.completed();
                }
        );
        dispatcher.tryDispatch(List.of(
                "one",
                "two",
                "three",
                "later"
        ));

        dispatcher.start();
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(observed).containsExactly(
                List.of("one", "two", "three"),
                List.of("later", "one", "three")
        );
    }

    @Test
    void unavailableBatchReturnsToQueueTailWithoutInlineRetry()
            throws Exception {
        List<String> attempts = new CopyOnWriteArrayList<>();
        CountDownLatch processed = new CountDownLatch(3);
        AtomicInteger firstAttempts = new AtomicInteger();
        BatchDispatcher<String> dispatcher = queued(
                4,
                1,
                batch -> {
                    String item = batch.get(0);
                    attempts.add(item);
                    processed.countDown();
                    if (item.equals("first")
                            && firstAttempts.getAndIncrement() == 0) {
                        throw failure(
                                WorkerDeliveryAdapterErrorCode
                                        .REMOTE_API_UNAVAILABLE
                        );
                    }
                    return BatchProcessResult.completed();
                }
        );
        dispatcher.tryDispatch(List.of("first", "later"));

        dispatcher.start();
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(attempts).containsExactly("first", "later", "first");
    }

    @Test
    void protocolFailureDropsCurrentBatchAndContinues() throws Exception {
        List<String> attempts = new CopyOnWriteArrayList<>();
        CountDownLatch processed = new CountDownLatch(2);
        BatchDispatcher<String> dispatcher = queued(
                4,
                1,
                batch -> {
                    String item = batch.get(0);
                    attempts.add(item);
                    processed.countDown();
                    if (item.equals("bad")) {
                        throw failure(
                                WorkerDeliveryAdapterErrorCode
                                        .REMOTE_API_PROTOCOL_ERROR
                        );
                    }
                    return BatchProcessResult.completed();
                }
        );
        dispatcher.tryDispatch(List.of("bad", "next"));

        dispatcher.start();
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(attempts).containsExactly("bad", "next");
    }

    @Test
    void unexpectedFailureDropsCurrentBatchAndContinues() throws Exception {
        List<String> attempts = new CopyOnWriteArrayList<>();
        CountDownLatch processed = new CountDownLatch(2);
        BatchDispatcher<String> dispatcher = queued(
                4,
                1,
                batch -> {
                    String item = batch.get(0);
                    attempts.add(item);
                    processed.countDown();
                    if (item.equals("bad")) {
                        throw new IllegalStateException("unknown outcome");
                    }
                    return BatchProcessResult.completed();
                }
        );
        dispatcher.tryDispatch(List.of("bad", "next"));

        dispatcher.start();
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(attempts).containsExactly("bad", "next");
    }

    @Test
    void pullingModeProcessesRetrySliceBeforeOneFreshBatch()
            throws Exception {
        List<List<String>> observed = new CopyOnWriteArrayList<>();
        CountDownLatch processed = new CountDownLatch(2);
        AtomicInteger freshCalls = new AtomicInteger();
        BatchDispatcher<String> dispatcher = pulling(
                4,
                2,
                () -> freshCalls.getAndIncrement() == 0
                        ? List.of("fresh")
                        : List.of(),
                batch -> {
                    observed.add(batch);
                    processed.countDown();
                    return BatchProcessResult.completed();
                }
        );
        dispatcher.tryDispatch(List.of("retry"));

        dispatcher.start();
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(observed).containsExactly(
                List.of("retry"),
                List.of("fresh")
        );
    }

    @Test
    void freshSourceFailureEndsIterationWithoutCreatingABatch()
            throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        List<List<String>> observed = new CopyOnWriteArrayList<>();
        CountDownLatch processed = new CountDownLatch(1);
        BatchDispatcher<String> dispatcher = pulling(
                4,
                2,
                () -> {
                    int sourceCall = sourceCalls.getAndIncrement();
                    if (sourceCall == 0) {
                        throw failure(
                                WorkerDeliveryAdapterErrorCode
                                        .REMOTE_API_UNAVAILABLE
                        );
                    }
                    return sourceCall == 1
                            ? List.of("fresh")
                            : List.of();
                },
                batch -> {
                    observed.add(batch);
                    processed.countDown();
                    return BatchProcessResult.completed();
                }
        );

        dispatcher.start();
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(sourceCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(observed).containsExactly(List.of("fresh"));
    }

    @Test
    void retryOnlyTrafficUsesIdleBackoffInsteadOfHotLooping()
            throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BatchDispatcher<String> dispatcher = BatchDispatcher.pulling(
                "adapter-1",
                "test",
                2,
                1,
                Duration.ofMillis(300),
                List::of,
                batch -> {
                    attempts.incrementAndGet();
                    return BatchProcessResult.requeue(
                            WorkerDeliveryAdapterErrorCode
                                    .WORKER_DELIVERY_RETRY_LATER,
                            List.of(0)
                    );
                }
        );
        dispatcher.tryDispatch(List.of("retry"));

        dispatcher.start();
        await(() -> attempts.get() == 1);
        Thread.sleep(100);
        stop(dispatcher);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void admissionKeepsOneWholeBatchBeyondTheSoftThreshold() {
        BatchDispatcher<String> dispatcher = queued(
                3,
                3,
                batch -> BatchProcessResult.completed()
        );

        assertThat(dispatcher.tryDispatch(List.of("one", "two")))
                .isEqualTo(ACCEPTED);
        assertThat(dispatcher.tryDispatch(List.of(
                "three",
                "four",
                "five"
        ))).isEqualTo(ACCEPTED);
        assertThat(dispatcher.tryDispatch(List.of("six"))).isEqualTo(FULL);

        dispatcher.stopIngress();
        assertThat(dispatcher.tryDispatch(List.of("late")))
                .isEqualTo(CLOSED);
    }

    @Test
    void concurrentProducersAdmitOrRejectWholeBatches() throws Exception {
        int producerCount = 8;
        CountDownLatch ready = new CountDownLatch(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producerCount);
        List<BatchDispatcher.DispatchStatus> statuses =
                new CopyOnWriteArrayList<>();
        BatchDispatcher<String> dispatcher = queued(
                8,
                8,
                batch -> BatchProcessResult.completed()
        );

        for (int index = 0; index < producerCount; index++) {
            int producer = index;
            Thread thread = new Thread(() -> {
                ready.countDown();
                awaitUnchecked(start);
                statuses.add(dispatcher.tryDispatch(List.of(
                        "item-" + producer + "-a",
                        "item-" + producer + "-b"
                )));
                done.countDown();
            });
            thread.start();
        }
        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(statuses).filteredOn(ACCEPTED::equals).hasSize(4);
        assertThat(statuses).filteredOn(FULL::equals).hasSize(4);
    }

    @Test
    void requeueCapacityFailureDropsTheWholeSelectedSubset()
            throws Exception {
        List<String> attempts = new CopyOnWriteArrayList<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch laterProcessed = new CountDownLatch(2);
        BatchDispatcher<String> dispatcher = queued(
                2,
                1,
                batch -> {
                    String item = batch.get(0);
                    attempts.add(item);
                    if (item.equals("first")) {
                        firstEntered.countDown();
                        awaitUnchecked(releaseFirst);
                        return BatchProcessResult.requeue(
                                WorkerDeliveryAdapterErrorCode
                                        .WORKER_DELIVERY_RETRY_LATER,
                                List.of(0)
                        );
                    }
                    laterProcessed.countDown();
                    return BatchProcessResult.completed();
                }
        );
        dispatcher.tryDispatch(List.of("first"));

        dispatcher.start();
        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatcher.tryDispatch(List.of("later-1", "later-2")))
                .isEqualTo(ACCEPTED);
        releaseFirst.countDown();
        assertThat(laterProcessed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(attempts).containsExactly(
                "first",
                "later-1",
                "later-2"
        );
    }

    @Test
    void rejectsInvalidBoundsAndProcessResults() {
        assertThatThrownBy(() -> BatchDispatcher.queued(
                "adapter-1",
                "test",
                0,
                1,
                Duration.ofMillis(10),
                batch -> BatchProcessResult.completed()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchDispatcher.queued(
                "adapter-1",
                "test",
                Integer.MAX_VALUE / 2 + 2,
                1,
                Duration.ofMillis(10),
                batch -> BatchProcessResult.completed()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchProcessResult.requeue(
                WorkerDeliveryAdapterErrorCode
                        .WORKER_DELIVERY_RETRY_LATER,
                List.of(1, 1)
        )).isInstanceOf(IllegalArgumentException.class);

        BatchDispatcher<String> dispatcher = queued(
                2,
                1,
                batch -> BatchProcessResult.completed()
        );
        assertThatThrownBy(() -> dispatcher.tryDispatch(List.of(
                "one",
                "two",
                "three"
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stopInterruptsBlockingTakeAndRejectsLaterIngress()
            throws Exception {
        BatchDispatcher<String> dispatcher = queued(
                2,
                2,
                batch -> BatchProcessResult.completed()
        );
        dispatcher.start();
        awaitState(dispatcher.thread(), Thread.State.WAITING);

        stop(dispatcher);

        assertThat(dispatcher.isAlive()).isFalse();
        assertThat(dispatcher.tryDispatch(List.of("late")))
                .isEqualTo(CLOSED);
    }

    @Test
    void errorEscapesAndClosesTheDispatcherIngress() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        BatchDispatcher<String> dispatcher = queued(
                2,
                1,
                batch -> {
                    throw new AssertionError("fatal");
                }
        );
        dispatcher.thread().setUncaughtExceptionHandler((thread, error) -> {
            uncaught.set(error);
            observed.countDown();
        });
        dispatcher.tryDispatch(List.of("fatal"));

        dispatcher.start();
        assertThat(observed.await(2, TimeUnit.SECONDS)).isTrue();
        dispatcher.thread().join(2_000);

        assertThat(uncaught.get()).isInstanceOf(AssertionError.class);
        assertThat(dispatcher.tryDispatch(List.of("late")))
                .isEqualTo(CLOSED);
    }

    private static BatchDispatcher<String> queued(
            int capacity,
            int batchSize,
            AdapterBatchProcessor<String> processor
    ) {
        return BatchDispatcher.queued(
                "adapter-1",
                "test",
                capacity,
                batchSize,
                Duration.ofMillis(10),
                processor
        );
    }

    private static BatchDispatcher<String> pulling(
            int capacity,
            int batchSize,
            java.util.function.Supplier<List<String>> source,
            AdapterBatchProcessor<String> processor
    ) {
        return BatchDispatcher.pulling(
                "adapter-1",
                "test",
                capacity,
                batchSize,
                Duration.ofMillis(10),
                source,
                processor
        );
    }

    private static WorkerDeliveryAdapterException failure(
            WorkerDeliveryAdapterErrorCode errorCode
    ) {
        return new WorkerDeliveryAdapterException(
                errorCode,
                "batchTest.process",
                null,
                null
        );
    }

    private static void stop(BatchDispatcher<?> dispatcher)
            throws InterruptedException {
        dispatcher.stopIngress();
        dispatcher.stop();
        dispatcher.thread().join(2_000);
    }

    private static void awaitState(Thread thread, Thread.State state) {
        await(() -> thread.getState() == state);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Condition did not become true");
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test thread was interrupted", error);
        }
    }
}
