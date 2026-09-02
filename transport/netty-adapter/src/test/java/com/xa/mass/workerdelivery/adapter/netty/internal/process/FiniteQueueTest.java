package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.FULL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FiniteQueueTest {

    @Test
    void acceptsOneBoundedBatchBeyondTheSoftCapacity() {
        FiniteQueue<String> queue = new FiniteQueue<>(3);

        assertThat(queue.ingress(List.of("one", "two")))
                .isEqualTo(ACCEPTED);
        assertThat(queue.ingress(List.of("three", "four")))
                .isEqualTo(ACCEPTED);
        assertThat(queue.ingress(List.of("five"))).isEqualTo(FULL);

        assertThat(queue.consume(2)).containsExactly("one", "two");
        assertThat(queue.ingress(List.of("five"))).isEqualTo(ACCEPTED);
        assertThat(queue.consume(10))
                .containsExactly("three", "four", "five");
    }

    @Test
    void rejectsInvalidBoundsAndBatchValues() {
        assertThatThrownBy(() -> new FiniteQueue<>(0))
                .isInstanceOf(IllegalArgumentException.class);

        FiniteQueue<String> queue = new FiniteQueue<>(2);
        assertThatThrownBy(() -> queue.consume(0))
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
    void stopIngressStillAllowsDrainAndClearDoesNotReopen() {
        FiniteQueue<String> queue = new FiniteQueue<>(2);
        queue.ingress(List.of("one", "two"));

        queue.stopIngress();
        queue.stopIngress();

        assertThat(queue.ingress(List.of("late"))).isEqualTo(CLOSED);
        assertThat(queue.consume(1)).containsExactly("one");
        queue.clear();
        assertThat(queue.consume(2)).isEmpty();
        assertThat(queue.ingress(List.of("still-late"))).isEqualTo(CLOSED);
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
        assertThat(queue.consume(capacity)).hasSize(capacity);
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
}
