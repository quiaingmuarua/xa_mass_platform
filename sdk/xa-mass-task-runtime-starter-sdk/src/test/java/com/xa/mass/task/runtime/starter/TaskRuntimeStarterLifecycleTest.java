package com.xa.mass.task.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskRuntimeStarterLifecycleTest {

    @Test
    void loopHostStartStopIsIdempotentAndDoesNotLeakThreads() throws Exception {
        var threadFactory = new TrackingThreadFactory();
        var loop = new CountingLoop("repair-loop");

        try (var handle = TaskRuntimeStarter.start(
                TaskRuntimeBootstrapConfig.memory().withLoopIntervalMillis(5L),
                List.of(loop),
                threadFactory,
                () -> 100L)) {
            handle.start();
            assertThat(loop.awaitFirstRun()).isTrue();
            assertThat(handle.status().running()).isTrue();

            handle.stop();
            handle.stop();

            assertThat(threadFactory.awaitNoLiveThreads()).isTrue();
            assertThat(handle.status().running()).isFalse();

            handle.start();
            assertThat(handle.status().running()).isTrue();
            handle.stop();
            assertThat(threadFactory.awaitNoLiveThreads()).isTrue();
        }
    }

    @Test
    void loopHostAcceptsStarterLoopsRegisteredAfterRuntimeStart() throws Exception {
        var threadFactory = new TrackingThreadFactory();
        var loop = new CountingLoop("late-lease-repair-loop", 5L);

        try (var handle = TaskRuntimeStarter.start(
                TaskRuntimeBootstrapConfig.memory().withLoopIntervalMillis(1_000L),
                List.of(),
                threadFactory,
                () -> 100L)) {
            assertThat(handle.status().running()).isTrue();
            assertThat(handle.status().loopNames()).isEmpty();

            handle.registerLoops(List.of(loop));

            assertThat(handle.status().loopNames()).containsExactly("late-lease-repair-loop");
            assertThat(loop.awaitFirstRun()).isTrue();
        }
        assertThat(threadFactory.awaitNoLiveThreads()).isTrue();
    }

    @Test
    void duplicateLoopNamesAreRejectedBeforeThreadsStart() {
        var first = new CountingLoop("same-loop");
        var second = new CountingLoop("same-loop");

        assertThatThrownBy(() -> TaskRuntimeStarter.start(
                TaskRuntimeBootstrapConfig.memory(),
                List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate task runtime loop name");
    }

    private static final class CountingLoop implements TaskRuntimeLoop {

        private final String name;
        private final long intervalMillis;
        private final CountDownLatch firstRun = new CountDownLatch(1);
        private final AtomicInteger runs = new AtomicInteger();

        private CountingLoop(String name) {
            this(name, 0L);
        }

        private CountingLoop(String name, long intervalMillis) {
            this.name = name;
            this.intervalMillis = intervalMillis;
        }

        @Override
        public void runOnce(TaskRuntimeLoopContext context) {
            runs.incrementAndGet();
            firstRun.countDown();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long intervalMillis() {
            return intervalMillis;
        }

        private boolean awaitFirstRun() throws InterruptedException {
            return firstRun.await(2, TimeUnit.SECONDS) && runs.get() > 0;
        }
    }

    private static final class TrackingThreadFactory implements ThreadFactory {

        private final Set<Thread> liveThreads = ConcurrentHashMap.newKeySet();
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            var thread = new Thread(() -> {
                var current = Thread.currentThread();
                liveThreads.add(current);
                try {
                    task.run();
                } finally {
                    liveThreads.remove(current);
                }
            }, "task-runtime-test-loop-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }

        private boolean awaitNoLiveThreads() throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                if (liveThreads.isEmpty()) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return liveThreads.isEmpty();
        }
    }
}
