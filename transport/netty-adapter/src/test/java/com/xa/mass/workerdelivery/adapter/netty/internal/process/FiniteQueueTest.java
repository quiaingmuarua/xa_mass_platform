package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.FULL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FiniteQueueTest {

    @Test
    void acceptsOneBoundedBatchBeyondTheSoftCapacity() throws Exception {
        FiniteQueue<String> queue = new FiniteQueue<>(3);

        assertThat(queue.ingress(List.of("one", "two")))
                .isEqualTo(ACCEPTED);
        assertThat(queue.ingress(List.of("three", "four", "five")))
                .isEqualTo(ACCEPTED);
        assertThat(queue.ingress(List.of("six"))).isEqualTo(FULL);

        assertThat(queue.takeBatch(3))
                .containsExactly("one", "two", "three");
        assertThat(queue.ingress(List.of("six"))).isEqualTo(ACCEPTED);
        assertThat(queue.takeBatch(3))
                .containsExactly("four", "five", "six");
    }

    @Test
    void rejectsInvalidBoundsAndBatchValues() {
        assertThatThrownBy(() -> new FiniteQueue<>(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FiniteQueue<>(
                Integer.MAX_VALUE / 2 + 2
        )).isInstanceOf(IllegalArgumentException.class);

        FiniteQueue<String> queue = new FiniteQueue<>(2);
        assertThatThrownBy(() -> queue.takeBatch(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.ingress(List.of("1", "2", "3")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.ingress((List<String>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> queue.ingress(
                java.util.Arrays.asList("one", null)
        )).isInstanceOf(NullPointerException.class);
        assertThat(queue.ingress(List.of())).isEqualTo(ACCEPTED);
    }

    @Test
    void stopIngressRejectsNewBatchesWithoutOwningConsumerWakeup()
            throws Exception {
        FiniteQueue<String> queue = new FiniteQueue<>(2);
        queue.ingress(List.of("one", "two"));

        queue.stopIngress();
        queue.stopIngress();

        assertThat(queue.ingress(List.of("late"))).isEqualTo(CLOSED);
        assertThat(queue.ingress(List.of())).isEqualTo(CLOSED);
        assertThat(queue.takeBatch(2)).containsExactly("one", "two");
        queue.clear();
        assertThat(queue.ingress(List.of("still-late"))).isEqualTo(CLOSED);
    }

    @Test
    void takeBatchWaitsForFirstIngressAndWakesImmediately()
            throws Exception {
        FiniteQueue<String> queue = new FiniteQueue<>(3);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<List<String>> consumed = executor.submit(() -> {
                started.countDown();
                return queue.takeBatch(3);
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(consumed.isDone()).isFalse();

            assertThat(queue.ingress(List.of("one")))
                    .isEqualTo(ACCEPTED);

            assertThat(consumed.get(2, TimeUnit.SECONDS))
                    .containsExactly("one");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void takeBatchDrainsAvailableRemainderInFifoOrder() throws Exception {
        FiniteQueue<String> queue = new FiniteQueue<>(3);
        assertThat(queue.ingress(List.of("one", "two", "three")))
                .isEqualTo(ACCEPTED);

        assertThat(queue.takeBatch(3))
                .containsExactly("one", "two", "three");
    }

    @Test
    void takeBatchIsInterruptible() throws Exception {
        FiniteQueue<String> queue = new FiniteQueue<>(2);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread consumer = new Thread(() -> {
            try {
                queue.takeBatch(2);
            } catch (InterruptedException expected) {
                interrupted.set(true);
            }
        });
        consumer.start();

        awaitState(consumer, Thread.State.WAITING);
        consumer.interrupt();
        consumer.join(2_000);

        assertThat(consumer.isAlive()).isFalse();
        assertThat(interrupted).isTrue();
    }

    @Test
    void concurrentBatchIngressIsThreadSafe() throws Exception {
        int capacity = 16;
        FiniteQueue<Integer> queue = new FiniteQueue<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int value = 0; value < 64; value++) {
                int item = value;
                futures.add(executor.submit(() -> {
                    start.await();
                    if (queue.ingress(List.of(item)) == ACCEPTED) {
                        accepted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(capacity);
        assertThat(queue.takeBatch(capacity)).hasSize(capacity);
    }

    @Test
    void admitsAnExpiredTaskPairTogetherAtTheSoftBoundary()
            throws Exception {
        FiniteQueue<String> queue = new FiniteQueue<>(2);

        assertThat(queue.ingress(List.of("existing"))).isEqualTo(ACCEPTED);
        assertThat(queue.ingress(List.of("task-expired", "kernel-expired")))
                .isEqualTo(ACCEPTED);
        assertThat(queue.ingress(List.of("late"))).isEqualTo(FULL);

        assertThat(queue.takeBatch(3)).containsExactly(
                "existing",
                "task-expired",
                "kernel-expired"
        );
    }

    @Test
    void exposesOnlyBatchIngressAndNoQueueIdentity() {
        assertThat(List.of(FiniteQueue.class.getDeclaredMethods()))
                .filteredOn(method -> method.getName().equals("ingress"))
                .singleElement()
                .extracting(Method::getParameterTypes)
                .satisfies(parameters -> assertThat(parameters)
                        .containsExactly(List.class));
        assertThat(List.of(FiniteQueue.class.getDeclaredMethods()))
                .extracting(Method::getName)
                .doesNotContain("name", "key");
    }

    private static void awaitState(Thread thread, Thread.State expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline
                && thread.getState() != expected) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).isEqualTo(expected);
    }
}
