package com.xa.mass.client.worker.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRuntimeMaintenanceLoopTest {
    private ScheduledExecutorService executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void runsFixedDelayRuntimeMaintenanceTask() throws Exception {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "worker-runtime-maintenance-test");
            thread.setDaemon(true);
            return thread;
        });
        WorkerRuntimeMaintenanceLoop loop = new WorkerRuntimeMaintenanceLoop(executor);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        loop.addFixedDelayTask("heartbeat", Duration.ZERO, Duration.ofMillis(10), () -> {
            runs.incrementAndGet();
            ran.countDown();
        });
        loop.start();

        assertTrue(ran.await(2, TimeUnit.SECONDS), "maintenance task should run");
        assertTrue(runs.get() >= 1);
    }

    @Test
    void rejectsMutationAfterStart() {
        executor = Executors.newSingleThreadScheduledExecutor();
        WorkerRuntimeMaintenanceLoop loop = new WorkerRuntimeMaintenanceLoop(executor);
        loop.addFixedDelayTask("heartbeat", Duration.ZERO, Duration.ofSeconds(1), () -> {
        });
        loop.start();

        assertThrows(IllegalStateException.class,
                () -> loop.addFixedDelayTask("runtime-evidence", Duration.ZERO, Duration.ofSeconds(1), () -> {
                }));
    }

    @Test
    void validatesTaskTiming() {
        executor = Executors.newSingleThreadScheduledExecutor();
        WorkerRuntimeMaintenanceLoop loop = new WorkerRuntimeMaintenanceLoop(executor);

        assertThrows(IllegalArgumentException.class,
                () -> loop.addFixedDelayTask("heartbeat", Duration.ofMillis(-1), Duration.ofSeconds(1), () -> {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> loop.addFixedDelayTask("heartbeat", Duration.ZERO, Duration.ZERO, () -> {
                }));
    }

    @Test
    void repeatedStartDoesNotDuplicateSchedule() throws Exception {
        executor = Executors.newSingleThreadScheduledExecutor();
        WorkerRuntimeMaintenanceLoop loop = new WorkerRuntimeMaintenanceLoop(executor);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstRun = new CountDownLatch(1);

        loop.addFixedDelayTask("heartbeat", Duration.ZERO, Duration.ofSeconds(1), () -> {
            runs.incrementAndGet();
            firstRun.countDown();
        });
        loop.start();
        loop.start();

        assertTrue(firstRun.await(2, TimeUnit.SECONDS), "maintenance task should run");
        Thread.sleep(100L);
        assertEquals(1, runs.get(), "start() must not schedule duplicate first-run tasks");
    }
}
