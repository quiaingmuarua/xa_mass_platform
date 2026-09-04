package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchDispatcherTest {

    @Test
    void processesOneRetrySliceBeforeOneFreshBatch() throws Exception {
        List<List<String>> processed = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger freshCalls = new AtomicInteger();
        BatchDispatcher<String> dispatcher = pulling(
                () -> freshCalls.getAndIncrement() == 0
                        ? List.of("fresh")
                        : List.of(),
                batch -> {
                    processed.add(batch);
                    completed.countDown();
                    return BatchProcessResult.completed();
                }
        );
        assertThat(dispatcher.tryDispatch(List.of("retry"))).isEqualTo(
                BatchDispatcher.DispatchStatus.ACCEPTED
        );

        dispatcher.start();
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(processed).startsWith(
                List.of("retry"),
                List.of("fresh")
        );
    }

    @Test
    void selectedRetryItemsReturnToTheQueueTail() throws Exception {
        List<List<String>> processed = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(2);
        BatchDispatcher<String> dispatcher = pulling(
                List::of,
                batch -> {
                    processed.add(batch);
                    completed.countDown();
                    if (processed.size() == 1) {
                        return BatchProcessResult.requeue(
                                WorkerDeliveryAdapterErrorCode
                                        .WORKER_DELIVERY_RETRY_LATER,
                                List.of(1)
                        );
                    }
                    return BatchProcessResult.completed();
                }
        );
        assertThat(dispatcher.tryDispatch(List.of("done", "retry")))
                .isEqualTo(BatchDispatcher.DispatchStatus.ACCEPTED);

        dispatcher.start();
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(processed).startsWith(
                List.of("done", "retry"),
                List.of("retry")
        );
    }

    @Test
    void freshSourceFailureEndsOneIterationAndThenContinues()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch processed = new CountDownLatch(1);
        BatchDispatcher<String> dispatcher = pulling(
                () -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("remote unavailable");
                    }
                    return List.of("fresh");
                },
                batch -> {
                    processed.countDown();
                    return BatchProcessResult.completed();
                }
        );

        dispatcher.start();
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void unexpectedProcessorFailureDoesNotReplayTheBatch()
            throws Exception {
        AtomicInteger freshOrdinal = new AtomicInteger();
        List<String> processed = new CopyOnWriteArrayList<>();
        CountDownLatch secondCompleted = new CountDownLatch(1);
        BatchDispatcher<String> dispatcher = pulling(
                () -> {
                    int ordinal = freshOrdinal.getAndIncrement();
                    if (ordinal == 0) {
                        return List.of("first");
                    }
                    if (ordinal == 1) {
                        return List.of("second");
                    }
                    return List.of();
                },
                batch -> {
                    processed.addAll(batch);
                    if (batch.equals(List.of("first"))) {
                        throw new IllegalStateException("unknown side effect");
                    }
                    secondCompleted.countDown();
                    return BatchProcessResult.completed();
                }
        );

        dispatcher.start();
        assertThat(secondCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(processed).containsExactly("first", "second");
    }

    @Test
    void admissionIsFiniteAndStopRejectsLaterIngress() {
        BatchDispatcher<String> dispatcher = BatchDispatcher.pulling(
                "adapter-1",
                "delivery-command",
                2,
                2,
                Duration.ofSeconds(1),
                List::of,
                batch -> BatchProcessResult.completed()
        );

        assertThat(dispatcher.tryDispatch(List.of("one", "two")))
                .isEqualTo(BatchDispatcher.DispatchStatus.ACCEPTED);
        assertThat(dispatcher.tryDispatch(List.of("three")))
                .isEqualTo(BatchDispatcher.DispatchStatus.FULL);

        dispatcher.stopIngress();
        assertThat(dispatcher.tryDispatch(List.of("late")))
                .isEqualTo(BatchDispatcher.DispatchStatus.CLOSED);
    }

    @Test
    void stopInterruptsIdleBackoff() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        BatchDispatcher<String> dispatcher = BatchDispatcher.pulling(
                "adapter-1",
                "delivery-command",
                2,
                2,
                Duration.ofSeconds(10),
                () -> {
                    called.countDown();
                    return List.of();
                },
                batch -> BatchProcessResult.completed()
        );
        dispatcher.start();
        assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();

        stop(dispatcher);

        assertThat(dispatcher.isAlive()).isFalse();
    }

    @Test
    void errorEscapesAndClosesIngress() throws Exception {
        BatchDispatcher<String> dispatcher = pulling(
                () -> List.of("item"),
                batch -> {
                    throw new AssertionError("fatal");
                }
        );

        dispatcher.start();
        dispatcher.thread().join(2_000);

        assertThat(dispatcher.isAlive()).isFalse();
        assertThat(dispatcher.tryDispatch(List.of("late"))).isEqualTo(
                BatchDispatcher.DispatchStatus.CLOSED
        );
    }

    @Test
    void rejectsInvalidBounds() {
        assertThatThrownBy(() -> BatchDispatcher.pulling(
                "adapter-1",
                "delivery-command",
                0,
                1,
                Duration.ofMillis(1),
                List::of,
                batch -> BatchProcessResult.completed()
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchDispatcher.pulling(
                "adapter-1",
                "delivery-command",
                1,
                1,
                Duration.ZERO,
                List::of,
                batch -> BatchProcessResult.completed()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static BatchDispatcher<String> pulling(
            java.util.function.Supplier<List<String>> source,
            AdapterBatchProcessor<String> processor
    ) {
        return BatchDispatcher.pulling(
                "adapter-1",
                "delivery-command",
                8,
                4,
                Duration.ofMillis(10),
                source,
                processor
        );
    }

    private static void stop(BatchDispatcher<?> dispatcher)
            throws InterruptedException {
        dispatcher.stopIngress();
        dispatcher.stop();
        dispatcher.thread().join(2_000);
    }
}
