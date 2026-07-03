package com.xa.mass.engine.watchdog;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeReadyDispatchPumpTest {

    @Test
    void suppressesRepeatedIdlePollingUntilBackoffExpires() throws Exception {
        Task task = batchTask("task-1");
        AtomicInteger attempts = new AtomicInteger();
        RuntimeReadyDispatchPump pump = new RuntimeReadyDispatchPump(
                limit -> List.of(task),
                ignored -> {
                    attempts.incrementAndGet();
                    return false;
                },
                50L,
                10,
                500L,
                1_000L
        );

        try {
            pump.start();
            awaitAttempts(attempts, 1, 1_000L);
            Thread.sleep(220L);
            assertEquals(1, attempts.get());
        } finally {
            pump.stop();
        }
    }

    @Test
    void reattemptsAfterIdleBackoffExpires() throws Exception {
        Task task = batchTask("task-1");
        AtomicInteger attempts = new AtomicInteger();
        RuntimeReadyDispatchPump pump = new RuntimeReadyDispatchPump(
                limit -> List.of(task),
                ignored -> {
                    attempts.incrementAndGet();
                    return false;
                },
                50L,
                10,
                100L,
                1_000L
        );

        try {
            pump.start();
            awaitAttempts(attempts, 2, 1_000L);
            assertTrue(attempts.get() >= 2);
        } finally {
            pump.stop();
        }
    }

    @Test
    void usesConfiguredIdleBackoffPolicy() throws Exception {
        Task task = batchTask("task-1");
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger policyCalls = new AtomicInteger();
        RuntimeReadyDispatchPump pump = new RuntimeReadyDispatchPump(
                limit -> List.of(task),
                ignored -> {
                    attempts.incrementAndGet();
                    return false;
                },
                50L,
                10,
                1_000L,
                1_000L,
                decision -> {
                    policyCalls.incrementAndGet();
                    return 1_000L;
                }
        );

        try {
            pump.start();
            awaitAttempts(attempts, 1, 1_000L);
            Thread.sleep(160L);
            assertEquals(1, attempts.get());
            assertEquals(1, policyCalls.get());
        } finally {
            pump.stop();
        }
    }

    @Test
    void explicitWakeupClearsIdleAdmission() throws Exception {
        Task task = batchTask("task-1");
        AtomicInteger attempts = new AtomicInteger();
        RuntimeReadyDispatchPump pump = new RuntimeReadyDispatchPump(
                limit -> List.of(task),
                ignored -> {
                    attempts.incrementAndGet();
                    return false;
                },
                50L,
                10,
                1_000L,
                1_000L
        );

        try {
            pump.start();
            awaitAttempts(attempts, 1, 1_000L);
            Thread.sleep(160L);
            assertEquals(1, attempts.get());

            pump.wakeIdleAdmissions();

            awaitAttempts(attempts, 2, 400L);
        } finally {
            pump.stop();
        }
    }

    @Test
    void idleAdmissionIsPerPollingResource() throws Exception {
        Task first = batchTask("task-1");
        Task second = batchTask("task-2");
        CountDownLatch latch = new CountDownLatch(2);
        Set<String> attemptedTaskIds = ConcurrentHashMap.newKeySet();
        RuntimeReadyDispatchPump pump = new RuntimeReadyDispatchPump(
                limit -> List.of(first, second),
                task -> {
                    attemptedTaskIds.add(task.getTid());
                    latch.countDown();
                    return false;
                },
                50L,
                10,
                1_000L,
                1_000L
        );

        try {
            pump.start();
            assertTrue(latch.await(1, TimeUnit.SECONDS));
            assertEquals(Set.of("task-1", "task-2"), attemptedTaskIds);
        } finally {
            pump.stop();
        }
    }

    @Test
    void progressClearsIdleAdmission() throws Exception {
        Task task = batchTask("task-1");
        AtomicInteger attempts = new AtomicInteger();
        RuntimeReadyDispatchPump pump = new RuntimeReadyDispatchPump(
                limit -> List.of(task),
                ignored -> attempts.incrementAndGet() == 2,
                50L,
                10,
                1_000L,
                1_000L
        );

        try {
            pump.start();
            awaitAttempts(attempts, 1, 1_000L);
            Thread.sleep(160L);
            assertEquals(1, attempts.get());

            pump.wakeIdleAdmissions();

            awaitAttempts(attempts, 3, 600L);
        } finally {
            pump.stop();
        }
    }

    private static Task batchTask(String taskId) {
        Task task = new Task();
        task.setTid(taskId);
        task.setContract(TaskContract.BATCH);
        task.setStatus(TaskStatus.READY);
        return task;
    }

    private static void awaitAttempts(AtomicInteger attempts, int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (attempts.get() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(attempts.get() >= expected,
                "expected at least " + expected + " attempts but got " + attempts.get());
    }
}
