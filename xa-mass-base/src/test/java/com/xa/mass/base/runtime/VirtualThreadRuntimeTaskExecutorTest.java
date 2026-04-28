package com.xa.mass.base.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualThreadRuntimeTaskExecutorTest {

    @Test
    void runsTaskOnVirtualThread() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("test-runtime-vt-", 2);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicBoolean virtualThread = new AtomicBoolean();

        try {
            executor.submit(() -> {
                virtualThread.set(Thread.currentThread().isVirtual());
                handled.countDown();
            });

            assertTrue(handled.await(1, TimeUnit.SECONDS));
            assertTrue(virtualThread.get());
            assertEquals(1, executor.getStatistics().getSubmittedTasks());
            assertEquals(1, executor.getStatistics().getCompletedTasks());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectsWhenAdmissionLimitIsReached() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("test-runtime-vt-", 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            executor.submit(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> {}));
            assertEquals(1, executor.getStatistics().getRejectedTasks());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsAfterShutdown() {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("test-runtime-vt-", 1);
        executor.shutdown();

        assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> {}));
        assertEquals(1, executor.getStatistics().getRejectedTasks());
    }
}
